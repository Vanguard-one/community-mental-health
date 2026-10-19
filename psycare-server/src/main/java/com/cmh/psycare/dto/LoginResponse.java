package com.cmh.psycare.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 登录响应。
 * <p>
 * 前四个字段（token / userId / username / realName）与 role 是稳定契约，
 * 前端 auth.ts、路由守卫、JWT 载荷都依赖它们，**不要改名或删字段**。
 * <p>
 * 角色差异化信息（医生科室、管理员级别、医生审核状态）走 extra，
 * 刻意**不放进 JWT** —— 避免 token 体积膨胀，也避免审核状态变更后
 * 已签发 token 里的旧状态继续生效。
 */
@Data
@Builder
public class LoginResponse {
    private String token;
    private Long userId;
    private String username;
    private String realName;
    /** RESIDENT / DOCTOR / ADMIN */
    private String role;
    /** 角色差异化信息：医生 department、管理员 adminLevel 等，可为 null */
    private Map<String, Object> extra;
}
