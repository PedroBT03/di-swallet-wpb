/**
 * Performance benchmarks for the security-critical WPB paths (RQ4).
 *
 * These are @Tag("performance") tests, excluded from the default `test` task and
 * run through the `performanceTest` Gradle task. They measure the backend cost of
 * the cryptographic operations the Wallet Provider Backend actually performs on the
 * Remote WSCD (SoftHSM2 via PKCS#11), plus one end-to-end latency for a
 * FIDO2-authenticated issuance request that produces an HSM-signed SD-JWT.
 *
 * Honesty note for the dissertation: the WSCD is SoftHSM2 (a software token), so the
 * absolute latencies are not representative of a certified hardware HSM. What these
 * measurements characterise is the backend orchestration cost and the relative cost
 * of each cryptographic operation, measured on the real code path (KeyStore load,
 * SunPKCS11 signing, DER to R||S transcoding, JOSE assembly).
 */

package di.swallet.wpb.ops

import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.service.DeviceBindingService
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.wallet.WalletTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

@Tag("performance")
class WpbPerformanceBenchmarkTest : BaseIntegrationTest() {

    @Autowired lateinit var hsmService: HsmService
    @Autowired lateinit var deviceBindingService: DeviceBindingService
    @Autowired lateinit var walletUnitRepository: WalletUnitRepository

    /**
     * Latency distribution of a set of measured samples, in milliseconds.
     */
    private data class Stats(
        val label: String,
        val samples: Int,
        val minMs: Double,
        val medianMs: Double,
        val p95Ms: Double,
        val p99Ms: Double,
        val maxMs: Double,
        val meanMs: Double,
    ) {
        val opsPerSecond: Double get() = if (meanMs > 0) 1000.0 / meanMs else 0.0

        fun toMarkdownRow(): String =
            "| %s | %d | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.1f |".format(
                label, samples, minMs, medianMs, p95Ms, p99Ms, maxMs, meanMs, opsPerSecond,
            )

        fun toConsoleLine(): String =
            "PERF %-34s n=%-4d min=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f mean=%.2f ms (%.1f ops/s)".format(
                label, samples, minMs, medianMs, p95Ms, p99Ms, maxMs, meanMs, opsPerSecond,
            )
    }

    private fun percentile(sortedNanos: List<Long>, p: Double): Double {
        if (sortedNanos.isEmpty()) return 0.0
        val idx = ((sortedNanos.size - 1) * p).roundToInt().coerceIn(0, sortedNanos.size - 1)
        return sortedNanos[idx] / 1_000_000.0
    }

    private fun stats(label: String, nanos: List<Long>): Stats {
        val sorted = nanos.sorted()
        val meanMs = sorted.map { it / 1_000_000.0 }.average()
        return Stats(
            label = label,
            samples = sorted.size,
            minMs = sorted.first() / 1_000_000.0,
            medianMs = percentile(sorted, 0.50),
            p95Ms = percentile(sorted, 0.95),
            p99Ms = percentile(sorted, 0.99),
            maxMs = sorted.last() / 1_000_000.0,
            meanMs = meanMs,
        )
    }

    /** Runs [warmup] untimed iterations, then [measured] timed iterations of [op]. */
    private fun measure(warmup: Int, measured: Int, op: (Int) -> Unit): List<Long> {
        repeat(warmup) { op(it) }
        val nanos = ArrayList<Long>(measured)
        repeat(measured) { i ->
            val start = System.nanoTime()
            op(i)
            nanos += System.nanoTime() - start
        }
        return nanos
    }

