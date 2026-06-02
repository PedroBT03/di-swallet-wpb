package di.swallet.wpb.format.mdoc

import org.springframework.stereotype.Component

/**
 * Runtime registry for ISO mdoc docTypes.
 *
 * Normative basis:
 * - PID rulebook namespace/docType: eu.europa.ec.eudi.pid.1
 * - mDL rulebook on ISO 18013-5 mdoc format
 */
@Component
class MdocDocTypeRegistry {
    private val known = listOf(
        MdocDocTypeDefinition(
            docType = "eu.europa.ec.eudi.pid.1",
            namespace = "eu.europa.ec.eudi.pid.1",
            aliases = setOf("eu.europa.ec.eudi.pid.1", "pid_mdoc", "pid-mdoc"),
            claimMapping = pidClaimMapping,
        ),
        MdocDocTypeDefinition(
            docType = "org.iso.18013.5.1.mDL",
            namespace = "org.iso.18013.5.1",
            aliases = setOf("org.iso.18013.5.1.mdl", "mdoc_mdl", "mdoc-mdl", "driving_licence_mdoc", "driver_license_mdoc"),
            claimMapping = mdlClaimMapping,
        ),
    )

    fun resolve(docType: String?): MdocDocTypeDefinition? {
        val normalized = docType?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return known.firstOrNull { it.docType.equals(normalized, ignoreCase = true) }
    }

    fun infer(configurationId: String?, docTypeHint: String?, vctHint: String?): MdocDocTypeDefinition? {
        resolve(docTypeHint)?.let { return it }
        resolve(vctHint)?.let { return it }
        val id = configurationId?.trim()?.lowercase().orEmpty()
        if (id.isBlank()) return null
        return known.firstOrNull { def ->
            def.aliases.any { alias -> alias in id } || def.docType.lowercase() in id
        }
    }

    fun isSupported(docType: String?): Boolean = resolve(docType) != null

    fun all(): List<MdocDocTypeDefinition> = known

    private companion object {
        val pidClaimMapping = mapOf(
            // PID rulebook representative mapping subset for runtime matching.
            "birth_place" to "place_of_birth",
            "nationality" to "nationalities",
            "family_name" to "family_name",
            "given_name" to "given_name",
            "birth_date" to "birth_date",
            "issuance_date" to "issuance_date",
            "expiry_date" to "expiry_date",
            "issuing_authority" to "issuing_authority",
        )

        val mdlClaimMapping = mapOf(
            // ISO 18013-5 mDL common fields.
            "family_name" to "family_name",
            "given_name" to "given_name",
            "birth_date" to "birth_date",
            "issue_date" to "issue_date",
            "expiry_date" to "expiry_date",
            "issuing_country" to "issuing_country",
            "driving_privileges" to "driving_privileges",
        )
    }
}

data class MdocDocTypeDefinition(
    val docType: String,
    val namespace: String,
    val aliases: Set<String> = emptySet(),
    /**
     * Runtime mapping from external/request claim names to canonical mdoc names.
     */
    val claimMapping: Map<String, String> = emptyMap(),
)
