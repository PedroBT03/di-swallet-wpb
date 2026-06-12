package di.swallet.wpb.ops.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry

object WpbMetricsTestSupport {
    fun noop(): WpbMetrics = WpbMetrics(SimpleMeterRegistry())
}
