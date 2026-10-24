package org.ghost.setup

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import com.google.protobuf.StringValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Host-only dependency checks. These do not run inference, Android radios, or the GHOST protocol. */
class DependencyCompatibilityTest {
    @Test
    fun litertConfigurationCanBeConstructedWithoutLoadingNativeRuntime() {
        val config = EngineConfig(modelPath = "/not-provisioned/model.litertlm", backend = Backend.CPU())
        assertEquals("/not-provisioned/model.litertlm", config.modelPath)
    }

    @Test
    fun protobufLiteAndJsonAreUsableOnTheUnitTestClasspath() {
        val message = StringValue.of("dependency check")
        assertEquals(message, StringValue.parseFrom(message.toByteArray()))
        val json = Json.parseToJsonElement("""{"status":"unavailable"}""").jsonObject
        assertEquals("unavailable", json.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun coroutineTestSchedulerAndFlowUseCompatibleRuntimeVersions() = runTest {
        val state = MutableStateFlow("unavailable")
        assertEquals("unavailable", state.first())
    }
}

