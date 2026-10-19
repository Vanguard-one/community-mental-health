package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 医生表（角色差异字段 + 资质审核）。
 * <p>
 * 与 {@link SysUser} 以 id 1:1。姓名/手机号等账号共性字段在基表，
 * 本表只放医生特有信息。因此「推荐引擎取医生名」需要与基表 join。
 * <p>
 * 相比原 doctor_profile，本表新增了资质审核四个字段 —— 医生注册后
 * 处于 PENDING，登录链路会直接拒绝，避免无资质账号进入处置台。
 */
@Data
@TableName("doctor")
public class Doctor {

    /** 主键 = sys_user.id，非自增 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 执业证书编号（资质材料） */
    private String licenseNo;

    private String department;

    private String title;

    /** 专长标签，逗号分隔，如「抑郁,焦虑,睡眠」 */
    private String expertiseTags;

    private Double rating;

    /** 当前接诊量 */
    private Integer currentLoad;

    /** 1 可接诊 */
    private Integer available;

    /** PENDING 待审核 / APPROVED 通过 / REJECTED 驳回 */
    private String auditStatus;

    /** 审核意见（驳回时说明原因） */
    private String auditRemark;

    /** 审核人 admin.id */
    private Long auditedBy;

    private LocalDateTime auditedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
