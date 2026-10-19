package com.cmh.psycare.dto;

import java.time.LocalDateTime;

/**
 * 会话消息明细项。
 *
 * <p>{@code finishReason} 透出给前端是有意的：它让「这条回复本来就短」与
 * 「这条被截断了」在界面上可分辨（当前端只用来做诊断展示，不改文案）。
 */
public record AiMessageVO(
        Long id,
        /** RESIDENT / DOCTOR / AI */
        String sender,
        String content,
        Integer crisisFlag,
        /** 仅 AI 消息有值：DONE / INTERRUPTED / CRISIS / ERROR */
        String finishReason,
        LocalDateTime createdAt) {
}
