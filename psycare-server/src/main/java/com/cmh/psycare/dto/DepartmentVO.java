package com.cmh.psycare.dto;

import lombok.Data;

import java.util.List;

/**
 * 按科室分组后的医生目录（「医生咨询」页的一级列表）。
 *
 * <p>分组的字段是 {@code doctor.department}（如「心理科」），它是自由文本而非字典表 ——
 * 因此这里不做任何枚举校验，读出来是什么就按什么分组，
 * 只在为空时统一归到「其他」（一张没有标题的科室卡片没法点）。
 *
 * <p>为什么在 Java 侧分组而不是 SQL 里 {@code GROUP BY}：
 * 需要的是「分组 + 组内完整对象列表」，SQL 只能给出行级结果，
 * 前端拿到后仍要自己分一次 —— 那样分组规则就存在两份（前端一份、SQL 一份），
 * 而两份规则迟早会漂移。分组放服务端，前端只负责画。
 */
@Data
public class DepartmentVO {

    /** 科室名，如「心理科」；医生未填科室时为「其他」 */
    private String department;

    /** 该科室下可咨询的医生数 */
    private int doctorCount;

    /** 该科室的医生，顺序沿用 SQL 的排序（已有对话优先 → 评分 → id） */
    private List<ChatPeerVO> doctors;
}
