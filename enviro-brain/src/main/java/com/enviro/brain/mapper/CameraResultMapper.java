package com.enviro.brain.mapper;

import com.enviro.brain.entity.CameraResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CameraResultMapper {

    void insert(CameraResult result);

    CameraResult findById(@Param("id") Long id);

    List<CameraResult> findByRecordId(@Param("recordId") Long recordId);

    List<CameraResult> findBySyncVersionGreaterThan(@Param("syncVersion") Long syncVersion, @Param("limit") Integer limit);

    List<CameraResult> findAll();

    void batchInsert(@Param("list") List<CameraResult> list);
}
