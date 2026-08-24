package vip.newsclaw.llm.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.llm.model.ModelProviderEntity;

@Mapper
public interface ModelProviderMapper extends BaseMapper<ModelProviderEntity> {
}
