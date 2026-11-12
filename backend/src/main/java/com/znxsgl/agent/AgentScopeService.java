package com.znxsgl.agent;

import com.znxsgl.agent.dto.AgentContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端 AI 助手的数据范围服务。
 * <p>
 * 管理员：全局；教师：仅 course.teacher_id = 当前教师 的任教班级。
 */
@Service
public class AgentScopeService {

    private final JdbcTemplate jdbc;
    private final TeacherIdentityService teacherIdentityService;

    public AgentScopeService(JdbcTemplate jdbc, TeacherIdentityService teacherIdentityService) {
        this.jdbc = jdbc;
        this.teacherIdentityService = teacherIdentityService;
    }

    public AgentContext buildContext(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            throw new AgentException("UNAUTHORIZED", "请先登录", 401);
        }
        boolean admin = hasRole(auth, "ROLE_ADMIN");
        boolean teacher = hasRole(auth, "ROLE_TEACHER");

        Map<String, Object> user = jdbc.queryForList(
                        "SELECT id, username, real_name FROM user WHERE id = ?", userId)
                .stream().findFirst().orElse(null);
        if (user == null) {
            throw new AgentException("USER_NOT_FOUND", "登录用户不存在", 401);
        }

        AgentContext ctx = new AgentContext();
        ctx.setUserId(userId);
        ctx.setUsername(str(user.get("username")));
        ctx.setRealName(str(user.get("real_name")));
        ctx.setAuthentication(auth);
        ctx.setAdmin(admin);
        ctx.setTaughtClassIds(new LinkedHashSet<>());
        ctx.setClassNames(new LinkedHashMap<>());

        if (admin) {
            ctx.setRole("ADMIN");
            return ctx;
        }
        if (!teacher) {
            throw new AgentException("FORBIDDEN", "当前角色不能使用管理端 AI 助手", 403);
        }

        TeacherIdentityService.TeacherIdentity identity = teacherIdentityService.resolve(userId);
        ctx.setRole("TEACHER");
        ctx.setTeacherId(identity.id());
        ctx.setLeader(identity.leader());

        List<Long> classIds = jdbc.queryForList(
                "SELECT DISTINCT cc.class_id FROM course c " +
                        "JOIN course_class cc ON cc.course_id = c.id " +
                        "WHERE c.teacher_id = ?", Long.class, identity.id());
        ctx.setTaughtClassIds(new LinkedHashSet<>(classIds));

        if (!classIds.isEmpty()) {
            String placeholders = placeholders(classIds.size());
            List<Object> params = new ArrayList<>(classIds);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, class_name FROM class_info WHERE id IN (" + placeholders + ") ORDER BY id",
                    params.toArray());
            for (Map<String, Object> row : rows) {
                ctx.getClassNames().put(((Number) row.get("id")).longValue(), str(row.get("class_name")));
            }
        }
        return ctx;
    }

    public void assertClassInScope(AgentContext ctx, Long classId) {
        if (ctx.isAdmin()) return;
        if (classId == null) {
            throw new AgentException("INVALID_ARGS", "请提供班级");
        }
        if (!ctx.getTaughtClassIds().contains(classId)) {
            throw new AgentException("FORBIDDEN", "无权操作该班级，你只能管理自己任教的班级", 403);
        }
    }

    public void assertStudentInScope(AgentContext ctx, Long studentClassId) {
        if (ctx.isAdmin()) return;
        if (studentClassId == null || !ctx.getTaughtClassIds().contains(studentClassId)) {
            throw new AgentException("FORBIDDEN", "无权操作该学生，他不属于你任教的班级", 403);
        }
    }

    public void assertCourseOwned(AgentContext ctx, Long courseId) {
        if (ctx.isAdmin()) return;
        Long teacherId = firstLong("SELECT teacher_id FROM course WHERE id = ?", courseId);
        if (teacherId == null || !teacherId.equals(ctx.getTeacherId())) {
            throw new AgentException("FORBIDDEN", "无权操作该课程", 403);
        }
    }

    public String classNamesText(AgentContext ctx) {
        if (ctx.isAdmin()) return "全校所有班级";
        if (ctx.getClassNames().isEmpty()) return "暂无任教班级";
        StringBuilder sb = new StringBuilder();
        ctx.getClassNames().forEach((id, name) ->
                sb.append(id).append("=").append(name).append("；"));
        return sb.toString();
    }

    public boolean hasRole(Authentication auth, String role) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }

    private Long firstLong(String sql, Object... params) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
