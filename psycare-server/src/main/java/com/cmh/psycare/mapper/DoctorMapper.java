package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmh.psycare.entity.Doctor;
import org.apache.ibatis.annotations.Mapper;

/** 医生表（角色差异字段 + 资质审核） */
@Mapper
public interface DoctorMapper extends BaseMapper<Doctor> {
}
