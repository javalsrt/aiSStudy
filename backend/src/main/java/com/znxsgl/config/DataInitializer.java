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
        initUserPasswords();
        fixCourseCredits();
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
