package com.znxsgl.controller;

import com.znxsgl.service.BellTimeService;
import com.znxsgl.service.SemesterService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.*;
import java.util.regex.*;

/**
 * 教师班级管理接口
 *
 * 权限：仅教师或管理员可访问。
 */
@RestController
@RequestMapping("/api/teacher/class")
@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
public class TeacherClassController {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SemesterService semesterService;
    private final BellTimeService bellTimeService;

    public TeacherClassController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                   SemesterService semesterService, BellTimeService bellTimeService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.semesterService = semesterService;
        this.bellTimeService = bellTimeService;
    }

    /** 列出教师管理的所有班级 */
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> listClasses(Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long realTeacherId = getRealTeacherId(teacherUserId);
        List<Map<String, Object>> list = jdbc.queryForList(
            "SELECT DISTINCT ci.id, ci.class_name AS className, ci.major, ci.grade, ci.department, " +
            "  (SELECT COUNT(*) FROM user u WHERE u.class_id = ci.id AND u.role = 1) AS studentCount " +
            "FROM class_info ci " +
            "JOIN course_class cc ON cc.class_id = ci.id " +
            "JOIN course c ON c.id = cc.course_id " +
            "WHERE c.teacher_id = ? ORDER BY ci.id", realTeacherId);
        return ResponseEntity.ok(list);
    }

    /** 创建班级（含课程+课表+关联） */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createClass(Authentication auth, @RequestBody Map<String, Object> body) {
        Long teacherUserId = (Long) auth.getPrincipal();
        String className = safeStr(body, "className");
        String major = safeStr(body, "major", "计算机科学与技术");
        String grade = safeStr(body, "grade", "2023");
        String department = safeStr(body, "department", "计算机与信息工程学院");
        String semester = safeStr(body, "semester", semesterService.getCurrentSemesterName());

        // 创建班级
        jdbc.update("INSERT INTO class_info (class_name, major, department, grade) VALUES (?,?,?,?)",
                className, major, department, grade);
        Long classId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classId", classId);
        result.put("className", className);
        result.put("msg", "班级创建成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 教师修改自己课程的每周课时上限（credit）。
     * 管理员可修改任意课程。
     */
    @PostMapping("/update-course-credit")
    public ResponseEntity<Map<String, Object>> updateCourseCredit(Authentication auth, @RequestBody Map<String, Object> body) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long courseId = Long.valueOf(body.get("courseId").toString());
        int credit;
        try {
            credit = Integer.parseInt(body.get("credit").toString());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "credit 必须是数字"));
        }

        // 范围校验：1~20 课时/周，避免极端值
        if (credit < 1 || credit > 20) {
            return ResponseEntity.badRequest().body(Map.of("error", "课时范围必须在 1~20 之间"));
        }

        // 权限校验
        boolean admin = isAdmin(auth);
        if (!admin) {
            Long realTeacherId = getRealTeacherId(teacherUserId);
            if (!courseBelongsToTeacher(courseId, realTeacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限修改该课程"));
            }
        }

        // 更新 credit
        int rows = jdbc.update("UPDATE course SET credit = ? WHERE id = ?", credit, courseId);
        if (rows == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "课程不存在"));
        }
        return ResponseEntity.ok(Map.of("msg", "课程课时上限已更新", "courseId", courseId, "credit", credit));
    }

    /** 导入课表：解析教务系统 HTML → 创建课程 + schedule */
    @PostMapping("/import-schedule")
    public ResponseEntity<Map<String, Object>> importSchedule(Authentication auth, @RequestBody Map<String, Object> body) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long classId = Long.valueOf(body.get("classId").toString());
        String html = body.get("html").toString();
        String semester = safeStr(body, "semester", semesterService.getCurrentSemesterName());

        // 获取真实的 teacher.id（teacher表主键，非user.id）
        Long realTeacherId = getRealTeacherId(teacherUserId);

        // 校验班级归属当前教师
        if (!classBelongsToTeacher(classId, realTeacherId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }

        List<Map<String, String>> courses = parseTimetableHtml(html);

        int createdCourses = 0, createdSchedules = 0;
        Set<String> seenCourses = new HashSet<>();

        for (Map<String, String> item : courses) {
            String courseName = item.get("courseName");
            String classroom = item.get("classroom");
            int dayOfWeek = Integer.parseInt(item.get("dayOfWeek"));
            int startNode = Integer.parseInt(item.get("startNode"));
            int step = Integer.parseInt(item.get("step"));

            // 创建或获取课程
            Long courseId = getOrCreateCourse(courseName, realTeacherId, semester);

            // 避免重复绑定课程-班级关系
            String courseKey = courseId + "-" + classId;
            if (!seenCourses.contains(courseKey)) {
                seenCourses.add(courseKey);
                // 创建 course_class 关联
                int existCC = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM course_class WHERE course_id = ? AND class_id = ?",
                    Integer.class, courseId, classId);
                if (existCC == 0) {
                    jdbc.update("INSERT INTO course_class (course_id, class_id, semester) VALUES (?,?,?)",
                            courseId, classId, semester);
                    createdCourses++;
                }
            }

            // 删除同课程+同日+同时段的旧 schedule（防止重复导入）
            jdbc.update("DELETE FROM schedule WHERE day_of_week = ? AND start_node = ? AND course_id = ? " +
                    "AND user_id IN (SELECT u.id FROM user u WHERE u.class_id = ?)",
                    dayOfWeek, startNode, courseId, classId);

            // student schedule: 该班级所有学生
            List<Long> studentIds = jdbc.queryForList(
                "SELECT id FROM user WHERE class_id = ? AND role = 1", Long.class, classId);

            for (Long sid : studentIds) {
                // 根据节次推算时间
                LocalTime st = nodeToTime(classId, startNode);
                LocalTime et = nodeToTime(classId, startNode + step);
                jdbc.update("INSERT INTO schedule (user_id, course_id, course_name, day_of_week, " +
                        "start_node, step, start_time, end_time, classroom, semester, weeks, status) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,1)",
                        sid, courseId, courseName, dayOfWeek, startNode, step, st, et,
                        classroom, semester,
                        "[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18]");
                createdSchedules++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("courses", createdCourses);
        result.put("schedules", createdSchedules);
        if (courses.isEmpty()) {
            result.put("msg", "解析失败：未找到课程数据。请确认复制的是 <table id=\"tablecs\"> 部分，而非整个页面或其他内容。");
        } else {
            result.put("msg", "导入成功：" + courses.size() + "个时段，覆盖 " + seenCourses.size() + " 门课程");
        }
        return ResponseEntity.ok(result);
    }

    /** 批量导入学生 */
    @PostMapping("/import-students")
    public ResponseEntity<Map<String, Object>> importStudents(Authentication auth, @RequestBody Map<String, Object> body) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long classId = Long.valueOf(body.get("classId").toString());
        String studentText = body.get("students").toString();
        String defaultPassword = safeStr(body, "password", "123456");

        // 校验班级归属当前教师
        Long realTeacherId = getRealTeacherId(teacherUserId);
        if (!classBelongsToTeacher(classId, realTeacherId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }

        String encodedPwd = passwordEncoder.encode(defaultPassword);
        int created = 0;
        List<String> errors = new ArrayList<>();

        for (String line : studentText.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 支持格式：学号 \t 姓名  或  学号,姓名
            String[] parts = line.split("[\\t,]");
            if (parts.length < 2) {
                errors.add("格式错误: " + line);
                continue;
            }
            String studentNo = parts[0].trim();
            String realName = parts[1].trim();

            // 检查学号是否已存在
            int exist = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user WHERE student_no = ?", Integer.class, studentNo);
            if (exist > 0) {
                errors.add("学号已存在: " + studentNo);
                continue;
            }

            jdbc.update("INSERT INTO user (student_no, username, password_hash, real_name, role, class_id, major, grade, status) " +
                    "VALUES (?,?,?,?,1,?,(SELECT major FROM class_info WHERE id=?), (SELECT grade FROM class_info WHERE id=?), 1)",
                    studentNo, studentNo, encodedPwd, realName, classId, classId, classId);
            created++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("errors", errors);
        result.put("msg", String.format("成功添加 %d 名学生", created));
        return ResponseEntity.ok(result);
    }

    // ===== 辅助方法 =====

    /** 解析教务系统课表 HTML */
    private List<Map<String, String>> parseTimetableHtml(String html) {
        List<Map<String, String>> result = new ArrayList<>();

        // 清理 HTML
        String clean = html.replace("\\n", "\n").replace("\\t", "").replace("\\r", "");

        System.out.println("=== 课表解析: 收到 " + clean.length() + " 字符 ===");
        System.out.println("头部: " + clean.substring(0, Math.min(150, clean.length())));

        // 提取 <table id="tablecs"> ... </table>
        Pattern tableExtract = Pattern.compile(
            "<table[^>]*id\\s*=\\s*[\"']?tablecs[\"']?[^>]*>(.*?)</table>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher tableMatcher = tableExtract.matcher(clean);
        if (tableMatcher.find()) {
            clean = "<table>" + tableMatcher.group(1) + "</table>";
            System.out.println("提取表格后: " + clean.length() + " 字符");
        } else {
            System.out.println("WARN: 未找到 <table id=tablecs>！");
        }

        // 解码 HTML 实体（保留原始顺序，先处理 & 本身）
        String entityDecoded = clean
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");

        // 统计: 有多少个包含 课程名称 的 title 属性
        int rawCount = 0;
        Pattern cntP = Pattern.compile("title=\"[^\"]*课程名称[^\"]*\"");
        Matcher cntM = cntP.matcher(entityDecoded);
        while (cntM.find()) rawCount++;
        System.out.println("title含课程名称: " + rawCount + " 个");

        // 匹配所有 <p ... title="...课程名称..."> 的 title 内容
        Pattern titlePattern = Pattern.compile(
            "title=\"([^\"]*?课程名称[^\"]*)\"",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = titlePattern.matcher(entityDecoded);
        Set<String> seen = new HashSet<>();
        int parsedCount = 0;

        while (m.find()) {
            String title = m.group(1);
            String courseName = "", dayStr = "", startNodeStr = "", endNodeStr = "", classroom = "";

            // 去掉 title 内残留的 HTML 标签/实体
            String cleanTitle = title
                .replace("<br/>", "\n").replace("<br />", "\n").replace("<br>", "\n")
                .replace("</p>", "").replace("<p>", "");

            String[] fields = cleanTitle.split("\n");
            for (String field : fields) {
                field = field.replaceAll("<[^>]+>", "").trim();
                if (field.isEmpty()) continue;

                if (field.contains("课程名称")) {
                    courseName = field.replaceAll(".*[：:]\\s*", "").trim();
                } else if (field.contains("上课时间")) {
                    String timeInfo = field.replaceAll(".*[：:]\\s*", "").trim();
                    Pattern timePat = Pattern.compile("第\\d+周\\s*(星期[一二三四五六日])\\s*\\[?(\\d+)\\s*-\\s*(\\d+)\\s*节?\\]?");
                    Matcher t = timePat.matcher(timeInfo);
                    if (t.find()) {
                        dayStr = t.group(1);
                        startNodeStr = t.group(2);
                        endNodeStr = t.group(3);
                    }
                } else if (field.contains("上课地点")) {
                    classroom = field.replaceAll(".*[：:]\\s*", "").trim();
                }
            }

            if (!courseName.isEmpty() && !dayStr.isEmpty() && !startNodeStr.isEmpty()) {
                parsedCount++;
                int dayOfWeek = parseDayOfWeek(dayStr);
                int startNode = Integer.parseInt(startNodeStr);
                int endNode = Integer.parseInt(endNodeStr);
                int step = endNode - startNode + 1;

                String key = courseName + "-" + dayOfWeek + "-" + startNode;
                if (!seen.contains(key)) {
                    seen.add(key);
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("courseName", courseName);
                    item.put("dayOfWeek", String.valueOf(dayOfWeek));
                    item.put("startNode", String.valueOf(startNode));
                    item.put("step", String.valueOf(step));
                    item.put("classroom", classroom);
                    result.add(item);
                }
            }
        }

        System.out.println("成功解析: " + parsedCount + " 个, 去重后: " + result.size());
        return result;
    }

    private int parseDayOfWeek(String dayStr) {
        String[] days = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        for (int i = 0; i < days.length; i++) {
            if (dayStr.contains(days[i])) return i + 1;
        }
        return 1;
    }

    /** 获取或创建课程（按教师+课程名去重） */
    private Long getOrCreateCourse(String courseName, Long teacherId, String semester) {
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM course WHERE course_name = ? AND teacher_id = ?",
            Long.class, courseName, teacherId);
        if (!ids.isEmpty()) return ids.get(0);

        jdbc.update("INSERT INTO course (course_name, teacher_id, semester, course_type) VALUES (?,?,?,'必修')",
                courseName, teacherId, semester);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String safeStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }

    private String safeStr(Map<String, Object> m, String key, String defaultVal) {
        Object v = m.get(key);
        return v != null && !v.toString().isEmpty() ? v.toString() : defaultVal;
    }

    /** 从 user 表推断 teacher 表的真实 ID */
    private Long getRealTeacherId(Long userId) {
        try {
            Map<String, Object> u = jdbc.queryForMap("SELECT real_name FROM user WHERE id = ?", userId);
            String realName = (String) u.get("real_name");
            return jdbc.queryForObject(
                "SELECT id FROM teacher WHERE real_name = ? LIMIT 1", Long.class, realName);
        } catch (Exception e) {
            return userId; // fallback
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

    /** 校验课程是否归属当前教师 */
    private boolean courseBelongsToTeacher(Long courseId, Long teacherId) {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course WHERE id = ? AND teacher_id = ?",
                Integer.class, courseId, teacherId);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 判断当前登录用户是否为管理员 */
    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    /** 班级年级（如 2026级），解析失败返回 null */
    private String resolveGrade(Long classId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT grade FROM class_info WHERE id = ? LIMIT 1", classId);
            if (!rows.isEmpty() && rows.get(0).get("grade") != null) {
                return rows.get(0).get("grade").toString();
            }
        } catch (Exception ignore) { }
        return null;
    }

    /** 节次转时间：优先按班级年级作息表自动适配（导入覆盖整学期，取通用表），无配置回退内置表 */
    private LocalTime nodeToTime(Long classId, int node) {
        try {
            String grade = resolveGrade(classId);
            LocalTime t = bellTimeService.nodeStartTime(grade, null, node);
            if (t != null) return t;
        } catch (Exception ignore) { }
        return switch (node) {
            case 1 -> LocalTime.of(8, 30);
            case 2 -> LocalTime.of(9, 15);
            case 3 -> LocalTime.of(10, 10);
            case 4 -> LocalTime.of(10, 55);
            case 5 -> LocalTime.of(11, 40);
            case 6 -> LocalTime.of(14, 20);
            case 7 -> LocalTime.of(15, 5);
            case 8 -> LocalTime.of(16, 0);
            case 9 -> LocalTime.of(16, 45);
            case 10 -> LocalTime.of(19, 10);
            case 11 -> LocalTime.of(20, 0);
            case 12 -> LocalTime.of(20, 50);
            default -> LocalTime.of(8, 30);
        };
    }
}
