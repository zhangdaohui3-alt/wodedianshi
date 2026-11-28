package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import static org.junit.Assert.*;

public class SourceSelectionPolicyTest {
    private final java.util.List<String> sources = Arrays.asList("a", "b", "c", "d");

    @Test public void queuedSourceResolvesByExactUrlAndCurrentTune() {
        Object channel=new Object();
        java.util.List<String> reordered=Arrays.asList("c","a","d","b");
        assertEquals(2,SourceSelectionPolicy.resolveQueuedManualSelection(channel,channel,7L,7L,
                reordered,"a","a","d",Collections.emptySet()));
        assertEquals(-1,SourceSelectionPolicy.resolveQueuedManualSelection(channel,new Object(),7L,7L,
                reordered,"a","a","d",Collections.emptySet()));
        assertEquals(-1,SourceSelectionPolicy.resolveQueuedManualSelection(channel,channel,7L,8L,
                reordered,"a","a","d",Collections.emptySet()));
        assertEquals(-1,SourceSelectionPolicy.resolveQueuedManualSelection(channel,channel,7L,7L,
                reordered,"a","c","d",Collections.emptySet()));
        assertEquals(-1,SourceSelectionPolicy.resolveQueuedManualSelection(channel,channel,7L,7L,
                reordered,"a","a","a",Collections.emptySet()));
        assertEquals(-1,SourceSelectionPolicy.resolveQueuedManualSelection(channel,channel,7L,7L,
                reordered,"a","a","d",Collections.singleton("d")));
        assertEquals(-1,SourceSelectionPolicy.resolveQueuedManualSelection(channel,channel,7L,7L,
                reordered,"a","a","missing",Collections.emptySet()));
    }

    @Test public void wrapIndexHandlesNegativeAndOversizedValues() {
        assertEquals(3, SourceSelectionPolicy.wrapIndex(-1, 4));
        assertEquals(2, SourceSelectionPolicy.wrapIndex(6, 4));
        assertEquals(-1, SourceSelectionPolicy.wrapIndex(0, 0));
    }

    @Test public void prefersFirstUnblockedSourceOutsideCooldown() {
        assertEquals(3, SourceSelectionPolicy.findNext(
                sources, 1, Collections.singleton("c"), Collections.emptySet()));
    }

    @Test public void skipsCooldownWhenAnotherSourceIsHealthy() {
        assertEquals(3, SourceSelectionPolicy.findNext(
                sources, 1, Collections.emptySet(), new HashSet<>(Arrays.asList("c"))));
    }

    @Test public void triesFirstUnblockedSourceWhenAllAreCoolingDown() {
        assertEquals(2, SourceSelectionPolicy.findNext(
                sources, 1, Collections.emptySet(), new HashSet<>(Arrays.asList("c", "d", "a"))));
    }

    @Test public void returnsMinusOneWhenAllAlternatesAreBlocked() {
        assertEquals(-1, SourceSelectionPolicy.findNext(
                sources, 1, new HashSet<>(Arrays.asList("a", "c", "d")), Collections.emptySet()));
    }

    @Test public void requiresAtLeastTwoSources() {
        assertEquals(-1, SourceSelectionPolicy.findNext(
                Collections.singletonList("only"), 0, Collections.emptySet(), Collections.emptySet()));
        assertEquals(-1, SourceSelectionPolicy.findNext(
                null, 0, Collections.emptySet(), Collections.emptySet()));
    }

    @Test public void skipsNullAndBlankBackupEntries() {
        assertEquals(4, SourceSelectionPolicy.findNext(
                Arrays.asList("a", "b", null, " ", "d"), 0,
                Collections.singleton("b"), Collections.emptySet()));
    }

    @Test public void doesNotTreatDuplicateActiveUrlAsARealBackup() {
        assertEquals(-1, SourceSelectionPolicy.findNext(
                Arrays.asList("a", "b", "a"), 0,
                Collections.singleton("b"), Collections.emptySet()));
    }

    @Test public void treatsSchemeHostCaseAndDefaultPortAsSameEndpoint() {
        assertEquals(-1, SourceSelectionPolicy.findNext(
                Arrays.asList("HTTP://Example.COM:80/live.m3u8", "http://example.com/live.m3u8"),
                0, Collections.emptySet(), Collections.emptySet()));
    }

    @Test public void skipsEquivalentEndpointAndContinuesToDistinctBackup() {
        assertEquals(2, SourceSelectionPolicy.findNext(
                Arrays.asList("https://EXAMPLE.com:443/live.m3u8",
                        "https://example.com/live.m3u8", "https://backup.example/live.m3u8"),
                0, Collections.emptySet(), Collections.emptySet()));
    }

