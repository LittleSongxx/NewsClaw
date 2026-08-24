package vip.mate.skill.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 技能目录扫描器
 * 扫描 references/ 和 scripts/ 目录树
 */
@Slf4j
@Component
public class SkillDirectoryScanner {

    /**
     * 构建目录树结构
     * 文件 -> {filename: null}
     * 目录 -> {dirname: {nested}}
     */
    public Map<String, Object> buildDirectoryTree(Path directory) {
        Map<String, Object> tree = new HashMap<>();

        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return tree;
        }

        try (Stream<Path> stream = Files.list(directory)) {
            stream.sorted().forEach(item -> {
                String name = item.getFileName().toString();
                if (Files.isRegularFile(item)) {
                    tree.put(name, null);
                } else if (Files.isDirectory(item)) {
                    tree.put(name, buildDirectoryTree(item));
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan directory {}: {}", directory, e.getMessage());
        }

        return tree;
    }
}
