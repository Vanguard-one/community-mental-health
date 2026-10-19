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
}
