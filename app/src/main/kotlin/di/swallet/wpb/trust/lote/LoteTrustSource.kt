/**
 * Labels for LoTE trust list load sources.
 */

package di.swallet.wpb.trust.lote

/** Indicates whether a trust snapshot came from local, remote, or hybrid LoTE data. */
enum class LoteTrustSource(val label: String) {
    LOCAL("local"),
    REMOTE("remote"),
    HYBRID("hybrid"),
}
