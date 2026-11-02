package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 居民提交预约。
 *
 * <p>入参里<strong>没有</strong> {@code residentId}、也没有 {@code doctorId}：
 * 居民身份从 JWT 取（{@code SecurityUtil.getUserId()}），医生与时段从
 * {@code scheduleId} 反查。这是本项目防越权的一贯做法 —— 只要 DTO 里没有这个字段，
 * 就不存在「把别人的 id 填进来」这条攻击路径（个人资料编辑那次踩过同样的坑）。
 *
 * <p>{@code consentVersion} 是知情同意书的版本号。心理健康场景里「用户同意了什么」
 * 必须留痕，所以它和 {@code consentAt} 一起落库。前端未勾选同意时提交按钮是禁用的，
 * 但后端仍要校验 —— 前端禁用只是体验，不是防线。
 */
@Data
public class AppointmentCreateRequest {

    @NotNull(message = "请选择就诊时段")
    private Long scheduleId;

    @NotBlank(message = "请填写就诊人姓名")
    @Size(max = 32, message = "姓名不能超过 32 个字")
    private String patientName;

    @NotBlank(message = "请填写联系电话")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请填写正确的手机号")
    private String patientPhone;

    @Size(max = 255, message = "主诉不能超过 255 个字")
    private String chiefComplaint;

    /** 知情同意书版本号，如 "v1"。留痕用，不参与业务判断 */
    @NotBlank(message = "请先阅读并同意知情同意书")
    private String consentVersion;
}
