package com.gt.apkforge.engine.log

import com.gt.apkforge.engine.ForgeLog
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/** Routes jadx's SLF4J output (INFO and up) into the active [ForgeLog]. */
object ForgeSlf4j {
    @Volatile var sink: ForgeLog? = null
    @Volatile var minLevel: Level = Level.INFO

    /** Counts ERROR lines while a sink is active (the CLI greps ERROR in decompile.log). */
    @Volatile var errors = 0
}

class ForgeSlf4jProvider : SLF4JServiceProvider {
    private val factory = ILoggerFactory { name -> loggers.getOrPut(name) { ForgeLogger(name) } }
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()
    override fun getLoggerFactory(): ILoggerFactory = factory
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() {}

    private companion object {
        val loggers = ConcurrentHashMap<String, Logger>()
    }
}

private class ForgeLogger(private val loggerName: String) : LegacyAbstractLogger() {
    init { name = loggerName }

    private fun on(level: Level) = ForgeSlf4j.sink != null && level.toInt() >= ForgeSlf4j.minLevel.toInt()
    override fun isTraceEnabled() = on(Level.TRACE)
    override fun isDebugEnabled() = on(Level.DEBUG)
    override fun isInfoEnabled() = on(Level.INFO)
    override fun isWarnEnabled() = on(Level.WARN)
    override fun isErrorEnabled() = on(Level.ERROR)
    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level, marker: Marker?, messagePattern: String?, arguments: Array<out Any?>?, throwable: Throwable?,
    ) {
        val sink = ForgeSlf4j.sink ?: return
        val msg = MessageFormatter.basicArrayFormat(messagePattern, arguments)
        if (level == Level.ERROR) ForgeSlf4j.errors++
        val line = StringBuilder().append(level.name.padEnd(5)).append(" - ").append(msg)
        if (throwable != null) line.append('\n').append(ForgeLog.stackTrace(throwable).lineSequence().take(12).joinToString("\n"))
        sink.detail(line.toString())
    }
}
