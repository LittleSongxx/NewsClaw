package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.news.feedback.AiNewsFeedbackEntity;

/** Persistence mapper kept under the application-wide repository scan. */
@Mapper
public interface AiNewsFeedbackMapper extends BaseMapper<AiNewsFeedbackEntity> {
}
