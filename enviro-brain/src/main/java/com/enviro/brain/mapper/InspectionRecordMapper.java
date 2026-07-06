package com.enviro.brain.mapper;

import com.enviro.brain.entity.InspectionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InspectionRecordMapper {

    void insert(InspectionRecord record);

    InspectionRecord findById(@Param("id") Long id);

    List<InspectionRecord> findByBatchId(@Param("batchId") String batchId);

    List<InspectionRecord> findBySyncVersionGreaterThan(@Param("syncVersion") Long syncVersion, @Param("limit") Integer limit);

    List<InspectionRecord> findAll();

    void updateById(InspectionRecord record);
}
