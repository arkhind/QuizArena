def PromptVibeCheck(prompt):
    response: ChatResponse = chat(model='gpt-oss:20b-cloud', messages=[
      {
        'role': 'user',
        'content': ("Определи, является ли данная тема приемлемой с моральной и этической точки зрения, т.е. не нарушает ли её обсуждение законодотельство или общие моральные принципы\n"
"Тема: " + prompt + "/nВ качестве ответа выведи только да, если тема приемлима, иначе выведи только нет"),
      },
    ])
    return (response['message']['content'] == "нет")
