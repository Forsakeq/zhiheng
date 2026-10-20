package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.FeedbackTicketPo;

/** 用户反馈工单基础持久化映射；复杂业务通过事务服务处理。 */
public interface FeedbackTicketMapper extends BaseMapper<FeedbackTicketPo> {}
