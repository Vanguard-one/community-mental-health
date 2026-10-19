package com.cmh.psycare.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件上传配置（{@code psycare.upload.*}）。
 *
 * <h3>为什么上传目录必须是配置项而不是写死</h3>
 * <p>「上传的文件放哪」在开发机与服务器上必然不同：
 * <ul>
 *   <li>开发机：项目目录下某个 {@code data/} 就行；</li>
 *   <li>服务器：通常挂一块独立数据盘，或挂在容器外的卷上 ——
 *       如果写死在 {@code src/main/resources/static}，<b>打包后目录只读</b>，
 *       而且每次重新部署（解包新的 jar）都会把上传的文件覆盖掉。</li>
 * </ul>
 * <p>所以默认值刻意选在<b>项目根目录之外的相对位置</b>（{@code ./data/uploads}），
 * 而不是 classpath 下。它不出现在 jar 里，重新打包不影响它。
 *
 * <h3>为什么存盘路径与访问 URL 是两个配置</h3>
 * <p>{@code dir} 是「文件系统上在哪」，{@code urlPrefix} 是「通过什么 URL 能拿到」。
 * 两者是不同的东西：前者是本地路径（可能是 {@code D:/data/uploads}），
 * 后者是 HTTP 路径（{@code /api/uploads}）。虽然大多数情况下它们的末段同名，
 * 但混成一个配置会让「换个磁盘挂载点」变成「改 URL 导致前端全部 404」。
 */
@Data
@Component
@ConfigurationProperties(prefix = "psycare.upload")
public class UploadProperties {

    /**
     * 文件落盘根目录。**相对路径按 JVM 工作目录解析**（IDEA 里就是项目目录）。
     *
     * <p>默认 {@code ./data/uploads}：既不进 jar（重新部署不丢），
     * 也在项目目录下（开发时好找）。生产用绝对路径或环境变量覆盖。
     */
    private String dir = "./data/uploads";

    /**
     * 对外访问前缀（<b>含 context-path</b>）。
     *
     * <p>本项目的 {@code server.servlet.context-path} 是 {@code /api}，
     * 所以静态资源映射注册在 {@code /uploads/**}，对外 URL 实际是
     * {@code /api/uploads/**}。返回给前端的 {@code imageUrl} 必须带上
     * 完整前缀，否则前端拿到 {@code /uploads/x.png} 会打到 vite dev server
     * 而不是后端 —— 504/404 且看不出原因。
     */
    private String urlPrefix = "/api/uploads";

    /**
     * 单个文件大小上限（MB）。
     *
     * <p>与 Spring 的 {@code spring.servlet.multipart.max-file-size} 是<b>两个</b>限制：
     * 那个是容器层（超限直接 413，进不到 Controller），这个是业务层（能给出友好提示）。
     * 业务值应当<b>小于等于</b>容器值，否则用户永远只会看到 413。
     */
    private long maxSizeMb = 5;

    /** 允许的扩展名（小写，不含点）。banner 是运营位，收紧到常见图片格式即可 */
    private String allowedExtensions = "jpg,jpeg,png,gif,webp";

    /**
     * 目标宽高比（宽/高）。默认 {@code 2.5}，对应标准物料尺寸 1200×480。
     *
     * <p>这个值必须与前端 {@code tokens.css} 的 {@code --banner-ratio} 一致 ——
     * 前端负责把图裁剪成这个比例，后端负责在有人<b>绕过前端</b>直接调接口时兜底。
     */
    private double targetRatio = 2.5;

    /**
     * 比例容差（相对误差，{@code 0.1} = 10%）。
     *
     * <h3>为什么是「超差才拒」而不是「必须精确」</h3>
     * <p>运营拿到的物料不可能刚好 1200×480。若严格要求精确比例，运营每次都得
     * 自己先算一遍裁剪，体验极差且会把大量本来可用的图挡在门外。
     * 只要比例在容差内，前端 {@code object-fit: cover} 裁掉的那点边缘肉眼不可见。
     *
     * <p>真正要挡的是<b>量级不对</b>的图：竖图（0.56:1）、长图（6:1）。
     * 10% 的容差足够宽松地放过 2.4:1 ~ 2.6:1，又能挡住一切「一眼就不对」的比例。
     */
    private double ratioTolerance = 0.1;
}
