package com.cmh.psycare.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 医生端「保存某天时段」请求：一次提交该日期的整组时段。
 *
 * <h3>为什么整组提交而不是一条一条加</h3>
 * 医生配号源的真实动作是「把周三上午排出来」，通常是 4~8 个连续时段，
 * 一条一条提交意味着 8 次往返、8 次失败机会，中途失败就得到半个上午。
 * 整组提交让后端在一个事务里做「存在则改上限、不存在则插入」。
 *
 * <p><b>本接口只增改，不删除。</b> 本次没提交的旧时段不会被删掉 ——
 * 那里面可能已经有人约了。要删必须走 {@code DELETE /schedule/{id}}，
 * 那条接口会检查 {@code booked_count == 0}。这个语义必须在页面上写明，
 * 否则医生会以为「把行删掉再保存」等于删除号源。
 *
 * <p>{@code startTime} 用 String 而不是 {@code LocalTime}：前者能同时接受
 * {@code "09:00"} 与 {@code "09:00:00"}（浏览器 {@code <input type="time">} 给的是前者，
 * 手写测试常给后者），而把 {@code LocalTime} 交给 Jackson 需要一个全局
 * {@code @JsonFormat} 或自定义反序列化器 —— 为两个入参字段改全局配置不划算。
 */
@Data
public class ScheduleSaveRequest {

    @NotNull(message = "出诊日期不能为空")
    private LocalDate workDate;

    @NotEmpty(message = "至少保留一个时段")
    @Size(max = 20, message = "一天最多 20 个时段")
    @Valid
    private List<Slot> slots;

    @Data
    public static class Slot {

        @NotBlank(message = "开始时间不能为空")
        private String startTime;

        @NotBlank(message = "结束时间不能为空")
        private String endTime;

        @NotNull(message = "人数上限不能为空")
        @Min(value = 1, message = "人数上限至少为 1")
        @Max(value = 50, message = "单个时段上限不能超过 50 人")
        private Integer capacity;
    }
}
