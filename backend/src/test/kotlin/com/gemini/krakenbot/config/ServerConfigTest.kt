package com.gemini.krakenbot.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class ServerConfigTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "uses the default port when the property is absent" {
            val previousValue = System.getProperty(ServerConfig.SERVER_PORT_PROPERTY)
            try {
                System.clearProperty(ServerConfig.SERVER_PORT_PROPERTY)

                ServerConfig.resolveServerPort() shouldBe ServerConfig.DEFAULT_SERVER_PORT
            } finally {
                if (previousValue == null) {
                    System.clearProperty(ServerConfig.SERVER_PORT_PROPERTY)
                } else {
                    System.setProperty(ServerConfig.SERVER_PORT_PROPERTY, previousValue)
                }
            }
        }

        "uses the default port when an explicit value is blank" {
            ServerConfig.resolveServerPort("  ") shouldBe ServerConfig.DEFAULT_SERVER_PORT
        }

        "accepts valid boundary and custom ports" {
            ServerConfig.resolveServerPort("1") shouldBe 1
            ServerConfig.resolveServerPort("18080") shouldBe 18080
            ServerConfig.resolveServerPort("65535") shouldBe 65535
        }

        "rejects a nonnumeric port" {
            shouldThrow<IllegalArgumentException> {
                ServerConfig.resolveServerPort("not-a-port")
            }
        }

        "rejects a port below the valid range" {
            shouldThrow<IllegalArgumentException> {
                ServerConfig.resolveServerPort("0")
            }
        }

        "rejects a port above the valid range" {
            shouldThrow<IllegalArgumentException> {
                ServerConfig.resolveServerPort("65536")
            }
        }
    }
}
