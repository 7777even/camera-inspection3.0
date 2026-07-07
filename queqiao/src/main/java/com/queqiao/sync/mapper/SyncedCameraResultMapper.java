package com.queqiao.sync.mapper;

import com.queqiao.sync.entity.SyncedCameraResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SyncedCameraResultMapper {

    /** 幂等写入：以环保小脑原始 id 为主键，存在则更新业务字段 */
    int upsert(SyncedCameraResult record);

    SyncedCameraResult findById(Long id);

    int count();

    List<SyncedCameraResult> findByRecordId(@Param("recordId") Long recordId);

    List<SyncedCameraResult> findByCameraCode(@Param("cameraCode") String cameraCode);

    List<SyncedCameraResult> findLatestPerCamera();

    List<SyncedCameraResult> findByRecordIds(@Param("ids") List<Long> ids);
}
