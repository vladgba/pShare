package x.vladgba.sync.core.testing

import x.vladgba.sync.core.DiffTest
import x.vladgba.sync.core.EndToEndTest
import x.vladgba.sync.core.RulesTest
import x.vladgba.sync.core.ScanPlanTest
import x.vladgba.sync.core.VersionVectorTest
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** Tiny dependency-free test harness (the project uses no libraries, test ones included). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class AfterTest

class AssertionFailed(message: String) : AssertionError(message)

fun fail(message: String): Nothing = throw AssertionFailed(message)

fun assertEquals(expected: Any?, actual: Any?, message: String? = null) {
    if (expected != actual) fail("${message?.let { "$it: " } ?: ""}expected <$expected> but was <$actual>")
}

fun assertTrue(value: Boolean, message: String? = null) {
    if (!value) fail(message ?: "expected true")
}

fun assertFalse(value: Boolean, message: String? = null) {
    if (value) fail(message ?: "expected false")
}

fun assertNull(value: Any?, message: String? = null) {
    if (value != null) fail(message ?: "expected null but was <$value>")
}

inline fun <reified T> assertIs(value: Any?): T =
    value as? T ?: fail("expected ${T::class.simpleName} but was <$value>")

private val ALL = listOf(
    VersionVectorTest::class.java,
    DiffTest::class.java,
    RulesTest::class.java,
    ScanPlanTest::class.java,
    EndToEndTest::class.java,
)

fun main() {
    var passed = 0
    val failures = mutableListOf<String>()
    for (cls in ALL) {
        val tests = cls.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.sortedBy { it.name }
        val after = cls.declaredMethods.filter { it.isAnnotationPresent(AfterTest::class.java) }
        for (m in tests) {
            val name = "${cls.simpleName}.${m.name}"
            val instance = cls.getDeclaredConstructor().newInstance()
            try {
                m.invoke(instance)
                passed++
                println("PASS $name")
            } catch (e: InvocationTargetException) {
                val cause = e.targetException
                failures += name
                println("FAIL $name: $cause")
                cause.printStackTrace(System.out)
            } finally {
                after.forEach { runCatching { it.invoke(instance) } }
            }
        }
    }
    println("\n$passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
    exitProcess(0)
}
