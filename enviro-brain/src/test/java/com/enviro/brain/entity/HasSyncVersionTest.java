package com.enviro.brain.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD for HasSyncVersion interface
 * 
 * RED: Write test first - interface doesn't exist yet
 * GREEN: Create interface with getSyncVersion() method
 * REFACTOR: Verify implementation classes work correctly
 */
class HasSyncVersionTest {
    
    @Test
    void should_define_getSyncVersion_method() {
        // Create anonymous implementation to test interface contract
        HasSyncVersion obj = () -> 100L;
        
        assertNotNull(obj);
        assertEquals(100L, obj.getSyncVersion());
    }
    
    @Test
    void should_allow_implementation_by_entities() {
        // Verify InspectionRecord implements HasSyncVersion
        InspectionRecord record = new InspectionRecord();
        record.setSyncVersion(50L);
        
        // Polymorphic assignment should work
        HasSyncVersion syncObj = record;
        assertEquals(50L, syncObj.getSyncVersion());
    }
}
