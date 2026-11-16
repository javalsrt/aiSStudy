package com.znxsgl.service;

import com.znxsgl.entity.QuizAnswer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * BKT 贝叶斯知识追踪。
 *
 * 每个学生 × 每个知识点维护一个"已掌握"概率：
 *   观测更新（答对/答错）→ 学习更新（做题本身带来掌握）
 * 每次交卷按知识点聚合，逐题更新并写一条快照，供教师端画曲线。
 */
@Service
public class MasteryService {

    private static final double P_INIT = 0.30;
    // 学习概率取 0.10：连续答对仍能快速收敛到 0.9+，答错时下降更明显（不会因一次失误就归零）
    private static final double P_LEARN = 0.10;
    private static final double P_SLIP = 0.10;

    private final JdbcTemplate jdbc;

    public MasteryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 题目内容指纹：同一道题在生成和交卷时算出同一个 qid */
    public static String qid(Long courseId, String question) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String src = courseId + "|" + (question == null ? "" : question.trim());
            byte[] d = md.digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 生成阶段：给题目写 qid，并按 knowledge 字段绑定知识点（新知识点自动入库） */
    public void bindQuestions(Long courseId, List<Map<String, Object>> questions) {
        if (courseId == null || questions == null || questions.isEmpty()) return;
        Map<String, Long> kpByName = knowledgeMap(courseId);
        for (Map<String, Object> q : questions) {
            String question = String.valueOf(q.getOrDefault("question", ""));
            String qid = qid(courseId, question);
            if (qid == null || question.isEmpty()) continue;
            q.put("qid", qid);
            Object kn = q.get("knowledge");
            String label = kn == null ? "" : String.valueOf(kn).trim();
            Long kpId = label.isEmpty() ? null : kpByName.get(label);
            // AI 标注的新知识点：自动建点，保证每门科目都能积累 BKT 数据
            if (kpId == null && label.length() >= 2 && label.length() <= 40) {
                kpId = createKnowledge(courseId, label);
                if (kpId != null) kpByName.put(label, kpId);
            }
            if (kpId == null) kpId = matchByKeyword(courseId, question);
            if (kpId == null) continue;
            try {
                jdbc.update("INSERT IGNORE INTO question_knowledge(qid, knowledge_id, course_id) VALUES(?,?,?)",
                        qid, kpId, courseId);
            } catch (Exception ignored) {
            }
        }
    }

