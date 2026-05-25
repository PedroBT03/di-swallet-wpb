package di.swallet.wpb.wia.status

import di.swallet.wpb.issuance.domain.WiaStatusReference

interface WiaStatusManagementService {
    fun getOrAllocateStatus(holderId: String, issuerId: String?): WiaStatusReference
    fun revokeHolder(holderId: String)
}

