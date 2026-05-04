package com.github.eladrimonos.jasprintellij

/**
 * Marks code used for compatibility with older Jaspr versions.
 * This is NOT deprecated as it is still required for older versions.
 *
 * @param reason The reason why this code is considered legacy.
 * @param version The Jaspr version where this implementation was superseded.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
annotation class JasprLegacy(
    val reason: String = "",
    val version: String = ""
)
