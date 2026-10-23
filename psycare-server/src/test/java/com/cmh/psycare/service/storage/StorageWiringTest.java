package com.cmh.psycare.service.storage;

import com.cmh.psycare.config.UploadProperties;
import com.cmh.psycare.service.UploadService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储后端的条件装配守卫。
 *
 * <h3>为什么这个测试值得存在</h3>
 * <p>{@link LocalFileStorage} 与 {@link OssFileStorage} 互斥装配，靠的是一个
 * 「{@code provider} 这个键<b>在 application.yml 里不存在</b>」的约定
 * （见 {@code UploadProperties#getProvider()} 的注释：键一旦被写在 application.yml 里，
 * 就会把 {@code application-local.yml} 的取值盖回去）。
 * <p>这个约定<b>没有编译期检查</b>：写错了照样能构建、能启动，
 * 只是「本地文件里改成 oss 却不生效」—— 属于最难查的一类问题。
 * <p>所以这里用 {@link ApplicationContextRunner} 把三种取值形态都钉死：
 * 不写、写 local、写 oss。它是<b>毫秒级</b>的，不连数据库、不起 Web 容器。
 *
 * <h3>oss 用例用的是假密钥</h3>
 * <p>{@code OSSClientBuilder.build()} 只做参数校验与对象构造，<b>不发起网络请求</b>，
 * 所以假 AK 也能通过；这里要验的是「装配到哪个实现」，不是「能不能连上」。
 */
class StorageWiringTest {

    @Configuration
    @EnableConfigurationProperties(UploadProperties.class)
    static class PropsConfig {
    }

    /** 只装载两个实现，不碰 Web / DB / Redis */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropsConfig.class, LocalFileStorage.class, OssFileStorage.class);
    }

    @Test
    void 不配置provider时默认装配本地存储() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(FileStorage.class);
            assertThat(ctx.getBean(FileStorage.class)).isInstanceOf(LocalFileStorage.class);
        });
    }

    @Test
    void provider为local时装配本地存储() {
        runner()
                .withPropertyValues("psycare.upload.provider=local")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(FileStorage.class)).isInstanceOf(LocalFileStorage.class);
                });
    }

    @Test
    void provider为oss且配置齐全时装配OSS存储() {
        runner()
                .withPropertyValues(
                        "psycare.upload.provider=oss",
                        "psycare.upload.oss.endpoint=oss-cn-hangzhou.aliyuncs.com",
                        "psycare.upload.oss.bucket=unit-test-bucket",
                        "psycare.upload.oss.public-domain=https://unit-test-bucket.oss-cn-hangzhou.aliyuncs.com",
                        "psycare.upload.oss.access-key-id=LTAI_FAKE_FOR_TEST",
                        "psycare.upload.oss.access-key-secret=FAKE_SECRET_FOR_TEST")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(FileStorage.class);
                    assertThat(ctx.getBean(FileStorage.class)).isInstanceOf(OssFileStorage.class);
                });
    }

    /**
     * 缺密钥时必须**启动失败**，而不是静默回落到本地盘。
     * <p>静默回落的后果是「上传成功、图片在服务器本地、库里存着云端地址」，
     * 运维在 bucket 里怎么也找不到那张图。
     */
    @Test
    void provider为oss但缺密钥时启动失败() {
        runner()
                .withPropertyValues(
                        "psycare.upload.provider=oss",
                        "psycare.upload.oss.endpoint=oss-cn-hangzhou.aliyuncs.com",
                        "psycare.upload.oss.bucket=unit-test-bucket",
                        "psycare.upload.oss.public-domain=https://unit-test-bucket.oss-cn-hangzhou.aliyuncs.com")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    /**
     * public-domain 缺协议必须启动失败。
     * <p>否则生成的 URL 没有 scheme，浏览器当成相对路径拼到当前页面地址后面，
     * 表现为「所有头像/ banner 裂图」，而库里存的值看起来完全正常。
     */
    @Test
    void publicDomain缺少协议时启动失败() {
        runner()
                .withPropertyValues(
                        "psycare.upload.provider=oss",
                        "psycare.upload.oss.endpoint=oss-cn-hangzhou.aliyuncs.com",
                        "psycare.upload.oss.bucket=unit-test-bucket",
                        "psycare.upload.oss.public-domain=unit-test-bucket.oss-cn-hangzhou.aliyuncs.com",
                        "psycare.upload.oss.access-key-id=LTAI_FAKE_FOR_TEST",
                        "psycare.upload.oss.access-key-secret=FAKE_SECRET_FOR_TEST")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    /**
     * 头像白名单要**同时**认本地与 OSS 两个前缀（灰度期两种数据共存），
     * 且必须挡住外站地址（否则是追踪像素）、挡住 banner 目录。
     */
    @Test
    void 头像白名单同时认可本地与OSS前缀() {
        runner()
                .withPropertyValues(
                        "psycare.upload.url-prefix=/api/uploads",
                        "psycare.upload.oss.public-domain=https://unit-test-bucket.oss-cn-hangzhou.aliyuncs.com")
                .run(ctx -> {
                    // provider 仍是 local，但白名单不依赖 provider —— 它要接住历史数据
                    assertThat(ctx.getBean(UploadProperties.class).isOssEnabled()).isFalse();

                    UploadService svc = new UploadService(
                            ctx.getBean(UploadProperties.class), ctx.getBean(FileStorage.class));

                    // 两种前缀都要过
                    assertThat(svc.isAvatarUrl("/api/uploads/avatar/2026/10/21/a.jpg")).isTrue();
                    assertThat(svc.isAvatarUrl(
                            "https://unit-test-bucket.oss-cn-hangzhou.aliyuncs.com/avatar/2026/10/21/a.jpg")).isTrue();

                    // 外站、banner 目录、空值都要挡
                    assertThat(svc.isAvatarUrl("https://evil.com/avatar/a.jpg")).isFalse();
                    assertThat(svc.isAvatarUrl(
                            "https://unit-test-bucket.oss-cn-hangzhou.aliyuncs.com/banner/a.jpg")).isFalse();
                    // 同名域的仿冒域名（前缀不带尾随斜杠时会误判为通过）
                    assertThat(svc.isAvatarUrl(
                            "https://unit-test-bucket.oss-cn-hangzhou.aliyuncs.com.evil.com/avatar/a.jpg")).isFalse();
                    assertThat(svc.isAvatarUrl(null)).isFalse();
                    assertThat(svc.isAvatarUrl("")).isFalse();
                });
    }
}
