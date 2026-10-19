package com.cmh.psycare.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.enums.AlertStatus;
import com.cmh.psycare.common.enums.AlertType;
import com.cmh.psycare.common.enums.RiskLevel;
import com.cmh.psycare.entity.AlertRecord;
import com.cmh.psycare.entity.Assessment;
import com.cmh.psycare.mapper.AlertRecordMapper;
import com.cmh.psycare.mapper.AssessmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 预警引擎：在测评保存后触发，产出三类预警工单。
 *
 * 1. CRISIS    危机项阳性（最高优先级，强制 D）
 * 2. THRESHOLD 单次测评达到 B/C/D
 * 3. TREND     时间窗口内相邻两次测评总分涨幅超过阈值（纵向趋势）
 *
 * 幂等：同居民 + 同类型 + 仍处于 PENDING 的工单不重复创建。
 */
@Component
@RequiredArgsConstructor
public class AlertEngine {

    private final AlertRecordMapper alertRecordMapper;
    private final AssessmentMapper assessmentMapper;

    @Value("${psycare.alert.trend-score-rise:5}")
    private Integer trendScoreRise;

    @Value("${psycare.alert.trend-window-days:90}")
    private Integer trendWindowDays;

    /**
     * 评估一次测评是否产生预警，返回新建的工单列表。
     */
    public List<AlertRecord> evaluate(Assessment assessment) {
        List<AlertRecord> created = new ArrayList<>();
        Long residentId = assessment.getResidentId();

        // 1. 危机预警
        if (Integer.valueOf(1).equals(assessment.getCrisisFlag())) {
            createIfAbsent(created, residentId, assessment, AlertType.CRISIS, RiskLevel.D);
        }

        // 2. 阈值预警（B 及以上；危机已建则不重复）
        RiskLevel level = assessment.getRiskLevel();
        if (level != null && level.ordinal() >= RiskLevel.B.ordinal()
                && !Integer.valueOf(1).equals(assessment.getCrisisFlag())) {
            createIfAbsent(created, residentId, assessment, AlertType.THRESHOLD, level);
        }

        // 3. 趋势预警：窗口内相邻两次测评总分涨幅超阈值
        RiskLevel trendLevel = detectTrend(assessment);
        if (trendLevel != null) {
            createIfAbsent(created, residentId, assessment, AlertType.TREND, trendLevel);
        }
        return created;
    }

    /** 趋势检测：与上一条测评比较总分涨幅，返回应预警的等级，无则返回 null */
    private RiskLevel detectTrend(Assessment current) {
        LocalDateTime since = LocalDateTime.now().minusDays(trendWindowDays);
        List<Assessment> recent = assessmentMapper.listRecent(current.getResidentId(), since);
        // recent 按时间倒序，第一条是 current 本身
        Assessment previous = recent.stream()
                .filter(a -> !a.getId().equals(current.getId()))
                .findFirst()
                .orElse(null);
        if (previous == null) {
            return null;
        }
        int rise = current.getTotalScore() - previous.getTotalScore();
        if (rise >= trendScoreRise) {
            // 趋势恶化，按当前风险等级提升预警，至少 B
            RiskLevel level = current.getRiskLevel();
            return level.ordinal() < RiskLevel.B.ordinal() ? RiskLevel.B : level;
        }
        return null;
    }

    /** 幂等创建：存在同类型 PENDING 工单则跳过 */
    private void createIfAbsent(List<AlertRecord> created,
                                Long residentId,
                                Assessment assessment,
                                AlertType type,
                                RiskLevel level) {
        Long pending = alertRecordMapper.selectCount(
                new LambdaQueryWrapper<AlertRecord>()
                        .eq(AlertRecord::getResidentId, residentId)
                        .eq(AlertRecord::getAlertType, type)
                        .eq(AlertRecord::getStatus, AlertStatus.PENDING));
        if (pending != null && pending > 0) {
            return;
        }
        AlertRecord record = new AlertRecord();
        record.setResidentId(residentId);
        record.setAssessmentId(assessment.getId());
        record.setAlertType(type);
        record.setAlertLevel(level);
        record.setStatus(AlertStatus.PENDING);
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        alertRecordMapper.insert(record);
        created.add(record);
    }
}
