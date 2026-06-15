/**
 * Shared test helpers for wpb metrics.
 */

package di.swallet.wpb.ops.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry

object WpbMetricsTestSupport {
    /** Returns a WpbMetrics instance backed by a SimpleMeterRegistry for no-op metric recording in tests. */
    fun noop(): WpbMetrics = WpbMetrics(SimpleMeterRegistry())
}
