package com.queqiao.sync.mapper;

import com.queqiao.sync.entity.SyncedLedgerRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SyncedLedgerRecordMapper {

    /** 幂等写入：以环保小脑原始 id 为主键，存在则更新业务字段 */
    int upsert(SyncedLedgerRecord record);

    SyncedLedgerRecord findById(Long id);

    int count();

    SyncedLedgerRecord findByRecordId(@Param("recordId") Long recordId);
}
