package com.znxsgl.service;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 请假审批状态机：班主任初审 → 超过 3 天升级领导终审。
 * <p>
 * 从 LeaveController 抽取，供页面审批与管理端 AI 助手共用，避免复制状态流转逻辑。
 */
@Service
public class LeaveReviewService {

    private final JdbcTemplate jdbc;

    public LeaveReviewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ResponseEntity<?> review(Long id, Map<String, Object> body, Authentication auth) {
        Long userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        String action = "reject".equalsIgnoreCase(str(body.get("action"))) ? "reject" : "approve";
        String comment = str(body.get("comment")).trim();
        boolean admin = hasRole(auth, "ROLE_ADMIN");

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM leave_request WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "申请不存在"));
        Map<String, Object> r = rows.get(0);
        String status = str(r.get("status"));
        double days = ((Number) r.get("days")).doubleValue();

        if ("PENDING_TEACHER".equals(status)) {
            Long classId = r.get("class_id") instanceof Number n ? n.longValue() : null;
            boolean leader = isLeader(userId);
            if (!(admin || leader || isClassTeacher(userId, classId))) {
                return ResponseEntity.status(403).body(Map.of("error", "只有本班班主任、校级领导或管理员可以审批"));
            }
            String identity = reviewerIdentity(auth, classId);
            if ("reject".equals(action)) {
                jdbc.update("UPDATE leave_request SET status='REJECTED', teacher_id_op=?, teacher_comment=?, " +
                                "teacher_reviewer_role=?, teacher_at=NOW() WHERE id=?",
                        userId, comment, identity, id);
                return ResponseEntity.ok(Map.of("msg", "已驳回（审批人：" + identity + "）", "status", "REJECTED"));
            }
            if ("LEAVE".equals(str(r.get("type"))) && days > 3) {
                jdbc.update("UPDATE leave_request SET status='PENDING_LEADER', teacher_id_op=?, teacher_comment=?, " +
                                "teacher_reviewer_role=?, teacher_at=NOW() WHERE id=?",
                        userId, comment, identity, id);
                return ResponseEntity.ok(Map.of(
                        "msg", "初审通过（审批人：" + identity + "），已提交领导终审（超过 3 天）",
                        "status", "PENDING_LEADER"));
            }
            jdbc.update("UPDATE leave_request SET status='APPROVED', teacher_id_op=?, teacher_comment=?, " +
                            "teacher_reviewer_role=?, teacher_at=NOW() WHERE id=?",
                    userId, comment, identity, id);
            return ResponseEntity.ok(Map.of("msg", "审批通过（审批人：" + identity + "）", "status", "APPROVED"));
        }

        if ("PENDING_LEADER".equals(status)) {
            if (!(admin || isLeader(userId))) {
                return ResponseEntity.status(403).body(Map.of("error", "仅教导主任/校级领导或管理员可终审"));
            }
            String identity = reviewerIdentity(auth, null);
            if ("reject".equals(action)) {
                jdbc.update("UPDATE leave_request SET status='REJECTED', leader_id_op=?, leader_comment=?, " +
                                "leader_reviewer_role=?, leader_at=NOW() WHERE id=?",
                        userId, comment, identity, id);
                return ResponseEntity.ok(Map.of("msg", "已驳回（审批人：" + identity + "）", "status", "REJECTED"));
            }
            jdbc.update("UPDATE leave_request SET status='APPROVED', leader_id_op=?, leader_comment=?, " +
                            "leader_reviewer_role=?, leader_at=NOW() WHERE id=?",
                    userId, comment, identity, id);
            return ResponseEntity.ok(Map.of("msg", "终审通过（审批人：" + identity + "）", "status", "APPROVED"));
        }

        return ResponseEntity.badRequest().body(Map.of("error", "该申请当前状态不可审批"));
    }

    private boolean isClassTeacher(Long userId, Long classId) {
        if (classId == null) return false;
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_info ci JOIN teacher t ON t.id = ci.head_teacher_id " +
                        "JOIN user u ON u.real_name = t.real_name WHERE ci.id = ? AND u.id = ?",
                Integer.class, classId, userId);
        return n != null && n > 0;
    }

    private boolean isLeader(Long userId) {
        if (userId == null) return false;
        List<String> names = jdbc.queryForList("SELECT real_name FROM user WHERE id = ?", String.class, userId);
        if (names.isEmpty()) return false;
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM teacher WHERE real_name = ? AND is_leader = 1", Integer.class, names.get(0));
        return n != null && n > 0;
    }

    private String reviewerIdentity(Authentication auth, Long classId) {
        Long uid = uid(auth);
        if (hasRole(auth, "ROLE_ADMIN")) return "管理员";
        if (classId != null && isClassTeacher(uid, classId)) return "班主任";
        if (isLeader(uid)) return "教导主任";
        List<String> duty = jdbc.queryForList(
                "SELECT t.duty FROM teacher t JOIN user u ON u.real_name = t.real_name WHERE u.id = ? LIMIT 1",
                String.class, uid);
        return duty.isEmpty() ? "教师" : duty.get(0);
    }

    private boolean hasRole(Authentication auth, String role) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    private Long uid(Authentication auth) {
        return (auth != null && auth.getPrincipal() instanceof Long id) ? id : null;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
