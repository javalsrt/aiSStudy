package com.znxsgl.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.znxsgl.entity.User;
import com.znxsgl.mapper.UserMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用启动时的数据初始化与修复
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public DataInitializer(UserMapper userMapper, PasswordEncoder passwordEncoder, JdbcTemplate jdbc) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        ensureSchema();
        initUserPasswords();
        fixCourseCredits();
    }

    /**
     * 兼容旧数据库：补齐新版本新增的字段和表。
     * 部署时只更新代码、未同步数据库时，缺少字段会导致课程/课表接口 500，
     * 这里在启动时做一次幂等检查并自动补齐。
     */
    private void ensureSchema() {
        ensureColumn("course", "cover_url",
                "ALTER TABLE `course` ADD COLUMN `cover_url` varchar(500) NULL " +
                        "COMMENT '课程封面图 URL' AFTER `description`");

        ensureTable("bell_schedule",
                "CREATE TABLE IF NOT EXISTS `bell_schedule` (" +
                        "`id` bigint NOT NULL AUTO_INCREMENT," +
                        "`semester` varchar(20) NOT NULL DEFAULT '' COMMENT '学期，空=通用'," +
                        "`grade` varchar(20) NOT NULL DEFAULT '' COMMENT '年级（如 2026级），空=默认作息'," +
                        "`class_id` bigint DEFAULT NULL COMMENT '班级ID'," +
                        "`week_parity` tinyint NOT NULL DEFAULT '0' COMMENT '0=通用 1=单周 2=双周'," +
                        "`node` int NOT NULL COMMENT '节次，从 1 开始'," +
                        "`start_time` time NOT NULL COMMENT '上课时间'," +
                        "`end_time` time NOT NULL COMMENT '下课时间'," +
                        "PRIMARY KEY (`id`)," +
                        "KEY `idx_bell_class_node` (`class_id`,`node`)," +
                        "KEY `idx_bell_scope` (`semester`,`class_id`,`week_parity`,`node`)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='作息时间表（节次起止时间）'");

        ensureTable("grade_week_parity",
                "CREATE TABLE IF NOT EXISTS `grade_week_parity` (" +
                        "`id` bigint NOT NULL AUTO_INCREMENT," +
                        "`grade` varchar(20) NOT NULL COMMENT '年级，如 2026级'," +
                        "`odd_week_parity` tinyint NOT NULL DEFAULT '1' COMMENT '奇数周套用的作息：1=单周表 2=双周表'," +
                        "`remark` varchar(100) DEFAULT NULL," +
                        "PRIMARY KEY (`id`)," +
                        "UNIQUE KEY `grade` (`grade`)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='年级单双周作息映射'");

        ensureTable("ai_agent_log",
                "CREATE TABLE IF NOT EXISTS `ai_agent_log` (" +
                        "`id` bigint NOT NULL AUTO_INCREMENT," +
                        "`request_id` varchar(64) DEFAULT NULL COMMENT '单次请求 ID'," +
                        "`user_id` bigint NOT NULL COMMENT '操作人 user.id'," +
                        "`user_role` varchar(16) NOT NULL COMMENT 'ADMIN / TEACHER'," +
                        "`session_id` varchar(64) DEFAULT NULL COMMENT '会话 ID'," +
                        "`mode` varchar(16) NOT NULL COMMENT 'agent / chat'," +
                        "`user_message` text COMMENT '用户原始指令'," +
                        "`action_name` varchar(64) DEFAULT NULL COMMENT '执行的工具名'," +
                        "`action_args` json DEFAULT NULL COMMENT '工具参数（密码类已脱敏）'," +
                        "`result_status` varchar(16) DEFAULT NULL COMMENT 'success / failed / pending_confirm / canceled'," +
                        "`result_summary` varchar(500) DEFAULT NULL COMMENT '执行结果摘要'," +
                        "`duration_ms` int DEFAULT NULL COMMENT '耗时'," +
                        "`error_code` varchar(64) DEFAULT NULL COMMENT '错误码'," +
                        "`created_at` datetime DEFAULT CURRENT_TIMESTAMP," +
                        "PRIMARY KEY (`id`)," +
                        "KEY `idx_ai_log_user_time` (`user_id`, `created_at`)," +
                        "KEY `idx_ai_log_action` (`action_name`)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理端 AI 助手操作审计'");
    }

    /** 字段不存在时才 ALTER，避免重复执行报错 */
    private void ensureColumn(String table, String column, String ddl) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (count == null || count == 0) {
                jdbc.execute(ddl);
                System.out.println("[初始化] 已自动补充字段 " + table + "." + column);
            }
        } catch (Exception e) {
            System.err.println("[初始化] 检查字段 " + table + "." + column + " 失败: " + e.getMessage());
        }
    }

    /** 表不存在时才创建 */
    private void ensureTable(String table, String ddl) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, table);
            if (count == null || count == 0) {
                jdbc.execute(ddl);
                System.out.println("[初始化] 已自动创建表 " + table);
            }
        } catch (Exception e) {
            System.err.println("[初始化] 检查表 " + table + " 失败: " + e.getMessage());
        }
    }

    /**
     * 自动加密/重置用户密码：
     * - 已加密（BCrypt 格式）则跳过
     * - 空密码默认重置为 123456
     * - 明文密码按原值加密
     */
    private void initUserPasswords() {
        List<User> users = userMapper.selectList(null);

        if (users.isEmpty()) {
            System.err.println("[初始化] 用户表为空，请先执行 seed_all.sql");
            return;
        }

        int updated = 0;
        for (User u : users) {
            String hash = u.getPasswordHash();
            if (!isPasswordHashed(hash)) {
                String rawPassword = resolveRawPassword(hash);
                u.setPasswordHash(passwordEncoder.encode(rawPassword));
                userMapper.updateById(u);
                updated++;
            }
        }

        if (updated > 0) {
            System.out.println("[初始化] 已加密/重置 " + updated + " 个用户密码");
        }
    }

    /**
     * 判断密码是否已是 BCrypt 哈希格式。
     */
    private boolean isPasswordHashed(String hash) {
        if (hash == null) return false;
        // 32 位 hex 是 MD5，不是 BCrypt
        if (hash.trim().matches("^[a-fA-F0-9]{32}$")) return false;
        return hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$");
    }

    /**
     * 解析原始密码：
     * - 空密码 → 默认 123456
     * - 32 位 MD5（如 123456 的 MD5）→ 视为原密码 123456
     * - 其他明文 → 按原值返回
     */
    private String resolveRawPassword(String hash) {
        if (hash == null || hash.trim().isEmpty()) {
            return "123456";
        }
        String trimmed = hash.trim();
        if (trimmed.matches("^[a-fA-F0-9]{32}$")) {
            return "123456";
        }
        return trimmed;
    }

    /**
     * 修复课程表：给 credit 为 NULL 或 0 的课程设置合理课时。
     * 优先从 teaching_task.weekly_hours 回填；无任务数据时按课程类型兜底：必修=4, 其他=4。
     * 注意：当前系统中 credit 被排课模块用作"每周可排课时上限"，因此默认值不宜过小。
     */
    private void fixCourseCredits() {
        // 第一步：用教学任务中的周学时回填
        jdbc.update("UPDATE course c " +
                "LEFT JOIN teaching_task t ON t.course_id = c.id " +
                "SET c.credit = t.weekly_hours " +
                "WHERE (c.credit IS NULL OR c.credit = 0) " +
                "AND t.weekly_hours IS NOT NULL AND t.weekly_hours > 0");

        // 第二步：按课程类型兜底（每周上限至少 4 课时，避免教师感觉课时过少）
        jdbc.update("UPDATE course SET credit = 4 WHERE (credit IS NULL OR credit = 0) AND course_type = '必修'");
        jdbc.update("UPDATE course SET credit = 4 WHERE (credit IS NULL OR credit = 0) AND course_type IN ('限选','选修','公共课','通识')");
        jdbc.update("UPDATE course SET credit = 4 WHERE (credit IS NULL OR credit = 0)");

        int fixed = jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE credit IS NULL OR credit = 0", Integer.class);
        if (fixed > 0) {
            System.err.println("[初始化] 仍有 " + fixed + " 个课程课时为 0");
        }
    }
}
