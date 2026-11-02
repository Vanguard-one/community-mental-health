package com.cmh.psycare.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 医生端「按周批量排班」请求。
 *
 * <h3>为什么需要这个接口</h3>
 * 单日 {@code POST /schedule/batch} 的粒度是「一天」，而医生的真实安排是
 * 「周一到周五，上午三个号、下午两个号，一直排到下下周」。用单日接口完成这件事
 * 要发 10 次请求、改 10 次日期，中途漏一天就是那天没有号源 —— 而且页面不会提醒。
 *
 * <p>本接口只做「把同一套时段模板铺到若干个周几上」，不引入「周期排班表」：
 * 库里的 {@code doctor_schedule} 仍然是一行一个具体日期的时段，
 * 所以居民端查询、扣号、改上限、删除全都不变。批量只是写入方式，不是新模型。
 *
 * <h3>为什么不删</h3>
 * 与单日接口一致：本次铺到的已有时段只可能「改上限」或「不动」，
 * <b>不会被删除</b>。模板里没有的时段可能是上周手工加的、里面还有人约。
 * 真要清理请走 {@code DELETE /schedule/{id}}（那条会检查已约人数）。
 */
@Data
public class WeeklyScheduleRequest {

    /**
     * 从哪天开始铺。为空取今天。
     *
     * <p>早于今天会被<b>静默收敛到今天</b>而不是报错：医生昨天打开页面、
     * 今天才点「生成」是很正常的事，报一个「不能配置过去的日期」只会让人困惑，
     * 而他想要的结果（铺未来）完全不受影响。
     */
    private LocalDate fromDate;

    /** 铺几周，1~4。默认 2 周 —— 正好覆盖医生端配置页那 14 天的日期条 */
    private Integer weekCount;

    /**
     * 铺哪几个周几。1=周一 … 7=周日，默认 {@code [1,2,3,4,5]}（工作日）。
     *
     * <p>用 1~7 而不是 {@code java.time.DayOfWeek} 名：JSON 里写
     * {@code "MONDAY"} 对前端没有额外信息量，而数字可以直接当数组下标用。
     */
    private List<@Min(value = 1, message = "周几取值为 1~7")
                 @Max(value = 7, message = "周几取值为 1~7") Integer> weekdays;

    /**
     * 已有时段是否顺带把人数上限改成模板里的值。默认 false。
     *
     * <p>默认不覆盖是刻意的：医生第二次点「生成」时，模板可能已经改了，
     * 而库里那些时段的上限也许是他昨天特意为某天调过的。
     * 覆盖只能由他明确勾选。
     *
     * <p>无论是否覆盖，都不会把上限改到小于已约人数（那会让
     * {@code booked_count > capacity}，此后该时段永远不再放号）。
     */
    private Boolean overwriteCapacity;

    /**
     * 时段模板。每天重复使用同一组。
     *
     * <p>上限 12 段：4 周 × 5 天 × 12 段已是 240 行，再多就不是「半天门诊」的形态了。
     */
    @NotEmpty(message = "至少配置一个时段")
    @Size(max = 12, message = "一次最多铺 12 个时段")
    @Valid
    private List<ScheduleSaveRequest.Slot> slots;
}
