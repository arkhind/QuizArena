from fastapi import FastAPI
from fastapi.responses import JSONResponse

import LLM_functions

app = FastAPI()

@app.get("/question/{prompt}/{number}")
async def root(prompt : str, number : int):
    print(f"=== ЭТАП 5: FastAPI endpoint /question/{prompt}/{number} - Начало ===")
    print(f"ЭТАП 5.1: Получены параметры - prompt='{prompt}', number={number}")
    
    print("ЭТАП 5.2: Вызов LLM_functions.ChatResponse()")
    result = LLM_functions.ChatResponse(prompt, number)
    print(f"ЭТАП 5.2 ЗАВЕРШЕН: Получен ответ от LLM, длина: {len(result)} символов")
    print(f"ЭТАП 5.2: Первые 200 символов ответа: {result[:200]}")
    
    print("ЭТАП 5.3: Возврат ответа как JSON")
    print("=== ЭТАП 5 ЗАВЕРШЕН: FastAPI endpoint ===")
    return JSONResponse(content={"questions": result})

'''
fastapi dev main.py
'''
