package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预警工单状态流转日志
 */
@Data
@TableName("alert_status_log")
public class AlertStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long alertId;

    private String fromStatus;

    private String toStatus;

    private Long operatorId;

    private String remark;

    private LocalDateTime createdAt;
}
