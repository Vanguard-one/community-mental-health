package com.cmh.psycare.service.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.config.UploadProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * 阿里云对象存储（{@code psycare.upload.provider=oss}）。
 *
 * <h3>为什么是「后端代传」而不是「前端直传 / 预签名 URL」</h3>
 * <p>直传确实能省掉一次中转流量，但它会把<b>三道校验全部废掉</b>：
 * banner 的 2.5:1 宽高比、5 MB 上限、扩展名白名单，都是在服务端做的；
 * 前端直传意味着客户端可以绕过它们，往 bucket 里塞任意大小任意格式的文件。
 * <p>而且直传要把 AccessKey 或 STS 临时凭证发到浏览器 —— 一旦泄露，
 * bucket 就不再属于我们。本项目的图片量级（banner 几条、头像几十条、
 * 单张几百 KB）根本不值得用这点流量去换上述风险，所以走服务端中转。
 *
 * <h3>为什么 OSS 客户端是单例而不是每次 new</h3>
 * <p>{@link OSS} 实例内部持有 HTTP 连接池，官方明确说明它是<b>线程安全</b>的、
 * 应当复用。每次上传 new 一个再 shutdown 会导致：连接无法复用（每次上传多一次
 * TCP + TLS 握手，跨境/跨地域时尤其明显）、以及忘记 shutdown 时的连接泄漏。
 * <p>它是 Spring 单例 bean，生命周期由容器管理，{@link PreDestroy} 里统一关闭。
 *
 * <h3>为什么配置不全要让启动直接失败</h3>
 * <p>{@code provider=oss} 但少了 bucket 或密钥，是一个<b>必然坏掉</b>的状态：
 * 每一次上传都会失败。若降级回本地盘「假装成功」，图片会存在本机而数据库里
 * 记着云地址，运维怎么也找不到。所以这里在 {@link PostConstruct} 里逐项校验，
 * 缺什么就在启动日志里点名 —— 这比上线后让运营一张张传失败要便宜得多。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "psycare.upload.provider", havingValue = "oss")
public class OssFileStorage implements FileStorage {

    private final UploadProperties props;

    private OSS client;

    /** 规整后的对外域名（无末尾斜杠），如 {@code https://b.oss-cn-hangzhou.aliyuncs.com} */
    private String publicDomain;

    /** 规整后的 key 前缀（无末尾斜杠），可能为空串 */
    private String keyPrefix = "";

