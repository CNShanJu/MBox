package com.github.tvbox.osc.cache;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * @author pj567
 * @date :2021/1/7
 * @description: 历史记录表。索引覆盖高频查询条件:(sourceKey,vodId) 点查、updateTime 倒序分页。
 */
@Entity(tableName = "vodRecord",
        indices = {
                @Index(value = {"sourceKey", "vodId"}),
                @Index(value = {"updateTime"})
        })
public class VodRecord implements Serializable {
    @PrimaryKey(autoGenerate = true)
    private int id;
    @ColumnInfo(name = "vodId")
    public String vodId;
    @ColumnInfo(name = "updateTime")
    public long updateTime;
    @ColumnInfo(name = "sourceKey")
    public String sourceKey;
    public String dataJson;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}