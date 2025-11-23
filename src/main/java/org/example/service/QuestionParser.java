package org.example.service;

import org.example.model.Question;
import org.example.model.AnswerOption;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QuestionParser {
    public static class ParsedQuestion {
        public Question question;
        public List<AnswerOption> answerOptions;
    }

    public static List<ParsedQuestion> parse(String data) {
        List<ParsedQuestion> result = new ArrayList<>();
        String cleaned = data.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        cleaned = cleaned.replace("\\n", "\n");
        String[] questions = cleaned.split("\\*Следующий вопрос\\*");
        
        for (String qText : questions) {
            if (qText.trim().isEmpty()) continue;
            
            ParsedQuestion pq = new ParsedQuestion();
            pq.answerOptions = new ArrayList<>();
            
            Pattern questionPattern = Pattern.compile("\\*Вопрос\\*\\s*\n1\\)\\s*(.+?)(?=\\n2\\))", Pattern.DOTALL);
            Pattern answerPattern = Pattern.compile("(\\d+)\\)\\s*(.+?)(?=\\n\\d+\\)|\\n\\*правильный)", Pattern.DOTALL);
            Pattern correctPattern = Pattern.compile("\\*правильный ответ\\*\\s*\n(\\d+)");
            Pattern explanationPattern = Pattern.compile("\\*объяснение\\*\\s*\n(.+?)(?=\\n\\*Следующий|$)", Pattern.DOTALL);
            
            Matcher qMatcher = questionPattern.matcher(qText);
            if (qMatcher.find()) {
                Question question = new Question();
                question.setText(qMatcher.group(1).trim());
                question.setType("multiple_choice");
                question.setImageUrl("");
                
                Matcher expMatcher = explanationPattern.matcher(qText);
                if (expMatcher.find()) {
                    question.setExplanation(expMatcher.group(1).trim());
                }
                
                pq.question = question;
                
                Matcher ansMatcher = answerPattern.matcher(qText);
                while (ansMatcher.find()) {
                    String optionText = ansMatcher.group(2).trim();
                    
                    AnswerOption option = new AnswerOption();
                    option.setText(optionText);
                    option.setIsNaOption(false);
                    option.setIsCorrect(false);
                    pq.answerOptions.add(option);
                }
                
                Matcher correctMatcher = correctPattern.matcher(qText);
                if (correctMatcher.find()) {
                    int correctNum = Integer.parseInt(correctMatcher.group(1));
                    int correctIndex = correctNum - 2;
                    if (correctIndex >= 0 && correctIndex < pq.answerOptions.size()) {
                        pq.answerOptions.get(correctIndex).setIsCorrect(true);
                        for (int i = 0; i < pq.answerOptions.size(); i++) {
                            if (i != correctIndex) {
                                pq.answerOptions.get(i).setIsCorrect(false);
                            }
                        }
                    }
                }
            }
            
            if (pq.question != null) {
                result.add(pq);
            }
        }
        
        return result;
    }
}

