package com.wodedianshi.tv.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "categories")
public class CategoryEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String name;
    public int sortOrder;

    public CategoryEntity(@NonNull String id, @NonNull String name, int sortOrder) {
        this.id = id;
        this.name = name;
        this.sortOrder = sortOrder;
    }
}