    @Test
    fun `WPB cryptographic path performance benchmarks`() {
        val results = mutableListOf<Stats>()
        val notes = mutableListOf<String>()

        // Holder bootstrap (device binding + HSM key), not part of any measurement.
        val holder = "perf-holder-${UUID.randomUUID()}"
        WalletTestSupport.bootstrapHolderForIssuance(
            deviceBindingService, walletUnitRepository, hsmService, holder,
        )

        // 1. Core WSCD signing cost: ES256 over a representative signing input.
        val signingInput = ByteArray(256) { (it % 251).toByte() }
        results += stats(
            "hsm_es256_sign",
            measure(warmup = 20, measured = 200) { hsmService.signData(holder, signingInput) },
        )

        // 2. SD-JWT issuance signing (issuer JWT assembly + HSM signature).
        val sdClaims = mapOf(
            "vct" to "eu.europa.ec.eudi.pid.1",
            "iss" to "https://issuer.example",
            "iat" to Instant.now().epochSecond,
            "_sd_alg" to "sha-256",
            "_sd" to listOf(
                "abcABC0123456789abcABC0123456789abcABC01234",
                "defDEF0123456789defDEF0123456789defDEF01234",
                "ghiGHI0123456789ghiGHI0123456789ghiGHI01234",
            ),
        )
        results += stats(
            "sdjwt_issuance_sign",
            measure(warmup = 20, measured = 200) { hsmService.signSdJwt(holder, sdClaims) },
        )

        // 3. Presentation key-binding JWT signing (HAIP KB-JWT).
        val kbPayload = mapOf(
            "nonce" to UUID.randomUUID().toString(),
            "aud" to "https://verifier.example",
            "iat" to Instant.now().epochSecond,
            "sd_hash" to "GhQ1s2t3u4v5w6x7y8z9A0B1C2D3E4F5G6H7I8J9K0L",
        )
        results += stats(
            "presentation_kbjwt_sign",
            measure(warmup = 20, measured = 200) { hsmService.signKeyBindingJwt(holder, kbPayload) },
        )

        // 4. HSM key generation cost (provisioning): distinct holder per iteration,
        //    since the store keeps a single active key per user.
        results += stats(
            "hsm_ec_p256_keygen",
            measure(warmup = 3, measured = 30) { i ->
                val kgUser = "perf-keygen-$i-${UUID.randomUUID()}"
                val unit = walletUnitRepository.save(
                    di.swallet.wpb.domain.WalletUnit(holderId = kgUser),
                )
                hsmService.generateKeyForUser(kgUser, unit)
            },
        )

        // 5. Concurrent signing throughput on a shared holder key (HSM contention).
        results += concurrentSigningThroughput(holder, threads = 8, totalOps = 240, notes = notes)

        // 6. End-to-end: FIDO2-authenticated issuance request that yields an HSM-signed
        //    SD-JWT, timing only the authenticated POST (headers built untimed).
        runCatching { endToEndFido2ToSignedSdJwt() }
            .onSuccess { results += it }
            .onFailure { notes += "e2e_fido2_to_signed_sdjwt: not measured (${it.message})" }

        writeBaselineReport(results, notes)

        // Sanity guard so the benchmark fails loudly if a path regresses catastrophically
        // or the HSM is misconfigured; thresholds are deliberately generous for SoftHSM/CI.
        val coreSign = results.first { it.label == "hsm_es256_sign" }
        assertThat(coreSign.medianMs).isLessThan(1_000.0)
    }

    /**
     * Load study for NFG-04: the same PKCS#11 signing primitives at increasing concurrency.
     * Two operations share one harness so throughput and latency trends are comparable.
     * End-to-end issuance is excluded from the sweep because concurrent issue-sd calls
     * on one holder race credential supersession.
     */
    @Test
    fun `WPB signing load study under increasing concurrency`() {
        val holder = "perf-load-${UUID.randomUUID()}"
        WalletTestSupport.bootstrapHolderForIssuance(
            deviceBindingService, walletUnitRepository, hsmService, holder,
        )
        val signingInput = ByteArray(256) { (it % 251).toByte() }
        val operations = listOf(
            LoadOp("hsm_es256_sign") { hsmService.signData(holder, signingInput) },
            LoadOp("presentation_kbjwt_sign") {
                hsmService.signKeyBindingJwt(
                    holder,
                    mapOf(
                        "nonce" to UUID.randomUUID().toString(),
                        "aud" to "https://verifier.example",
                        "iat" to Instant.now().epochSecond,
                        "sd_hash" to "GhQ1s2t3u4v5w6x7y8z9A0B1C2D3E4F5G6H7I8J9K0L",
                    ),
                )
            },
        )
        val levels = intArrayOf(1, 2, 4, 8, 16)
        val repeats = 2
        val warmupOps = 16
        val measuredOps = 64
        val rows = mutableListOf<LoadRow>()

        for (op in operations) {
            for (threads in levels) {
                for (repeat in 1..repeats) {
                    rows += concurrentLoad(
                        operation = op.name,
                        threads = threads,
                        repeat = repeat,
                        warmupOps = warmupOps,
                        measuredOps = measuredOps,
                        work = op.work,
                    )
                }
            }
        }
        writeLoadStudy(rows, warmupOps, measuredOps, repeats, levels)
        assertThat(rows).isNotEmpty
        assertThat(rows.none { it.failures > 0 }).isTrue()
    }

