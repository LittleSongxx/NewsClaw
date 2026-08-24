package vip.newsclaw.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * NewsClaw 项目文档读取工具
 * 允许 Agent 在运行时读取内置项目文档（classpath:docs/ 下的 Markdown 文件）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsClawDocTool {

    private final NewsClawDocService docService;

    @Tool(description = """
        Read NewsClaw project documentation.
        Use this tool to look up the current AI-news workflow, configuration, and operational boundaries.

        Parameters:
        - action: "list" to list all available doc files, "read" to read a specific doc
        - path: (required when action="read") Relative path like "zh/config.md" or "en/quickstart.md"

        Returns: For "list", a list of available doc files grouped by language.
                 For "read", the full markdown content of the specified doc.
        """)
    public String readNewsClawDoc(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Action to perform: 'list' or 'read'")
        String action,

        @JsonProperty
        @JsonPropertyDescription("Doc path relative to docs/, e.g. 'zh/config.md' or 'en/quickstart.md'. Required when action='read'.")
        String path
    ) {
        if ("list".equalsIgnoreCase(action)) {
            return listDocs();
        } else if ("read".equalsIgnoreCase(action)) {
            return docService.readRawForTool(path);
        } else {
            return "Error: Unknown action '" + action + "'. Use 'list' or 'read'.";
        }
    }

    private String listDocs() {
        StringBuilder sb = new StringBuilder();
        sb.append("NewsClaw Documentation\n\n");

        sb.append("## 中文文档 (zh/)\n");
        appendGroup(sb, "zh");

        sb.append("\n## English Docs (en/)\n");
        appendGroup(sb, "en");

        sb.append("\nUse readNewsClawDoc(action=\"read\", path=\"zh/ai-news-ops.md\") to read a specific doc.");
        return sb.toString();
    }

    private void appendGroup(StringBuilder sb, String lang) {
        List<NewsClawDocService.DocMeta> docs = docService.list(lang);
        if (docs.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (NewsClawDocService.DocMeta doc : docs) {
            sb.append("  - ").append(lang).append('/').append(doc.slug()).append(".md\n");
        }
    }
}
