# Запуск QuizArena на удалённом сервере

Проект теперь можно поднять одной командой через Docker Compose: Java-приложение, LLM-сервис, PostgreSQL, Redis, Kafka, Prometheus и Grafana запускаются в одной сети контейнеров.

## Требования

- Linux-сервер с Docker и Docker Compose plugin.
- Открытый порт приложения, по умолчанию `8081`.
- API key для OpenAI-compatible LLM backend.

## Первый запуск

```bash
git clone <repo-url> QuizArena
cd QuizArena
cp .env.example .env
nano .env
docker compose up -d --build
```

Обязательно поменяйте в `.env`:

- `POSTGRES_PASSWORD`
- `QUIZARENA_JWT_SECRET` (`openssl rand -base64 32`)
- `OPENAI_API_KEY`
- `GF_SECURITY_ADMIN_PASSWORD`
- `QUIZARENA_CORS_ALLOWED_ORIGINS`, если приложение открывается через домен

После запуска:

```bash
docker compose ps
docker compose logs -f app
```

Приложение будет доступно на `http://<server-ip>:8081`.

## Полезные команды

```bash
docker compose up -d --build     # пересобрать и запустить
docker compose logs -f app       # логи Java-приложения
docker compose logs -f llm-service
docker compose restart app
docker compose down
```

Данные PostgreSQL, загрузки приложения, состояние LLM-сервиса, Kafka, Prometheus и Grafana хранятся в Docker volumes и переживают рестарт контейнеров.

## Порты

По умолчанию наружу открыт только порт приложения:

- `APP_PORT=8081` на `0.0.0.0`

Остальные сервисы привязаны к `127.0.0.1`, чтобы их было удобно смотреть через SSH tunnel и не открывать всему интернету:

- PostgreSQL: `POSTGRES_PORT=5433`
- Redis: `REDIS_PORT=6380`
- Kafka: `KAFKA_HOST_PORT=9092`
- LLM service: `LLM_PORT=8000`
- Prometheus: `PROMETHEUS_PORT=9090`
- Grafana: `GRAFANA_PORT=3000`

Если нужно открыть Grafana наружу, поменяйте `GRAFANA_BIND=0.0.0.0` и используйте сильный пароль.

## Reverse proxy

Для домена обычно ставят Nginx или Caddy перед приложением и проксируют на `http://127.0.0.1:8081`. В этом случае добавьте домен в:

```env
QUIZARENA_CORS_ALLOWED_ORIGINS=https://quiz.example.com
```