    @Test public void workerCandidateReusesEndpointWithoutRewritingRequestUrl() {
        String raw="https://EXAMPLE.invalid:443/Live?token=AbC";
        SourceUpdateWorker.Entry entry=new SourceUpdateWorker.Entry("test",raw,"央视");
        String endpoint=entry.endpoint();
        assertEquals("https://example.invalid/Live?token=AbC",endpoint);
        assertSame(endpoint,entry.endpoint());
        assertEquals(raw,entry.url);
        SourceUpdateWorker.Entry distinct=new SourceUpdateWorker.Entry("test",
                "https://example.invalid/Live?token=abc","央视");
        assertNotEquals(endpoint,distinct.endpoint());
    }

    @Test public void canonicalEndpointKeyMatchesSchemeHostAndDefaultPortAliases() {
        assertEquals(SourceSelectionPolicy.endpointKey("HTTP://Example.COM:80/live.m3u8"),
                SourceSelectionPolicy.endpointKey("http://example.com/live.m3u8"));
    }

    @Test public void canonicalEndpointKeyPreservesCaseSensitivePathAndQuery() {
        assertNotEquals(SourceSelectionPolicy.endpointKey("http://example.com/Live?token=AbC"),
                SourceSelectionPolicy.endpointKey("http://example.com/live?token=AbC"));
        assertNotEquals(SourceSelectionPolicy.endpointKey("http://example.com/live?token=AbC"),
                SourceSelectionPolicy.endpointKey("http://example.com/live?token=abc"));
    }

    @Test public void blockedEndpointAliasesAreAlsoBlocked() {
        assertEquals(2, SourceSelectionPolicy.findNext(
                Arrays.asList("http://active/live", "HTTP://Blocked.Example:80/live",
                        "http://backup.example/live"),
                0, Collections.singleton("http://blocked.example/live"), Collections.emptySet()));
    }

    @Test public void cooldownEndpointAliasesAreSkippedWhenHealthyBackupExists() {
        assertEquals(2, SourceSelectionPolicy.findNext(
                Arrays.asList("http://active/live", "HTTP://Slow.Example:80/live",
                        "http://backup.example/live"),
                0, Collections.emptySet(), Collections.singleton("http://slow.example/live")));
    }

    @Test public void multipleBlockedAndCoolingAliasesPreserveSignedUrlAndFallbackOrder() {
        java.util.List<String> routes = Arrays.asList("http://active/live",
                "HTTP://Blocked.Example:80/live", "https://SLOW.example:443/Live?token=A",
                "https://slow.example/Live?token=B", "http://other/live");
        java.util.Set<String> blocked = new HashSet<>(Arrays.asList("http://blocked.example/live", "http://other/live"));
        java.util.Set<String> cooling = new HashSet<>(Arrays.asList("https://slow.example/Live?token=A", "https://elsewhere/live"));
        assertEquals(3, SourceSelectionPolicy.findNext(routes, 0, blocked, cooling));
        cooling.add("https://slow.example/Live?token=B");
        assertEquals(2, SourceSelectionPolicy.findNext(routes, 0, blocked, cooling));
        assertEquals(1, SourceSelectionPolicy.findNext(routes, 0, null, null));
    }

    @Test public void skipsDuplicateActiveUrlAndContinuesToDistinctBackup() {
        assertEquals(3, SourceSelectionPolicy.findNext(
                Arrays.asList("a", "b", "a", "c"), 0,
                Collections.singleton("b"), Collections.emptySet()));
    }

    @Test public void sourceMenuFollowsExactUrlAfterReorderingButRejectsStaleChannel() {
        Object channel = new Object();
        java.util.List<String> reordered = Arrays.asList("b", "a", "c");
        assertEquals(0, SourceSelectionPolicy.resolveManualSelection(channel, channel, reordered, "b"));
        assertEquals(-1, SourceSelectionPolicy.resolveManualSelection(channel, new Object(), reordered, "b"));
        assertEquals(-1, SourceSelectionPolicy.resolveManualSelection(channel, channel, reordered, "removed"));
        assertEquals(-1, SourceSelectionPolicy.resolveManualSelection(channel, channel, reordered, ""));
        assertEquals(-1, SourceSelectionPolicy.resolveManualSelection(channel, channel,
                Collections.singletonList("https://example.com/live?token=new"),
                "https://example.com/live?token=old"));
    }

