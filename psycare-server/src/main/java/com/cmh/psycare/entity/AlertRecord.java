package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cmh.psycare.common.enums.AlertStatus;
import com.cmh.psycare.common.enums.AlertType;
import com.cmh.psycare.common.enums.RiskLevel;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预警工单
 */
@Data
@TableName("alert_record")
public class AlertRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long residentId;

    /** 关联测评记录（趋势预警可能关联最新一次） */
    private Long assessmentId;

    private AlertType alertType;

    private RiskLevel alertLevel;

    private AlertStatus status;

    /** 处理人（管理员/医生 userId） */
    private Long handlerId;

    /** 处理备注 */
    private String handleNote;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
