package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 医生注册入参。
 * <p>
 * 注册成功后 audit_status = PENDING，此时**无法登录** ——
 * 需管理员在账号治理页审核通过。这是医疗场景的资质门禁。
 * <p>
 * licenseNo 不做唯一约束：执业证书编号存在历史格式差异，
 * 唯一性由管理员审核阶段人工判重，程序只做长度校验。
 */
@Data
public class DoctorRegisterRequest {

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

    /** 执业证书编号 —— 资质审核的凭据，必填 */
    @NotBlank(message = "执业证书编号不能为空")
    @Size(max = 64, message = "执业证书编号过长")
    private String licenseNo;

    @Size(max = 32, message = "科室名称过长")
    private String department;

    @Size(max = 32, message = "职称过长")
    private String title;

    /** 专长标签，逗号分隔，用于推荐引擎的维度匹配 */
    @Size(max = 255, message = "专长标签过长")
    private String expertiseTags;
}
