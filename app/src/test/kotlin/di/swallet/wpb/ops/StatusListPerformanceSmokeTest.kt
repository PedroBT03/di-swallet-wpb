/**
 * Smoke tests for status list performance.
 */

package di.swallet.wpb.ops

import di.swallet.wpb.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

@Tag("performance")
class StatusListPerformanceSmokeTest : BaseIntegrationTest() {

    /**
     * Issues 30 unauthenticated GET requests to the PRIMARY_LIST JWT endpoint, measures
     * latency, and expects the p95 response time to stay below 3000 ms.
     */
    @Test
    fun `status list JWT endpoint p95 stays within CI smoke threshold`() {
        val iterations = 30
        val latenciesMs = mutableListOf<Long>()
        repeat(iterations) {
            val start = System.nanoTime()
            val response = restTemplate.getForEntity(
                "/api/v1/wallet/status-lists/PRIMARY_LIST",
                String::class.java,
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            latenciesMs += (System.nanoTime() - start) / 1_000_000
        }
        latenciesMs.sort()
        val p95Index = ((iterations - 1) * 0.95).toInt().coerceIn(0, iterations - 1)
        val p95 = latenciesMs[p95Index]
        assertThat(p95).isLessThan(3_000)
    }
}
