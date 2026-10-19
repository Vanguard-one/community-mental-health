package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.BannerRequest;
import com.cmh.psycare.entity.Banner;
import com.cmh.psycare.service.BannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 首页轮播 banner 接口。
 *
 * <h3>为什么 active 不要求 ADMIN</h3>
 * <p>它是首页的运营展示位，居民和医生都要看 —— 只要求「已登录」
 * （由 {@code JwtAuthFilter} 保证，本方法不额外断言）。这与
 * {@code /notification/my} 同一性质。其余所有写操作与全量列表都是
 * 管理端能力，逐个断言 {@code requireRole("ADMIN")}（硬约定 #18：
 * 本项目无全局授权，前端藏菜单不算安全边界）。
 *
 * <h3>为什么管理端列表与首页列表是两个接口而不是一个带参数</h3>
 * <p>两者的过滤条件<b>语义相反</b>：首页要「过滤掉不能展示的」，
 * 管理端要「一条都不漏」。用一个 {@code ?all=true} 开关会让
 * 「读错分支 → 首页泄露已停用 banner」这种 bug 有了发生的可能，
 * 而两个独立端点各自的意图在调用处一眼可见。
 */
@RestController
@RequestMapping("/banner")
@RequiredArgsConstructor
public class BannerController {

    private final BannerService bannerService;

    /* ==================== 首页（登录即可） ==================== */

    /** 首页轮播数据：仅启用中且在时间窗口内的 */
    @GetMapping("/active")
    public Result<List<Banner>> active() {
        return Result.ok(bannerService.active());
    }

    /* ==================== 管理端 ==================== */

    @GetMapping("/list")
    public Result<List<Banner>> list() {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(bannerService.listAll());
    }

    @PostMapping
    public Result<Banner> create(@RequestBody BannerRequest req) {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(bannerService.create(req));
    }

    @PutMapping("/{id}")
    public Result<Banner> update(@PathVariable Long id, @RequestBody BannerRequest req) {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(bannerService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        SecurityUtil.requireRole("ADMIN");
        bannerService.delete(id);
        return Result.ok();
    }

    /** 启用 / 停用（切换，不需前端传目标值） */
    @PutMapping("/{id}/status")
    public Result<Banner> toggleStatus(@PathVariable Long id) {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(bannerService.toggleStatus(id));
    }

    /** 上移 / 下移，{@code direction = up | down}。返回重排后的完整列表，前端直接替换 */
    @PutMapping("/{id}/move")
    public Result<List<Banner>> move(@PathVariable Long id,
                                     @RequestParam String direction) {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(bannerService.move(id, direction));
    }
}
