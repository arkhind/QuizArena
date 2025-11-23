# TODO: написать нормальный README
-----------------------------------------------------
# Проект Quiz AI Arena (команда 15)

> Веб-платформа, позволяющая пользователям автоматически 
> генерировать квизы на основе выбранной темы или предоставленного
> материала. Сервис использует интеллектуальные алгоритмы для
> формирования вопросов и ответов, после чего пользователь
> может пройти созданный квиз и увидеть результат

## Запуск
- Открыть Docker Desktop
- Из консоли в директории проекта выполнить команду
```bash
docker compose up -d
```
- Нажать кнопочку Run

## Требования

- Python 3.8+
- Ollama
- FastAPI

## Установка

### 1. Установка Ollama

**macOS:**
```bash
brew install ollama
```

**Linux:**
```bash
curl -fsSL https://ollama.com/install.sh | sh
```

**Windows:**
Скачайте установщик с [ollama.com](https://ollama.com)

### 2. Запуск Ollama сервиса

```bash
brew services start ollama
```

Или вручную:
```bash
ollama serve
```

### 3. Загрузка модели

```bash
ollama pull qwen3:8b
```

### 4. Установка Python зависимостей

```bash
cd python
pip install -r requirements.txt
```

## Запуск сервера

```bash
cd python
fastapi dev main.py
```

Сервер запустится на `http://127.0.0.1:8000`


## Использование API

### Эндпоинт

```
GET /question/{prompt}/{number}
```

**Параметры:**
- `prompt` (string) - тема для генерации вопросов (например: "математика", "история")
- `number` (int) - количество вопросов для генерации

### Примеры запросов

**Через браузер:**
```
http://127.0.0.1:8000/question/математика/5
```

**Через curl:**
```bash
curl http://127.0.0.1:8000/question/математика/5
```

**Через Java:**
```java
import org.example.service.ApiService;
import java.io.IOException;

public class Example {
    public static void main(String[] args) throws IOException, InterruptedException {
        ApiService apiService = new ApiService();
        String response = apiService.getQuestionsByPrompt("математика", 5);
        System.out.println(response);
    }
}
```

**Через JavaScript (fetch):**
```javascript
fetch('http://127.0.0.1:8000/question/математика/5')
  .then(response => response.text())
  .then(data => console.log(data));
```

### Формат ответа

API возвращает строку в следующем формате:

```
*Вопрос*
1) Вариант ответа 1
2) Вариант ответа 2
3) Вариант ответа 3
4) Вариант ответа 4
*правильный ответ*
Номер правильного ответа
*объяснение*
Текст объяснения
*Следующий вопрос*

*Вопрос*
...
```

## Примеры тем

- `математика`
- `история`
- `география`
- `литература`
- `физика`
- и т.д.

## Troubleshooting

**Ошибка подключения к Ollama:**
- Убедитесь, что Ollama сервис запущен: `brew services list | grep ollama`
- Проверьте, что модель загружена: `ollama list`

**Ошибка порта 8000 занят:**
- Измените порт в команде запуска: `uvicorn main:app --port 8001`

**Модель не найдена:**
- Загрузите модель: `ollama pull qwen3:8b`

## Документы

- [Функциональные и нефункциональные требования](https://docs.google.com/document/d/1iCJUvyteMbJYmxocf2DZl811XCzPHpy2WGHVnMrF6eQ/edit?tab=t.0)
- [User story](https://docs.google.com/document/d/11rnLCAsbskH97Sg7dkK7zSCnAP9_jqC_lLmW3XKxXiU/edit?hl=RU&tab=t.0)
- [Прототип UI](https://app.diagrams.net/?src=about#G1W5PkT0oa1OsAObiy0Fdg7VpnIeO6Zh_1%23%7B%22pageId%22%3A%22v4vXsFOn4bkPqltqCtXt%22%7D)
- [Схема БД](https://drawsql.app/teams/mfti/diagrams/quizarena-2)

## Авторы
### Ментор
- Александр Бобряков - @AlexanderBobryakov

### Команда
- Архипов Никита - @arkhind
- Климанов Илья - @Rafl214
- Соль Михаил - @sol-m-07
- Сударкин Георгий - @SudarkinG
- Чапурина Валерия - @Artyy-l
