package com.znxsgl.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.znxsgl.service.BellTimeService;
import com.znxsgl.service.BellVisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作息时间管理（管理端）：按班级维护节次起止时间。
 * <p>
 * 优先级：班级专属(class_id) > 年级(grade) > 默认(均为空)。
 * 保存后会同步该范围内「已排课」的 schedule.start_time/end_time 并清空作息缓存，立即生效。
 */
@RestController
@RequestMapping("/api/admin/bell")
public class AdminBellController {

    private static final Logger log = LoggerFactory.getLogger(AdminBellController.class);

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** 标准作息建议（2026-2027 秋季学期，教学节次） */
    private static final String[][] PRESET = {
            {"1", "08:55", "09:35"},
            {"2", "09:45", "10:25"},
            {"3", "10:35", "11:15"},
            {"4", "11:25", "12:05"},
            {"5", "14:20", "15:00"},
            {"6", "15:10", "15:50"},
            {"7", "16:00", "16:30"},
            {"8", "19:40", "21:00"},
    };

    private final JdbcTemplate jdbc;
    private final BellTimeService bellTimeService;
    private final BellVisionService bellVisionService;
    private final com.znxsgl.service.BellOcrClient bellOcrClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminBellController(JdbcTemplate jdbc, BellTimeService bellTimeService,
                               BellVisionService bellVisionService,
                               com.znxsgl.service.BellOcrClient bellOcrClient) {
        this.jdbc = jdbc;
        this.bellTimeService = bellTimeService;
        this.bellVisionService = bellVisionService;
        this.bellOcrClient = bellOcrClient;
    }

