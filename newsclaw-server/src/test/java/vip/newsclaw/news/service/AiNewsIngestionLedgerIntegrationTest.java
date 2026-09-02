package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.news.model.AiNewsIngestionRunItemEntity;
import vip.newsclaw.news.model.AiNewsRawCaptureEntity;
import vip.newsclaw.news.model.AiNewsSourceItemVersionEntity;
import vip.newsclaw.news.repository.AiNewsIngestionRunItemMapper;
import vip.newsclaw.news.repository.AiNewsIngestionRunMapper;
import vip.newsclaw.news.repository.AiNewsRawCaptureMapper;
import vip.newsclaw.news.repository.AiNewsSourceEndpointMapper;
import vip.newsclaw.news.repository.AiNewsSourceItemVersionMapper;
import vip.newsclaw.news.source.NewsSourceChannel;
import vip.newsclaw.news.source.NewsSourceEndpointDescriptor;
import vip.newsclaw.news.source.NewsSourcePollBatch;
import vip.newsclaw.news.source.NewsSourceProvenance;
import vip.newsclaw.news.source.NewsSourceResult;
import vip.newsclaw.news.source.NewsSourceTransportRecord;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:ai_news_ingestion_ledger;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "newsclaw.ai-news.ingestion.enabled=false"
})
@Transactional
class AiNewsIngestionLedgerIntegrationTest {

    @Autowired
    private AiNewsIngestionLedgerService ledger;
    @Autowired
    private AiNewsRawCaptureMapper rawCaptureMapper;
    @Autowired
    private AiNewsSourceItemVersionMapper versionMapper;
    @Autowired
    private AiNewsIngestionRunItemMapper runItemMapper;
    @Autowired
    private AiNewsIngestionRunMapper runMapper;
    @Autowired
    private AiNewsSourceEndpointMapper endpointMapper;
    @Autowired
    private AiNewsIngestionAdminService adminService;
    @Autowired
    private AiNewsSourceTimeAttestationService timeAttestations;

