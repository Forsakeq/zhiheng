package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.AiDraftRevisionPo;

/** 草稿不可变修订基础持久化映射；复杂业务通过事务服务处理。 */
public interface AiDraftRevisionMapper extends BaseMapper<AiDraftRevisionPo> {}
