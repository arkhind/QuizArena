CREATE TABLE "User"(
    "id" BIGINT NOT NULL,
    "login" CHAR(255) NOT NULL,
    "password" CHAR(255) NOT NULL
);
ALTER TABLE
    "User" ADD PRIMARY KEY("id");

CREATE TABLE "Quiz"(
    "id" BIGINT NOT NULL,
    "name" CHAR(255) NOT NULL,
    "prompt" CHAR(255) NOT NULL,
    "create_by" BIGINT NOT NULL,
    "has_material" BOOLEAN NOT NULL,
    "material_url" CHAR(255) NOT NULL,
    "question_number" BIGINT NOT NULL,
    "time" BIGINT NOT NULL,
    "is_private" BOOLEAN NOT NULL,
    "is_static" BOOLEAN NOT NULL,
    "private_code" CHAR(255) NOT NULL,
    "created_at" TIMESTAMP(0) WITHOUT TIME ZONE NOT NULL
);
ALTER TABLE
    "Quiz" ADD PRIMARY KEY("id");

CREATE TABLE "Question"(
    "id" BIGINT NOT NULL,
    "quiz_id" BIGINT NOT NULL,
    "text" CHAR(255) NOT NULL,
    "type" CHAR(255) NOT NULL,
    "explanation" CHAR(255) NOT NULL,
    "image_url" CHAR(255) NOT NULL
);
ALTER TABLE
    "Question" ADD PRIMARY KEY("id");

CREATE TABLE "AnswerOption"(
    "id" BIGINT NOT NULL,
    "question_id" BIGINT NOT NULL,
    "text" CHAR(255) NOT NULL,
    "is_correct" BOOLEAN NOT NULL,
    "is_na_option" BOOLEAN NOT NULL
);
ALTER TABLE
    "AnswerOption" ADD PRIMARY KEY("id");

CREATE TABLE "UserQuizAttempt"(
    "id" BIGINT NOT NULL,
    "user_id" BIGINT NOT NULL,
    "quiz_id" BIGINT NOT NULL,
    "start_time" BIGINT NOT NULL,
    "finish_time" BIGINT NOT NULL,
    "score" BIGINT NOT NULL,
    "is_completed" BIGINT NOT NULL
);
ALTER TABLE
    "UserQuizAttempt" ADD PRIMARY KEY("id");

-- Foreign Key Constraints
ALTER TABLE
    "UserQuizAttempt" ADD CONSTRAINT "userquizattempt_user_id_foreign" FOREIGN KEY("user_id") REFERENCES "User"("id");
ALTER TABLE
    "Quiz" ADD CONSTRAINT "quiz_create_by_foreign" FOREIGN KEY("create_by") REFERENCES "User"("id");
ALTER TABLE
    "Question" ADD CONSTRAINT "question_quiz_id_foreign" FOREIGN KEY("quiz_id") REFERENCES "Quiz"("id");
ALTER TABLE
    "UserQuizAttempt" ADD CONSTRAINT "userquizattempt_quiz_id_foreign" FOREIGN KEY("quiz_id") REFERENCES "Quiz"("id");
ALTER TABLE
    "AnswerOption" ADD CONSTRAINT "answeroption_question_id_foreign" FOREIGN KEY("question_id") REFERENCES "Question"("id");
