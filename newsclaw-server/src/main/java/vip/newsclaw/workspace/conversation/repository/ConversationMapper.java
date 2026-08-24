package vip.newsclaw.workspace.conversation.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.workspace.conversation.model.ConversationEntity;

/**
 * 会话 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}
