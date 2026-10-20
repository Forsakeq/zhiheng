package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.AiDraftPo;

/** 可应用 AI 草稿基础持久化映射；复杂业务通过事务服务处理。 */
public interface AiDraftMapper extends BaseMapper<AiDraftPo> {}
