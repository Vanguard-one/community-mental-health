package com.cmh.psycare.service;

import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.config.UploadProperties;
import com.cmh.psycare.service.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * 图片上传：<b>校验 + 生成对象名</b>，落盘/上云交给 {@link FileStorage}。
 *
 * <h3>本类与 FileStorage 的分工</h3>
 * <p>本类只做「能不能存」和「存成什么名字」，{@link FileStorage} 只做「存到哪」。
 * 这条分界线的价值在于：所有校验（扩展名、大小、banner 宽高比）都是<b>存储无关</b>的，
 * 它们必须在 {@code local} 与 {@code oss} 两种模式下<b>完全一致</b>。
 * 把校验写进存储实现里，切换 provider 就等于悄悄换掉了安全边界。
 *
 * <h3>为什么文件名不用原始名</h3>
 * <p>三个理由，每一个单独都足够：
 * <ol>
 *   <li><b>路径穿越</b>：原始名可能是 {@code ../../application.yml}，
 *       直接拼接会写到目录之外（对象存储里则是写到意料之外的 key）；</li>
 *   <li><b>中文/空格/特殊字符</b>：URL 编码后长短不一，且部分代理会二次解码出错；</li>
 *   <li><b>重名覆盖</b>：运营两次都传「banner.png」，后一张会静默覆盖前一张，
 *       而数据库里两条记录指向同一个名字 → 前一个 banner 的图也变了。</li>
 * </ol>
 * <p>所以对象名一律是 {@code UUID + 原扩展名}，原始文件名只用于取扩展名。
 * <p>附带的好处：名字与内容<b>一一对应、永不复用</b>，所以 CDN/浏览器缓存可以给长周期
 * （改头像 = 换一个新名字的新对象），不存在「缓存了旧图刷不出来」。
 *
 * <h3>为什么按日期分目录</h3>
 * <p>{@code banner/2026/10/09/xxx.png}。单目录下堆几万个文件时，
 * 部分文件系统（尤其是 Windows/NTFS 之外的）在列目录时会明显变慢；
 * 对象存储的「目录」是 key 前缀，同样受益于按前缀分桶（控制台翻页、按日期批量清理）。
 * 按日期分桶是零成本的预防。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** banner 的业务子目录名。用它做分支判断，避免在每个调用点重复传「要不要校验比例」 */
    private static final String SUB_DIR_BANNER = "banner";

    /** 头像的业务子目录名。只有它会被 {@link #isAvatarUrl} 认可 */
    private static final String SUB_DIR_AVATAR = "avatar";

    private final UploadProperties props;
    private final FileStorage storage;

    /**
     * 保存上传的图片，返回<b>可直接给前端用的 URL</b>。
     *
     * <p>{@code local} 模式下形如 {@code /api/uploads/banner/2026/10/09/3f2a...c1.png}；
     * {@code oss} 模式下是绝对地址 {@code https://bucket.oss.../banner/...}。
     * 前端两种都能直接塞进 {@code <img src>}（浏览器对相对/绝对地址一视同仁），
     * 所以切换存储不需要改前端任何一行。
     *
     * @param file   上传的文件（由 Spring 的 MultipartFile 接收）
     * @param subDir 业务子目录，如 {@code banner} / {@code avatar}。按业务分目录是为了
     *               将来清理时能整目录删，而不用按文件名特征去猜
     * @return 可写入数据库的 URL
     */
    public String saveImage(MultipartFile file, String subDir) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要上传的图片");
        }

        String original = file.getOriginalFilename();
        String ext = extensionOf(original);
        if (!isAllowed(ext)) {
            throw new BizException(400, "只支持 " + props.getAllowedExtensions() + " 格式的图片");
        }

        // 大小校验放在扩展名之后：先挡掉明显不对的文件，再做（可能较慢的）大小判断。
        long maxBytes = props.getMaxSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new BizException(400, "图片不能超过 " + props.getMaxSizeMb() + " MB");
        }

        String safeSubDir = sanitizeSubDir(subDir);

        // ---------- 一次性读入字节 ----------
        // MultipartFile 的流**不保证可重复读**：若在比例校验里读一次、落盘时再读一次，
        // 第二次拿到的可能是空流 → 落盘文件 0 字节，且不报错，
        // 表现为「上传成功但图片打不开」。
        // 所以统一在这里读一次，校验与存储都用同一份字节 ——
        // 这也保证了「校验的那份内容」和「真正存下去的内容」绝不会是两个东西。
        // 大小已限制在 maxSizeMb（默认 5 MB）以内，内存开销可以接受。
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(400, "图片读取失败：" + e.getMessage());
        }

        // ---------- 比例校验 ----------
        // 只对 banner 生效：目前只有它有「必须铺满固定比例容器」的需求。
        // 写成分支而不是每个调用方各自校验，是为了让「首页不会被拉长」这件事
        // 在后端有一道不可绕过的关口 —— 前端裁剪只是体验优化，不是安全边界。
        if (SUB_DIR_BANNER.equals(safeSubDir)) {
            checkBannerRatio(bytes, original);
        }

        String key = safeSubDir + "/" + LocalDate.now().format(DATE_PATH) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + ext;

        String url = storage.store(bytes, key, contentTypeOf(ext));
        log.info("[Upload:{}] 已保存 {} ({} bytes) → {}", storage.name(), original, bytes.length, url);
        return url;
    }

    /**
     * 这个 URL 是否是本系统上传接口产出的<b>头像</b>。
     *
     * <h3>为什么要白名单而不是「不校验」</h3>
     * <p>{@code avatar_url} 会被写进 {@code <img src>}，直接放行任意字符串是两个问题：
     * 一是<b>追踪像素</b>（填一个外站地址，所有看他资料的人都向该站发一次请求，
     * 泄露 IP 与访问时间）；二是<b>潜在的 XSS 落点</b>（历史浏览器对 {@code javascript:}
     * 之类的伪协议处理不一致）。写进库的 URL 必须可追溯到自己。
     *
     * <h3>为什么同时认「本地」与「OSS」两个前缀</h3>
     * <p>切到 OSS 之后，本地盘上那些历史头像的 URL 仍然留在库里；
     * 反过来从 OSS 退回 local 时，云上的历史 URL 也要继续有效。
     * 只认当前 provider 一个前缀，会让「改一个配置」变成「一半人的头像保存时报不合法」。
     * <p>⚠️ 前缀匹配必须<b>带尾随斜杠</b>：{@code https://b.oss-cn-hangzhou.aliyuncs.com}
     * 若不带到 {@code /}，{@code https://b.oss-cn-hangzhou.aliyuncs.com.evil.com/x.png}
     * 恰好也能通过 startsWith。
     */
    public boolean isAvatarUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        for (String prefix : knownUrlPrefixes()) {
            if (url.startsWith(prefix + "/" + SUB_DIR_AVATAR + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本系统图片 URL 的已知前缀集合（不带尾随斜杠）。
     *
     * <p>三个来源，都要认：
     * <ol>
     *   <li>{@code urlPrefix}（{@code /api/uploads}）—— 本地模式当前值 + 历史数据；</li>
     *   <li>{@code oss.public-domain} —— OSS 模式当前值；</li>
     *   <li>{@code oss.public-domain + "/" + oss.key-prefix} —— 配了 key-prefix 时对象名多一层。</li>
     * </ol>
     */
    private List<String> knownUrlPrefixes() {
        List<String> out = new ArrayList<>(3);

        String local = normalizePrefix(props.getUrlPrefix());
        if (!local.isEmpty()) {
            out.add(local);
        }

        UploadProperties.Oss oss = props.getOss();
        String domain = normalizePrefix(oss.getPublicDomain());
        if (!domain.isEmpty()) {
            out.add(domain);
            String prefixed = normalizePrefix(domain + "/" + oss.getKeyPrefix());
            if (!prefixed.equals(domain)) {
                out.add(prefixed);
            }
        }
        return out;
    }

    /** 去掉首尾空白与末尾斜杠；null 归一为空串 */
    private String normalizePrefix(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 校验 banner 图片的宽高比是否落在容差范围内。
     *
     * <h3>为什么解析失败不直接报错</h3>
     * <p>扩展名白名单已经挡掉了大部分非图片，但「改名成 .png 的文本文件」仍可能进来。
     * 这种情况下 {@code ImageIO.read} 返回 null，我们给一个明确的「不是有效图片」提示，
     * 而不是抛 NPE 变成 500 —— 用户看不懂，排查也得多翻一层日志。
     * <p>webp 在两个 JDK 上都可能没有内置 SPI，此时同样跳过比例校验：
     * 宁可放过，也不要因为解码器缺失把正常图挡掉。
     */
    private void checkBannerRatio(byte[] bytes, String originalName) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new BizException(400, "图片读取失败：" + e.getMessage());
        }

        if (img == null) {
            log.warn("[Upload] 无法解码图片，跳过比例校验：{}", originalName);
            return;
        }

        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) {
            log.warn("[Upload] 图片尺寸异常 {}x{}，跳过比例校验", w, h);
            return;
        }

        double actual = (double) w / h;
        double target = props.getTargetRatio();
        // 相对误差：|实际 - 目标| / 目标。比绝对差更能表达「差了多少个百分比」
        double diff = Math.abs(actual - target) / target;

        if (diff > props.getRatioTolerance()) {
            throw new BizException(400, String.format(
                    "图片比例不合适：当前 %.2f:1（%d×%d），需要 %.2f:1（如 1200×480）。"
                            + "请使用管理端的裁剪功能调整后再上传",
                    actual, w, h, target));
        }
    }

    /** 扩展名 → MIME。设置到对象/响应头上，决定浏览器「显示」还是「下载」 */
    private String contentTypeOf(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            // 正常走不到（扩展名已过白名单），留个兜底避免返回 null
            default -> "application/octet-stream";
        };
    }

    /**
     * 取扩展名（小写，不含点）。
     *
     * <p>用 {@code lastIndexOf('.')} 而不是 {@code substringAfterLast} 之类的工具，
     * 是为了显式处理「没有点」与「点在最末尾」两种边界 —— 前者返回空串（会被
     * isAllowed 挡掉），后者也返回空串（同理），而不是抛异常。
     */
    private String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isAllowed(String ext) {
        if (ext.isEmpty()) {
            return false;
        }
        for (String a : props.getAllowedExtensions().split(",")) {
            if (a.trim().toLowerCase(Locale.ROOT).equals(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 子目录白名单化：只保留字母数字与连字符，其余一律去掉。
     *
     * <p>不用「过滤 '..'」这种黑名单写法 —— 那样 {@code ....//} 之类的变体
     * 需要逐个想全。白名单是「只允许已知安全的字符」，漏不掉。
     * 本项目的调用方只会传 {@code banner} / {@code avatar}，所以过滤后为空就回落到默认值。
     */
    private String sanitizeSubDir(String subDir) {
        if (subDir == null) {
            return "misc";
        }
        String cleaned = subDir.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.isEmpty() ? "misc" : cleaned;
    }
}
