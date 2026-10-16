package com.agent.history;

/** 持久化后的工具事件只保留审计所需的最小信息。 */
public record ToolEvent(String name, String status, boolean detailsOmitted) {
}
