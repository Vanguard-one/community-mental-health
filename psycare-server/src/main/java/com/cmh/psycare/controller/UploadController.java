package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件上传接口。
 *
 * <h3>为什么只给 banner 一个端点，不做一个通用的 {@code /upload}</h3>
 * <p>通用上传端点意味着<b>任何登录用户都能往服务器写任意文件</b>。
 * 本项目的上传需求只有 banner 图片一处，所以端点直接命名为
 * {@code /upload/banner}，并断言 ADMIN —— 权限与用途在 URL 上就写清楚了。
 * 将来真出现第二个上传场景（如医生头像）时再开一个 {@code /upload/avatar}，
 * 各自带自己的校验，比一个「什么都能传」的端点安全得多。
 *
 * <p>2026-10 补：{@code /upload/avatar} 已按上述约定新增。它<b>不</b>与 banner
 * 共用端点，因为两者的授权模型不同 —— banner 只有管理员能传，
 * 而头像<b>三种角色都要能传自己的</b>。合成一个端点就必须在方法内部
 * 按 URL 参数分叉权限，那是把「安全边界」藏进了 if 里。
 * 各自独立的第二个好处是校验可以各写各的：banner 要卡 2.5:1 比例，
 * 头像不卡（圆形裁切在前端完成，后端只保证是图片、不超大小）。
 *
 * <h3>返回结构为什么是对象而不是纯字符串</h3>
 * <p>返回 {@code {"url": "/api/uploads/banner/..."}} 而不是裸字符串，
 * 是为了给将来留位置（如 {@code width} / {@code height} / {@code size}），
 * 且前端 {@code res.data.url} 的取值方式对以后加字段是兼容的。
 * 裸字符串一旦要加字段就是破坏性变更。
 */
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    /** 上传 banner 图片。返回可直接写入 {@code banner.imageUrl} 的相对 URL */
    @PostMapping("/banner")
    public Result<Map<String, Object>> banner(@RequestParam("file") MultipartFile file) {
        SecurityUtil.requireRole("ADMIN");
        String url = uploadService.saveImage(file, "banner");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("size", file.getSize());
        return Result.ok(out);
    }

    /**
     * 上传头像。**登录即可**，三种角色都能传自己的。
     *
     * <h3>为什么这里不断言角色，但也不是「无授权」</h3>
     * <p>授权信息其实在调用者的 token 里，只是本接口**不落任何用户数据** ——
     * 它只把文件交给存储后端（本地盘的 {@code uploads/avatar/}，或对象存储里
     * {@code avatar/} 前缀下），返回一个 URL。
     * 真正把 URL 写到 {@code sys_user.avatar_url} 的是
     * {@code PUT /profile/me}，那里目标账号由 token 反查，前端无法指定。
     *
     * <h3>为什么上传与实际生效分成两步</h3>
     * <p>这是「先传图拿到 URL、再统一保存表单」的通用形态：用户选完图可以反悔
     * （关掉面板就不保存），若上传即生效，一次误点就会覆盖掉旧头像。
     * 代价是可能留下未被引用的孤儿文件 —— 单机 + 图片才几十 KB，
     * 由后续清理任务处理，比「误操作即不可逆」划算。
     *
     * <h3>为什么返回值里不带 userId</h3>
     * <p>上传本身无需知道是谁 —— 它只产生一个 URL，不落任何用户数据，
     * 因而也没有「谁传的」这一维信息需要记录。要不要把 URL 写进资料，
     * 由后续的 {@code PUT /profile/me} 决定（那里目标账号由 token 反查）。
     * 不在这里记录上传者还有一个好处：将来「注册时先选头像」可以复用本接口
     * （那时用户还没登录、没有 userId）。
     *
     * <h3>但仍显式断言登录态</h3>
     * <p>虽然 JwtAuthFilter 会把 token 解析进上下文，但「必须登录」这件事
     * 若只由全局配置保证，将来调整过滤器放行规则时这里会静默失去保护 ——
     * 变成任何人都能往服务器写文件。所以显式调一次 {@code getUserId()}，
     * 只用它「未登录则抛 401」的副作用，不取返回值。
     */
    @PostMapping("/avatar")
    public Result<Map<String, Object>> avatar(@RequestParam("file") MultipartFile file) {
        // 显式断言登录态：不取 id，只用它「未登录则抛 401」的副作用
        SecurityUtil.getUserId();
        String url = uploadService.saveImage(file, "avatar");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("size", file.getSize());
        return Result.ok(out);
    }
}
