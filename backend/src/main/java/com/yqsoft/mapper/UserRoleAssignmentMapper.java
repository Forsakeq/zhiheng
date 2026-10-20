package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.UserRoleAssignmentPo;

/** 账号角色分配基础持久化映射；复杂业务通过事务服务处理。 */
public interface UserRoleAssignmentMapper extends BaseMapper<UserRoleAssignmentPo> {}
