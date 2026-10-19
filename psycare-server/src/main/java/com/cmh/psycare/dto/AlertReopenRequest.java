package com.cmh.psycare.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重新开启工单请求（仅管理员）。
 *
 * 只有一个可选备注，**不接受前端指定目标状态** ——
 * 「重新让医生查一遍」的语义只有一种（退回 PENDING），
 * 若开放 toStatus，等于把状态机暴露给前端，又会绕开 handle 的流转校验。
 */
@Data
public class AlertReopenRequest {

    @Size(max = 200, message = "备注长度不能超过 200 字")
    private String note;
}
