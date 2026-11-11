package com.znxsgl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.znxsgl.config.JwtUtil;
import com.znxsgl.dto.LoginRequest;
import com.znxsgl.dto.LoginResponse;
import com.znxsgl.entity.User;
import com.znxsgl.mapper.SysPermissionMapper;
import com.znxsgl.mapper.SysRoleMapper;
import com.znxsgl.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;

    public AuthService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       SysRoleMapper sysRoleMapper,
                       SysPermissionMapper sysPermissionMapper) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.sysRoleMapper = sysRoleMapper;
        this.sysPermissionMapper = sysPermissionMapper;
    }

    public LoginResponse login(LoginRequest request) {
        // 用 username 查找用户（学号/用户名均可）
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.username()));

        if (user == null) {
            user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getStudentNo, request.username()));
        }

        if (user == null) {
            // 不区分「账号不存在」与「密码错误」，避免被枚举账号
            return new LoginResponse("账号或密码错误");
        }

        if (user.getStatus() != 1) {
            return new LoginResponse("账号已被禁用");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            return new LoginResponse("账号或密码错误");
        }

        // 自动填充会处理 lastLogin，这里显式赋值用于确保登录时间立即更新
        user.setLastLogin(LocalDateTime.now());

        // 生成新 token 并存入数据库（旧 token 自动失效）
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        user.setCurrentToken(token);
        userMapper.updateById(user);

        // 查询 RBAC 角色与权限列表
        List<String> roles = sysRoleMapper.selectRoleCodesByUserId(user.getId());
        List<String> permissions = sysPermissionMapper.selectPermCodesByUserId(user.getId());

        return LoginResponse.success(token, user.getRealName(), user.getUsername(),
                user.getRole(), user.getId(), user.getAvatarUrl(), roles, permissions);
    }
}
