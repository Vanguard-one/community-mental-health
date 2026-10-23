package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.util.JwtUtil;
import com.cmh.psycare.dto.DoctorRegisterRequest;
import com.cmh.psycare.dto.LoginRequest;
import com.cmh.psycare.dto.LoginResponse;
import com.cmh.psycare.dto.RegisterResponse;
import com.cmh.psycare.dto.ResidentRegisterRequest;
import com.cmh.psycare.entity.Admin;
import com.cmh.psycare.entity.Doctor;
import com.cmh.psycare.entity.SysUser;
import com.cmh.psycare.entity.User;
import com.cmh.psycare.mapper.AdminMapper;
import com.cmh.psycare.mapper.DoctorMapper;
import com.cmh.psycare.mapper.SysUserMapper;
import com.cmh.psycare.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务：登录（按角色路由）/ 注册分流 / 登出（JWT 黑名单）。
 *
 * <h3>账号模型</h3>
 * 角色拆分后 sys_user 是**唯一认证来源**（username / password / role / status /
 * is_deleted），三张角色表以 id 与其 1:1，只承载角色差异字段。
 * 因此本服务不会出现「密码要写两张表」这类双写问题。
 *
 * <h3>登录链路</h3>
 * ① 基表定位账号 → ② 身份一致性校验 → ③ 状态与密码校验
 * → ④ **按角色路由到对应角色表**做角色专属校验（医生走资质门禁）
 * → ⑤ 签发 token（载荷仍是 userId/username/role，前端零改动）。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private static final String ROLE_RESIDENT = "RESIDENT";
    private static final String ROLE_DOCTOR = "DOCTOR";
    private static final String ROLE_ADMIN = "ADMIN";

    private static final String AUDIT_PENDING = "PENDING";
    private static final String AUDIT_APPROVED = "APPROVED";
    private static final String AUDIT_REJECTED = "REJECTED";

    private final SysUserMapper sysUserMapper;
    private final UserMapper userMapper;
    private final DoctorMapper doctorMapper;
    private final AdminMapper adminMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    // ============================================================
    // 登录
    // ============================================================

    public LoginResponse login(LoginRequest req) {
        // ① 基表定位账号（@TableLogic 自动排除 is_deleted = 1 的账号）
        SysUser account = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, req.getUsername()));
        if (account == null) {
            // 刻意不区分「账号不存在」与「密码错误」，防止用户名枚举
            throw new BizException(400, "用户名或密码错误");
        }

        // ② 身份一致性：前端选了身份，就必须与账号实际角色吻合
        if (StringUtils.hasText(req.getUserType())
                && !req.getUserType().equalsIgnoreCase(account.getRole())) {
            throw new BizException(400,
                    "该账号不是" + roleLabel(req.getUserType()) + "账号，请切换身份后重试");
        }

        if (Integer.valueOf(0).equals(account.getStatus())) {
            throw new BizException(403, "账号已被禁用，请联系管理员");
        }
        if (!passwordEncoder.matches(req.getPassword(), account.getPassword())) {
            throw new BizException(400, "用户名或密码错误");
        }

        // ③ 按角色路由到对应角色表，做该角色专属的校验
        Map<String, Object> extra = switch (account.getRole() == null ? "" : account.getRole()) {
            case ROLE_RESIDENT -> residentExtra(account);
            case ROLE_DOCTOR -> doctorExtra(account);
            case ROLE_ADMIN -> adminExtra(account);
            // role 脏数据（既不是三种之一）不允许登录，否则后续鉴权会全部失效
            default -> throw new BizException(403, "账号角色异常，请联系管理员");
        };

        // ④ 签发 token：载荷保持 userId/username/role 三字段，前端与路由守卫零改动
        String token = jwtUtil.generate(account.getId(), account.getUsername(), account.getRole());
        return LoginResponse.builder()
                .token(token)
                .userId(account.getId())
                .username(account.getUsername())
                .realName(account.getRealName())
                .role(account.getRole())
                // 头像随登录一起下发：侧栏头像在每一页都要渲染，
                // 让前端登录后再补查一次会在首屏闪一下默认图标。
                .avatarUrl(account.getAvatarUrl())
                .extra(extra.isEmpty() ? null : extra)
                .build();
    }

    /**
     * 居民：无角色专属门禁。
     * 角色差异字段（性别/出生年等）全部可选，缺失也不影响登录 ——
     * 存量居民档案本身为空，强校验会直接把老用户挡在门外。
     */
    private Map<String, Object> residentExtra(SysUser account) {
        return new HashMap<>();
    }

    /**
     * 医生：资质门禁。未通过审核一律拒绝登录 —— 无资质账号不得进入工单处置台。
     * 这是本需求「审核状态」落地的唯一强制点。
     */
    private Map<String, Object> doctorExtra(SysUser account) {
        Doctor doctor = doctorMapper.selectById(account.getId());
        if (doctor == null) {
            // 角色表缺行属于数据不一致：宁可拒绝，也不能放一个没有资质的账号进来
            throw new BizException(403, "医生资质信息缺失，请联系管理员");
        }
        String audit = doctor.getAuditStatus() == null ? AUDIT_PENDING : doctor.getAuditStatus();
        if (!AUDIT_APPROVED.equals(audit)) {
            // 审核界面已不采集文字意见，audit_remark 通常为空 —— 无原因时给通用但完整的提示
            throw new BizException(403, AUDIT_REJECTED.equals(audit)
                    ? (StringUtils.hasText(doctor.getAuditRemark())
                    ? "资质审核未通过：" + doctor.getAuditRemark()
                    : "资质审核未通过，请联系管理员补充材料后重新提交")
                    : "资质审核中，请等待管理员审核通过后再登录");
        }
        Map<String, Object> extra = new HashMap<>();
        extra.put("auditStatus", audit);
        extra.put("department", doctor.getDepartment());
        extra.put("title", doctor.getTitle());
        return extra;
    }

    /** 管理员：只需带出分级，供前端决定是否显示「超管专属」操作 */
    private Map<String, Object> adminExtra(SysUser account) {
        Map<String, Object> extra = new HashMap<>();
        Admin admin = adminMapper.selectById(account.getId());
        if (admin != null && admin.getAdminLevel() != null) {
            extra.put("adminLevel", admin.getAdminLevel());
        }
        return extra;
    }

    private String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case ROLE_RESIDENT -> "居民";
            case ROLE_DOCTOR -> "医生";
            case ROLE_ADMIN -> "管理员";
            default -> "";
        };
    }

    // ============================================================
    // 注册分流
    // ============================================================

    /**
     * 居民注册。
     * 一次事务写两张表：sys_user（账号）+ user（居民差异字段）。
     */
    @Transactional(rollbackFor = Exception.class)
    public RegisterResponse registerResident(ResidentRegisterRequest req) {
        checkPasswordConfirmed(req.getPassword(), req.getConfirmPassword());
        SysUser account = createAccount(req.getUsername(), req.getPassword(),
                req.getRealName(), req.getPhone(), ROLE_RESIDENT);

        User resident = new User();
        resident.setId(account.getId());          // 主键沿用基表 id
        resident.setGender(blankToNull(req.getGender()));
        resident.setBirthYear(req.getBirthYear());
        resident.setOccupation(blankToNull(req.getOccupation()));
        resident.setEmergencyContact(blankToNull(req.getEmergencyContact()));
        resident.setCreatedAt(LocalDateTime.now());
        resident.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(resident);

        return RegisterResponse.builder()
                .userId(account.getId())
                .userType(ROLE_RESIDENT)
                .tip("注册成功，请返回登录")
                .build();
    }

    /**
     * 医生注册。
     * 注册即 PENDING，**不能立即登录**，需管理员在账号治理页审核。
     * 因此返回值必须带上 auditStatus，让前端能给出明确预期。
     */
    @Transactional(rollbackFor = Exception.class)
    public RegisterResponse registerDoctor(DoctorRegisterRequest req) {
        checkPasswordConfirmed(req.getPassword(), req.getConfirmPassword());
        SysUser account = createAccount(req.getUsername(), req.getPassword(),
                req.getRealName(), req.getPhone(), ROLE_DOCTOR);

        Doctor doctor = new Doctor();
        doctor.setId(account.getId());
        doctor.setLicenseNo(req.getLicenseNo());
        doctor.setDepartment(blankToNull(req.getDepartment()));
        doctor.setTitle(blankToNull(req.getTitle()));
        doctor.setExpertiseTags(blankToNull(req.getExpertiseTags()));
        doctor.setRating(5.0);
        doctor.setCurrentLoad(0);
        doctor.setAvailable(1);
        doctor.setAuditStatus(AUDIT_PENDING);
        doctor.setCreatedAt(LocalDateTime.now());
        doctor.setUpdatedAt(LocalDateTime.now());
        doctorMapper.insert(doctor);

        return RegisterResponse.builder()
                .userId(account.getId())
                .userType(ROLE_DOCTOR)
                .auditStatus(AUDIT_PENDING)
                .tip("资料已提交，审核通过后即可登录")
                .build();
    }

    /** 建账号：写 sys_user，返回带自增 id 的实体作为角色表主键来源 */
    private SysUser createAccount(String username, String rawPassword,
                                  String realName, String phone, String role) {
        Long exists = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (exists != null && exists > 0) {
            throw new BizException(400, "用户名已存在");
        }
        SysUser account = new SysUser();
        account.setUsername(username);
        account.setPassword(passwordEncoder.encode(rawPassword));
        account.setRealName(realName);
        account.setPhone(blankToNull(phone));
        account.setRole(role);
        account.setStatus(1);
        account.setIsDeleted(0);
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.insert(account);
        return account;
    }

    private void checkPasswordConfirmed(String password, String confirmPassword) {
        if (confirmPassword != null && !confirmPassword.equals(password)) {
            throw new BizException(400, "两次输入的密码不一致");
        }
    }

    /** 前端空输入会传空串，落库统一转 null，避免出现「有值但为空」的脏数据 */
    private String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    // ============================================================
    // 登出
    // ============================================================

    /** 登出：把 token 加入黑名单，TTL 与 token 剩余有效期一致 */
    public void logout(String token) {
        long ttlMillis = jwtUtil.parse(token).getExpiration().getTime() - System.currentTimeMillis();
        if (ttlMillis > 0) {
            redis.opsForValue().set(BLACKLIST_PREFIX + token, "1", ttlMillis, TimeUnit.MILLISECONDS);
        }
    }
}
