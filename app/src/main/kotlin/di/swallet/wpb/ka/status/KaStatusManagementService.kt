package di.swallet.wpb.ka.status

import di.swallet.wpb.issuance.domain.KaStatusReference

interface KaStatusManagementService {
    fun getOrAllocateStatus(holderId: String, issuerId: String?, attestationFingerprint: String): KaStatusReference
    fun revokeHolder(holderId: String)
}
