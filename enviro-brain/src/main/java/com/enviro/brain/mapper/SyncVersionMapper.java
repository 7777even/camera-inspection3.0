package com.enviro.brain.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.annotation.Transactional;

@Mapper
public interface SyncVersionMapper {

    /**
     * 原子递增版本号（返回递增后的值）
     * 使用 SELECT FOR UPDATE 锁定行，确保并发安全
     */
    @Transactional
    default Long nextVersion() {
        // SELECT ... FOR UPDATE 在当前事务中锁定该行
        Long current = getNextVersionForUpdate();
        incrementVersion();
        return current + 1;
    }

    /** 原子递增 next_val（UPDATE next_val = next_val + 1） */
    @Update("UPDATE sync_version_seq SET next_val = next_val + 1 WHERE id = 1")
    void incrementVersion();

    /** 查询当前 next_val 并锁定行（用于并发控制） */
    @Select("SELECT next_val FROM sync_version_seq WHERE id = 1 FOR UPDATE")
    Long getNextVersionForUpdate();

    /** 查询当前 next_val（不加锁，用于 watermark 查询） */
    @Select("SELECT next_val FROM sync_version_seq WHERE id = 1")
    Long getCurrentVersion();
}
