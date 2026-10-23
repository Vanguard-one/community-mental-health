package com.cmh.psycare.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 个人资料（「我的资料」面板的读取结果）。
 *
 * <h3>为什么单独开一个 VO，不复用 LoginResponse</h3>
 * <p>两者的变更频率与消费方完全不同：
 * <ul>
 *   <li>{@code LoginResponse} 是<b>登录契约</b>，注释里明确写了
 *       「token / userId / username / realName / role 是稳定契约，前端 auth.ts、
 *       路由守卫、JWT 载荷都依赖它们」。往里加字段等于动所有端的登录链路。</li>
 *   <li>本 VO 只被资料面板消费，加字段（比如将来的「个人简介」）只影响一处。</li>
 * </ul>
 *
 * <h3>为什么带 role 与 roleLabel</h3>
 * <p>角色在面板里是<b>只读展示</b>：账号类型不能自助修改（那是管理员职能，
 * 见 {@code AccountController}）。前端要把「你是什么身份」明确摆出来，
 * 否则用户会以为是编辑框内容、尝试改动。
 *
 * <h3>为什么没有 password 字段</h3>
 * <p>库里只有 BCrypt 哈希，反查不出明文。面板里密码是「改」不是「看」，
 * 走独立的 {@code PUT /profile/password}。
 */
@Data
@Builder
public class ProfileVO {

    private Long id;

    /** 登录账号（可改，改后需唯一性校验） */
    private String username;

    /** 姓名（可改） */
    private String realName;

    /** 手机号（可改，可空） */
    private String phone;

    /** 头像相对 URL，null = 未设置 */
    private String avatarUrl;

    /** RESIDENT / DOCTOR / ADMIN，只读 */
    private String role;

    /** 角色的中文名，直接给前端展示，避免前端再维护一份映射 */
    private String roleLabel;
}
