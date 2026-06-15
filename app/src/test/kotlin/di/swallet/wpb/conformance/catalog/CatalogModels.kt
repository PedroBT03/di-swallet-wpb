/**
 * Data models for the loaded conformance catalog and its scenarios.
 */

package di.swallet.wpb.conformance.catalog

data class ConformanceCatalog(
    val scenarios: List<CatalogScenario> = emptyList(),
)

data class CatalogScenario(
    val id: String,
    val hlr: List<String> = emptyList(),
    val protocol: String,
    val tier: Int = 1,
    val fixture: String? = null,
    val negative: Boolean = false,
    val description: String? = null,
    val testClass: String? = null,
)
