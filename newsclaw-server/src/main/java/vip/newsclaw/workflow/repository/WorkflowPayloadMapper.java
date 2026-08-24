package vip.newsclaw.workflow.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.workflow.model.WorkflowPayloadEntity;

@Mapper
public interface WorkflowPayloadMapper extends BaseMapper<WorkflowPayloadEntity> {
}
