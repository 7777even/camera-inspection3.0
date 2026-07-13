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

    List<CameraConfig> findActive(@Param("offset") int offset, @Param("size") int size);

    int countActive();

    List<CameraConfig> findActiveByScenario(@Param("scenario") String scenario, @Param("offset") int offset, @Param("size") int size);

    int countByScenario(@Param("scenario") String scenario);

    int upsert(CameraConfig config);

    int deleteByCameraCode(@Param("cameraCode") String cameraCode);

    int update(CameraConfig config);
}
