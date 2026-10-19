package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 预警工单处置请求
 */
@Data
public class AlertHandleRequest {

    @NotNull(message = "目标状态不能为空")
    private String toStatus;

    private String note;
}
