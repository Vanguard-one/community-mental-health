package com.cmh.psycare.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量排班的结果。
 *
 * <p>分开给 {@code created / updated / skipped} 而不是只给一个「成功」：
 * 医生点了「生成」之后最需要知道的是「为什么只有 8 天有号而不是 10 天」。
 * 只回一个布尔值的话，他只能一天一天点开日期条去找。
 *
 * <p><b>skipped 的两种含义在这个接口里合并计数</b>：① 该时段今天已经过了；
 * ② 库里已有该时段，且没有勾选「覆盖上限」（或上限本来就相同）。
 * 两者对医生而言都是「这段没有新增号源」，而前端提示只需要一个总数。
 */
@Data
public class WeeklyScheduleResultVO {

    /** 实际有增改的日期数（不是遍历到的天数） */
    private int dayCount;

    /** 新建的时段数 */
    private int created;

    /** 上限被改掉的时段数 */
    private int updated;

    /** 跳过未处理的时段数 */
    private int skipped;

    /** 实际有增改的日期，"2026-10-26" 形态，按时间升序 */
    private List<String> dates = new ArrayList<>();

    /**
     * 一句可直接展示的结论。
     *
     * <p>拼在服务端而不是前端：三个数字的组合语义（全跳过 = 已经排过了、
     * 部分跳过 = 今天已过）只有这里最清楚，前端再拼一次就要重复这套判断。
     */
    public String getSummary() {
        if (created == 0 && updated == 0) {
            return skipped == 0
                    ? "所选日期内没有需要排班的日期"
                    : "这些时段已经排过了，本次没有变化（跳过 " + skipped + " 段）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已为 ").append(dayCount).append(" 天生成号源：新增 ")
          .append(created).append(" 段");
        if (updated > 0) {
            sb.append("、更新上限 ").append(updated).append(" 段");
        }
        if (skipped > 0) {
            sb.append("、跳过 ").append(skipped).append(" 段");
        }
        return sb.toString();
    }
}
