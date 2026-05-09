from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from prometheus_fastapi_instrumentator import Instrumentator
from pydantic import ValidationError

from .config import get_settings
from .job_manager import JobManager
from .schemas import GenerationRequest, JobCreateResponse, JobState, QuestionType

settings = get_settings()
job_manager = JobManager(settings)


@asynccontextmanager
async def lifespan(_: FastAPI):
    await job_manager.start()
    try:
        yield
    finally:
        await job_manager.stop()


app = FastAPI(title=settings.app_name, lifespan=lifespan)

Instrumentator().instrument(app).expose(app)


def _parse_question_types(raw_value: str | None) -> list[QuestionType]:
    if not raw_value:
        return [QuestionType.single_choice]

    result: list[QuestionType] = []
    invalid_values: list[str] = []

    for item in raw_value.split(","):
        normalized = item.strip()
        if not normalized:
            continue
        lowered = normalized.lower().replace("-", "_")
        aliases = {
            "single_choice": QuestionType.single_choice,
            "multiple_choice": QuestionType.multiple_choice,
            "true_false": QuestionType.true_false,
            "100k1": QuestionType.q100k1,
            "q100k1": QuestionType.q100k1,
            "hundred_to_one": QuestionType.q100k1,
        }
        try:
            result.append(aliases.get(lowered, QuestionType(normalized)))
        except ValueError:
            invalid_values.append(normalized)

    if invalid_values:
        allowed = ", ".join(item.value for item in QuestionType)
        raise HTTPException(
            status_code=422,
            detail=(
                "Некорректные question_types: "
                f"{', '.join(invalid_values)}. Допустимые значения: {allowed}"
            ),
        )

    return result or [QuestionType.single_choice]


async def _extract_uploaded_files(request: Request) -> list[UploadFile]:
    form = await request.form()
    files: list[UploadFile] = []
    for item in form.getlist("files"):
        if getattr(item, "filename", None):
            files.append(item)
    return files


@app.get("/health")
async def health() -> dict:
    return {"status": "ok", "app": settings.app_name}


@app.get("/config")
async def config() -> dict:
    return job_manager.config_snapshot()


@app.get("/check-ethics/{prompt}")
async def check_ethics(prompt: str) -> dict:
    try:
        client = job_manager._get_client()
        unethical = await client.check_prompt_ethics(prompt)
        return {"unethical": unethical}
    except Exception as exc:  # noqa: BLE001
        # Не валим backend-пайплайн генерации при падении модерации.
        return {"unethical": False, "error": str(exc)}


@app.post("/jobs/generate", response_model=JobCreateResponse)
async def create_generation_job(
    topic: str = Form(...),
    number: int = Form(...),
    question_types: str | None = Form(default="single_choice"),
    files: list[UploadFile] = File(default=[]),
) -> JobCreateResponse:
    try:
        request = GenerationRequest(
            topic=topic,
            number=number,
            question_types=_parse_question_types(question_types),
        )
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail=exc.errors()) from exc
    job = await job_manager.create_job(request=request, files=files)
    return JobCreateResponse(job_id=job.id, status=job.status, created_at=job.created_at)


@app.get("/jobs/{job_id}", response_model=JobState)
async def get_job(job_id: str) -> JobState:
    return job_manager.get_job(job_id)


@app.post("/generate", response_model=JobState)
async def generate(
    topic: str = Form(...),
    number: int = Form(...),
    question_types: str | None = Form(default="single_choice"),
    files: list[UploadFile] = File(default=[]),
) -> JobState:
    try:
        request = GenerationRequest(
            topic=topic,
            number=number,
            question_types=_parse_question_types(question_types),
        )
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail=exc.errors()) from exc
    return await job_manager.generate_now(request=request, files=files)


@app.get("/question/{prompt}/{number}")
async def legacy_generate(prompt: str, number: int) -> dict:
    try:
        request = GenerationRequest(topic=prompt, number=number)
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail=exc.errors()) from exc
    job = await job_manager.generate_now(request=request, files=[])
    return {
        "job_id": job.id,
        "status": job.status,
        "result": job.result.parsed_response or job.result.raw_response,
        "errors": job.errors,
    }
