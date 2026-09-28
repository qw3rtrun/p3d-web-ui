package org.qw3rtrun.p3d.g.code.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The dependency direction inside the portable core, enforced over the source text.
 *
 * `core/token` is the bottom layer: tokens and the lexer. It may name nothing above itself - not in
 * an import, not in code, not in a KDoc link. `core/block` sits on top of it: lines, commands and
 * checksums, and may name `token` and itself. Both may import `kotlin.*`, and `java.math.BigDecimal`
 * is the one JVM type the core still carries (see the `low-level-protocol-dev` skill's known debt).
 *
 * The scan is textual on purpose. A fully-qualified name in a KDoc link is a dependency a reader
 * follows and a port author has to resolve, even though the compiler never sees it, and it is the
 * form an IDE refactoring leaves behind when it moves a class across the boundary.
 *
 * ```
 * GLayeringTest().`the token layer names nothing above itself and block names nothing above token`()
 * ```
 */
class GLayeringTest {

    private val root = File("src/main/kotlin/org/qw3rtrun/p3d/g/code/core")

    private val project = "org.qw3rtrun.p3d."

    private val tokenPackage = "g.code.core.token"

    private val blockPackage = "g.code.core.block"

    /**
     * Every line in `core/token` and `core/block` that reaches outside what its layer may name.
     *
     * The directories are asserted to exist and hold sources first, so a moved or renamed tree
     * cannot make the scan pass by finding nothing.
     *
     * **The expected list is temporary, and every entry in it is a known violation being removed.**
     * It must only ever shrink. The two `GTokenizer.kt` imports of `GLine` and `GLiner` went when
     * `lines()` moved from `GTokenizer` to `GLiner`'s companion. What is left:
     * - the `GTokens.kt` import of `GBlockPart` goes when `GComment` stops being a block part and
     *   `GCommentPart` wraps it instead.
     *
     * After that the expected list is empty, permanently.
     */
    @Test
    fun `the token layer names nothing above itself and block names nothing above token`() {
        val token = File(root, "token")
        val block = File(root, "block")
        assertTrue(sources(token).isNotEmpty()) { "no sources under ${token.absolutePath}" }
        assertTrue(sources(block).isNotEmpty()) { "no sources under ${block.absolutePath}" }

        val violations = violations(token, listOf(tokenPackage)) +
            violations(block, listOf(tokenPackage, blockPackage))

        assertEquals(
            listOf(
                "GTokens.kt: import org.qw3rtrun.p3d.g.code.core.block.GBlockPart",
            ),
            violations,
        )
    }

    private fun sources(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }?.sortedBy { it.name } ?: emptyList()

    private fun violations(dir: File, allowed: List<String>): List<String> =
        sources(dir).flatMap { file ->
            file.readLines().map { it.trim() }.filter { line -> !lineAllowed(line, allowed) }
                .map { "${file.name}: $it" }
        }

    private fun lineAllowed(line: String, allowed: List<String>): Boolean {
        if (line.startsWith("import ") && !importAllowed(line.removePrefix("import ").trim(), allowed)) return false
        var at = line.indexOf(project)
        while (at >= 0) {
            val rest = line.substring(at + project.length)
            if (allowed.none { inPackage(rest, it) }) return false
            at = line.indexOf(project, at + project.length)
        }
        return true
    }

    private fun importAllowed(target: String, allowed: List<String>): Boolean =
        target.startsWith("kotlin.") ||
            target == "java.math.BigDecimal" ||
            (target.startsWith(project) && allowed.any { inPackage(target.removePrefix(project), it) })

    private fun inPackage(name: String, pkg: String): Boolean =
        name.startsWith(pkg) && (name.length == pkg.length || !Character.isJavaIdentifierPart(name[pkg.length]))
}
