package com.znxsgl.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 考试/作业发布通知服务（带幂等去重，防止重复发送29次这种故障）
 * 三重防重：
 *   1) 内存缓存：同一 examId+userId 10分钟内只发1次
 *   2) DB先查已发送：同examId已发过的学生跳过
 *   3) DB唯一索引：uk_user_biz(user_id, biz_type, biz_id) + INSERT IGNORE
 */
@Service
public class ExamHomeworkNotifyService {

    private static final Logger log = LoggerFactory.getLogger(ExamHomeworkNotifyService.class);

    private final JdbcTemplate jdbc;

    /** 内存去重：key=examId+"_"+userId */
    private static final ConcurrentHashMap<String, Long> EXAM_NOTIFY_DEDUP = new ConcurrentHashMap<>();
    private static final long EXAM_NOTIFY_DEDUP_MS = 600_000L; // 10分钟

    public ExamHomeworkNotifyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 给指定班级所有学生发送考试/作业发布通知
     * @param examId    exam_homework.id（必填，做幂等键）
     * @param classId   接收班级
     * @param courseId  所属课程（可为空）
     * @param title     考试/作业标题
     * @param type      exam/homework
     */
    public void notifyStudents(Long examId, Long classId, Long courseId, String title, String type) {
        log.info("[ExamNotify] 收到通知请求: examId={}, classId={}, courseId={}, title={}, type={}",
                examId, classId, courseId, title, type);
        if (examId == null || classId == null) {
            log.warn("[ExamNotify] 参数校验失败: examId={}, classId={}", examId, classId);
            return;
        }
        try {
            String courseName = courseId != null
                    ? jdbc.queryForObject("SELECT course_name FROM course WHERE id = ?", String.class, courseId)
                    : null;
            log.info("[ExamNotify] 课程名称: {}", courseName);

            String label = "exam".equals(type) ? "考试" : "作业";
            // 移动端卡片协议：[exam]标题|考试ID|类型|状态文本|课程名
            String cardContent = "[exam]" + title + "|" + examId + "|" + type + "|点击开始作答|" +
                    (courseName != null ? courseName : "");

            // 查询 DB 已发送记录
            Set<Long> sent = new HashSet<>();
            try {
                List<Long> already = jdbc.queryForList(
                        "SELECT user_id FROM chat_message WHERE biz_type = 'exam_publish' AND biz_id = ?",
                        Long.class, examId);
                sent.addAll(already);
                log.info("[ExamNotify] DB 已发送学生数: {}, 列表: {}", sent.size(), sent);
            } catch (Exception e) {
                log.error("[ExamNotify] 查询 DB 已发送记录失败(examId={}): {}", examId, e.getMessage(), e);
            }

            // 查询班级学生
            List<Long> studentIds;
            try {
                studentIds = jdbc.queryForList(
                        "SELECT id FROM user WHERE class_id = ? AND role = 1", Long.class, classId);
                log.info("[ExamNotify] 班级学生数: {}, 列表: {}", studentIds.size(), studentIds);
            } catch (Exception e) {
                log.error("[ExamNotify] 查询班级学生失败(examId={}, classId={}): {}", examId, classId, e.getMessage(), e);
                return;
            }

            // 清理过期内存缓存
            long now = System.currentTimeMillis();
            int removed = 0;
            Iterator<Map.Entry<String, Long>> it = EXAM_NOTIFY_DEDUP.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > EXAM_NOTIFY_DEDUP_MS) {
                    it.remove();
                    removed++;
                }
            }
            log.info("[ExamNotify] 内存缓存清理前大小: {}, 清理过期: {}, 当前大小: {}",
                    EXAM_NOTIFY_DEDUP.size() + removed, removed, EXAM_NOTIFY_DEDUP.size());

            int skipped = 0;
            int inserted = 0;
            int memSkipped = 0;
            int dbSkipped = 0;

            for (Long sid : studentIds) {
                String dedupKey = examId + "_" + sid;
                Long last = EXAM_NOTIFY_DEDUP.get(dedupKey);
                if (last != null && (now - last) <= EXAM_NOTIFY_DEDUP_MS) {
                    log.debug("[ExamNotify] 学生 {} 被内存缓存跳过, last={}", sid, last);
                    memSkipped++;
                    skipped++;
                    continue;
                }
                if (sent.contains(sid)) {
                    log.debug("[ExamNotify] 学生 {} 被 DB 已发送记录跳过", sid);
                    dbSkipped++;
                    skipped++;
                    continue;
                }

                try {
                    int upd = jdbc.update(
                            "INSERT IGNORE INTO chat_message " +
                                    "(user_id, course_name, course_id, content, sender_role, sender_name, is_read, created_at, biz_type, biz_id) " +
                                    "VALUES (?, ?, ?, ?, 'teacher', '系统通知', 0, NOW(), 'exam_publish', ?)",
                            sid, courseName != null ? courseName : "", courseId, cardContent, examId);
                    if (upd > 0) {
                        inserted++;
                        EXAM_NOTIFY_DEDUP.put(dedupKey, now);
                        log.debug("[ExamNotify] 学生 {} 通知写入成功", sid);
                    } else {
                        skipped++;
                        dbSkipped++;
                        log.warn("[ExamNotify] 学生 {} INSERT IGNORE 返回 0, 可能因唯一索引已存在", sid);
                    }
                } catch (Exception ex) {
                    skipped++;
                    log.error("[ExamNotify] 单条写入失败(examId={}, userId={}): {}", examId, sid, ex.getMessage(), ex);
                }
            }
            log.info("[ExamNotify] 完成: examId={}, 学生总数={}, 新增发送={}, 跳过总数={}, 其中内存跳过={}, DB跳过={}",
                    examId, studentIds.size(), inserted, skipped, memSkipped, dbSkipped);
        } catch (Exception e) {
            log.error("[ExamNotify] 发送异常: examId={}, classId={}, title={}", examId, classId, title, e);
        }
    }
}
