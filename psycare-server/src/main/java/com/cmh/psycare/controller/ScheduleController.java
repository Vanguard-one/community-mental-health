package com.cmh.psycare.controller;

import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.ScheduleDayVO;
import com.cmh.psycare.dto.ScheduleSaveRequest;
import com.cmh.psycare.dto.ScheduleSlotVO;
import com.cmh.psycare.dto.WeeklyScheduleRequest;
import com.cmh.psycare.dto.WeeklyScheduleResultVO;
import com.cmh.psycare.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 出诊号源接口。
 *
 * <p>居民端与医生端共用这个 Controller，但<strong>角色各自断言</strong>：
 * 读别人的号源是居民的行为，配自己的号源是医生的行为，两者的授权口径完全不同，
 * 所以不按路径前缀放行，而是每个方法开头单独 {@code requireRole}。
 * 前端把按钮藏起来不构成防线（硬约定 #18）。
 */
@RestController
@RequestMapping("/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /**
     * 某医生未来 N 天的号源（居民端选时间用）。
     *
     * <p>{@code days} 只接受 1~30，Service 内部再 clamp 一次 ——
     * Controller 挡住越界值只是为了不把 3650 天这种请求带进 SQL。
     */
    @GetMapping("/doctor/{doctorId}")
    public Result<List<ScheduleDayVO>> doctorSchedule(@PathVariable Long doctorId,
                                                      @RequestParam(defaultValue = "7") int days) {
        SecurityUtil.requireRole("RESIDENT");
        return Result.ok(scheduleService.doctorSchedule(doctorId, days));
    }

    /**
     * 我自己的号源（医生端配置页）。
     *
     * <p>没有 {@code doctorId} 入参 —— 身份从 JWT 取。只要入参里没有这个字段，
     * 「把别人的 id 填进来」这条越权路径就不存在。
     */
    @GetMapping("/mine")
    public Result<List<ScheduleDayVO>> mine(@RequestParam(required = false) String from,
                                            @RequestParam(defaultValue = "14") int days) {
        SecurityUtil.requireRole("DOCTOR");
        return Result.ok(scheduleService.mine(parseDate(from), days));
    }

    /** 保存某天的整组时段：存在则改上限、不存在则插入，本次未提交的旧时段不删 */
    @PostMapping("/batch")
    public Result<List<ScheduleSlotVO>> saveBatch(@Valid @RequestBody ScheduleSaveRequest req) {
        SecurityUtil.requireRole("DOCTOR");
        return Result.ok(scheduleService.saveBatch(req));
    }

    /**
     * 按周批量铺号源：把一组时段模板铺到若干个周几上，连续若干周。
     *
     * <p>与 {@code /batch} 并存的理由见 {@code WeeklyScheduleRequest} 类注释：
     * 单日接口解决「临时加一天」，批量接口解决「周一到周五一直排到下下周」。
     * 后者要发 10 次请求才能做到，而漏掉一天不会有任何提示。
     */
    @PostMapping("/weekly")
    public Result<WeeklyScheduleResultVO> weekly(@Valid @RequestBody WeeklyScheduleRequest req) {
        SecurityUtil.requireRole("DOCTOR");
        return Result.ok(scheduleService.weekly(req));
    }

    /** 删除一个时段：只有一位都没约过才允许 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        SecurityUtil.requireRole("DOCTOR");
        scheduleService.deleteSlot(id);
        return Result.ok();
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            // 归到 400 而不是让兜底报成 500：这是调用方参数写错，不是系统故障
            throw new BizException(400, "日期格式不正确：" + text);
        }
    }
}
