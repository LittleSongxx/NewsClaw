package vip.newsclaw.datasource.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.datasource.model.DatasourceEntity;

/**
 * 数据源 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface DatasourceMapper extends BaseMapper<DatasourceEntity> {
}
