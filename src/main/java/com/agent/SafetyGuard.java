package com.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.agent.llm.ToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 校验工具调用配对，并阻止模型连续重复完全相同的行动。 */
final class SafetyGuard {
    static final int MAX_IDENTICAL_CALLS = 3;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> seenCallIds = new HashSet<>();
    private String previousFingerprint;
    private int repeatedCalls;

    /** 一次性验证整组调用，避免执行一半后才发现 ID 无法可靠配对。 */
    String validateCalls(List<ToolCall> calls) {
        Set<String> responseIds = new HashSet<>();
        for (ToolCall call : calls) {
            if (call == null || call.id() == null || call.id().isBlank()) {
                return "工具调用 ID 不能为空";
            }
            if (!responseIds.add(call.id()) || seenCallIds.contains(call.id())) {
                return "工具调用 ID 重复: " + call.id();
            }
            if (call.function() == null || call.name() == null || call.name().isBlank()) {
                return "工具名称不能为空";
            }
        }
        seenCallIds.addAll(responseIds);
        return null;
    }

    /** 返回非空文本表示当前调用已达到连续重复阈值，应停止执行。 */
    String checkRepeatedCall(ToolCall call) {
        String fingerprint = call.name() + '\n' + canonicalArguments(call.arguments());
        if (fingerprint.equals(previousFingerprint)) {
            repeatedCalls++;
        } else {
            previousFingerprint = fingerprint;
            repeatedCalls = 1;
        }
        if (repeatedCalls >= MAX_IDENTICAL_CALLS) {
            return "检测到连续 " + repeatedCalls + " 次相同工具调用: " + call.name();
        }
        return null;
    }

    /** 递归排序对象字段，使仅字段顺序不同的 JSON 产生相同调用指纹。 */
    private String canonicalArguments(String arguments) {
        try {
            JsonNode parsed = mapper.readTree(arguments == null ? "{}" : arguments);
            return mapper.writeValueAsString(sortNode(parsed));
        } catch (JsonProcessingException ignored) {
            return arguments == null ? "{}" : arguments.trim();
        }
    }

    private JsonNode sortNode(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(value -> result.add(sortNode(value)));
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted(Comparator.naturalOrder())
                    .forEach(name -> result.set(name, sortNode(node.get(name))));
            return result;
        }
        return node;
    }
}
