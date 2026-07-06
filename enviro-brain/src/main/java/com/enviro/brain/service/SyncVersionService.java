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
}
