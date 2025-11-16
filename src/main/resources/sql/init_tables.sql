CREATE TABLE "User"(
    "id" BIGINT NOT NULL PRIMARY KEY,
    "login" VARCHAR(32) NOT NULL UNIQUE,
    "password" VARCHAR(64) NOT NULL
);

CREATE TABLE "Quiz"(
    "id" BIGINT NOT NULL PRIMARY KEY,
    "name" VARCHAR(64) NOT NULL,
    "prompt" VARCHAR(500) NOT NULL,
    "create_by" BIGINT NOT NULL,
    "has_material" BOOLEAN NOT NULL,
    "material_url" VARCHAR(255),
    "time_per_question_seconds" INTEGER,
    "is_private" BOOLEAN NOT NULL,
    "is_static" BOOLEAN NOT NULL,
    "created_at" TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT "quiz_create_by_foreign" FOREIGN KEY("create_by") REFERENCES "User"("id")
);

CREATE TABLE "Question"(
    "id" BIGINT NOT NULL PRIMARY KEY,
    "quiz_id" BIGINT NOT NULL,
    "text" VARCHAR(300) NOT NULL,
    "type" VARCHAR(32) NOT NULL,
    "explanation" VARCHAR(255) NOT NULL,
    "image" BYTEA,
    CONSTRAINT "question_quiz_id_foreign" FOREIGN KEY("quiz_id") REFERENCES "Quiz"("id")
);

CREATE TABLE "AnswerOption"(
    "id" BIGINT NOT NULL PRIMARY KEY,
    "question_id" BIGINT NOT NULL,
    "text" VARCHAR(100) NOT NULL,
    "is_correct" BOOLEAN NOT NULL,
    "is_na_option" BOOLEAN NOT NULL,
    CONSTRAINT "answeroption_question_id_foreign" FOREIGN KEY("question_id") REFERENCES "Question"("id")
);

CREATE TABLE "UserQuizAttempt"(
    "id" BIGINT NOT NULL PRIMARY KEY,
    "user_id" BIGINT NOT NULL,
    "quiz_id" BIGINT NOT NULL,
    "start_time" TIMESTAMP WITH TIME ZONE NOT NULL,
    "finish_time" TIMESTAMP WITH TIME ZONE NOT NULL,
    "score" BIGINT NOT NULL,
    "is_completed" BOOLEAN NOT NULL,
    CONSTRAINT "userquizattempt_user_id_foreign" FOREIGN KEY("user_id") REFERENCES "User"("id"),
    CONSTRAINT "userquizattempt_quiz_id_foreign" FOREIGN KEY("quiz_id") REFERENCES "Quiz"("id")
);
