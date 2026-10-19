package com.cmh.psycare.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 历史 AI 会话列表项。
 *
 * <p>用可变类而不是 record：它是 MyBatis 的 {@code resultType} 映射目标，
 * 需要无参构造器 + setter。{@code stage} 不在这里 —— 阶段存在 Redis / 由历史重放得出，
 * 列表接口若要显示就得为每条会话各查一次，而列表本身只需要「何时聊过、聊了几条、最后一句」。
 */
@Data
public class AiSessionVO {

    private Long sessionId;

    /** OPEN / CLOSED */
    private String status;

    /** 发起这次会话的测评记录（可能为 null） */
    private Long assessmentId;

    private Integer messageCount;

    /** 最后一条消息内容，前端做预览（会在前端截断） */
    private String lastMessage;

    private LocalDateTime lastAt;

    private LocalDateTime createdAt;
}
