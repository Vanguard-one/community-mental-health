package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 预约单（业务凭证）。
 *
 * <h3>为什么没有 isDeleted</h3>
 * 本库的逻辑删除是<strong>全局配置</strong>（{@code logic-delete-field: isDeleted}），
 * 实体只要声明该字段，{@code selectById / selectList} 就会自动追加
 * {@code is_deleted = 0}。预约单是凭证：居民要能翻到三个月前取消过的单子，
 * 医生要能核对历史就诊。挂了逻辑删除，「取消」就变成「查不到」，而且**不报错**
 * —— {@code ScaleMapper.selectByIdIncludeDeleted} 就是为了绕开同一个坑才存在的。
 * 本表不设该列，实体也不声明该字段，「取消」由 {@link #status} 表达。
 *
 * <h3>为什么 visit_date / start_time / end_time 是冗余快照</h3>
 * 医生删掉某个时段后（只有 {@code booked_count = 0} 才允许删），历史单子仍要能
 * 显示「10-25 周六 09:00-09:30」。若靠 join doctor_schedule，删完就只剩一个
 * 悬空的 schedule_id。冗余之后「我的预约」列表也不用 join 就能按日期排序。
 *
 * <h3>为什么没有 bookedFlag 字段</h3>
 * 库里有一列 {@code booked_flag TINYINT GENERATED ALWAYS AS (IF(status='BOOKED',1,NULL)) STORED}，
 * 它参与唯一索引 {@code uk_slot_resident}，用来保证「同人同时段只能有一张有效单」。
 * 生成列<strong>不可写</strong>，一旦出现在 INSERT 的列清单里就会报 3105；
 * 所以这里刻意不声明它，交给数据库自己算。
 */
@Data
@TableName("appointment")
public class Appointment {

    /** 待就诊 */
    public static final String STATUS_BOOKED = "BOOKED";

    /** 已取消 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 预约单号，展示给居民 */
    private String appointmentNo;

    private Long residentId;

    private Long doctorId;

    /** 来源时段 id */
    private Long scheduleId;

    private LocalDate visitDate;

    private LocalTime startTime;

    private LocalTime endTime;

    /** {@link #STATUS_BOOKED} / {@link #STATUS_CANCELLED}。「已过期」是时间函数，不落库 */
    private String status;

    private String patientName;

    private String patientPhone;

    private String chiefComplaint;

    /** 知情同意版本号：心理健康场景的合规留痕，不是可选装饰 */
    private String consentVersion;

    private LocalDateTime consentAt;

    private String cancelReason;

    private LocalDateTime cancelledAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
