package com.enviro.brain.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SyncVersionMapper {

    /**
     * 原子递增版本号（返回递增后的值）
     * 对于 H2/MySQL，先递增再查询
     */
    default Long nextVersion() {
        incrementVersion();
        return getNextVersion();
    }

    /** 原子递增 next_val */
    void incrementVersion();

    /** 查询当前 next_val */
    @Select("SELECT next_val FROM sync_version_seq WHERE id = 1")
    Long getNextVersion();
}
