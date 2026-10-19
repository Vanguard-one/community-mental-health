package com.cmh.psycare.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.enums.RiskLevel;
import com.cmh.psycare.dto.RecommendResult;
import com.cmh.psycare.entity.Doctor;
import com.cmh.psycare.entity.RecommendationLog;
import com.cmh.psycare.entity.SysUser;
import com.cmh.psycare.mapper.DoctorMapper;
import com.cmh.psycare.mapper.RecommendationLogMapper;
import com.cmh.psycare.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 医生推荐引擎：规则引擎 + 三级匹配 + 加权排序。
 *
 * 三级匹配：
 *  1) 风险等级 → 目标科室（D/C 精神科优先，B 心理科，A 全科）
 *  2) 测评维度 → 专长标签匹配（如 抑郁维度高分 → 带"抑郁"标签的医生）
 *  3) 综合排序：评分 - 负载惩罚 + 标签加成 + 科室优先级加成
 *
 * 诚实定位：这是基于规则的画像匹配，不硬套协同过滤。推荐理由落库供解释。
 *
 * <h3>角色拆分后的变化</h3>
 * 医生差异字段从 doctor_profile 移到 doctor 表，且 doctor.id 直接就是
 * sys_user.id（不再有 profileId 这一层）。副作用是**修掉了一个历史 bug**：
 * 过去 writeLog 写的是 profile.id，而 consult_session.doctor_id 语义上是
 * sys_user.id，两者对不上；现在天然一致。
 */
@Component
@RequiredArgsConstructor
public class RecommendationEngine {

    private static final String AUDIT_APPROVED = "APPROVED";

    private final DoctorMapper doctorMapper;
    private final SysUserMapper sysUserMapper;
    private final RecommendationLogMapper recommendationLogMapper;

    /** 风险等级 → 科室优先级（越靠前越优先） */
    private static final Map<RiskLevel, List<String>> DEPT_PRIORITY = Map.of(
            RiskLevel.D, List.of("精神科", "心理科", "全科"),
            RiskLevel.C, List.of("精神科", "心理科", "全科"),
            RiskLevel.B, List.of("心理科", "全科", "精神科"),
            RiskLevel.A, List.of("全科", "心理科", "精神科")
    );

    /**
     * @param riskLevel 当前风险等级
     * @param dimension 主要测评维度（如 抑郁），可为 null
     * @param topN      返回条数
     */
    public List<RecommendResult> recommend(Long residentId, Long sessionId,
                                           RiskLevel riskLevel, String dimension, int topN) {
        return recommend(residentId, sessionId, riskLevel, dimension, topN, true);
    }

