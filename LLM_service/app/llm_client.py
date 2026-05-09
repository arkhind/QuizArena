from __future__ import annotations

import asyncio
import base64
import mimetypes
import re
import zipfile
from pathlib import Path
from typing import Any

from openai import AsyncOpenAI
from pypdf import PdfReader

from .config import Settings
from .prompts import SYSTEM_PROMPT, build_user_prompt
from .schemas import GenerationRequest, QuestionSet
from .utils import normalize_message_content, try_parse_json


class LLMClient:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        if not self.settings.api_key:
            raise RuntimeError(
                "Не задан OPENAI_API_KEY. Добавь ключ в файл .env "
                "или в переменные окружения."
            )

        client_kwargs: dict[str, Any] = {
            "api_key": self.settings.api_key,
            "timeout": self.settings.request_timeout_seconds,
        }
        if self.settings.api_base_url:
            client_kwargs["base_url"] = self.settings.api_base_url

        self.client = AsyncOpenAI(**client_kwargs)
        self._semaphore = asyncio.Semaphore(self.settings.max_parallel_llm_calls)

    async def close(self) -> None:
        close = getattr(self.client, "close", None)
        if callable(close):
            await close()

    async def generate_questions(
        self,
        *,
        request: GenerationRequest,
        file_paths: list[Path] | None = None,
    ) -> tuple[str, dict[str, Any] | None]:
        file_paths = file_paths or []

        async with self._semaphore:
            try:
                return await self._generate_via_responses(request=request, file_paths=file_paths)
            except Exception as exc:  # noqa: BLE001
                if file_paths:
                    return await self._generate_via_chat_completions(
                        request=request,
                        file_paths=file_paths,
                    )
                return await self._generate_via_chat_completions(request=request, file_paths=[])

    async def check_prompt_ethics(self, prompt: str) -> bool:
        text = (prompt or "").strip()
        if not text:
            return False

        system = (
            "Ты модератор учебного контента. Верни только JSON: "
            '{"unethical": true|false, "reason": "..."}'
        )
        user = (
            "Проверь, нарушает ли запрос правила безопасности: насилие, ненависть, "
            "призывы к вреду, криминальные инструкции, сексуальный контент с несовершеннолетними. "
            "Если запрос безопасен для учебного квиза, верни unethical=false.\n"
            f"Запрос: {text}"
        )

        async def _ask(model_name: str) -> str:
            payload: dict[str, Any] = {
                "model": model_name,
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ],
                "response_format": {"type": "json_object"},
            }
            async with self._semaphore:
                response = await self.client.chat.completions.create(**payload)
            return self._extract_chat_text(response)

        try:
            raw_text = await _ask(self.settings.ethics_model_name)
        except Exception:
            raw_text = await _ask(self.settings.model_name)

        parsed = try_parse_json(raw_text)
        if isinstance(parsed, dict):
            return bool(parsed.get("unethical", False))
        lower = raw_text.lower()
        return "true" in lower and "unethical" in lower

    async def _generate_via_responses(
        self,
        *,
        request: GenerationRequest,
        file_paths: list[Path],
    ) -> tuple[str, dict[str, Any] | None]:
        content: list[dict[str, Any]] = []
        for path in file_paths:
            content.append(
                {
                    "type": "input_file",
                    "filename": path.name,
                    "file_data": self.file_to_data_url(path),
                }
            )

        extracted_text = self._build_extracted_text_block(file_paths)
        if extracted_text:
            content.append({"type": "input_text", "text": extracted_text})

        content.append(
            {
                "type": "input_text",
                "text": build_user_prompt(request=request, has_files=bool(file_paths)),
            }
        )

        payload: dict[str, Any] = {
            "model": self.settings.model_name,
            "instructions": SYSTEM_PROMPT,
            "input": [{"role": "user", "content": content}],
            "store": False,
        }

        if self.settings.enable_reasoning:
            payload["reasoning"] = {"effort": self.settings.reasoning_effort}

        if self.settings.force_json_response:
            payload["text"] = {
                "format": {
                    "type": "json_schema",
                    "name": "question_set",
                    "strict": True,
                    "schema": QuestionSet.model_json_schema(),
                }
            }

        response = await self.client.responses.create(**payload)
        raw_text = self._extract_responses_text(response)
        return raw_text, try_parse_json(raw_text)

    async def _generate_via_chat_completions(
        self,
        *,
        request: GenerationRequest,
        file_paths: list[Path],
    ) -> tuple[str, dict[str, Any] | None]:
        user_content = build_user_prompt(request=request, has_files=bool(file_paths))
        extracted_text = self._build_extracted_text_block(file_paths)
        if extracted_text:
            user_content = extracted_text + "\n\n" + user_content

        payload: dict[str, Any] = {
            "model": self.settings.model_name,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": user_content,
                },
            ],
        }

        if self.settings.force_json_response:
            payload["response_format"] = {"type": "json_object"}

        response = await self.client.chat.completions.create(**payload)
        raw_text = self._extract_chat_text(response)
        return raw_text, try_parse_json(raw_text)

    def _extract_responses_text(self, response: Any) -> str:
        output_text = getattr(response, "output_text", None)
        if isinstance(output_text, str) and output_text.strip():
            return output_text.strip()

        parts: list[str] = []
        for item in getattr(response, "output", []) or []:
            if getattr(item, "type", None) != "message":
                continue
            for content_item in getattr(item, "content", []) or []:
                if getattr(content_item, "type", None) == "output_text":
                    text = getattr(content_item, "text", None)
                    if text:
                        parts.append(str(text))
                else:
                    text = getattr(content_item, "text", None)
                    if text:
                        parts.append(str(text))

        return "\n".join(part.strip() for part in parts if part).strip()

    def _extract_chat_text(self, response: Any) -> str:
        try:
            content = response.choices[0].message.content
        except Exception:  # noqa: BLE001
            return ""
        return normalize_message_content(content).strip()

    def _build_extracted_text_block(self, file_paths: list[Path]) -> str:
        snippets: list[str] = []
        for path in file_paths:
            text = self._extract_plain_text(path)
            if text:
                snippets.append(f"Файл {path.name}:\n{text}")
        if not snippets:
            return ""
        return (
            "Ниже машинно извлеченный текст из прикрепленных файлов. "
            "Используй его как дополнительный источник вместе с самими файлами:\n\n"
            + "\n\n".join(snippets)
        )

    def _extract_plain_text(self, path: Path) -> str:
        suffix = path.suffix.lower()
        try:
            if suffix == ".txt":
                return self._compact_text(path.read_text(encoding="utf-8", errors="ignore"))
            if suffix == ".docx":
                return self._extract_docx_text(path)
            if suffix == ".pdf":
                return self._extract_pdf_text(path)
        except Exception:
            return ""
        return ""

    def _extract_docx_text(self, path: Path) -> str:
        with zipfile.ZipFile(path) as archive:
            xml = archive.read("word/document.xml").decode("utf-8", errors="ignore")
        text = re.sub(r"<[^>]+>", " ", xml)
        return self._compact_text(text)

    def _extract_pdf_text(self, path: Path) -> str:
        reader = PdfReader(str(path))
        pages: list[str] = []
        for index, page in enumerate(reader.pages[:20], start=1):
            text = page.extract_text() or ""
            if text.strip():
                pages.append(f"Страница {index}:\n{text}")
        return self._compact_text("\n\n".join(pages))

    def _compact_text(self, text: str) -> str:
        compact = re.sub(r"\s+", " ", text).strip()
        return compact[:12000]

    @staticmethod
    def file_to_data_url(path: Path) -> str:
        raw = path.read_bytes()
        encoded = base64.b64encode(raw).decode("utf-8")
        mime_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        return f"data:{mime_type};base64,{encoded}"
