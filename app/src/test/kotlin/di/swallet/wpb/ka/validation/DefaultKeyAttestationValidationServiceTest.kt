package di.swallet.wpb.ka.validation

import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.crypto.Rfc7638JwkThumbprint
import di.swallet.wpb.issuance.domain.KaStatusReference
import di.swallet.wpb.issuance.domain.KeyAttestation
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.proof.ProofMaterial
import di.swallet.wpb.ka.trust.CertificateChainValidator
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.service.StatusListService
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

class DefaultKeyAttestationValidationServiceTest {
    private val properties = OpenId4VciProperties()
    private val statusListService = Mockito.mock(StatusListService::class.java).also {
        Mockito.`when`(it.isRevoked(Mockito.anyInt())).thenReturn(false)
    }
    private val certificateChainValidator = Mockito.mock(CertificateChainValidator::class.java)
    private val service = DefaultKeyAttestationValidationService(properties, statusListService, certificateChainValidator)

    private fun proof(): ProofMaterial {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return ProofMaterial(
            keyId = "proof-key-1",
            publicKey = kp.public as ECPublicKey,
            algorithm = "ES256",
        )
    }

    private fun attestation(proof: ProofMaterial, expired: Boolean = false): KeyAttestation {
        val now = Instant.now()
        return KeyAttestation(
            jwt = "header.payload.signature",
            keyId = proof.keyId,
            keyStorage = "iso_18045_high",
            certification = "test-cert",
            attestedJkt = Rfc7638JwkThumbprint.fromEcPublicKey(proof.publicKey),
            status = KaStatusReference(
                listId = "PRIMARY_LIST",
                index = 1,
                uri = "/api/v1/wallet/status-lists/PRIMARY_LIST",
            ),
            tokenExpiresAt = if (expired) now.minusSeconds(1) else now.plusSeconds(3600),
            statusExpiresAt = now.plusSeconds(31 * 24 * 3600),
            issuedAt = now,
        )
    }

    @Test
    fun `valid technical and binding checks pass`() {
        val proof = proof()
        val config = CredentialConfigurationDescriptor(
            id = "pid_jwt",
            format = IssuanceCredentialFormat.SD_JWT_VC,
            keyAttestationRequired = true,
        )
        assertDoesNotThrow {
            val att = attestation(proof)
            service.validateTechnical(att, config)
            service.validateBinding(att, proof)
        }
    }

    @Test
    fun `expired token fails technical validation`() {
        val proof = proof()
        val config = CredentialConfigurationDescriptor(
            id = "pid_jwt",
            format = IssuanceCredentialFormat.SD_JWT_VC,
            keyAttestationRequired = true,
        )
        assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTechnical(attestation(proof, expired = true), config)
        }
    }

    @Test
    fun `mismatched proof key fails binding`() {
        val proofA = proof()
        val proofB = proof()
        assertThrows(KeyAttestationValidationException::class.java) {
            service.validateBinding(attestation(proofA), proofB)
        }
    }

    @Test
    fun `revoked status fails technical validation`() {
        val revokedStatus = Mockito.mock(StatusListService::class.java).also {
            Mockito.`when`(it.isRevoked(Mockito.anyInt())).thenReturn(true)
        }
        val revokedService = DefaultKeyAttestationValidationService(properties, revokedStatus, certificateChainValidator)
        val proof = proof()
        val config = CredentialConfigurationDescriptor(
            id = "pid_jwt",
            format = IssuanceCredentialFormat.SD_JWT_VC,
            keyAttestationRequired = true,
        )
        assertThrows(KeyAttestationValidationException::class.java) {
            revokedService.validateTechnical(attestation(proof), config)
        }
    }
}
