package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送聊天消息请求。
 * <p>
 * **刻意不接收 sender / senderId**：发送者身份一律由后端从 JWT 推导
 * （{@code SecurityUtil.getRole()} + {@code getUserId()}）。
 * 若让前端传，任何居民都能伪造一条署名医生的消息。
 */
@Data
public class ChatSendRequest {

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 2000, message = "单条消息不能超过 2000 字")
    private String content;
}
