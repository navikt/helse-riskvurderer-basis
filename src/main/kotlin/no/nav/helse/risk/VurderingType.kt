package no.nav.helse.risk

enum class VurderingType {
    PROD,
    ANALYSE;

    fun erAnalyse() : Boolean = this != PROD
    fun erKunAnalyse() : Boolean = this == ANALYSE

    companion object {
        fun vurderingstypeForventetFor(vedtaksperiodeId: String): VurderingType =
            if (!vedtaksperiodeId.erAnalyseVedtaksperiodeId()) PROD
            else ANALYSE
    }
}

private fun String.erAnalyseVedtaksperiodeId(): Boolean = this.contains(':')

fun RiskNeed.vurderingstypeForventet(): VurderingType =
    VurderingType.vurderingstypeForventetFor(vedtaksperiodeId = this.vedtaksperiodeId)

fun RiskNeed.erKunAnalyse() : Boolean = vurderingstypeForventet().erKunAnalyse()