package vip.newsclaw.auth.pat;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Small, deterministic HTTP authorization policy for Personal Access Tokens. */
public final class PersonalAccessTokenScopePolicy {

    private PersonalAccessTokenScopePolicy() {}

    public static boolean allows(String configuredScopes, String method, String uri) {
        return allows(configuredScopes, requiredScope(method, uri));
    }

    public static boolean allows(String configuredScopes, String requiredScope) {
        Set<String> scopes = parse(configuredScopes);
        if (scopes.contains("*")) return true;
        String required = requiredScope.toLowerCase(Locale.ROOT);
        int colon = required.indexOf(':');
        String resourceWildcard = colon > 0 ? required.substring(0, colon) + ":*" : required;
        return scopes.contains(required) || scopes.contains(resourceWildcard);
    }

    public static String normalize(String configuredScopes) {
        if (configuredScopes == null || configuredScopes.isBlank()) return "*";
        Set<String> scopes = parse(configuredScopes);
        for (String scope : scopes) {
            if ("*".equals(scope)) continue;
            if (!scope.matches("[a-z0-9_-]+:(read|write|\\*)")) {
                throw new IllegalArgumentException("Invalid PAT scope: " + scope);
            }
        }
        return scopes.stream().sorted().collect(Collectors.joining(","));
    }

    static String requiredScope(String method, String uri) {
        String path = uri == null ? "" : uri;
        if (path.equals("/api/v1/talk/ws") || path.equals("/api/v1/chat/stream")
                || path.matches("/api/v1/agents/[^/]+/chat(?:/stream)?")) {
            return "chat:write";
        }
        if (path.equals("/api/v1/desktop/ws")) return "desktop:write";

        String resource = "api";
        String prefix = "/api/v1/";
        if (path.startsWith(prefix)) {
            String tail = path.substring(prefix.length());
            int slash = tail.indexOf('/');
            resource = (slash >= 0 ? tail.substring(0, slash) : tail);
            if (resource.isBlank()) resource = "api";
        } else if (path.startsWith("/api/")) {
            String tail = path.substring("/api/".length());
            int slash = tail.indexOf('/');
            resource = slash >= 0 ? tail.substring(0, slash) : tail;
        }
        boolean read = "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
        return resource.toLowerCase(Locale.ROOT) + ':' + (read ? "read" : "write");
    }

    private static Set<String> parse(String configuredScopes) {
        if (configuredScopes == null || configuredScopes.isBlank()) return Set.of("*");
        return Arrays.stream(configuredScopes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
