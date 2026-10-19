package com.cmh.psycare.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号治理列表项（三端统一视图）。
 * <p>
 * 刻意做成一个宽 VO 而非三个：管理端的「全部」tab 需要三张表 UNION 后
 * 同构展示，字段按 userType 分支填充，前端只需按类型决定显示哪些列。
 * <p>
 * 安全约定：**绝不包含 password**。这是白名单 DTO，不是实体直出。
 */
@Data
@Builder
public class AccountVO {

    private Long id;

    /** RESIDENT / DOCTOR / ADMIN */
    private String userType;

    private String username;

    private String realName;

    /** 已脱敏，如 138****0001 */
    private String phone;

    /** 1 正常 0 禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    /** 医生专有：PENDING / APPROVED / REJECTED */
    private String auditStatus;

    /** 医生专有：审核意见 */
    private String auditRemark;

    /** 医生专有 */
    private String department;

    /** 医生专有 */
    private String title;

    /** 医生专有，逗号分隔的专长标签 */
    private String expertiseTags;

    /** 医生专有：执业证书编号 */
    private String licenseNo;

    /** 管理员专有：SUPER / NORMAL */
    private String adminLevel;

    /** 居民专有 */
    private String gender;

    /** 居民专有 */
    private Integer birthYear;
}
