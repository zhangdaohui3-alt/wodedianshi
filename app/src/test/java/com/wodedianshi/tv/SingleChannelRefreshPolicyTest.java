package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class SingleChannelRefreshPolicyTest {
    @Test public void appendOnlyUsesFreeSlotsInVerifiedQualityOrder(){
        java.util.List<String> old=Arrays.asList("https://a.example/live","https://b.example/live");
        assertEquals(Arrays.asList("https://c.example/live","https://d.example/live"),
            SingleChannelRefreshPolicy.appendCandidates(old,Arrays.asList(
                "https://A.example:443/live","https://c.example/live","https://c.example/live",
                "file:///bad","https:///bad","https://d.example/live","https://e.example/live"),4));
        assertEquals(Arrays.asList("https://a.example/live","https://b.example/live"),old);
    }
    @Test public void fullOrEmptyRefreshDoesNotRemoveAnyExistingRoute(){
        assertTrue(SingleChannelRefreshPolicy.appendCandidates(
            Arrays.asList("https://a.example/live","https://b.example/live"),
            Collections.singletonList("https://new.example/live"),2).isEmpty());
        assertTrue(SingleChannelRefreshPolicy.appendCandidates(
            Collections.singletonList("https://a.example/live"),Collections.emptyList(),8).isEmpty());
    }
}
