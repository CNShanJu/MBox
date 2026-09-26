package com.github.tvbox.osc.cache;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "vodCollect",
        indices = {
                @Index(value = {"sourceKey", "vodId"}),
                @Index(value = {"updateTime"})
        })
public class VodCollect implements Serializable {
    @PrimaryKey(autoGenerate = true)
    private int id;
    @ColumnInfo(name = "vodId")
    public String vodId;
    @ColumnInfo(name = "updateTime")
    public long updateTime;
    @ColumnInfo(name = "sourceKey")
    public String sourceKey;
    @ColumnInfo(name = "name")
    public String name;
    @ColumnInfo(name = "pic")
    public String pic;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}