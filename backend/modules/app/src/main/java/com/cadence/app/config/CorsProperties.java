package com.cadence.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-origin policy of the HTTP API.
 *
 * <ul>
 *   <li>{@code allowedOrigins}: origin patterns (as in {@code CorsConfiguration#setAllowedOriginPatterns}, e.g.
 *       {@code https://console.example.com}, {@code https://*.example.com}, {@code http://localhost:*}). Fail closed:
 *       an empty list allows no cross-origin request at all. Env form: {@code APP_CORS_ALLOWED_ORIGINS=a,b}.</li>
 *   <li>{@code allowCredentials}: send {@code Access-Control-Allow-Credentials}. Off by default: the console
 *       authenticates with a Bearer token in {@code Authorization}, which does not need it (that flag is for cookies,
 *       HTTP auth and client certificates). When it is on, a pattern that can match a foreign site is refused: any-host
 *       patterns ({@code *}, {@code https://*}), a wildcard in the scheme or port, and a host wildcard that is not
 *       {@code *.} followed by at least two literal labels that are not a well-known public suffix
 *       ({@code https://*.example.com} is fine; {@code https://*.com} and {@code https://*.co.uk} are not).</li>
 * </ul>
 * Blank entries are dropped. Invalid combinations fail at startup.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins, boolean allowCredentials) {

    /** {@code *}, {@code *:*}, {@code https://*}, {@code http://*:8080}, ...: every site matches. */
    private static final Pattern ANY_HOST = Pattern.compile("^(?:[A-Za-z][A-Za-z0-9+.-]*://)?\\*(?::(?:\\*|\\d+))?$");

    /** {@code [scheme://]host[:port]} with the wildcard allowed in each part. */
    private static final Pattern ORIGIN = Pattern.compile("^(?:([^:/]*)://)?([^:/\\[\\]]+)(?::([^/]*))?$");

    /**
     * Multi-label public suffixes (and shared-hosting domains) a wildcard must not sit directly in front of. A pragmatic
     * subset, not the full Public Suffix List: the single-label case ({@code *.com}) is handled structurally.
     */
    private static final Set<String> PUBLIC_SUFFIXES = Set.of(
            "co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "ac.uk", "gov.uk", "sch.uk",
            "com.au", "net.au", "org.au", "edu.au", "gov.au", "co.nz", "net.nz", "org.nz", "ac.nz",
            "co.jp", "or.jp", "ne.jp", "ac.jp", "co.kr", "or.kr", "co.in", "net.in", "org.in", "co.id", "co.il",
            "com.br", "net.br", "org.br", "com.ar", "com.mx", "com.co", "com.pe", "com.ve",
            "com.cn", "net.cn", "org.cn", "com.hk", "com.tw", "com.sg", "com.my", "com.ph", "com.vn", "com.tr",
            "co.za", "org.za", "com.ng", "co.ke", "com.eg", "com.sa", "co.th",
            "com.ua", "org.ua", "net.ua", "com.pl", "com.ru", "com.ge", "com.de", "com.es", "com.pt",
            "github.io", "gitlab.io", "pages.dev", "workers.dev", "netlify.app", "vercel.app", "herokuapp.com",
            "web.app", "firebaseapp.com", "appspot.com", "azurewebsites.net", "cloudfront.net", "amazonaws.com",
            "blogspot.com", "wordpress.com", "ngrok.io", "ngrok-free.app", "onrender.com", "fly.dev", "railway.app");

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream().filter(o -> o != null && !o.isBlank()).map(String::strip).toList();
        // An unset env var (prod: ${APP_CORS_ALLOWED_ORIGINS}) reaches here as the literal placeholder: fail loudly.
        for (String origin : allowedOrigins) {
            if (origin.contains("${")) {
                throw new IllegalStateException("app.cors.allowed-origins contains an unresolved placeholder '" + origin
                        + "': set APP_CORS_ALLOWED_ORIGINS to the console origin(s), e.g. https://console.example.com");
            }
        }
        if (allowCredentials) {
            for (String origin : allowedOrigins) {
                if (ANY_HOST.matcher(origin).matches()) {
                    throw new IllegalStateException(
                            "app.cors.allowed-origins contains '" + origin + "' together with app.cors.allow-credentials=true: "
                                    + "that would let any website call the API with the user's browser credentials. "
                                    + "List explicit origins (e.g. https://console.example.com) or turn credentials off");
                }
                String problem = wildcardProblem(origin);
                if (problem != null) {
                    throw new IllegalStateException(
                            "app.cors.allowed-origins contains '" + origin + "' together with app.cors.allow-credentials=true: "
                                    + problem + ", so it could match a site the operator does not control "
                                    + "(any website would then be able to call the API with the user's browser credentials). "
                                    + "List explicit origins or '*.' plus your own registrable domain "
                                    + "(e.g. https://*.example.com), or turn credentials off");
                }
            }
        }
    }

    /** Why a wildcard origin pattern is too broad for credentialed requests, or {@code null} when it is acceptable. */
    private static String wildcardProblem(String origin) {
        if (origin.indexOf('*') < 0) {
            return null;
        }
        Matcher m = ORIGIN.matcher(origin);
        if (!m.matches()) {
            return "its structure is not [scheme://]host[:port]";
        }
        String scheme = m.group(1);
        String host = m.group(2).toLowerCase(Locale.ROOT);
        String port = m.group(3);
        if (scheme != null && scheme.contains("*")) {
            return "the scheme is a wildcard";
        }
        if (port != null && port.contains("*")) {
            return "the port is a wildcard";
        }
        if (!host.contains("*")) {
            return null;
        }
        if (!host.startsWith("*.") || host.indexOf('*', 1) >= 0) {
            return "the host wildcard must be a whole leading label ('*.' followed by a literal domain)";
        }
        String rest = host.substring(2);
        String[] labels = rest.split("\\.", -1);
        if (labels.length < 2 || java.util.Arrays.stream(labels).anyMatch(String::isEmpty)) {
            return "the wildcard stands right in front of a top-level domain ('" + rest + "')";
        }
        if (PUBLIC_SUFFIXES.contains(rest)) {
            return "the wildcard stands right in front of the public suffix '" + rest + "'";
        }
        return null;
    }
}
