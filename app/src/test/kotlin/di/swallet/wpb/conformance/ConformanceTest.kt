/**
 * Marks test classes and methods that belong to the OpenID conformance suite.
 */

package di.swallet.wpb.conformance

import di.swallet.wpb.conformance.report.ConformanceReportExtension
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(ConformanceTags.CONFORMANCE)
@ExtendWith(ConformanceReportExtension::class)
annotation class ConformanceTest
