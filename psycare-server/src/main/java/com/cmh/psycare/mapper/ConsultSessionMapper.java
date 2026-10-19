package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmh.psycare.dto.AiSessionVO;
import com.cmh.psycare.entity.ConsultSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConsultSessionMapper extends BaseMapper<ConsultSession> {

    /**
     * 某个居民的历史 AI 会话列表（含消息数与最后一条消息预览）。
     *
     * <p>{@code residentId} 由服务层从<b>登录态</b>取出后传入，不是请求参数 ——
     * 这样「换个 id 读别人会话」在接口层面就不可能构造出来。
     *
     * @param limit 上限，服务层给常量（不接收前端传入的任意值，避免被用来拖库）
     */
    List<AiSessionVO> selectAiSessionList(@Param("residentId") Long residentId,
                                         @Param("limit") int limit);
}