    /**
     * @param record 是否写 {@code recommendation_log}
     *
     *               <p><b>什么时候该传 false</b>：只把推荐当**素材**用、而不是当作
     *               「给用户的一次推荐事件」时。典型场景是 AI 对话上下文注入 ——
     *               它每轮都要一份名单，若照常落日志，一轮就多 3 行，
     *               几轮下来同一份推荐在表里重复几十次，
     *               {@code recommendation_log} 的存在意义（统计推荐了什么、
     *               后续有没有被接受）会被彻底稀释。
     *
     *               <p>而侧栏/推荐页那次调用是真实事件，必须记。
     */
    public List<RecommendResult> recommend(Long residentId, Long sessionId,
                                           RiskLevel riskLevel, String dimension, int topN,
                                           boolean record) {
        // 只推荐「可接诊 + 资质已通过」的医生。
        // 资质未通过就参与推荐，等于把患者导给一个无资质账号。
        List<Doctor> doctors = doctorMapper.selectList(new LambdaQueryWrapper<Doctor>()
                .eq(Doctor::getAvailable, 1)
                .eq(Doctor::getAuditStatus, AUDIT_APPROVED));
        if (doctors.isEmpty()) {
            return List.of();
        }

        // 账号共性字段（姓名）在基表；@TableLogic 已排除软删账号
        Map<Long, SysUser> userMap = sysUserMapper.selectBatchIds(
                        doctors.stream().map(Doctor::getId).collect(Collectors.toSet()))
                .stream()
                .filter(u -> Integer.valueOf(1).equals(u.getStatus()))
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));

        List<String> deptPriority = DEPT_PRIORITY.getOrDefault(riskLevel, DEPT_PRIORITY.get(RiskLevel.A));

        List<ScoredDoctor> scored = new ArrayList<>();
        for (Doctor d : doctors) {
            // 账号被禁用 / 已删除的医生不参与推荐
            SysUser account = userMap.get(d.getId());
            if (account == null) {
                continue;
            }
            // 两份理由，用途不同，刻意分开：
            //   reason      内部评分依据，进 recommendation_log，允许出现风险等级/维度等标签，便于复算打分
            //   humanReason 对外展示，进 RecommendResult.reason，只讲「为什么适合你」，不含内部标签
            StringBuilder reason = new StringBuilder();
            StringBuilder humanReason = new StringBuilder();
            double score = 0;

            // 科室优先级加成
            int deptIdx = deptPriority.indexOf(d.getDepartment());
            if (deptIdx >= 0) {
                score += 30 - deptIdx * 10;
                reason.append("风险等级").append(riskLevel).append("→科室[").append(d.getDepartment()).append("]；");
                humanReason.append("你目前的情况适合先到 ").append(d.getDepartment()).append(" 就诊；");
            }
            // 专长标签匹配
            if (dimension != null && d.getExpertiseTags() != null
                    && Arrays.stream(d.getExpertiseTags().split(","))
                    .anyMatch(t -> t.trim().equalsIgnoreCase(dimension) || dimension.contains(t.trim()))) {
                score += 25;
                reason.append("维度[").append(dimension).append("]与专长匹配；");
                humanReason.append("擅长方向与你测评中较突出的「").append(dimension).append("」问题相符；");
            }
            // 评分加成
            score += (d.getRating() == null ? 0 : d.getRating()) * 4;
            // 负载惩罚
            score -= (d.getCurrentLoad() == null ? 0 : d.getCurrentLoad()) * 3;
            if (d.getCurrentLoad() != null && d.getCurrentLoad() <= 3) {
                humanReason.append("当前接诊量不大，可以较快安排；");
            }

            scored.add(new ScoredDoctor(d, account, score, reason.toString(), humanReason.toString()));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredDoctor::score).reversed())
                .limit(Math.max(1, topN))
                .map(s -> {
                    if (record) {
                        writeLog(residentId, sessionId, s);
                    }
                    return RecommendResult.builder()
                            // doctorId 与 userId 现在是同一个值（doctor.id == sys_user.id）。
                            // 两个字段都保留是为了不破坏既有前端契约。
                            .doctorId(s.doctor.getId())
                            .userId(s.doctor.getId())
                            .doctorName(s.user.getRealName())
                            .department(s.doctor.getDepartment())
                            .title(s.doctor.getTitle())
                            .expertiseTags(s.doctor.getExpertiseTags())
                            .rating(s.doctor.getRating())
                            .currentLoad(s.doctor.getCurrentLoad())
                            .score(s.score)
                            .reason(humanReason(s))
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 对居民展示的推荐理由。
     *
     * <h4>为什么不能直接透出内部评分依据</h4>
     * <p>内部依据长这样：{@code 风险等级A→科室[全科]；维度[抑郁]与专长匹配；}。
     * 它含<b>系统风险等级标签</b>，而 prompt 的 L2 第 6 条与产品约定都明确禁止
     * 向居民透露内部评级（避免标签化）。此前这段字符串直接显示在推荐卡片上，
     * 用户看到的是「风险等级A→科室[全科]」——不像理由，像日志。
     *
     * <p>兜底文案兼顾「没有任何加成项」的医生（例如只靠评分与低负载入选）。
     */
    private String humanReason(ScoredDoctor s) {
        if (s.humanReason == null || s.humanReason.isBlank()) {
            return "综合擅长方向与当前接诊情况，适合作为候选。";
        }
        // 分段末尾的「；」换成句号收尾
        return s.humanReason.replaceAll("；$", "。");
    }

    private void writeLog(Long residentId, Long sessionId, ScoredDoctor s) {
        RecommendationLog log = new RecommendationLog();
        log.setResidentId(residentId);
        log.setSessionId(sessionId);
        log.setDoctorId(s.doctor.getId());
        // 日志记内部依据而不是对外文案：复盘打分时要能看出是哪一项加的分
        log.setReason(s.reason);
        log.setAccepted(0);
        log.setCreatedAt(LocalDateTime.now());
        recommendationLogMapper.insert(log);
    }

    private record ScoredDoctor(Doctor doctor, SysUser user, double score,
                                String reason, String humanReason) {
    }
}
