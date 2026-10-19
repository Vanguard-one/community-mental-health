package com.cmh.psycare.engine;

import com.cmh.psycare.common.enums.RiskLevel;

/**
 * 计分引擎输出
 *
 * @param totalScore 总分
 * @param rawLevel   原始等级（按量表切分，如 中度）
 * @param riskLevel  系统风险等级 A/B/C/D（危机项阳性强制 D）
 * @param crisis     是否危机项阳性
 */
public record ScoreResult(int totalScore, String rawLevel, RiskLevel riskLevel, boolean crisis) {
}
