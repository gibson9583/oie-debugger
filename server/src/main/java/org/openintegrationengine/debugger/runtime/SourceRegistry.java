package org.openintegrationengine.debugger.runtime;

import org.mozilla.javascript.debug.DebuggableScript;
import org.mozilla.javascript.ContextFactory;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Associates exact compilation callback text with compiled identities, never a mutable engine source cache. */
final class SourceRegistry {
    static final int MAX_SOURCES = 256;
    // Exact text follows the lifetime of live engine scripts. Source must never reference its compiled keys.
    private final Map<DebuggableScript, Source> compiled = new WeakHashMap<>();
    // Bound only the extra retention of recent sources whose scripts may already have been collected.
    private final LinkedHashMap<String, Source> sources = new LinkedHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private long characters;

    synchronized Set<String> register(ContextFactory factory, DebuggableScript script, String text) {
        if (text == null || text.length() > 1_000_000 || !script.isTopLevel()) return Set.of();
        Set<String> invalidated = new HashSet<>();
        boolean dynamic = script.isGeneratedScript();
        // Separate eval invocations share a source name but may be executing concurrently.
        if (!dynamic && script.getSourceName() != null) {
            for (Source existing : retainedSources()) {
                if (existing.valid && !existing.dynamic && existing.factory.get() == factory && existing.name.equals(script.getSourceName())) {
                    existing.valid = false;
                    invalidated.add(existing.id);
                    if (sources.remove(existing.id) != null) characters -= existing.text.length();
                }
            }
        }
        Source source = new Source(factory, script.getSourceName(), text, generation.incrementAndGet(), dynamic);
        bind(script, source);
        sources.put(source.id, source);
        characters += text.length();
        while (sources.size() > MAX_SOURCES || characters > 16_000_000) {
            Source removed = sources.remove(sources.keySet().iterator().next());
            characters -= removed.text.length();
        }
        if (!invalidated.isEmpty()) compiled.values().removeIf(value -> invalidated.contains(value.id));
        return invalidated;
    }

    private Set<Source> retainedSources() {
        Set<Source> retained = new LinkedHashSet<>(sources.values());
        retained.addAll(compiled.values());
        return retained;
    }

    private void bind(DebuggableScript script, Source source) {
        compiled.put(script, source);
        for (int line : script.getLineNumbers()) source.lines.add(line);
        for (int i = 0; i < script.getFunctionCount(); i++) bind(script.getFunction(i), source);
    }

    synchronized Source compiled(DebuggableScript script) {
        Source source = compiled.get(script);
        return source != null && source.valid ? source : null;
    }

    synchronized void observe(Source source, String channel) {
        if (source.valid && !source.invalidChannels.contains(channel)) source.channels.add(channel);
    }

    synchronized Source require(String id) {
        Source source = sources.get(id);
        if (source == null) source = compiled.values().stream().filter(s -> s.id.equals(id)).findFirst().orElse(null);
        if (source == null || !source.valid) throw new IllegalArgumentException("Source is unavailable or invalidated; execute the newly deployed script first");
        return source;
    }

    synchronized List<Map<String, Object>> list(String channel) {
        return retainedSources().stream().filter(s -> s.valid && s.channels.contains(channel)).map(s -> s.dto(false)).toList();
    }

    synchronized Map<String, Object> detail(String id) { return require(id).dto(true); }

    synchronized void invalidate(String channel) {
        for (Source source : retainedSources()) if (source.channels.remove(channel)) source.invalidChannels.add(channel);
    }

    synchronized void clear() { retainedSources().forEach(s -> s.valid = false); sources.clear(); compiled.clear(); characters = 0; }

    static final class Source {
        final String id = UUID.randomUUID().toString();
        final String name, text, hash, kind;
        final boolean dynamic;
        final long generation;
        final long compiledAt = System.currentTimeMillis();
        final WeakReference<ContextFactory> factory;
        final Set<Integer> lines = new TreeSet<>();
        final Set<String> channels = new TreeSet<>();
        final Set<String> invalidChannels = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile boolean valid = true;

        Source(ContextFactory factory, String name, String text, long generation, boolean dynamic) {
            this.factory = new WeakReference<>(factory);
            this.name = name == null ? "(anonymous)" : name;
            this.text = text;
            this.generation = generation;
            this.dynamic = dynamic;
            this.hash = sha256(text);
            String lower = this.name.toLowerCase(Locale.ROOT);
            this.kind = List.of("response", "preprocessor", "postprocessor", "attachment", "batch", "undeploy", "deploy", "transformer", "filter", "connector", "global")
                    .stream().filter(lower::contains).findFirst().orElse("javascript");
        }

        Map<String, Object> dto(boolean includeText) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id); value.put("name", name); value.put("hash", hash); value.put("generation", generation);
            value.put("compiledAt", compiledAt);
            value.put("dynamic", dynamic);
            value.put("lineCount", text.lines().count()); value.put("breakableLines", List.copyOf(lines));
            value.put("channels", List.copyOf(channels)); value.put("kind", kind);
            if (includeText) value.put("text", text);
            return value;
        }

        private static String sha256(String value) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
            catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }
}
