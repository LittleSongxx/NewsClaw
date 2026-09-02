package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.news.model.AiNewsDiscoveryRunEntity;

@Mapper
public interface AiNewsDiscoveryRunMapper extends BaseMapper<AiNewsDiscoveryRunEntity> {

    @Select("SELECT snapshot_json FROM mate_ai_news_discovery_run "
            + "WHERE id = #{id} AND deleted = 0")
    String selectSnapshotJson(@Param("id") Long id);
}
