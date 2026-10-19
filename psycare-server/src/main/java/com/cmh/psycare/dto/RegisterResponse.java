package com.cmh.psycare.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 注册返回。
 * <p>
 * 医生注册后 auditStatus=PENDING 且**不能立即登录**，
 * 因此必须把这个状态透出给前端，让表单能给出「等待审核」的明确预期，
 * 而不是让用户去登录页反复试错。
 */
@Data
@Builder
public class RegisterResponse {

    private Long userId;

    /** RESIDENT / DOCTOR */
    private String userType;

    /** 仅医生有值：PENDING / APPROVED / REJECTED */
    private String auditStatus;

    /** 给用户看的一句话说明 */
    private String tip;
}
