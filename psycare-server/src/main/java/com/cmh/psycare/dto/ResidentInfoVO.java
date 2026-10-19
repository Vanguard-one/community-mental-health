package com.cmh.psycare.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 医生端「用户信息」列表项：把「基础档案 + 测评概览 + 当前会话」汇成一行。
 * <p>
 * 用 {@code @Data} 而非 {@code @Builder}：它由 MyBatis 直接自动映射填充，
 * 而 {@code @Builder} 生成的全参构造器会吃掉无参构造器，导致 MyBatis 无法实例化。
 * <p>
 * 安全约定：姓名与手机号在 Service 层统一走 {@code MaskUtil} 脱敏后才下发，
 * 本 VO 里带着的已是脱敏值；**不含 password**。
 */
@Data
public class ResidentInfoVO {

    /** sys_user.id */
    private Long id;

    private String username;

    /** 已脱敏，如 张* */
    private String realName;

    /** 已脱敏，如 138****0001 */
    private String phone;

    /** M/F/U，来自 user 表 */
    private String gender;

    private Integer birthYear;

    /** 由 birthYear 推算，缺年份时为 null */
    private Integer age;

    private String occupation;

    // ---------- 测评概览 ----------

    /** 累计测评次数 */
    private Integer assessmentCount;

    /** 最近一次测评时间，无记录为 null */
    private LocalDateTime lastAssessmentAt;

    /** 最近一次测评的风险等级（A/B/C/D），无记录为 null */
    private String lastRiskLevel;

    /** 危机项阳性次数（crisis_flag = 1 的测评数） */
    private Integer crisisCount;

    // ---------- 聊天 ----------

    /**
     * 当前登录医生与这位居民**已存在**的进行中会话 id；没有则为 null。
     * <p>
     * 带上它是为了让前端「聊天」按钮能直接打开，而不是每次都发一次创建请求 ——
     * 新建会话是写操作，仅靠按钮点击触发更容易产生并发重复会话。
     */
    private Long sessionId;
}
