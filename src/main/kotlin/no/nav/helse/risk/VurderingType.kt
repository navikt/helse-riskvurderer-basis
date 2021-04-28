package no.nav.helse.risk

enum class VurderingType {
    PROD,
    STAGE,
    ANALYSE;

    fun erStageEllerAnalyse() : Boolean = this != PROD
    fun erKunAnalyse() : Boolean = this == ANALYSE

    companion object {
        fun vurderingstypeForventetFor(vedtaksperiodeId: String): VurderingType =
            if (!vedtaksperiodeId.erStageEllerAnalyseVedtaksperiodeId()) PROD
            else if (vedtaksperiodeId.startsWith("stage:")) STAGE
            else ANALYSE
    }
}

private fun String.erStageEllerAnalyseVedtaksperiodeId(): Boolean = this.contains(':')

fun RiskNeed.vurderingstypeForventet(): VurderingType =
    VurderingType.vurderingstypeForventetFor(vedtaksperiodeId = this.vedtaksperiodeId)

fun RiskNeed.erKunAnalyse() : Boolean = vurderingstypeForventet().erKunAnalyse()