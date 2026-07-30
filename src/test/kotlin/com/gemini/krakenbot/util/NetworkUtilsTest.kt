package com.gemini.krakenbot.util

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class NetworkUtilsTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should identify localhost and loopback origins" {
            isLocalOrPrivateOrigin("http://localhost:8080") shouldBe true
            isLocalOrPrivateOrigin("https://127.0.0.1:443") shouldBe true
            isLocalOrPrivateOrigin("http://localhost") shouldBe true
            isLocalOrPrivateOrigin("127.0.0.1") shouldBe true
            isLocalOrPrivateOrigin("http://[::1]:8080") shouldBe true
            isLocalOrPrivateOrigin("[::1]") shouldBe true
        }

        "should identify .local mDNS origins" {
            isLocalOrPrivateOrigin("http://app-server.local:8080") shouldBe true
        }

        "should identify private IP ranges (192.168.x, 10.x, 169.254.x)" {
            isLocalOrPrivateOrigin("http://192.168.1.100:8080") shouldBe true
            isLocalOrPrivateOrigin("http://10.0.0.5:8080") shouldBe true
            isLocalOrPrivateOrigin("http://169.254.10.20:8080") shouldBe true
        }

        "should identify 172.16.x.x to 172.31.x.x private IP range" {
            isLocalOrPrivateOrigin("http://172.16.0.1:8080") shouldBe true
            isLocalOrPrivateOrigin("http://172.31.255.255:8080") shouldBe true
            isLocalOrPrivateOrigin("http://172.15.0.1:8080") shouldBe false
            isLocalOrPrivateOrigin("http://172.32.0.1:8080") shouldBe false
            isLocalOrPrivateOrigin("http://172.invalid:8080") shouldBe false
        }

        "should return false for public domain origins" {
            isLocalOrPrivateOrigin("https://kraken.com") shouldBe false
            isLocalOrPrivateOrigin("https://google.com:443") shouldBe false
        }

        "CQ-12-L2: should reject private-prefix lookalikes and invalid numeric IPv4 hosts" {
            isLocalOrPrivateOrigin("http://10.evil.example") shouldBe false
            isLocalOrPrivateOrigin("http://192.168.evil.example") shouldBe false
            isLocalOrPrivateOrigin("http://169.254.example.com") shouldBe false
            isLocalOrPrivateOrigin("http://172.16.example.com") shouldBe false
            isLocalOrPrivateOrigin("http://10.0.0.999") shouldBe false
            isLocalOrPrivateOrigin("http://192.168.1") shouldBe false
            isLocalOrPrivateOrigin("http://192.167.1.1") shouldBe false
            isLocalOrPrivateOrigin("http://169.253.1.1") shouldBe false
            isLocalOrPrivateOrigin("http://8.8.8.8") shouldBe false
            isLocalOrPrivateOrigin("http://[2001:db8::1]") shouldBe false
        }

        "CQ-12-L2: should reject malformed origins and local-hostname lookalikes" {
            isLocalOrPrivateOrigin("http://localhost.evil.example") shouldBe false
            isLocalOrPrivateOrigin("http://app.local.evil.example") shouldBe false
            isLocalOrPrivateOrigin("http://evil.example@localhost") shouldBe false
            isLocalOrPrivateOrigin("ftp://localhost") shouldBe false
            isLocalOrPrivateOrigin("http://localhost/settings") shouldBe false
            isLocalOrPrivateOrigin("http://localhost?next=settings") shouldBe false
            isLocalOrPrivateOrigin("http://localhost#settings") shouldBe false
            isLocalOrPrivateOrigin("http://app..local") shouldBe false
            isLocalOrPrivateOrigin("http://-app.local") shouldBe false
            isLocalOrPrivateOrigin("not a valid origin") shouldBe false
            isLocalOrPrivateOrigin("") shouldBe false
        }
    }
}
