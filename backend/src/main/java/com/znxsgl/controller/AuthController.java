package com.znxsgl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.znxsgl.config.JwtUtil;
import com.znxsgl.dto.AdminLoginRequest;
import com.znxsgl.dto.LoginRequest;
import com.znxsgl.dto.LoginResponse;
import com.znxsgl.entity.User;
import com.znxsgl.mapper.SysPermissionMapper;
import com.znxsgl.mapper.SysRoleMapper;
import com.znxsgl.mapper.UserMapper;
import com.znxsgl.service.AuthService;
import com.znxsgl.service.CaptchaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;

    public AuthController(AuthService authService, CaptchaService captchaService,
                          JwtUtil jwtUtil, UserMapper userMapper,
                          SysRoleMapper sysRoleMapper, SysPermissionMapper sysPermissionMapper) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysPermissionMapper = sysPermissionMapper;
    }

    /** App 端登录：保持原逻辑，不需要验证码 */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        if (response.getToken() == null) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }

    /** 管理端图形验证码：5 分钟有效、一次性使用 */
    @GetMapping("/captcha")
    public Map<String, String> captcha() {
        return captchaService.create();
    }

    /** 管理端登录：账号密码 + 验证码，且仅允许教师/管理员账号 */
    @PostMapping("/admin-login")
    public ResponseEntity<?> adminLogin(@Valid @RequestBody AdminLoginRequest request) {
        if (!captchaService.validate(request.captchaId(), request.captchaCode())) {
            return ResponseEntity.badRequest().body(Map.of("message", "验证码错误或已过期"));
        }
        LoginResponse response = authService.login(
                new LoginRequest(request.username(), request.password()));
        if (response.getToken() == null) {
            return ResponseEntity.badRequest().body(response);
        }
        // 管理端不向学生账号开放
        if (response.getRole() != null && response.getRole() == 1) {
            return ResponseEntity.badRequest().body(Map.of("message", "学生账号请使用移动端或学生端登录"));
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 访客登录：无需账号密码，返回只读 token（最长 2 小时）。
     * 以管理员视图浏览数据；JwtAuthFilter 会拦截所有非 GET 请求，前端也会禁用写操作。
     */
    @PostMapping("/guest")
    public ResponseEntity<LoginResponse> guestLogin() {
        User admin = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getRole, 3)
                .eq(User::getStatus, 1)
                .last("LIMIT 1"));
        Long adminId = admin != null ? admin.getId() : 1L;
        List<String> roles = sysRoleMapper.selectRoleCodesByUserId(adminId);
        if (roles.isEmpty()) {
            roles = List.of("admin");
        }
        List<String> permissions = sysPermissionMapper.selectPermCodesByUserId(adminId);
        String token = jwtUtil.generateGuestToken(adminId);
        return ResponseEntity.ok(LoginResponse.builder()
                .token(token)
                .realName("访客")
                .username("guest")
                .role(3)
                .userId(adminId)
                .roles(roles)
                .permissions(permissions)
                .message("访客模式")
                .build());
    }
}
