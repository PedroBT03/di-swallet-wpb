package di.swallet.wpb.consent

import di.swallet.wpb.presentation.domain.CredentialCandidate
import di.swallet.wpb.presentation.domain.CredentialFormat
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialChoiceGrouperTest {

    private val grouper = CredentialChoiceGrouper()

    @Test
    fun `single candidate does not require explicit selection`() {
        val candidates = listOf(
            candidate("c1", queryId = "q1"),
        )
        assertFalse(grouper.requiresExplicitSelection(candidates))
    }

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
