package com.enviro.brain.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SyncVersionMapper {

    /**
     * 原子递增并返回下一个版本号
     * @return 递增后的版本号
     */
    Long nextVersion();
}
