package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmh.psycare.entity.User;
import org.apache.ibatis.annotations.Mapper;

/** 居民用户表（角色差异字段） */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
