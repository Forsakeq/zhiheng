package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.AiRunPo;

/** AI 运行与异步控制基础持久化映射；复杂业务通过事务服务处理。 */
public interface AiRunMapper extends BaseMapper<AiRunPo> {}