    @Test
    void persistsFirstVersionThenRecordsNotModifiedObservationWithoutVersionChurn() {
        NewsSourceEndpointDescriptor descriptor = descriptor("ledger-feed", "metadata_only",
                "review_required");
        var endpoint = ledger.syncEndpoint(descriptor);
        assertTrue(ledger.isDue(endpoint, Instant.now()));
        Instant claimTime = Instant.now();
        assertTrue(ledger.claimDue(endpoint, claimTime, Duration.ofMinutes(30)));
        assertFalse(ledger.claimDue(endpoint, claimTime, Duration.ofMinutes(30)),
                "only one scheduler/request thread may lease the same due endpoint");
        var leaseUntil = endpoint.getNextPollAt();

        Instant firstStarted = Instant.parse("2026-08-27T08:00:00Z");
        byte[] feedBody = "<rss>first representation</rss>".getBytes(StandardCharsets.UTF_8);
        NewsSourceResult firstResult = result(false);
        var firstRun = ledger.startRun(endpoint, descriptor, "scheduled");
        assertEquals(leaseUntil, endpoint.getNextPollAt(),
                "starting a run must not shorten the endpoint lease");
        NewsSourceTransportRecord firstTransport = new NewsSourceTransportRecord(
                descriptor.url(), descriptor.url(), 200, "application/rss+xml", "\"v1\"",
                "Wed, 27 Aug 2026 08:00:00 GMT", "", (long) feedBody.length,
                feedBody, false, false, firstStarted, firstStarted.plusMillis(125), "", "");
        NewsSourcePollBatch firstBatch = new NewsSourcePollBatch(descriptor,
                NewsSourcePollBatch.Status.SUCCESS, firstStarted, firstStarted.plusMillis(150),
                List.of(firstResult, duplicateResult()), List.of(firstTransport), "", "");

        AiNewsIngestionLedgerService.Completion first =
                ledger.completeRun(firstRun, endpoint, firstBatch);
        assertEquals(1, first.itemCount());
        assertEquals(1, first.newItemCount());
        assertEquals(1, first.newVersionCount());
        assertEquals(0, first.unchangedItemCount());
        assertEquals("\"v1\"", endpoint.getEtag());
        assertFalse(ledger.isDue(endpoint, firstStarted.plusSeconds(1)));

        List<AiNewsRawCaptureEntity> firstCaptures = rawCaptureMapper.selectList(
                new LambdaQueryWrapper<AiNewsRawCaptureEntity>()
                        .eq(AiNewsRawCaptureEntity::getIngestionRunId, firstRun.getId()));
        assertEquals(1, firstCaptures.size());
        assertEquals("metadata_only", firstCaptures.get(0).getRetentionApplied());
        assertNotNull(firstCaptures.get(0).getRepresentationDigest());
        assertNull(firstCaptures.get(0).getRawBody());
        AiNewsSourceItemVersionEntity metadataOnlyVersion = versionMapper.selectList(
                        new LambdaQueryWrapper<AiNewsSourceItemVersionEntity>())
                .getFirst();
        assertNull(metadataOnlyVersion.getContent(),
                "metadata_only endpoints must not persist feed/article content");
        assertEquals(1L, adminService.endpoints(1, 20, "rss", true).getTotal());
        assertEquals(1L, adminService.runs(1, 20, endpoint.getId(), "success").getTotal());
        AiNewsIngestionAdminService.RunInspection inspection =
                adminService.inspectRun(firstRun.getId());
        assertEquals(1, inspection.items().size());
        assertEquals("A durable AI release", inspection.items().getFirst().getTitle());
        assertEquals(1, inspection.captures().size());
        assertFalse(Boolean.TRUE.equals(inspection.captures().getFirst().getBodyRetained()));
        assertNotNull(inspection.captures().getFirst().getRepresentationDigest());

        var secondRun = ledger.startRun(endpoint, descriptor, "scheduled");
        Instant secondStarted = firstStarted.plusSeconds(900);
        NewsSourceTransportRecord secondTransport = new NewsSourceTransportRecord(
                descriptor.url(), descriptor.url(), 304, "", "\"v1\"",
                "Wed, 27 Aug 2026 08:00:00 GMT", "", 0L, new byte[0], false,
                true, secondStarted, secondStarted.plusMillis(30), "", "");
        NewsSourcePollBatch secondBatch = new NewsSourcePollBatch(descriptor,
                NewsSourcePollBatch.Status.NOT_MODIFIED, secondStarted,
                secondStarted.plusMillis(35), List.of(result(true)), List.of(secondTransport),
                "", "");
        AiNewsIngestionLedgerService.Completion second =
                ledger.completeRun(secondRun, endpoint, secondBatch);

        assertEquals(0, second.newItemCount());
        assertEquals(0, second.newVersionCount());
        assertEquals(1, second.unchangedItemCount());
        assertEquals(1L, versionMapper.selectCount(
                new LambdaQueryWrapper<AiNewsSourceItemVersionEntity>()));
        assertEquals(2L, runItemMapper.selectCount(
                new LambdaQueryWrapper<AiNewsIngestionRunItemEntity>()));
        AiNewsRawCaptureEntity revalidated = rawCaptureMapper.selectOne(
                new LambdaQueryWrapper<AiNewsRawCaptureEntity>()
                        .eq(AiNewsRawCaptureEntity::getIngestionRunId, secondRun.getId()));
        assertNotNull(revalidated.getRevalidatedFromCaptureId());
        assertEquals(firstCaptures.get(0).getRepresentationDigest(),
                revalidated.getRepresentationDigest());

        List<NewsSourceResult> latest = ledger.recentResults(
                Instant.parse("2026-08-27T00:00:00Z"), 10);
        assertEquals(1, latest.size());
        assertEquals("A durable AI release", latest.get(0).title());
        assertEquals("2026-08-27T08:05:00Z",
                latest.get(0).provenance().metadata().get("publishedAt"));
    }

