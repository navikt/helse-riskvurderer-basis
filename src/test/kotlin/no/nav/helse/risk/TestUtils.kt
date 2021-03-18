package no.nav.helse.risk

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.ContextBase
import org.slf4j.Logger

internal class LogTapper(logger: Logger) {
    val logbackLogger = logger as ch.qos.logback.classic.Logger
    val logEvents = mutableListOf<ILoggingEvent>()
    fun messages() : List<String> = logEvents.map { it.formattedMessage }

    init {
        logbackLogger.addAppender(object : AppenderBase<ILoggingEvent>() {
            override fun append(eventObject: ILoggingEvent?) {
                if (eventObject != null) logEvents.add(eventObject)
            }
        }.apply {
            context = ContextBase()
            start()
        })
    }
}
