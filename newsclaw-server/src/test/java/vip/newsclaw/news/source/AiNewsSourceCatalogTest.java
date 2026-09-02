package vip.newsclaw.news.source;

import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsSourceCatalogTest {

    @Test
    void bundledCatalogIsVersionedAndFailClosedByDefault() {
        AiNewsSourceCatalog catalog = new AiNewsSourceCatalog(new AiNewsSourceRegistry(), "");

        assertEquals(3, catalog.version());
        assertTrue(catalog.all().size() >= 6);
        assertTrue(catalog.enabled(AiNewsSourceCatalog.EndpointAdapter.FEED).isEmpty());
        assertTrue(catalog.all().stream().allMatch(endpoint -> !endpoint.evidenceEligible()));
        assertTrue(catalog.all().stream().allMatch(endpoint -> !endpoint.rightsStatus().isBlank()));
        var farms = catalog.all().stream()
                .filter(endpoint -> endpoint.endpointId().equals("farms-news-all-rss"))
                .findFirst().orElseThrow();
        assertEquals("farms", farms.sourceKey());
        assertEquals("written_permission_required", farms.rightsStatus());
    }

    @Test
    void explicitReviewedIdEnablesOnlyThatEndpointAndAddsAuditMetadata() {
        AiNewsSourceCatalog catalog = new AiNewsSourceCatalog(
                new AiNewsSourceRegistry(), "openai-news-rss");

        var endpoint = catalog.enabled(AiNewsSourceCatalog.EndpointAdapter.FEED).getFirst();
        assertEquals("openai-news-rss", endpoint.endpointId());
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        endpoint.addProvenance(metadata, catalog.version());
        assertEquals("openai", metadata.get("sourceEndpointOwnerKey"));
        assertEquals("metadata_only", metadata.get("sourceEndpointRawRetention"));
        assertEquals(false, metadata.get("sourceEndpointEvidenceEligible"));
    }

    @Test
    void unknownEndpointIdFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> new AiNewsSourceCatalog(new AiNewsSourceRegistry(), "typo-endpoint"));
    }

    @Test
    void evidenceGovernanceUsesExplicitReviewedAllowlists() {
        assertTrue(AiNewsSourceGovernancePolicy.evidenceEligible(
                true, "public_metadata", "allowed"));
        assertTrue(!AiNewsSourceGovernancePolicy.evidenceEligible(
                true, "review_required", "allowed"));
        assertTrue(!AiNewsSourceGovernancePolicy.evidenceEligible(
                true, "approved", "review_required"));
        assertTrue(!AiNewsSourceGovernancePolicy.evidenceEligible(
                false, "approved", "allowed"));
    }
}
