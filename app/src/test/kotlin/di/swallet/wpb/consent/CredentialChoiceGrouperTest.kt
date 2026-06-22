/**
 * Tests credential choice grouper.
 */

package di.swallet.wpb.consent

import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialChoiceGrouperTest {

    private val grouper = CredentialChoiceGrouper()

    /**
     * Supplies one credential candidate for a query and expects requiresExplicitSelection
     * to return false because the holder has no competing choice.
     */
    @Test
    fun `single candidate does not require explicit selection`() {
        val candidates = listOf(
            candidate("c1", queryId = "q1"),
        )
        assertFalse(grouper.requiresExplicitSelection(candidates))
    }

    /**
     * Supplies two PID candidates for the same query and expects explicit selection to be
     * required, with the grouped result flagged as requiresUserSelection.
     */
    @Test
    fun `duplicate pid candidates require explicit selection`() {
        val candidates = listOf(
            candidate("c1", queryId = "q1", credentialId = 1L),
            candidate("c2", queryId = "q1", credentialId = 2L),
        )
        assertTrue(grouper.requiresExplicitSelection(candidates))
        val groups = grouper.group(candidates)
        assertTrue(groups.single().requiresUserSelection)
    }

    fun `mdl candidate label uses holder-facing type name`() {
        val candidates = listOf(
            CredentialCandidate(
                candidateId = "c1",
                credentialId = 42L,
                holderId = "holder-1",
                queryId = "q1",
                credentialType = "org.iso.18013.5.1.mDL",
                format = CredentialFormat.MDOC,
            ),
        )
        val group = grouper.group(candidates).single()
        assertEquals("Driving licence", group.credentialType)
        assertEquals("Driving licence", group.candidates.single().label)
    }

    /**
     * Builds a PID SD-JWT CredentialCandidate with the given ids for grouper selection
     * and grouping tests.
     */
    private fun candidate(
        id: String,
        queryId: String,
        credentialId: Long = 1L,
    ) = CredentialCandidate(
        candidateId = id,
        credentialId = credentialId,
        holderId = "holder-1",
        queryId = queryId,
        credentialType = "PID",
        format = CredentialFormat.SD_JWT,
    )
}
