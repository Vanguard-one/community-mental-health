package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 登录请求。
 * <p>
 * userType 可选：登录页有身份切换时前端会带上，后端据此做「角色一致性校验」
 * （防止拿居民账号走医生入口）。不传时后端按账号实际 role 自行路由，
 * 因此**旧版前端不改也能继续登录**。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /** 可选：RESIDENT / DOCTOR / ADMIN */
    @Pattern(regexp = "^$|^(RESIDENT|DOCTOR|ADMIN)$", message = "身份取值不合法")
    private String userType;
}
