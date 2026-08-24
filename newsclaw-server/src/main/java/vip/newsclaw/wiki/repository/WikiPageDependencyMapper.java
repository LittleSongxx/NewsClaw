package vip.newsclaw.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.wiki.model.WikiPageDependencyEntity;

/**
 * Mapper for {@link WikiPageDependencyEntity}.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface WikiPageDependencyMapper extends BaseMapper<WikiPageDependencyEntity> {
}
