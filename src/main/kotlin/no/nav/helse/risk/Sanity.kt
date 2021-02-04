package no.nav.helse.risk

import ch.qos.logback.core.rolling.RollingFileAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val log: Logger = LoggerFactory.getLogger(Sanity::class.java)

object Sanity {
    private var secureLoggerVerified = false
    private var skipSanityChecksForProduction = false
    private fun createSecureLogger(): Logger = LoggerFactory.getLogger(SECURE_LOGGER_NAME)
    fun getSecureLogger() : Logger {
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
    internal fun runSanityChecks() {
        checkSecureLog()
    }
}


internal val SECURE_LOGGER_NAME = "sikkerLogg"

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
