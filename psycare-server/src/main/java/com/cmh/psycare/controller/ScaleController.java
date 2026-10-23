package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AdminScaleVO;
import com.cmh.psycare.entity.Scale;
import com.cmh.psycare.service.ScaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 量表 / 筛查表接口。
 *
 * <h3>权限划分</h3>
 * <p>{@code /list} 与 {@code /{id}/detail} 是居民答题链路，<b>登录即可</b>
 * （由 JwtAuthFilter 保证）；{@code /manage/**} 与删除是管理端能力，
 * 逐个方法显式断言 {@code requireRole("ADMIN")} ——
 * 本项目没有全局授权，前端藏菜单不算安全边界（硬约定 #18）。
 *
 * <h3>为什么管理端不用查询参数复用 /list</h3>
 * <p>两条路径的过滤条件语义相反：居民端"只给能做的"，管理端"一张都不能漏"。
 * 共用端点加个 {@code ?all=true} 之后，一旦参数漏传或传错，
 * 已禁用的量表就会出现在居民端列表里 —— 这是会直接导致误测的 bug。
 * 两个端点各自的意图在调用处一眼可见，比省一个方法更值。
 */
@RestController
@RequestMapping("/scale")
@RequiredArgsConstructor
public class ScaleController {

    private final ScaleService scaleService;

    /* ==================== 居民端（登录即可） ==================== */

    @GetMapping("/list")
    public Result<List<Scale>> list() {
        return Result.ok(scaleService.listEnabled());
    }

    /** 问卷页结构：量表 + 题目 + 选项 */
    @GetMapping("/{id}/detail")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(scaleService.detail(id));
    }

    /* ==================== 管理端：筛查表管理 ==================== */

    /**
     * 全量筛查表（含已禁用，不含已删除），附带题数与测评次数。
     * <p>此前的 {@code /scale/list} 只返回启用中的量表，
     * 管理端一禁用就"找不回那条记录"，无法再启用 —— 所以单独开这一个。
     */
    @GetMapping("/manage/list")
    public Result<List<AdminScaleVO>> manageList() {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(scaleService.listForAdmin());
    }

    /**
     * 启用 / 禁用。传目标值（{@code status=1} 启用、{@code status=0} 禁用），
     * 不用 toggle —— 双击与并发操作下 toggle 会把状态翻回去。
     */
    @PutMapping("/{id}/status")
    public Result<Scale> setStatus(@PathVariable Long id, @RequestParam Integer status) {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(scaleService.setStatus(id, status));
    }

    /**
     * 删除筛查表（逻辑删除，历史测评记录保留）。
     * <p>删除后居民端立即不可见、不可提交；管理端列表不再展示。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        SecurityUtil.requireRole("ADMIN");
        scaleService.remove(id);
        return Result.ok();
    }
}
