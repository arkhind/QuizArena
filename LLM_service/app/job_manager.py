from __future__ import annotations

import asyncio
import traceback
from datetime import datetime
from pathlib import Path
from typing import Iterable

import aiofiles
from fastapi import HTTPException, UploadFile

from .config import Settings
from .llm_client import LLMClient
from .schemas import GenerationRequest, JobState, JobStatus

CHUNK_SIZE = 1024 * 1024


class JobManager:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.jobs: dict[str, JobState] = {}
        self.queue: asyncio.Queue[str] = asyncio.Queue()
        self.worker_tasks: list[asyncio.Task[None]] = []
        self.llm_client: LLMClient | None = None
        self._started = False

    async def start(self) -> None:
        if self._started:
            return

        self.settings.resolved_storage_dir.mkdir(parents=True, exist_ok=True)
        self.worker_tasks = [
            asyncio.create_task(self._worker_loop(index), name=f"job-worker-{index}")
            for index in range(self.settings.worker_count)
        ]
        self._started = True

    async def stop(self) -> None:
        for task in self.worker_tasks:
            task.cancel()

        if self.worker_tasks:
            await asyncio.gather(*self.worker_tasks, return_exceptions=True)

        if self.llm_client is not None:
            await self.llm_client.close()
            self.llm_client = None

        self.worker_tasks.clear()
        self._started = False

    async def create_job(self, request: GenerationRequest, files: list[UploadFile]) -> JobState:
        self._ensure_api_key()

        job = JobState(request=request)
        job_dir = self._job_dir(job.id)
        inputs_dir = job_dir / "inputs"
        inputs_dir.mkdir(parents=True, exist_ok=True)

        saved_files = await self._save_files(inputs_dir=inputs_dir, files=files)
        job.files = [str(path) for path in saved_files]
        job.add_log("Задание создано и поставлено в очередь")

        self.jobs[job.id] = job
        await self.queue.put(job.id)
        return job

    async def generate_now(self, request: GenerationRequest, files: list[UploadFile]) -> JobState:
        self._ensure_api_key()

        job = JobState(
            request=request,
            status=JobStatus.running,
            started_at=datetime.utcnow(),
        )
        job_dir = self._job_dir(job.id)
        inputs_dir = job_dir / "inputs"
        inputs_dir.mkdir(parents=True, exist_ok=True)

        saved_files = await self._save_files(inputs_dir=inputs_dir, files=files)
        job.files = [str(path) for path in saved_files]

        self.jobs[job.id] = job
        await self._process_job(job.id)
        return self.jobs[job.id]

    def get_job(self, job_id: str) -> JobState:
        job = self.jobs.get(job_id)
        if job is None:
            raise HTTPException(status_code=404, detail="Задача не найдена")
        return job

    def config_snapshot(self) -> dict:
        return {
            "model_name": self.settings.model_name,
            "api_base_url": self.settings.api_base_url,
            "storage_dir": str(self.settings.resolved_storage_dir),
            "max_upload_size_mb": self.settings.max_upload_size_mb,
            "max_parallel_llm_calls": self.settings.max_parallel_llm_calls,
            "worker_count": self.settings.worker_count,
            "queue_size": self.queue.qsize(),
            "jobs_total": len(self.jobs),
        }

    async def _worker_loop(self, worker_index: int) -> None:
        while True:
            job_id = await self.queue.get()
            try:
                job = self.jobs.get(job_id)
                if job is not None:
                    job.add_log(f"Задание взял worker #{worker_index}")
                await self._process_job(job_id)
            finally:
                self.queue.task_done()

    async def _process_job(self, job_id: str) -> None:
        job = self.jobs[job_id]
        job.status = JobStatus.running
        if job.started_at is None:
            job.started_at = datetime.utcnow()
        job.add_log("Начата обработка задания")

        try:
            client = self._get_client()
            raw_text, parsed = await client.generate_questions(
                request=job.request,
                file_paths=[Path(path) for path in job.files],
            )
            job.result.raw_response = raw_text
            job.result.parsed_response = parsed
            job.status = JobStatus.finished
            job.finished_at = datetime.utcnow()

            if parsed is None:
                job.errors.append("Ответ модели не удалось распарсить как JSON")
                job.add_log("Ответ получен, но JSON не распарсился")
            else:
                job.add_log("Ответ успешно получен и распарсен")

            await self._persist_job(job)
        except Exception as exc:  # noqa: BLE001
            job.status = JobStatus.failed
            job.finished_at = datetime.utcnow()
            job.errors.append(str(exc))
            job.add_log(f"Ошибка: {exc}")
            await self._persist_failure(job, traceback.format_exc())

    async def _persist_job(self, job: JobState) -> None:
        job_dir = self._job_dir(job.id)
        async with aiofiles.open(job_dir / "job_state.json", "w", encoding="utf-8") as file:
            await file.write(job.model_dump_json(indent=2))

    async def _persist_failure(self, job: JobState, tb: str) -> None:
        job_dir = self._job_dir(job.id)
        async with aiofiles.open(job_dir / "traceback.txt", "w", encoding="utf-8") as file:
            await file.write(tb)
        await self._persist_job(job)

    async def _save_files(self, inputs_dir: Path, files: Iterable[UploadFile]) -> list[Path]:
        saved: list[Path] = []

        for upload in files:
            if not isinstance(upload, UploadFile):
                continue
            if not upload.filename:
                continue

            suffix = Path(upload.filename).suffix.lower()
            if suffix != ".pdf" and not self.settings.allow_non_pdf_files:
                raise HTTPException(
                    status_code=400,
                    detail=f"Поддерживаются только PDF-файлы: {upload.filename}",
                )

            destination = inputs_dir / Path(upload.filename).name
            written = 0

            try:
                async with aiofiles.open(destination, "wb") as out:
                    while True:
                        chunk = await upload.read(CHUNK_SIZE)
                        if not chunk:
                            break

                        written += len(chunk)
                        if written > self.settings.max_upload_size_bytes:
                            raise HTTPException(
                                status_code=413,
                                detail=(
                                    f"Файл {upload.filename} превышает лимит "
                                    f"{self.settings.max_upload_size_mb} MB"
                                ),
                            )

                        await out.write(chunk)
            except Exception:
                if destination.exists():
                    destination.unlink(missing_ok=True)
                raise
            finally:
                await upload.close()

            saved.append(destination)

        return saved

    def _job_dir(self, job_id: str) -> Path:
        return self.settings.resolved_storage_dir / job_id

    def _ensure_api_key(self) -> None:
        if not self.settings.api_key:
            raise HTTPException(status_code=500, detail="OPENAI_API_KEY не задан")

    def _get_client(self) -> LLMClient:
        self._ensure_api_key()
        if self.llm_client is None:
            self.llm_client = LLMClient(self.settings)
        return self.llm_client
