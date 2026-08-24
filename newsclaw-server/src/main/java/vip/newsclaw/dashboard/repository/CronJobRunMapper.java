package vip.newsclaw.dashboard.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.dashboard.model.CronJobRunEntity;

@Mapper
public interface CronJobRunMapper extends BaseMapper<CronJobRunEntity> {
}
