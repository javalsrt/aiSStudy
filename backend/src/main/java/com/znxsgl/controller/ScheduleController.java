package com.znxsgl.controller;

import com.znxsgl.dto.SemesterDTO;
import com.znxsgl.dto.StudentCourseDTO;
import com.znxsgl.dto.StudentScheduleDTO;
import com.znxsgl.dto.TeacherCourseDTO;
import com.znxsgl.service.ScheduleService;
import com.znxsgl.service.SemesterService;
import com.znxsgl.websocket.ScheduleWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 课表查询接口
 *
 * 权限说明：
 * - /teacher/** 仅教师或管理员可访问
 * - /student/** 仅学生或管理员可访问
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final SemesterService semesterService;
    private final JdbcTemplate jdbc;
    private final ScheduleWebSocketHandler wsHandler;

    public ScheduleController(ScheduleService scheduleService, SemesterService semesterService,
                              JdbcTemplate jdbc, ScheduleWebSocketHandler wsHandler) {
        this.scheduleService = scheduleService;
        this.semesterService = semesterService;
        this.jdbc = jdbc;
        this.wsHandler = wsHandler;
    }

    // 教师/管理员：查看课程列表（教师看自己的，管理员看所有）
    @GetMapping("/teacher/courses")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<List<TeacherCourseDTO>> getTeacherCourses(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) {
            return ResponseEntity.ok(scheduleService.getAllCoursesForAdmin());
        }
        return ResponseEntity.ok(scheduleService.getTeacherCourses(userId));
    }

    // 学生：查看我的课程列表（含上下架状态）
    @GetMapping("/student/courses")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<List<StudentCourseDTO>> getStudentCourses(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(scheduleService.getStudentCourses(userId));
    }

    // 学生：查看我的课表（?week=13&semester=xxx）
    @GetMapping("/student/my")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<List<StudentScheduleDTO>> getMySchedule(
            Authentication auth,
            @RequestParam(defaultValue = "0") int week,
            @RequestParam(required = false) String semester)
    {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(scheduleService.getStudentSchedule(userId, week, semester));
    }

    /**
     * 学生：查看我的课表 + 学期信息（含放假提示）
     * 支持传入 semester 查询指定学期；不传则查询当前学期。
     *
     * 响应示例（暑假期间）：
     * {
     *   "semester": {
     *     "name": "2026-2027-1",
     *     "startDate": "2026-09-01",
     *     "status": "before",
     *     "notice": "距开学还有 39 天（2026-09-01 开学）"
     *   },
     *   "schedules": []
     * }
     *
     * 前端根据 semester.status 判断显示：
     * - "before" / "ended" → 居中显示 notice，隐藏课表网格
     * - "ongoing"          → 正常显示 schedules
     */
    @GetMapping("/student/my-with-semester")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getMyScheduleWithSemester(Authentication auth,
                                                                          @RequestParam(defaultValue = "0") int week,
                                                                          @RequestParam(required = false) String semester) {
        Long userId = (Long) auth.getPrincipal();
        SemesterDTO semesterDto = semesterService.getSemesterWithStatusByName(semester);
        List<StudentScheduleDTO> schedules = scheduleService.getStudentSchedule(userId, week, semester);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("semester", semesterDto);
        result.put("schedules", schedules);
        return ResponseEntity.ok(result);
    }

    // 学生：获取我可见的学期列表（正常学期 + 本班假期培训）
    @GetMapping("/student/semesters")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<List<SemesterDTO>> getStudentSemesters(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(scheduleService.getStudentSemesters(userId));
    }

    /**
     * 学生：同班在线人数（WebSocket 实时在线，排除自己）
     * 响应：{ "count": 2, "names": ["张三", "李四"] }
     */
    @GetMapping("/student/online-classmates")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getOnlineClassmates(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> userRows = jdbc.queryForList(
            "SELECT class_id FROM user WHERE id = ? AND role = 1", userId);
        if (userRows.isEmpty() || userRows.get(0).get("class_id") == null) {
            result.put("count", 0);
            result.put("names", Collections.emptyList());
            return ResponseEntity.ok(result);
        }
        Long classId = ((Number) userRows.get(0).get("class_id")).longValue();
        Set<Long> onlineIds = wsHandler.getOnlineStudentIds();
        List<Map<String, Object>> classmates = jdbc.queryForList(
            "SELECT id, real_name FROM user WHERE class_id = ? AND role = 1 AND id != ?",
            classId, userId);
        List<String> onlineNames = new ArrayList<>();
        for (Map<String, Object> row : classmates) {
            Object idObj = row.get("id");
            Long uid = idObj instanceof Number ? ((Number) idObj).longValue() : null;
            if (uid != null && onlineIds.contains(uid)) {
                onlineNames.add(row.get("real_name") != null ? row.get("real_name").toString() : "");
            }
        }
        result.put("count", onlineNames.size());
        result.put("names", onlineNames);
        return ResponseEntity.ok(result);
    }

    /**
     * 学生：查看通讯录（同班同学 + 教我的老师）
     */
    @GetMapping("/student/contacts")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getStudentContacts(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();

        // 查询当前学生所在班级
        List<Map<String, Object>> userRows = jdbc.queryForList(
            "SELECT class_id, real_name FROM user WHERE id = ? AND role = 1", userId);
        if (userRows.isEmpty() || userRows.get(0).get("class_id") == null) {
            return ResponseEntity.ok(Map.of("classmates", Collections.emptyList(),
                    "teachers", Collections.emptyList()));
        }
        Long classId = ((Number) userRows.get(0).get("class_id")).longValue();

        // 同班同学：同班级、角色为学生、排除自己
        List<Map<String, Object>> classmates = jdbc.queryForList(
            "SELECT id AS userId, real_name AS realName, student_no AS studentNo " +
            "FROM user WHERE class_id = ? AND role = 1 AND id != ? " +
            "ORDER BY student_no, real_name", classId, userId);

        // 教我的老师：取本班有排课（status=1）或 course_class 关联的课程的教师，聚合课程名
        List<Map<String, Object>> teachers = jdbc.queryForList(
            "SELECT t.id AS teacherId, t.real_name AS realName, t.title, " +
            "GROUP_CONCAT(DISTINCT c.course_name ORDER BY c.course_name SEPARATOR '、') AS courseNames " +
            "FROM course c " +
            "JOIN teacher t ON t.id = c.teacher_id " +
            "WHERE c.id IN ( " +
            "  SELECT DISTINCT s.course_id FROM schedule s " +
            "  JOIN user u ON u.id = s.user_id " +
            "  WHERE u.class_id = ? AND s.status = 1 AND s.course_id IS NOT NULL " +
            "  UNION " +
            "  SELECT cc.course_id FROM course_class cc WHERE cc.class_id = ? " +
            ") " +
            "GROUP BY t.id, t.real_name, t.title " +
            "ORDER BY t.real_name", classId, classId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classmates", classmates);
        result.put("teachers", teachers);
        return ResponseEntity.ok(result);
    }

    // 教师：按班级+周查看课表（用于排课时空位查看，week=0 表示查所有周）
    // courseName 可选，用于计算当前课程的实际最大周次，避免被学期总周数限制
    @GetMapping("/teacher/class-schedule")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getClassSchedule(
            @RequestParam Long classId,
            @RequestParam int week,
            @RequestParam(required = false) String courseName,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        // 非管理员需校验班级归属当前教师
        if (!isAdmin) {
            Long teacherId = resolveTeacherId(userId);
            if (teacherId == null || !classBelongsToTeacher(classId, teacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
            }
        }

        List<Map<String, Object>> schedules;
        try {
            if (week > 0) {
                // 仅返回已上架(status=1)的课表，并按学生维度聚合为唯一课程安排
                schedules = jdbc.queryForList(
                    "SELECT MIN(s.id) AS id, s.course_name, s.day_of_week, s.start_time, s.end_time, " +
                    "s.start_node, s.step, s.classroom, s.weeks, s.course_id, s.status, " +
                    "COUNT(DISTINCT s.user_id) AS student_count " +
                    "FROM schedule s " +
                    "JOIN user u ON u.id = s.user_id " +
                    "WHERE u.class_id = ? AND s.day_of_week > 0 AND s.status = 1 " +
                    "AND JSON_CONTAINS(s.weeks, CAST(? AS JSON)) " +
                    "GROUP BY s.course_name, s.day_of_week, s.start_time, s.end_time, " +
                    "s.start_node, s.step, s.classroom, s.weeks, s.course_id, s.status " +
                    "ORDER BY s.day_of_week, s.start_time",
                    classId, week);
            } else {
                schedules = jdbc.queryForList(
                    "SELECT MIN(s.id) AS id, s.course_name, s.day_of_week, s.start_time, s.end_time, " +
                    "s.start_node, s.step, s.classroom, s.weeks, s.course_id, s.status, " +
                    "COUNT(DISTINCT s.user_id) AS student_count " +
                    "FROM schedule s " +
                    "JOIN user u ON u.id = s.user_id " +
                    "WHERE u.class_id = ? AND s.day_of_week > 0 AND s.status = 1 " +
                    "GROUP BY s.course_name, s.day_of_week, s.start_time, s.end_time, " +
                    "s.start_node, s.step, s.classroom, s.weeks, s.course_id, s.status " +
                    "ORDER BY s.day_of_week, s.start_time",
                    classId);
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "课表周次数据格式异常，请联系管理员检查数据：" + e.getMessage()));
        }

        // 获取班级信息（改用 queryForList + 判空，避免 EmptyResultDataAccessException）
        List<Map<String, Object>> classRows = jdbc.queryForList(
            "SELECT id, class_name FROM class_info WHERE id = ?", classId);
        if (classRows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "班级不存在"));
        }
        Map<String, Object> classInfo = classRows.get(0);

        // 计算该班级已上架课程中的最大周次，避免排课弹窗被学期总周数限制而看不到后面的周
        List<String> weekStrs = jdbc.queryForList(
            "SELECT DISTINCT s.weeks FROM schedule s JOIN user u ON u.id = s.user_id " +
            "WHERE u.class_id = ? AND s.day_of_week > 0 AND s.status = 1", String.class, classId);
        int maxWeek = calcMaxWeek(weekStrs);

        // 若指定了课程名称，额外计算该课程在当前班级的实际最大周次，排课弹窗优先以此为准
        List<String> courseWeekStrs = Collections.emptyList();
        int courseMaxWeek = 0;
        if (courseName != null && !courseName.trim().isEmpty()) {
            courseWeekStrs = jdbc.queryForList(
                "SELECT DISTINCT s.weeks FROM schedule s JOIN user u ON u.id = s.user_id " +
                "WHERE u.class_id = ? AND s.course_name = ? AND s.day_of_week > 0 AND s.status = 1",
                String.class, classId, courseName.trim());
            courseMaxWeek = calcMaxWeek(courseWeekStrs);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("classId", classId);
        result.put("className", classInfo.get("class_name"));
        result.put("week", week);
        result.put("schedules", schedules);
        result.put("maxWeek", maxWeek);
        result.put("courseMaxWeek", courseMaxWeek);
        result.put("debugCourseName", courseName);
        result.put("debugCourseWeeks", courseWeekStrs);
        return ResponseEntity.ok(result);
    }

    /** 从 weeks JSON 数组字符串列表中计算最大周次 */
    private int calcMaxWeek(List<String> weekStrs) {
        int maxWeek = 0;
        for (String ws : weekStrs) {
            if (ws == null) continue;
            String stripped = ws.replaceAll("[\\[\\]\\s]", "");
            if (stripped.isEmpty()) continue;
            for (String s : stripped.split(",")) {
                try {
                    maxWeek = Math.max(maxWeek, Integer.parseInt(s.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return maxWeek;
    }

    /** 通过 userId 反查 teacher 表的 id（依据 real_name 关联） */
    private Long resolveTeacherId(Long userId) {
        try {
            List<Long> ids = jdbc.queryForList(
                "SELECT t.id FROM teacher t " +
                "JOIN user u ON u.real_name = t.real_name " +
                "WHERE u.id = ? LIMIT 1", Long.class, userId);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 校验班级是否归属当前教师（通过 course_class + course 关联） */
    private boolean classBelongsToTeacher(Long classId, Long teacherId) {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_class cc JOIN course c ON c.id = cc.course_id " +
                "WHERE cc.class_id = ? AND c.teacher_id = ?", Integer.class, classId, teacherId);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // 教师：获取课程在指定班级的未上架 schedule 记录（用于查看课程原始时间段）
    @GetMapping("/teacher/course-schedule")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> getCourseSchedule(
            @RequestParam String courseName,
            @RequestParam Long classId,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        // 非管理员需校验班级归属当前教师
        if (!isAdmin) {
            Long teacherId = resolveTeacherId(userId);
            if (teacherId == null || !classBelongsToTeacher(classId, teacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
            }
        }
        List<Map<String, Object>> records = jdbc.queryForList(
            "SELECT DISTINCT s.day_of_week, s.start_time, s.end_time, s.start_node, s.step, " +
            "s.classroom, s.weeks, s.semester, s.course_name " +
            "FROM schedule s " +
            "JOIN user u ON u.id = s.user_id " +
            "WHERE s.course_name = ? AND u.class_id = ? AND s.status = 0",
            courseName, classId);
        return ResponseEntity.ok(records);
    }
}
