package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.UserSyncClockPo;

/** 每用户已提交同步时钟基础持久化映射；复杂业务通过事务服务处理。 */
public interface UserSyncClockMapper extends BaseMapper<UserSyncClockPo> {}
