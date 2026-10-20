package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.RolePermissionPo;

/** 角色权限关系基础持久化映射；复杂业务通过事务服务处理。 */
public interface RolePermissionMapper extends BaseMapper<RolePermissionPo> {}
