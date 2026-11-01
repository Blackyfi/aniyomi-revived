package eu.kanade.tachiyomi.crash

import android.app.ApplicationExitInfo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProcessExitLogTest {

    @Test
    fun `failures are abnormal and ordinary exits are not`() {
        ProcessExitLog.isAbnormal(ApplicationExitInfo.REASON_ANR) shouldBe true
        ProcessExitLog.isAbnormal(ApplicationExitInfo.REASON_CRASH_NATIVE) shouldBe true
        ProcessExitLog.isAbnormal(ApplicationExitInfo.REASON_LOW_MEMORY) shouldBe true
        ProcessExitLog.isAbnormal(ApplicationExitInfo.REASON_EXIT_SELF) shouldBe false
        ProcessExitLog.isAbnormal(ApplicationExitInfo.REASON_USER_REQUESTED) shouldBe false
        ProcessExitLog.isAbnormal(ApplicationExitInfo.REASON_UNKNOWN) shouldBe false
    }

    @Test
    fun `unknown reasons still get a name`() {
        ProcessExitLog.reasonName(ApplicationExitInfo.REASON_ANR) shouldBe "ANR"
        ProcessExitLog.reasonName(999) shouldBe "reason 999"
    }

    @Test
    fun `main thread frames are taken from the trace`() {
        val trace = """
            ----- pid 1 at 2026-11-01 -----
            "Signal Catcher" daemon prio=10 tid=7 Runnable
              at not.the.main.Thread(Thread.java:1)

            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1
              native: #00 pc 000 /system/lib64/libc.so (syscall+28)
              at eu.kanade.Foo.bar(Foo.kt:1)
              - waiting to lock <0x01> (a java.lang.Object)
              at eu.kanade.Foo.baz(Foo.kt:2)

            "other" prio=5 tid=2 Waiting
              at eu.kanade.Other.run(Other.kt:3)
        """.trimIndent()

        ProcessExitLog.mainThreadStack(trace.lineSequence()) shouldBe """
            native: #00 pc 000 /system/lib64/libc.so (syscall+28)
            at eu.kanade.Foo.bar(Foo.kt:1)
            at eu.kanade.Foo.baz(Foo.kt:2)
        """.trimIndent()
    }

    @Test
    fun `a trace without a main thread gives nothing`() {
        ProcessExitLog.mainThreadStack(sequenceOf("\"other\" prio=5", "  at a.B.c(B.kt:1)")) shouldBe null
        ProcessExitLog.mainThreadStack(emptySequence()) shouldBe null
    }

    @Test
    fun `long stacks are capped`() {
        val lines = sequenceOf("\"main\" prio=5 tid=1 Blocked") +
            generateSequence { "  at a.B.c(B.kt:1)" }.take(100)

        ProcessExitLog.mainThreadStack(lines)!!.lines().size shouldBe 30
    }
}
