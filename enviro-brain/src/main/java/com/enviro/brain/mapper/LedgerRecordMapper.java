package com.enviro.brain.mapper;

import com.enviro.brain.entity.LedgerRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LedgerRecordMapper {

    void insert(LedgerRecord record);

    LedgerRecord findById(@Param("id") Long id);

    List<LedgerRecord> findByRecordId(@Param("recordId") Long recordId);

    List<LedgerRecord> findBySyncVersionGreaterThan(@Param("syncVersion") Long syncVersion, @Param("limit") Integer limit);

    List<LedgerRecord> findAll();
}
