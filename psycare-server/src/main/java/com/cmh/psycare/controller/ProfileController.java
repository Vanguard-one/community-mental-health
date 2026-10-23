package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.dto.ChangePasswordRequest;
import com.cmh.psycare.dto.ProfileUpdateRequest;
import com.cmh.psycare.dto.ProfileVO;
import com.cmh.psycare.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人资料：三端（居民 / 医生 / 管理员）共用，改的都是「自己那份」。
 *
 * <h3>为什么这里没有 requireRole</h3>
 * <p>与 {@code AccountController}（全接口断言 ADMIN）刚好相反 —— 本控制器的
 * 授权模型是「登录即可，但范围被锁死在自己身上」。范围不是靠角色断言实现的，
 * 而是靠<b>不接收 id</b>：目标账号只能由 {@code SecurityUtil.getUserId()} 决定。
 * <p>所以这里的注解不是漏写。真要加断言，写成 {@code requireRole("RESIDENT",
 * "DOCTOR", "ADMIN")} 与不加完全等价（role 只有这三种，且 getUserId 已挡掉未登录），
 * 反而会让人误以为「角色」在这里是安全边界。
 *
 * <h3>为什么用 PUT 而不是 POST</h3>
 * <p>两次相同的 PUT 结果一致（幂等），符合语义；改密码同理 ——
 * 重复提交同一个新密码，第二次会被「新密码不能与当前密码相同」挡掉，
 * 不会产生副作用。
 */
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /** 当前登录者的资料 */
    @GetMapping("/me")
    public Result<ProfileVO> me() {
        return Result.ok(profileService.getMine());
    }

    /** 修改资料：账号、姓名、手机号、头像。返回保存后的资料，供前端直接替换本地状态 */
    @PutMapping("/me")
    public Result<ProfileVO> update(@Valid @RequestBody ProfileUpdateRequest req) {
        return Result.ok(profileService.updateMine(req));
    }

    /**
     * 修改密码。需要旧密码。
     * <p>
     * 不返回新 token —— 改密码不影响 token 有效期（原因见
     * {@code ProfileService#changePassword}）。前端在成功后自行登出，
     * 强制用新密码重新登录。
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        profileService.changePassword(req);
        return Result.ok();
    }
}
