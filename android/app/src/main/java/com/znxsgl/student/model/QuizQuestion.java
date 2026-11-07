package com.znxsgl.student.model;

import java.util.List;

public class QuizQuestion {
    private int questionIndex;
    private String questionType;   // 单选/判断/解析/填空
    private String subject;
    private String question;
    private List<String> options;  // 选择题和判断题的选项
    private String correctAnswer;
    private String graph;          // 函数图像表达式（如 "y=2*x+3"），无则 null

    // 客户端行为记录
    private String userAnswer;
    private int durationSec;
    private int modifiedCount;

    public int getQuestionIndex() { return questionIndex; }
    public void setQuestionIndex(int v) { questionIndex = v; }
    public String getQuestionType() { return questionType; }
    public void setQuestionType(String v) { questionType = v; }
    public String getSubject() { return subject; }
    public void setSubject(String v) { subject = v; }
    public String getQuestion() { return question; }
    public void setQuestion(String v) { question = v; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> v) { options = v; }
    public String getCorrectAnswer() { return correctAnswer; }
    public void setCorrectAnswer(String v) { correctAnswer = v; }
    public String getGraph() { return graph; }
    public void setGraph(String v) { graph = v; }
    public String getUserAnswer() { return userAnswer; }
    public void setUserAnswer(String v) { userAnswer = v; }
    public int getDurationSec() { return durationSec; }
    public void setDurationSec(int v) { durationSec = v; }
    public int getModifiedCount() { return modifiedCount; }
    public void setModifiedCount(int v) { modifiedCount = v; }
}