    @PostConstruct
    void init() {
        UploadProperties.Oss cfg = props.getOss();

        String endpoint = require(cfg.getEndpoint(), "psycare.upload.oss.endpoint");
        String bucket = require(cfg.getBucket(), "psycare.upload.oss.bucket");
        String ak = require(cfg.getAccessKeyId(), "psycare.upload.oss.access-key-id");
        String sk = require(cfg.getAccessKeySecret(), "psycare.upload.oss.access-key-secret");
        String domain = require(cfg.getPublicDomain(), "psycare.upload.oss.public-domain");

        // endpoint 必须是「裸域名」：SDK 内部会自己拼 https://。
        // 写成 https://xxx 时部分 SDK 版本能容忍、部分会拼成 https://https://xxx，
        // 报错信息完全看不出根因，所以这里直接剥掉并提示。
        endpoint = endpoint.trim();
        String lower = endpoint.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            endpoint = endpoint.substring(endpoint.indexOf("://") + 3);
            log.warn("[Upload:oss] endpoint 不应带协议前缀，已自动剥离为 {}", endpoint);
        }
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }

        // public-domain 反过来：必须带协议，否则生成的 URL 没有 scheme，
        // 浏览器会把它当成相对路径拼到当前页面地址后面 → 头像/banner 全部裂图，
        // 而库里存的值看起来完全正常。所以这里是硬校验而不是自动补 https。
        domain = domain.trim();
        String dLower = domain.toLowerCase(Locale.ROOT);
        if (!dLower.startsWith("https://") && !dLower.startsWith("http://")) {
            throw new IllegalStateException(
                    "psycare.upload.oss.public-domain 必须带协议前缀（如 https://"
                            + domain + "），当前值：" + domain);
        }
        while (domain.endsWith("/")) {
            domain = domain.substring(0, domain.length() - 1);
        }

        String prefix = cfg.getKeyPrefix() == null ? "" : cfg.getKeyPrefix().trim();
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        ClientBuilderConfiguration cc = new ClientBuilderConfiguration();
        cc.setConnectionTimeout(cfg.getConnectionTimeoutMs());
        cc.setSocketTimeout(cfg.getSocketTimeoutMs());

        this.client = new OSSClientBuilder().build(endpoint, ak, sk, cc);
        this.publicDomain = domain;
        this.keyPrefix = prefix;

        // 密钥绝不进日志，只打「配没配 + 末尾四位」便于确认用的是哪一把，
        // 打全串等于把钥匙抄在了日志文件里。
        log.info("[Upload:oss] 已启用。endpoint={} bucket={} domain={} keyPrefix={} ak=***{}",
                endpoint, bucket, domain,
                prefix.isEmpty() ? "(无)" : prefix,
                ak.length() > 4 ? ak.substring(ak.length() - 4) : "****");
    }

    @PreDestroy
    void destroy() {
        if (client != null) {
            client.shutdown();
            log.info("[Upload:oss] 客户端已关闭");
        }
    }

    @Override
    public String name() {
        return "oss";
    }

    @Override
    public String store(byte[] data, String key, String contentType) {
        String fullKey = keyPrefix.isEmpty() ? key : keyPrefix + "/" + key;
        UploadProperties.Oss cfg = props.getOss();

        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(data.length);
        // Content-Type 决定浏览器「显示」还是「下载」。不设的话 OSS 会回
        // application/octet-stream，<img> 依然能靠内容嗅探显示出来，
        // 但「在新标签页打开图片」会变成下载，体验差别很直观。
        meta.setContentType(contentType);
        // 对象名是 UUID、内容与名字一一对应、永不复用 → 长缓存不会读到旧图
        meta.setCacheControl("public, max-age=" + cfg.getCacheMaxAgeSeconds());

        try {
            PutObjectRequest req = new PutObjectRequest(
                    cfg.getBucket(), fullKey, new ByteArrayInputStream(data), meta);
            client.putObject(req);
        } catch (OSSException e) {
            // 服务端明确拒绝：NoSuchBucket / AccessDenied / InvalidAccessKeyId 都在这里。
            // errorCode 是唯一的排查入口，必须打出来 —— 否则只剩一句 "OSS failed"。
            log.error("[Upload:oss] 上传被拒绝 key={} errorCode={} requestId={} message={}",
                    fullKey, e.getErrorCode(), e.getRequestId(), e.getErrorMessage());
            throw new BizException(500, "图片上传失败（" + friendly(e.getErrorCode()) + "），请联系管理员");
        } catch (ClientException e) {
            // 网络不通、超时、DNS 失败等客户端侧问题
            log.error("[Upload:oss] 上传异常 key={}", fullKey, e);
            throw new BizException(500, "图片存储服务暂时不可用，请稍后重试");
        }

        String url = publicDomain + "/" + fullKey;
        log.info("[Upload:oss] 已保存 key={} ({} bytes) → {}", fullKey, data.length, url);
        return url;
    }

    /**
     * 把 OSS 的错误码翻译成一句人能看懂的话。
     * <p>原文（如 {@code InvalidAccessKeyId}）直接甩给运营等于没说；
     * 但错误码本身要留在日志里，所以这里只改<b>给用户看</b>的那一层。
     */
    private String friendly(String errorCode) {
        if (errorCode == null) {
            return "存储服务返回未知错误";
        }
        return switch (errorCode) {
            case "AccessDenied" -> "存储权限不足";
            case "InvalidAccessKeyId" -> "存储密钥无效";
            case "SignatureDoesNotMatch" -> "存储密钥配置有误";
            case "NoSuchBucket" -> "存储空间不存在";
            case "RequestTimeTooSkewed" -> "服务器时间与云端不一致";
            default -> errorCode;
        };
    }

    /** 必填项校验：空值直接让启动失败，并在消息里点名是哪个配置项 */
    private String require(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    "已启用 OSS 存储（psycare.upload.provider=oss），但缺少配置 " + name);
        }
        return value.trim();
    }
}
