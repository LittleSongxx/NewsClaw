package vip.newsclaw.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.wiki.model.WikiHotCacheEntity;

@Mapper
public interface WikiHotCacheMapper extends BaseMapper<WikiHotCacheEntity> {
}