    /**
     * 作息表图片识别：上传作息时间表截图/照片，AI 视觉提取教学节次时间。
     * 返回 periods 供前端填入表格（不直接落库，需人工核对后保存）。
     */
    @PostMapping("/recognize")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> recognize(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择要识别的图片"));
        }
        String name = String.valueOf(file.getOriginalFilename()).toLowerCase();
        String mime = file.getContentType();
        boolean image = (mime != null && mime.startsWith("image/"))
                || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".bmp");
        if (!image) {
            return ResponseEntity.badRequest().body(Map.of("error", "仅支持图片文件（jpg/png/webp）"));
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "图片不能超过 10MB"));
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "图片读取失败"));
        }

        // 1) 本地 OCR 优先：OpenCV 版式切分 + RapidOCR，离线零成本
        try {
            List<Map<String, Object>> ocrPeriods = bellOcrClient.recognize(bytes, name, mime);
            if (!ocrPeriods.isEmpty()) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("periods", ocrPeriods);
                resp.put("count", ocrPeriods.size());
                resp.put("source", "ocr");
                return ResponseEntity.ok(resp);
            }
            log.warn("[BellOCR] 未识别到教学节次，尝试视觉模型兜底");
        } catch (Exception e) {
            log.warn("[BellOCR] 本地 OCR 不可用（{}），尝试视觉模型兜底", e.getMessage());
        }

        // 2) 视觉大模型兜底（需配置 VISION_API_KEY）
        if (bellVisionService.enabled()) {
            try {
                String text = bellVisionService.recognize(bytes, mime);
                List<Map<String, Object>> periods = parsePeriods(text);
                if (!periods.isEmpty()) {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("periods", periods);
                    resp.put("count", periods.size());
                    resp.put("source", "vision");
                    return ResponseEntity.ok(resp);
                }
            } catch (Exception e) {
                return ResponseEntity.ok(Map.of("error", "识别失败：" + e.getMessage()));
            }
        }

        return ResponseEntity.ok(Map.of("error",
                "未识别到教学节次时间：请确认图片是作息时间表；若本地 OCR 服务未启动且未配置视觉模型，也无法识别"));
    }

    /** 解析模型返回的 JSON（容忍 ```json 包裹、前后废话），并规范化时间 */
    private List<Map<String, Object>> parsePeriods(String text) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (text == null) return result;
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return result;
        try {
            JsonNode root = mapper.readTree(cleaned.substring(start, end + 1));
            JsonNode arr = root.path("periods");
            if (!arr.isArray()) return result;
            for (JsonNode p : arr) {
                int node = p.path("node").asInt(-1);
                String st = normalizeTime(p.path("startTime").asText(""));
                String et = normalizeTime(p.path("endTime").asText(""));
                if (node < 1 || node > 12 || st == null || et == null) continue;
                if (st.compareTo(et) >= 0) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("node", node);
                m.put("startTime", st);
                m.put("endTime", et);
                result.add(m);
            }
        } catch (Exception e) {
            // 忽略解析异常，交由上层提示
        }
        result.sort((a, b) -> Integer.compare((int) a.get("node"), (int) b.get("node")));
        return result;
    }

    /** "8:5" / "08：55" → "08:05" / "08:55" */
    private String normalizeTime(String raw) {
        if (raw == null) return null;
        String s = raw.replace('：', ':').replace("—", "-").replace("－", "-").trim();
        // 只取第一个时间片段（防止 "8:55-9:35" 这类脏数据）
        int dash = s.indexOf('-');
        int space = s.indexOf(' ');
        int cut = s.length();
        if (dash > 0) cut = Math.min(cut, dash);
        if (space > 0) cut = Math.min(cut, space);
        s = s.substring(0, cut).trim();
        String[] parts = s.split(":");
        if (parts.length < 2) return null;
        try {
            int h = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
            int m = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return String.format("%02d:%02d", h, m);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 查询某班级（classId 为空=默认作息）当前生效的作息 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> get(
            @RequestParam(value = "classId", required = false) Long classId,
            @RequestParam(value = "semester", required = false) String semester) {

        String sem = resolveSemester(semester);
        String className = null;
        String grade = null;
        if (classId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT class_name, grade FROM class_info WHERE id = ?", classId);
            if (!rows.isEmpty()) {
                className = (String) rows.get(0).get("class_name");
                grade = (String) rows.get(0).get("grade");
            }
        }

        // 该范围自己的配置（class_id 精确匹配；默认范围=class_id 与 grade 均为空）
        List<Map<String, Object>> ownRows = classId != null
                ? jdbc.queryForList("SELECT node, start_time, end_time FROM bell_schedule " +
                        "WHERE class_id = ? AND week_parity = 0 ORDER BY node", classId)
                : jdbc.queryForList("SELECT node, start_time, end_time FROM bell_schedule " +
                        "WHERE class_id IS NULL AND (grade IS NULL OR grade = '') AND week_parity = 0 ORDER BY node");

        // 当前实际生效（class > grade > default）
        List<Map<String, Object>> effective = bellTimeService.bellsByParity(
                classId, grade, bellTimeService.effectiveParity(grade, java.time.LocalDate.now()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("classId", classId);
        data.put("className", className);
        data.put("grade", grade);
        data.put("semester", sem);
        data.put("periods", toPeriods(ownRows));
        data.put("effective", effective);
        data.put("preset", preset());
        return ResponseEntity.ok(data);
    }

    /** 保存某班级（classId 为空=默认作息）的作息，并同步该范围内已排课时间 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        Long classId = parseLong(body.get("classId"));
        String semester = resolveSemester((String) body.get("semester"));
        Object periodsObj = body.get("periods");
        if (!(periodsObj instanceof List<?> list) || list.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "作息内容不能为空"));
        }

        List<LocalTime[]> times = new ArrayList<>();
        List<Integer> nodes = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> p)) continue;
            int node = parseInt(p.get("node"));
            if (node < 1 || node > 12) {
                return ResponseEntity.badRequest().body(Map.of("error", "节次范围错误（1-12）"));
            }
            LocalTime start;
            LocalTime end;
            try {
                start = LocalTime.parse(String.valueOf(p.get("startTime")), HHMM);
                end = LocalTime.parse(String.valueOf(p.get("endTime")), HHMM);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "时间格式应为 HH:mm"));
            }
            if (!start.isBefore(end)) {
                return ResponseEntity.badRequest().body(Map.of("error", "第 " + node + " 节开始时间必须早于结束时间"));
            }
            nodes.add(node);
            times.add(new LocalTime[]{start, end});
        }
        if (nodes.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "作息内容为空或格式错误"));
        }
        if (nodes.stream().distinct().count() != nodes.size()) {
            return ResponseEntity.badRequest().body(Map.of("error", "节次重复"));
        }

        // 目标范围：classIds 批量 > classId 单个 > 默认作息
        List<Long> targets = new ArrayList<>();
        Object idsObj = body.get("classIds");
        if (idsObj instanceof List<?> idList) {
            for (Object o : idList) {
                Long v = parseLong(o);
                if (v != null) targets.add(v);
            }
        }
        Long single = parseLong(body.get("classId"));
        if (targets.isEmpty() && single != null) targets.add(single);
        boolean defaultScope = targets.isEmpty();

        int synced = 0;
        if (defaultScope) {
            // 覆盖默认作息配置
            jdbc.update("DELETE FROM bell_schedule WHERE class_id IS NULL AND (grade IS NULL OR grade = '') " +
                    "AND week_parity = 0 AND COALESCE(semester,'') = ?", semester);
            for (int i = 0; i < nodes.size(); i++) {
                jdbc.update("INSERT INTO bell_schedule (semester, grade, class_id, week_parity, node, start_time, end_time) " +
                                "VALUES (?, '', NULL, 0, ?, ?, ?)",
                        semester, nodes.get(i), times.get(i)[0], times.get(i)[1]);
            }
            // 同步「没有班级专属作息」的班级的已排课时间
            synced = jdbc.update(
                    "UPDATE schedule s " +
                            "JOIN user u ON u.id = s.user_id " +
                            "JOIN bell_schedule b1 ON b1.class_id IS NULL AND b1.grade = '' AND b1.week_parity = 0 AND b1.node = s.start_node " +
                            "JOIN bell_schedule b2 ON b2.class_id IS NULL AND b2.grade = '' AND b2.week_parity = 0 AND b2.node = s.start_node + s.step - 1 " +
                            "SET s.start_time = b1.start_time, s.end_time = b2.end_time " +
                            "WHERE s.semester = ? AND s.day_of_week > 0 " +
                            "  AND NOT EXISTS (SELECT 1 FROM bell_schedule bx WHERE bx.class_id = u.class_id AND bx.week_parity = 0)",
                    semester);
        } else {
            for (Long cid : targets) {
                jdbc.update("DELETE FROM bell_schedule WHERE class_id = ? AND week_parity = 0 AND COALESCE(semester,'') = ?",
                        cid, semester);
                String grade = "";
                List<String> g = jdbc.queryForList("SELECT grade FROM class_info WHERE id = ?", String.class, cid);
                if (!g.isEmpty() && g.get(0) != null) grade = g.get(0);
                for (int i = 0; i < nodes.size(); i++) {
                    jdbc.update("INSERT INTO bell_schedule (semester, grade, class_id, week_parity, node, start_time, end_time) " +
                                    "VALUES (?, ?, ?, 0, ?, ?, ?)",
                            semester, grade, cid, nodes.get(i), times.get(i)[0], times.get(i)[1]);
                }
                synced += jdbc.update(
                        "UPDATE schedule s " +
                                "JOIN user u ON u.id = s.user_id " +
                                "JOIN bell_schedule b1 ON b1.class_id = ? AND b1.week_parity = 0 AND b1.node = s.start_node " +
                                "JOIN bell_schedule b2 ON b2.class_id = ? AND b2.week_parity = 0 AND b2.node = s.start_node + s.step - 1 " +
                                "SET s.start_time = b1.start_time, s.end_time = b2.end_time " +
                                "WHERE u.class_id = ? AND s.semester = ? AND s.day_of_week > 0",
                        cid, cid, cid, semester);
            }
        }

        bellTimeService.evictCache();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("msg", defaultScope
                ? "默认作息已保存"
                : (targets.size() == 1 ? "本班作息已保存" : targets.size() + " 个班级的作息已保存"));
        resp.put("saved", nodes.size());
        resp.put("classes", defaultScope ? 0 : targets.size());
        resp.put("syncedSchedules", synced);
        return ResponseEntity.ok(resp);
    }

    private List<Map<String, Object>> preset() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String[] p : PRESET) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node", Integer.parseInt(p[0]));
            m.put("startTime", p[1]);
            m.put("endTime", p[2]);
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> toPeriods(List<Map<String, Object>> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node", r.get("node"));
            // TIME 列取出来可能是 java.sql.Time，统一转 HH:mm
            m.put("startTime", hhmm(r.get("start_time")));
            m.put("endTime", hhmm(r.get("end_time")));
            list.add(m);
        }
        return list;
    }

    private String hhmm(Object time) {
        if (time == null) return null;
        String s = time.toString();
        return s.length() >= 5 ? s.substring(0, 5) : s;
    }

    private String resolveSemester(String semester) {
        if (semester != null && !semester.isBlank()) return semester.trim();
        var sem = bellTimeService.currentSemester();
        return sem == null ? "" : sem.getName();
    }

    private Long parseLong(Object o) {
        if (o == null) return null;
        try {
            long v = Long.parseLong(String.valueOf(o).trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseInt(Object o) {
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
