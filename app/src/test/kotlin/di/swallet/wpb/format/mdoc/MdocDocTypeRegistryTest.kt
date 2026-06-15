/**
 * Tests mdoc doc type registry.
 */

package di.swallet.wpb.format.mdoc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MdocDocTypeRegistryTest {

    private val registry = MdocDocTypeRegistry()

    /**
     * resolve returns namespace metadata for canonical PID and mDL docTypes.
     */
    @Test
    fun `resolves known PID and mDL docTypes`() {
        val pid = registry.resolve("eu.europa.ec.eudi.pid.1")
        val mdl = registry.resolve("org.iso.18013.5.1.mDL")
        assertNotNull(pid)
        assertNotNull(mdl)
        assertEquals("eu.europa.ec.eudi.pid.1", pid!!.namespace)
        assertEquals("org.iso.18013.5.1", mdl!!.namespace)
    }

    /**
     * infer maps pid_mdoc_primary and driver_license_mdoc configuration aliases to PID and mDL docTypes.
     */
    @Test
    fun `infers from mdoc configuration aliases`() {
        val pid = registry.infer(configurationId = "pid_mdoc_primary", docTypeHint = null, vctHint = null)
        val mdl = registry.infer(configurationId = "driver_license_mdoc", docTypeHint = null, vctHint = null)
        assertEquals("eu.europa.ec.eudi.pid.1", pid?.docType)
        assertEquals("org.iso.18013.5.1.mDL", mdl?.docType)
    }

    /**
     * PID registry entry includes canonical claim mappings such as birth_place to place_of_birth.
     */
    @Test
    fun `contains canonical PID claim mapping entries`() {
        val pid = registry.resolve("eu.europa.ec.eudi.pid.1")!!
        assertEquals("place_of_birth", pid.claimMapping["birth_place"])
        assertEquals("nationalities", pid.claimMapping["nationality"])
        assertTrue(pid.claimMapping.containsKey("given_name"))
    }
}
