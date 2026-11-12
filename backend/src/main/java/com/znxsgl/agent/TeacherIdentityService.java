package com.znxsgl.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 教师身份解析。
 * <p>
 * user 与 teacher 是弱关联：优先用教师工号 = 登录账号匹配，其次用唯一姓名匹配。
 * 任何无法唯一确定的情况都必须 fail-closed，禁止回退成 userId，避免越权。
 */
@Service
public class TeacherIdentityService {

    private final JdbcTemplate jdbc;

    public TeacherIdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TeacherIdentity(Long id, String realName, boolean leader) {
    }

    public TeacherIdentity resolve(Long userId) {
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, username, real_name, role FROM user WHERE id = ?", userId);
        if (users.isEmpty()) {
            throw new AgentException("USER_NOT_FOUND", "登录用户不存在", 401);
        }
        Map<String, Object> user = users.get(0);
        int role = toInt(user.get("role"));
        if (role != 2) {
            throw new AgentException("FORBIDDEN", "仅教师可执行该操作", 403);
        }
        String username = str(user.get("username"));
        String realName = str(user.get("real_name"));

        if (!username.isEmpty()) {
            List<Map<String, Object>> byNo = jdbc.queryForList(
                    "SELECT id, real_name, is_leader FROM teacher WHERE teacher_no = ? LIMIT 2", username);
            if (byNo.size() == 1) {
                return toIdentity(byNo.get(0));
            }
        }
        List<Map<String, Object>> byName = jdbc.queryForList(
                "SELECT id, real_name, is_leader FROM teacher WHERE real_name = ? LIMIT 2", realName);
        if (byName.size() == 1) {
            return toIdentity(byName.get(0));
        }

        throw new AgentException("TEACHER_NOT_BOUND",
                "教师身份未绑定或存在重名，请联系管理员在教师管理中核对工号", 403);
    }

    private TeacherIdentity toIdentity(Map<String, Object> row) {
        return new TeacherIdentity(
                ((Number) row.get("id")).longValue(),
                str(row.get("real_name")),
                toInt(row.get("is_leader")) == 1);
    }

    private int toInt(Object o) {
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Number n) return n.intValue();
        return 0;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
