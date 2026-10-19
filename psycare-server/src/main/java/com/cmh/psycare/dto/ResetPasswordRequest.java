package com.cmh.psycare.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 重置密码入参。newPassword 留空则由后端生成随机强密码并回显 */
@Data
public class ResetPasswordRequest {

    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
    private String newPassword;
}
