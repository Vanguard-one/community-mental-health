package com.cmh.psycare.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风险等级分布项。
 *
 * 固定返回 A/B/C/D 四项（个数为 0 的也返回），前端可直接映射环形图四段，
 * 无需自行补零或排序。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskCount {

    /** 风险等级 A/B/C/D */
    private String riskLevel;

    /** 该等级的测评数 */
    private Long count;
}
