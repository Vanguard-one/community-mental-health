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
     * 存储后端：{@code local}（本地磁盘）或 {@code oss}（阿里云对象存储）。
     *
     * <h3>⚠️ 这个键<b>不要</b>写在 application.yml 里</h3>
     * <p>{@code spring.config.import} 导入的 {@code application-local.yml}
     * <b>优先级低于</b>导入它的 {@code application.yml}。若在 application.yml 里
     * 写上 {@code provider: local}，即使本地文件改成 {@code oss} 也会被盖回去，
     * 表现为「配置改了但完全不生效，日志里还是写本地盘」—— 且没有任何报错。
     * （同一个坑在 api-key 上已经踩过一次。）
     * <p>所以：application.yml 只写注释说明默认值，真实取值一律写
     * {@code application-local.yml}；本字段的 Java 默认值负责兜底。
     */
    private String provider = "local";

    /** 是否走对象存储。判断集中在这里，避免各处散落字符串比较 */
    public boolean isOssEnabled() {
        return "oss".equalsIgnoreCase(provider);
    }

    /**
     * 文件落盘根目录（仅 {@code provider=local} 时生效）。
     * **相对路径按 JVM 工作目录解析**（IDEA 里就是项目目录）。
     *
     * <p>默认 {@code ./data/uploads}：既不进 jar（重新部署不丢），
     * 也在项目目录下（开发时好找）。生产用绝对路径或环境变量覆盖。
     * <p>⚠️ 切到 {@code oss} 后本目录仍然保留并被静态映射 —— 历史图片的 URL
     * 还在库里，删掉目录会让它们集体 404。详见 {@code WebConfig}。
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

    /**
     * 阿里云对象存储配置（{@code psycare.upload.oss.*}），仅 {@code provider=oss} 时使用。
     *
     * <h3>为什么 endpoint / bucket / public-domain 也放在 local 文件里</h3>
     * <p>这三项本身不涉密，但<b>必须与 provider 待在同一个文件里</b>：
     * provider 只能写在 {@code application-local.yml}（见 {@link #getProvider()} 的注释），
     * 若把 endpoint 拆到 application.yml、provider 留在 local，
     * 就会出现「一半配置在 A 文件、一半在 B 文件，改一处忘另一处」的经典事故。
     *
     * <h3>access-key-id / secret 绝不能进 application.yml</h3>
     * <p>本仓库要推到 GitHub，写进去等于公开泄露一把能删空 bucket 的钥匙。
     */
    private Oss oss = new Oss();

    /**
     * 取 oss 段（<b>永不为 null</b>）。
     *
     * <p>手写覆盖 Lombok 生成的 getter：若 {@code application-local.yml} 里把
     * {@code psycare.upload.oss:} 写成空值（后面没有子键），绑定器可能把它设成 null。
     * 而 {@code provider=local} 时没人会去校验 oss 段，调用方（如
     * {@code UploadService#isAvatarUrl}）却会读它 → NPE 落在一条跟 OSS 毫无关系的路径上。
     * 这里兜底成空对象，让「没配」表现为「空串前缀」，自然被忽略。
     */
    public Oss getOss() {
        if (oss == null) {
            oss = new Oss();
        }
        return oss;
    }

    @Data
    public static class Oss {

        /**
         * 地域节点，<b>不带</b>协议前缀。如 {@code oss-cn-hangzhou.aliyuncs.com}。
         *
         * <p>写错地域的典型现象：{@code 403 AccessDenied} 或
         * {@code The bucket you are attempting to access must be addressed using the specified endpoint}。
         * 后者是明确的「endpoint 与 bucket 地域不匹配」，直接照提示里的 endpoint 改。
         */
        private String endpoint;

        /** bucket 名，如 {@code psycare-public} */
        private String bucket;

        /**
         * 对外访问域名（<b>含协议，不带末尾斜杠</b>）。
         *
         * <p>形如 {@code https://psycare-public.oss-cn-hangzhou.aliyuncs.com}。
         * 之所以独立成一个配置而不是由 endpoint + bucket 拼：
         * <ul>
         *   <li>将来挂 CDN / 自定义域名时，只改这一行，<b>已入库的 URL 仍然有效</b>
         *       （数据库里存的是绝对地址）；</li>
         *   <li>内网访问用的 endpoint 与公网访问域名可以是两个东西。</li>
         * </ul>
         *
         * <p>⚠️ 它同时是头像 URL 白名单的前缀（见 {@code UploadService#isAvatarUrl}），
         * 末尾多余的斜杠会被自动规整，不必手写。
         */
        private String publicDomain;

        /** AccessKeyId（建议用 RAM 子账号，只授本 bucket 的 PutObject / GetObject） */
        private String accessKeyId;

        /** AccessKeySecret */
        private String accessKeySecret;

        /**
         * 对象 key 的公共前缀，默认空（直接 {@code banner/...}）。
         *
         * <p>一个 bucket 放多个环境时填 {@code prod} / {@code dev}，
         * 变成 {@code prod/banner/2026/10/21/x.png}。单环境就留空 ——
         * 多加一层没意义的目录只会让 URL 变长。
         */
        private String keyPrefix = "";

        /**
         * 浏览器缓存秒数，默认 7 天（{@code 604800}）。
         *
         * <p>对象名是 UUID，<b>内容与名字一一对应、永不复用</b>
         * （改头像 = 换一个新名字的新对象，旧对象不再被任何记录引用）。
         * 所以可以放心长缓存 —— 不存在「缓存了旧图刷不出来」的问题。
         * 反过来，若给成 0，头像/banner 每次进页面都要回源，纯浪费流量。
         */
        private long cacheMaxAgeSeconds = 604800;

        /** 建连超时（毫秒）。给短值：OSS 不通时不该让上传接口挂几十秒 */
        private int connectionTimeoutMs = 5000;

        /** 读写超时（毫秒）。banner 最大 5 MB，20 秒足够;超过即视为上游异常 */
        private int socketTimeoutMs = 20000;
    }
}
