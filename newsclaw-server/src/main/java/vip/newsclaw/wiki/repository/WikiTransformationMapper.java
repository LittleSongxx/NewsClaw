package vip.newsclaw.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.wiki.model.WikiTransformationEntity;

@Mapper
public interface WikiTransformationMapper extends BaseMapper<WikiTransformationEntity> {
}
