package vip.mate.dashboard.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.dashboard.model.CronJobRunEntity;

@Mapper
public interface CronJobRunMapper extends BaseMapper<CronJobRunEntity> {
}
