package com.cmh.psycare.mapper;

import com.cmh.psycare.dto.TrendPoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 驾驶舱聚合查询。
 *
 * 只有「按天分组」这类无法用 MyBatis-Plus 条件构造器表达的查询放在这里（走 XML），
 * 普通计数一律用各实体自带的 BaseMapper.selectCount，避免 SQL 与实体定义两处维护。
 */
@Mapper
public interface AdminDashboardMapper {

    /**
     * 指定时间点之后的按天测评量 + 平均分。
     * 无测评的日期不会出现，由 AdminService 补零。
     */
    List<TrendPoint> selectDailyTrend(@Param("since") LocalDateTime since);
}
