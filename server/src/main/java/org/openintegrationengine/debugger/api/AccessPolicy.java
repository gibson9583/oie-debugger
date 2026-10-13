package org.openintegrationengine.debugger.api;

import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Optional installation restrictions; engine operation and channel authorization always apply. */
public final class AccessPolicy {
    private final Set<String> users;
    private final Set<String> evaluators;
    private final Set<String> globalUsers;
    private final Set<String> channels;
    public final boolean enabled;
    public final boolean evaluationEnabled;

    public AccessPolicy(Properties properties) {
        enabled = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
        evaluationEnabled = Boolean.parseBoolean(properties.getProperty("evaluationEnabled", "true"));
        users = values(properties, "allowedUserIds");
        evaluators = values(properties, "evaluationUserIds");
        globalUsers = values(properties, "globalUserIds");
        channels = values(properties, "allowedChannelIds");
    }
    private static Set<String> values(Properties p, String key) {
        return Arrays.stream(p.getProperty(key, "").split(",")).map(String::trim)
            .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
    private static boolean permits(Set<String> restrictions, int user) {
        return restrictions.isEmpty() || restrictions.contains(Integer.toString(user));
    }
    public boolean allows(int user) { return enabled && permits(users, user); }
    public boolean evaluates(int user) { return allows(user) && evaluationEnabled && permits(evaluators, user); }
    public boolean global(int user) { return allows(user) && permits(globalUsers, user); }
    public boolean channel(String id) { return id != null && !id.isBlank() && (channels.isEmpty() || channels.contains("*") || channels.contains(id)); }
}
