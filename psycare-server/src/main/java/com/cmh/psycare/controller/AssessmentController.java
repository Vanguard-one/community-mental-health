package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AssessmentRecordVO;
import com.cmh.psycare.dto.AssessmentResultResponse;
import com.cmh.psycare.dto.AssessmentSubmitRequest;
import com.cmh.psycare.dto.LatestReportVO;
import com.cmh.psycare.entity.Assessment;
import com.cmh.psycare.service.AssessmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 心理测评接口
 */
@RestController
@RequestMapping("/assessment")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    /** 提交测评：计分 → 落库 → 预警评估 */
    @PostMapping("/submit")
    public Result<AssessmentResultResponse> submit(@Valid @RequestBody AssessmentSubmitRequest req) {
        return Result.ok(assessmentService.submit(req));
    }

    /** 我的测评历史（时间序列） */
    @GetMapping("/history")
    public Result<List<Assessment>> history() {
        return Result.ok(assessmentService.history(SecurityUtil.getUserId()));
    }

    /**
     * 最近一次测评报告（AI 问诊页「报告」视图）。
     *
     * <p>从未做过测评时返回 {@code data: null} 而不是 404 ——「还没测过」是
     * 一个正常状态，不是错误，前端要据此渲染空状态卡而不是错误提示。
     *
     * <p>路径必须排在 {@code /{id}} 之前声明。Spring 的路径匹配本身是
     * 「字面量段优先于变量段」，所以顺序不影响结果；但把 /latest-report 写在
     * /{id} 后面会让人误以为它会被解析成 id=latest-report，读代码时白花时间。
     */
    @GetMapping("/latest-report")
    public Result<LatestReportVO> latestReport() {
        return Result.ok(assessmentService.latestReport(SecurityUtil.getUserId()));
    }

    /**
     * 按 id 查测评的完整做题记录。
     * <p>
     * 补上此前的接口缺口：结果页原先只能靠 sessionStorage 缓存渲染，
     * 刷新或深链必然打不开；工单右栏的「查看做题记录」也需要它做跳转。
     * <p>
     * 访问控制：居民只能查自己的（Service 内校验 residentId），
     * 医生/管理员可查任意记录，用于工单处置。
     */
    @GetMapping("/{id}")
    public Result<AssessmentRecordVO> detail(@PathVariable Long id) {
        return Result.ok(assessmentService.getRecord(id));
    }
}
