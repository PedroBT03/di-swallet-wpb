package di.swallet.wpb.conformance.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import di.swallet.wpb.conformance.catalog.ConformanceCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

object ConformanceReportWriter {
    private val mapper = ObjectMapper().registerKotlinModule()

    fun write(reportDir: Path, results: List<ScenarioResult>, catalog: ConformanceCatalog) {
        Files.createDirectories(reportDir)
        val byId = results.associateBy { it.scenarioId }
        val rows = catalog.scenarios.map { scenario ->
            val result = byId[scenario.id]
            ReportRow(
                id = scenario.id,
                hlr = scenario.hlr,
                protocol = scenario.protocol,
                tier = scenario.tier,
                negative = scenario.negative,
                status = result?.status ?: "NOT_RUN",
                durationMs = result?.durationMs,
                failureMessage = result?.failureMessage,
            )
        } + results.filter { r -> catalog.scenarios.none { it.id == r.scenarioId } }.map { r ->
            ReportRow(
                id = r.scenarioId,
                hlr = r.hlr,
                protocol = r.protocol,
                tier = r.tier,
                negative = false,
                status = r.status,
                durationMs = r.durationMs,
                failureMessage = r.failureMessage,
            )
        }

        val passed = rows.count { it.status == "PASSED" }
        val failed = rows.count { it.status == "FAILED" }
        val skipped = rows.count { it.status == "SKIPPED" || it.status == "NOT_RUN" }

        val summary = ConformanceSummary(
            generatedAt = Instant.now().toString(),
            total = rows.size,
            passed = passed,
            failed = failed,
            skipped = skipped,
            scenarios = rows,
        )

        mapper.writerWithDefaultPrettyPrinter().writeValue(reportDir.resolve("summary.json").toFile(), summary)
        Files.writeString(reportDir.resolve("summary.md"), renderMarkdown(summary))
    }

    private fun renderMarkdown(summary: ConformanceSummary): String {
        val sb = StringBuilder()
        sb.appendLine("# DI-Swallet WPB Conformance Report")
        sb.appendLine()
        sb.appendLine("Generated: ${summary.generatedAt}")
        sb.appendLine()
        sb.appendLine("| Metric | Count |")
        sb.appendLine("|--------|------:|")
        sb.appendLine("| Total scenarios | ${summary.total} |")
        sb.appendLine("| Passed | ${summary.passed} |")
        sb.appendLine("| Failed | ${summary.failed} |")
        sb.appendLine("| Skipped / not run | ${summary.skipped} |")
        sb.appendLine()
        sb.appendLine("| id | hlr | protocol | tier | status | durationMs |")
        sb.appendLine("|----|-----|----------|-----:|--------|------------:|")
        summary.scenarios.forEach { row ->
            val hlr = row.hlr.joinToString(", ")
            val duration = row.durationMs?.toString() ?: "-"
            sb.appendLine("| ${row.id} | $hlr | ${row.protocol ?: "-"} | ${row.tier ?: "-"} | ${row.status} | $duration |")
        }
        val failures = summary.scenarios.filter { it.status == "FAILED" && !it.failureMessage.isNullOrBlank() }
        if (failures.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Failures")
            failures.forEach { row ->
                sb.appendLine("- **${row.id}**: ${row.failureMessage}")
            }
        }
        return sb.toString()
    }
}

data class ConformanceSummary(
    val generatedAt: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val scenarios: List<ReportRow>,
)

data class ReportRow(
    val id: String,
    val hlr: List<String>,
    val protocol: String?,
    val tier: Int?,
    val negative: Boolean,
    val status: String,
    val durationMs: Long?,
    val failureMessage: String?,
)
