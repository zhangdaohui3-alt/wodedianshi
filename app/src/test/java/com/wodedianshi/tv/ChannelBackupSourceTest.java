package com.wodedianshi.tv;

import org.junit.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class ChannelBackupSourceTest {
    private Object newChannel() throws Exception {
        Class<?> type = Class.forName("com.wodedianshi.tv.MainActivity$Channel");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("CCTV-1", "https://a.example/live.m3u8", "央视");
    }

    private void add(Object channel, String methodName, String url, String scope) throws Exception {
        Method method = channel.getClass().getDeclaredMethod(methodName, String.class, String.class);
        method.setAccessible(true);
        method.invoke(channel, url, scope);
    }

    @SuppressWarnings("unchecked")
    private List<String> backups(Object channel) throws Exception {
        Field field = channel.getClass().getDeclaredField("backups");
        field.setAccessible(true);
        return (List<String>) field.get(channel);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> backupScopes(Object channel) throws Exception {
        Field field = channel.getClass().getDeclaredField("backupSourceScopes");
        field.setAccessible(true);
        return (Map<String, Set<String>>) (Map<?, ?>) field.get(channel);
    }

    @SuppressWarnings("unchecked")
    private Set<String> sourceScopes(Object channel) throws Exception {
        Field field = channel.getClass().getDeclaredField("sourceScopes");
        field.setAccessible(true);
        return (Set<String>) field.get(channel);
    }

    @Test public void knownUniqueRouteIsAddedAndItsScopeIsTracked() throws Exception {
        Object channel = newChannel();
        add(channel, "addBackupSourceKnownUnique", "https://b.example/live.m3u8", "china");
        assertEquals(2, backups(channel).size());
        assertTrue(backupScopes(channel).get("https://b.example/live.m3u8").contains("china"));
        assertTrue(sourceScopes(channel).contains("china"));
    }

    @Test public void ordinaryAdditionStillDeduplicatesRepeatedUrl() throws Exception {
        Object channel = newChannel();
        add(channel, "addBackupSource", "https://a.example/live.m3u8", "china");
        assertEquals(1, backups(channel).size());
    }

    @Test public void knownUniqueAdditionDoesNotRemoveExistingBackups() throws Exception {
        Object channel = newChannel();
        add(channel, "addBackupSourceKnownUnique", "https://b.example/live.m3u8", "china");
        add(channel, "addBackupSourceKnownUnique", "https://c.example/live.m3u8", "global");
        assertEquals(3, backups(channel).size());
        assertTrue(backupScopes(channel).get("https://c.example/live.m3u8").contains("global"));
    }
}
