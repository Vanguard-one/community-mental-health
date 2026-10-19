package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.PageResult;
import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AlertAssessmentVO;
import com.cmh.psycare.dto.AlertHandleRequest;
import com.cmh.psycare.dto.AlertReopenRequest;
import com.cmh.psycare.dto.StatusCount;
import com.cmh.psycare.entity.AlertRecord;
import com.cmh.psycare.entity.AlertStatusLog;
import com.cmh.psycare.service.AlertService;
import com.cmh.psycare.service.AssessmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 预警工单接口（医生端 / 管理端）。
 *
 * 权限：仅 DOCTOR 与 ADMIN。居民账号不得读取全域工单或执行流转。
 */
@RestController
@RequestMapping("/alert")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;
    private final AssessmentService assessmentService;

    /** 工单处置是医护侧能力，统一在入口断言角色 */
    private void requireStaff() {
        SecurityUtil.requireRole("ADMIN", "DOCTOR");
    }

    /**
     * 工单分页。
     *
     * @param status    状态过滤，支持逗号分隔多值（如 "CONFIRMED,PROCESSING"）
     * @param alertType 预警类型过滤（THRESHOLD / TREND / CRISIS）
     */
    @GetMapping("/page")
    public Result<PageResult<AlertRecord>> page(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String alertType) {
        requireStaff();
        return Result.ok(alertService.page(current, size, status, alertType));
    }

    /** 各状态工单数（固定返回 6 个状态，用于列表分组角标） */
    @GetMapping("/status-count")
    public Result<List<StatusCount>> statusCount() {
        requireStaff();
        return Result.ok(alertService.statusCount());
    }

    /** 工单详情：分栏处置台深链直达 / 刷新还原用 */
    @GetMapping("/{id}")
    public Result<AlertRecord> detail(@PathVariable Long id) {
        requireStaff();
        return Result.ok(alertService.detail(id));
    }

    /** 处置工单：状态机流转 */
    @PostMapping("/{id}/handle")
    public Result<Void> handle(@PathVariable Long id, @Valid @RequestBody AlertHandleRequest req) {
        requireStaff();
        alertService.handle(id, req);
        return Result.ok();
    }

    @GetMapping("/{id}/logs")
    public Result<List<AlertStatusLog>> logs(@PathVariable Long id) {
        requireStaff();
        return Result.ok(alertService.logs(id));
    }

    /**
     * 重新开启工单：把已驳回 / 已关闭的工单退回「待确认」，医生端会像新工单一样再次看到它。
     * <p>
     * <b>仅管理员</b>，故意不复用 {@link #handle}：医生不能打回自己的终态处置结果，
     * 否则「谁在什么时候结的案」这套留痕就失去意义。前端也只在 ADMIN 角色下渲染入口。
     * 目标状态由服务端固定为 PENDING，请求体只收备注。
     */
    @PostMapping("/{id}/reopen")
    public Result<Void> reopen(@PathVariable Long id,
                               @Valid @RequestBody(required = false) AlertReopenRequest req) {
        SecurityUtil.requireRole("ADMIN");
        alertService.reopen(id, req == null ? null : req.getNote());
        return Result.ok();
    }

    /**
     * 该工单关联用户的评测做题记录。
     * <p>
     * 返回「触发本工单的那次测评」逐题明细（卷名 / 题干 / 作答 / 得分 / 完成时间），
     * 外加该居民的历史测评摘要，供医生纵向判断病情走向。
     * 工单无关联测评（TREND 趋势类）时 record 为 null，history 仍返回。
     */
    @GetMapping("/{id}/assessment")
    public Result<AlertAssessmentVO> assessment(@PathVariable Long id) {
        requireStaff();
        return Result.ok(assessmentService.getAlertAssessment(id));
    }
}
