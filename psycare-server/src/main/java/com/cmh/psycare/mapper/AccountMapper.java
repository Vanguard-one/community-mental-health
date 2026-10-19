package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cmh.psycare.dto.AccountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 管理端账号治理的读模型。
 * <p>
 * 为什么需要 XML 而不是只用 MyBatis-Plus：
 * 列表要一次性带上「账号共性字段（sys_user）」+「角色差异字段（三张角色表）」，
 * 是典型的跨表读模型，用 wrapper 拼不出来。
 */
@Mapper
public interface AccountMapper {

    /**
     * 账号分页列表。
     * <p>
     * 靠 sys_user.role 区分类型，三张角色表都用 LEFT JOIN 挂上 ——
     * 因为角色表主键 = sys_user.id 且各角色 id 子集互不重叠，
     * 一次 join 就够了，**不需要 UNION 三条分支**。
     */
    IPage<AccountVO> selectAccountPage(IPage<AccountVO> page,
                                       @Param("userType") String userType,
                                       @Param("status") Integer status,
                                       @Param("auditStatus") String auditStatus,
                                       @Param("keyword") String keyword);

    /** 待审核医生数（管理端菜单角标用），无待审核时返回 0 */
    Integer selectPendingAuditCount();
}
