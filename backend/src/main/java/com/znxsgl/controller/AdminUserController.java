package com.znxsgl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.znxsgl.entity.ClassInfo;
import com.znxsgl.entity.Course;
import com.znxsgl.entity.Teacher;
import com.znxsgl.entity.User;
import com.znxsgl.mapper.ClassInfoMapper;
import com.znxsgl.mapper.CourseMapper;
import com.znxsgl.mapper.TeacherMapper;
import com.znxsgl.mapper.UserMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理员端 - 用户管理接口
 *
 * 包含：
 *   1. 管理员账号管理（role=3）
 *   2. 教师账号管理（role=2）
 *   3. 学生账号管理（role=1）
 *   4. 班级列表、课程列表（下拉选择用
 *
 * 全部接口需要 ADMIN 权限。
 */
@RestController
@RequestMapping("/api/admin/user")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserMapper userMapper;
    private final TeacherMapper teacherMapper;
    private final ClassInfoMapper classInfoMapper;
    private final CourseMapper courseMapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public AdminUserController(UserMapper userMapper,
                               TeacherMapper teacherMapper,
                               ClassInfoMapper classInfoMapper,
                               CourseMapper courseMapper,
                               PasswordEncoder passwordEncoder,
                               JdbcTemplate jdbc) {
        this.userMapper = userMapper;
        this.teacherMapper = teacherMapper;
        this.classInfoMapper = classInfoMapper;
        this.courseMapper = courseMapper;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    // ============================================================
    //  用户列表（按角色分页查询）
    // ============================================================

    /**
     * 分页查询用户列表
     * @param role 角色：1=学生，2=教师，3=管理员
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @param keyword 搜索关键词（姓名/学号/用户名）
     * @param classId 班级ID（学生用）
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam Integer role,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String searchField,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false, defaultValue = "1") Integer status) {
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<User>()
                .eq(User::getRole, role)
                .orderByDesc(User::getCreatedAt);

        // 默认只查询启用状态账号，传 status=-1 可查询全部
        if (status != null && status > 0) {
            qw.eq(User::getStatus, status);
        }

        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            String field = (searchField != null) ? searchField : "all";
            switch (field) {
                case "realName":
                    qw.like(User::getRealName, kw);
                    break;
                case "username":
                    qw.like(User::getUsername, kw);
                    break;
                case "studentNo":
                    qw.like(User::getStudentNo, kw);
                    break;
                case "phone":
                    qw.like(User::getPhone, kw);
                    break;
                case "major":
                    qw.like(User::getMajor, kw);
                    break;
                case "grade":
                    qw.like(User::getGrade, kw);
                    break;
                case "className":
                    // 按班级名称查找 classId，再按 classId 过滤
                    List<ClassInfo> matched = classInfoMapper.selectList(
                            new LambdaQueryWrapper<ClassInfo>().like(ClassInfo::getClassName, kw));
                    if (!matched.isEmpty()) {
                        List<Long> ids = matched.stream().map(ClassInfo::getId).collect(Collectors.toList());
                        qw.in(User::getClassId, ids);
                    } else {
                        // 无匹配班级，返回空结果
                        qw.eq(User::getId, -1L);
                    }
                    break;
                default: // "all" 及未识别字段
                    qw.and(w -> w.like(User::getRealName, kw)
                            .or().like(User::getUsername, kw)
                            .or().like(User::getStudentNo, kw));
                    break;
            }
        }
        if (classId != null) {
            qw.eq(User::getClassId, classId);
        }

        Page<User> page = userMapper.selectPage(new Page<>(pageNum, pageSize), qw);

        List<Map<String, Object>> records = new ArrayList<>();
        for (User user : page.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.getId());
            item.put("username", user.getUsername());
            item.put("studentNo", user.getStudentNo());
            item.put("realName", user.getRealName());
            item.put("email", user.getEmail());
            item.put("phone", user.getPhone());
            item.put("role", user.getRole());
            item.put("classId", user.getClassId());
            item.put("major", user.getMajor());
            item.put("grade", user.getGrade());
            item.put("status", user.getStatus());
            item.put("createdAt", user.getCreatedAt());
            item.put("lastLogin", user.getLastLogin());
            records.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pageNum", page.getCurrent());
        result.put("pageSize", page.getSize());
        result.put("list", records);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取用户详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<User> getUser(@PathVariable Long id) {
        User user = userMapper.selectById(id);
        if (user != null) {
            user.setPasswordHash(null);
            user.setCurrentToken(null);
        }
        return ResponseEntity.ok(user);
    }

    /**
     * 人员概览统计（用于管理端人员管理卡片）
     * @param role 角色：1=学生，2=教师
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(
            @RequestParam Integer role,
            @RequestParam(required = false, defaultValue = "1") Integer status) {
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<User>()
                .eq(User::getRole, role);
        if (status != null && status > 0) {
            qw.eq(User::getStatus, status);
        }

        // 查询该角色全部用户（概览数据量可控）
        List<User> users = userMapper.selectList(qw);
        long total = users.size();
        long enabled = users.stream().filter(u -> u.getStatus() != null && u.getStatus() == 1).count();
        long disabled = total - enabled;

        // 预加载班级映射
        List<ClassInfo> allClasses = classInfoMapper.selectList(null);
        Map<Long, String> classIdToName = new HashMap<>();
        for (ClassInfo c : allClasses) {
            classIdToName.put(c.getId(), c.getClassName());
        }

        // 专业分布（教师无专业字段，走院系分布）
        Map<String, Long> majorCount = new LinkedHashMap<>();
        if (role != null && role == 2) {
            // 教师按院系分布：teacher.dept_id -> department.dept_name
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT d.dept_name AS name, COUNT(t.id) AS cnt " +
                    "FROM teacher t LEFT JOIN department d ON d.id = t.dept_id " +
                    "GROUP BY d.dept_name")) {
                String name = (String) row.get("name");
                majorCount.merge(name == null || name.isBlank() ? "未设置院系" : name,
                        ((Number) row.get("cnt")).longValue(), Long::sum);
            }
        } else {
            for (User u : users) {
                String major = u.getMajor();
                if (major == null || major.trim().isEmpty()) continue;
                majorCount.merge(major, 1L, Long::sum);
            }
        }
        List<Map<String, Object>> majorList = majorCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        // 班级分布（仅学生角色有意义；教师按院系/专业归类也可使用）
        Map<String, Map<String, Object>> classGroup = new LinkedHashMap<>();
        for (User u : users) {
            if (u.getClassId() == null) continue;
            String className = classIdToName.getOrDefault(u.getClassId(), "未知班级");
            String major = u.getMajor();
            final String finalMajor = (major == null || major.trim().isEmpty()) ? "未设置专业" : major;
            String key = u.getClassId() + "@" + className;
            classGroup.compute(key, (k, v) -> {
                if (v == null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getClassId());
                    m.put("name", className);
                    m.put("major", finalMajor);
                    m.put("count", 1L);
                    return m;
                }
                v.put("count", ((Long) v.get("count")) + 1);
                return v;
            });
        }
        List<Map<String, Object>> classList = classGroup.values().stream()
                .sorted((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("enabled", enabled);
        result.put("disabled", disabled);
        result.put("majors", majorList);
        result.put("classes", classList);

        // 教师角色：返回各院系教师名单（前端点击院系卡片下钻展示对应教师）
        if (role != null && role == 2) {
            // teacher + 院系
            List<Map<String, Object>> teachers = jdbc.queryForList(
                    "SELECT t.real_name AS realName, t.teacher_no AS teacherNo, t.status AS status, " +
                    "COALESCE(d.dept_name, '未设置院系') AS dept " +
                    "FROM teacher t LEFT JOIN department d ON d.id = t.dept_id " +
                    "ORDER BY d.dept_name, t.real_name");
            // 用户索引：账号 → 用户；姓名 → 用户（工号与账号不一致时按姓名兜底匹配）
            Map<String, User> userByUsername = new HashMap<>();
            Map<String, User> userByName = new HashMap<>();
            for (User u : users) {
                if (u.getUsername() != null) userByUsername.put(u.getUsername(), u);
                if (u.getRealName() != null) userByName.put(u.getRealName(), u);
            }
            List<Map<String, Object>> deptMembers = new ArrayList<>();
            for (Map<String, Object> t : teachers) {
                String teacherNo = t.get("teacherNo") == null ? "" : t.get("teacherNo").toString();
                String realName = t.get("realName") == null ? "" : t.get("realName").toString();
                User u = userByUsername.get(teacherNo);
                if (u == null) u = userByName.get(realName);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dept", t.get("dept"));
                m.put("realName", realName);
                // 账号/状态优先取用户表，关联不上回退 teacher 表
                m.put("username", u != null && u.getUsername() != null ? u.getUsername() : teacherNo);
                Object st = (u != null && u.getStatus() != null) ? u.getStatus() : t.get("status");
                m.put("status", st == null ? 0 : ((Number) st).intValue());
                deptMembers.add(m);
            }
            result.put("deptMembers", deptMembers);
        }
        return ResponseEntity.ok(result);
    }

    // ============================================================
    //  新增用户
    // ============================================================

    /**
     * 新增用户
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String realName = (String) body.get("realName");
        Integer role = body.get("role") != null ? ((Number) body.get("role")).intValue() : null;
        String studentNo = (String) body.get("studentNo");
        String email = (String) body.get("email");
        String phone = (String) body.get("phone");
        Long classId = body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null;
        String major = (String) body.get("major");
        String grade = (String) body.get("grade");

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名不能为空"));
        }
        if (password == null || password.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码不能为空"));
        }
        if (realName == null || realName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "真实姓名不能为空"));
        }
        if (role == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择角色"));
        }

        // 检查用户名是否已存在
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (count != null && count > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名已存在"));
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRealName(realName);
        user.setRole(role);
        user.setStudentNo(studentNo);
        user.setEmail(email);
        user.setPhone(phone);
        user.setClassId(classId);
        user.setMajor(major);
        user.setGrade(grade);
        user.setStatus(1);
        userMapper.insert(user);
        assignSysRole(user.getId(), role);

        // 如果是教师，同步到 teacher 表
        if (role == 2) {
            Teacher teacher = new Teacher();
            teacher.setRealName(realName);
            // 教师工号为空时默认使用用户名，避免 teacher_no NOT NULL 唯一索引冲突
            String teacherNo = (studentNo == null || studentNo.trim().isEmpty()) ? username : studentNo;
            teacher.setTeacherNo(teacherNo);
            teacher.setEmail(email);
            teacher.setPhone(phone);
            teacher.setStatus(1);
            teacherMapper.insert(teacher);
        }

        return ResponseEntity.ok(Map.of("id", user.getId(), "message", "创建成功"));
    }

    // ============================================================
    //  修改用户
    // ============================================================

    /**
     * 修改用户信息
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, String>> updateUser(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户不存在"));
        }

        String realName = (String) body.get("realName");
        String email = (String) body.get("email");
        String phone = (String) body.get("phone");
        Long classId = body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null;
        String major = (String) body.get("major");
        String grade = (String) body.get("grade");
        Integer status = body.get("status") != null ? ((Number) body.get("status")).intValue() : null;
        String username = (String) body.get("username");
        String studentNo = (String) body.get("studentNo");
        Integer role = body.get("role") != null ? ((Number) body.get("role")).intValue() : null;

        // 修改用户名时检查是否已被他人占用
        if (username != null && !username.trim().isEmpty() && !username.equals(user.getUsername())) {
            Long cnt = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, username).ne(User::getId, id));
            if (cnt != null && cnt > 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "用户名已被占用"));
            }
            user.setUsername(username);
        }
        if (studentNo != null) user.setStudentNo(studentNo);
        String oldRealName = user.getRealName();
        int oldRole = user.getRole();
        if (realName != null) user.setRealName(realName);
        if (email != null) user.setEmail(email);
        if (phone != null) user.setPhone(phone);
        if (classId != null) user.setClassId(classId);
        if (major != null) user.setMajor(major);
        if (grade != null) user.setGrade(grade);
        if (status != null) user.setStatus(status);
        if (role != null) user.setRole(role);

        userMapper.updateById(user);
        // 角色变更时同步 RBAC 角色绑定
        if (role != null && role != oldRole) {
            assignSysRole(user.getId(), role);
        }

        // 处理 teacher 表同步
        if (user.getRole() == 2) {
            // 用旧名字查询 teacher 记录（因为可能改了名字）
            List<Teacher> teachers = teacherMapper.selectList(
                    new LambdaQueryWrapper<Teacher>().eq(Teacher::getRealName, oldRealName));
            if (!teachers.isEmpty()) {
                // 已存在，更新信息
                Teacher teacher = teachers.get(0);
                if (realName != null) teacher.setRealName(realName);
                if (email != null) teacher.setEmail(email);
                if (phone != null) teacher.setPhone(phone);
                if (status != null) teacher.setStatus(status);
                if (studentNo != null) teacher.setTeacherNo(studentNo);
                teacherMapper.updateById(teacher);
            } else {
                // 不存在，新建 teacher 记录（角色从其他改为教师）
                Teacher teacher = new Teacher();
                teacher.setRealName(user.getRealName());
                teacher.setTeacherNo(user.getStudentNo());
                teacher.setEmail(user.getEmail());
                teacher.setPhone(user.getPhone());
                teacher.setStatus(user.getStatus() != null ? user.getStatus() : 1);
                teacherMapper.insert(teacher);
            }
        } else if (oldRole == 2 && role != null && role != 2) {
            // 从教师改为其他角色，删除 teacher 表记录
            List<Teacher> teachers = teacherMapper.selectList(
                    new LambdaQueryWrapper<Teacher>().eq(Teacher::getRealName, oldRealName));
            if (!teachers.isEmpty()) {
                teacherMapper.deleteById(teachers.get(0).getId());
            }
        }

        return ResponseEntity.ok(Map.of("message", "更新成功"));
    }

    /**
     * 重置密码
     */
    @PutMapping("/{id}/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@PathVariable Long id,
                                                              @RequestBody Map<String, String> body) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户不存在"));
        }

        String newPassword = body.get("password");
        if (newPassword == null || newPassword.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码不能为空"));
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setCurrentToken(null); // 使旧token失效
        userMapper.updateById(user);

        return ResponseEntity.ok(Map.of("message", "密码重置成功"));
    }

    // ============================================================
    //  删除用户
    // ============================================================

    /**
     * 删除用户（软删除：status=0）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户不存在"));
        }

        // 不能删除自己
//         Authentication auth = SecurityContextHolder.getContext().getAuthentication();
//         if (auth != null && auth.getPrincipal() instanceof Long) {
//             Long currentId = (Long) auth.getPrincipal();
//             if (currentId.equals(id)) {
//                 return ResponseEntity.badRequest().body(Map.of("error", "不能删除当前登录账号"));
//             }
//         }

        user.setStatus(0);
        userMapper.updateById(user);

        // 如果是教师，同步禁用 teacher 表
        if (user.getRole() == 2) {
            Teacher teacher = teacherMapper.selectOne(
                    new LambdaQueryWrapper<Teacher>().eq(Teacher::getRealName, user.getRealName()));
            if (teacher != null) {
                teacher.setStatus(0);
                teacherMapper.updateById(teacher);
            }
        }

        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    // ============================================================
    //  批量导入学生
    // ============================================================

    /**
     * 批量导入学生（Excel上传）
     * Excel格式：学号 | 姓名 | 用户名 | 班级名称 | 专业 | 年级 | 邮箱 | 手机号
     * 默认密码：123456
     */
    @PostMapping("/import-students")
    public ResponseEntity<Map<String, Object>> importStudents(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传文件"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !(filename.toLowerCase().endsWith(".xlsx") || filename.toLowerCase().endsWith(".xls"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "仅支持 .xlsx / .xls 格式"));
        }

        // 文件大小限制：最大 10MB
        final long MAX_SIZE = 10 * 1024 * 1024;
        if (file.getSize() > MAX_SIZE) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件大小不能超过 10MB"));
        }

        int total = 0, imported = 0, skipped = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        String defaultPassword = "123456";

        // 预加载所有班级（按名称索引，避免重复查库）
        List<ClassInfo> allClasses = classInfoMapper.selectList(null);
        Map<String, Long> classNameToId = new HashMap<>();
        for (ClassInfo c : allClasses) {
            classNameToId.put(c.getClassName(), c.getId());
        }

        // 预加载已有用户名（避免重复插入）
        Set<String> existingUsernames = new HashSet<>();
        List<User> allUsers = userMapper.selectList(
                new LambdaQueryWrapper<User>().select(User::getUsername));
        for (User u : allUsers) {
            existingUsernames.add(u.getUsername());
        }

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();

            // 表头校验
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Excel 缺少表头，请使用标准模板"));
            }
            boolean hasStudentNo = false, hasRealName = false;
            for (Cell cell : headerRow) {
                String val = formatter.formatCellValue(cell).trim();
                if (val.contains("学号")) hasStudentNo = true;
                if (val.contains("姓名")) hasRealName = true;
            }
            if (!hasStudentNo || !hasRealName) {
                return ResponseEntity.badRequest().body(Map.of("error", "表头必须包含「学号」和「姓名」列，请使用标准模板"));
            }

            // 最大行数限制（不含表头）
            final int MAX_ROWS = 1000;
            int dataRows = Math.max(0, sheet.getLastRowNum());
            if (dataRows > MAX_ROWS) {
                return ResponseEntity.badRequest().body(Map.of("error", "单次导入不能超过 " + MAX_ROWS + " 行"));
            }

            // 从第2行开始（第1行为表头）
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                // 跳过完全空行
                boolean allEmpty = true;
                for (int c = 0; c < 8; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    if (!formatter.formatCellValue(cell).trim().isEmpty()) {
                        allEmpty = false;
                        break;
                    }
                }
                if (allEmpty) continue;

                total++;
                List<String> rowData = new ArrayList<>();
                for (int c = 0; c < 8; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    rowData.add(formatter.formatCellValue(cell).trim());
                }

                String studentNo = rowData.get(0);
                String realName = rowData.get(1);
                String username = rowData.get(2);
                String className = rowData.get(3);
                String major = rowData.get(4);
                String grade = rowData.get(5);
                String email = rowData.get(6);
                String phone = rowData.get(7);

                // 校验必填项
                List<String> rowErrors = new ArrayList<>();
                if (realName.isEmpty()) rowErrors.add("姓名为空");
                if (studentNo.isEmpty()) rowErrors.add("学号为空");

                // 用户名为空时用学号作为用户名
                if (username.isEmpty()) {
                    username = studentNo;
                }
                if (username.isEmpty()) rowErrors.add("用户名为空");

                // 班级名称不为空时需匹配
                Long classId = null;
                if (!className.isEmpty()) {
                    classId = classNameToId.get(className);
                    if (classId == null) {
                        rowErrors.add("班级「" + className + "」不存在");
                    }
                }

                // 用户名重复检查
                if (!username.isEmpty() && existingUsernames.contains(username)) {
                    rowErrors.add("用户名「" + username + "」已存在");
                }

                // 手机号格式校验
                if (!phone.isEmpty() && !phone.matches("^1[3-9]\\d{9}$")) {
                    rowErrors.add("手机号格式不正确");
                }

                // 邮箱格式校验
                if (!email.isEmpty() && !email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                    rowErrors.add("邮箱格式不正确");
                }

                if (!rowErrors.isEmpty()) {
                    skipped++;
                    Map<String, Object> errItem = new LinkedHashMap<>();
                    errItem.put("row", r + 1);
                    errItem.put("studentNo", studentNo);
                    errItem.put("realName", realName);
                    errItem.put("errors", rowErrors);
                    errors.add(errItem);
                    continue;
                }

                // 创建学生
                User user = new User();
                user.setUsername(username);
                user.setPasswordHash(passwordEncoder.encode(defaultPassword));
                user.setRealName(realName);
                user.setRole(1);
                user.setStudentNo(studentNo);
                user.setEmail(email.isEmpty() ? null : email);
                user.setPhone(phone.isEmpty() ? null : phone);
                user.setClassId(classId);
                user.setMajor(major.isEmpty() ? null : major);
                user.setGrade(grade.isEmpty() ? null : grade);
                user.setStatus(1);
                userMapper.insert(user);
                assignSysRole(user.getId(), 1);

                existingUsernames.add(username);
                imported++;
            }

            if (total == 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "未检测到有效数据行，请检查文件内容"));
            }

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "解析Excel失败：" + e.getMessage()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("errors", errors);
        result.put("message", String.format("导入完成：共%d条，成功%d条，跳过%d条", total, imported, skipped));
        return ResponseEntity.ok(result);
    }

    // ============================================================
    //  批量导入教师
    // ============================================================

    /**
     * 批量导入教师（Excel上传）
     * Excel格式：账号 | 姓名 | 密码 | 手机号 | 邮箱 | 院系
     * 密码为空时默认 123456；院系不存在时自动创建
     */
    @PostMapping("/import-teachers")
    public ResponseEntity<Map<String, Object>> importTeachers(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传文件"));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !(filename.toLowerCase().endsWith(".xlsx") || filename.toLowerCase().endsWith(".xls"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "仅支持 .xlsx / .xls 格式"));
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件大小不能超过 10MB"));
        }

        int total = 0, imported = 0, skipped = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        String defaultPassword = "123456";

        // 预加载已有用户名
        Set<String> existingUsernames = new HashSet<>();
        for (User u : userMapper.selectList(new LambdaQueryWrapper<User>().select(User::getUsername))) {
            existingUsernames.add(u.getUsername());
        }
        // 预加载院系（名称 → id）
        Map<String, Long> deptNameToId = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT id, dept_name FROM department")) {
            Object name = row.get("dept_name");
            if (name != null) deptNameToId.put(name.toString().trim(), ((Number) row.get("id")).longValue());
        }

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Excel 缺少表头，请使用标准模板"));
            }
            boolean hasAccount = false, hasRealName = false;
            for (Cell cell : headerRow) {
                String val = formatter.formatCellValue(cell).trim();
                if (val.contains("账号")) hasAccount = true;
                if (val.contains("姓名")) hasRealName = true;
            }
            if (!hasAccount || !hasRealName) {
                return ResponseEntity.badRequest().body(Map.of("error", "表头必须包含「账号」和「姓名」列"));
            }

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                boolean allEmpty = true;
                for (int c = 0; c < 6; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    if (!formatter.formatCellValue(cell).trim().isEmpty()) { allEmpty = false; break; }
                }
                if (allEmpty) continue;

                total++;
                List<String> rowData = new ArrayList<>();
                for (int c = 0; c < 6; c++) {
                    rowData.add(formatter.formatCellValue(row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)).trim());
                }
                String username = rowData.get(0);
                String realName = rowData.get(1);
                String password = rowData.get(2);
                String phone = rowData.get(3);
                String email = rowData.get(4);
                String deptName = rowData.get(5);

                List<String> rowErrors = new ArrayList<>();
                if (username.isEmpty()) rowErrors.add("账号为空");
                if (realName.isEmpty()) rowErrors.add("姓名为空");
                if (!username.isEmpty() && existingUsernames.contains(username)) {
                    rowErrors.add("账号「" + username + "」已存在");
                }
                if (!rowErrors.isEmpty()) {
                    skipped++;
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", r + 1);
                    err.put("studentNo", username);
                    err.put("realName", realName);
                    err.put("errors", rowErrors);
                    errors.add(err);
                    continue;
                }

                User user = new User();
                user.setUsername(username);
                user.setPasswordHash(passwordEncoder.encode(password.isEmpty() ? defaultPassword : password));
                user.setRealName(realName);
                user.setRole(2);
                user.setEmail(email.isEmpty() ? null : email);
                user.setPhone(phone.isEmpty() ? null : phone);
                user.setStatus(1);
                userMapper.insert(user);

                Teacher teacher = new Teacher();
                teacher.setTeacherNo(username);
                teacher.setRealName(realName);
                teacher.setEmail(email.isEmpty() ? null : email);
                teacher.setPhone(phone.isEmpty() ? null : phone);
                teacher.setStatus(1);
                if (!deptName.isEmpty()) {
                    Long deptId = deptNameToId.get(deptName);
                    if (deptId == null) {
                        // 院系不存在则自动创建，保证院系分布可见
                        jdbc.update("INSERT INTO department (dept_name, dept_type) VALUES (?, '教学单位')", deptName);
                        deptId = jdbc.queryForObject("SELECT id FROM department WHERE dept_name = ? LIMIT 1", Long.class, deptName);
                        if (deptId != null) deptNameToId.put(deptName, deptId);
                    }
                    teacher.setDeptId(deptId);
                }
                teacherMapper.insert(teacher);
                assignSysRole(user.getId(), 2);

                existingUsernames.add(username);
                imported++;
            }

            if (total == 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "未检测到有效数据行，请检查文件内容"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "解析Excel失败：" + e.getMessage()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("errors", errors);
        result.put("message", String.format("导入完成：共%d条，成功%d条，跳过%d条", total, imported, skipped));
        return ResponseEntity.ok(result);
    }

    // ============================================================
    //  辅助接口：班级列表、课程列表（下拉选择用）
    // ============================================================

    /**
     * 获取所有班级列表
     */
    @GetMapping("/classes")
    public ResponseEntity<List<ClassInfo>> listClasses() {
        return ResponseEntity.ok(classInfoMapper.selectList(
                new LambdaQueryWrapper<ClassInfo>().orderByAsc(ClassInfo::getClassName)));
    }

    /**
     * 获取所有课程列表
     */
    @GetMapping("/courses")
    public ResponseEntity<List<Course>> listCourses(
            @RequestParam(required = false) String semester) {
        LambdaQueryWrapper<Course> qw = new LambdaQueryWrapper<Course>()
                .orderByAsc(Course::getCourseName);
        if (semester != null && !semester.isEmpty()) {
            qw.eq(Course::getSemester, semester);
        }
        return ResponseEntity.ok(courseMapper.selectList(qw));
    }

    /**
     * 获取教师列表
     */
    @GetMapping("/teachers")
    public ResponseEntity<List<Teacher>> listTeachers() {
        return ResponseEntity.ok(teacherMapper.selectList(
                new LambdaQueryWrapper<Teacher>()
                        .eq(Teacher::getStatus, 1)
                        .orderByAsc(Teacher::getRealName)));
    }

    /**
     * 查询教师所教课程及班级列表
     * @param teacherUserId 教师对应用户表ID（user.id）
     */
    @GetMapping("/teacher-courses")
    public ResponseEntity<List<Map<String, Object>>> getTeacherCourses(
            @RequestParam Long teacherUserId,
            @RequestParam(required = false) String semester) {

        // 根据user id获取教师真实姓名，再匹配teacher表
        User user = userMapper.selectById(teacherUserId);
        if (user == null || user.getRole() != 2) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        String realName = user.getRealName();
        Teacher teacher = teacherMapper.selectOne(
                new LambdaQueryWrapper<Teacher>().eq(Teacher::getRealName, realName));
        if (teacher == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        Long teacherId = teacher.getId();

        // 查询该教师所有课程，以及每个课程关联的班级
        // JOIN: course -> course_class -> class_info
        String sql = "SELECT c.id AS course_id, c.course_name, c.course_type, c.credit, c.semester, " +
                "ci.id AS class_id, ci.class_name " +
                "FROM course c " +
                "INNER JOIN course_class cc ON cc.course_id = c.id " +
                "INNER JOIN class_info ci ON cc.class_id = ci.id " +
                "WHERE c.teacher_id = ?";
        List<Object> params = new ArrayList<>();
        params.add(teacherId);

        if (semester != null && !semester.trim().isEmpty()) {
            sql += " AND c.semester = ?";
            params.add(semester);
        }

        sql += " ORDER BY c.semester DESC, c.course_name, ci.class_name";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params.toArray());
        return ResponseEntity.ok(rows);
    }

    /**
     * 绑定 RBAC 角色（登录时 roles/permissions 由此关联表驱动）。
     * user.role: 1=学生 2=教师 3=管理员；sys_role.id: 1=admin 2=teacher 3=student，
     * 恰好倒序，故 sys_role_id = 4 - user.role。
     */
    private void assignSysRole(Long userId, Integer userRole) {
        if (userId == null || userRole == null) return;
        int sysRoleId = 4 - userRole;
        if (sysRoleId < 1 || sysRoleId > 3) return;
        try {
            jdbc.update("INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, sysRoleId);
        } catch (Exception ignored) {
            // 角色绑定失败不影响用户创建
        }
    }
}
