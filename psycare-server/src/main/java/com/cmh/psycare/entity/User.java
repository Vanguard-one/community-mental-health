package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 居民用户表（角色差异字段）。
 * <p>
 * 与 {@link SysUser} 以 id 1:1 —— 账号共性字段（username/password/role/status/
 * is_deleted/real_name/phone）一律只在基表保存，本表不重复存储，因此不存在双写。
 */
@Data
@TableName("user")
public class User {

    /**
     * 主键 = sys_user.id，非自增。
     * 必须先由 SysUser 的自增拿到全局唯一 id，再以该 id 插入本表。
     */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** M 男 / F 女 / U 未知 */
    private String gender;

    /** 出生年份（弱化识别，只到年） */
    private Integer birthYear;

    private String occupation;

    /** 紧急联系人（展示层脱敏） */
    private String emergencyContact;

    /** 档案创建时间 */
    private LocalDateTime createdAt;

    /** 档案最后修改时间（语义不同于账号更新时间） */
    private LocalDateTime updatedAt;
}
