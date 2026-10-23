package com.cmh.psycare.service.storage;

import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.config.UploadProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地磁盘存储（{@code psycare.upload.provider=local}，<b>默认</b>）。
 *
 * <h3>为什么默认仍然是它</h3>
 * <p>「clone 下来就能跑」的价值很高：新人不需要先申请一把云账号密钥、建一个 bucket、
 * 配好跨域，才能把项目跑起来看效果。对象存储是需要<b>外部账号</b>的依赖，
 * 不能作为默认值 —— 否则每个开发者本地都会因为缺密钥而无法启动。
 *
 * <h3>它的硬限制（写在这里，因为将来第一个撞上的就是它）</h3>
 * <p>文件落在<b>单台机器</b>的磁盘上：传在 A 机的图，请求打到 B 机就 404。
 * 单机部署完全够用；一旦横向扩容或多环境共享数据，必须切 {@code oss}。
 * 切换只改配置（{@code provider: oss}），不需要动任何业务代码 —— 这正是抽
 * {@link FileStorage} 的目的。
 *
 * <h3>切到 oss 之后这个类仍然要保留装配能力</h3>
 * <p>历史图片的 URL（{@code /api/uploads/banner/...}）还留在数据库里，
 * 而 {@code WebConfig} 的静态映射与 provider 无关、始终生效。
 * 所以本地文件不能删 —— 删了那些历史记录就变成一张张破图。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "psycare.upload.provider", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private final UploadProperties props;

    @Override
    public String name() {
        return "local";
    }

    @Override
    public String store(byte[] data, String key, String contentType) {
        Path baseDir = resolveBaseDir();
        Path target = baseDir.resolve(key).normalize();

        // 双保险：key 已由 UploadService 白名单化（去掉 '..' 与分隔符），
        // 这一步防「将来有人改坏了上游」—— 路径穿越是能覆盖任意文件的高危问题，
        // 值得在上限逻辑里再断言一次。
        if (!target.startsWith(baseDir)) {
            throw new BizException(400, "非法的上传路径");
        }

        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Files.write 默认 CREATE + TRUNCATE_EXISTING：UUID 撞名的概率可以忽略，
            // 但万一撞上，覆盖比报错合理。
            Files.write(target, data);
        } catch (IOException e) {
            log.error("[Upload:local] 写入失败 target={}", target, e);
            throw new BizException(500, "图片保存失败：" + e.getMessage());
        }

        String prefix = props.getUrlPrefix();
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/" + key;
    }

    /** 落盘根目录（绝对化，便于日志与 startsWith 校验） */
    public Path resolveBaseDir() {
        String dir = props.getDir();
        if (dir == null || dir.isBlank()) {
            dir = "./data/uploads";
        }
        return Paths.get(dir).toAbsolutePath().normalize();
    }
}
