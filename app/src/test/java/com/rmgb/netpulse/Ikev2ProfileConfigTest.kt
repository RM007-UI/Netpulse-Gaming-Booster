package com.rmgb.netpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Ikev2ProfileConfigTest {
    @Test
    fun roundTripsEapCredentialsWithoutLosingFields() {
        val original = Ikev2ProfileConfig(
            serverAddress = "vpn.example.net",
            identity = "device@example.net",
            authMethod = Ikev2AuthMethod.EAP_MSCHAPV2,
            username = "alice",
            password = "secret",
            displayName = "Work IKEv2"
        )
        val decoded = Ikev2ProfileConfig.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsPskConfiguration() {
        val original = Ikev2ProfileConfig(
            serverAddress = "192.0.2.10",
            identity = "client@example.net",
            authMethod = Ikev2AuthMethod.PRE_SHARED_KEY,
            preSharedKey = "a-long-example-pre-shared-key"
        )
        assertEquals(original, Ikev2ProfileConfig.fromJson(original.toJson()))
    }

    @Test
    fun rejectsUrlAndMissingIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            Ikev2ProfileConfig(
                serverAddress = "https://vpn.example.net",
                identity = "user@example.net",
                authMethod = Ikev2AuthMethod.EAP_MSCHAPV2,
                username = "alice",
                password = "secret"
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            Ikev2ProfileConfig(
                serverAddress = "vpn.example.net",
                identity = "",
                authMethod = Ikev2AuthMethod.EAP_MSCHAPV2,
                username = "alice",
                password = "secret"
            ).validate()
        }
    }

    @Test
    fun rejectsMissingOrMixedAuthentication() {
        assertThrows(IllegalArgumentException::class.java) {
            Ikev2ProfileConfig(
                serverAddress = "vpn.example.net",
                identity = "client@example.net",
                authMethod = Ikev2AuthMethod.EAP_MSCHAPV2,
                username = "alice"
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            Ikev2ProfileConfig(
                serverAddress = "vpn.example.net",
                identity = "client@example.net",
                authMethod = Ikev2AuthMethod.PRE_SHARED_KEY,
                username = "alice",
                password = "secret",
                preSharedKey = "a-long-example-pre-shared-key"
            ).validate()
        }
    }
}
