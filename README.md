# QuizArena

Проект для генерации вопросов для викторин с использованием LLM модели через FastAPI.

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

