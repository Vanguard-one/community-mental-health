package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmh.psycare.entity.Admin;
import org.apache.ibatis.annotations.Mapper;

/** 管理员表（角色差异字段） */
@Mapper
public interface AdminMapper extends BaseMapper<Admin> {
}
