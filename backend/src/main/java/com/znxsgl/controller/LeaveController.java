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
    private final com.znxsgl.service.GeoService geoService;

    public LeaveController(JdbcTemplate jdbc, com.znxsgl.service.GeoService geoService) {
        this.jdbc = jdbc;
        this.geoService = geoService;
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
        fillMissingAddress(rows, 3);
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
                                          @RequestParam(value = "address", required = false) String address,
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
        try {
            jdbc.update("UPDATE leave_request SET status = 'RETURNED', return_photo = ?, return_lat = ?, " +
                            "return_lng = ?, return_address = ?, return_at = NOW() WHERE id = ?",
                    url, lat, lng, address == null || address.isBlank() ? null : address.trim(), id);
        } catch (Exception e) {
            // 未执行迁移 26 时降级：地址不入库，其余照常
            jdbc.update("UPDATE leave_request SET status = 'RETURNED', return_photo = ?, return_lat = ?, " +
                    "return_lng = ?, return_at = NOW() WHERE id = ?", url, lat, lng, id);
        }
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
                "ht.real_name AS headTeacherName, t1.real_name AS teacherName FROM leave_request r " +
                "LEFT JOIN user u ON u.id = r.user_id " +
                "LEFT JOIN class_info ci ON ci.id = r.class_id " +
                "LEFT JOIN teacher ht ON ht.id = ci.head_teacher_id " +
                "LEFT JOIN user t1 ON t1.id = r.teacher_id_op " +
                "WHERE r.status IN ('PENDING_TEACHER','PENDING_LEADER') ";
        boolean leader = isLeader(userId);
        if (admin || leader) {
            // 管理员 / 领导：全校请假，按班级、状态排序
            rows = jdbc.queryForList(sql + "ORDER BY ci.class_name, r.id DESC LIMIT 200");
        } else {
            rows = jdbc.queryForList(sql + "AND (r.teacher_id = ? OR ci.head_teacher_id IN (" +
                    "SELECT t.id FROM teacher t JOIN user tu ON tu.real_name = t.real_name WHERE tu.id = ?)) " +
                    "ORDER BY r.id DESC LIMIT 100", userId, userId);
        }
        List<Map<String, Object>> list = decorate(rows);
        for (Map<String, Object> row : list) {
            String st = str(row.get("status"));
            Long cid = row.get("class_id") instanceof Number n ? n.longValue() : null;
            boolean can = "PENDING_TEACHER".equals(st)
                    ? (admin || leader || isClassTeacher(userId, cid))
                    : (admin || leader);
            row.put("canReview", can);
            row.put("reviewStage", "PENDING_LEADER".equals(st) ? "领导终审" : "班主任审批");
        }
        return ResponseEntity.ok(list);
    }

    /** 已处理记录：审批完成后的申请（含返校打卡信息），教师看本班，领导/管理员看全校 */
    @GetMapping("/records")
    public ResponseEntity<?> records(@RequestParam(value = "limit", defaultValue = "200") int limit,
                                     Authentication auth) {
        Long userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        int n = Math.min(Math.max(limit, 1), 500);
        boolean admin = hasRole(auth, "ROLE_ADMIN");
        boolean leader = isLeader(userId);

        String sql = "SELECT r.*, u.real_name AS studentName, u.student_no AS studentNo, ci.class_name AS className, " +
                "ht.real_name AS headTeacherName, t1.real_name AS teacherName, t2.real_name AS leaderName " +
                "FROM leave_request r " +
                "LEFT JOIN user u ON u.id = r.user_id " +
                "LEFT JOIN class_info ci ON ci.id = r.class_id " +
                "LEFT JOIN teacher ht ON ht.id = ci.head_teacher_id " +
                "LEFT JOIN user t1 ON t1.id = r.teacher_id_op " +
                "LEFT JOIN user t2 ON t2.id = r.leader_id_op " +
                "WHERE r.status IN ('APPROVED','REJECTED','RETURNED','CANCELED') ";
        List<Map<String, Object>> rows;
        if (admin || leader) {
            rows = jdbc.queryForList(sql + "ORDER BY r.id DESC LIMIT ?", n);
        } else {
            rows = jdbc.queryForList(sql + "AND (r.teacher_id = ? OR ci.head_teacher_id IN (" +
                            "SELECT t.id FROM teacher t JOIN user tu ON tu.real_name = t.real_name WHERE tu.id = ?)) " +
                            "ORDER BY r.id DESC LIMIT ?", userId, userId, n);
        }
        fillMissingAddress(rows, 8);
        return ResponseEntity.ok(decorate(rows));
    }

    /** 批量审批：body = { ids:[...], action: approve|reject, comment: "" } */
    @PostMapping("/batch-review")
    public ResponseEntity<?> batchReview(@RequestBody Map<String, Object> body, Authentication auth) {
        Long userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        if (!(body.get("ids") instanceof List<?> raw) || raw.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先勾选要审批的申请"));
        }
        String action = "reject".equalsIgnoreCase(str(body.get("action"))) ? "reject" : "approve";
        String comment = str(body.get("comment")).trim();

        int ok = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Object o : raw) {
            Long id = o instanceof Number num ? num.longValue() : null;
            if (id == null) continue;
            try {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("action", action);
                one.put("comment", comment);
                ResponseEntity<?> r = doReview(id, one, auth);
                if (r.getStatusCode().is2xxSuccessful()) {
                    ok++;
                } else {
                    Object err = r.getBody() instanceof Map<?, ?> m ? m.get("error") : null;
                    failed.add(Map.of("id", id, "error", err == null ? "审批失败" : String.valueOf(err)));
                }
            } catch (Exception e) {
                failed.add(Map.of("id", id, "error", String.valueOf(e.getMessage())));
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", ok);
        resp.put("failed", failed);
        resp.put("msg", "已" + ("reject".equals(action) ? "驳回" : "通过") + " " + ok + " 条"
                + (failed.isEmpty() ? "" : "，" + failed.size() + " 条失败"));
        return ResponseEntity.ok(resp);
    }

    /** 审批：action=approve|reject */
    @PostMapping("/{id}/review")
    public ResponseEntity<?> review(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        // 统一兜底：把底层 SQL 异常转成可读提示，避免前端只看到 Internal Server Error
        try {
            return doReview(id, body, auth);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            return ResponseEntity.badRequest().body(Map.of("error",
                    "审批失败：" + detail + "（若提示字段不存在，请执行 init-db/25-catchup-safe.sql）"));
        }
    }

    private ResponseEntity<?> doReview(Long id, Map<String, Object> body, Authentication auth) {
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
            // 初审权：该班班主任 / 校领导 / 管理员
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
            // 终审权：校领导 / 管理员
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

    // ==================== 工具 ====================

    /**
     * 老记录只存了经纬度：配置了高德 Key 时按需反查地址并回写数据库。
     * 单次最多处理 limit 条，避免页面加载被外部接口拖慢。
     */
    private void fillMissingAddress(List<Map<String, Object>> rows, int limit) {
        if (!geoService.enabled() || rows == null || rows.isEmpty()) return;
        int done = 0;
        for (Map<String, Object> r : rows) {
            if (done >= limit) break;
            Object addr = r.get("return_address");
            if (addr != null && !String.valueOf(addr).isBlank()) continue;
            if (!(r.get("return_lat") instanceof Number lat) || !(r.get("return_lng") instanceof Number lng)) continue;
            if (!(r.get("id") instanceof Number idNum)) continue;
            String text = geoService.reverse(lat.doubleValue(), lng.doubleValue());
            if (text == null) continue;
            try {
                jdbc.update("UPDATE leave_request SET return_address = ? WHERE id = ?", text, idNum.longValue());
                r.put("return_address", text);
                done++;
            } catch (Exception ignored) {
            }
        }
    }

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
            m.put("teacherReviewerRole", r.get("teacher_reviewer_role"));
            m.put("leaderReviewerRole", r.get("leader_reviewer_role"));
            m.put("headTeacherName", r.get("headTeacherName"));
            m.put("teacherName", r.get("teacherName"));
            m.put("leaderName", r.get("leaderName"));
            // 返校打卡（定位 + 拍照）
            m.put("returnPhoto", r.get("return_photo"));
            m.put("returnLat", r.get("return_lat"));
            m.put("returnLng", r.get("return_lng"));
            m.put("returnAtText", r.get("return_at") == null ? null : fmt(r.get("return_at")));
            m.put("returnAddress", r.get("return_address"));
            m.put("hasCheckIn", r.get("return_at") != null);
            list.add(m);
        }
        return list;
    }

    private String fmt(Object o) {
        if (o instanceof java.time.LocalDateTime ldt) return ldt.format(FMT);
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().format(FMT);
        return o == null ? "" : String.valueOf(o).replace('T', ' ');
    }

    /** 班级班主任（class_info.head_teacher_id → teacher → user 账号） */
    private Long resolveClassTeacher(Long classId) {
        if (classId == null) return null;
        List<Long> ids = jdbc.queryForList(
                "SELECT u.id FROM class_info ci JOIN teacher t ON t.id = ci.head_teacher_id " +
                        "JOIN user u ON u.real_name = t.real_name WHERE ci.id = ? LIMIT 1", Long.class, classId);
        if (!ids.isEmpty()) return ids.get(0);
        // 兜底：未设置班主任时，取该班第一门课的授课教师
        List<Long> fallback = jdbc.queryForList(
                "SELECT u.id FROM course_class cc JOIN course c ON c.id = cc.course_id " +
                        "JOIN teacher t ON t.id = c.teacher_id JOIN user u ON u.real_name = t.real_name " +
                        "WHERE cc.class_id = ? LIMIT 1", Long.class, classId);
        return fallback.isEmpty() ? null : fallback.get(0);
    }

    private boolean isClassTeacher(Long userId, Long classId) {
        if (classId == null) return false;
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_info ci JOIN teacher t ON t.id = ci.head_teacher_id " +
                        "JOIN user u ON u.real_name = t.real_name WHERE ci.id = ? AND u.id = ?",
                Integer.class, classId, userId);
        return n != null && n > 0;
    }

    /** 当前登录人是否校级领导（teacher.is_leader=1） */
    private boolean isLeader(Long userId) {
        if (userId == null) return false;
        List<String> names = jdbc.queryForList("SELECT real_name FROM user WHERE id = ?", String.class, userId);
        if (names.isEmpty()) return false;
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM teacher WHERE real_name = ? AND is_leader = 1", Integer.class, names.get(0));
        return n != null && n > 0;
    }

    /** 审批人身份标记：班主任 / 教导主任 / 管理员 */
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