    @Test
    void lateCompletionCannotResurrectAnAbandonedRunOrItsLease() {
        NewsSourceEndpointDescriptor descriptor = descriptor("cas-feed", "metadata_only",
                "review_required");
        var endpoint = ledger.syncEndpoint(descriptor);
        var run = ledger.startRun(endpoint, descriptor, "scheduled");

        // Make the persisted owner old enough for reconciliation and align the
        // endpoint cursor with that owner, as it would be after a crash.
        LocalDateTime staleAt = LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
        run.setStartedAt(staleAt);
        runMapper.updateById(run);
        endpoint.setLastAttemptAt(staleAt);
        endpoint.setNextPollAt(staleAt);
        endpointMapper.updateById(endpoint);

        assertEquals(1, ledger.abandonStaleRuns(Duration.ofMinutes(1)));
        assertEquals("abandoned", runMapper.selectById(run.getId()).getRunStatus());

        NewsSourcePollBatch lateBatch = new NewsSourcePollBatch(descriptor,
                NewsSourcePollBatch.Status.SUCCESS, Instant.now(), Instant.now(),
                List.of(), List.of(), "", "");
        assertThrows(IllegalStateException.class,
                () -> ledger.completeRun(run, endpoint, lateBatch));
        assertEquals("abandoned", runMapper.selectById(run.getId()).getRunStatus());
        assertEquals(0L, rawCaptureMapper.selectCount(new LambdaQueryWrapper<>()));
    }

    @Test
    void terminalCompletionDoesNotOverwriteANewerEndpointLease() {
        NewsSourceEndpointDescriptor descriptor = descriptor("endpoint-fence-feed", "metadata_only",
                "review_required");
        var endpoint = ledger.syncEndpoint(descriptor);
        var run = ledger.startRun(endpoint, descriptor, "scheduled");

        LocalDateTime newerAttempt = databaseTimestamp(LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(1));
        LocalDateTime newerLease = newerAttempt.plusMinutes(30);
        endpoint.setLastAttemptAt(newerAttempt);
        endpoint.setNextPollAt(newerLease);
        endpointMapper.updateById(endpoint);

        Instant started = Instant.now();
        ledger.completeRun(run, endpoint, new NewsSourcePollBatch(descriptor,
                NewsSourcePollBatch.Status.SUCCESS, started, started.plusMillis(1),
                List.of(), List.of(), "", ""));

        var persisted = endpointMapper.selectById(endpoint.getId());
        assertEquals(newerAttempt, persisted.getLastAttemptAt());
        assertEquals(newerLease, persisted.getNextPollAt());
    }

    @Test
    void startRunAbortsWhenTheEndpointWasReclaimedBetweenClaimAndStart() {
        NewsSourceEndpointDescriptor descriptor = descriptor("start-fence-feed", "metadata_only",
                "review_required");
        var endpoint = ledger.syncEndpoint(descriptor);
        var newer = endpointMapper.selectById(endpoint.getId());
        LocalDateTime newerAttempt = databaseTimestamp(LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(1));
        newer.setLastAttemptAt(newerAttempt);
        newer.setNextPollAt(newerAttempt.plusMinutes(30));
        endpointMapper.updateById(newer);

        assertThrows(IllegalStateException.class,
                () -> ledger.startRun(endpoint, descriptor, "scheduled"));
        assertEquals(0L, runMapper.selectCount(new LambdaQueryWrapper<>()));
    }

    private static LocalDateTime databaseTimestamp(LocalDateTime value) {
        return value.withNano((value.getNano() / 1_000_000) * 1_000_000);
    }

    @Test
    void retainsRawBodyOnlyForExplicitApprovedFullPolicy() {
        NewsSourceEndpointDescriptor descriptor = descriptor("full-feed", "full", "approved");
        var endpoint = ledger.syncEndpoint(descriptor);
        var run = ledger.startRun(endpoint, descriptor, "manual");
        byte[] body = "licensed representation".getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.parse("2026-08-27T10:00:00Z");
        NewsSourceTransportRecord transport = new NewsSourceTransportRecord(
                descriptor.url(), descriptor.url(), 200, "application/atom+xml", "", "",
                "", (long) body.length, body, false, false, now, now.plusMillis(10), "", "");
        ledger.completeRun(run, endpoint, new NewsSourcePollBatch(descriptor,
                NewsSourcePollBatch.Status.SUCCESS, now, now.plusMillis(12), List.of(),
                List.of(transport), "", ""));

        AiNewsRawCaptureEntity capture = rawCaptureMapper.selectOne(
                new LambdaQueryWrapper<AiNewsRawCaptureEntity>()
                        .eq(AiNewsRawCaptureEntity::getIngestionRunId, run.getId()));
        assertEquals("inline_full", capture.getRetentionApplied());
        assertEquals("licensed representation",
                new String(capture.getRawBody(), StandardCharsets.UTF_8));
        assertTrue(Boolean.TRUE.equals(adminService.inspectRun(run.getId()).captures()
                .getFirst().getBodyRetained()));
    }

