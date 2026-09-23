package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp.McpToolBridge
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket

/**
 * A port somebody else already holds is the commonest way starting this server fails: a second IDE,
 * a stale process, anything at all on 63343.
 *
 * Nothing covered it, and the gap was not theoretical. CIO reports the failed bind by cancelling its
 * start coroutine and nests the [java.net.BindException] two cancellations deep, so code inspecting
 * only the immediate cause rethrew instead of returning [KtorMcpServer.StartResult.PortInUse] -- and
 * `PortInUse` is the one result that raises the notification telling the user what to change.
 */
class KtorMcpServerBindTest {

    @Test
    fun `an occupied port is reported rather than thrown`() {
        ServerSocket(0).use { occupied ->
            val server = KtorMcpServer(
                port = occupied.localPort,
                mcpServer = McpServerFactory.create(ToolRegistry().apply { registerBuiltInTools() }, McpToolBridge()),
            )
            try {
                assertEquals(
                    "an occupied port must come back as PortInUse, not as an exception and not as a generic Error",
                    KtorMcpServer.StartResult.PortInUse(occupied.localPort),
                    server.start(),
                )
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `a free port still starts`() {
        // The counterpart, so the test above cannot pass by reporting PortInUse for everything.
        val port = ServerSocket(0).use { it.localPort }
        val server = KtorMcpServer(
            port = port,
            mcpServer = McpServerFactory.create(ToolRegistry().apply { registerBuiltInTools() }, McpToolBridge()),
        )
        try {
            assertEquals(KtorMcpServer.StartResult.Success, server.start())
        } finally {
            server.stop()
        }
    }
}
