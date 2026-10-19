package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.enums.RiskLevel;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.RecommendResult;
import com.cmh.psycare.engine.RecommendationEngine;
import com.cmh.psycare.entity.Assessment;
import com.cmh.psycare.entity.ConsultSession;
import com.cmh.psycare.entity.Scale;
import com.cmh.psycare.mapper.AssessmentMapper;
import com.cmh.psycare.mapper.ConsultSessionMapper;
import com.cmh.psycare.mapper.ScaleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 医生推荐服务：取测评的等级与维度，调用推荐引擎。
 *
 * 取哪一次测评的规则：**优先会话关联的那次**（AI 问诊通常是从某次测评进来的），
 * 没有会话时才退化为该居民最近一次测评。
 * 若一律用「最近一次」，会出现「带着危机测评进入问诊，却按更晚的一次低风险测评
 * 推荐全科医生」这类自相矛盾的结果。
 */
@Service
@RequiredArgsConstructor
public class RecommendService {

    private final RecommendationEngine recommendationEngine;
    private final AssessmentMapper assessmentMapper;
    private final ScaleMapper scaleMapper;
    private final ConsultSessionMapper consultSessionMapper;

    /**
     * 基于测评结果推荐医生，<b>并记一条推荐日志</b>。
     *
     * <p>面向「用户看得见的那次推荐」（右侧栏、推荐页）。
     *
     * @param sessionId 来源会话（AI 问诊触发推荐时传入），可空
     * @param topN      返回条数
     */
    public List<RecommendResult> recommendForCurrentResident(Long sessionId, int topN) {
        return doRecommend(sessionId, topN, true);
    }

    /**
     * 只算不记：供 **AI 对话的上下文注入** 使用（L5b 医生名单）。
     *
     * <p>对话每轮都要一份名单，若照常写 {@code recommendation_log}，
     * 同一份推荐会在表里重复几十次，把「推荐了什么、后来有没有被接受」这个
     * 统计口径彻底稀释掉。注进 prompt 的只是**素材**，不是一次推荐事件。
     */
    public List<RecommendResult> recommendForContext(Long sessionId, int topN) {
        return doRecommend(sessionId, topN, false);
    }

    private List<RecommendResult> doRecommend(Long sessionId, int topN, boolean record) {
        Long residentId = SecurityUtil.getUserId();
        Assessment target = resolveTargetAssessment(sessionId, residentId);

        RiskLevel level = target != null ? target.getRiskLevel() : RiskLevel.A;
        // 主维度取自测评所用量表的测量维度（如 抑郁/焦虑/压力），
        // 它是「专长标签匹配」的输入，传 null 会让该维度加成与对应推荐理由永久失效
        String dimension = null;
        if (target != null && target.getScaleId() != null) {
            Scale scale = scaleMapper.selectById(target.getScaleId());
            if (scale != null) {
                dimension = scale.getDimension();
            }
        }
        return recommendationEngine.recommend(residentId, sessionId, level, dimension, topN, record);
    }

    /** 会话关联测评优先，其次该居民最近一次测评 */
    private Assessment resolveTargetAssessment(Long sessionId, Long residentId) {
        if (sessionId != null) {
            ConsultSession session = consultSessionMapper.selectById(sessionId);
            if (session != null && session.getAssessmentId() != null) {
                Assessment linked = assessmentMapper.selectById(session.getAssessmentId());
                if (linked != null) {
                    return linked;
                }
            }
        }
        return assessmentMapper.selectList(
                        new LambdaQueryWrapper<Assessment>()
                                .eq(Assessment::getResidentId, residentId)
                                .orderByDesc(Assessment::getCreatedAt)
                                .last("limit 1"))
                .stream().findFirst().orElse(null);
    }
}
