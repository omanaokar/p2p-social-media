package com.proximity.transport

import com.proximity.transport.battery.DutyCycleController
import com.proximity.transport.battery.DutyCycleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DutyCycleTest {

    @Test
    fun `duty cycler alternates discovery and sleep`() = runTest {
        val startCalls = AtomicInteger()
        val stopCalls = AtomicInteger()

        val controller = DutyCycleController(
            scope = this,
            onStartDiscovery = { startCalls.incrementAndGet() },
            onStopDiscovery = { stopCalls.incrementAndGet() },
            defaultActiveDuration = Duration.ofSeconds(5),
            defaultIdleDuration = Duration.ofSeconds(10),
        )

        controller.start()
        runCurrent()
        assertEquals(1, startCalls.get())
        assertEquals(DutyCycleState.SCANNING, controller.state.value)
        assertTrue(controller.isScanning.value)

        // Advance through active scan window (5 seconds)
        advanceTimeBy(5001)
        runCurrent()
        assertEquals(1, stopCalls.get())
        assertEquals(DutyCycleState.IDLE, controller.state.value)

        // Advance through idle window (10 seconds)
        advanceTimeBy(10001)
        runCurrent()
        assertEquals(2, startCalls.get())
        assertEquals(DutyCycleState.SCANNING, controller.state.value)

        controller.stop()
        runCurrent()
        assertEquals(DutyCycleState.STOPPED, controller.state.value)
    }

    @Test
    fun `continuous discovery runs when duty cycling is disabled`() = runTest {
        val startCalls = AtomicInteger()
        val stopCalls = AtomicInteger()

        val controller = DutyCycleController(
            scope = this,
            onStartDiscovery = { startCalls.incrementAndGet() },
            onStopDiscovery = { stopCalls.incrementAndGet() },
        ).apply {
            dutyCycleEnabled = false
        }

        controller.start()
        runCurrent()
        assertEquals(1, startCalls.get())
        assertTrue(controller.isScanning.value)

        advanceTimeBy(60000)
        runCurrent()
        // Should not have entered sleep
        assertEquals(0, stopCalls.get())

        controller.stop()
        runCurrent()
    }
}