    @Test public void rememberedSourceMatchesHostCaseAndDefaultPortAliases() {
        assertEquals(1, SourceSelectionPolicy.findEquivalentSource(
                Arrays.asList("http://other/live", "HTTP://Example.COM:80/live.m3u8"),
                "http://example.com/live.m3u8"));
    }

    @Test public void rememberedSourceDoesNotIgnoreSignedPathOrQueryDifferences() {
        assertEquals(-1, SourceSelectionPolicy.findEquivalentSource(
                Collections.singletonList("https://example.com/live?token=old"),
                "https://example.com/live?token=new"));
        assertEquals(-1, SourceSelectionPolicy.findEquivalentSource(
                Collections.singletonList("https://example.com/Live"),
                "https://example.com/live"));
    }

    @Test public void missingRememberedSourceDoesNotInventAnIndex() {
        assertEquals(-1, SourceSelectionPolicy.findEquivalentSource(
                Collections.singletonList("https://example.com/current"),
                "https://example.com/removed"));
    }

    @Test public void scanCursorFollowsLastUrlWhenPlaylistEntriesAreInserted() {
        java.util.List<String> oldList = Arrays.asList("a", "b", "c");
        String last = SourceSelectionPolicy.lastScannedKey(oldList, 0, 2);
        java.util.List<String> changed = Arrays.asList("new", "a", "b", "c");
        assertEquals(3, SourceSelectionPolicy.nextScanStart(changed, last, 2));
    }

    @Test public void scanCursorUsesLegacyIndexWhenLastUrlDisappears() {
        assertEquals(2, SourceSelectionPolicy.nextScanStart(
                Arrays.asList("a", "b", "c"), "missing-key", 5));
    }

    @Test public void scanCursorWrapsAtEndOfPlaylist() {
        java.util.List<String> urls = Arrays.asList("a", "b", "c");
        String last = SourceSelectionPolicy.lastScannedKey(urls, 1, 2);
        assertEquals(0, SourceSelectionPolicy.nextScanStart(urls, last, 0));
    }

    @Test public void largeCursorSearchPreservesLastDuplicateAndStopsAtExactMatch() {
        int size=20000;
        String target="https://example.invalid/last";
        String key=StableKey.url(target);
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(0,SourceSelectionPolicy.nextScanStart(size,i->{
            reads.incrementAndGet();
            return i==2||i==size-1?target:"https://example.invalid/"+i;
        },key,0));
        assertEquals(1,reads.get());
        reads.set(0);
        assertEquals(7,SourceSelectionPolicy.nextScanStart(size,i->{
            reads.incrementAndGet();return "https://example.invalid/"+i;
        },key,7));
        assertEquals(size,reads.get());
        reads.set(0);
        assertEquals(StableKey.url(target),SourceSelectionPolicy.lastScannedKey(size,i->{
            reads.incrementAndGet();assertEquals(size-1,i);return target;
        },size-2,2));
        assertEquals(1,reads.get());
    }

    @Test public void emptyScanDoesNotCreateCursorKey() {
        assertEquals("", SourceSelectionPolicy.lastScannedKey(
                Arrays.asList("a", "b"), 0, 0));
    }

    @Test public void channelFailoverStopsWhenThereIsOnlyOneChannel() {
        assertEquals(-1, ChannelFailoverPolicy.nextChannelIndex(1, 0, 0));
    }

    @Test public void channelFailoverStopsAfterEveryOtherChannelWasTried() {
        assertEquals(-1, ChannelFailoverPolicy.nextChannelIndex(4, 2, 3));
    }

    @Test public void channelFailoverAdvancesAndWrapsAround() {
        assertEquals(0, ChannelFailoverPolicy.nextChannelIndex(4, 3, 1));
        assertEquals(2, ChannelFailoverPolicy.nextChannelIndex(4, 1, 0));
    }

    @Test public void channelFailoverRejectsEmptyListAndInvalidIndex() {
        assertEquals(-1, ChannelFailoverPolicy.nextChannelIndex(0, 0, 0));
        assertEquals(-1, ChannelFailoverPolicy.nextChannelIndex(3, -1, 0));
    }

