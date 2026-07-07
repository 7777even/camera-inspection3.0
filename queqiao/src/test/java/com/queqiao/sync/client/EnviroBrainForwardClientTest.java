package com.queqiao.sync.client;

import com.queqiao.sync.exception.SyncClientException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EnviroBrainForwardClientTest {

    private static final String BASE_URL = "http://enviro-brain";
    private static final String API_KEY = "test-key";

    /** 环保小脑返回 HTTP 200 但 data 为 null 时，应抛出 SyncClientException 以便服务层降级 */
    @Test
    void triggerInspection_throwsWhen200WithNullData() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        EnviroBrainForwardClient client = new EnviroBrainForwardClient(rt, BASE_URL, API_KEY);

        server.expect(requestTo(BASE_URL + "/api/v1/inspections/trigger"))
                .andRespond(withSuccess("{\"code\":200,\"message\":\"success\",\"data\":null}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.triggerInspection("手动触发"))
                .isInstanceOf(SyncClientException.class);
    }

    @Test
    void downloadLedgerDocx_throwsWhen200WithNullData() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        EnviroBrainForwardClient client = new EnviroBrainForwardClient(rt, BASE_URL, API_KEY);

        server.expect(requestTo(BASE_URL + "/api/v1/ledger/10/download"))
                .andRespond(withSuccess("{\"code\":200,\"message\":\"success\",\"data\":null}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.downloadLedgerDocx(10L))
                .isInstanceOf(SyncClientException.class);
    }

    /** 环保小脑返回 200 但响应体不可反序列化（结构损坏）时，应抛 SyncClientException 以便服务层降级（不裸抛 HttpMessageConversionException） */
    @Test
    void triggerInspection_throwsWhenBodyUnparseable() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        EnviroBrainForwardClient client = new EnviroBrainForwardClient(rt, BASE_URL, API_KEY);

        server.expect(requestTo(BASE_URL + "/api/v1/inspections/trigger"))
                .andRespond(withSuccess("this-is-not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.triggerInspection("手动触发"))
                .isInstanceOf(SyncClientException.class);
    }

    @Test
    void downloadLedgerDocx_throwsWhenBodyUnparseable() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        EnviroBrainForwardClient client = new EnviroBrainForwardClient(rt, BASE_URL, API_KEY);

        server.expect(requestTo(BASE_URL + "/api/v1/ledger/10/download"))
                .andRespond(withSuccess("this-is-not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.downloadLedgerDocx(10L))
                .isInstanceOf(SyncClientException.class);
    }
}
