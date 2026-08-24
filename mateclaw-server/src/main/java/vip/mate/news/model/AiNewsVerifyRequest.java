package vip.mate.news.model;

/** Optional operator verdict; the server still applies the evidence policy. */
public record AiNewsVerifyRequest(String verdict, Double confidence) {
}
