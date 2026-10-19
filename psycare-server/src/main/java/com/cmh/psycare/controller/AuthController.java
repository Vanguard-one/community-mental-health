package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.dto.DoctorRegisterRequest;
import com.cmh.psycare.dto.LoginRequest;
import com.cmh.psycare.dto.LoginResponse;
import com.cmh.psycare.dto.RegisterResponse;
import com.cmh.psycare.dto.ResidentRegisterRequest;
import com.cmh.psycare.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <h3>注册为什么拆成两个接口</h3>
 * 居民与医生的入参差异大（医生要执业证书号，居民要性别/出生年等），
 * 共用一个接口只能退回 Map 接收 —— 而 Map 拿不到 @Valid，脏数据全靠 Service 兜。
 * 拆成两个带 DTO 的接口后校验声明式完成，且返回体可以按角色给出不同提示
 * （医生要明确告知「需审核后才能登录」）。
 *
 * <h3>破坏性变更</h3>
 * 原有的 POST /auth/register（Map 入参）已移除，由下面两个接口取代。
 * 前端 api/auth.ts 已在同一次改动中拆分，无其他调用方。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录。入参 userType 可选：
     * 带则做身份一致性校验，不带则后端按账号实际角色自行路由（兼容旧前端）。
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    /** 居民注册：写 sys_user + user，注册后即可登录 */
    @PostMapping("/register/resident")
    public Result<RegisterResponse> registerResident(
            @Valid @RequestBody ResidentRegisterRequest req) {
        return Result.ok(authService.registerResident(req));
    }

    /**
     * 医生注册：写 sys_user + doctor，初始 audit_status = PENDING，
     * 需管理员审核通过后才能登录（返回体中的 tip 已说明）。
     */
    @PostMapping("/register/doctor")
    public Result<RegisterResponse> registerDoctor(
            @Valid @RequestBody DoctorRegisterRequest req) {
        return Result.ok(authService.registerDoctor(req));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            authService.logout(header.substring(7));
        }
        return Result.ok();
    }
}
