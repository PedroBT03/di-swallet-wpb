/**
 * Shared test helpers for wsca sci.
 */

package di.swallet.wpb.security

import di.swallet.wpb.config.WscaSciProperties

object WscaSciTestSupport {
    /**
     * Returns a WscaSciGrantService backed by default test properties for consent-grant
     * scenarios that do not need custom SCI boundary configuration.
     */
    fun grantService(): WscaSciGrantService = WscaSciGrantService(WscaSciProperties())
}