    @Test public void bulkBlockMatchingPreservesSingleSourceSemantics() {
        String url="https://example.org/live?token=A";
        String alias="https://EXAMPLE.org:443/live?token=A";
        java.util.List<String> sources=Arrays.asList(null,""," ",url,alias,"https://example.org/live?token=B");
        java.util.Set<String> records=new java.util.HashSet<>();
        for(String identity:Arrays.asList(null,"","CCTV-1","CCTV-2")){
            assertEquals(Collections.emptySet(),SourceSelectionPolicy.blockedUrls(records,identity,sources));
        }
        records.add(url);
        records.add(" ");
        records.add(SourceSelectionPolicy.channelBlockKey("CCTV-1",url));
        for(String identity:Arrays.asList(null,"","CCTV-1","CCTV-2")){
            java.util.Set<String> expected=new java.util.HashSet<>();
            for(String source:sources)if(SourceSelectionPolicy.isUserBlocked(records,identity,source))expected.add(source);
            assertEquals(expected,SourceSelectionPolicy.blockedUrls(records,identity,sources));
        }
        assertEquals(new java.util.HashSet<>(Arrays.asList(" ",url,alias)),
            SourceSelectionPolicy.blockedUrls(records,"CCTV-1",sources));
        assertEquals(new java.util.HashSet<>(Arrays.asList(" ",url)),
            SourceSelectionPolicy.blockedUrls(records,"CCTV-2",sources));
    }

    @org.junit.Test public void explicitBlockIsScopedToStationAndEndpoint(){
        String url="https://example.org/live?token=A";
        String alias="https://EXAMPLE.org:443/live?token=A";
        java.util.Set<String> records=new java.util.HashSet<>();
        records.add(SourceSelectionPolicy.channelBlockKey("CCTV-1|cctv",url));
        org.junit.Assert.assertTrue(SourceSelectionPolicy.isUserBlocked(records,"CCTV-1|cctv",alias));
        org.junit.Assert.assertFalse(SourceSelectionPolicy.isUserBlocked(records,"CCTV-10|cctv",url));
        org.junit.Assert.assertFalse(SourceSelectionPolicy.isUserBlocked(records,"CCTV-1|cctv","https://example.org/live?token=B"));
        org.junit.Assert.assertEquals(java.util.Collections.singleton(alias),
            SourceSelectionPolicy.blockedUrls(records,"CCTV-1|cctv",java.util.Arrays.asList(alias,"https://example.org/other")));
        records.remove(SourceSelectionPolicy.channelBlockKey("CCTV-1|cctv",alias));
        org.junit.Assert.assertFalse(SourceSelectionPolicy.isUserBlocked(records,"CCTV-1|cctv",url));
        records.add(url);
        org.junit.Assert.assertTrue(SourceSelectionPolicy.isUserBlocked(records,"CCTV-10|cctv",url));
    }
    @Test public void automaticSelectionChecksOnlyEligibleEndpointsUntilHealthyRoute() {
        java.util.List<String> routes=Arrays.asList("https://active.example/live",
                "HTTPS://ACTIVE.example:443/live","http://blocked.example/live",
                "HTTP://FAILED.example:80/live","https://slow.example/Live?token=A",
                "https://slow.example/Live?token=B","https://later.example/live");
        java.util.Set<String> blocked=Collections.singleton("HTTP://BLOCKED.example:80/live");
        java.util.Set<String> failed=Collections.singleton("http://failed.example/live");
        java.util.List<String> checked=new java.util.ArrayList<>();
        assertEquals(5,SourceSelectionPolicy.findNextByEndpoint(routes,0,blocked,failed,key->{
            checked.add(key);
            return key.endsWith("token=A");
        }));
        assertEquals(Arrays.asList("https://slow.example/Live?token=A",
                "https://slow.example/Live?token=B"),checked);
        checked.clear();
        assertEquals(4,SourceSelectionPolicy.findNextByEndpoint(routes,0,blocked,failed,key->{
            checked.add(key);return true;
        }));
        assertEquals(Arrays.asList("https://slow.example/Live?token=A",
                "https://slow.example/Live?token=B","https://later.example/live"),checked);
    }

    @Test public void initialSelectionPrefersHealthyMemoryThenRankedHealthyRoute() {
        java.util.List<String> ranked=Arrays.asList("https://best.example/live",
                "https://next.example/live","https://remembered.example/live");
        assertEquals(2,SourceSelectionPolicy.findInitialSource(ranked,
                "HTTPS://REMEMBERED.example:443/live",Collections.emptySet(),key->false));
        assertEquals(0,SourceSelectionPolicy.findInitialSource(ranked,null,
                Collections.emptySet(),key->false));
        assertEquals(1,SourceSelectionPolicy.findInitialSource(ranked,ranked.get(2),
                Collections.emptySet(),key->!key.equals(ranked.get(1))));
        assertEquals(1,SourceSelectionPolicy.findInitialSource(ranked,ranked.get(2),
                new HashSet<>(Arrays.asList(ranked.get(0),ranked.get(2))),key->false));
        assertEquals(0,SourceSelectionPolicy.findInitialSource(ranked,null,
                Collections.emptySet(),key->true));
        assertEquals(-1,SourceSelectionPolicy.findInitialSource(ranked,null,
                new HashSet<>(ranked),key->false));
    }

