package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 居民注册入参。
 * <p>
 * 差异字段（性别/出生年/职业/紧急联系人）全部可选 —— 存量居民
 * 档案表实际为空，设为必填会让老用户无法迁移，也会挡住快速注册。
 */
@Data
public class ResidentRegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{4,32}$", message = "用户名只能包含字母、数字、下划线，长度 4-32")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
    private String password;

    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 32, message = "姓名过长")
    private String realName;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** M / F / U */
    @Pattern(regexp = "^$|^[MFU]$", message = "性别取值必须是 M、F 或 U")
    private String gender;

    /** 出生年份，只到年（弱化识别） */
    private Integer birthYear;

    @Size(max = 64, message = "职业过长")
    private String occupation;

    @Size(max = 64, message = "紧急联系人过长")
    private String emergencyContact;
}
