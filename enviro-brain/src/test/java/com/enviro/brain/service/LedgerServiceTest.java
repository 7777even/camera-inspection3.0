package com.enviro.brain.service;

import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.mapper.LedgerRecordMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerService")
class LedgerServiceTest {

    private LedgerService ledgerService;
    @Mock private LedgerRecordMapper ledgerRecordMapper;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerRecordMapper);
        ReflectionTestUtils.setField(ledgerService, "templatePath", "templates/test.docx");
        ReflectionTestUtils.setField(ledgerService, "ledgerDir", "./ledger");
    }

    @Nested
    @DisplayName("generateAndSave()")
    class GenerateAndSave {

        @Test
        @DisplayName("should return null when targets empty")
        void shouldReturnNullWhenEmpty() {
            String result = ledgerService.generateAndSave(1L, Collections.emptyList(), 42L);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should insert ledger records for each target")
        void shouldInsertLedgerRecords() {
            CameraResult r1 = new CameraResult();
            r1.setId(101L); r1.setCameraCode("CAM001"); r1.setCameraName("危废1");
            r1.setStatus("offline"); r1.setErrorMessage("No signal");

            CameraResult r2 = new CameraResult();
            r2.setId(102L); r2.setCameraCode("CAM002"); r2.setCameraName("危废2");
            r2.setStatus("abnormal"); r2.setQualityScore(new BigDecimal("0.30"));

            doNothing().when(ledgerRecordMapper).insert(any());

            String docxPath = ledgerService.generateAndSave(1L, Arrays.asList(r1, r2), 42L);

            verify(ledgerRecordMapper, times(2)).insert(any());
            assertThat(docxPath).contains("台账");
        }
    }

    @Nested
    @DisplayName("shouldRegisterToLedger()")
    class ShouldRegister {

        @Test
        @DisplayName("should register offline camera")
        void shouldRegisterOffline() throws Exception {
            Method method = LedgerService.class.getDeclaredMethod("shouldRegisterToLedger", CameraResult.class);
            method.setAccessible(true);

            CameraResult r = new CameraResult();
            r.setStatus("offline");

            assertThat((Boolean) method.invoke(ledgerService, r)).isTrue();
        }

        @Test
        @DisplayName("should register abnormal camera")
        void shouldRegisterAbnormal() throws Exception {
            Method method = LedgerService.class.getDeclaredMethod("shouldRegisterToLedger", CameraResult.class);
            method.setAccessible(true);

            CameraResult r = new CameraResult();
            r.setStatus("abnormal");

            assertThat((Boolean) method.invoke(ledgerService, r)).isTrue();
        }

        @Test
        @DisplayName("should register low quality online camera")
        void shouldRegisterLowQuality() throws Exception {
            Method method = LedgerService.class.getDeclaredMethod("shouldRegisterToLedger", CameraResult.class);
            method.setAccessible(true);

            CameraResult r = new CameraResult();
            r.setStatus("online");
            r.setQualityScore(new BigDecimal("0.30"));

            assertThat((Boolean) method.invoke(ledgerService, r)).isTrue();
        }

        @Test
        @DisplayName("should not register good quality online camera")
        void shouldNotRegisterGoodQuality() throws Exception {
            Method method = LedgerService.class.getDeclaredMethod("shouldRegisterToLedger", CameraResult.class);
            method.setAccessible(true);

            CameraResult r = new CameraResult();
            r.setStatus("online");
            r.setQualityScore(new BigDecimal("0.85"));

            assertThat((Boolean) method.invoke(ledgerService, r)).isFalse();
        }
    }
}
