package com.cmh.psycare.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 取消预约请求。
 *
 * <p>整个 body 可以为空（用户直接点「确认取消」不说理由），所以 {@code reason} 不加
 * {@code @NotBlank}。长度上限必须有 —— 这一列会进站内信正文，不设上限等于允许
 * 用户把任意长文本推进医生的消息箱。
 */
@Data
public class AppointmentCancelRequest {

    @Size(max = 128, message = "取消原因不能超过 128 个字")
    private String reason;
}
