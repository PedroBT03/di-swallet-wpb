/**
 * Loads the YAML conformance catalog from test resources.
 */

package di.swallet.wpb.conformance.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

object ConformanceCatalogLoader {
    private val mapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    /** Parses conformance/catalog.yaml from the test classpath into a ConformanceCatalog model. */
    fun load(): ConformanceCatalog {
        val stream = javaClass.classLoader.getResourceAsStream("conformance/catalog.yaml")
            ?: error("conformance/catalog.yaml not found on classpath")
        return mapper.readValue(stream)
    }

    /** Looks up a single catalog scenario by id, returning null when the id is not defined. */
    fun byId(id: String): CatalogScenario? = load().scenarios.firstOrNull { it.id == id }

    /** Returns catalog scenarios whose tier is 1 or lower for smoke-level conformance runs. */
    fun tier1Scenarios(): List<CatalogScenario> = load().scenarios.filter { it.tier <= 1 }
}
