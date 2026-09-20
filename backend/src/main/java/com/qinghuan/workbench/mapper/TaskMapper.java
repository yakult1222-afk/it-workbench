package com.qinghuan.workbench.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qinghuan.workbench.entity.Task;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {
}
