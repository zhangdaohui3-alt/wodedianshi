package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class SourceMaintenanceRetentionPolicyTest {
    private static final Comparator<String> BEST_FIRST =
        (a,b) -> Integer.compare(rank(b),rank(a));
    private static int rank(String s){
        return s.contains("new")?100:s.contains("weak")?1:50;
    }

    @Test public void repeatedFailuresNeedThreeIndependentProbesAndResetOnSuccess(){
        assertEquals(1,SourceMaintenanceRetentionPolicy.nextFailureStreak(0,false));
        assertEquals(2,SourceMaintenanceRetentionPolicy.nextFailureStreak(1,false));
        assertEquals(3,SourceMaintenanceRetentionPolicy.nextFailureStreak(2,false));
        assertEquals(3,SourceMaintenanceRetentionPolicy.nextFailureStreak(3,false));
        assertEquals(0,SourceMaintenanceRetentionPolicy.nextFailureStreak(2,true));
        assertEquals(1,SourceMaintenanceRetentionPolicy.nextFailureStreak(0,false));
    }

    @Test public void anUnverifiedReplacementUrlIsNeverAccepted(){
        List<String> old=Arrays.asList("https://primary.test/live","https://weak.test/live");
        Set<String> eligible=Collections.singleton(SourceSelectionPolicy.endpointKey(old.get(1)));
        assertEquals(old,SourceMaintenanceRetentionPolicy.retainDuringRoutineUpdate(old,
            "new-invalid-source",2,BEST_FIRST,old.get(0),old.get(0),eligible));
    }

    @Test public void routineUpdateKeepsOldFallbackAfterOneBadProbe(){
        List<String> old=Arrays.asList("https://primary.test/live","https://weak.test/live");
        assertEquals(old,SourceMaintenanceRetentionPolicy.retainDuringRoutineUpdate(old,
            "https://new.test/live",2,BEST_FIRST,old.get(0),old.get(0),Collections.emptySet()));
    }

    @Test public void successfulCandidateCanRetireBackupAfterThreeFailures(){
        List<String> old=Arrays.asList("https://primary.test/live","https://weak.test/live");
        Set<String> eligible=Collections.singleton(SourceSelectionPolicy.endpointKey(old.get(1)));
        assertEquals(Arrays.asList(old.get(0),"https://new.test/live"),
            SourceMaintenanceRetentionPolicy.retainDuringRoutineUpdate(old,
            "https://new.test/live",2,BEST_FIRST,old.get(0),old.get(0),eligible));
    }

    @Test public void evenRepeatedFailuresNeverEvictCurrentOrPrimarySource(){
        List<String> old=Arrays.asList("https://primary.test/live","https://weak.test/live");
        Set<String> eligible=Collections.singleton(SourceSelectionPolicy.endpointKey(old.get(1)));
        assertEquals(old,SourceMaintenanceRetentionPolicy.retainDuringRoutineUpdate(old,
            "https://new.test/live",2,BEST_FIRST,old.get(0),old.get(1),eligible));
    }

    @Test public void nonFullChannelMayGainVerifiedBackupWithoutLosingOld(){
        List<String> old=Collections.singletonList("https://primary.test/live");
        assertEquals(Arrays.asList(old.get(0),"https://new.test/live"),
            SourceMaintenanceRetentionPolicy.retainDuringRoutineUpdate(old,
            "https://new.test/live",2,BEST_FIRST,old.get(0),old.get(0),Collections.emptySet()));
    }
}
