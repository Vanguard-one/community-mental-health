package com.cmh.psycare.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.entity.Admin;
import com.cmh.psycare.entity.Doctor;
import com.cmh.psycare.entity.SysUser;
import com.cmh.psycare.entity.User;
import com.cmh.psycare.mapper.AdminMapper;
import com.cmh.psycare.mapper.DoctorMapper;
import com.cmh.psycare.mapper.SysUserMapper;
import com.cmh.psycare.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动数据初始化。
 *
 * <h3>两个职责</h3>
 * 1. **首次启动播种**：sys_user 为空时初始化管理员与示例医生
 *    （密码用 BCrypt 实时生成，不在 SQL 里硬编码不可逆哈希）。
 * 2. **角色行补齐**：角色拆分后账号落在 sys_user，差异字段落在三张角色表。
 *    若某些账号缺角色行（例如由旧版本代码写入、或手工插的数据），
 *    这里补建。
 *
 * <h3>为什么必须做第 2 件事</h3>
 * 拆分前的写法是「sys_user 非空就直接 return」—— 迁移后 sys_user 一定有数据，
 * 于是角色表永远补不上，医生登录会因「资质信息缺失」被拒。幂等补齐可以根治这一点。
 *
 * <h3>补齐时的默认值取舍</h3>
 * 补建的医生行一律给 audit_status = APPROVED，而不是表默认的 PENDING：
 * 存量账号是「拆分前就已经在用」的合法账号，若置为 PENDING 会被登录门禁直接拒绝，
 * 等于把老用户锁在门外。新注册的医生走 AuthService.registerDoctor，仍是 PENDING。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final String AUDIT_APPROVED = "APPROVED";

    private final SysUserMapper sysUserMapper;
    private final UserMapper userMapper;
    private final DoctorMapper doctorMapper;
    private final AdminMapper adminMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        Long count = sysUserMapper.selectCount(new LambdaQueryWrapper<>());
        if (count == null || count == 0) {
            seed();
        }
        backfillRoleRows();
    }

    // ============================================================
    // 首次播种
    // ============================================================

    private void seed() {
        log.info("初始化基础账号数据...");

        SysUser admin = newAccount("admin", "admin123", "系统管理员", "13800000000", "ADMIN");
        sysUserMapper.insert(admin);
        adminMapper.insert(newAdmin(admin.getId(), "SUPER"));

        SysUser d1 = newAccount("doctor_li", "doctor123", "李医生", "13800000001", "DOCTOR");
        sysUserMapper.insert(d1);
        doctorMapper.insert(newDoctor(d1.getId(), "110101000001", "精神科", "主任医师", "抑郁,焦虑,睡眠", 4.8, 2));

        SysUser d2 = newAccount("doctor_wang", "doctor123", "王医生", "13800000002", "DOCTOR");
        sysUserMapper.insert(d2);
        doctorMapper.insert(newDoctor(d2.getId(), "110101000002", "心理科", "副主任医师", "青少年,压力,焦虑", 4.6, 1));

        SysUser d3 = newAccount("doctor_zhao", "doctor123", "赵医生", "13800000003", "DOCTOR");
        sysUserMapper.insert(d3);
        doctorMapper.insert(newDoctor(d3.getId(), "110101000003", "全科", "主治医师", "老年,慢病,抑郁", 4.5, 0));

        log.info("初始化完成：admin/admin123，医生账号 doctor_li|doctor_wang|doctor_zhao / doctor123");
    }

    // ============================================================
    // 角色行补齐（幂等，每次启动都跑）
    // ============================================================

    private void backfillRoleRows() {
        // selectList 走 @TableLogic，已软删账号不会被补齐
        List<SysUser> accounts = sysUserMapper.selectList(new LambdaQueryWrapper<>());
        int fixed = 0;
        for (SysUser account : accounts) {
            String role = account.getRole();
            if (role == null) {
                continue;
            }
            switch (role) {
                case "RESIDENT" -> {
                    if (userMapper.selectById(account.getId()) == null) {
                        User resident = new User();
                        resident.setId(account.getId());
                        resident.setCreatedAt(LocalDateTime.now());
                        resident.setUpdatedAt(LocalDateTime.now());
                        userMapper.insert(resident);
                        fixed++;
                    }
                }
                case "DOCTOR" -> {
                    if (doctorMapper.selectById(account.getId()) == null) {
                        Doctor doctor = new Doctor();
                        doctor.setId(account.getId());
                        doctor.setRating(5.0);
                        doctor.setCurrentLoad(0);
                        doctor.setAvailable(1);
                        doctor.setAuditStatus(AUDIT_APPROVED);
                        doctor.setAuditRemark("角色拆分时补建，视为已审核");
                        doctor.setCreatedAt(LocalDateTime.now());
                        doctor.setUpdatedAt(LocalDateTime.now());
                        doctorMapper.insert(doctor);
                        fixed++;
                    }
                }
                case "ADMIN" -> {
                    if (adminMapper.selectById(account.getId()) == null) {
                        adminMapper.insert(newAdmin(account.getId(), "NORMAL"));
                        fixed++;
                    }
                }
                default -> log.warn("账号 id={} 的角色值异常：{}", account.getId(), role);
            }
        }
        if (fixed > 0) {
            log.info("角色行补齐完成，新建 {} 条角色记录", fixed);
        }
        ensureSuperAdminExists();
    }

    /**
     * 兜底保证至少有一个超级管理员。
     * 没有超管会让「SUPER 才能删/禁用管理员」的保护规则变成死锁：
     * 谁都动不了管理员账号，只能改库。
     */
    private void ensureSuperAdminExists() {
        Long superCount = adminMapper.selectCount(
                new LambdaQueryWrapper<Admin>().eq(Admin::getAdminLevel, "SUPER"));
        if (superCount != null && superCount > 0) {
            return;
        }
        List<Admin> admins = adminMapper.selectList(new LambdaQueryWrapper<>());
        if (admins.isEmpty()) {
            return;
        }
        Admin first = admins.get(0);
        first.setAdminLevel("SUPER");
        first.setUpdatedAt(LocalDateTime.now());
        adminMapper.updateById(first);
        log.warn("未发现超级管理员，已将 admin id={} 提升为 SUPER", first.getId());
    }

    // ============================================================
    // 构造器
    // ============================================================

    private SysUser newAccount(String username, String raw, String name, String phone, String role) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(raw));
        u.setRealName(name);
        u.setPhone(phone);
        u.setRole(role);
        u.setStatus(1);
        u.setIsDeleted(0);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        return u;
    }

    private Doctor newDoctor(Long id, String licenseNo, String dept, String title,
                             String tags, double rating, int load) {
        Doctor d = new Doctor();
        d.setId(id);
        d.setLicenseNo(licenseNo);
        d.setDepartment(dept);
        d.setTitle(title);
        d.setExpertiseTags(tags);
        d.setRating(rating);
        d.setCurrentLoad(load);
        d.setAvailable(1);
        d.setAuditStatus(AUDIT_APPROVED);
        d.setAuditRemark("初始化示例数据");
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        return d;
    }

    private Admin newAdmin(Long id, String level) {
        Admin a = new Admin();
        a.setId(id);
        a.setAdminLevel(level);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        return a;
    }
}
