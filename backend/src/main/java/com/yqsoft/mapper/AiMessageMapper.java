package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.AiMessagePo;

/** 会话消息与操作卡片基础持久化映射；复杂业务通过事务服务处理。 */
public interface AiMessageMapper extends BaseMapper<AiMessagePo> {}
