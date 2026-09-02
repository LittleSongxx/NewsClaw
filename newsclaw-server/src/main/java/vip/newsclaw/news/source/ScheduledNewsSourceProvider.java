package vip.newsclaw.news.source;

import java.util.List;

/** Structured provider whose endpoints can be polled independently of an Agent request. */
public interface ScheduledNewsSourceProvider extends NewsSourceProvider {

    List<NewsSourceEndpointDescriptor> configuredEndpoints();

    NewsSourcePollBatch poll(NewsSourceEndpointDescriptor endpoint,
                             NewsSourceValidators validators);
}
