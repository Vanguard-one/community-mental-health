package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cmh.psycare.dto.ResidentInfoVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 医生端「用户信息」读模型。
 * <p>
 * 为什么需要 XML：一行要同时带上「sys_user 基础字段 + user 档案字段 +
 * assessment 聚合统计 + 当前医生的会话 id」，是典型跨表读模型，
 * wrapper 拼不出来。
 */
@Mapper
public interface ResidentInfoMapper {

    /**
     * 居民分页列表。
     *
     * @param doctorId 当前登录医生，用于带出「与该医生已存在的会话 id」；
     *                 传 null 则不查会话（仅用于管理端复用场景）
     * @param keyword  可选，按姓名或登录名模糊匹配
     */
    IPage<ResidentInfoVO> selectResidentPage(IPage<ResidentInfoVO> page,
                                             @Param("doctorId") Long doctorId,
                                             @Param("keyword") String keyword);
}
