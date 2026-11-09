package com.portoperatingsystem.tv.surface

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The vendored `PortosTokens.kt` is the monorepo's generated file, copied by `make sync-tokens`, which records the
 * monorepo commit and the SHA-256 of the file as generated. These tests fail when the vendored copy was edited by
 * hand, and, when a monorepo checkout is given (`MONOREPO=/path make test`), when it differs from the file at the
 * commit it names.
 */
class TokensTest {
    private val relative = "frontend/packages/ui/theme/PortosTokens.kt"
    private val vendored = File("src/main/kotlin/com/portoperatingsystem/tv/surface/PortosTokens.kt")

    private fun text(): String = vendored.readText().replace("\r", "")

    private fun header(name: String): String =
        Regex("^// $name: (.+)$", RegexOption.MULTILINE).find(text())?.groupValues?.get(1)
            ?: error("PortosTokens.kt has no '$name' header line")

    /** The file as the monorepo generates it: the two recorded header lines back to UNRECORDED. */
    private fun asGenerated(): String = text()
        .replace(Regex("^// Source commit: .+$", RegexOption.MULTILINE), "// Source commit: UNRECORDED")
        .replace(Regex("^// Source SHA-256: .+$", RegexOption.MULTILINE), "// Source SHA-256: UNRECORDED")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun theHeaderNamesACommitAndADigest() {
        assertTrue(Regex("[0-9a-f]{40}").matches(header("Source commit")), "Source commit must be a full SHA")
        assertTrue(Regex("[0-9a-f]{64}").matches(header("Source SHA-256")), "Source SHA-256 must be a SHA-256")
    }

    @Test
    fun theVendoredFileIsExactlyWhatWasGenerated() {
        // sync-tokens.sh hashes the file with trailing newlines trimmed and one added.
        val digest = sha256(asGenerated().trimEnd('\n') + "\n")

        assertEquals(header("Source SHA-256"), digest, "PortosTokens.kt was edited; run make sync-tokens instead")
    }

    @Test
    fun theVendoredFileMatchesTheMonorepoFileAtTheCommitItNames() {
        val monorepo = System.getenv("MONOREPO")
        assumeTrue(
            "set MONOREPO to a portos-backend checkout to compare with the source commit",
            !monorepo.isNullOrBlank()
        )
        val commit = header("Source commit")
        val process = ProcessBuilder("git", "-C", monorepo, "show", "$commit:$relative")
            .redirectErrorStream(true)
            .start()
        val source = process.inputStream.bufferedReader().readText().replace("\r", "")
        val exit = process.waitFor()

        assertEquals(0, exit, "commit $commit is not in $monorepo: $source")
        assertEquals(source.trimEnd('\n'), asGenerated().trimEnd('\n'))
    }
}
