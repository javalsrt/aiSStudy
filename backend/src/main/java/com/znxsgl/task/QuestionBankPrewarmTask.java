package com.znxsgl.task;

import com.znxsgl.service.QuestionGenerationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 题库预生成定时任务（默认关闭）。
 * 开启条件：app.question-bank.prewarm-enabled=true
 * 启动后会按 cron 扫描有章节的课程，异步预生成难度 1~3 的题库。
 */
@Component
@ConditionalOnProperty(name = "app.question-bank.prewarm-enabled", havingValue = "true")
public class QuestionBankPrewarmTask {

    private final JdbcTemplate jdbc;
    private final QuestionGenerationService questionGenerationService;

    public QuestionBankPrewarmTask(JdbcTemplate jdbc, QuestionGenerationService questionGenerationService) {
        this.jdbc = jdbc;
        this.questionGenerationService = questionGenerationService;
    }

    @Scheduled(cron = "${app.question-bank.prewarm-cron:0 0 4 * * ?}")
    public void prewarm() {
        try {
            List<Long> courseIds = jdbc.queryForList(
                    "SELECT DISTINCT c.id FROM course c " +
                    "JOIN course_chapter cc ON cc.course_id = c.id AND cc.deleted = 0 AND cc.status = 1 " +
                    "ORDER BY c.id LIMIT 30",
                    Long.class);
            System.out.println("=== 题库预生成任务开始，课程数=" + courseIds.size());
            for (Long courseId : courseIds) {
                questionGenerationService.prewarmCourseAsync(courseId);
            }
        } catch (Exception e) {
            System.out.println("=== 题库预生成任务异常: " + e.getMessage());
        }
    }
}