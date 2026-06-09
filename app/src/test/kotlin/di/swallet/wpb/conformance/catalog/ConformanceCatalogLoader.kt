package di.swallet.wpb.conformance.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

object ConformanceCatalogLoader {
    private val mapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    fun load(): ConformanceCatalog {
        val stream = javaClass.classLoader.getResourceAsStream("conformance/catalog.yaml")
            ?: error("conformance/catalog.yaml not found on classpath")
        return mapper.readValue(stream)
    }

    fun byId(id: String): CatalogScenario? = load().scenarios.firstOrNull { it.id == id }

    fun tier1Scenarios(): List<CatalogScenario> = load().scenarios.filter { it.tier <= 1 }
}
