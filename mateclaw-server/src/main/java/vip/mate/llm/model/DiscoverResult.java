package vip.mate.llm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiscoverResult {
    private List<ModelInfoDTO> discoveredModels;
    private List<ModelInfoDTO> newModels;
    private int totalDiscovered;
    private int newCount;
}
