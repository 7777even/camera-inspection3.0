package com.enviro.brain.service;

import com.enviro.brain.mapper.SyncVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyncVersionService {

    private final SyncVersionMapper syncVersionMapper;

    /**
     * 获取下一个全局递增版本号。
     * 事务传播由调用方控制；Mapper 内部使用 FOR UPDATE 保证原子性。
     */
    @Transactional
    public Long nextVersion() {
        return syncVersionMapper.nextVersion();
    }

    /**
     * 获取当前水位（最大已分配版本号）。
     * 返回 next_val - 1，即当前已分配的最大版本号。
     * 初始状态 next_val=1 时返回 0，表示尚无任何数据被同步版本标记。
     */
    public Long getWatermark() {
        return syncVersionMapper.getCurrentVersion() - 1;
    }
}
