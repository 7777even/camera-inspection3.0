package com.queqiao.sync.dto.view;

import lombok.Data;

import java.util.Map;

/**
 * 操作类 MCP 工具的返回视图。
 * 注意：Spring AI 1.0.0 的 {@code MethodToolCallbackProvider} 会忽略返回类型为
 * {@code Object} / 函数式类型的 {@code @Tool} 方法（直接丢弃，不注册）。因此操作类工具
 * 必须返回「具体类型」。ForwardService 内部以 {@code Map<String,Object>} 承载友好降级结果，
 * 此处将其转换为具体 DTO 以便被 MCP 工具契约正确暴露。
 */
@Data
public class OperationResultView {

    /** 是否成功（环保小脑可达且受理） */
    private Boolean ok;

    /** 友好提示信息（成功或降级原因） */
    private String message;

    /** 原始返回数据（taskId / fileName / docxPath 等） */
    private Map<String, Object> data;

    public static OperationResultView from(Map<String, Object> m) {
        OperationResultView v = new OperationResultView();
        if (m == null) {
            v.setOk(false);
            v.setMessage("环保小脑暂不可用，请稍后重试");
            return v;
        }
        Object ok = m.get("ok");
        v.setOk(ok instanceof Boolean ? (Boolean) ok : null);
        v.setMessage((String) m.get("message"));
        v.setData(m);
        return v;
    }
}
