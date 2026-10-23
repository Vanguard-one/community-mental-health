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

    /**
     * 头像相对 URL，null = 未设置（前端回落角色图标）。
     * <p>
     * <b>后加字段</b>（2026-10）：只加不改，前四个字段与 role 的契约未动。
     * 放在登录响应里而不是让前端登录后再查一次 {@code /profile/me}，
     * 是为了让侧栏头像在<b>首屏</b>就有值 —— 否则每个页面加载都会先闪一下
     * 默认图标再换成真实头像，这个闪烁在固定位置的侧栏上非常显眼。
     */
    private String avatarUrl;
    /** 角色差异化信息：医生 department、管理员 adminLevel 等，可为 null */
    private Map<String, Object> extra;
}
