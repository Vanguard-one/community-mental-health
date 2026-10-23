package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.enums.RiskLevel;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.util.MaskUtil;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AlertAssessmentVO;
import com.cmh.psycare.dto.AssessmentHistoryVO;
import com.cmh.psycare.dto.AssessmentRecordVO;
import com.cmh.psycare.dto.AssessmentResultResponse;
import com.cmh.psycare.dto.AssessmentSubmitRequest;
import com.cmh.psycare.dto.LatestReportVO;
import com.cmh.psycare.engine.AlertEngine;
import com.cmh.psycare.engine.ScoreResult;
import com.cmh.psycare.engine.ScoringEngine;
import com.cmh.psycare.entity.AlertRecord;
import com.cmh.psycare.entity.Assessment;
import com.cmh.psycare.entity.AssessmentAnswer;
import com.cmh.psycare.entity.Scale;
import com.cmh.psycare.entity.ScaleItem;
import com.cmh.psycare.entity.ScaleOption;
import com.cmh.psycare.entity.SysUser;
import com.cmh.psycare.mapper.AlertRecordMapper;
import com.cmh.psycare.mapper.AssessmentAnswerMapper;
import com.cmh.psycare.mapper.AssessmentMapper;
import com.cmh.psycare.mapper.AssessmentRecordMapper;
import com.cmh.psycare.mapper.ScaleItemMapper;
import com.cmh.psycare.mapper.ScaleMapper;
import com.cmh.psycare.mapper.ScaleOptionMapper;
import com.cmh.psycare.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 测评服务：整条"测评闭环"的编排入口。
 *
 * 数据流：
 *   提交作答 → 计分引擎 → 落库(测评+明细) → 预警引擎评估 → 返回结果
 * 这是"测评驱动后续链路"的起点，assessment 的 id 会被问诊、推荐、预警引用。
 */
@Service
@RequiredArgsConstructor
public class AssessmentService {

    private static final String ROLE_RESIDENT = "RESIDENT";

    private final ScaleMapper scaleMapper;
    private final ScaleItemMapper scaleItemMapper;
    private final ScaleOptionMapper scaleOptionMapper;
    private final AssessmentMapper assessmentMapper;
    private final AssessmentAnswerMapper assessmentAnswerMapper;
    private final AssessmentRecordMapper assessmentRecordMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final SysUserMapper sysUserMapper;
    private final ScoringEngine scoringEngine;
    private final AlertEngine alertEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 提交一次测评：计分 → 落库 → 预警评估，返回分级结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public AssessmentResultResponse submit(AssessmentSubmitRequest req) {
        Long residentId = SecurityUtil.getUserId();

        Scale scale = scaleMapper.selectById(req.getScaleId());
        if (scale == null || !Integer.valueOf(1).equals(scale.getStatus())) {
            throw new BizException(400, "量表不存在或已停用");
        }
        List<ScaleItem> items = scaleItemMapper.selectList(
                new LambdaQueryWrapper<ScaleItem>().eq(ScaleItem::getScaleId, scale.getId()));
        List<ScaleOption> allOptions = scaleOptionMapper.selectList(
                new LambdaQueryWrapper<ScaleOption>()
                        .in(ScaleOption::getItemId, items.stream().map(ScaleItem::getId).collect(Collectors.toList())));

        Map<Long, Long> submitted = req.getAnswers().stream()
                .collect(Collectors.toMap(AssessmentSubmitRequest.AnswerItem::getItemId,
                        AssessmentSubmitRequest.AnswerItem::getOptionId, (a, b) -> a));
        if (submitted.size() < items.size()) {
            throw new BizException(400, "存在未作答的题目");
        }

        // 1. 计分与分级
        ScoreResult result = scoringEngine.score(scale, items, allOptions, submitted);

        // 2. 落库测评主表
        Assessment assessment = new Assessment();
        assessment.setResidentId(residentId);
        assessment.setScaleId(scale.getId());
        assessment.setTotalScore(result.totalScore());
        assessment.setRawLevel(result.rawLevel());
        assessment.setRiskLevel(result.riskLevel());
        assessment.setCrisisFlag(result.crisis() ? 1 : 0);
        assessment.setAnswerJson(toJson(submitted));
        assessment.setCreatedAt(LocalDateTime.now());
        assessmentMapper.insert(assessment);

        // 3. 落库作答明细
        //    必须补 item_no 与 score：历史上这两列从未赋值，库里 68 行全为 NULL，
        //    导致任何依赖它们的查询（题号排序、单题得分）都拿不到值。
        //    题干/选项文案仍以 scale_item / scale_option 为准，这两列属冗余快照。
        Map<Long, ScaleItem> itemMap = items.stream()
                .collect(Collectors.toMap(ScaleItem::getId, Function.identity(), (a, b) -> a));
        Map<Long, ScaleOption> optionMap = allOptions.stream()
                .collect(Collectors.toMap(ScaleOption::getId, Function.identity(), (a, b) -> a));
        for (Map.Entry<Long, Long> e : submitted.entrySet()) {
            AssessmentAnswer ans = new AssessmentAnswer();
            ans.setAssessmentId(assessment.getId());
            ans.setItemId(e.getKey());
            ans.setOptionId(e.getValue());
            ScaleItem item = itemMap.get(e.getKey());
            if (item != null) {
                ans.setItemNo(item.getItemNo());
            }
            ScaleOption option = optionMap.get(e.getValue());
            if (option != null) {
                ans.setScore(option.getScore());
            }
            assessmentAnswerMapper.insert(ans);
        }

        // 4. 预警引擎评估（可能产生 THRESHOLD / TREND / CRISIS 工单）
        List<AlertRecord> alerts = alertEngine.evaluate(assessment);

        return AssessmentResultResponse.builder()
                .assessmentId(assessment.getId())
                .totalScore(result.totalScore())
                .rawLevel(result.rawLevel())
                .riskLevel(result.riskLevel())
                .riskLevelDesc(result.riskLevel().getDesc())
                .crisis(result.crisis())
                .alertTriggered(!alerts.isEmpty())
                .advice(buildAdvice(result))
                .build();
    }

