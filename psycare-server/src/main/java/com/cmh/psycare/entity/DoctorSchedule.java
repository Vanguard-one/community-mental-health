package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 医生出诊时段（号源）。
 *
 * <p>一行 = 某位医生某一天的一个时段，{@code capacity} 是人数上限、
 * {@code booked_count} 是已约人数。**不预生成多个号**：一个容量 3 的时段
 * 只是一行记录，成交一次就把 {@code booked_count} +1。
 *
 * <p>为什么不做「排班 + 号源」两层：那套结构的前提是按周排班、号源提前展开，
 * 换来的是并发时少一次 UPDATE。当前是单机、日均可约单量三位数，
 * 预生成会让「医生改一次上限」变成一次批量 DELETE + INSERT，
 * 而且改到一半失败就会出现「号源表与排班表不一致」这种查不出来的脏数据。
 *
 * <p><b>本实体故意没有 {@code isDeleted} 字段</b>：本库的逻辑删除是全局配置
 * （{@code logic-delete-field: isDeleted}），一旦声明，所有 select 都会自动追加
 * {@code is_deleted = 0}，而 doctor_schedule 没有这一列 —— 会直接 SQL 报错。
 *
 * <p>时段是不可变的：{@code work_date / start_time / end_time} 建好之后不允许修改，
 * 只能删了重建，而「已有预约的时段不允许删」。预约单里冗余的那三列因此永远不会过期。
 * 唯一允许改的是 {@code capacity}，且不能改到小于 {@code booked_count}。
 */
@Data
@TableName("doctor_schedule")
public class DoctorSchedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 医生 id，等于 sys_user.id */
    private Long doctorId;

    private LocalDate workDate;

    private LocalTime startTime;

    private LocalTime endTime;

    /** 该时段可接诊人数上限 */
    private Integer capacity;

    /**
     * 已约人数。
     *
     * <p>这个值只允许由 {@code DoctorScheduleMapper#tryBook} / {@code #release}
     * 两条带条件的 UPDATE 改动 —— 那是全项目唯一保证「不会超卖」的地方。
     * 任何「先查后写」的写法都会在两人同时点提交时卖出第 N+1 个号。
     *
     * <p>{@code FieldStrategy.NEVER}：更新实体时永远不带这一列。
     * 否则将来任何一处 {@code updateById(schedule)} 都会用内存里的旧值
     * 覆盖掉数据库里刚被 +1 的计数 —— 覆盖后既不报错，也不会有人发现。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer bookedCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