    /** 自动创建知识点（同名去重），返回知识点 id */
    private Long createKnowledge(Long courseId, String name) {
        try {
            Integer next = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sort_order),0)+1 FROM knowledge_point WHERE course_id = ?",
                    Integer.class, courseId);
            jdbc.update("INSERT IGNORE INTO knowledge_point(course_id, name, sort_order, status) VALUES(?,?,?,1)",
                    courseId, name, next == null ? 1 : next);
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM knowledge_point WHERE course_id = ? AND name = ?", Long.class, courseId, name);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按弱项排序：把掌握度最低的知识点对应的题目排到前面。
     * 题库按课程共享，无法为每人重新生成，但顺序可以个性化，让薄弱知识点优先练。
     */
    public List<Map<String, Object>> orderByWeakness(Long userId, Long courseId,
                                                     List<Map<String, Object>> questions) {
        if (userId == null || courseId == null || questions == null || questions.size() < 2) return questions;
        try {
            Map<Long, Double> masteryByKp = new HashMap<>();
            jdbc.query("SELECT knowledge_id, p_mastery FROM user_knowledge_mastery WHERE user_id = ? AND course_id = ?",
                    rs -> {
                        masteryByKp.put(rs.getLong("knowledge_id"), rs.getDouble("p_mastery"));
                    }, userId, courseId);
            if (masteryByKp.isEmpty()) return questions;

            Map<String, Long> kpByQid = new HashMap<>();
            for (Map<String, Object> q : questions) {
                Object qidObj = q.get("qid");
                if (qidObj == null) continue;
                List<Long> ids = jdbc.queryForList(
                        "SELECT knowledge_id FROM question_knowledge WHERE qid = ? LIMIT 1",
                        Long.class, String.valueOf(qidObj));
                if (!ids.isEmpty()) kpByQid.put(String.valueOf(qidObj), ids.get(0));
            }
            if (kpByQid.isEmpty()) return questions;

            List<Map<String, Object>> copy = new ArrayList<>(questions);
            copy.sort(Comparator.comparingDouble(q -> {
                Long kp = kpByQid.get(String.valueOf(q.get("qid")));
                return kp == null ? 1.0 : masteryByKp.getOrDefault(kp, P_INIT);
            }));
            for (int i = 0; i < copy.size(); i++) copy.get(i).put("questionIndex", i + 1);
            return copy;
        } catch (Exception e) {
            return questions;
        }
    }

    /** 交卷后更新：按知识点聚合，同一知识点内按作答顺序逐题更新 */
    public void updateFromSession(Long userId, Long courseId, Long sessionId, List<QuizAnswer> answers) {
        if (userId == null || courseId == null || answers == null || answers.isEmpty()) return;

        Map<Long, List<QuizAnswer>> grouped = new LinkedHashMap<>();
        for (QuizAnswer a : answers) {
            if (a.getQuestion() == null || a.getQuestion().isEmpty()) continue;
            if ("解析".equals(a.getQuestionType())) continue; // 主观题判分噪声大，不纳入 BKT
            Long kp = resolveKnowledge(courseId, a.getQuestion());
            if (kp == null) continue;
            grouped.computeIfAbsent(kp, k -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<Long, List<QuizAnswer>> e : grouped.entrySet()) {
            Long kp = e.getKey();
            List<QuizAnswer> list = e.getValue();
            double before = getMastery(userId, kp);
            double p = before;
            int correctCount = 0;
            for (QuizAnswer a : list) {
                boolean ok = a.getIsCorrect() != null && a.getIsCorrect() == 1;
                if (ok) correctCount++;
                p = step(p, ok, guessRate(a.getQuestionType()));
            }
            p = clamp(p);
            upsertMastery(userId, courseId, kp, p, list.size(), correctCount, sessionId);
            insertSnapshot(userId, courseId, kp, sessionId, before, p, list.size(), correctCount);
        }
    }

    /** BKT 两步更新：观测修正 + 学习增量 */
    double step(double p, boolean correct, double guess) {
        double pSlip = P_SLIP;
        double pObs;
        if (correct) {
            double num = p * (1 - pSlip);
            pObs = num / (num + (1 - p) * guess);
        } else {
            double num = p * pSlip;
            pObs = num / (num + (1 - p) * (1 - guess));
        }
        return pObs + (1 - pObs) * P_LEARN;
    }

    private double guessRate(String type) {
        if ("判断".equals(type)) return 0.50;
        if ("填空".equals(type)) return 0.05;
        return 0.25;
    }

    private double clamp(double p) {
        if (p < 0.01) return 0.01;
        if (p > 0.99) return 0.99;
        return p;
    }

    private Map<String, Long> knowledgeMap(Long courseId) {
        Map<String, Long> map = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT id, name FROM knowledge_point WHERE course_id = ? AND status = 1 ORDER BY sort_order",
                    rs -> {
                        map.put(rs.getString("name").trim(), rs.getLong("id"));
                    }, courseId);
        } catch (Exception ignored) {
        }
        return map;
    }

    private Long resolveKnowledge(Long courseId, String question) {
        String qid = qid(courseId, question);
        if (qid != null) {
            try {
                List<Long> ids = jdbc.queryForList(
                        "SELECT knowledge_id FROM question_knowledge WHERE qid = ? LIMIT 1", Long.class, qid);
                if (!ids.isEmpty()) return ids.get(0);
            } catch (Exception ignored) {
            }
        }
        // 没有显式绑定（旧题库/缓存题）时退回关键词匹配，保证演示和历史数据也能算
        return matchByKeyword(courseId, question);
    }

    /** 用知识点名称里的关键词匹配题干，取最长命中，避免"函数"抢过"导数" */
    private Long matchByKeyword(Long courseId, String question) {
        if (question == null || question.isEmpty()) return null;
        try {
            List<Map<String, Object>> kps = jdbc.queryForList(
                    "SELECT id, name FROM knowledge_point WHERE course_id = ? AND status = 1 ORDER BY sort_order",
                    courseId);
            Long best = null;
            int bestLen = 0;
            for (Map<String, Object> kp : kps) {
                String name = String.valueOf(kp.get("name"));
                for (String token : name.split("[与和的、,，/\\s]+")) {
                    if (token.length() >= 2 && question.contains(token) && token.length() > bestLen) {
                        best = ((Number) kp.get("id")).longValue();
                        bestLen = token.length();
                    }
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private double getMastery(Long userId, Long knowledgeId) {
        try {
            Double p = jdbc.queryForObject(
                    "SELECT p_mastery FROM user_knowledge_mastery WHERE user_id = ? AND knowledge_id = ?",
                    Double.class, userId, knowledgeId);
            return p == null ? P_INIT : p;
        } catch (Exception e) {
            return P_INIT;
        }
    }

    private void upsertMastery(Long userId, Long courseId, Long kp, double p,
                               int attempts, int correct, Long sessionId) {
        jdbc.update("INSERT INTO user_knowledge_mastery(user_id, course_id, knowledge_id, p_mastery, attempts, correct, last_session_id, updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE " +
                        "p_mastery = VALUES(p_mastery), attempts = attempts + VALUES(attempts), " +
                        "correct = correct + VALUES(correct), last_session_id = VALUES(last_session_id), updated_at = NOW()",
                userId, courseId, kp, p, attempts, correct, sessionId);
    }

    private void insertSnapshot(Long userId, Long courseId, Long kp, Long sessionId,
                                double before, double after, int count, int correct) {
        try {
            jdbc.update("INSERT INTO mastery_snapshot(user_id, course_id, knowledge_id, session_id, p_before, p_after, question_count, correct_count) " +
                            "VALUES(?,?,?,?,?,?,?,?)",
                    userId, courseId, kp, sessionId, before, after, count, correct);
        } catch (Exception ignored) {
        }
    }

    /** 学生某课程的科目级掌握度（按作答次数加权；无数据时返回 BKT 先验 0.30） */
    public double courseMastery(Long userId, Long courseId) {
        try {
            Double v = jdbc.queryForObject(
                    "SELECT SUM(p_mastery * GREATEST(attempts, 1)) / SUM(GREATEST(attempts, 1)) " +
                            "FROM user_knowledge_mastery WHERE user_id = ? AND course_id = ? AND attempts > 0",
                    Double.class, userId, courseId);
            return v == null ? P_INIT : v;
        } catch (Exception e) {
            return P_INIT;
        }
    }

    /** 学生某课程的知识点掌握度列表（未作答的也返回初始值） */
    public List<Map<String, Object>> knowledgeList(Long userId, Long courseId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT k.id, k.name, COALESCE(m.p_mastery, 0.3) AS p, COALESCE(m.attempts,0) AS attempts, " +
                            "COALESCE(m.correct,0) AS correct, m.updated_at " +
                            "FROM knowledge_point k LEFT JOIN user_knowledge_mastery m " +
                            "ON m.knowledge_id = k.id AND m.user_id = ? " +
                            "WHERE k.course_id = ? AND k.status = 1 ORDER BY k.sort_order",
                    userId, courseId);
            for (Map<String, Object> r : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("knowledgeId", r.get("id"));
                item.put("name", r.get("name"));
                item.put("mastery", ((Number) r.get("p")).doubleValue());
                item.put("attempts", r.get("attempts"));
                item.put("correct", r.get("correct"));
                item.put("updatedAt", r.get("updated_at"));
                list.add(item);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 某次交卷中各知识点的掌握度变化（knowledgeId -> [before, after]），供报告页展示"本次变化" */
    public Map<Long, double[]> sessionChanges(Long userId, Long sessionId) {
        Map<Long, double[]> map = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT knowledge_id, p_before, p_after FROM mastery_snapshot WHERE user_id = ? AND session_id = ?",
                    userId, sessionId);
            for (Map<String, Object> r : rows) {
                map.put(((Number) r.get("knowledge_id")).longValue(),
                        new double[]{((Number) r.get("p_before")).doubleValue(),
                                ((Number) r.get("p_after")).doubleValue()});
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    /** 某知识点的掌握度曲线（来自快照） */
    public List<Map<String, Object>> history(Long userId, Long knowledgeId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT session_id, p_before, p_after, question_count, correct_count, created_at " +
                            "FROM mastery_snapshot WHERE user_id = ? AND knowledge_id = ? ORDER BY created_at",
                    userId, knowledgeId);
            for (Map<String, Object> r : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sessionId", r.get("session_id"));
                item.put("before", ((Number) r.get("p_before")).doubleValue());
                item.put("after", ((Number) r.get("p_after")).doubleValue());
                item.put("questionCount", r.get("question_count"));
                item.put("correctCount", r.get("correct_count"));
                item.put("time", r.get("created_at"));
                list.add(item);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 重置某个学生在某课程下的掌握度与快照（演示用） */
    public void reset(Long userId, Long courseId) {
        jdbc.update("DELETE FROM user_knowledge_mastery WHERE user_id = ? AND course_id = ?", userId, courseId);
        jdbc.update("DELETE FROM mastery_snapshot WHERE user_id = ? AND course_id = ?", userId, courseId);
    }
}
