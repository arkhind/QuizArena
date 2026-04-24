from fastapi import FastAPI, File, UploadFile
from fastapi.responses import JSONResponse
from prometheus_fastapi_instrumentator import Instrumentator

import shutil
import os
import LLM_functions

app = FastAPI()

Instrumentator().instrument(app).expose(app)

UPLOAD_DIRECTORY = "docs"

@app.post("/uploadfile/")
async def create_upload_file(file: UploadFile = File(...)):
    """
    Принимает загруженный файл и сохраняет его в папку 'docs'.
    """

    # Создаем директорию, если ее нет
    if not os.path.exists(UPLOAD_DIRECTORY):
        os.makedirs(UPLOAD_DIRECTORY)

    file_path = os.path.join(UPLOAD_DIRECTORY, file.filename)

    # Сохраняем файл
    try:
        with open(file_path, "wb") as buffer:
            # Читаем файл порциями и записываем в буфер
            # shutil.copyfileobj - более эффективный способ для больших файлов
            shutil.copyfileobj(file.file, buffer)
    except Exception as e:
        return {"message": f"Произошла ошибка при сохранении файла: {e}"}
    finally:
        # Важно: закрываем файл после использования (shutil.copyfileobj это делает)
        await file.close()


@app.get("/check-ethics/{prompt}")
async def check_ethics(prompt: str):
    """
    Проверяет этичность промпта ДО генерации вопросов.
    Возвращает true, если промпт неэтичен (не прошел проверку).
    """
    try:
        is_unethical = LLM_functions.PromptVibeCheck(prompt)
        return JSONResponse(content={"unethical": is_unethical})
    except Exception as e:
        return JSONResponse(
            status_code=500,
            content={"error": "CHECK_ERROR", "message": f"Ошибка при проверке этичности: {str(e)}"}
        )

@app.get("/question/{prompt}/{number}")
async def root(prompt : str, number : int):
    try:
        Result = LLM_functions.ChatResponse(prompt, number)
        if os.path.exists("docs"):
            shutil.rmtree("docs")
        return JSONResponse(content={"questions": Result})
    except ValueError as e:
        if str(e) == "UNETHICAL_PROMPT":
            if os.path.exists("docs"):
                shutil.rmtree("docs")
            return JSONResponse(
                status_code=400,
                content={"error": "UNETHICAL_PROMPT", "message": "Данные квиза являются неэтичными"}
            )
        else:
            if os.path.exists("docs"):
                shutil.rmtree("docs")
            return JSONResponse(
                status_code=500,
                content={"error": "GENERATION_ERROR", "message": str(e)}
            )
    except Exception as e:
        if os.path.exists("docs"):
            shutil.rmtree("docs")
        return JSONResponse(
            status_code=500,
            content={"error": "GENERATION_ERROR", "message": f"Ошибка при генерации вопросов: {str(e)}"}
        )
#http://127.0.0.1:8000/Математический анализ/4
'''
fastapi dev main.py
'''
