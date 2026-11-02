package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AppointmentCancelRequest;
import com.cmh.psycare.dto.AppointmentCreateRequest;
import com.cmh.psycare.dto.AppointmentVO;
import com.cmh.psycare.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 预约接口（居民侧）。
 *
 * <p>医生端看预约信息走站内信，没有单独的名单接口 —— V1 的需求是
 * 「医生端在通知里可以看到预约信息」，多做一个名单页属于臆造需求。
 * 若后续需要，加 {@code GET /schedule/{id}/appointments}（Mapper 里
 * {@code selectBySchedule} 已经就绪）。
 */
@RestController
@RequestMapping("/appointment")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    /** 提交预约。居民身份取自 JWT，入参里没有 residentId / doctorId */
    @PostMapping
    public Result<AppointmentVO> create(@Valid @RequestBody AppointmentCreateRequest req) {
        SecurityUtil.requireRole("RESIDENT");
        return Result.ok(appointmentService.create(req));
    }

    /** 我的预约（含历史与已取消） */
    @GetMapping("/my")
    public Result<List<AppointmentVO>> my() {
        SecurityUtil.requireRole("RESIDENT");
        return Result.ok(appointmentService.my());
    }

    /**
     * 取消预约。body 可为空。
     *
     * <p>{@code @RequestBody(required = false)}：前端「不填理由直接取消」时
     * 不会带 body，如果这里强校验 body 会把一个正常操作报成 400。
     */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id,
                               @Valid @RequestBody(required = false) AppointmentCancelRequest req) {
        SecurityUtil.requireRole("RESIDENT");
        appointmentService.cancel(id, req == null ? null : req.getReason());
        return Result.ok();
    }
}
