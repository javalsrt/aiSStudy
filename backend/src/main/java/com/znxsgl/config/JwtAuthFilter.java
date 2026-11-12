package com.znxsgl.config;

import com.znxsgl.entity.User;
import com.znxsgl.mapper.SysPermissionMapper;
import com.znxsgl.mapper.SysRoleMapper;
import com.znxsgl.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;

    public JwtAuthFilter(JwtUtil jwtUtil, UserMapper userMapper,
                         SysRoleMapper sysRoleMapper, SysPermissionMapper sysPermissionMapper) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysPermissionMapper = sysPermissionMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 登录相关接口是公开接口：忽略浏览器里可能残留的旧 token（尤其是访客 token），
        // 否则访客 token 会让 admin-login 这类 POST 请求在进入 Controller 前就被 403 拦截
        if (request.getRequestURI().startsWith("/api/auth/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (!jwtUtil.validateToken(token)) {
                // token 过期或无效，返回 401 而非 403
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"登录已过期，请重新登录\"}");
                return;
            }
            Long userId = jwtUtil.getUserId(token);

            // 访客 token：跳过单设备校验，且除查询外的请求一律拒绝
            io.jsonwebtoken.Claims claims = jwtUtil.parseToken(token);
            boolean guest = Boolean.TRUE.equals(claims.get("guest", Boolean.class));
            if (guest) {
                String method = request.getMethod();
                boolean readOnly = "GET".equalsIgnoreCase(method)
                        || "HEAD".equalsIgnoreCase(method)
                        || "OPTIONS".equalsIgnoreCase(method);
                if (!readOnly) {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"error\":\"Forbidden\",\"message\":\"访客模式仅支持浏览，不能进行增删改操作\"}");
                    return;
                }
                List<String> guestRoles = sysRoleMapper.selectRoleCodesByUserId(userId);
                List<String> guestPerms = sysPermissionMapper.selectPermCodesByUserId(userId);
                List<SimpleGrantedAuthority> guestAuthorities = new ArrayList<>();
                for (String rc : guestRoles) {
                    guestAuthorities.add(new SimpleGrantedAuthority("ROLE_" + rc.toUpperCase()));
                }
                for (String pc : guestPerms) {
                    guestAuthorities.add(new SimpleGrantedAuthority(pc));
                }
                if (guestAuthorities.isEmpty()) {
                    guestAuthorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }
                UsernamePasswordAuthenticationToken guestAuth =
                        new UsernamePasswordAuthenticationToken(userId, 3, guestAuthorities);
                SecurityContextHolder.getContext().setAuthentication(guestAuth);
                filterChain.doFilter(request, response);
                return;
            }

            // 单设备登录校验：token 必须与数据库 current_token 一致
            User user = userMapper.selectById(userId);
            if (user == null || !token.equals(user.getCurrentToken())) {
                // token 已被新登录覆盖，返回 401
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"账号已在其他设备登录，请重新登录\"}");
                return;
            }

            Integer role = jwtUtil.getRole(token);
            // 从 RBAC 表加载角色编码与权限编码，构建 Spring Security authorities
            List<String> roleCodes = sysRoleMapper.selectRoleCodesByUserId(userId);
            List<String> permCodes = sysPermissionMapper.selectPermCodesByUserId(userId);

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            // 角色编码统一转为 ROLE_ 前缀，支持 hasRole('ADMIN') / hasRole('TEACHER') 等表达式
            for (String rc : roleCodes) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + rc.toUpperCase()));
            }
            // 权限编码作为独立 authority，支持 hasPermission(null, 'perm:code')
            for (String pc : permCodes) {
                authorities.add(new SimpleGrantedAuthority(pc));
            }
            // 兜底：如果 RBAC 未配置角色，仍按 JWT 中的 role 字段授予单一角色
            if (authorities.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority(roleToAuthority(role)));
            }

            // credentials 仍保留 role 数值，兼容现有 Controller 中 (Integer) auth.getCredentials() 的用法
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(userId, role, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }

    /** role 数值 → Spring Security authority 字符串 */
    private static String roleToAuthority(Integer role) {
        if (role == null) return "ROLE_STUDENT";
        return switch (role) {
            case 2 -> "ROLE_TEACHER";
            case 3 -> "ROLE_ADMIN";
            default -> "ROLE_STUDENT";
        };
    }
}
