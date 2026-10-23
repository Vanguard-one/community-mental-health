package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统账号（认证基表）
 * <p>
 * 角色拆分后本表只承担「账号共性 + 认证」职责：
 * 登录名、密码、角色、状态、逻辑删除。三张角色表 user / doctor / admin
 * 以 id 与本表 1:1，承载各角色差异字段。
 * <p>
 * 全局 id 空间由本表自增保证，因此通知/工单处置人/问诊医生等
 * 不带角色的跨表引用无需改造。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名（全局唯一，仅未删除行唯一） */
    private String username;

    /** 密码（BCrypt 哈希存储，禁止明文） */
    private String password;

    /** 姓名（展示层脱敏） */
    private String realName;

    /** 手机号（展示层脱敏，存储可加密） */
    private String phone;

    /**
     * 头像相对 URL，形如 {@code /api/uploads/avatar/2026/10/19/xxx.png}。
     * <p>
     * 放在基表而不是角色表：头像对三种角色语义一致，属「账号共性」。
     * NULL = 未设置 → 前端回落到 {@code RoleAvatar} 的角色图标。
     * <p>
     * 为什么存路径不存 BLOB：与 banner 同一套理由（文件落本地磁盘）。
     * 尤其不能存 BLOB —— sys_user 是登录的点查表，让它随图片体积膨胀
     * 会直接拖慢每一次登录。
     */
    private String avatarUrl;

    /** 角色：RESIDENT / DOCTOR / ADMIN */
    private String role;

    /** 状态：1 正常 0 禁用 */
    private Integer status;

    /**
     * 逻辑删除标志：0 未删除 1 已删除。
     * <p>
     * 有 @TableLogic 才会真正生效。此前 application.yml 配了
     * logic-delete-field: deleted 而实体无该字段，导致「逻辑删除」静默失效、
     * removeById 走的是物理删除。改名与加字段必须成对完成。
     */
    @TableLogic
    private Integer isDeleted;

    /** 删除时间（审计留痕，@TableLogic 不管理，由 Service 显式写入） */
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
