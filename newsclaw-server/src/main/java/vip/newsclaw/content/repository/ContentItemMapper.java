package vip.newsclaw.content.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.content.model.ContentItemEntity;

/**
 * Mapper for {@link ContentItemEntity}. Must live under a {@code repository}
 * package so {@code @MapperScan("vip.newsclaw.**.repository")} registers it.
 */
@Mapper
public interface ContentItemMapper extends BaseMapper<ContentItemEntity> {
}