    /** 居民测评历史（个人时间序列） */
    public List<Assessment> history(Long residentId) {
        return assessmentMapper.selectList(
                new LambdaQueryWrapper<Assessment>()
                        .eq(Assessment::getResidentId, residentId)
                        .orderByDesc(Assessment::getCreatedAt));
    }

    /**
     * 最近一次测评报告（AI 问诊页「报告」视图用）。
     *
     * <h3>为什么由服务端组装</h3>
     * 报告要的字段横跨三处：assessment（分数/等级/危机）、scale（卷名/满分/维度）、
     * buildAdvice（建议文案）。让前端串 {@code /assessment/history} +
     * {@code /assessment/{id}} + {@code /scale/list} 三个请求去拼，有两个坑：
     * <ul>
     *   <li>{@code /scale/list} 只返回启用中的量表 —— 量表被管理员禁用后，
     *       前端就查不到 maxScore，百分比条会**静默算成 0**，没有任何报错；</li>
     *   <li>{@code history} 的排序不是接口契约，前端取 {@code [0]} 迟早出错。</li>
     * </ul>
     * 这里一条 SQL 取最新，量表用 {@code selectByIdIncludeDeleted} 取（含软删），
     * 一次给全。
     *
     * @return 该居民从未做过测评时返回 {@code null}（前端渲染空状态）
     */
    public LatestReportVO latestReport(Long residentId) {
        // ORDER BY created_at DESC, id DESC：同一秒内提交两次时按自增 id 决胜，
        // 避免「刚做完却显示上一次」。LIMIT 1 让 selectOne 不会因多行抛异常。
        Assessment latest = assessmentMapper.selectOne(
                new LambdaQueryWrapper<Assessment>()
                        .eq(Assessment::getResidentId, residentId)
                        .orderByDesc(Assessment::getCreatedAt)
                        .orderByDesc(Assessment::getId)
                        .last("LIMIT 1"));
        if (latest == null) {
            return null;
        }

        // 量表可能已被软删：必须绕过逻辑删除，否则历史报告的卷名与满分就没了。
        Scale scale = scaleMapper.selectByIdIncludeDeleted(latest.getScaleId());

        // risk_level 在提交时必写，但老数据 / 手工订正不排除为空。
        // 兜底 A（最低风险）而不是抛异常：报告只是回显，缺一个等级不该让整页打不开。
        RiskLevel level = latest.getRiskLevel() == null ? RiskLevel.A : latest.getRiskLevel();
        boolean crisis = Integer.valueOf(1).equals(latest.getCrisisFlag());
        int total = latest.getTotalScore() == null ? 0 : latest.getTotalScore();
        String rawLevel = latest.getRawLevel() == null ? "" : latest.getRawLevel();

        return LatestReportVO.builder()
                .assessmentId(latest.getId())
                .scaleId(latest.getScaleId())
                .scaleCode(scale == null ? null : scale.getCode())
                .scaleName(scale == null ? null : scale.getName())
                .dimension(scale == null ? null : scale.getDimension())
                .maxScore(scale == null ? null : scale.getMaxScore())
                .totalScore(total)
                .rawLevel(rawLevel)
                .riskLevel(level)
                .riskLevelDesc(level.getDesc())
                .crisis(crisis)
                // 重算而不是读库：assessment 表没有 advice 列，buildAdvice 只依赖
                // crisis + riskLevel，而这两个值都在库里 —— 结果与提交时一致。
                .advice(buildAdvice(new ScoreResult(total, rawLevel, level, crisis)))
                .completedAt(latest.getCreatedAt())
                .build();
    }

