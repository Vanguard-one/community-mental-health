package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.ChangePasswordRequest;
import com.cmh.psycare.dto.ProfileUpdateRequest;
import com.cmh.psycare.dto.ProfileVO;
import com.cmh.psycare.entity.SysUser;
import com.cmh.psycare.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 个人资料：当前登录者改自己的账号信息。
 *
 * <h3>本类与 {@code AccountService} 的边界</h3>
 * <p>两者都改 {@code sys_user}，但**主体不同**，绝不能合并：
 * <table border="1">
 *   <tr><th></th><th>本类（ProfileService）</th><th>AccountService</th></tr>
 *   <tr><td>操作者</td><td>账号主人自己</td><td>管理员</td></tr>
 *   <tr><td>目标</td><td>只能是当前 token 对应的那个人</td><td>任意人，且跨三种角色</td></tr>
 *   <tr><td>权限</td><td>登录即有权（隐含「仅限自己」）</td><td>必须 {@code requireRole("ADMIN")}</td></tr>
 *   <tr><td>可改字段</td><td>资料四字段 + 密码（需旧密码）</td><td>状态、角色、审核、重置密码</td></tr>
 * </table>
 * <p>合并的直接后果是「自己改资料」这条路会带上管理员的全部能力 ——
 * 这是权限提升类漏洞最常见的形态。
 *
 * <h3>所有方法都不接收 id</h3>
 * <p>目标账号一律由 {@link SecurityUtil#getUserId()} 决定。前端传什么 id 都不看，
 * 结构上就不存在改别人资料的可能。对应「硬约定 #18」那条的镜像：
 * 前端隐藏入口不算安全边界，能信任的只有服务端从 token 反查出来的身份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final UploadService uploadService;

    // ============================================================
    // 读
    // ============================================================

    /**
     * 当前登录者的资料。
     * <p>
     * 账号查不到有两种可能：被管理员删了（逻辑删除），或被物理删了。
     * 两种情况都按「登录态已失效」处理 —— 让前端跳回登录页，
     * 比返回一个「用户不存在」的 400 更符合用户视角（他确实是登不进来了）。
     */
    public ProfileVO getMine() {
        SysUser account = loadMine();
        return toVO(account);
    }

    // ============================================================
    // 改资料
    // ============================================================

    /**
     * 更新资料四字段。
     *
     * <h3>为什么用 UpdateWrapper 而不是 updateById</h3>
     * <p>MyBatis-Plus 的 {@code updateById} 默认是 <b>NOT_NULL 策略</b>：
     * 实体里为 null 的字段不会进 SET 子句。而本接口恰恰需要「把手机号清空」
     * 和「清除头像」这两种**写入 null** 的语义 —— 用 updateById 会静默不生效，
     * 用户点了保存、提示成功、刷新后又变回来，且日志里一切正常。
     * <p>所以这里显式 {@code .set(列, 值)}，值就是最终值（空串已归一为 null）。
     */
    public ProfileVO updateMine(ProfileUpdateRequest req) {
        SysUser account = loadMine();
        Long meId = account.getId();

        String username = req.getUsername().trim();

        // ---------- 账号唯一性 ----------
        // 数据库的唯一索引是「仅未删除行」的函数索引，能兜底；但直接撞索引
        // 抛出来的 DuplicateKeyException 文案是英文 SQL 错误，用户看不懂，
        // 所以这里先查一次给出人话提示。索引仍然是最后一道防线
        // （并发下两个请求可能同时通过这里）。
        if (!username.equals(account.getUsername())) {
            Long taken = sysUserMapper.selectCount(
                    new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getUsername, username)
                            .ne(SysUser::getId, meId));
            if (taken != null && taken > 0) {
                throw new BizException(400, "该账号已被占用，请换一个");
            }
        }

        // ---------- 头像前缀白名单 ----------
        // avatarUrl 为空 = 恢复默认头像（前端 RoleAvatar 内置的角色图标），是合法值。
        String avatarUrl = blankToNull(req.getAvatarUrl());
        if (avatarUrl != null && !uploadService.isAvatarUrl(avatarUrl)) {
            // 见 ProfileUpdateRequest 类注释：不做限制的话这是个追踪像素 +
            // 潜在的 XSS 落点。只认本系统上传接口产出的路径形态。
            //
            // 【为什么把判断委托给 UploadService 而不是在这里拼前缀】
            // 切换存储后端（local ↔ OSS）会改变 URL 形态，而这个校验必须
            // 同时认可「当前后端」与「历史数据的旧前缀」—— 这段知识属于上传模块，
            // 写在这里就会变成第二份需要同步维护的真相。
            throw new BizException(400, "头像地址不合法，请通过上传功能设置头像");
        }

        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, meId)
                .set(SysUser::getUsername, username)
                .set(SysUser::getRealName, req.getRealName().trim())
                // 空串归一为 NULL：「没填手机号」在库里应当只有一个表示
                .set(SysUser::getPhone, blankToNull(req.getPhone()))
                .set(SysUser::getAvatarUrl, avatarUrl)
                .set(SysUser::getUpdatedAt, LocalDateTime.now()));

        log.info("[个人资料] 已更新 userId={} 账号 {} → {}，头像={}",
                meId, account.getUsername(), username, avatarUrl == null ? "未设置" : "已设置");

        // 回查而不是就地拼 VO：以库里实际落盘值为准。
        // 「保存成功但没存进去」这类问题，就地拼装会把它盖住。
        return toVO(loadMine());
    }

    // ============================================================
    // 改密码
    // ============================================================

    /**
     * 修改当前登录者的密码。需校验旧密码。
     *
     * <h3>改完后当前 token 仍然有效 —— 这是已知的、有意的取舍</h3>
     * <p>本项目的登出是「把<b>当前这一个</b> token 丢进 Redis 黑名单」，
     * 没有「按用户批量失效」的机制（那需要在 filter 里加一层用户级
     * tokenVersion 比对，会改动登录、登出、鉴权三处）。所以此刻改密码，
     * 别的设备上已签发的 token 在过期前仍能用。
     * <p>应对在<b>前端</b>：改密成功后立即登出并要求用新密码重新登录
     * （见 {@code ProfileModal.vue}）。这不能撤销别处的 token，
     * 但能保证「改密码的人自己」用的确实是新密码。
     * <p>要真正堵住，得引入用户级 token 版本号；那是独立改造，
     * 不该塞进这个功能里顺手做 —— 改鉴权链路的回归面远大于本功能。
     */
    public void changePassword(ChangePasswordRequest req) {
        SysUser account = loadMine();

        if (!passwordEncoder.matches(req.getOldPassword(), account.getPassword())) {
            // 文案刻意不说「旧密码错误」，只说「当前密码不正确」——
            // 与登录接口「不区分账号不存在/密码错误」同一考虑：不额外泄露信息。
            throw new BizException(400, "当前密码不正确");
        }
        if (passwordEncoder.matches(req.getNewPassword(), account.getPassword())) {
            throw new BizException(400, "新密码不能与当前密码相同");
        }

        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, account.getId())
                .set(SysUser::getPassword, passwordEncoder.encode(req.getNewPassword()))
                .set(SysUser::getUpdatedAt, LocalDateTime.now()));

        // 日志只记「谁改了密码」，绝不记密码本身（明文与哈希都不记）。
        log.info("[个人资料] 密码已修改 userId={} 账号={}", account.getId(), account.getUsername());
    }

    // ============================================================
    // 内部
    // ============================================================

    /** 取当前登录者。查不到 = 登录态失效 */
    private SysUser loadMine() {
        Long meId = SecurityUtil.getUserId();
        // selectById 会带上 @TableLogic 的 is_deleted = 0 条件（MP 自动追加）
        SysUser account = sysUserMapper.selectById(meId);
        if (account == null) {
            throw new BizException(401, "账号不存在或已被删除，请重新登录");
        }
        return account;
    }

    private ProfileVO toVO(SysUser a) {
        return ProfileVO.builder()
                .id(a.getId())
                .username(a.getUsername())
                .realName(a.getRealName())
                .phone(a.getPhone())
                .avatarUrl(a.getAvatarUrl())
                .role(a.getRole())
                .roleLabel(roleLabel(a.getRole()))
                .build();
    }

    /**
     * 角色中文名。只在这一处维护 —— 前端若再自行映射一份，
     * 将来加角色（比如「社区工作者」）必然有一端漏改，表现为界面上出现英文。
     */
    private String roleLabel(String role) {
        if (role == null) {
            return "未知角色";
        }
        return switch (role) {
            case "RESIDENT" -> "居民用户";
            case "DOCTOR" -> "医生";
            case "ADMIN" -> "管理员";
            default -> role;
        };
    }

    /** 去空白；空串与纯空白都归一为 null（库里只有一个「没填」的表示） */
    private String blankToNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }
}
