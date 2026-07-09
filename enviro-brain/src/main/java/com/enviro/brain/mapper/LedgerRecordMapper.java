package com.enviro.brain.mapper;

import com.enviro.brain.entity.LedgerRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface LedgerRecordMapper {

    void insert(LedgerRecord record);

    LedgerRecord findById(@Param("id") Long id);

    List<LedgerRecord> findByRecordId(@Param("recordId") Long recordId);

    /**
     * 删除指定巡检日期的全部台账记录（用于"每天只保留最新一份台账"的覆盖更新）。
     */
    int deleteByInspectionDate(@Param("inspectionDate") LocalDate inspectionDate);

    List<LedgerRecord> findBySyncVersionGreaterThan(@Param("syncVersion") Long syncVersion, @Param("limit") Integer limit);

    List<LedgerRecord> findAll();
}
