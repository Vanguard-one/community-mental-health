package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员表（角色差异字段）。
 * <p>
 * 管理员没有业务档案，本表只承担分级，用于防止
 * 「普通管理员把最后一个超级管理员禁用/删除」导致系统无人可管。
 */
@Data
@TableName("admin")
public class Admin {

    /** 主键 = sys_user.id，非自增 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** SUPER 超级管理员 / NORMAL 普通管理员 */
    private String adminLevel;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
