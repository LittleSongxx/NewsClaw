package vip.newsclaw.workflow.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.workflow.model.WorkflowRunEntity;

@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRunEntity> {
}
