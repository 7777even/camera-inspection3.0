package com.queqiao.sync.mapper;

import com.queqiao.sync.entity.SyncWatermark;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SyncWatermarkMapper {

    /** 按表名查询水位，不存在返回 null */
    SyncWatermark selectByTableName(@Param("tableName") String tableName);

    /** 幂等写入水位 */
    int upsert(SyncWatermark watermark);
}
