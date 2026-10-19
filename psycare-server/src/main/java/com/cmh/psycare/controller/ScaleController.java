package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.entity.Scale;
import com.cmh.psycare.service.ScaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 量表/问卷接口
 */
@RestController
@RequestMapping("/scale")
@RequiredArgsConstructor
public class ScaleController {

    private final ScaleService scaleService;

    @GetMapping("/list")
    public Result<List<Scale>> list() {
        return Result.ok(scaleService.listEnabled());
    }

    /** 问卷页结构：量表 + 题目 + 选项 */
    @GetMapping("/{id}/detail")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(scaleService.detail(id));
    }
}
