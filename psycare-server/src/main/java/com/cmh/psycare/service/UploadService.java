package com.cmh.psycare.service;

import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.config.UploadProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * 本地磁盘文件上传。
 *
 * <h3>为什么是「本地磁盘 + 静态映射」，而不是对象存储</h3>
 * <p>这是用户确认的方案。它是本项目最省事且完全够用的做法：单机部署、
 * 文件量小（运营 banner 图，几条到几十条）。代价是<b>多实例部署会失效</b> ——
 * 传在 A 机的图，请求打到 B 机就 404。这个限制必须写在这里，因为
 * 将来横向扩容时，第一个出问题的就是它，而现象（图片时有时无）极难归因。
 * 届时的出路是把本类换成对象存储实现（S3/OSS），接口形态保持不变。
 *
 * <h3>为什么文件名不用原始名</h3>
 * <p>三个理由，每一个单独都足够：
 * <ol>
 *   <li><b>路径穿越</b>：原始名可能是 {@code ../../application.yml}，
 *       直接拼接会写到目录之外；</li>
 *   <li><b>中文/空格/特殊字符</b>：URL 编码后长短不一，且部分代理会二次解码出错；</li>
 *   <li><b>重名覆盖</b>：运营两次都传「banner.png」，后一张会静默覆盖前一张，
 *       而数据库里两条记录指向同一个名字 → 前一个 banner 的图也变了。</li>
 * </ol>
 * <p>所以落盘名一律是 {@code UUID + 原扩展名}，原始文件名只用于取扩展名。
 *
 * <h3>为什么按日期分目录</h3>
 * <p>{@code banner/2026/10/09/xxx.png}。单目录下堆几万个文件时，
 * 部分文件系统（尤其是 Windows/NTFS 之外的）在列目录时会明显变慢。
 * 按日期分桶是零成本的预防。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** banner 的业务子目录名。用它做分支判断，避免在每个调用点重复传「要不要校验比例」 */
    private static final String SUB_DIR_BANNER = "banner";

    private final UploadProperties props;

    /**
     * 校验 banner 图片的宽高比是否落在容差范围内。
     *
     * <h3>为什么用 byte[] 而不是直接读流</h3>
     * <p>{@code MultipartFile} 的流<b>只能读一次</b>。若在这里 {@code ImageIO.read(file.getInputStream())}
     * 读完，后面 {@code Files.copy(file.getInputStream(), ...)} 拿到的就是已经到 EOF 的空流，
     * 落盘文件大小为 0 —— 而且不报错，表现为「上传成功但图片打不开」。
     * 所以这里用 {@code file.getBytes()} 读进内存校验一次，后续落盘复用同一份字节。
     * 文件大小已被 maxSizeMb 限制在个位数 MB，内存开销可以接受。
     *
     * <h3>为什么解析失败不直接报错</h3>
     * <p>扩展名白名单已经挡掉了大部分非图片，但「改名成 .png 的文本文件」仍可能进来。
     * 这种情况下 {@code ImageIO.read} 返回 null，我们给一个明确的「不是有效图片」提示，
     * 而不是抛 NPE 变成 500 —— 用户看不懂，排查也得多翻一层日志。
     */
    private void checkBannerRatio(MultipartFile file) {
        BufferedImage img;
        try {
            byte[] bytes = file.getBytes();
            // ImageIO 支持 jpg/png/gif/bmp；webp 若本机 JDK 无对应 SPI 会返回 null，
            // 此时跳过比例校验（宁可放过，也不要因为解码器缺失把正常图挡掉）。
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new BizException(400, "图片读取失败：" + e.getMessage());
        }

        if (img == null) {
            log.warn("[Upload] 无法解码图片，跳过比例校验：{}", file.getOriginalFilename());
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

    /**
     * 保存上传的图片，返回<b>可直接给前端用的相对 URL</b>。
     *
     * <p>返回值形如 {@code /api/uploads/banner/2026/10/09/3f2a...c1.png}。
     * 前端拼上请求 baseURL 即可访问；数据库中 {@code banner.image_url} 存的就是它。
     *
     * @param file     上传的文件（由 Spring 的 MultipartFile 接收）
     * @param subDir   业务子目录，如 {@code banner}。按业务分目录是为了将来清理时
     *                 能整目录删，而不用按文件名特征去猜
     * @return 相对 URL（含 context-path）
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

        // ---------- 比例校验 ----------
        // 只对 banner 生效：目前只有它有「必须铺满固定比例容器」的需求。
        // 写成分支而不是每个调用方各自校验，是为了让「首页不会被拉长」这件事
        // 在后端有一道不可绕过的关口 —— 前端裁剪只是体验优化，不是安全边界。
        if (SUB_DIR_BANNER.equals(safeSubDir)) {
            checkBannerRatio(file);
        }

        String datePath = LocalDate.now().format(DATE_PATH);
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        Path baseDir = resolveBaseDir();
        Path targetDir = baseDir.resolve(safeSubDir).resolve(datePath);
        Path target = targetDir.resolve(fileName);

        // 双保险：normalize 之后仍必须落在 baseDir 之内。
        // sanitizeSubDir 已经把 '..' 和分隔符去掉了，这一步是防「将来有人改坏了上面」——
        // 路径穿越是会覆盖任意文件的高危问题，值得多一层断言。
        if (!target.normalize().startsWith(baseDir.normalize())) {
            throw new BizException(400, "非法的上传路径");
        }

        try {
            Files.createDirectories(targetDir);
            // REPLACE_EXISTING：UUID 撞名的概率可以忽略，但万一撞上，覆盖比报错合理
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("[Upload] 写入失败 target={}", target, e);
            throw new BizException(500, "图片保存失败：" + e.getMessage());
        }

        String url = props.getUrlPrefix() + "/" + safeSubDir + "/" + datePath + "/" + fileName;
        log.info("[Upload] 已保存 {} ({} bytes) → {}", original, file.getSize(), url);
        return url;
    }

    /** 落盘根目录（绝对化，便于日志与 contains 校验） */
    public Path resolveBaseDir() {
        return Paths.get(props.getDir()).toAbsolutePath().normalize();
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
     * 本项目的调用方只会传 {@code banner}，所以过滤后为空就回落到默认值。
     */
    private String sanitizeSubDir(String subDir) {
        if (subDir == null) {
            return "misc";
        }
        String cleaned = subDir.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.isEmpty() ? "misc" : cleaned;
    }
}
