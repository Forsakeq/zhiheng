package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.PrivacyRequestPo;

/** 导出、删除预览与后台工单基础持久化映射；复杂业务通过事务服务处理。 */
public interface PrivacyRequestMapper extends BaseMapper<PrivacyRequestPo> {}
