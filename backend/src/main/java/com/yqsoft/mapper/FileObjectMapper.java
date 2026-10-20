package com.yqsoft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yqsoft.po.FileObjectPo;

/** 私有文件与保留策略基础持久化映射；复杂业务通过事务服务处理。 */
public interface FileObjectMapper extends BaseMapper<FileObjectPo> {}
