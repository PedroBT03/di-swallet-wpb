/**
 * JUnit extension that collects conformance scenario outcomes into a report.
 */

package di.swallet.wpb.conformance.report

import di.swallet.wpb.conformance.ConformanceScenario
import di.swallet.wpb.conformance.catalog.ConformanceCatalogLoader
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ScenarioResult(
    val scenarioId: String,
    val className: String,
    val methodName: String,
    val status: String,
    val durationMs: Long,
    val hlr: List<String>,
    val protocol: String?,
    val tier: Int?,
    val failureMessage: String? = null,
)

object ConformanceReportCollector {
    private val results = ConcurrentHashMap<String, ScenarioResult>()
    private val runCount = AtomicInteger(0)

    /** Upserts a scenario result keyed by scenario id, replacing any prior entry for the same id. */
    fun record(result: ScenarioResult) {
        results[result.scenarioId] = result
    }

    /** Increments the global test-run counter invoked before each conformance test executes. */
    fun markRun() {
        runCount.incrementAndGet()
    }

    /** Returns all recorded scenario results sorted alphabetically by scenario id. */
    fun snapshot(): List<ScenarioResult> = results.values.sortedBy { it.scenarioId }

    /** Clears accumulated results and resets the run counter between conformance suite invocations. */
    fun clear() {
        results.clear()
        runCount.set(0)
    }
}

class ConformanceReportExtension : TestWatcher, AfterAllCallback, BeforeTestExecutionCallback {
    private val startTimes = ConcurrentHashMap<String, Long>()

    /** Records a PASSED scenario outcome with elapsed duration from the stored start timestamp. */
    override fun testSuccessful(context: ExtensionContext) {
        record(context, "PASSED", null)
    }

    /** Records a FAILED scenario outcome capturing the throwable message as the failure detail. */
    override fun testFailed(context: ExtensionContext, cause: Throwable) {
        record(context, "FAILED", cause.message)
    }

    /** Records a SKIPPED scenario outcome when JUnit aborts a test before completion. */
    override fun testAborted(context: ExtensionContext, cause: Throwable) {
        record(context, "SKIPPED", cause.message)
    }

    /** Records a SKIPPED scenario outcome for explicitly disabled tests with the disable reason. */
    override fun testDisabled(context: ExtensionContext, reason: java.util.Optional<String>) {
        record(context, "SKIPPED", reason.orElse("disabled"))
    }

    /** Marks the test run and stores the current time as the start timestamp for duration calculation. */
    override fun beforeTestExecution(context: ExtensionContext) {
        ConformanceReportCollector.markRun()
        startTimes[context.uniqueId] = System.currentTimeMillis()
    }

    /** Builds a ScenarioResult from the JUnit context, catalog metadata, and measured duration. */
    private fun record(context: ExtensionContext, status: String, failure: String?) {
        val scenarioId = resolveScenarioId(context)
        val catalog = ConformanceCatalogLoader.byId(scenarioId)
        val started = startTimes.remove(context.uniqueId) ?: System.currentTimeMillis()
        val duration = System.currentTimeMillis() - started
        ConformanceReportCollector.record(
            ScenarioResult(
                scenarioId = scenarioId,
                className = context.requiredTestClass.name,
                methodName = context.testMethod.orElse(null)?.name ?: "unknown",
                status = status,
                durationMs = duration,
                hlr = catalog?.hlr ?: emptyList(),
                protocol = catalog?.protocol,
                tier = catalog?.tier,
                failureMessage = failure,
            ),
        )
    }

    /** Resolves the catalog scenario id from @ConformanceScenario or falls back to class.method naming. */
    private fun resolveScenarioId(context: ExtensionContext): String {
        context.testMethod.orElse(null)?.getAnnotation(ConformanceScenario::class.java)?.let { return it.value }
        context.testClass.orElse(null)?.getAnnotation(ConformanceScenario::class.java)?.let { return it.value }
        val className = context.testClass.orElse(Object::class.java).simpleName
        val methodName = context.testMethod.orElse(null)?.name ?: "class"
        return "$className.$methodName"
    }

    /** Writes summary.json and summary.md after all tests in the class complete when results exist. */
    override fun afterAll(context: ExtensionContext) {
        if (ConformanceReportCollector.snapshot().isEmpty()) return
        val reportDir = reportDirectory()
        ConformanceReportWriter.write(reportDir, ConformanceReportCollector.snapshot(), ConformanceCatalogLoader.load())
    }

    /** Resolves the report output directory from the system property or the default build/reports/conformance path. */
    private fun reportDirectory(): Path {
        System.getProperty("conformance.report.dir")?.let { return Paths.get(it) }
        val default = Paths.get("build/reports/conformance")
        if (!Files.exists(default)) Files.createDirectories(default)
        return default
    }
}
