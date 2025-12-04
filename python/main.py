from fastapi import FastAPI

import LLM_functions

app = FastAPI()

@app.get("/question/{prompt}/{number}")
async def root(prompt : str, number : int):
    return LLM_functions.ChatResponse(prompt, number)

'''
fastapi dev main.py
'''
