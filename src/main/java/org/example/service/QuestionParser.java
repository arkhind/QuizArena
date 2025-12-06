package org.example.service;

import org.example.model.Question;
import org.example.model.AnswerOption;
import org.example.model.QuestionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QuestionParser {
    public static class ParsedQuestion {
        public Question question;
        public List<AnswerOption> answerOptions;
        
        public ParsedQuestion() {
            this.answerOptions = new ArrayList<>();
        }
    }

    public static List<ParsedQuestion> parse(String data) {
        List<ParsedQuestion> result = new ArrayList<>();
        
        if (data == null || data.trim().isEmpty()) {
            System.err.println("QuestionParser: Пустые данные для парсинга");
            return result;
        }
        
        // Очистка данных
        String cleaned = data.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        cleaned = cleaned.replace("\\n", "\n");
        cleaned = cleaned.replace("\\r", "");
        cleaned = cleaned.replaceAll(" +", " ");
        cleaned = cleaned.replaceAll(" *\n+ *", "\n");
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");
        
        // Разделяем на отдельные вопросы
        String[] questionBlocks = cleaned.split("(?i)\\*Следующий вопрос\\*");
        if (questionBlocks.length == 1 && !cleaned.toLowerCase().contains("следующий вопрос")) {
            questionBlocks = cleaned.split("(?i)\\*Вопрос\\*");
        }
        
        for (int blockIndex = 0; blockIndex < questionBlocks.length; blockIndex++) {
            String block = questionBlocks[blockIndex].trim();
            if (block.isEmpty()) continue;
            
            ParsedQuestion pq = new ParsedQuestion();
            
            // Извлекаем текст вопроса
            Pattern questionPattern = Pattern.compile("(?i)(?:\\*)?Вопрос(?:\\*)?[\\s\\n]+([^\\n]+?)(?=\\s*\\n\\s*\\d+\\)|\\s*\\n\\s*\\*правильный|$)", Pattern.DOTALL | Pattern.MULTILINE);
            Matcher qMatcher = questionPattern.matcher(block);
            
            String questionText = null;
            if (qMatcher.find()) {
                questionText = qMatcher.group(1).trim();
                questionText = questionText.replaceAll("^\\*+|\\*+$", "").trim();
                questionText = questionText.replaceAll(" +", " ").trim();
            } else {
                System.err.println("QuestionParser: Не найден текст вопроса в блоке " + (blockIndex + 1));
                continue;
            }
            
            if (questionText == null || questionText.isEmpty()) {
                System.err.println("QuestionParser: Текст вопроса пустой, пропускаем блок " + (blockIndex + 1));
                continue;
            }
            
            Question question = new Question();
            question.setText(questionText);
            question.setType(QuestionType.MULTIPLE_CHOICE);
            pq.question = question;
            
            // Извлекаем варианты ответов
            Map<Integer, AnswerOption> optionsByNumber = new HashMap<>();
            
            int correctAnswerPos = block.toLowerCase().indexOf("*правильный ответ*");
            if (correctAnswerPos < 0) {
                correctAnswerPos = block.toLowerCase().indexOf("правильный ответ");
            }
            
            int explanationPos = block.toLowerCase().indexOf("*объяснение*");
            if (explanationPos < 0) {
                explanationPos = block.toLowerCase().indexOf("объяснение");
            }
            
            int optionsEndPos = block.length();
            if (correctAnswerPos >= 0) {
                optionsEndPos = Math.min(optionsEndPos, correctAnswerPos);
            }
            if (explanationPos >= 0) {
                optionsEndPos = Math.min(optionsEndPos, explanationPos);
            }
            
            String optionsBlock = block.substring(0, optionsEndPos);
            Pattern answerPattern = Pattern.compile("(\\d+)\\)\\s*([^\\n]+?)(?=\\s*\\n\\s*\\d+\\)|\\s*\\n\\s*\\*правильный|\\s*\\n\\s*\\*объяснение|$)", Pattern.DOTALL | Pattern.MULTILINE);
            Matcher ansMatcher = answerPattern.matcher(optionsBlock);
            
            while (ansMatcher.find()) {
                int optionNumber = Integer.parseInt(ansMatcher.group(1).trim());
                String optionText = ansMatcher.group(2).trim();
                
                if (optionText.isEmpty()) continue;
                
                optionText = optionText.replaceAll("^\\*+|\\*+$", "").trim();
                optionText = optionText.replaceAll(" +", " ").trim();
                
                boolean isPlaceholder = false;
                if (optionText.equalsIgnoreCase("вариант ответа") || 
                    optionText.matches("^\\*+$") ||
                    (optionText.length() <= 2 && !optionText.matches(".*[\\d\\w].*"))) {
                    isPlaceholder = true;
                }
                
                if (isPlaceholder) continue;
                
                if (optionText.startsWith("=") || 
                    (optionText.length() > 10 && (optionText.startsWith("При") || optionText.startsWith("Сумма") || optionText.startsWith("Сложение")))) {
                    continue;
                }
                
                AnswerOption option = new AnswerOption();
                option.setText(optionText);
                option.setCorrect(false);
                option.setNaOption(false);
                
                optionsByNumber.put(optionNumber, option);
                pq.answerOptions.add(option);
            }
            
            if (pq.answerOptions.size() < 2) {
                System.err.println("QuestionParser: Недостаточно вариантов ответов (" + pq.answerOptions.size() + "), пропускаем блок " + (blockIndex + 1));
                continue;
            }
            
            // Извлекаем правильный ответ - пробуем несколько паттернов
            int correctNum = -1;
            
            // Паттерн 1: "*правильный ответ*" на отдельной строке, число на следующей
            Pattern correctPattern = Pattern.compile("(?i)\\*правильный ответ\\*\\s*\\n\\s*(\\d+)", Pattern.DOTALL | Pattern.MULTILINE);
            Matcher correctMatcher = correctPattern.matcher(block);
            if (correctMatcher.find()) {
                try {
                    correctNum = Integer.parseInt(correctMatcher.group(1).trim());
                } catch (NumberFormatException e) {
                    // Игнорируем
                }
            }
            
            // Паттерн 2: "*правильный ответ*" и число на той же или следующей строке
            if (correctNum < 0) {
                correctPattern = Pattern.compile("(?i)\\*правильный ответ\\*[\\s\\n]+(\\d+)", Pattern.DOTALL | Pattern.MULTILINE);
                correctMatcher = correctPattern.matcher(block);
                if (correctMatcher.find()) {
                    try {
                        correctNum = Integer.parseInt(correctMatcher.group(1).trim());
                    } catch (NumberFormatException e) {
                        // Игнорируем
                    }
                }
            }
            
            // Паттерн 3: "правильный ответ" без звездочек
            if (correctNum < 0) {
                correctPattern = Pattern.compile("(?i)правильный ответ[\\s\\n]+(\\d+)", Pattern.DOTALL | Pattern.MULTILINE);
                correctMatcher = correctPattern.matcher(block);
                if (correctMatcher.find()) {
                    try {
                        correctNum = Integer.parseInt(correctMatcher.group(1).trim());
                    } catch (NumberFormatException e) {
                        // Игнорируем
                    }
                }
            }
            
            // Паттерн 4: Ищем число после "*правильный ответ*" в любом месте (более гибкий)
            if (correctNum < 0) {
                int correctAnswerKeywordPos = block.toLowerCase().indexOf("правильный ответ");
                if (correctAnswerKeywordPos >= 0) {
                    String afterKeyword = block.substring(correctAnswerKeywordPos);
                    Pattern numberPattern = Pattern.compile("\\d+");
                    Matcher numberMatcher = numberPattern.matcher(afterKeyword);
                    if (numberMatcher.find()) {
                        try {
                            correctNum = Integer.parseInt(numberMatcher.group());
                        } catch (NumberFormatException e) {
                            // Игнорируем
                        }
                    }
                }
            }
            
            // Устанавливаем правильный ответ
            if (correctNum > 0) {
                AnswerOption correctOption = optionsByNumber.get(correctNum);
                if (correctOption != null) {
                    correctOption.setCorrect(true);
                } else {
                    System.err.println("QuestionParser: Не найден вариант с номером " + correctNum + " в сохраненных вариантах. Доступные номера: " + optionsByNumber.keySet());
                    System.err.println("QuestionParser: Фрагмент блока вокруг 'правильный ответ':");
                    int pos = block.toLowerCase().indexOf("правильный ответ");
                    if (pos >= 0) {
                        int start = Math.max(0, pos - 50);
                        int end = Math.min(block.length(), pos + 100);
                        System.err.println(block.substring(start, end));
                    }
                    if (!pq.answerOptions.isEmpty()) {
                        pq.answerOptions.get(0).setCorrect(true);
                    }
                }
            } else {
                System.err.println("QuestionParser: Не найден правильный ответ в блоке " + (blockIndex + 1));
                System.err.println("QuestionParser: Фрагмент блока вокруг 'правильный ответ':");
                int pos = block.toLowerCase().indexOf("правильный ответ");
                if (pos >= 0) {
                    int start = Math.max(0, pos - 50);
                    int end = Math.min(block.length(), pos + 100);
                    System.err.println(block.substring(start, end));
                } else {
                    System.err.println("QuestionParser: Строка 'правильный ответ' не найдена в блоке");
                }
                if (!pq.answerOptions.isEmpty()) {
                    pq.answerOptions.get(0).setCorrect(true);
                }
            }
            
            // Извлекаем объяснение
            Pattern explanationPattern = Pattern.compile("(?i)(?:\\*)?объяснение(?:\\*)?[\\s\\n]+(.+?)(?=\\s*\\n\\s*\\*Следующий|$)", Pattern.DOTALL | Pattern.MULTILINE);
            Matcher expMatcher = explanationPattern.matcher(block);
            
            if (expMatcher.find()) {
                String explanation = expMatcher.group(1).trim();
                explanation = explanation.replaceAll("^\\*+|\\*+$", "").trim();
                explanation = explanation.replaceAll(" +", " ").replaceAll("\\n+", " ").trim();
                question.setExplanation(explanation);
            } else {
                question.setExplanation("Объяснение отсутствует");
            }
            
            // Проверяем валидность
            if (question.getText() == null || question.getText().trim().isEmpty()) {
                System.err.println("QuestionParser: Текст вопроса пустой, пропускаем блок " + (blockIndex + 1));
                continue;
            }
            
            if (pq.answerOptions.size() < 2) {
                System.err.println("QuestionParser: Недостаточно вариантов ответов (" + pq.answerOptions.size() + "), пропускаем блок " + (blockIndex + 1));
                continue;
            }
            
            boolean hasCorrect = pq.answerOptions.stream().anyMatch(AnswerOption::isCorrect);
            if (!hasCorrect && !pq.answerOptions.isEmpty()) {
                System.err.println("QuestionParser: Нет правильного ответа, делаем первый правильным");
                pq.answerOptions.get(0).setCorrect(true);
            }
            
            result.add(pq);
        }
        
        return result;
    }
}
