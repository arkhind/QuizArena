from fastapi import FastAPI, File, UploadFile

import shutil
import os
import LLM_functions

app = FastAPI()

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


@app.get("/question/{prompt}/{number}")
async def root(prompt : str, number : int):
    Result = LLM_functions.ChatResponse(prompt, number)
    if os.path.exists("docs"):
        shutil.rmtree("docs")
    return Result
#http://127.0.0.1:8000/Математический анализ/4
'''
fastapi dev main.py
'''
