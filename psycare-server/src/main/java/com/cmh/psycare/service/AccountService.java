package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.result.PageResult;
import com.cmh.psycare.common.util.MaskUtil;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AccountVO;
import com.cmh.psycare.entity.Admin;
import com.cmh.psycare.entity.Doctor;
import com.cmh.psycare.entity.SysUser;
import com.cmh.psycare.mapper.AccountMapper;
import com.cmh.psycare.mapper.AdminMapper;
import com.cmh.psycare.mapper.DoctorMapper;
import com.cmh.psycare.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 管理端账号治理：列表 / 启用禁用 / 逻辑删除 / 重置密码 / 医生资质审核。
 *
 * <h3>为什么所有写操作都要先回查 sys_user</h3>
 * 路径参数里的 userType 是**前端声明**，不可信。若直接按它决定操作哪张表，
 * 就能构造 DELETE /admin/account/DOCTOR/{某个管理员的 id} 之类的越权请求。
 * 因此统一先按 id 查 sys_user 拿到真实 role，再校验它与 userType 是否吻合。
 * 角色表在此模型下只是「差异字段的附属表」，真正的账号状态（status / is_deleted）
 * 一律落在 sys_user。
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final Set<String> USER_TYPES = Set.of("RESIDENT", "DOCTOR", "ADMIN");
    private static final String LEVEL_SUPER = "SUPER";
    private static final String AUDIT_PENDING = "PENDING";
    private static final String AUDIT_APPROVED = "APPROVED";
    private static final String AUDIT_REJECTED = "REJECTED";

    private static final char[] PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private final SysUserMapper sysUserMapper;
    private final DoctorMapper doctorMapper;
    private final AdminMapper adminMapper;
    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;

    private final SecureRandom random = new SecureRandom();

    // ============================================================
    // 查询
    // ============================================================

    /** 分页列表：按角色 / 状态 / 审核状态 / 关键字筛选 */
    public PageResult<AccountVO> page(long current, long size, String userType,
                                      Integer status, String auditStatus, String keyword) {
        IPage<AccountVO> result = accountMapper.selectAccountPage(
                new Page<>(current, size),
                normalizeType(userType), status,
                StringUtils.hasText(auditStatus) ? auditStatus : null,
                StringUtils.hasText(keyword) ? keyword.trim() : null);
        // 手机号脱敏后再出网。姓名不脱敏 —— 管理端要能定位到具体账号，脱了就没法治理
        for (AccountVO vo : result.getRecords()) {
            vo.setPhone(MaskUtil.phone(vo.getPhone()));
        }
        return PageResult.of(result);
    }

    /** 待审核医生数，供管理端菜单角标 */
    public int pendingAuditCount() {
        Integer count = accountMapper.selectPendingAuditCount();
        return count == null ? 0 : count;
    }

    // ============================================================
    // 启用 / 禁用
    // ============================================================

    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String userType, Long id, Integer status) {
        SysUser target = requireAccount(userType, id);
        if (Integer.valueOf(0).equals(status)) {
            assertNotSelf(id, "不能禁用当前登录账号");
            assertSuperAdminProtected(target, "禁用");
        } else {
            // 启用前校验角色行是否齐备，否则启用一个「缺角色行」的医生会导致其登录被拒
            assertRoleRowExists(target);
        }
        SysUser update = new SysUser();
        update.setId(target.getId());
        update.setStatus(status);
        update.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(update);
    }

    // ============================================================
    // 逻辑删除
    // ============================================================

    /**
     * 逻辑删除。
     * <p>
     * 刻意**不物理删除、也不级联删业务数据**：工单的 handler_id、
     * 测评的 resident_id 等都指向该 id，删掉会让历史记录失去可追溯性。
     * 这正是「统一走逻辑删除、保留审计」的意义。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String userType, Long id) {
        SysUser target = requireAccount(userType, id);
        assertNotSelf(id, "不能删除当前登录账号");
        assertSuperAdminProtected(target, "删除");

        // 显式 set deleted_at：@TableLogic 只负责置 is_deleted，不会写删除时间
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .set(SysUser::getIsDeleted, 1)
                .set(SysUser::getDeletedAt, LocalDateTime.now())
                .set(SysUser::getUpdatedAt, LocalDateTime.now()));
    }

    // ============================================================
    // 重置密码
    // ============================================================

    /**
     * 重置密码。newPassword 留空则生成随机强密码并回显给管理员转达。
     * <p>
     * 只改 sys_user 一处 —— 密码是账号身份字段，三张角色表不存副本，
     * 因此不存在「两张表密码不一致」的问题。
     */
    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(String userType, Long id, String newPassword) {
        requireAccount(userType, id);
        String plain = StringUtils.hasText(newPassword) ? newPassword : randomPassword();
        SysUser update = new SysUser();
        update.setId(id);
        update.setPassword(passwordEncoder.encode(plain));
        update.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(update);
        return plain;
    }

    // ============================================================
    // 医生资质审核
    // ============================================================

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id, String auditStatus, String auditRemark) {
        SysUser target = requireAccount("DOCTOR", id);
        if (!AUDIT_APPROVED.equals(auditStatus) && !AUDIT_REJECTED.equals(auditStatus)) {
            throw new BizException(400, "审核结果只能是 APPROVED 或 REJECTED");
        }
        // 审核结论只区分通过/驳回，不强制文字意见。
        // （界面已移除「驳回原因 / 审核意见」输入框，这里同步放开校验，
        //   否则删掉输入框后驳回会被 400 打回、功能直接坏掉。）
        // audit_remark 列保留：传了才写，不传即保持/清空为 NULL。
        Doctor doctor = doctorMapper.selectById(target.getId());
        if (doctor == null) {
            throw new BizException(400, "该账号缺少医生资质记录，无法审核");
        }
        doctor.setAuditStatus(auditStatus);
        doctor.setAuditRemark(StringUtils.hasText(auditRemark) ? auditRemark.trim() : null);
        doctor.setAuditedBy(SecurityUtil.getUserId());
        doctor.setAuditedAt(LocalDateTime.now());
        doctor.setUpdatedAt(LocalDateTime.now());
        doctorMapper.updateById(doctor);
    }

    // ============================================================
    // 公共校验
    // ============================================================

    /**
     * 按 id 回查账号并校验其真实角色与前端声明的 userType 一致。
     * @TableLogic 已保证已删除账号查不出来，因此这里天然拒绝操作已删账号。
     */
    private SysUser requireAccount(String userType, Long id) {
        if (!USER_TYPES.contains(userType)) {
            throw new BizException(400, "账号类型不合法");
        }
        SysUser account = sysUserMapper.selectById(id);
        if (account == null) {
            throw new BizException(404, "账号不存在或已删除");
        }
        if (!userType.equals(account.getRole())) {
            throw new BizException(400, "账号类型与目标账号不符");
        }
        return account;
    }

    private void assertNotSelf(Long id, String message) {
        if (id.equals(SecurityUtil.getUserId())) {
            throw new BizException(400, message);
        }
    }

    /**
     * 超级管理员保护：
     * 1) 只有超管能操作超管账号（否则普通管理员能把超管踢掉）；
     * 2) 系统必须至少保留一个超管，避免治理功能彻底锁死。
     */
    private void assertSuperAdminProtected(SysUser target, String action) {
        if (!"ADMIN".equals(target.getRole())) {
            return;
        }
        Admin targetAdmin = adminMapper.selectById(target.getId());
        if (targetAdmin == null || !LEVEL_SUPER.equals(targetAdmin.getAdminLevel())) {
            return;
        }
        Admin self = adminMapper.selectById(SecurityUtil.getUserId());
        if (self == null || !LEVEL_SUPER.equals(self.getAdminLevel())) {
            throw new BizException(403, "需要超级管理员权限才能" + action + "超级管理员账号");
        }
        Long others = adminMapper.selectCount(new LambdaQueryWrapper<Admin>()
                .eq(Admin::getAdminLevel, LEVEL_SUPER)
                .ne(Admin::getId, target.getId()));
        if (others == null || others == 0) {
            throw new BizException(400, "系统必须保留至少一个超级管理员，无法" + action);
        }
    }

    /** 启用前确认角色行存在（医生缺资质行会直接被登录门禁拒绝） */
    private void assertRoleRowExists(SysUser target) {
        if ("DOCTOR".equals(target.getRole()) && doctorMapper.selectById(target.getId()) == null) {
            throw new BizException(400, "该医生账号缺少资质记录，请先补齐后再启用");
        }
    }

    private String normalizeType(String userType) {
        if (!StringUtils.hasText(userType)) {
            return null;
        }
        String upper = userType.trim().toUpperCase();
        if (!USER_TYPES.contains(upper)) {
            throw new BizException(400, "账号类型不合法");
        }
        return upper;
    }

    /** 随机密码：排除 0/O、1/l/I 等易混字符，长度 10 */
    private String randomPassword() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)]);
        }
        return sb.toString();
    }
}
