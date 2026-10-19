package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.PageResult;
import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AccountStatusRequest;
import com.cmh.psycare.dto.AccountVO;
import com.cmh.psycare.dto.AuditRequest;
import com.cmh.psycare.dto.ResetPasswordRequest;
import com.cmh.psycare.service.AccountService;
import jakarta.validation.Valid;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端账号治理。
 *
 * <p>路径变量 {userType} 取 RESIDENT / DOCTOR / ADMIN，仅作为**声明**；
 * Service 会按 id 回查账号真实角色并校验一致，防止用错误的类型路径越权操作。
 *
 * <p>全部接口限 ADMIN。医生不能进这里 —— 账号治理是纯管理职能。
 */
@RestController
@RequestMapping("/admin/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    private void requireAdmin() {
        SecurityUtil.requireRole("ADMIN");
    }

    /**
     * 账号分页列表。
     * userType 不传 = 三端汇总；auditStatus 仅对 DOCTOR 有意义。
     */
    @GetMapping("/page")
    public Result<PageResult<AccountVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String auditStatus,
            @RequestParam(required = false) String keyword) {
        requireAdmin();
        return Result.ok(accountService.page(current, size, userType, status, auditStatus, keyword));
    }

    /** 待审核医生数（菜单角标） */
    @GetMapping("/pending-audit-count")
    public Result<Integer> pendingAuditCount() {
        requireAdmin();
        return Result.ok(accountService.pendingAuditCount());
    }

    /** 启用 / 禁用。入参 status：1 启用 0 禁用 */
    @PutMapping("/{userType}/{id}/status")
    public Result<Void> updateStatus(@PathVariable String userType,
                                     @PathVariable Long id,
                                     @Valid @RequestBody AccountStatusRequest req) {
        requireAdmin();
        accountService.updateStatus(userType, id, req.getStatus());
        return Result.ok();
    }

    /** 逻辑删除（保留审计，业务数据不级联删除） */
    @DeleteMapping("/{userType}/{id}")
    public Result<Void> delete(@PathVariable String userType, @PathVariable Long id) {
        requireAdmin();
        accountService.delete(userType, id);
        return Result.ok();
    }

    /**
     * 重置密码。newPassword 可留空，留空则由后端生成随机强密码。
     * 返回明文供管理员转达给用户 —— 库中只存 BCrypt 哈希，无法反查，
     * 不回显的话这次重置就白做了。
     */
    @PostMapping("/{userType}/{id}/reset-password")
    public Result<Map<String, String>> resetPassword(@PathVariable String userType,
                                                     @PathVariable Long id,
                                                     @Valid @RequestBody ResetPasswordRequest req) {
        requireAdmin();
        String plain = accountService.resetPassword(userType, id, req.getNewPassword());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("password", plain);
        return Result.ok(data);
    }

    /** 医生资质审核。只提交通过/驳回结论；auditRemark 可选，界面不采集 */
    @PutMapping("/{userType}/{id}/audit")
    public Result<Void> audit(@PathVariable String userType,
                              @PathVariable Long id,
                              @Valid @RequestBody AuditRequest req) {
        requireAdmin();
        accountService.audit(id, req.getAuditStatus(), req.getAuditRemark());
        return Result.ok();
    }
}
