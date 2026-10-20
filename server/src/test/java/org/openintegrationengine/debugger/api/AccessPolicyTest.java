package org.openintegrationengine.debugger.api;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.debugger.DebuggerPlugin;
import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {
    private static Properties enabledProperties() {
        Properties properties = new Properties();
        properties.setProperty("enabled", "true");
        return properties;
    }

    @Test void missingEnabledPropertyDefaultsOn() {
        assertTrue(new AccessPolicy(new Properties()).enabled);
    }

    @Test void absentRestrictionsDeferAllCapabilitiesToNativeEngineAuthorization() {
        AccessPolicy policy = new AccessPolicy(enabledProperties());
        assertTrue(policy.allows(7)); assertTrue(policy.evaluates(7)); assertTrue(policy.global(7));
        assertTrue(policy.channel("channel-a")); assertTrue(policy.channel("channel-b"));
        assertFalse(policy.channel(null)); assertFalse(policy.channel("")); assertFalse(policy.channel(" "));
    }

    @Test void previouslyPersistedEmptyAndWhitespaceListsAlsoInheritNativeAuthorization() {
        Properties properties = enabledProperties();
        properties.setProperty("allowedUserIds", ""); properties.setProperty("evaluationUserIds", " , \t, ");
        properties.setProperty("globalUserIds", " "); properties.setProperty("allowedChannelIds", ",,");
        AccessPolicy policy = new AccessPolicy(properties);
        assertTrue(policy.allows(7)); assertTrue(policy.evaluates(7)); assertTrue(policy.global(7)); assertTrue(policy.channel("channel-a"));
    }

    @Test void shippedDefaultsEnableEvaluationSubjectToNativePermission() {
        Properties properties = new DebuggerPlugin().getDefaultProperties();
        assertEquals("true", properties.getProperty("enabled"));
        assertTrue(new AccessPolicy(properties).allows(7));
        assertEquals("true", properties.getProperty("evaluationEnabled"));
        properties.setProperty("enabled", "true");
        AccessPolicy policy = new AccessPolicy(properties);
        assertTrue(policy.allows(7)); assertTrue(policy.evaluates(7)); assertTrue(policy.global(7)); assertTrue(policy.channel("channel-a"));
    }

    @Test void explicitEvaluationKillSwitchSurvivesOtherwiseEmptyLegacyConfiguration() {
        Properties properties = enabledProperties(); properties.setProperty("allowedUserIds", "");
        properties.setProperty("evaluationUserIds", ""); properties.setProperty("evaluationEnabled", "false");
        AccessPolicy policy = new AccessPolicy(properties);
        assertTrue(policy.allows(7)); assertFalse(policy.evaluationEnabled); assertFalse(policy.evaluates(7));
        assertTrue(policy.global(7)); assertTrue(policy.channel("channel-a"));
    }

    @Test void nonemptyEvaluationGlobalAndChannelRestrictionsRemainIndependent() {
        Properties properties = enabledProperties();
        properties.setProperty("allowedUserIds", "1, 2"); properties.setProperty("allowedChannelIds", "channel-a");
        properties.setProperty("evaluationUserIds", "2,3"); properties.setProperty("globalUserIds", "1,3");
        AccessPolicy policy = new AccessPolicy(properties);
        assertTrue(policy.allows(1)); assertFalse(policy.evaluates(1)); assertTrue(policy.evaluates(2)); assertFalse(policy.evaluates(3));
        assertTrue(policy.global(1)); assertFalse(policy.global(2)); assertFalse(policy.global(3));
        assertTrue(policy.channel("channel-a")); assertFalse(policy.channel("channel-b"));
    }

    @Test void emptySubordinateListsDoNotBypassAnExplicitUserRestriction() {
        Properties properties = enabledProperties(); properties.setProperty("allowedUserIds", "7");
        AccessPolicy policy = new AccessPolicy(properties);
        assertTrue(policy.allows(7)); assertTrue(policy.evaluates(7)); assertTrue(policy.global(7));
        assertFalse(policy.allows(8)); assertFalse(policy.evaluates(8)); assertFalse(policy.global(8));
    }

    @Test void disabledPluginRemainsAnExplicitKillSwitch() {
        Properties properties = enabledProperties(); properties.setProperty("enabled", "false");
        AccessPolicy policy = new AccessPolicy(properties);
        assertFalse(policy.allows(7)); assertFalse(policy.evaluates(7)); assertFalse(policy.global(7));
    }

    @Test void wildcardIsOnlySupportedForChannels() {
        Properties properties = enabledProperties(); properties.setProperty("allowedUserIds", "*"); properties.setProperty("allowedChannelIds", "*");
        AccessPolicy policy = new AccessPolicy(properties);
        assertFalse(policy.allows(7)); assertTrue(policy.channel("channel-b")); assertFalse(policy.channel(""));
    }
}
