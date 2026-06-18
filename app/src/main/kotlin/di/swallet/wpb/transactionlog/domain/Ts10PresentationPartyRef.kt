/**
 * Shared eligibility helpers for TS10 presentation payloads.
 */

package di.swallet.wpb.transactionlog.domain

/** Checks whether a presentation references the interacting party well enough for privacy flows. */
object Ts10PresentationPartyRef {
    /** True when an EUID, registrar URL, or display name is stored. */
    fun hasReference(presentation: Ts10Presentation): Boolean =
        !presentation.interactingPartyIdentifier?.identifier.isNullOrBlank() ||
            !presentation.registrarURL.isNullOrBlank() ||
            !presentation.interactingPartyName.isNullOrBlank()
}
