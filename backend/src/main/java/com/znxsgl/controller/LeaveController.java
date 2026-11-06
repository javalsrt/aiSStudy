package com.znxsgl.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 学生请假 / 临时外出。
 * <p>
 * 规则：
 * 1) 请假 ≤3 天：班主任审批；>3 天：班主任初审 → 学生处（管理员）终审。
 * 2) 临时外出（当天往返）：班主任审批。
 * 3) 必须填写出校/回校时间；可选择是否已告知家长并发送短信。
 * 4) 返校后需定位 + 拍照打卡，状态变为「已返校」。
 */
@RestController
@RequestMapping("/api/leave")
public class LeaveController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;

    public LeaveController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 提交申请 */
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody Map<String, Object> body, Authentication auth) {
        Long userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));

        String type = "OUTING".equals(String.valueOf(body.get("type"))) ? "OUTING" : "LEAVE";
        String reason = str(body.get("reason")).trim();
        String destination = str(body.get("destination")).trim();
        String startText = str(body.get("startTime"));
        String endText = str(body.get("endTime"));
        if (reason.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "请填写事由"));
        if (startText.isEmpty() || endText.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择出校与回校时间"));
        }
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDateTime.parse(startText.length() > 16 ? startText.substring(0, 16) : startText, FMT);
            end = LocalDateTime.parse(endText.length() > 16 ? endText.substring(0, 16) : endText, FMT);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "时间格式应为 yyyy-MM-dd HH:mm"));
        }
        if (!end.isAfter(start)) {
            return ResponseEntity.badRequest().body(Map.of("error", "回校时间必须晚于出校时间"));
        }
        boolean parentNotified = isTrue(body.get("parentNotified"));
        String parentPhone = str(body.get("parentPhone")).trim();
        if (!parentNotified) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先告知家长并勾选确认"));
        }
        if (parentPhone.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请填写家长联系电话"));
        }

        double hours = Duration.between(start, end).toMinutes() / 60.0;
        double days = Math.max(0.5, Math.round(hours / 24.0 * 10) / 10.0);
        boolean longLeave = "LEAVE".equals(type) && days > 3;

        Long classId = firstLong("SELECT class_id FROM user WHERE id = ?", userId);
        Long teacherId = resolveClassTeacher(classId);

        // 只拦截「待审批」的重复申请；已通过的可在返校前后另行申请
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_request WHERE user_id = ? AND status IN ('PENDING_TEACHER','PENDING_LEADER')",
                Integer.class, userId);
        if (dup != null && dup > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "你有正在审批中的申请，请等待审批或先取消"));
        }

        jdbc.update("INSERT INTO leave_request (user_id, class_id, teacher_id, type, reason, destination, " +
                        "start_time, end_time, days, parent_notified, parent_phone, sms_sent, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_TEACHER')",
                userId, classId, teacherId, type, reason, destination, start, end, days,
                parentNotified ? 1 : 0, parentPhone, parentNotified ? 1 : 0);

        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("msg", longLeave ? "已提交，需班主任初审后由学生处审批" : "已提交，等待班主任审批");
        resp.put("status", "PENDING_TEACHER");
        resp.put("needLeader", longLeave);
        return ResponseEntity.ok(resp);
    }

    /** 我的申请 */
    @GetMapping("/my")
    public ResponseEntity<?> my(Authentication auth) {
        Long userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.*, u.real_name AS studentName, ci.class_name AS className FROM leave_request r " +
                        "LEFT JOIN user u ON u.id = r.user_id LEFT JOIN class_info ci ON ci.id = r.class_id " +
                        "WHERE r.user_id = ? ORDER BY r.id DESC LIMIT 30", userId);
        return ResponseEntity.ok(decorate(rows));
    }

    /** 取消申请 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id, Authentication auth) {
        Long userId = uid(auth);
        int n = jdbc.update("UPDATE leave_request SET status = 'CANCELED' " +
                "WHERE id = ? AND user_id = ? AND status IN ('PENDING_TEACHER','PENDING_LEADER')", id, userId);
        return ResponseEntity.ok(Map.of("msg", n > 0 ? "已取消" : "当前状态不可取消"));
    }

    /** 返校打卡：定位 + 拍照 */
    @PostMapping("/{id}/return")
    public ResponseEntity<?> returnSchool(@PathVariable Long id,
                                          @RequestParam("photo") MultipartFile photo,
                                          @RequestParam(value = "lat", required = false) Double lat,
                                          @RequestParam(value = "lng", required = false) Double lng,
                                          Authentication auth) {
        Long userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        if (photo == null || photo.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请拍照后再提交"));
        }
        Integer ok = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_request WHERE id = ? AND user_id = ? AND status = 'APPROVED'",
                Integer.class, id, userId);
        if (ok == null || ok == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "仅在审批通过后可打卡"));
        }
        String url;
        try {
            String dir = System.getProperty("user.dir") + "/uploads/leave/";
            File d = new File(dir);
            if (!d.exists() && !d.mkdirs()) throw new IllegalStateException("无法创建上传目录");
            String name = "return_" + id + "_" + UUID.randomUUID().toString().substring(0, 8) + ".jpg";
            Path path = Paths.get(dir, name);
            Files.write(path, photo.getBytes());
            url = "/uploads/leave/" + name;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "照片保存失败：" + e.getMessage()));
        }
        jdbc.update("UPDATE leave_request SET status = 'RETURNED', return_photo = ?, return_lat = ?, " +
                "return_lng = ?, return_at = NOW() WHERE id = ?", url, lat, lng, id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("msg", "返校打卡成功");
        resp.put("photoUrl", url);
        return ResponseEntity.ok(resp);
    }

    /** 待审批列表：教师看本班，管理员看全部 */
    @GetMapping("/pending")
    public ResponseEntity<?> pending(Authentication auth) {
        Long userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        boolean admin = hasRole(auth, "ROLE_ADMIN");
        List<Map<String, Object>> rows;
        String sql = "SELECT r.*, u.real_name AS studentName, u.student_no AS studentNo, ci.class_name AS className, " +
                "t1.real_name AS teacherName FROM leave_request r " +
                "LEFT JOIN user u ON u.id = r.user_id " +
                "LEFT JOIN class_info ci ON ci.id = r.class_id " +
                "LEFT JOIN user t1 ON t1.id = r.teacher_id_op " +
                "WHERE r.status IN ('PENDING_TEACHER','PENDING_LEADER') ";
        if (admin) {
            rows = jdbc.queryForList(sql + "ORDER BY r.id DESC LIMIT 100");
        } else {
            rows = jdbc.queryForList(sql + "AND (r.teacher_id = ? OR r.class_id IN (" +
                    "SELECT cc.class_id FROM course_class cc JOIN course c ON c.id = cc.course_id " +
                    "JOIN teacher t ON t.id = c.teacher_id JOIN user tu ON tu.real_name = t.real_name " +
                    "WHERE tu.id = ?)) ORDER BY r.id DESC LIMIT 100", userId, userId);
        }
        return ResponseEntity.ok(decorate(rows));
    }

    /** 审批：action=approve|reject */
    @PostMapping("/{id}/review")
    public ResponseEntity<?> review(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
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
            boolean isTeacher = admin || isClassTeacher(userId, classId);
            if (!isTeacher) {
                return ResponseEntity.status(403).body(Map.of("error", "只有班主任或管理员可以审批"));
            }
            if ("reject".equals(action)) {
                jdbc.update("UPDATE leave_request SET status='REJECTED', teacher_id_op=?, teacher_comment=?, teacher_at=NOW() WHERE id=?",
                        userId, comment, id);
                return ResponseEntity.ok(Map.of("msg", "已驳回", "status", "REJECTED"));
            }
            if ("LEAVE".equals(str(r.get("type"))) && days > 3) {
                jdbc.update("UPDATE leave_request SET status='PENDING_LEADER', teacher_id_op=?, teacher_comment=?, teacher_at=NOW() WHERE id=?",
                        userId, comment, id);
                return ResponseEntity.ok(Map.of("msg", "初审通过，已提交学生处终审（超过 3 天）", "status", "PENDING_LEADER"));
            }
            jdbc.update("UPDATE leave_request SET status='APPROVED', teacher_id_op=?, teacher_comment=?, teacher_at=NOW() WHERE id=?",
                    userId, comment, id);
            return ResponseEntity.ok(Map.of("msg", "审批通过", "status", "APPROVED"));
        }

        if ("PENDING_LEADER".equals(status)) {
            if (!admin) {
                return ResponseEntity.status(403).body(Map.of("error", "仅学生处/管理员可终审"));
            }
            if ("reject".equals(action)) {
                jdbc.update("UPDATE leave_request SET status='REJECTED', leader_id_op=?, leader_comment=?, leader_at=NOW() WHERE id=?",
                        userId, comment, id);
                return ResponseEntity.ok(Map.of("msg", "已驳回", "status", "REJECTED"));
            }
            jdbc.update("UPDATE leave_request SET status='APPROVED', leader_id_op=?, leader_comment=?, leader_at=NOW() WHERE id=?",
                    userId, comment, id);
            return ResponseEntity.ok(Map.of("msg", "终审通过", "status", "APPROVED"));
        }

        return ResponseEntity.badRequest().body(Map.of("error", "该申请当前状态不可审批"));
    }

    // ==================== 工具 ====================

    private List<Map<String, Object>> decorate(List<Map<String, Object>> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            String status = str(r.get("status"));
            m.put("statusText", switch (status) {
                case "PENDING_TEACHER" -> "待班主任审批";
                case "PENDING_LEADER" -> "待学生处终审";
                case "APPROVED" -> "已通过";
                case "REJECTED" -> "已驳回";
                case "CANCELED" -> "已取消";
                case "RETURNED" -> "已返校";
                default -> status;
            });
            m.put("typeText", "OUTING".equals(str(r.get("type"))) ? "临时外出" : "请假");
            m.put("startText", fmt(r.get("start_time")));
            m.put("endText", fmt(r.get("end_time")));
            m.put("createdText", fmt(r.get("created_at")));
            list.add(m);
        }
        return list;
    }

    private String fmt(Object o) {
        if (o instanceof java.time.LocalDateTime ldt) return ldt.format(FMT);
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().format(FMT);
        return o == null ? "" : String.valueOf(o).replace('T', ' ');
    }

    private Long resolveClassTeacher(Long classId) {
        if (classId == null) return null;
        List<Long> ids = jdbc.queryForList(
                "SELECT u.id FROM course_class cc JOIN course c ON c.id = cc.course_id " +
                        "JOIN teacher t ON t.id = c.teacher_id JOIN user u ON u.real_name = t.real_name " +
                        "WHERE cc.class_id = ? LIMIT 1", Long.class, classId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private boolean isClassTeacher(Long userId, Long classId) {
        if (classId == null) return false;
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_class cc JOIN course c ON c.id = cc.course_id " +
                        "JOIN teacher t ON t.id = c.teacher_id JOIN user u ON u.real_name = t.real_name " +
                        "WHERE cc.class_id = ? AND u.id = ?", Integer.class, classId, userId);
        return n != null && n > 0;
    }

    private boolean hasRole(Authentication auth, String role) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    private Long uid(Authentication auth) {
        return (auth != null && auth.getPrincipal() instanceof Long id) ? id : null;
    }

    private Long firstLong(String sql, Object... args) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private boolean isTrue(Object o) {
        if (o instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o));
    }
}