    @Test public void initialSelectionNeverRevivesBlockedAliasesOrEmptyRoutes() {
        java.util.List<String> ranked=Arrays.asList("HTTP://BLOCKED.example:80/live",
                "http://blocked.example/live",null," ");
        assertEquals(-1,SourceSelectionPolicy.findInitialSource(ranked,
                "http://blocked.example/live",Collections.singleton("http://blocked.example/live"),
                key->{fail("blocked or empty routes must not query cooldown");return false;}));
        assertEquals(-1,SourceSelectionPolicy.findInitialSource(Arrays.asList(null," "),
                null,Collections.emptySet(),key->{fail("empty route must not query cooldown");return false;}));
    }

    @Test public void manualNavigationSkipsEmptyAndExactlyBlockedRoutesInBothDirections() {
        java.util.List<String> routes=Arrays.asList("active",null," ","HTTP://EXAMPLE.invalid:80/live",
                "http://example.invalid/live","last");
        java.util.Set<String> blocked=Collections.singleton("HTTP://EXAMPLE.invalid:80/live");
        assertEquals(4,SourceSelectionPolicy.findNextManualSource(routes,0,1,blocked));
        assertEquals(5,SourceSelectionPolicy.findNextManualSource(routes,0,-1,blocked));
        assertEquals(4,SourceSelectionPolicy.findNextManualSource(routes,5,-1,blocked));
        assertEquals(-1,SourceSelectionPolicy.findNextManualSource(
                Arrays.asList("active",null," ","blocked"),0,1,Collections.singleton("blocked")));
        assertEquals(-1,SourceSelectionPolicy.findNextManualSource(Collections.singletonList("active"),0,1,blocked));
    }

    @Test public void capturedAdBlockDoesNotFollowAutomaticSourceOrChannelChanges() {
        String station="CCTV-1|cctv";
        String ad="https://example.org/live?token=A";
        String next="https://example.org/live?token=B";
        String captured=SourceSelectionPolicy.channelBlockKey(station,ad);
        java.util.Set<String> records=Collections.singleton(captured);
        org.junit.Assert.assertFalse(captured.isEmpty());
        org.junit.Assert.assertEquals(captured,SourceSelectionPolicy.channelBlockKey(
                station,"https://EXAMPLE.org:443/live?token=A"));
        org.junit.Assert.assertNotEquals(captured,SourceSelectionPolicy.channelBlockKey(station,next));
        org.junit.Assert.assertNotEquals(captured,SourceSelectionPolicy.channelBlockKey("CCTV-2|cctv",ad));
        org.junit.Assert.assertTrue(SourceSelectionPolicy.isUserBlocked(records,station,ad));
        org.junit.Assert.assertFalse(SourceSelectionPolicy.isUserBlocked(records,station,next));
        org.junit.Assert.assertFalse(SourceSelectionPolicy.isUserBlocked(records,"CCTV-2|cctv",ad));
        assertEquals(1,SourceSelectionPolicy.findInitialSource(Arrays.asList(ad,next),ad,
                SourceSelectionPolicy.blockedUrls(records,station,Arrays.asList(ad,next)),key->false));
    }

    @Test public void blockedRememberedRouteSkipsEntireMemoryLookup() {
        String blocked="https://blocked.invalid/live";
        String healthy="https://healthy.invalid/live";
        java.util.List<String> reads=new java.util.ArrayList<>();
        java.util.List<String> ranked=new java.util.AbstractList<String>(){
            @Override public int size(){return 3;}
            @Override public String get(int index){
                String url=Arrays.asList(healthy,"https://other.invalid/live",blocked).get(index);
                reads.add(url);
                return url;
            }
        };
        assertEquals(0,SourceSelectionPolicy.findInitialSource(ranked,
                "https://BLOCKED.invalid:443/live",Collections.singleton(blocked),key->{
                    assertEquals(healthy,key);return false;
                }));
        assertEquals(Collections.singletonList(healthy),reads);
    }

}
