package com.cmh.psycare.service.storage;

/**
 * 文件存储后端。<b>只负责「把字节写到某处并返回一个可访问的 URL」</b>，
 * 不做任何业务校验（扩展名、大小、宽高比都在 {@code UploadService} 里，与存储无关）。
 *
 * <h3>为什么要有这层接口</h3>
 * <p>同一件事有两套实现：本地磁盘（{@code local}）与阿里云对象存储（{@code oss}）。
 * 它们的<b>校验规则完全相同</b>，只有「最后落在哪」这一步不同。若不抽接口，
 * 校验逻辑要么复制两份（改一处忘一处），要么在一个方法里写两段 if
 * （比例校验、路径穿越断言会跟着存储分支一起被包进 if，很容易在某一支里漏掉）。
 *
 * <h3>实现由 {@code psycare.upload.provider} 条件装配</h3>
 * <p>两个实现上都挂 {@code @ConditionalOnProperty}，同一时刻<b>只有一个</b>会成为 bean，
 * 所以 {@code UploadService} 直接注入本接口即可，不需要 Map&lt;String, FileStorage&gt;。
 * 若两个都没装配上（provider 写了个拼错的值），启动会因「找不到 FileStorage bean」而失败 ——
 * 这是刻意的 fail-fast：静默回落到本地盘会让「图传上去了但生产机器上找不到」这类问题
 * 拖到运维阶段才暴露。
 */
public interface FileStorage {

    /** 后端名，只用于日志（{@code local} / {@code oss}） */
    String name();

    /**
     * 写入一个对象。
     *
     * @param data        文件字节。**由调用方一次性读入**，见 {@code UploadService#saveImage}
     *                    里「为什么先读成 byte[]」的说明 —— MultipartFile 的流不保证可重复读。
     * @param key         对象相对路径，形如 {@code banner/2026/10/21/3f2a...c1.png}。
     *                    已由 {@code UploadService} 白名单化（无 {@code ..}、无绝对路径）。
     * @param contentType MIME 类型，如 {@code image/png}。用于设置响应头，
     *                    决定浏览器是「显示图片」还是「下载文件」。
     * @return 可直接写进数据库、直接给 {@code <img src>} 用的 URL。
     *         {@code local} 返回相对路径（{@code /api/uploads/...}），
     *         {@code oss} 返回绝对地址（{@code https://bucket.oss.../...}）。
     * @throws com.cmh.psycare.common.exception.BizException 存储失败时（统一转成业务错误）
     */
    String store(byte[] data, String key, String contentType);
}
