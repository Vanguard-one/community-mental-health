package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * AI 问诊消息请求
 */
@Data
public class AiChatRequest {

    @NotNull(message = "会话ID不能为空")
    private Long sessionId;

    @NotBlank(message = "消息内容不能为空")
    private String content;
}
