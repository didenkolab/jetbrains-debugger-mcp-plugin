package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.output

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps what each debuggee printed, so an agent can read it.
 *
 * The IDE shows this in a console, which is exactly the problem: a console is for
 * a person. An agent stopped at a breakpoint could see the stack and the
 * variables and not the line the program had just printed -- often the shortest
 * route to the answer, and frequently the only place a library reports anything.
 *
 * Capture starts when the session starts rather than when the tool is first
 * called. Output produced before the first call is the interesting half: a panic
 * message arrives once, and asking for it afterwards is too late.
 */
@Service(Service.Level.PROJECT)
class DebuggeeOutputService(private val project: Project) {

    /** One ring per session id, bounded so a chatty program cannot exhaust the IDE. */
    private val buffers = ConcurrentHashMap<String, OutputRing>()

    /**
     * Follows every debug session for the life of the project.
     *
     * Called once from startup. Subscribing per tool call would miss the sessions
     * that started first, which are the ones already in trouble.
     */
    fun start() {
        project.messageBus.connect().subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) {
                    attach(debugProcess)
                }

                override fun processStopped(debugProcess: XDebugProcess) {
                    // The buffer outlives the process on purpose: the most useful
                    // moment to read a program's last words is after it has died.
                }
            }
        )
        // Sessions already running when this starts are followed too, so enabling
        // the plugin mid-session is not a silent blind spot.
        XDebuggerManager.getInstance(project).debugSessions.forEach { attach(it.debugProcess) }
    }

    private fun attach(debugProcess: XDebugProcess) {
        val sessionId = sessionKey(debugProcess)
        val ring = buffers.computeIfAbsent(sessionId) { OutputRing() }
        val handler = debugProcess.processHandler ?: return

        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // System output is the IDE's own narration ("Connected to the
                // target VM"), not the program's, and mixing them makes the
                // program's output harder to read rather than richer.
                val stream = when {
                    outputType === ProcessOutputType.STDERR -> Stream.STDERR
                    outputType === ProcessOutputType.STDOUT -> Stream.STDOUT
                    else -> return
                }
                ring.append(stream, event.text)
            }
        })
    }

    /** Everything newer than [since], with a cursor to continue from. */
    fun page(sessionId: String, since: Int, limit: Int): OutputPage =
        buffers[sessionId]?.page(since, limit) ?: OutputPage(emptyList(), since, 0)

    /** The last [n] lines, for a pause report. */
    fun tail(sessionId: String, n: Int): List<OutputLine> = buffers[sessionId]?.tail(n) ?: emptyList()

    companion object {
        const val MAX_LINES = 4000

        fun getInstance(project: Project): DebuggeeOutputService = project.service()

        /**
         * The key a buffer is filed under.
         *
         * The session's own hash, matching how the rest of this plugin identifies
         * a session, so a caller uses the id it already has.
         */
        fun sessionKey(debugProcess: XDebugProcess): String =
            debugProcess.session.hashCode().toString()
    }
}

enum class Stream(val wireName: String) {
    STDOUT("stdout"),
    STDERR("stderr")
}

data class OutputLine(val seq: Int, val stream: String, val text: String)

data class OutputPage(val lines: List<OutputLine>, val nextSince: Int, val dropped: Int)

/**
 * A bounded, ordered record of one debuggee's output.
 *
 * Bounded because the alternative is a program that can exhaust the IDE by
 * printing. Ordered across both streams because the interleaving is information:
 * it says which came first.
 */
internal class OutputRing {
    private val lines = ArrayDeque<OutputLine>()
    private var nextSeq = 0
    private var dropped = 0
    private val partial = HashMap<Stream, StringBuilder>()

    @Synchronized
    fun append(stream: Stream, text: String) {
        // Output arrives in whatever chunks the process wrote, which is not
        // lines. Holding the tail until a newline is what stops one printf from
        // becoming three entries.
        val buffer = partial.getOrPut(stream) { StringBuilder() }
        buffer.append(text)
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline < 0) break
            val line = buffer.substring(0, newline).trimEnd('\r')
            buffer.delete(0, newline + 1)
            add(stream, line)
        }
    }

    private fun add(stream: Stream, text: String) {
        nextSeq++
        lines.addLast(OutputLine(nextSeq, stream.wireName, text))
        while (lines.size > DebuggeeOutputService.MAX_LINES) {
            lines.removeFirst()
            dropped++
        }
    }

    @Synchronized
    fun page(since: Int, limit: Int): OutputPage {
        val selected = mutableListOf<OutputLine>()
        var cursor = since
        for (line in lines) {
            if (line.seq <= since) continue
            selected.add(line)
            cursor = line.seq
            if (limit > 0 && selected.size >= limit) break
        }
        return OutputPage(selected, cursor, dropped)
    }

    @Synchronized
    fun tail(n: Int): List<OutputLine> {
        if (n <= 0) return emptyList()
        return lines.toList().takeLast(n)
    }
}
