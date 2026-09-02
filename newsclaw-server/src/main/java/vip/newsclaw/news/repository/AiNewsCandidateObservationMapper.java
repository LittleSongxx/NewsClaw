package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.news.model.AiNewsCandidateObservationEntity;
import vip.newsclaw.news.model.AiNewsProviderYieldRow;

import java.util.List;

@Mapper
public interface AiNewsCandidateObservationMapper
        extends BaseMapper<AiNewsCandidateObservationEntity> {

    @Select("""
            SELECT provider_id,
                   COUNT(DISTINCT candidate_id) AS candidateCount,
                   COUNT(DISTINCT CASE WHEN selected = TRUE THEN candidate_id END) AS selectedCount,
                   COUNT(DISTINCT CASE WHEN provider_total = 1 THEN candidate_id END) AS marginalUniqueCount
              FROM (
                    SELECT o.provider_id, o.candidate_id, o.selected,
                           (SELECT COUNT(DISTINCT p.provider_id)
                              FROM mate_ai_news_candidate_observation p
                             WHERE p.scan_run_id = o.scan_run_id
                               AND p.candidate_id = o.candidate_id
                               AND p.deleted = 0) AS provider_total
                      FROM mate_ai_news_candidate_observation o
                     WHERE o.scan_run_id = #{scanRunId} AND o.deleted = 0
                   ) provider_rows
             GROUP BY provider_id
             ORDER BY provider_id
            """)
    List<AiNewsProviderYieldRow> selectProviderYields(@Param("scanRunId") Long scanRunId);

    @Select("SELECT DISTINCT candidate_id FROM mate_ai_news_candidate_observation "
            + "WHERE scan_run_id = #{scanRunId} AND deleted = 0")
    List<Long> selectCandidateIds(@Param("scanRunId") Long scanRunId);

    @Select("SELECT DISTINCT candidate_id FROM mate_ai_news_candidate_observation "
            + "WHERE scan_run_id = #{scanRunId} AND selected = TRUE AND deleted = 0")
    List<Long> selectSelectedCandidateIds(@Param("scanRunId") Long scanRunId);

    @Select("SELECT DISTINCT scan_run_id FROM mate_ai_news_candidate_observation "
            + "WHERE candidate_id = #{candidateId} AND deleted = 0")
    List<Long> selectScanRunIds(@Param("candidateId") Long candidateId);
}
