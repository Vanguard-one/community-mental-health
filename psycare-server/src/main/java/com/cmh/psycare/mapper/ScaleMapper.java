package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmh.psycare.entity.Scale;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScaleMapper extends BaseMapper<Scale> {

    /**
     * 按 id 取量表，**包含已被逻辑删除的行**。
     *
     * <p>{@code selectById} 走的是 MP 生成的 SQL，全局配置
     * {@code logic-delete-field: isDeleted} 会自动追加 {@code is_deleted = 0}，
     * 于是管理端把一张量表「删除」之后，历史测评就再也查不到它的名字与满分了。
     *
     * <p>而「历史报告」恰恰必须能回显 —— 居民三个月前做的是哪张表、满分多少，
     * 不能因为管理员今天禁用了这张表就凭空消失。这与
     * {@code AssessmentService#getRecord} 里「即便量表被删也能正常回显」是同一条原则，
     * 只是那边靠的是 XML 里手写 SQL（MP 不管），这边 BaseMapper 没有等价入口，
     * 所以显式写一条 {@code @Select}。
     *
     * <p>⚠️ 只用于**回显历史**。任何「当前可用量表」的判断都必须走
     * {@code selectById} / {@code status}，否则会把已删除的表当成可选项。
     */
    @Select("SELECT * FROM scale WHERE id = #{id}")
    Scale selectByIdIncludeDeleted(@Param("id") Long id);
}
