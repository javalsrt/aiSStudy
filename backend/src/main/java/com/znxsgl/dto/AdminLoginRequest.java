package com.znxsgl.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 管理端登录请求：在账号密码之外强制校验图形验证码。
 * App 端仍使用 {@link LoginRequest}，不受验证码限制。
 */
public record AdminLoginRequest(

        @NotBlank(message = "账号不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password,

        @NotBlank(message = "验证码不能为空")
        String captchaId,

        @NotBlank(message = "验证码不能为空")
        String captchaCode
) {
}
