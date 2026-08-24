package vip.newsclaw.task.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.task.model.AsyncTaskEntity;

/**
 * 异步任务 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTaskEntity> {
}
