package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmh.psycare.entity.DoctorSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 出诊时段 Mapper。
 *
 * <p>只额外提供两条带条件的 UPDATE —— 全项目「不会超卖」和「计数不被减成负数」
 * 的保证都在这两个方法里。用注解而不是 XML：本表没有 is_deleted 列，
 * 一旦写成自定义 XML，就得记住「不要手写 is_deleted = 0」这条反向规则（硬约定 #13）。
 */
@Mapper
public interface DoctorScheduleMapper extends BaseMapper<DoctorSchedule> {

    /**
     * 占一个号：只有「还有余量」时计数才 +1。
     *
     * <p>判断与扣减在同一条语句里完成，所以两个人同时提交不会同时成功 ——
     * 第二个人的 WHERE 已经看到 {@code booked_count == capacity}，影响 0 行。
     *
     * <p>为什么不用 {@code SELECT ... FOR UPDATE} 再 {@code COUNT(*)}：
     * 那样要多一次往返、多一次潜在死锁，而「是否已满」本来就写得出 WHERE 条件。
     * 一条语句同时完成判断和扣减，是这里最省心也最难写错的写法。
     *
     * @return 1 占号成功；0 表示已满（或时段刚被删）
     */
    @Update("UPDATE doctor_schedule SET booked_count = booked_count + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND booked_count < capacity")
    int tryBook(@Param("id") Long id);

    /**
     * 退一个号。
     *
     * <p>{@code booked_count > 0} 是刻意的：重复取消时影响 0 行，而不是把计数减成负数。
     * 减成负数之后界面上会显示「剩 5 个 / 上限 3」，而且没有任何日志能指出是哪次取消造成的。
     *
     * @return 1 退号成功；0 表示已经没有可退的号
     */
    @Update("UPDATE doctor_schedule SET booked_count = booked_count - 1, updated_at = NOW() "
            + "WHERE id = #{id} AND booked_count > 0")
    int release(@Param("id") Long id);
}
