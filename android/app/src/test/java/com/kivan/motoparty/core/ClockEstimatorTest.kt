package com.kivan.motoparty.core

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockEstimatorTest {
    @Test
    fun fixtureSteps() {
        val f = Fixtures.load("clock.json").jsonObject
        val est = ClockEstimator()
        for ((i, step) in f["steps"]!!.jsonArray.withIndex()) {
            val s = step.jsonObject["sample"]!!.jsonArray.map { it.jsonPrimitive.long }
            est.add(s[0], s[1], s[2], s[3])
            assertEquals("step $i", step.jsonObject["expectOffset"]!!.jsonPrimitive.double, est.offset!!, 1e-9)
        }
        for (c in f["conversions"]!!.jsonArray) {
            val host = c.jsonObject["host"]!!.jsonPrimitive.long
            val local = c.jsonObject["local"]!!.jsonPrimitive.long
            assertEquals(local.toDouble(), est.hostToLocal(host)!!, 1e-9)
            assertEquals(host.toDouble(), est.localToHost(local)!!, 1e-9)
        }
    }
}
