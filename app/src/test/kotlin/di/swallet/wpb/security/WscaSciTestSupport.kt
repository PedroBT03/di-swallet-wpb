package di.swallet.wpb.security

import di.swallet.wpb.config.WscaSciProperties

object WscaSciTestSupport {
    fun grantService(): WscaSciGrantService = WscaSciGrantService(WscaSciProperties())
}
