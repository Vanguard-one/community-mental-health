package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cmh.psycare.common.enums.ConsultType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问诊会话（电话/医生图文/AI 三种渠道统一）
 */
@Data
@TableName("consult_session")
public class ConsultSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long residentId;

    /** 医生（AI 问诊为 null） */
    private Long doctorId;

    private ConsultType consultType;

    /** 状态：OPEN / CLOSED */
    private String status;

    /** 关联的测评记录（测评驱动问诊的落点） */
    private Long assessmentId;

    /** 关联的预警工单 */
    private Long alertId;

    /** 咨询小结 */
    private String summary;

    private LocalDateTime createdAt;
    private LocalDateTime closedAt;
}
