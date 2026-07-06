package com.enviro.brain.mapper;

import com.enviro.brain.entity.CameraConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CameraConfigMapper {

    void insert(CameraConfig config);

    CameraConfig findById(@Param("id") Long id);

    CameraConfig findByCameraCode(@Param("cameraCode") String cameraCode);

    List<CameraConfig> findAll();

    int upsert(CameraConfig config);

    int deleteByCameraCode(@Param("cameraCode") String cameraCode);

    int update(CameraConfig config);
}
