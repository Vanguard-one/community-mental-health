package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AssessmentRecordVO;
import com.cmh.psycare.dto.AssessmentResultResponse;
import com.cmh.psycare.dto.AssessmentSubmitRequest;
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
