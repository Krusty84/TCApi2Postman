import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.NodeVisitor;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GeneratePostmanFromTeamcenter {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String QNAME_PREFIX = "http://teamcenter.com/Schemas/";

    // ===== Root documentation shown in Postman (Markdown) =====
    private static final String COLLECTION_DESCRIPTION = """
# Teamcenter REST API Collection

This collection was generated from **structure.js**. 
It mirrors Teamcenter JsonRestServices:
`<Library> → Services → <Service> → <YYYY-MM> → <operation>`.

> **What you should do now**
> 1. Set Postman variables: **TCURL**, **TCURL_WEBTIER_PORT**, **WEBTIER_APP_NAME** (defaults come from config).
> 2. Open **Core → Services → Session → 2011-06 → login** and try a request.
> 3. Fill the `body.credentials` fields (user, password).
> 4. Keep the `header` section as-is (already included from config).
> 5. (Optional) Re-run the generator with `--include-internal` to add Internal APIs.

**Notes**
- Example responses include `.QName` and minimal placeholders.
- Internal APIs are **excluded by default**. Include them with `--include-internal`.
""";

    // Default header (used if config.header missing)
    private static final ObjectNode DEFAULT_HEADER = (ObjectNode) parseJson("""
    {
      "state": {
        "formatProperties": true,
        "stateless": true,
        "unloadObjects": false,
        "enableServerStateHeaders": true,
        "locale": "en_US"
      },
      "policy": {
        "types": [
          {
            "name": "ItemRevision",
            "properties": [
              { "name": "item_id" },
              { "name": "item_revision_id" },
              { "name": "object_name" },
              { "name": "owning_user" },
              { "name": "last_mod_date" }
            ]
          }
        ]
      }
    }
    """);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(
                            "   ╔════════════════════════════╗\n" +
                            "   ║        TCApi2Postman       ║\n" +
                            "   ╚════════════════════════════╝ \n" +
                            "====================================\n" +
                            "Usage: java -jar TCApi2Postman <structure.js from \"...\\aws2\\stage\\out\\soa\\api\"> <out.postman_collection.json> [--include-internal] [--config <TCApi2Postman.config>]\n" +
                            "\n" +
                            "Example:\n" +
                            "  java -jar TCApi2Postman C:\\Siemens\\Teamcenter\\13\\aws2\\stage\\out\\soa\\api\\structure.js D:\\Temp\\TcApi_collection.json --config TCApi2Postman.config\n" +
                            "\n" +
                            "Options:\n" +
                            "  --include-internal : Include internal APIs in the output\n" +
                            "  --config <file>    : Specify custom configuration file\n" +
                            "\n" +
                            "Author: Alexey Sedoykin\n" +
                            "Contact|Support: www.linkedin.com/in/sedoykin | https://github.com/Krusty84\n" +
                            "====================================\n"
            );

            System.exit(1);
        }
        String in = args[0];
        String out = args[1];

        boolean includeInternal = false; // default: ignore internal
        String configPath = null;

        // Parse optional flags (order-independent)
        for (int i = 2; i < args.length; i++) {
            String a = args[i].trim();
            if ("--include-internal".equalsIgnoreCase(a)) {
                includeInternal = true;
            } else if ("--config".equalsIgnoreCase(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--config requires a path");
                    System.exit(2);
                }
                configPath = args[++i].trim();
            } else {
                System.err.println("Unknown option: " + a);
                System.err.println("Allowed: --include-internal  --config <TCApi2Postman.config>");
                System.exit(2);
            }
        }

        // Load config if provided
        JsonNode cfg = null;
        ObjectNode headerFromCfg = null;
        String varTCURL = "http://127.0.0.1";
        String varPORT = "7001";
        String varAPP = "tc";
        if (configPath != null) {
            String cfgRaw = Files.readString(Path.of(configPath));
            cfg = parseJson(cfgRaw);
            if (cfg != null) {
                if (cfg.has("header") && cfg.get("header").isObject()) {
                    headerFromCfg = (ObjectNode) cfg.get("header");
                }
                if (cfg.has("variables") && cfg.get("variables").isObject()) {
                    JsonNode v = cfg.get("variables");
                    if (v.has("TCURL")) varTCURL = v.get("TCURL").asText(varTCURL);
                    if (v.has("TCURL_WEBTIER_PORT")) varPORT = v.get("TCURL_WEBTIER_PORT").asText(varPORT);
                    if (v.has("WEBTIER_APP_NAME")) varAPP = v.get("WEBTIER_APP_NAME").asText(varAPP);
                }
            }
        }

        // Parse structure.js
        String rawStructureJsFile = Files.readString(Path.of(in));
        String extractedJson = extractRootJson(rawStructureJsFile);
        if (extractedJson == null) throw new IllegalStateException("Cannot find root JSON in " + in);
        JsonNode dataRoot = parseJson(extractedJson);

        DateTimeFormatter dtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String timestamp = LocalDateTime.now().format(dtFormatter);

        // Build collection with variables from config
        ObjectNode collection = buildEmptyCollection(
                "Teamcenter REST API (" + timestamp + ")",
                varTCURL, varPORT, varAPP
        );

        // Traverse ALL templates (Teamcenter and others)
        for (Iterator<Map.Entry<String, JsonNode>> itTpl = dataRoot.fields(); itTpl.hasNext(); ) {
            Map.Entry<String, JsonNode> tplEntry = itTpl.next();
            String templateName = tplEntry.getKey();
            JsonNode templateNode = tplEntry.getValue();
            if (templateNode == null || !templateNode.isObject()) continue;

            JsonNode soa = templateNode.get("Soa");
            if (soa == null || !soa.isObject()) continue;

            // External libraries (all keys except "Internal")
            for (Iterator<Map.Entry<String, JsonNode>> itLib = soa.fields(); itLib.hasNext(); ) {
                Map.Entry<String, JsonNode> libEntry = itLib.next();
                String libName = libEntry.getKey();
                if ("Internal".equals(libName)) continue;
                JsonNode libNode = libEntry.getValue();
                if (libNode == null || !libNode.isObject()) continue;

                collectFromLibrary(collection, templateName, libName, libNode, dataRoot, false, headerFromCfg);
            }

            // Internal libraries (only if flag is on)
            if (includeInternal) {
                JsonNode internal = soa.get("Internal");
                if (internal != null && internal.isObject()) {
                    for (Iterator<Map.Entry<String, JsonNode>> itLib = internal.fields(); itLib.hasNext(); ) {
                        Map.Entry<String, JsonNode> libEntry = itLib.next();
                        String libName = libEntry.getKey();
                        JsonNode libNode = libEntry.getValue();
                        if (libNode == null || !libNode.isObject()) continue;

                        collectFromLibrary(collection, templateName, libName, libNode, dataRoot, true, headerFromCfg);
                    }
                }
            }
        }

        try (var outWriter = mapper.writer(new DefaultPrettyPrinter())
                .createGenerator(Files.newOutputStream(Path.of(out)))) {
            mapper.writeTree(outWriter, collection);
        }
        System.out.println("Postman collection written: " + out);
    }

    private static void collectFromLibrary(ObjectNode collection,
                                           String templateName,
                                           String libName,
                                           JsonNode libNode,
                                           JsonNode dataRoot,
                                           boolean internal,
                                           ObjectNode headerFromCfg) throws IOException {
        // Grouping: <Library> → Services → <Service> → <YYYY-MM> → operations
        ObjectNode libFolder = ensureChildFolder(collection, libName);
        ObjectNode servicesFolder = ensureChildFolder(libFolder, "Services");

        // Versions: keys like _2011_06
        for (Iterator<Map.Entry<String, JsonNode>> itVer = libNode.fields(); itVer.hasNext(); ) {
            Map.Entry<String, JsonNode> verEntry = itVer.next();
            String verKey = verEntry.getKey();
            if (!verKey.startsWith("_")) continue;
            String versionPretty = verKeyToPretty(verKey); // 2011-06
            JsonNode versionNode = verEntry.getValue();
            if (versionNode == null || !versionNode.isObject()) continue;

            // Services under version
            for (Iterator<Map.Entry<String, JsonNode>> itSvc = versionNode.fields(); itSvc.hasNext(); ) {
                Map.Entry<String, JsonNode> svcEntry = itSvc.next();
                String serviceName = svcEntry.getKey();
                JsonNode serviceNode = svcEntry.getValue();
                if (serviceNode == null || !serviceNode.isObject()) continue;

                ObjectNode serviceFolder = ensureChildFolder(servicesFolder, serviceName);
                String dateFolderName = templateName.equals("Teamcenter")
                        ? versionPretty
                        : versionPretty + " (" + templateName + ")";
                ObjectNode dateFolder = ensureChildFolder(serviceFolder, dateFolderName);

                // Operations
                for (Iterator<Map.Entry<String, JsonNode>> itOp = serviceNode.fields(); itOp.hasNext(); ) {
                    Map.Entry<String, JsonNode> opEntry = itOp.next();
                    String opName = opEntry.getKey();
                    JsonNode opNode = opEntry.getValue();
                    if (!isOperationNode(opName, opNode)) continue;

                    // URL (Angular approach) with WEBTIER_APP_NAME variable
                    String urlPath = libName + "-" + versionPretty + "-" + serviceName + "/" + opName;
                    if (internal) urlPath = "Internal-" + urlPath;
                    String fullUrl = "{{TCURL}}:{{TCURL_WEBTIER_PORT}}/{{WEBTIER_APP_NAME}}/JsonRestServices/" + urlPath;

                    // Payload
                    ObjectNode inputBody = buildBodyFromInputWithTypes(opNode.get("input"), dataRoot);
                    ObjectNode payload = objectNode();
                    payload.set("header", (headerFromCfg != null ? headerFromCfg : DEFAULT_HEADER).deepCopy());
                    payload.set("body", inputBody);

                    // Description (HTML → Markdown) + Body fields
                    String descHtml = opNode.has("description") ? opNode.get("description").asText() : "";
                    String descMd = HtmlToMarkdown.toMarkdown(descHtml);
                    if (internal) descMd = ("**Internal:** true\n\n" + (descMd == null ? "" : descMd)).trim();

                    String bodyFieldsMd = buildBodyFieldsMarkdown(opNode.get("input"), dataRoot);
                    String fullDesc = (descMd == null || descMd.isBlank()) ? "" : descMd + "\n\n";
                    fullDesc += bodyFieldsMd;

                    ObjectNode request = postRequest(fullUrl, payload, fullDesc);

                    // Example output (.QName + minimal fields)
                    ObjectNode exampleOut = buildExampleOutput(libName, versionPretty, serviceName, opName, opNode.get("output"));

                    ObjectNode exampleResp = objectNode();
                    exampleResp.put("name", "Example 200");
                    exampleResp.put("status", "OK");
                    exampleResp.put("code", 200);
                    exampleResp.set("header", mapper.createArrayNode());
                    exampleResp.put("_postman_previewlanguage", "json");
                    exampleResp.put("body", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(exampleOut));

                    ObjectNode itemReq = objectNode();
                    itemReq.put("name", opName);
                    itemReq.put("_internal", internal);
                    itemReq.set("request", request);
                    ArrayNode respArr = mapper.createArrayNode();
                    respArr.add(exampleResp);
                    itemReq.set("response", respArr);

                    ((ArrayNode) dateFolder.get("item")).add(itemReq);
                }
            }
        }
    }

    // Operation detection: strict + block *Response/*Request
    private static boolean isOperationNode(String name, JsonNode node) {
        if (name == null || name.isEmpty() || node == null) return false;
        int cp0 = name.codePointAt(0);
        if (!Character.isLowerCase(cp0)) return false;
        if (isNumeric(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith("response") || lower.endsWith("request")) return false;
        return node.isObject() || node.isArray();
    }
    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    // Postman scaffolding
    private static ObjectNode buildEmptyCollection(String name, String tcurl, String port, String appName) {
        ObjectNode root = objectNode();
        ObjectNode info = objectNode();
        info.put("name", name);
        info.put("description", COLLECTION_DESCRIPTION);
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        root.set("info", info);
        root.set("item", mapper.createArrayNode());

        ArrayNode variables = mapper.createArrayNode();
        variables.add(objectNode().put("key", "TCURL").put("value", tcurl));
        variables.add(objectNode().put("key", "TCURL_WEBTIER_PORT").put("value", port));
        variables.add(objectNode().put("key", "WEBTIER_APP_NAME").put("value", appName));
        root.set("variable", variables);

        return root;
    }

    private static ObjectNode postRequest(String rawUrl, ObjectNode payload, String descriptionMarkdown) throws IOException {
        ObjectNode req = objectNode();
        req.put("method", "POST");

        ArrayNode headers = mapper.createArrayNode();
        headers.add(objectNode().put("key", "Content-Type").put("value", "application/json"));
        req.set("header", headers);

        // IMPORTANT: set url as a plain string so Postman doesn't drop it
        req.set("url", TextNode.valueOf(rawUrl));

        ObjectNode body = objectNode();
        body.put("mode", "raw");
        body.put("raw", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
        req.set("body", body);

        if (descriptionMarkdown != null && !descriptionMarkdown.isBlank()) {
            req.put("description", descriptionMarkdown);
        }
        return req;
    }

    // Folder helpers
    private static ObjectNode ensureChildFolder(ObjectNode parent, String name) {
        ArrayNode items = (ArrayNode) parent.get("item");
        if (items == null || !items.isArray()) {
            items = mapper.createArrayNode();
            parent.set("item", items);
        }
        for (JsonNode n : items) {
            if (n.isObject() && name.equals(n.get("name").asText())) {
                return (ObjectNode) n;
            }
        }
        ObjectNode f = folder(name);
        items.add(f);
        return f;
    }
    private static ObjectNode folder(String name) {
        ObjectNode f = objectNode();
        f.put("name", name);
        f.set("item", mapper.createArrayNode());
        return f;
    }

    // Build request body JSON (deep sampling)
    private static ObjectNode buildBodyFromInputWithTypes(JsonNode inputNode, JsonNode dataRoot) {
        ObjectNode body = objectNode();
        if (inputNode == null || !inputNode.isObject()) return body;

        inputNode.fields().forEachRemaining(e -> {
            String param = e.getKey();
            JsonNode def = e.getValue();
            String type = def.has("type") ? def.get("type").asText() : "object";
            JsonNode sample = sampleFromTypeDeep(type, dataRoot, new HashSet<>());

            if (def.has("properties")) {
                JsonNode props = def.get("properties");
                if (props.isObject()) {
                    ObjectNode o = objectNode();
                    props.fields().forEachRemaining(p -> {
                        String pname = p.getKey();
                        JsonNode pdef = p.getValue();
                        String ptype = pdef.has("type") ? pdef.get("type").asText() : "object";
                        o.set(pname, sampleFromTypeDeep(ptype, dataRoot, new HashSet<>()));
                    });
                    sample = o;
                } else if (props.isArray() && props.size() > 0) {
                    sample = TextNode.valueOf(props.get(0).asText(""));
                }
            }
            body.set(param, sample);
        });
        return body;
    }

    // ===== Body fields documentation =====
    private static String buildBodyFieldsMarkdown(JsonNode inputNode, JsonNode dataRoot) {
        if (inputNode == null || !inputNode.isObject()) return "### Body fields\n*(no body)*";
        List<FieldDoc> fields = new ArrayList<>();
        Set<String> visitedPaths = new HashSet<>();

        inputNode.fields().forEachRemaining(e -> {
            String rootName = e.getKey();
            JsonNode def = e.getValue();
            collectFieldDocs(rootName, def, dataRoot, new HashSet<>(), fields, visitedPaths);
        });

        if (fields.isEmpty()) return "### Body fields\n*(no fields)*";

        // Sort by path for stable output
        fields.sort(Comparator.comparing(fd -> fd.path));

        StringBuilder sb = new StringBuilder();
        sb.append("### Body fields\n");
        for (FieldDoc fd : fields) {
            sb.append("- `").append(fd.path).append("`");
            if (fd.type != null && !fd.type.isBlank()) {
                sb.append(" *(").append(fd.type).append(")*");
            }
            if (fd.description != null && !fd.description.isBlank()) {
                sb.append(" — ").append(fd.description.replaceAll("\\s+", " ").trim());
            }
            if (!fd.enumVals.isEmpty()) {
                sb.append("  \n  *Enum:* ").append(fd.enumVals);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static void collectFieldDocs(String path, JsonNode defNode, JsonNode dataRoot,
                                         Set<String> typeStack, List<FieldDoc> out, Set<String> visitedPaths) {
        if (visitedPaths.contains(path)) return;
        // Determine this node's type & description
        String type = defNode.has("type") ? defNode.get("type").asText() : "object";
        String desc = defNode.has("description") ? HtmlToMarkdown.toMarkdown(defNode.get("description").asText()) : "";

        // properties as enum array?
        if (defNode.has("properties") && defNode.get("properties").isArray()) {
            List<String> enums = new ArrayList<>();
            for (JsonNode v : defNode.get("properties")) enums.add(v.asText());
            out.add(new FieldDoc(path, typeDisplay(type), desc, enums));
            return;
        }

        // properties as object (inline complex)
        if (defNode.has("properties") && defNode.get("properties").isObject()) {
            out.add(new FieldDoc(path, typeDisplay(type), desc, Collections.emptyList()));
            defNode.get("properties").fields().forEachRemaining(f -> {
                String childPath = path + "." + f.getKey();
                collectFieldDocs(childPath, f.getValue(), dataRoot, typeStack, out, visitedPaths);
            });
            visitedPaths.add(path);
            return;
        }

        // Map type "K;V" → document as object with key/value type
        if (type.contains(";")) {
            out.add(new FieldDoc(path, typeDisplay(type), desc, Collections.emptyList()));
            visitedPaths.add(path);
            return;
        }

        // Primitive or array primitive → leaf
        if (isPrimitiveLike(type)) {
            out.add(new FieldDoc(path, typeDisplay(type), desc, Collections.emptyList()));
            visitedPaths.add(path);
            return;
        }

        // Complex "A::B::C" → resolve and recurse
        if (type.contains("::")) {
            if (typeStack.contains(type)) {
                // cycle break: still document as object
                out.add(new FieldDoc(path, typeDisplay(type), desc, Collections.emptyList()));
                visitedPaths.add(path);
                return;
            }
            typeStack.add(type);
            JsonNode typeDef = resolveNamespacePath(dataRoot, type);
            if (typeDef != null && typeDef.isObject()) {
                // Document the parent node too (helps users see container)
                out.add(new FieldDoc(path, typeDisplay(type), desc, Collections.emptyList()));
                typeDef.fields().forEachRemaining(f -> {
                    String childPath = path + "." + f.getKey();
                    collectFieldDocs(childPath, f.getValue(), dataRoot, typeStack, out, visitedPaths);
                });
            } else {
                out.add(new FieldDoc(path, typeDisplay(type), desc, Collections.emptyList()));
            }
            typeStack.remove(type);
            visitedPaths.add(path);
            return;
        }

        // Fallback
        out.add(new FieldDoc(path, typeDisplay(type), desc, Collections.emptyList()));
        visitedPaths.add(path);
    }

    private static String typeDisplay(String type) {
        if (type == null) return "object";
        // normalize spacing for map types
        if (type.contains(";")) {
            String[] kv = type.split(";", 2);
            return "(" + kv[0].trim() + " → " + kv[1].trim() + ") map";
        }
        return type;
    }

    private static boolean isPrimitiveLike(String t) {
        if (t == null) return false;
        String lower = t.toLowerCase(Locale.ROOT);
        if (t.endsWith("[]")) {
            String base = t.substring(0, t.length() - 2);
            return isPrimitiveLike(base);
        }
        return lower.contains("bool")
                || lower.contains("int") || lower.contains("long") || lower.contains("short")
                || lower.contains("double") || lower.contains("float") || lower.contains("decimal")
                || lower.endsWith("string") || lower.contains("std::string")
                || lower.contains("datetime") || lower.contains("uid");
    }

    // Example output (.QName + minimal fields)
    private static ObjectNode buildExampleOutput(String lib, String yymm, String service, String opName,
                                                 JsonNode outputDef) {
        ObjectNode out = objectNode();

        String opCap = opName.isEmpty() ? opName : (Character.toUpperCase(opName.charAt(0)) + opName.substring(1));
        String qname = QNAME_PREFIX + lib + "/" + yymm + "/" + service + "." + opCap + "Response";
        out.put(".QName", qname);

        if (outputDef != null && outputDef.isObject()) {
            outputDef.fields().forEachRemaining(e -> {
                String k = e.getKey();
                String keyOut = "partialErrors".equals(k) ? "PartialErrors" : k;
                String type = e.getValue().has("type") ? e.getValue().get("type").asText() : "object";
                out.set(keyOut, sampleForExample(type));
            });
        }
        return out;
    }

    // Minimal samples for example (outputs)
    private static JsonNode sampleForExample(String type) {
        if (type == null) return objectNode();
        String t = type.trim();
        if (t.endsWith("[]")) return mapper.createArrayNode();
        if (t.contains(";"))  return objectNode();
        String lower = t.toLowerCase();
        if (lower.contains("bool")) return BooleanNode.FALSE;
        if (lower.contains("int") || lower.contains("long") || lower.contains("short")) return IntNode.valueOf(0);
        if (lower.contains("double") || lower.contains("float") || lower.contains("decimal"))
            return mapper.getNodeFactory().numberNode(0.0);
        if (lower.endsWith("string") || lower.contains("std::string") || lower.contains("datetime") || lower.contains("uid"))
            return TextNode.valueOf("");
        if (t.contains("::")) return objectNode();
        return objectNode();
    }

    // Deep sampler (for request inputs)
    private static JsonNode sampleFromTypeDeep(String type, JsonNode dataRoot, Set<String> stack) {
        if (type == null) return objectNode();
        String trimmedType = type.trim();

        if (trimmedType.endsWith("[]")) {
            String elemType = trimmedType.substring(0, trimmedType.length() - 2);
            ArrayNode arr = mapper.createArrayNode();
            arr.add(sampleFromTypeDeep(elemType, dataRoot, stack));
            return arr;
        }

        int semi = trimmedType.indexOf(';');
        if (semi > 0 && semi < trimmedType.length() - 1) {
            String keyType = trimmedType.substring(0, semi);
            String valType = trimmedType.substring(semi + 1);
            ObjectNode map = objectNode();
            String sampleKey = keyType.toLowerCase().contains("imodelobject") ? "AAAAAAAAAAAAAA" : "SampleKey";
            map.set(sampleKey, sampleFromTypeDeep(valType, dataRoot, stack));
            return map;
        }

        String lower = trimmedType.toLowerCase();
        if (lower.contains("bool")) return BooleanNode.FALSE;
        if (lower.contains("int") || lower.contains("long") || lower.contains("short")) return IntNode.valueOf(0);
        if (lower.contains("double") || lower.contains("float") || lower.contains("decimal"))
            return mapper.getNodeFactory().numberNode(0.0);
        if (lower.endsWith("string") || lower.contains("std::string") || lower.contains("datetime") || lower.contains("uid"))
            return TextNode.valueOf("");

        if (trimmedType.contains("::")) {
            if (stack.contains(trimmedType)) return objectNode();
            stack.add(trimmedType);
            JsonNode def = resolveNamespacePath(dataRoot, trimmedType);
            ObjectNode o = objectNode();
            if (def != null && def.isObject()) {
                def.fields().forEachRemaining(e -> {
                    String fname = e.getKey();
                    JsonNode fdef = e.getValue();
                    String ftype = fdef.has("type") ? fdef.get("type").asText() : "object";
                    if (fdef.has("properties") && fdef.get("properties").isArray() && fdef.get("properties").size() > 0) {
                        o.set(fname, TextNode.valueOf(fdef.get("properties").get(0).asText("")));
                    } else {
                        o.set(fname, sampleFromTypeDeep(ftype, dataRoot, stack));
                    }
                });
            }
            stack.remove(trimmedType);
            return o;
        }
        return objectNode();
    }

    private static JsonNode resolveNamespacePath(JsonNode root, String nsPath) {
        String[] parts = nsPath.split("::");
        JsonNode cur = root;
        for (String p : parts) {
            cur = cur.get(p);
            if (cur == null) return null;
        }
        return cur;
    }

    // HTML → Markdown
    static final class HtmlToMarkdown {
        static String toMarkdown(String html) {
            if (html == null || html.isBlank()) return "";
            Document doc = Jsoup.parse(html);
            Element body = doc.body();
            final StringBuilder sb = new StringBuilder();
            final Deque<String> linkHref = new ArrayDeque<>();
            final Deque<Boolean> codeStack = new ArrayDeque<>();
            final int[] listLevel = {0};

            body.traverse(new NodeVisitor() {
                @Override public void head(org.jsoup.nodes.Node node, int depth) {
                    if (node instanceof Element el) {
                        String tag = el.tagName().toLowerCase(Locale.ROOT);
                        switch (tag) {
                            case "h1","h2","h3","h4","h5","h6" -> {
                                ensureBlankLine(sb);
                                int level = Integer.parseInt(tag.substring(1));
                                sb.append("#".repeat(Math.max(1, level))).append(" ");
                            }
                            case "p" -> ensureBlankLine(sb);
                            case "br" -> sb.append("\n");
                            case "ul","ol" -> { listLevel[0]++; ensureNewline(sb); }
                            case "li" -> {
                                ensureNewline(sb);
                                int indent = Math.max(0, listLevel[0]-1);
                                sb.append("  ".repeat(indent)).append("- ");
                            }
                            case "strong","b" -> sb.append("**");
                            case "em","i","u" -> sb.append("_");
                            case "code" -> { sb.append("`"); codeStack.push(true); }
                            case "pre" -> { ensureNewline(sb); sb.append("```").append("\n"); codeStack.push(true); }
                            case "a" -> { linkHref.push(el.hasAttr("href") ? el.attr("href") : ""); sb.append("["); }
                            case "font" -> {
                                String face = el.attr("face").toLowerCase(Locale.ROOT);
                                if (face.contains("courier")) { sb.append("`"); codeStack.push(true); }
                            }
                            default -> { /* ignore */ }
                        }
                    } else if (node instanceof org.jsoup.nodes.TextNode tn) {
                        String text = tn.getWholeText();
                        boolean inCode = !codeStack.isEmpty() && codeStack.peek();
                        if (!inCode) {
                            text = text.replace('\u00A0',' ').replaceAll("\\s+", " ");
                        }
                        sb.append(text);
                    }
                }
                @Override public void tail(org.jsoup.nodes.Node node, int depth) {
                    if (node instanceof Element el) {
                        String tag = el.tagName().toLowerCase(Locale.ROOT);
                        switch (tag) {
                            case "h1","h2","h3","h4","h5","h6" -> sb.append("\n\n");
                            case "p" -> sb.append("\n\n");
                            case "ul","ol" -> { listLevel[0] = Math.max(0, listLevel[0]-1); sb.append("\n"); }
                            case "li" -> sb.append("\n");
                            case "strong","b" -> sb.append("**");
                            case "em","i","u" -> sb.append("_");
                            case "code" -> { sb.append("`"); if (!codeStack.isEmpty()) codeStack.pop(); }
                            case "pre" -> { sb.append("\n```").append("\n\n"); if (!codeStack.isEmpty()) codeStack.pop(); }
                            case "a" -> {
                                String href = linkHref.isEmpty() ? "" : linkHref.pop();
                                sb.append("]");
                                if (!href.isBlank()) sb.append("(").append(href).append(")");
                            }
                            case "font" -> { if (!codeStack.isEmpty()) { sb.append("`"); codeStack.pop(); } }
                            default -> { /* ignore */ }
                        }
                    }
                }
            });

            String out = sb.toString();
            out = out.replaceAll("[ \\t]+\\n", "\n");
            out = out.replaceAll("\\n{3,}", "\n\n");
            out = out.trim();
            out = out.replaceAll("<[^>]+>", "");
            return out;
        }

        private static void ensureBlankLine(StringBuilder sb) {
            int n = sb.length();
            if (n == 0) return;
            if (n >= 2 && sb.substring(n-2).equals("\n\n")) return;
            if (sb.charAt(n-1) != '\n') sb.append("\n");
            if (n < 2 || !sb.substring(Math.max(0, n-2)).equals("\n\n")) sb.append("\n");
        }
        private static void ensureNewline(StringBuilder sb) {
            int n = sb.length();
            if (n == 0) return;
            if (sb.charAt(n-1) != '\n') sb.append("\n");
        }
    }

    // Utils
    private static String verKeyToPretty(String verKey) {
        Matcher m = Pattern.compile("^_(\\d{4})_(\\d{2})$").matcher(verKey);
        if (m.find()) return m.group(1) + "-" + m.group(2);
        return verKey.startsWith("_") ? verKey.substring(1) : verKey;
    }

    private static String extractRootJson(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0; boolean inStr = false; boolean esc = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static JsonNode parseJson(String s) {
        try {
            JsonFactory jsonFactory = new JsonFactory();
            jsonFactory.enable(JsonParser.Feature.ALLOW_COMMENTS);
            jsonFactory.enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
            jsonFactory.enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES);
            return mapper.readTree(jsonFactory.createParser(s));
        } catch (IOException e) {
            throw new RuntimeException("JSON parse error: " + e.getMessage(), e);
        }
    }

    private static ObjectNode objectNode() { return mapper.createObjectNode(); }

    // ----- small holder for field docs -----
    private static final class FieldDoc {
        final String path;
        final String type;
        final String description;
        final List<String> enumVals;
        FieldDoc(String path, String type, String description, List<String> enumVals) {
            this.path = path; this.type = type; this.description = description;
            this.enumVals = enumVals == null ? Collections.emptyList() : enumVals;
        }
    }
}