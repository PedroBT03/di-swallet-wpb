/**
 * Links a test to a conformance catalog scenario identifier.
 */

package di.swallet.wpb.conformance

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConformanceScenario(
    val value: String,
)