    private data class LoadOp(val name: String, val work: () -> Unit)

    private data class LoadRow(
        val operation: String,
        val concurrency: Int,
        val repeat: Int,
        val samples: Int,
        val failures: Int,
        val wallSeconds: Double,
        val throughputOps: Double,
        val medianMs: Double,
        val p95Ms: Double,
        val meanMs: Double,
        val minMs: Double,
        val maxMs: Double,
    ) {
        fun toCsv(): String =
            listOf(
                operation,
                concurrency.toString(),
                repeat.toString(),
                samples.toString(),
                failures.toString(),
                "%.4f".format(wallSeconds),
                "%.4f".format(throughputOps),
                "%.2f".format(medianMs),
                "%.2f".format(p95Ms),
                "%.2f".format(meanMs),
                "%.2f".format(minMs),
                "%.2f".format(maxMs),
            ).joinToString(",")
    }

    private fun concurrentLoad(
        operation: String,
        threads: Int,
        repeat: Int,
        warmupOps: Int,
        measuredOps: Int,
        work: () -> Unit,
    ): LoadRow {
        runPool(threads, warmupOps, work)
        val measured = runPool(threads, measuredOps, work)
        val st = stats(operation, measured.nanos)
        println(
            "LOAD %s c=%-2d r=%d n=%d fail=%d wall=%.3fs thr=%.2f ops/s p50=%.2f p95=%.2f ms".format(
                operation, threads, repeat, measured.nanos.size, measured.failures,
                measured.wallSeconds, measured.throughput, st.medianMs, st.p95Ms,
            ),
        )
        return LoadRow(
            operation = operation,
            concurrency = threads,
            repeat = repeat,
            samples = measured.nanos.size,
            failures = measured.failures,
            wallSeconds = measured.wallSeconds,
            throughputOps = measured.throughput,
            medianMs = st.medianMs,
            p95Ms = st.p95Ms,
            meanMs = st.meanMs,
            minMs = st.minMs,
            maxMs = st.maxMs,
        )
    }

    private data class PoolRun(
        val nanos: List<Long>,
        val failures: Int,
        val wallSeconds: Double,
        val throughput: Double,
    )

