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

        writeReport(results, notes)

        // Sanity guard so the benchmark fails loudly if a path regresses catastrophically
        // or the HSM is misconfigured; thresholds are deliberately generous for SoftHSM/CI.
        val coreSign = results.first { it.label == "hsm_es256_sign" }
        assertThat(coreSign.medianMs).isLessThan(1_000.0)
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

    private fun writeReport(results: List<Stats>, notes: List<String>) {
        val header = "%-34s samples  min   p50   p95   p99   max  mean  ops/s".format("benchmark")
        println("===== WPB performance benchmarks =====")
        println(header)
        results.forEach { println(it.toConsoleLine()) }
        notes.forEach { println("NOTE $it") }
        println("======================================")

        val reportDir: Path = Paths.get(
            System.getProperty("performance.report.dir") ?: "build/reports/performance",
        )
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
    }
}
