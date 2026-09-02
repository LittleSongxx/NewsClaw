package vip.newsclaw.wiki.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import vip.newsclaw.system.featureflag.FeatureFlagService;
import vip.newsclaw.tool.builtin.DocumentExtractTool;
import vip.newsclaw.tool.image.vision.ImageVisionService;
import vip.newsclaw.wiki.WikiProperties;
import vip.newsclaw.wiki.repository.WikiRawMaterialMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WikiUploadOrphanSweepTest {

    @Test
    void removesOnlyExpiredUnreferencedManagedUpload(@TempDir Path dir) throws Exception {
        Path orphan = Files.writeString(dir.resolve("orphan.pdf"), "x");
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minusSeconds(7200)));
        WikiRawMaterialMapper mapper = mock(WikiRawMaterialMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        WikiProperties properties = new WikiProperties();
        properties.setUploadDir(dir.toString());
        properties.setUploadOrphanTtlHours(1);
        WikiRawMaterialService service = new WikiRawMaterialService(
                mapper, mock(WikiKnowledgeBaseService.class), properties,
                mock(ApplicationEventPublisher.class), mock(DocumentExtractTool.class),
                mock(WikiChunkService.class), mock(ImageVisionService.class),
                mock(PdfImageExtractor.class), mock(FeatureFlagService.class));

        assertThat(service.sweepOrphanUploadsNow()).isEqualTo(1);
        assertThat(orphan).doesNotExist();
    }
}
