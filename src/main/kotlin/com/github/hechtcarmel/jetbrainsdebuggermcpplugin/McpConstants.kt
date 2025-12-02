package com.github.hechtcarmel.jetbrainsdebuggermcpplugin

object McpConstants {
    const val PLUGIN_NAME = "Debugger MCP Server"
    const val TOOL_WINDOW_ID = PLUGIN_NAME
    const val NOTIFICATION_GROUP_ID = PLUGIN_NAME
    const val SETTINGS_DISPLAY_NAME = PLUGIN_NAME

    // MCP Endpoint paths (HTTP+SSE transport)
    const val MCP_ENDPOINT_PATH = "/debugger-mcp"
    const val SSE_ENDPOINT_PATH = "$MCP_ENDPOINT_PATH/sse"

    // JSON-RPC version
    const val JSON_RPC_VERSION = "2.0"

    // MCP Protocol version
    const val MCP_PROTOCOL_VERSION = "2024-11-05"

    // Server identification
    const val SERVER_NAME = "jetbrains-debugger-mcp"
    const val SERVER_VERSION = "1.0.0"
    const val SERVER_DESCRIPTION = "JetBrains IDE debugger MCP server. Provides full debugger control including session management (start/stop debug sessions, run configurations), breakpoint management (set/remove/list breakpoints with conditions), execution control (step over/into/out, resume, pause, run to line), variable inspection (view/expand/modify variables), expression evaluation, and stack/thread navigation. Use when you need to debug code programmatically, set breakpoints, inspect variables, or step through code execution. Requires an open JetBrains IDE project with debugger support."

    // Agent rule text for the tip panel
    const val AGENT_RULE_TEXT = "IMPORTANT: When debugging, prefer using jetbrains-debugger MCP tools to interact with the IDE debugger."
}
