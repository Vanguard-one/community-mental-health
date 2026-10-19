package com.cmh.psycare.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 启用 / 禁用账号入参 */
@Data
public class AccountStatusRequest {

    /** 1 启用 0 禁用 */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态取值只能是 0 或 1")
    @Max(value = 1, message = "状态取值只能是 0 或 1")
    private Integer status;
}
