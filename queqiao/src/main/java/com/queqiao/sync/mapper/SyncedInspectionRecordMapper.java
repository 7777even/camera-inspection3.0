package com.queqiao.sync.mapper;

import com.queqiao.sync.entity.SyncedInspectionRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SyncedInspectionRecordMapper {

    /** 幂等写入：以环保小脑原始 id 为主键，存在则更新业务字段 */
    int upsert(SyncedInspectionRecord record);

    SyncedInspectionRecord findById(Long id);

    int count();
}
