package com.cmh.psycare.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.enums.RiskLevel;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.entity.Scale;
import com.cmh.psycare.entity.ScaleItem;
import com.cmh.psycare.entity.ScaleOption;
import com.cmh.psycare.entity.ScoringRule;
import com.cmh.psycare.mapper.ScoringRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 计分与风险分级引擎。
 *
 * <h3>风险分级模型：总分定基线 + 危机项覆写</h3>
 * 这是本项目判定「高风险」的<b>唯一口径</b>，两个维度互相独立：
 *
 * <ol>
 *   <li><b>总分基线</b>：按 {@code scoring_rule} 的量表学段把总分映射到 A/B/C/D。
 *       切分点全部入库，管理端可调，不写死在代码里；</li>
 *   <li><b>危机项覆写</b>：量表里标记 {@code crisis_flag=1} 的题（如 PHQ-9 第 9 题
 *       「有不如死掉或用某种方式伤害自己的念头」）一旦<b>得分 &gt; 0</b>，
 *       风险等级强制抬到 {@code crisisLevel}（默认 D，可配）。
 *       覆写是<b>单向抬升</b>，永不降低等级。</li>
 * </ol>
 *
 * <h3>为什么危机项要「覆写」而不是「参与加总」</h3>
 * 因为两者的临床含义完全不同。第 9 题阳性意味着<b>存在自伤/自杀观念</b>，
 * 这是独立的危险信号，不是「抑郁程度再加几分」。如果把它的分值混进总分，
 * 一个第 9 题答「好几天」（+1 分）、其余全答「完全不会」的人总分只有 1 分，
 * 会被分到「无抑郁」—— 显然荒谬。
 *
 * <h3>为什么覆写只抬升、且要有单独的 crisis 标记</h3>
 * 覆写保证「危急信号不会被总分稀释」；而 {@code crisis} 这个布尔值单独返回，
 * 是为了让预警引擎能建<b>CRISIS 工单</b>（最高优先级、立即人工介入），
 * 与普通的 THRESHOLD 阈值工单区分开。一个 20 分的重度抑郁若第 9 题为 0，
 * 不该被当成危机事件处理 —— 两种工单的响应时效要求不同。
 *
 * <h3>反向计分</h3>
 * 按「该题最大选项分 − 选项分」折算，用于积极取向的题目。
 */
@Component
@RequiredArgsConstructor
public class ScoringEngine {

    /**
     * 危机项阳性时的强制等级。
     *
     * <p>做成常量而不是散落在逻辑里，是因为它同时被
     * {@code ScoreResult.riskLevel} 与前端文案引用，
     * 且「危机项究竟强制到哪一级」是产品决策而非实现细节。
     */
    private static final RiskLevel CRISIS_LEVEL = RiskLevel.D;

    private final ScoringRuleMapper scoringRuleMapper;

    /**
     * @param scale      量表
     * @param items      量表全部题目
     * @param allOptions 量表全部选项（用于反向计分求每题最大分）
     * @param submitted  用户提交：itemId -> optionId
     */
    public ScoreResult score(Scale scale,
                             List<ScaleItem> items,
                             List<ScaleOption> allOptions,
                             Map<Long, Long> submitted) {
        Map<Long, ScaleItem> itemMap = items.stream()
                .collect(Collectors.toMap(ScaleItem::getId, i -> i));
        Map<Long, ScaleOption> optionMap = allOptions.stream()
                .collect(Collectors.toMap(ScaleOption::getId, o -> o));
        // 每题最大选项分（反向计分用）
        Map<Long, Integer> itemMaxScore = allOptions.stream()
                .collect(Collectors.groupingBy(ScaleOption::getItemId,
                        Collectors.mapping(ScaleOption::getScore,
                                Collectors.collectingAndThen(Collectors.maxBy(Integer::compareTo), o -> o.orElse(0)))));

        int total = 0;
        boolean crisis = false;
        for (Map.Entry<Long, Long> e : submitted.entrySet()) {
            ScaleItem item = itemMap.get(e.getKey());
            ScaleOption option = optionMap.get(e.getValue());
            if (item == null || option == null) {
                throw new BizException(400, "作答包含无效题目或选项");
            }
            int s = option.getScore() == null ? 0 : option.getScore();
            if (Integer.valueOf(1).equals(item.getReverseScore())) {
                s = itemMaxScore.getOrDefault(item.getId(), 0) - s;
            }
            total += s;
            // 危机项：只要计分后 > 0 即为阳性。
            // 用折算后的 s 而不是原始 option.getScore()，是为了让「反向标记的危机题」
            // 也能被正确识别（虽然现实中不会这么配，但规则应自洽而非依赖约定）。
            if (Integer.valueOf(1).equals(item.getCrisisFlag()) && s > 0) {
                crisis = true;
            }
        }

        ScoringRule matched = matchRule(scale.getId(), total);

        // 基线由总分决定；危机项阳性时单向抬升到 CRISIS_LEVEL。
        // 用 ordinal 比较而不是直接赋值：将来若把 CRISIS_LEVEL 调成 C，
        // 一个总分为重度（已是 D）的作答不该被「降级」回 C。
        RiskLevel baseline = RiskLevel.valueOf(matched.getRiskLevel());
        RiskLevel finalLevel = (crisis && CRISIS_LEVEL.ordinal() > baseline.ordinal())
                ? CRISIS_LEVEL
                : baseline;

        return new ScoreResult(total, matched.getRawLevel(), finalLevel, crisis);
    }

    /** 按总分命中学段规则（区间闭 [minScore, maxScore]，按 sort 升序取第一个命中） */
    private ScoringRule matchRule(Long scaleId, int total) {
        List<ScoringRule> rules = scoringRuleMapper.selectList(
                new LambdaQueryWrapper<ScoringRule>()
                        .eq(ScoringRule::getScaleId, scaleId)
                        .orderByAsc(ScoringRule::getSort));
        return rules.stream()
                .filter(r -> total >= r.getMinScore() && total <= r.getMaxScore())
                .min(Comparator.comparing(ScoringRule::getSort))
                .orElseThrow(() -> new BizException("量表[" + scaleId + "]未配置覆盖总分 " + total + " 的切分规则"));
    }
}
