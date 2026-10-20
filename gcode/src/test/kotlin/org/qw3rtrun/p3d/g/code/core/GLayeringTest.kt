package org.qw3rtrun.p3d.g.code.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The dependency direction inside the portable core, enforced over the source text.
 *
 * Three layers, each allowed to name itself and everything below it:
 *
 * - `core/token` - tokens and the lexer. The bottom: it may name nothing above itself, not in an
 *   import, not in code, not in a KDoc link.
 * - `core/block` - blocks, framing, checksums, and the one lexical field rule (`valueIndex`). May
 *   name `token` and itself.
 * - `core/command` - words, commands, drafts and the command head. May name `block`, `token` and
 *   itself.
 *
 * All three may import `kotlin.*`, and `java.math.BigDecimal` is the one JVM type the core still
 * carries (see the `low-level-protocol-dev` skill's known debt). `core/` itself - `GEncoder` - and
 * `core/session` sit above all three and are not scanned: they are allowed to span.
 *
 * The scan is textual on purpose. A fully-qualified name in a KDoc link is a dependency a reader
 * follows and a port author has to resolve, even though the compiler never sees it, and it is the
 * form an IDE refactoring leaves behind when it moves a class across the boundary.
 *
 * ```
 * GLayeringTest().`each core layer names only itself and the layers below it`()
 * ```
 */
class GLayeringTest {

    private val root = File("src/main/kotlin/org/qw3rtrun/p3d/g/code/core")

    private val project = "org.qw3rtrun.p3d."

    private val tokenPackage = "g.code.core.token"

    private val blockPackage = "g.code.core.block"

    private val commandPackage = "g.code.core.command"

    /**
     * Every source line in the three layers that reaches outside what its layer may name.
     *
     * The directories are asserted to exist and hold sources first, so a moved or renamed tree
     * cannot make the scan pass by finding nothing.
     *
     * **The expected list is empty, and stays empty.** It started with three known violations,
     * removed one change at a time: `GTokenizer`'s imports of `GBlock` and `GBlockReader` (the read
     * pipeline moved to `GBlockReader.blocks`), and `GTokens`' import of `GBlockPart` (a comment goes
     * into a block as `GCommentPart` rather than being a block part itself). The `command` layer was
     * split out of `block` with none, because the dependency already ran one way: the block layer
     * never asked what a block commands. A new entry is a new dependency pointing the wrong way; fix
     * the dependency, do not pin the entry.
     */
    @Test
    fun `each core layer names only itself and the layers below it`() {
        val token = File(root, "token")
        val block = File(root, "block")
        val command = File(root, "command")
        assertTrue(sources(token).isNotEmpty()) { "no sources under ${token.absolutePath}" }
        assertTrue(sources(block).isNotEmpty()) { "no sources under ${block.absolutePath}" }
        assertTrue(sources(command).isNotEmpty()) { "no sources under ${command.absolutePath}" }

        val violations = violations(token, listOf(tokenPackage)) +
            violations(block, listOf(tokenPackage, blockPackage)) +
            violations(command, listOf(tokenPackage, blockPackage, commandPackage))

        assertEquals(emptyList<String>(), violations)
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
