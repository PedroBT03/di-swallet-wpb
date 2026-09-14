/**
 * PKCS#11 HSM integration for wallet keys, JWT signing, pseudonym keys, and health probing.
 */

package di.swallet.wpb.service

import di.swallet.wpb.config.HsmProperties
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.domain.WalletUnit
import di.swallet.wpb.format.sdjwt.KeyBindingJwtSigner
import di.swallet.wpb.security.WscaAccessGuard
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.*
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.impl.ECDSA
import com.nimbusds.jose.jca.JCAContext
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.*

/**
 * WSCA gateway that performs key generation, signing, and deletion inside the remote PKCS#11 token.
 */
@Service
class HsmService(
    private val walletKeyRepository: WalletKeyRepository,
    private val statusListService: StatusListService,
    private val hsmProperties: HsmProperties,
    private val wscaAccessGuard: WscaAccessGuard,
) : KeyBindingJwtSigner {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var pkcs11Provider: Provider = Security.getProvider("SunPKCS11")
        ?: throw RuntimeException("SunPKCS11 provider not found")
    private val pin: String
        get() = hsmProperties.pin

    /**
     * Initializes the SunPKCS11 provider using the library path defined in configuration.
     * Also registers BouncyCastle for certificate utilities.
     */
    init {
        Security.addProvider(BouncyCastleProvider())

        val config = """
            --name = SoftHSM2
            library = ${hsmProperties.library}
            slotListIndex = 0
        """.trimIndent()

        try {
            val baseProvider = Security.getProvider("SunPKCS11")
                ?: throw RuntimeException("SunPKCS11 provider not found")
            
            pkcs11Provider = baseProvider.configure(config)
            Security.addProvider(pkcs11Provider)
            logger.info("WSCA: HSM initialized using library: ${hsmProperties.library}")
        } catch (e: Exception) {
            logger.error("WSCA: Critical error initializing HSM: ${e.message}")
            throw e
        }
    }

    /**
     * Returns the holder's active HSM key, creating one when missing or rotating when revoked.
     */
    fun ensureKeyForUser(userId: String, walletUnit: WalletUnit? = null): WalletKey {
        wscaAccessGuard.requireSciForHolder(userId)
        val existing = walletKeyRepository.findByUserId(userId)
        if (!existing.isPresent) {
            return generateKeyForUser(userId, walletUnit)
        }
        val key = existing.get()
        if (!statusListService.isRevoked(key.revocationIndex)) {
            return relinkWalletUnitIfNeeded(key, walletUnit)
        }
        logger.info("WSCA: Rotating revoked key for user $userId")
        return rotateRevokedKey(key, walletUnit)
    }

    /**
     * Generates an EC KeyPair inside the HSM and stores its metadata.
     * The private key is linked to a self-signed certificate for HSM storage compatibility.
     */
    fun generateKeyForUser(userId: String, walletUnit: WalletUnit? = null): WalletKey {
        wscaAccessGuard.requireSciForHolder(userId)
        try {
            val material = createHsmKeyMaterial(userId)
            val walletKey = WalletKey(
                userId = userId,
                keyAlias = material.alias,
                publicKeyBase64 = material.publicKeyBase64,
                revocationIndex = statusListService.getNextRevocationIndex(),
                walletUnit = walletUnit,
            )

            logger.info("WSCA: Key created for user $userId with alias ${material.alias}")
            return walletKeyRepository.save(walletKey)
        } catch (e: Exception) {
            logger.error("WSCA: Key generation failed for user $userId: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "HSM key generation failed: ${e.message}")
        }
    }

    private fun rotateRevokedKey(existing: WalletKey, walletUnit: WalletUnit? = null): WalletKey {
        try {
            val material = createHsmKeyMaterial(existing.userId)
            runCatching { deleteKeyEntry(existing.keyAlias) }
                .onFailure { logger.warn("WSCA: Could not delete revoked HSM alias ${existing.keyAlias}: ${it.message}") }

            return walletKeyRepository.save(
                WalletKey(
                    id = existing.id,
                    userId = existing.userId,
                    keyAlias = material.alias,
                    publicKeyBase64 = material.publicKeyBase64,
                    revocationIndex = statusListService.getNextRevocationIndex(),
                    createdAt = java.time.LocalDateTime.now(),
                    walletUnit = existing.walletUnit ?: walletUnit,
                ),
            )
        } catch (e: Exception) {
            logger.error("WSCA: Key rotation failed for user ${existing.userId}: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "HSM key rotation failed: ${e.message}")
        }
    }

    /** Backfills wallet_unit_id when a legacy key predates wallet init. */
    private fun relinkWalletUnitIfNeeded(key: WalletKey, walletUnit: WalletUnit?): WalletKey {
        if (key.walletUnit != null || walletUnit == null) {
            return key
        }
        return walletKeyRepository.save(
            WalletKey(
                id = key.id,
                userId = key.userId,
                keyAlias = key.keyAlias,
                publicKeyBase64 = key.publicKeyBase64,
                revocationIndex = key.revocationIndex,
                createdAt = key.createdAt,
                walletUnit = walletUnit,
            ),
        )
    }

    private data class HsmKeyMaterial(val alias: String, val publicKeyBase64: String)

    private fun createHsmKeyMaterial(userId: String): HsmKeyMaterial {
        val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
        keyStore.load(null, pin.toCharArray())

        val alias = "key-$userId-${System.currentTimeMillis()}"

        val keyPairGen = KeyPairGenerator.getInstance("EC", pkcs11Provider)
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGen.generateKeyPair()

        val chain = arrayOf(generateSelfSignedCertificate(keyPair))
        keyStore.setKeyEntry(alias, keyPair.private, null, chain)

        val pubKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.public.encoded)
        return HsmKeyMaterial(alias, pubKeyBase64)
    }

    /**
     * Validates if the key is authorized for use based on the bitstring status.
     * Throws 403 Forbidden if the key is marked as revoked.
     */
    fun validateKeyStatus(walletKey: WalletKey) {
        if (statusListService.isRevoked(walletKey.revocationIndex)) {
            logger.warn("Security: Blocked operation attempt using revoked key for user ${walletKey.userId}")
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "This holder HSM key has been revoked and cannot be used for signing, issuance, or presentation.",
            )
        }
    }

    /**
     * Performs a digital signature on the provided data using the user's private key in the HSM.
     */
    fun signData(userId: String, dataToSign: ByteArray): ByteArray {
        wscaAccessGuard.requireSciForHolder(userId)
        val walletKey = getUserKey(userId)
        
        // Ensure the key is active before signing
        validateKeyStatus(walletKey)

        try {
            val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
            keyStore.load(null, pin.toCharArray())

            val privateKey = keyStore.getKey(walletKey.keyAlias, null) as? PrivateKey
                ?: throw RuntimeException("Key alias ${walletKey.keyAlias} not found in HSM")

            val signature = Signature.getInstance("SHA256withECDSA", pkcs11Provider)
            signature.initSign(privateKey)
            signature.update(dataToSign)

            logger.info("WSCA: Document signed inside HSM boundary for user $userId")
            return signature.sign()
        } catch (e: Exception) {
            logger.error("WSCA: Signing failed: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Signing failed: ${e.message}")
        }
    }

    /**
     * Signs arbitrary bytes with the private key identified by alias after revocation checks.
     */
    fun signDataWithAlias(keyAlias: String, dataToSign: ByteArray): ByteArray {
        wscaAccessGuard.requireSciForWalletKeyAlias(keyAlias)
        val walletKey = getKeyByAlias(keyAlias)
        validateKeyStatus(walletKey)
        try {
            val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
            keyStore.load(null, pin.toCharArray())

            val privateKey = keyStore.getKey(walletKey.keyAlias, null) as? PrivateKey
                ?: throw RuntimeException("Key alias ${walletKey.keyAlias} not found in HSM")

            val signature = Signature.getInstance("SHA256withECDSA", pkcs11Provider)
            signature.initSign(privateKey)
            signature.update(dataToSign)
            return signature.sign()
        } catch (e: Exception) {
            logger.error("WSCA: Signing failed for alias $keyAlias: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Signing failed: ${e.message}")
        }
    }

    /**
     * Finds the wallet metadata for a specific user ID.
     */
    fun getUserKey(userId: String): WalletKey {
        return walletKeyRepository.findByUserId(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User wallet not found") }
    }

    /**
     * Loads wallet key metadata for a given HSM alias.
     */
    fun getKeyByAlias(keyAlias: String): WalletKey {
        return walletKeyRepository.findByKeyAlias(keyAlias)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet key alias not found") }
    }

    /**
     * Returns the HSM certificate chain for a wallet key encoded as standard Base64 strings.
     */
    fun certificateChainBase64(walletKey: WalletKey): List<String> {
        try {
            val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
            keyStore.load(null, pin.toCharArray())
            val chain = keyStore.getCertificateChain(walletKey.keyAlias)
                ?: keyStore.getCertificate(walletKey.keyAlias)?.let { arrayOf(it) }
                ?: emptyArray()
            return chain
                .map(Certificate::getEncoded)
                .map { Base64.getEncoder().encodeToString(it) }
        } catch (e: Exception) {
            logger.error("WSCA: Failed to resolve certificate chain for key ${walletKey.keyAlias}: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to resolve key certificate chain")
        }
    }

    /**
     * Generates a temporary X.509 certificate to allow the KeyStore to index the private key.
     */
    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val issuer = X500Name("CN=DI-Swallet-Internal")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000))

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, keyPair.public
        )

        // The HSM performs the signing operation for the certificate
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(pkcs11Provider)
            .build(keyPair.private)

        return JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
    }

    /**
     * Signs a JWT Claims Set using the holder's private key inside the PKCS#11 token.
     */
    fun signJwt(userId: String, claims: JWTClaimsSet): String {
        val walletKey = getUserKey(userId)

        // Ensure the key is active before signing
        validateKeyStatus(walletKey)
        
        // 1. Create the JWS Header, using ES256
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .keyID(walletKey.keyAlias)
            .type(JOSEObjectType.JWT)
            .build()

        // 2. Prepare the Signing Input (Base64URL(Header) + "." + Base64URL(Payload))
        val jwsObject = JWSObject(header, Payload(claims.toJSONObject()))
        val signingInput: ByteArray = jwsObject.signingInput

        // 3. Perform the signature inside the HSM
        val derSignature = signData(userId, signingInput)

        // 4. Convert DER signature (HSM standard) to Concatenated (JWT standard)
        val jwsSignatureBytes = ECDSA.transcodeSignatureToConcat(derSignature, 64)
        val base64UrlSignature = Base64URL.encode(jwsSignatureBytes)

        // 5. Assemble and return the complete serialized JWT
        val signedJwt = "${header.toBase64URL()}.${jwsObject.payload.toBase64URL()}.$base64UrlSignature"
        
        logger.info("WSCA: Successfully generated signed JWT for user $userId")
        return signedJwt
    }

    /**
     * Signs a specialized SD-JWT payload.
     * Unlike a standard JWT, the SD-JWT requires a specific content-type header
     * and a payload containing hashed disclosures.
     */
    fun signSdJwt(userId: String, sdClaims: Map<String, Any>): String {
        val walletKey = getUserKey(userId)

        // Ensure the key is active before signing
        validateKeyStatus(walletKey)
        
        // 1. Create the Header with the specific SD-JWT type
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .keyID(walletKey.keyAlias)
            .type(JOSEObjectType("kb+jwt")) // Key Binding / SD-JWT related type
            .build()

        val jwsObject = JWSObject(header, Payload(sdClaims))
        
        // 2. Sign using the HSM
        val derSignature = signData(userId, jwsObject.signingInput)
        
        // 3. Transcode signature to JWS format
        val jwsSignatureBytes = ECDSA.transcodeSignatureToConcat(derSignature, 64)
        val base64UrlSignature = Base64URL.encode(jwsSignatureBytes)

        // 4. Return the complete SD-JWT
        val signedSdJwt = "${header.toBase64URL()}.${jwsObject.payload.toBase64URL()}.$base64UrlSignature"

        return signedSdJwt
    }

    /**
     * Signs a Key Binding JWT for SD-JWT presentations (HAIP).
     *
     * The KB-JWT proves the holder controls the key that is bound to the
     * SD-JWT credential. The wallet signs it inside the HSM. The verifier
     * checks `aud`, `nonce` and `sd_hash` against the presentation it
     * received.
     */
    override fun signKeyBindingJwt(userId: String, payload: Map<String, Any>): String {
        val walletKey = getUserKey(userId)
        validateKeyStatus(walletKey)

        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .keyID(walletKey.keyAlias)
            .type(JOSEObjectType("kb+jwt"))
            .build()

        val jwsObject = JWSObject(header, Payload(payload))
        val derSignature = signData(userId, jwsObject.signingInput)
        val jwsSignatureBytes = ECDSA.transcodeSignatureToConcat(derSignature, 64)
        val base64UrlSignature = Base64URL.encode(jwsSignatureBytes)
        return "${header.toBase64URL()}.${jwsObject.payload.toBase64URL()}.$base64UrlSignature"
    }

    /**
     * Signs an SD-JWT key-binding JWT for the wallet key identified by alias.
     */
    override fun signKeyBindingJwtForKeyAlias(keyAlias: String, payload: Map<String, Any>): String {
        val walletKey = getKeyByAlias(keyAlias)
        validateKeyStatus(walletKey)

        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .keyID(walletKey.keyAlias)
            .type(JOSEObjectType("kb+jwt"))
            .build()

        val jwsObject = JWSObject(header, Payload(payload))
        val derSignature = signDataWithAlias(keyAlias, jwsObject.signingInput)
        val jwsSignatureBytes = ECDSA.transcodeSignatureToConcat(derSignature, 64)
        val base64UrlSignature = Base64URL.encode(jwsSignatureBytes)
        return "${header.toBase64URL()}.${jwsObject.payload.toBase64URL()}.$base64UrlSignature"
    }

    /**
     * Signs bytes with ES256 and returns the raw 64-byte R||S signature expected by COSE.
     */
    fun signCoseEs256WithAlias(keyAlias: String, bytesToSign: ByteArray): ByteArray {
        val derSignature = signDataWithAlias(keyAlias, bytesToSign)
        return ECDSA.transcodeSignatureToConcat(derSignature, 64)
    }

    /**
     * Generates an EC key pair in the HSM under [alias] without a [WalletKey] metadata row.
     * Used for per-RP pseudonym passkeys (Topic 11 / PA_14).
     */
    fun generateDedicatedEcKey(alias: String): java.security.interfaces.ECPublicKey {
        wscaAccessGuard.requireSciAuthorization()
        try {
            val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
            keyStore.load(null, pin.toCharArray())
            if (keyStore.containsAlias(alias)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "HSM alias already exists: $alias")
            }
            val keyPairGen = KeyPairGenerator.getInstance("EC", pkcs11Provider)
            keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = keyPairGen.generateKeyPair()
            val chain = arrayOf(generateSelfSignedCertificate(keyPair))
            keyStore.setKeyEntry(alias, keyPair.private, null, chain)
            logger.info("WSCA: Dedicated pseudonym key created with alias $alias")
            return keyPair.public as java.security.interfaces.ECPublicKey
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            logger.error("WSCA: Dedicated key generation failed for alias $alias: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "HSM Error: ${e.message}")
        }
    }

    /**
     * Reads the EC public key for a dedicated pseudonym HSM alias.
     */
    fun getDedicatedPublicKey(alias: String): java.security.interfaces.ECPublicKey {
        try {
            val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
            keyStore.load(null, pin.toCharArray())
            val certificate = keyStore.getCertificate(alias)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "HSM key alias not found: $alias")
            return certificate.publicKey as java.security.interfaces.ECPublicKey
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            logger.error("WSCA: Failed to read public key for alias $alias: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read HSM public key")
        }
    }

    /**
     * Signs assertion input with a dedicated pseudonym key and returns a COSE-style ES256 signature.
     */
    fun signEs256WithDedicatedAlias(alias: String, dataToSign: ByteArray): ByteArray {
        wscaAccessGuard.requireSciForDedicatedAlias(alias)
        try {
            val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
            keyStore.load(null, pin.toCharArray())
            val privateKey = keyStore.getKey(alias, null) as? PrivateKey
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "HSM key alias not found: $alias")
            val signature = Signature.getInstance("SHA256withECDSA", pkcs11Provider)
            signature.initSign(privateKey)
            signature.update(dataToSign)
            val derSignature = signature.sign()
            return ECDSA.transcodeSignatureToConcat(derSignature, 64)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            logger.error("WSCA: Dedicated signing failed for alias $alias: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Signing failed: ${e.message}")
        }
    }

    /**
     * Removes a dedicated pseudonym key entry from the HSM token.
     */
    fun deleteDedicatedKey(alias: String) {
        wscaAccessGuard.requireSciForDedicatedAlias(alias)
        deleteKeyEntry(alias)
    }

    /**
     * Removes a holder wallet key from the HSM token (GDPR erasure / wallet unit revocation).
     */
    fun deleteWalletKey(keyAlias: String) {
        wscaAccessGuard.requireSciForWalletKeyAlias(keyAlias)
        deleteKeyEntry(keyAlias)
    }

    /**
     * Deletes an HSM key entry by alias when it exists on the token.
     */
    private fun deleteKeyEntry(alias: String) {
        try {
            val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
            keyStore.load(null, pin.toCharArray())
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
                logger.info("WSCA: Deleted HSM key alias $alias")
            }
        } catch (e: Exception) {
            logger.error("WSCA: Failed to delete HSM key alias $alias: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to delete HSM key")
        }
    }

    /**
     * Lightweight PKCS#11 probe for actuator health. Opens a session and enumerates aliases
     * without performing signing operations.
     */
    fun probePkcs11Session(): HsmSessionProbe {
        return try {
            val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
            keyStore.load(null, pin.toCharArray())
            var count = 0
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                aliases.nextElement()
                count++
            }
            HsmSessionProbe(reachable = true, tokenLabel = "SoftHSM2", keyEntryCount = count, message = null)
        } catch (e: Exception) {
            HsmSessionProbe(reachable = false, tokenLabel = null, keyEntryCount = 0, message = e.message)
        }
    }
}

/** Result of a lightweight PKCS#11 session health check without signing operations. */
data class HsmSessionProbe(
    val reachable: Boolean,
    val tokenLabel: String?,
    val keyEntryCount: Int,
    val message: String?,
)