package di.swallet.wpb.conformance

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConformanceScenario(
    val value: String,
)
