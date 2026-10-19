package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 医生资质审核入参 */
@Data
public class AuditRequest {

    @NotBlank(message = "审核结果不能为空")
    @Pattern(regexp = "^(APPROVED|REJECTED)$", message = "审核结果只能是 APPROVED 或 REJECTED")
    private String auditStatus;

    /**
     * 可选文字意见。当前审核界面不采集（只提交通过/驳回结论），
     * 字段保留以兼容既有契约；若填了，会在医生登录被拒时拼进提示文案。
     */
    @Size(max = 255, message = "审核意见过长")
    private String auditRemark;
}
