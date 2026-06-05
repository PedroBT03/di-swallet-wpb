package di.swallet.wpb.ka.trust

import di.swallet.wpb.config.OpenId4VciProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KaTrustPolicyTest {

    @Test
    fun `demo mode keeps configured relaxed trust`() {
        val props = OpenId4VciProperties().apply {
            demoMode = true
            ka.trustMode = "relaxed"
        }
        assertEquals(KaTrustMode.RELAXED, KaTrustPolicy.effectiveMode(props))
    }

    @Test
    fun `production enforcement upgrades relaxed trust without mitigations`() {
        val props = OpenId4VciProperties().apply {
            demoMode = false
            ka.trustMode = "relaxed"
            ka.enforceProductionTrustPolicy = true
        }
        assertEquals(KaTrustMode.STRICT, KaTrustPolicy.effectiveMode(props))
    }

    @Test
    fun `production enforcement keeps relaxed when fingerprint allow-list is configured`() {
        val props = OpenId4VciProperties().apply {
            demoMode = false
            ka.trustMode = "relaxed"
            ka.enforceProductionTrustPolicy = true
            ka.allowedX5cFingerprints = "ABCDEF"
        }
        assertEquals(KaTrustMode.RELAXED, KaTrustPolicy.effectiveMode(props))
    }

    @Test
    fun `explicit strict remains strict`() {
        val props = OpenId4VciProperties().apply {
            demoMode = true
            ka.trustMode = "strict"
        }
        assertEquals(KaTrustMode.STRICT, KaTrustPolicy.effectiveMode(props))
    }
}
