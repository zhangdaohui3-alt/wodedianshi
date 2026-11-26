package com.wodedianshi.tv.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/** Private manual-round quality, independent of processed-opportunity state.
 * Null score means unknown; null streak means no recorded failures. */
@Entity(tableName="manual_probe_quality",primaryKeys={"owner","sourceKey"})
public final class ManualProbeQualityEntity {
    @NonNull public String owner;
    @NonNull public String sourceKey;
    public Integer score;
    public Integer failureStreak;
    public ManualProbeQualityEntity(@NonNull String owner,@NonNull String sourceKey,
            Integer score,Integer failureStreak){
        this.owner=owner;this.sourceKey=sourceKey;this.score=score;this.failureStreak=failureStreak;
    }
}
