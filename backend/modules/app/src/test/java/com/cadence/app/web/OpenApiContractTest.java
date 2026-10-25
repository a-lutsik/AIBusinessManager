package com.cadence.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.cadence.app.config.SecurityConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Contract check between the hand-written {@code static/openapi.yaml} and the real HTTP surface (TD-27, PRODUCT_PLAN
 * §11). Every handler mapping under {@code /api/**} of a Spring context that loads all controllers (no database) must
 * have an operation in the spec and every spec operation under {@code /api/**} must have a handler. Path templates are
 * compared by shape ({@code {id}} and {@code {x}} are the same), methods exactly.
 *
 * <p>The comparison itself is {@link Contract#check}; the second group of tests feeds it synthetic mapping/spec pairs
 * to prove that drift is reported in both directions.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class OpenApiContractTest extends CorsWebTestBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void openApiSpecMatchesEveryApiHandlerMapping() throws IOException {
        Set<Route> routes = Contract.routesOf(handlerMapping);

        // Guard against a silently empty collection (which would turn the check into "spec has no handlers").
        assertThat(routes).extracting(Route::path)
                .anyMatch(p -> p.startsWith("/api/public/"))
                .anyMatch(p -> p.startsWith("/api/app/"));

        List<String> problems = Contract.check(routes, Contract.parseSpec(openApiYaml()));
        if (!problems.isEmpty()) {
            fail("openapi.yaml and the /api/** handler mappings diverge (%d problem(s)); fix the spec (or the code):%n  %s"
                    .formatted(problems.size(), String.join("\n  ", problems)));
        }
    }

    // --- the comparison logic catches drift (synthetic mapping/spec pairs) ---

    @Test
    void reportsRoutesMissingInTheSpecAndSpecOperationsWithoutHandler() {
        Set<Route> routes = routes(
                "GET /api/public/items/{id}",   // spec names the parameter {itemId}: same shape, must match
                "POST /api/public/items",
                "GET /api/public/undocumented",
                "DELETE /api/public/items/{id}");
        Map<String, Object> spec = Contract.parseSpec("""
                paths:
                  /api/public/items/{itemId}:
                    get:
                      parameters: [ { in: path, name: itemId, required: true, schema: { type: string } } ]
                      responses: { "200": { description: ok } }
                  /api/public/items:
                    post:
                      responses: { "200": { description: ok } }
                  /api/public/ghost:
                    get:
                      responses: { "200": { description: ok } }
                    post:
                      responses: { "200": { description: ok } }
                """);

        List<String> problems = Contract.check(routes, spec);

        assertThat(problems).containsExactlyInAnyOrder(
                "In code, not in openapi.yaml: DELETE /api/public/items/{id}",
                "In code, not in openapi.yaml: GET /api/public/undocumented",
                "In openapi.yaml, no handler: GET /api/public/ghost",
                "In openapi.yaml, no handler: POST /api/public/ghost");
    }

    @Test
    void sameShapeDifferentMethodIsDriftInBothDirections() {
        Set<Route> routes = routes("PUT /api/public/items/{id}");
        Map<String, Object> spec = Contract.parseSpec("""
                paths:
                  /api/public/items/{id}:
                    get:
                      parameters: [ { in: path, name: id, required: true } ]
                      responses: { "200": { description: ok } }
                """);

        assertThat(Contract.check(routes, spec)).containsExactlyInAnyOrder(
                "In code, not in openapi.yaml: PUT /api/public/items/{id}",
                "In openapi.yaml, no handler: GET /api/public/items/{id}");
    }

    @Test
    void reportsSpecPathsThatDifferInShapeFromTheHandler() {
        Set<Route> routes = routes("GET /api/public/items/{id}/parts");
        Map<String, Object> spec = Contract.parseSpec("""
                paths:
                  /api/public/items/{id}:
                    get:
                      parameters: [ { in: path, name: id, required: true } ]
                      responses: { "200": { description: ok } }
                """);

        assertThat(Contract.check(routes, spec)).containsExactlyInAnyOrder(
                "In code, not in openapi.yaml: GET /api/public/items/{id}/parts",
                "In openapi.yaml, no handler: GET /api/public/items/{id}");
    }

    @Test
    void reportsBrokenOperationsAndParameters() {
        Set<Route> routes = routes(
                "GET /api/app/a/{id}",
                "GET /api/app/b",
                "GET /api/app/c/{id}",
                "GET /api/public/d");
        Map<String, Object> spec = Contract.parseSpec("""
                paths:
                  /api/app/a/{id}:
                    get:
                      responses: { "200": { description: ok }, "401": { description: no token } }
                  /api/app/b:
                    get:
                      parameters: [ { in: path, name: stray, required: true } ]
                      responses: { "404": { description: nope }, "401": { description: no token } }
                  /api/app/c/{id}:
                    get:
                      parameters: [ { in: path, name: id, required: false } ]
                      responses: { "200": { description: ok } }
                  /api/public/d:
                    get:
                      responses: { "200": { description: ok } }
                """);

        assertThat(Contract.check(routes, spec)).containsExactlyInAnyOrder(
                "GET /api/app/a/{id}: path parameter {id} of the template is not declared as an 'in: path' parameter",
                "GET /api/app/b: declares path parameter 'stray' that is not in the path template",
                "GET /api/app/b: no 2xx response documented",
                "GET /api/app/c/{id}: path parameter 'id' must be required: true",
                "GET /api/app/c/{id}: /api/app/** operation must document a 401 response");
    }

    @Test
    void resolvesSharedComponentResponsesAndParametersByReference() {
        Set<Route> routes = routes("GET /api/app/a/{id}");
        Map<String, Object> spec = Contract.parseSpec("""
                paths:
                  /api/app/a/{id}:
                    parameters:
                      - $ref: "#/components/parameters/Id"
                    get:
                      responses:
                        "200": { description: ok }
                        "401": { $ref: "#/components/responses/Unauthorized" }
                components:
                  parameters:
                    Id: { in: path, name: id, required: true }
                  responses:
                    Unauthorized: { description: no token }
                """);

        assertThat(Contract.check(routes, spec)).isEmpty();
    }

    @Test
    void reportsTwoSpecPathsThatCollapseToTheSameShape() {
        Set<Route> routes = routes("GET /api/public/items/{id}");
        Map<String, Object> spec = Contract.parseSpec("""
                paths:
                  /api/public/items/{id}:
                    get:
                      parameters: [ { in: path, name: id, required: true } ]
                      responses: { "200": { description: ok } }
                  /api/public/items/{other}:
                    get:
                      parameters: [ { in: path, name: other, required: true } ]
                      responses: { "200": { description: ok } }
                """);

        assertThat(Contract.check(routes, spec)).containsExactly(
                "openapi.yaml documents GET /api/public/items/{other} twice (same shape as /api/public/items/{id})");
    }

    private static Set<Route> routes(String... methodAndPath) {
        Set<Route> out = new LinkedHashSet<>();
        for (String s : methodAndPath) {
            String[] parts = s.split(" ", 2);
            out.add(new Route(parts[0], parts[1]));
        }
        return out;
    }

    private static String openApiYaml() throws IOException {
        try (InputStream in = new ClassPathResource("static/openapi.yaml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** One HTTP route of the application; {@code method} is {@code ANY} when a mapping does not restrict it. */
    record Route(String method, String path) {
        String shape() {
            return Contract.shapeOf(path);
        }

        String key() {
            return method + " " + shape();
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /** Route collection, spec parsing and the comparison. Static and Spring-free so tests can feed it synthetic input. */
    static final class Contract {

        private static final String API_PREFIX = "/api/";
        private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{([^/{}:]+)(?::[^/]*)?}");
        private static final Pattern SUCCESS = Pattern.compile("2\\d\\d");
        private static final Set<String> HTTP_METHODS =
                Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

        private Contract() {
        }

        /** Every {@code /api/**} path pattern and HTTP method of the application's {@code @RequestMapping} handlers. */
        static Set<Route> routesOf(RequestMappingHandlerMapping mapping) {
            Set<Route> routes = new TreeSet<>(Comparator.comparing(Route::path).thenComparing(Route::method));
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                Set<String> patterns = info.getPathPatternsCondition() == null
                        ? Set.of() : info.getPathPatternsCondition().getPatternValues();
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                for (String pattern : patterns) {
                    if (!pattern.startsWith(API_PREFIX)) {
                        continue;
                    }
                    if (methods.isEmpty()) {
                        routes.add(new Route("ANY", pattern));
                    }
                    for (RequestMethod method : methods) {
                        routes.add(new Route(method.name(), pattern));
                    }
                }
            }
            return routes;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parseSpec(String yaml) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new IllegalStateException("openapi.yaml is not a YAML mapping");
            }
            return (Map<String, Object>) map;
        }

        /** {@code /a/{id}/b/{x:\d+}} to {@code /a/{}/b/{}}: parameter names are not part of the contract. */
        static String shapeOf(String template) {
            return TEMPLATE_VARIABLE.matcher(template).replaceAll("{}");
        }

        static List<String> check(Collection<Route> routes, Map<String, Object> spec) {
            List<String> problems = new ArrayList<>();

            Map<String, Route> codeByKey = new LinkedHashMap<>();
            for (Route route : routes) {
                codeByKey.putIfAbsent(route.key(), route);
            }

            Map<String, Object> components = map(spec.get("components"));
            Map<String, SpecOperation> specByKey = new LinkedHashMap<>();
            for (Map.Entry<String, Object> pathEntry : map(spec.get("paths")).entrySet()) {
                String path = pathEntry.getKey();
                if (!path.startsWith(API_PREFIX)) {
                    continue;
                }
                Map<String, Object> item = map(pathEntry.getValue());
                for (Map.Entry<String, Object> opEntry : item.entrySet()) {
                    if (!HTTP_METHODS.contains(opEntry.getKey().toLowerCase())) {
                        continue;
                    }
                    String method = opEntry.getKey().toUpperCase();
                    SpecOperation op = new SpecOperation(method, path, map(opEntry.getValue()), item, components);
                    SpecOperation previous = specByKey.putIfAbsent(op.key(), op);
                    if (previous != null) {
                        problems.add("openapi.yaml documents %s %s twice (same shape as %s)"
                                .formatted(method, path, previous.path));
                    }
                }
            }

            for (Route route : codeByKey.values()) {
                if (!specByKey.containsKey(route.key())) {
                    problems.add("In code, not in openapi.yaml: " + route);
                }
            }
            for (SpecOperation op : specByKey.values()) {
                if (!codeByKey.containsKey(op.key())) {
                    problems.add("In openapi.yaml, no handler: " + op.method + " " + op.path);
                }
                problems.addAll(op.problems());
            }
            Collections.sort(problems);
            return problems;
        }

        private static String shapeKey(String method, String path) {
            return method + " " + shapeOf(path);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> map(Object value) {
            return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        @SuppressWarnings("unchecked")
        private static List<Object> list(Object value) {
            return value instanceof List<?> l ? (List<Object>) l : List.of();
        }

        /** Follows a local {@code $ref} such as {@code #/components/responses/Unauthorized}. */
        private static Map<String, Object> resolve(Map<String, Object> node, Map<String, Object> components) {
            Object ref = node.get("$ref");
            if (!(ref instanceof String s) || !s.startsWith("#/components/")) {
                return node;
            }
            Object cursor = components;
            for (String part : s.substring("#/components/".length()).split("/")) {
                cursor = map(cursor).get(part);
            }
            return map(cursor);
        }

        /** One {@code method + path} of the spec with the structural checks that do not need the code. */
        private record SpecOperation(
                String method, String path, Map<String, Object> operation, Map<String, Object> pathItem,
                Map<String, Object> components) {

            String key() {
                return shapeKey(method, path);
            }

            List<String> problems() {
                List<String> out = new ArrayList<>();
                String id = method + " " + path;

                Set<String> templateNames = new LinkedHashSet<>();
                Matcher m = TEMPLATE_VARIABLE.matcher(path);
                while (m.find()) {
                    templateNames.add(m.group(1));
                }
                Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
                for (Object raw : list(pathItem.get("parameters"))) {
                    addPathParameter(declared, raw);
                }
                for (Object raw : list(operation.get("parameters"))) {
                    addPathParameter(declared, raw);
                }
                for (String name : templateNames) {
                    if (!declared.containsKey(name)) {
                        out.add("%s: path parameter {%s} of the template is not declared as an 'in: path' parameter"
                                .formatted(id, name));
                    }
                }
                for (Map.Entry<String, Map<String, Object>> entry : declared.entrySet()) {
                    if (!templateNames.contains(entry.getKey())) {
                        out.add("%s: declares path parameter '%s' that is not in the path template"
                                .formatted(id, entry.getKey()));
                    } else if (!Boolean.TRUE.equals(entry.getValue().get("required"))) {
                        out.add("%s: path parameter '%s' must be required: true".formatted(id, entry.getKey()));
                    }
                }

                Set<String> codes = new LinkedHashSet<>();
                for (String code : map(operation.get("responses")).keySet()) {
                    codes.add(String.valueOf(code));
                }
                if (codes.stream().noneMatch(c -> SUCCESS.matcher(c).matches())) {
                    out.add(id + ": no 2xx response documented");
                }
                if (path.startsWith("/api/app/") && !codes.contains("401")) {
                    out.add(id + ": /api/app/** operation must document a 401 response");
                }
                return out;
            }

            private void addPathParameter(Map<String, Map<String, Object>> into, Object raw) {
                Map<String, Object> parameter = resolve(map(raw), components);
                if ("path".equals(parameter.get("in")) && parameter.get("name") != null) {
                    into.put(String.valueOf(parameter.get("name")), parameter);
                }
            }
        }
    }
}
