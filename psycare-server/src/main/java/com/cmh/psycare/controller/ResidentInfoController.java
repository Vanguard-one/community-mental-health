package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.PageResult;
import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AssessmentHistoryVO;
import com.cmh.psycare.dto.ResidentInfoVO;
import com.cmh.psycare.service.ResidentInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 医生端「用户信息」：居民档案 + 所属评测问卷。
 *
 * <p>权限：仅 DOCTOR。这是**医生看患者**的视角，管理员不在此列
 * （管理员另有账号治理页，职责不同）。
 */
@RestController
@RequestMapping("/doctor/resident")
@RequiredArgsConstructor
public class ResidentInfoController {

    private final ResidentInfoService residentInfoService;

    private void requireDoctor() {
        SecurityUtil.requireRole("DOCTOR");
    }

    /**
     * 居民分页列表（带测评概览与该医生的会话 id）。
     *
     * @param keyword 可选，按姓名或登录名模糊搜
     */
    @GetMapping("/page")
    public Result<PageResult<ResidentInfoVO>> page(@RequestParam(defaultValue = "1") long current,
                                                   @RequestParam(defaultValue = "10") long size,
                                                   @RequestParam(required = false) String keyword) {
        requireDoctor();
        return Result.ok(residentInfoService.page(current, size, keyword));
    }

    /** 该居民的历次测评 = 所属评测问卷（卷名 / 分数 / 风险 / 完成时间） */
    @GetMapping("/{id}/assessments")
    public Result<List<AssessmentHistoryVO>> assessments(@PathVariable Long id) {
        requireDoctor();
        return Result.ok(residentInfoService.assessments(id));
    }
}
