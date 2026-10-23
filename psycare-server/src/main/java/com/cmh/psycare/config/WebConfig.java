package com.cmh.psycare.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web 配置：CORS 跨域 + 注册 JWT 过滤器（放行登录/注册/文档）+ 上传文件静态映射
 *
 * <h3>为什么本类实现了 {@link WebMvcConfigurer}</h3>
 * <p>upload 功能需要把上传目录暴露成 HTTP 可访问的资源。Spring Boot 默认只把
 * classpath 下的 {@code static/} 映射到 {@code /}，而上传目录在<b>磁盘上</b>
 * （见 {@link UploadProperties#getDir()}），必须显式注册一个
 * {@link ResourceHandlerRegistry} 规则。
 *
 * <h3>为什么上传目录必须在启动时创建</h3>
 * <p>Spring 注册静态资源映射时<b>不校验目录是否存在</b>，目录不存在也只是访问时 404。
 * 但 {@code FileSystemResource} 的路径在注册那一刻就被解析了；如果目录是后来
 * 才由上传逻辑创建的，在部分平台上（尤其是 Windows）会因规范路径变化而读不到。
 * 所以这里在注册前先 {@code createDirectories}，代价是启动时一次 IO。
 *
 * <h3>为什么切到对象存储（{@code psycare.upload.provider=oss}）后这里照旧不动</h3>
 * <p>因为<b>历史数据的 URL 还在库里</b>：{@code banner.image_url} 与
 * {@code sys_user.avatar_url} 里存着一批 {@code /api/uploads/...} 的相对路径。
 * 切换 provider 只影响「新上传的文件去哪」，不影响「已存在的 URL 怎么被访问」。
 * 若在 oss 模式下把这条映射删掉，那些历史图片会集体 404，而数据库看起来一切正常。
 * <p>等到确认所有历史 URL 都已迁移到云端（一条 SQL 就能查出来），这段映射才可以退役。
 * 在此之前它必须一直在 —— 它是灰度期的回退通道。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtAuthFilter jwtAuthFilter;
    private final UploadProperties uploadProperties;

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilter() {
        FilterRegistrationBean<JwtAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(jwtAuthFilter);
        bean.addUrlPatterns("/*");
        bean.setOrder(2);
        return bean;
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /**
     * 上传目录 → {@code /uploads/**} 的静态映射。
     *
     * <p>注意路径写法：本项目的 {@code context-path} 是 {@code /api}，
     * 所以这里的 pattern 写 {@code /uploads/**}（<b>不含</b> context-path），
     * 对外实际 URL 是 {@code /api/uploads/**}。这两处不一致是最容易踩的坑：
     * 把 {@code /api/uploads/**} 写进 pattern，结果变成 {@code /api/api/uploads/**}，
     * 表现是所有图片 404 而后端日志里连请求都看不到（DispatcherServlet 没匹配上）。
     *
     * <p>末尾必须带 {@code /}：{@code file:/D:/data/uploads/} 与
     * {@code file:/D:/data/uploads}（无斜杠）在解析相对资源时行为不同，前者才是目录。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path baseDir = resolveBaseDir();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            // 创建失败不阻断启动：可能是权限问题，此时上传功能不可用但其余功能照常。
            // 明确打成 ERROR 并给出路径，比对着一堆 404 猜要直接得多。
            log.error("[Upload] 无法创建上传目录 {}，图片上传将不可用", baseDir.toAbsolutePath(), e);
        }

        String location = baseDir.toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }

        // {*path} 而不是 **：Spring 的 PathResourceResolver 用前者才能把带子目录的
        // 相对路径完整取出来（banner/2026/10/09/x.png）。用 ** 在多层子目录下会失配。
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
        log.info("[Upload] 静态映射 /uploads/** → {}", location);
    }

    /** 上传根目录；配置为空时回落到默认值（与 {@link UploadProperties#getDir()} 的默认值一致） */
    private Path resolveBaseDir() {
        String dir = uploadProperties.getDir();
        if (dir == null || dir.isBlank()) {
            dir = "./data/uploads";
        }
        return Paths.get(dir);
    }
}
