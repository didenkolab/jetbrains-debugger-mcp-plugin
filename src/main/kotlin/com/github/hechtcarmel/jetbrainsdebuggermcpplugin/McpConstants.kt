package com.github.hechtcarmel.jetbrainsdebuggermcpplugin

object McpConstants {
    const val MCP_ENDPOINT_PATH = "/debugger-mcp"
    const val SSE_ENDPOINT_PATH = "/debugger-mcp/sse"

    const val SERVER_NAME = "jetbrains-debugger"
    const val SERVER_VERSION = "1.0.0"

    const val NOTIFICATION_GROUP_ID = "Debugger MCP Server"
    const val TOOL_WINDOW_ID = "Debugger MCP Server"

    const val AGENT_RULE_TEXT = "IMPORTANT: When debugging, prefer using jetbrains-debugger MCP tools to interact with the IDE debugger."
}