    private fun runPool(threads: Int, totalOps: Int, work: () -> Unit): PoolRun {
        val pool = Executors.newFixedThreadPool(threads)
        val failures = AtomicInteger(0)
        val perOpNanos = java.util.Collections.synchronizedList(ArrayList<Long>(totalOps))
        val wallStart = System.nanoTime()
        try {
            val futures: List<Future<*>> = (0 until totalOps).map {
                pool.submit {
                    val s = System.nanoTime()
                    runCatching { work() }.onFailure { failures.incrementAndGet() }
                    perOpNanos += System.nanoTime() - s
                }
            }
            futures.forEach { it.get(180, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        val wallSeconds = (System.nanoTime() - wallStart) / 1_000_000_000.0
        val throughput = if (wallSeconds > 0) totalOps / wallSeconds else 0.0
        return PoolRun(perOpNanos.toList(), failures.get(), wallSeconds, throughput)
    }

    private fun concurrentSigningThroughput(
        holder: String,
        threads: Int,
        totalOps: Int,
        notes: MutableList<String>,
    ): Stats {
        val input = ByteArray(256) { (it % 251).toByte() }
        val pool = Executors.newFixedThreadPool(threads)
        val failures = AtomicInteger(0)
        val perOpNanos = java.util.Collections.synchronizedList(ArrayList<Long>(totalOps))
        try {
            val wallStart = System.nanoTime()
            val futures: List<Future<*>> = (0 until totalOps).map {
                pool.submit {
                    val s = System.nanoTime()
                    runCatching { hsmService.signData(holder, input) }
                        .onFailure { failures.incrementAndGet() }
                    perOpNanos += System.nanoTime() - s
                }
            }
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            val wallNanos = System.nanoTime() - wallStart
            val wallSeconds = wallNanos / 1_000_000_000.0
            val throughput = if (wallSeconds > 0) totalOps / wallSeconds else 0.0
            notes += "concurrent_sign: threads=%d totalOps=%d failures=%d wall=%.3fs throughput=%.1f ops/s".format(
                threads, totalOps, failures.get(), wallSeconds, throughput,
            )
        } finally {
            pool.shutdownNow()
        }
        // Recorded as a note rather than asserted, so a SoftHSM session limit does not
        // abort the whole benchmark and lose the report; interpret failures in Chapter 6.
        return stats("hsm_es256_sign_concurrent_x$threads", perOpNanos.toList())
    }

    private fun endToEndFido2ToSignedSdJwt(): Stats {
        val holder = "perf-e2e-${UUID.randomUUID()}"
        WalletTestSupport.bootstrapHolderForIssuance(
            deviceBindingService, walletUnitRepository, hsmService, holder,
        )
        // Warmup issuance requests (untimed).
        repeat(5) {
            restTemplate.postForEntity(
                "/api/v1/wallet/credentials/issue-sd/$holder",
                HttpEntity<String>(getDynamicHeaders(holder)),
                String::class.java,
            )
        }
        val nanos = ArrayList<Long>(30)
        repeat(30) {
            val headers = getDynamicHeaders(holder) // untimed: fresh FIDO2 assertion
            val start = System.nanoTime()
            val response = restTemplate.postForEntity(
                "/api/v1/wallet/credentials/issue-sd/$holder",
                HttpEntity<String>(headers),
                String::class.java,
            )
            nanos += System.nanoTime() - start
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        }
        return stats("e2e_fido2_to_signed_sdjwt", nanos)
    }

    private fun writeBaselineReport(results: List<Stats>, notes: List<String>) {
        val header = "%-34s samples  min   p50   p95   p99   max  mean  ops/s".format("benchmark")
        println("===== WPB performance benchmarks =====")
        println(header)
        results.forEach { println(it.toConsoleLine()) }
        notes.forEach { println("NOTE $it") }
        println("======================================")

        val reportDir = reportDir()
        Files.createDirectories(reportDir)
        val sb = StringBuilder()
        sb.appendLine("# DI-Swallet WPB Performance Report")
        sb.appendLine()
        sb.appendLine("Generated: ${Instant.now()}")
        sb.appendLine()
        sb.appendLine("WSCD: SoftHSM2 (software PKCS#11 token). Absolute latencies are not")
        sb.appendLine("representative of a certified hardware HSM; the relative cost of each")
        sb.appendLine("operation and the backend orchestration cost are the meaningful signal.")
        sb.appendLine()
        sb.appendLine("All values in milliseconds unless noted.")
        sb.appendLine()
        sb.appendLine("| Benchmark | Samples | Min | Median | p95 | p99 | Max | Mean | Ops/s |")
        sb.appendLine("|-----------|--------:|----:|-------:|----:|----:|----:|-----:|------:|")
        results.forEach { sb.appendLine(it.toMarkdownRow()) }
        if (notes.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Notes")
            notes.forEach { sb.appendLine("- $it") }
        }
        Files.writeString(reportDir.resolve("summary.md"), sb.toString())

        val csv = StringBuilder()
        csv.appendLine("benchmark,samples,min_ms,median_ms,p95_ms,p99_ms,max_ms,mean_ms,ops_per_s")
        results.forEach { s ->
            csv.appendLine(
                listOf(
                    s.label,
                    s.samples.toString(),
                    "%.2f".format(s.minMs),
                    "%.2f".format(s.medianMs),
                    "%.2f".format(s.p95Ms),
                    "%.2f".format(s.p99Ms),
                    "%.2f".format(s.maxMs),
                    "%.2f".format(s.meanMs),
                    "%.2f".format(s.opsPerSecond),
                ).joinToString(","),
            )
        }
        Files.writeString(reportDir.resolve("baseline.csv"), csv.toString())
        writeEnvironment(reportDir)
    }

    private fun writeLoadStudy(
        rows: List<LoadRow>,
        warmupOps: Int,
        measuredOps: Int,
        repeats: Int,
        levels: IntArray,
    ) {
        val reportDir = reportDir()
        Files.createDirectories(reportDir)
        val csv = StringBuilder()
        csv.appendLine(
            "operation,concurrency,repeat,samples,failures,wall_s,throughput_ops_s,median_ms,p95_ms,mean_ms,min_ms,max_ms",
        )
        rows.forEach { csv.appendLine(it.toCsv()) }
        Files.writeString(reportDir.resolve("load_study.csv"), csv.toString())

        val md = StringBuilder()
        md.appendLine("# WPB signing load study")
        md.appendLine()
        md.appendLine("Generated: ${Instant.now()}")
        md.appendLine()
        md.appendLine("- Warm-up operations per (operation, concurrency, repeat): $warmupOps")
        md.appendLine("- Measured operations per run: $measuredOps")
        md.appendLine("- Repeats per load level: $repeats")
        md.appendLine("- Concurrency levels: ${levels.joinToString(", ")}")
        md.appendLine("- WSCD: SoftHSM2 software PKCS#11 token")
        md.appendLine()
        md.appendLine("| Operation | Concurrency | Repeat | Failures | Throughput (ops/s) | Median (ms) | p95 (ms) |")
        md.appendLine("|-----------|------------:|-------:|---------:|-------------------:|------------:|---------:|")
        rows.forEach { r ->
            md.appendLine(
                "| %s | %d | %d | %d | %.2f | %.2f | %.2f |".format(
                    r.operation, r.concurrency, r.repeat, r.failures,
                    r.throughputOps, r.medianMs, r.p95Ms,
                ),
            )
        }
        Files.writeString(reportDir.resolve("load_study.md"), md.toString())
        writeEnvironment(reportDir)
        println("===== WPB load study written to $reportDir =====")
    }

    private fun writeEnvironment(reportDir: Path) {
        val cpuModel = runCatching {
            Files.readAllLines(Paths.get("/proc/cpuinfo"))
                .firstOrNull { it.startsWith("model name") }
                ?.substringAfter(":")
                ?.trim()
        }.getOrNull() ?: "unknown"
        val memKb = runCatching {
            Files.readAllLines(Paths.get("/proc/meminfo"))
                .firstOrNull { it.startsWith("MemTotal:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLong()
        }.getOrNull()
        val memGib = if (memKb != null) "%.1f".format(memKb / 1024.0 / 1024.0) else "unknown"
        val json = """
            {
              "generated": "${Instant.now()}",
              "os": "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
              "arch": "${System.getProperty("os.arch")}",
              "cpu_model": ${jsonString(cpuModel)},
              "available_processors": ${Runtime.getRuntime().availableProcessors()},
              "mem_gib": "$memGib",
              "java_version": ${jsonString(System.getProperty("java.version"))},
              "java_vm": ${jsonString(System.getProperty("java.vm.name"))},
              "wscd": "SoftHSM2 PKCS#11 software token",
              "database": "H2 in-memory (Spring profile test)",
              "postgresql": "not used in this benchmark",
              "client_server": "same JVM (Spring Boot RANDOM_PORT TestRestTemplate)",
              "note": "SoftHSM2 is a software prototype substitute. Results must not be extrapolated to certified Remote WSCD hardware."
            }
        """.trimIndent()
        Files.writeString(reportDir.resolve("environment.json"), json)
    }

    private fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun reportDir(): Path = Paths.get(
        System.getProperty("performance.report.dir") ?: "build/reports/performance",
    )
}
