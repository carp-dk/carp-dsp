package carp.dsp.core.common

/**
 * Annotation to exclude classes, methods, or properties from code coverage analysis.
 *
 * Usage examples:
 * ```kotlin
 * @ExcludeFromCoverage
 * class GeneratedClass {
 *     // Entire class excluded from coverage
 * }
 *
 * class MyClass {
 *     @ExcludeFromCoverage
 *     fun debugMethod() {
 *         // This method excluded from coverage
 *     }
 * }
 * ```
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CONSTRUCTOR
)
@Retention(AnnotationRetention.RUNTIME)
annotation class ExcludeFromCoverage(
    val reason: String = "Excluded from coverage analysis"
)