    @Test
    void persistedPublisherFeedCanAttestExactArticleTimeOnlyAfterGovernanceApproval() {
        URI feed = URI.create("https://openai.com/news/rss.xml");
        NewsSourceEndpointDescriptor descriptor = new NewsSourceEndpointDescriptor(
                "approved-openai-feed", 2, "openai", "rss", NewsSourceChannel.FEED,
                "FEED", feed, List.of("en"), List.of("product"), 900, true,
                "public_metadata", "metadata_only", "allowed");
        var endpoint = ledger.syncEndpoint(descriptor);
        var run = ledger.startRun(endpoint, descriptor, "manual");
        Instant started = Instant.parse("2026-08-27T03:01:00Z");
        byte[] feedBody = "<rss><item>publisher representation</item></rss>"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("feedEntryId", "https://openai.com/index/brazil");
        metadata.put("publishedAt", "2026-08-27T03:00:00Z");
        metadata.put("publishedAtRaw", "Thu, 27 Aug 2026 03:00:00 GMT");
        metadata.put("feedUrl", feed.toString());
        NewsSourceResult result = new NewsSourceResult(
                "Expanding OpenAI's presence in Brazil", "publisher summary", "publisher summary",
                new NewsSourceProvenance("rss", "official",
                        "https://openai.com/index/brazil",
                        "https://openai.com/index/brazil", started, 200,
                        "RSS_SEARCH", metadata));
        NewsSourceTransportRecord transport = new NewsSourceTransportRecord(
                feed, feed, 200, "application/rss+xml", "\"v1\"", "", "",
                (long) feedBody.length, feedBody, false, false, started,
                started.plusMillis(50), "", "");
        ledger.completeRun(run, endpoint, new NewsSourcePollBatch(descriptor,
                NewsSourcePollBatch.Status.SUCCESS, started, started.plusMillis(60),
                List.of(result), List.of(transport), "", ""));

        AiNewsSourceTimeAttestationService.Resolution resolution =
                timeAttestations.resolve("https://openai.com/index/brazil/?utm_source=discovery");

        assertEquals("BOUND", resolution.status());
        assertNotNull(resolution.attestation());
        assertEquals("STRUCTURED_FEED", resolution.attestation().method());
        assertEquals("2026-08-27T03:00",
                resolution.attestation().publishedAtUtc().toString());
        assertEquals(64, resolution.attestation().attestationHash().length());
    }

    private static NewsSourceEndpointDescriptor descriptor(String key, String retention,
                                                           String rights) {
        return new NewsSourceEndpointDescriptor(key, 1, "openai", "rss",
                NewsSourceChannel.FEED, "FEED", URI.create("https://example.com/" + key),
                List.of("en"), List.of("model"), 900, false, rights, retention,
                "review_required");
    }

    private static NewsSourceResult result(boolean revalidated) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("feedEntryId", "entry-1");
        metadata.put("publishedAt", "2026-08-27T08:05:00Z");
        metadata.put("publishedAtRaw", "2026-08-27T08:05:00Z");
        if (revalidated) {
            metadata.put("revalidated", true);
            metadata.put("revalidatedAt", "2026-08-27T08:15:00Z");
        }
        return new NewsSourceResult("A durable AI release", "release summary", "release summary",
                new NewsSourceProvenance("rss", "official",
                        "https://example.com/news/release", "https://example.com/news/release",
                        Instant.parse("2026-08-27T08:06:00Z"), revalidated ? 304 : 200,
                        revalidated ? "RSS_ATOM_REVALIDATED" : "RSS_SEARCH", metadata));
    }

    private static NewsSourceResult duplicateResult() {
        return new NewsSourceResult("Conflicting duplicate representation", "duplicate", "duplicate",
                new NewsSourceProvenance("rss", "official",
                        "https://example.com/news/duplicate-url",
                        "https://example.com/news/duplicate-url", Instant.now(), 200,
                        "RSS_SEARCH", Map.of("feedEntryId", "entry-1",
                        "publishedAt", "2026-08-27T08:05:00Z")));
    }
}
