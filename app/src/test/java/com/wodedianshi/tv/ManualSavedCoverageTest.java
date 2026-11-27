package com.wodedianshi.tv;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ManualSavedCoverageTest {
    @Test public void changedPoolKeepsExistingCoverageAndOnlyChecksNewEndpoints(){
        ManualSavedCoverage first=new ManualSavedCoverage(Collections.emptyMap());
        String a=first.identity("https://example.invalid/a"),b=first.identity("https://example.invalid/b");
        first.mark(a);first.mark(b);
        Map<String,String> saved=first.changes();
        ManualSavedCoverage next=new ManualSavedCoverage(saved);
        String c=next.identity("https://example.invalid/c");
        next.retainOnly(new HashSet<>(Arrays.asList(a,c)));
        assertTrue(next.contains(a));assertFalse(next.contains(b));assertFalse(next.contains(c));
        next.mark(c);saved.putAll(next.changes());
        ManualSavedCoverage restored=new ManualSavedCoverage(saved);
        assertTrue(restored.contains(a));assertTrue(restored.contains(c));assertFalse(restored.contains(b));
        assertFalse(new ManualSavedCoverage(Collections.emptyMap()).contains(a));
    }
    @Test public void repeatedMarkDoesNotRewriteUnchangedShard(){
        ManualSavedCoverage first=new ManualSavedCoverage(Collections.emptyMap());
        String id=first.identity("https://example.invalid/signed?token=A");
        first.mark(id);
        ManualSavedCoverage next=new ManualSavedCoverage(first.changes());
        next.mark(id);
        assertTrue(next.changes().isEmpty());
        assertNotEquals(id,next.identity("https://example.invalid/signed?token=a"));
    }
}
