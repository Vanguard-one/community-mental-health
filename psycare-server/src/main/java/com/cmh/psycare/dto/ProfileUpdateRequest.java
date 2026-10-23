package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料入参。
 *
 * <h3>为什么不带 id</h3>
 * <p>服务端一律用 {@code SecurityUtil.getUserId()} 取当前登录者，
 * <b>绝不从请求体接收 id</b>。这是这类接口最容易出的越权漏洞：
 * 一旦前端能传 id，任何登录用户改一下请求体就能改别人的账号
 * （包括把别人的用户名改成自己的，或改别人的密码）。
 * 入参里不放这个字段，是从结构上让它不可能发生 ——
 * 比「记得校验」可靠。
 *
 * <h3>为什么 avatarUrl 要做前缀白名单</h3>
 * <p>它会被原样写进 {@code <img src>}。若不做限制，攻击者可以：
 * <ul>
 *   <li>填 {@code javascript:...} —— 现代浏览器对 img src 不执行，但这类值
 *       一旦被别处以链接形式渲染就是 XSS 面；</li>
 *   <li>填任意外站 URL —— 头像变成「每次打开资料页都向第三方发一次请求」的
 *       追踪像素，且第三方能看到我们用户的活动时间。</li>
 * </ul>
 * <p>所以只接受本系统自己上传接口产出的路径前缀（见 {@code UploadService#saveImage}），
 * 校验在 Service 里做 —— 前端校验是体验，不是安全边界。
 *
 * <h3>为什么 username 用这么严的字符集</h3>
 * <p>它是登录名，会出现在日志、URL、以及管理员列表里。限制为
 * 「字母开头 + 字母数字下划线」可以一并挡掉：空白字符（登录时极易复制出
 * 尾部空格）、全角字符（视觉上像同名、排查极难）、以及任何需要 URL 编码的符号。
 */
@Data
public class ProfileUpdateRequest {

    /**
     * 登录账号。4-32 位，字母开头，只含字母数字下划线。
     * <p>
     * 1 位或 2 位的账号在管理端列表里与姓名挤在一起难以分辨，故下限取 4。
     */
    @NotBlank(message = "请输入登录账号")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{3,31}$",
            message = "账号需以字母开头，4-32 位，只能包含字母、数字和下划线")
    private String username;

    /** 姓名。允许中文，2-20 字 */
    @NotBlank(message = "请输入姓名")
    @Size(min = 2, max = 20, message = "姓名长度需在 2-20 个字符之间")
    private String realName;

    /**
     * 手机号。**可为空**（居民注册时就不采集手机号，强制必填会挡掉存量用户）。
     * 正则只做「像不像手机号」的粗校验 —— 精确到号段的正则会随运营商放号而过期。
     */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 头像相对 URL。**可为空**（表示清空头像，回落到角色图标）。
     * 前缀白名单在 Service 里断言，见类注释。
     */
    @Size(max = 255, message = "头像地址过长")
    private String avatarUrl;
}
