package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.dto.RecommendResult;
import com.cmh.psycare.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 医生推荐接口
 */
@RestController
@RequestMapping("/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;

    /** 基于最近测评推荐医生（AI 问诊结束触发时传 sessionId） */
    @GetMapping("/doctors")
    public Result<List<RecommendResult>> doctors(@RequestParam(required = false) Long sessionId,
                                                 @RequestParam(defaultValue = "3") int topN) {
        return Result.ok(recommendService.recommendForCurrentResident(sessionId, topN));
    }
}
