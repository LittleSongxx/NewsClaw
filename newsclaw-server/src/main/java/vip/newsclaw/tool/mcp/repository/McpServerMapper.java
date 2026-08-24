package vip.newsclaw.tool.mcp.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.tool.mcp.model.McpServerEntity;

/**
 * MCP Server Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface McpServerMapper extends BaseMapper<McpServerEntity> {
}
