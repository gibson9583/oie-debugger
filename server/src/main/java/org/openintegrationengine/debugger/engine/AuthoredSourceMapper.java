package org.openintegrationengine.debugger.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.mozilla.javascript.NativeFunction;
import org.mozilla.javascript.debug.DebuggableScript;
import org.openintegrationengine.debugger.runtime.DebugRuntime;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.DeployedChannelInfo;
import com.mirth.connect.model.FilterTransformerElement;
import com.mirth.connect.model.Transformer;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.CodeTemplateController;
import com.mirth.connect.server.controllers.ScriptController;
import com.mirth.connect.server.util.CompiledScriptCache;
import com.mirth.connect.util.CodeTemplateUtil;

/** Optional navigation hints anchored to exact, unambiguous text in one captured runtime source. */
public final class AuthoredSourceMapper {
    private AuthoredSourceMapper() { }

    public static boolean isCurrentSource(String sourceId, String sourceName, DebugRuntime runtime) {
        try {
            DebuggableScript compiled = currentScript(sourceName);
            return compiled != null && runtime.isSourceScript(sourceId, compiled)
                    && currentScript(sourceName) == compiled;
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    public static List<Map<String, Object>> map(String channelId, String sourceId, String sourceName,
            String generatedSource, DebugRuntime runtime) {
        try {
            DebuggableScript compiled = currentScript(sourceName);
            if (compiled == null || !runtime.isSourceScript(sourceId, compiled)) return List.of();
            List<Map<String, Object>> mappings = mapCurrent(channelId, sourceId, generatedSource);
            // Only the immutable source callback and exact compiled identity provide provenance.
            // Never read the cache's separately mutable source text during this verification.
            if (currentScript(sourceName) != compiled || !runtime.isSourceScript(sourceId, compiled)) return List.of();
            return mappings;
        } catch (RuntimeException | LinkageError unavailable) { return List.of(); }
    }

    static List<Map<String, Object>> mapCurrent(String channelId, String sourceId, String generatedSource) {
        try {
            List<Snippet> candidates = new ArrayList<>();
            if ("global".equals(channelId)) {
                for (Map.Entry<String, String> entry : ScriptController.getInstance().getGlobalScripts().entrySet()) {
                    add(candidates, "global:" + entry.getKey(), "Global " + entry.getKey(), "global", null, entry.getValue());
                }
            } else {
                ChannelController channels = ChannelController.getInstance();
                Channel channel = channels.getDeployedChannelById(channelId);
                DeployedChannelInfo deployment = channels.getDeployedChannelInfoById(channelId);
                if (channel == null || deployment == null || channel.getRevision() != deployment.getDeployedRevision()) {
                    return List.of();
                }
                Integer revision = channel.getRevision();
                add(candidates, "channel:deploy", "Deploy", "lifecycle", revision, channel.getDeployScript());
                add(candidates, "channel:undeploy", "Undeploy", "lifecycle", revision, channel.getUndeployScript());
                add(candidates, "channel:preprocessor", "Preprocessor", "preprocessor", revision, channel.getPreprocessingScript());
                add(candidates, "channel:postprocessor", "Postprocessor", "postprocessor", revision, channel.getPostprocessingScript());
                if (channel.getProperties() != null && channel.getProperties().getAttachmentProperties() != null) {
                    add(candidates, "channel:attachment", "Attachment", "attachment", revision,
                            channel.getProperties().getAttachmentProperties().getProperties().get("javascript.script"));
                }
                connector(candidates, channel.getSourceConnector(), revision);
                if (channel.getDestinationConnectors() != null) {
                    for (Connector connector : channel.getDestinationConnectors()) connector(candidates, connector, revision);
                }
                Map<String, Integer> templateRevisions = deployment.getCodeTemplateRevisions();
                if (templateRevisions != null && !templateRevisions.isEmpty()) {
                    for (CodeTemplate template : CodeTemplateController.getInstance().getCodeTemplates(templateRevisions.keySet())) {
                        Integer expectedRevision = templateRevisions.get(template.getId());
                        if (expectedRevision == null || !Objects.equals(template.getRevision(), expectedRevision)) continue;
                        String authored = template.getCode();
                        String runtime = CodeTemplateUtil.stripDocumentation(authored);
                        if (authored == null || runtime == null || runtime.isBlank()) continue;
                        int offset = authored.indexOf(runtime);
                        if (offset < 0 || authored.indexOf(runtime, offset + 1) >= 0) continue;
                        candidates.add(new Snippet("template:" + template.getId(), template.getName(), "code-template",
                                template.getRevision(), runtime, lineAt(authored, offset)));
                    }
                }
                // The engine's separate deployed caches can change during redeployment. No mapping
                // is safer than pairing a source with a revision observed from different generations.
                if (channels.getDeployedChannelById(channelId) != channel
                        || channels.getDeployedChannelInfoById(channelId) != deployment) return List.of();
            }
            return match(sourceId, generatedSource, candidates);
        } catch (Exception | LinkageError unavailable) {
            // Mapping is ancillary: exact generated source remains authoritative and usable.
            return List.of();
        }
    }

    private static DebuggableScript currentScript(String sourceName) {
        Object script = CompiledScriptCache.getInstance().getCompiledScript(sourceName);
        return script instanceof NativeFunction function ? function.getDebuggableView() : null;
    }

    static List<Map<String, Object>> match(String sourceId, String generated, List<Snippet> snippets) {
        if (generated == null || generated.isEmpty()) return List.of();
        List<Match> matches = new ArrayList<>();
        for (Snippet snippet : snippets) {
            String code = snippet.code();
            if (code == null || code.isBlank()) continue;
            int start = generated.indexOf(code);
            if (start < 0 || generated.indexOf(code, start + 1) >= 0) continue;
            int end = start + code.length();
            // Do not label a matching fragment embedded in another statement or string literal.
            if (start > 0 && !newline(generated.charAt(start - 1))) continue;
            int after = end;
            while (after < generated.length() && (generated.charAt(after) == ' ' || generated.charAt(after) == '\t')) after++;
            if (after < generated.length() && !newline(generated.charAt(after))) continue;
            matches.add(new Match(snippet, start, end));
        }
        matches.sort(Comparator.comparingInt(Match::start));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            Match match = matches.get(i);
            boolean ambiguous = false;
            for (int j = 0; j < matches.size(); j++) {
                Match other = matches.get(j);
                if (i != j && match.start() < other.end() && other.start() < match.end()) {
                    ambiguous = true;
                    break;
                }
            }
            if (ambiguous) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sourceId", sourceId);
            entry.put("authoredId", match.snippet().id());
            entry.put("name", match.snippet().name());
            entry.put("kind", match.snippet().kind());
            entry.put("revision", match.snippet().revision());
            entry.put("generatedStartLine", lineAt(generated, match.start()));
            entry.put("generatedEndLine", lineAt(generated, Math.max(match.start(), match.end() - 1)));
            entry.put("authoredStartLine", match.snippet().authoredStartLine());
            entry.put("text", match.snippet().code());
            result.add(entry);
        }
        return result;
    }

    private static void connector(List<Snippet> snippets, Connector connector, Integer revision) {
        if (connector == null) return;
        String id = "connector:" + connector.getMetaDataId();
        String name = connector.getName();
        if (connector.getFilter() != null) {
            elements(snippets, id + ":filter", name + " filter", "filter", revision, connector.getFilter().getEnabledElements());
        }
        transformer(snippets, id + ":transformer", name + " transformer", "transformer", revision, connector.getTransformer());
        transformer(snippets, id + ":response", name + " response", "response-transformer", revision, connector.getResponseTransformer());
        Object properties = connector.getProperties();
        if (properties != null && (properties.getClass().getName().equals("com.mirth.connect.connectors.js.JavaScriptReceiverProperties")
                || properties.getClass().getName().equals("com.mirth.connect.connectors.js.JavaScriptDispatcherProperties"))) {
            try {
                // Connector extensions are optional JARs. Only the two engine-defined property
                // classes with this documented String getter are read, never arbitrary beans.
                add(snippets, id + ":script", name, "connector", revision,
                        (String) properties.getClass().getMethod("getScript").invoke(properties));
            } catch (ReflectiveOperationException unavailable) { /* Exact generated source still works. */ }
        }
    }

    private static void transformer(List<Snippet> snippets, String id, String name, String kind,
            Integer revision, Transformer transformer) {
        if (transformer == null) return;
        elements(snippets, id, name, kind, revision, transformer.getEnabledElements());
        if (transformer.getInboundProperties() != null && transformer.getInboundProperties().getBatchProperties() != null) {
            add(snippets, id + ":batch", name + " batch", "batch", revision,
                    transformer.getInboundProperties().getBatchProperties().getBatchScript());
        }
    }

    private static void elements(List<Snippet> snippets, String id, String name, String kind,
            Integer revision, List<? extends FilterTransformerElement> elements) {
        int index = 0;
        for (FilterTransformerElement element : elements) {
            index++;
            // Other step/rule types generate code; calling them authored JavaScript is misleading.
            if ("JavaScript".equals(element.getType())) {
                try {
                    add(snippets, id + ':' + index, name + ": " + element.getName(), kind, revision, element.getScript(false));
                } catch (com.mirth.connect.util.ScriptBuilderException unavailable) { /* No inferred mapping. */ }
            }
        }
    }

    private static void add(List<Snippet> snippets, String id, String name, String kind, Integer revision, String code) {
        if (code != null && !code.isBlank()) snippets.add(new Snippet(id, name, kind, revision, code, 1));
    }

    static int lineAt(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            char current = source.charAt(i);
            if (current == '\r') {
                line++;
                if (i + 1 < offset && source.charAt(i + 1) == '\n') i++;
            } else if (current == '\n' || current == '\u2028' || current == '\u2029') line++;
        }
        return line;
    }

    private static boolean newline(char value) { return value == '\r' || value == '\n' || value == '\u2028' || value == '\u2029'; }

    record Snippet(String id, String name, String kind, Integer revision, String code, int authoredStartLine) { }
    private record Match(Snippet snippet, int start, int end) { }
}
