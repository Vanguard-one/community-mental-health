package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码入参。
 *
 * <h3>为什么要校验旧密码</h3>
 * <p>「能调用这个接口」只证明<b>当前 token 有效</b>，不证明操作者是账号主人 ——
 * 典型场景是别人借用了一台没锁屏的电脑。校验旧密码是这道场景下唯一的门槛。
 * 管理员重置密码走的是另一条路（{@code AccountController#resetPassword}，
 * 断言 ADMIN），那里<b>不需要</b>旧密码，二者不能合并。
 *
 * <h3>为什么没有 confirmPassword</h3>
 * <p>二次确认是<b>防手滑</b>的交互手段，属于前端职责：多传一份密码到网络
 * 没有任何安全收益（攻击者拿到请求体时两个字段都拿到了）。
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "请输入当前密码")
    private String oldPassword;

    /**
     * 新密码。6-32 位。
     * <p>
     * 下限取 6 是沿用 {@code ResetPasswordRequest} 的既有约定 ——
     * 两条改密码路径若一个要求 8 位、一个要求 6 位，用户会以为系统出错。
     * 上限 32 是因为 BCrypt 只使用前 72 <b>字节</b>，中文密码 24 字就到顶，
     * 再长也不会增强安全性。
     */
    @NotBlank(message = "请输入新密码")
    @Size(min = 6, max = 32, message = "新密码长度需在 6-32 位之间")
    private String newPassword;
}