    /** 居民测评历史 + 卷名（供工单右栏纵向对比用，带 join 的读模型） */
    public List<AssessmentHistoryVO> historyWithScale(Long residentId) {
        return assessmentRecordMapper.selectHistory(residentId);
    }

    /**
     * 单次测评的完整做题记录：卷名 + 每题题目与作答 + 得分 + 完成时间。
     * <p>
     * 这个接口此前缺失，前端 Result.vue 只能靠 sessionStorage 缓存渲染，
     * 刷新或深链必然打不开 —— 工单右栏的「查看做题记录」也依赖它做跳转。
     * <p>
     * 越权防护放在 Service 而不是 Controller：居民只能看自己的记录，
     * 医生/管理员可看任意记录（工单处置需要）。放 Service 层是为了
     * 任何未来新增的调用方都自动受保护。
     */
    public AssessmentRecordVO getRecord(Long assessmentId) {
        Assessment assessment = assessmentMapper.selectById(assessmentId);
        if (assessment == null) {
            throw new BizException(404, "测评记录不存在");
        }
        if (ROLE_RESIDENT.equals(SecurityUtil.getRole())
                && !assessment.getResidentId().equals(SecurityUtil.getUserId())) {
            throw new BizException(403, "无权查看他人的测评记录");
        }

        Scale scale = scaleMapper.selectById(assessment.getScaleId());
        // 卷头字段直接取实体，不依赖 join 结果 —— 即便量表被删也能正常回显
        List<AssessmentRecordVO.Answer> answers = assessmentRecordMapper
                .selectAnswerRows(assessmentId).stream()
                .filter(row -> row.getItemNo() != null)   // 无明细时 LEFT JOIN 会留一行全 null
                .map(row -> AssessmentRecordVO.Answer.builder()
                        .itemNo(row.getItemNo())
                        .question(row.getQuestion())
                        .answer(row.getAnswer())
                        .score(row.getScore())
                        .crisisFlag(row.getItemCrisis())
                        .build())
                .collect(Collectors.toList());

        return AssessmentRecordVO.builder()
                .id(assessment.getId())
                .scaleId(assessment.getScaleId())
                .scaleCode(scale != null ? scale.getCode() : null)
                .scaleName(scale != null ? scale.getName() : "未知量表")
                .totalScore(assessment.getTotalScore())
                .rawLevel(assessment.getRawLevel())
                .riskLevel(assessment.getRiskLevel())
                .crisisFlag(assessment.getCrisisFlag())
                .completedAt(assessment.getCreatedAt())
                .answers(answers)
                .build();
    }

    /**
     * 工单视角的做题记录：本次测评明细 + 该居民历史记录。
     * <p>
     * 两个维度的关联都要处理：
     * - alert_record.assessment_id 指向触发本工单的那次测评（TREND 趋势类工单可为 NULL）；
     * - alert_record.resident_id 指向该居民，用于拉历史记录做纵向对比。
     * <p>
     * 访问控制由调用方 AlertController 统一收敛为 DOCTOR/ADMIN，
     * 因此这里不再按「是否本人」过滤。
     */
    public AlertAssessmentVO getAlertAssessment(Long alertId) {
        AlertRecord alert = alertRecordMapper.selectById(alertId);
        if (alert == null) {
            throw new BizException(404, "工单不存在");
        }
        Long assessmentId = alert.getAssessmentId();
        SysUser resident = sysUserMapper.selectById(alert.getResidentId());
        return AlertAssessmentVO.builder()
                .alertId(alert.getId())
                .residentId(alert.getResidentId())
                .residentName(resident == null ? null : MaskUtil.name(resident.getRealName()))
                .assessmentId(assessmentId)
                // 工单无关联测评时 record 为 null，前端只展示历史记录
                .record(assessmentId == null ? null : getRecord(assessmentId))
                .history(historyWithScale(alert.getResidentId()))
                .build();
    }

    private String buildAdvice(ScoreResult r) {
        if (r.crisis()) {
            return "检测到你可能正经历困难时期。请立即拨打心理援助热线 12356，"
                    + "或联系北京心理危机研究与干预中心 010-82951332。我们已为你转接人工支持。";
        }
        return switch (r.riskLevel()) {
            case D -> "你的测评结果显示较高风险，建议尽快通过 AI 问诊或预约医生进一步沟通。";
            case C -> "你的测评结果显示存在一定风险，建议预约医生咨询，或先进行 AI 初步问诊。";
            case B -> "你的状态总体可控，建议保持关注，可选择 AI 问诊进一步倾诉与疏导。";
            case A -> "你的状态良好，保持规律作息与积极心态。如需倾诉也可随时使用 AI 问诊。";
        };
    }

    private String toJson(Map<Long, Long> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
