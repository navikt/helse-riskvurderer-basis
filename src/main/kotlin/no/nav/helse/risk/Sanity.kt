package no.nav.helse.risk

import ch.qos.logback.core.rolling.RollingFileAppender
import net.logstash.logback.appender.LogstashTcpSocketAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val log: Logger = LoggerFactory.getLogger(Sanity::class.java)

object Sanity {
    private var secureLoggerVerified = false
    private var skipSanityChecksForProduction = false
    private fun createSecureLogger(): Logger = LoggerFactory.getLogger(SECURE_LOGGER_NAME)

    fun getSecureLogger() : Logger {
        try {
            log.info("Trying to use TeamLogger as SecureLogger...")
            return getTeamLogger()
        } catch (ex:SanityCheckException) {
            log.error("SanityCheckException getting Team-logger... trying legacy SecureLogger instead...", ex)
            return getSecureLoggerLegacy()
        }
    }

    fun getSecureLoggerLegacy() : Logger {
        val logger = createSecureLogger()
        if (!secureLoggerVerified && !skipSanityChecksForProduction) {
            secureLoggerVerified = checkSecureLog(logger)
        }
        if (!secureLoggerVerified) log.warn("Using unverified secureLogger")
        return logger
    }
    fun setSkipSanityChecksForProduction() {
        log.warn("Setting skipSanityChecksForProduction=true")
        skipSanityChecksForProduction = true
    }
    private fun checkSecureLog() {
        getSecureLogger().debug("checkSecureLog")
    }

    private var teamLoggerVerified = false
    private fun createTeamLogger(): Logger = LoggerFactory.getLogger(TEAM_LOGGER_NAME)
    private fun getTeamLogger() : Logger {
        val logger = createTeamLogger()
        if (!teamLoggerVerified && !skipSanityChecksForProduction) {
            teamLoggerVerified = checkTeamLog(logger)
        }
        if (!teamLoggerVerified) log.warn("Using unverified teamLogger")
        return logger
    }
    private fun checkTeamLog() {
        getTeamLogger().debug("checkTeamLog")
    }

    internal fun runSanityChecks() {
        checkSecureLog()
        checkTeamLog()
    }
}


internal val SECURE_LOGGER_NAME = "sikkerLogg"
internal val TEAM_LOGGER_NAME = "teamlogs"

class SanityCheckException(msg: String) : RuntimeException("$msg. Call Sanity.setSkipSanityChecksForProduction() to skip this check.")

private fun checkSecureLog(supposedSecureLogger: Logger) : Boolean {
    log.info("Verifying $SECURE_LOGGER_NAME configuration...")
    val secureLog = supposedSecureLogger as ch.qos.logback.classic.Logger
    val baseInstruction = "Logger $SECURE_LOGGER_NAME should be configured with:"
    if (secureLog.isAdditive) throw SanityCheckException("$baseInstruction additive=false")
    secureLog.getAppender("secureAppender").also {
        if (it == null) {
            throw SanityCheckException("$baseInstruction RollingFileAppender named secureAppender")
        }
        val fileAppender = it as RollingFileAppender
        val expectedFilename = "/secure-logs/secure.log"
        if (fileAppender.file != expectedFilename) {
            throw SanityCheckException("$baseInstruction secureAppender logging to file $expectedFilename")
        }
    }
    log.info("LGTM")
    return true
}

private fun checkTeamLog(supposedTeamLogger: Logger) : Boolean {
    log.info("Verifying $TEAM_LOGGER_NAME configuration...")
    val teamLog = supposedTeamLogger as ch.qos.logback.classic.Logger
    val baseInstruction = "Logger $TEAM_LOGGER_NAME should be configured with:"
    if (teamLog.isAdditive) throw SanityCheckException("$baseInstruction additive=false")
    teamLog.getAppender("team-logs-appender").also {
        if (it == null) {
            throw SanityCheckException("$baseInstruction LogstashTcpSocketAppender named team-logs-appender")
        }
        val socketAppender = it as LogstashTcpSocketAppender
        val expectedHost = "team-logs.nais-system"
        val expectedPort = 5170

        val dest = socketAppender.destinations.first()

        if ((dest.hostName to dest.port) != (expectedHost to expectedPort)) {
            throw SanityCheckException("$baseInstruction team-logs-appender logging to destination $expectedHost:$expectedPort")
        }
        log.info("TeamLogs configured with destination $dest")
    }
    log.info("LGTM")
    return true
}

