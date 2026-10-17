package com.github.tvbox.osc.util

import com.github.tvbox.osc.util.subscription.ImportExpansionGuard
import com.github.tvbox.osc.util.subscription.ImportOnce
import com.github.tvbox.osc.util.subscription.ImportSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun lateLocalProbeCannotCommitAfterDirectOrJsonSelection() {
        val session = ImportSession()
        var selected = "original"
        val local = session.begin()
        val lateArchive = { if (session.isCurrent(local)) selected = "archive" }
        val lateProbe = { if (session.isCurrent(local)) selected = "probe" }
        val direct = session.begin()
        if (session.isCurrent(direct)) selected = "direct"
        lateArchive()
        assertEquals("direct", selected)
        val json = session.begin()
        if (session.isCurrent(json)) selected = "json"
        lateProbe()
        assertEquals("json", selected)
    }

    @Test fun oldCancelCannotInvalidateNewSessionAndDestroyRejectsPendingCommit() {
        val session = ImportSession()
        val first = session.begin()
        val oldCancel = { if (session.isCurrent(first)) session.invalidate() }
        val second = session.begin()
        oldCancel()
        assertTrue(session.isCurrent(second))
        session.invalidate()
        assertFalse(session.isCurrent(first))
        assertFalse(session.isCurrent(second))
    }

    @Test fun nestedExpansionKeepsTokenAndFinishIsOnce() {
        val session = ImportSession()
        val token = session.begin()
        val guard = ImportExpansionGuard()
        val once = ImportOnce()
        assertTrue(guard.enter("parent"))
        assertTrue(guard.enter("child"))
        var finishes = 0
        val finish = {
            if (session.isCurrent(token) && once.claim()) {
                guard.leave("child")
                finishes++
            }
        }
        finish()
        finish()
        assertEquals(1, finishes)
        assertTrue(session.isCurrent(token))
        assertTrue(guard.enter("child"))
        assertFalse(guard.enter("parent"))
    }

    @Test fun httpAliasesCannotHideSelfCycle() {
        val guard = ImportExpansionGuard()
        val first = ImportExpansionGuard.httpKey("https://EXAMPLE.org:443/a/../%7e/list.json#first")
        val same = ImportExpansionGuard.httpKey("https://example.org/~/list.json#second")
        assertEquals(first, same)
        assertTrue(guard.enter(first))
        assertFalse(guard.enter(same))
        assertEquals(ImportExpansionGuard.httpKey("http://example.org:80"),
            ImportExpansionGuard.httpKey("http://example.org/"))
        assertNotEquals(ImportExpansionGuard.httpKey("https://example.org/a%2fb"),
            ImportExpansionGuard.httpKey("https://example.org/a/b"))
        assertNotEquals(ImportExpansionGuard.httpKey("https://example.org/a?token=1"),
            ImportExpansionGuard.httpKey("https://example.org/a?token=2"))
        assertEquals(ImportExpansionGuard.httpKey("https://bücher.example/list"),
            ImportExpansionGuard.httpKey("https://xn--bcher-kva.example/list"))
    }

    @Test fun canonicalClanAliasesAndTwoFileCycleAreRejected() {
        val root = temporary.root
        File(root, "folder").mkdirs()
        val a = ImportExpansionGuard.key("clan://localhost/folder/a.json", root)
        val alias = ImportExpansionGuard.key("clan://localhost/folder/./a.json", root)
        val b = ImportExpansionGuard.key("clan://localhost/folder/b.json", root)
        assertEquals("clan:" + File(root, "folder/a.json").canonicalPath, a)
        assertEquals(a, alias)
        val guard = ImportExpansionGuard()
        assertTrue(guard.enter(a))
        assertFalse(guard.enter(alias))
        assertTrue(guard.enter(b))
        assertFalse(guard.enter(a))
    }

    @Test fun directedDiamondMayExpandSharedManifestOnBothBranches() {
        val guard = ImportExpansionGuard()
        val edges = mapOf("root" to listOf("left", "right"), "left" to listOf("shared"),
            "right" to listOf("shared"), "shared" to emptyList())
        val visited = mutableListOf<String>()
        fun visit(node: String) {
            assertTrue("Not a cycle: $node", guard.enter(node))
            visited.add(node)
            edges.getValue(node).forEach { visit(it) }
            guard.leave(node)
        }
        visit("root")
        assertEquals(listOf("root", "left", "shared", "right", "shared"), visited)
    }

    @Test fun bothDepthAndTotalExpansionAreBounded() {
        val depth = ImportExpansionGuard(maxDepth = 2, maxExpansions = 10)
        assertTrue(depth.enter("a"))
        assertTrue(depth.enter("b"))
        assertFalse(depth.enter("c"))
        depth.leave("b")
        assertTrue(depth.enter("c"))
        val total = ImportExpansionGuard(maxDepth = 10, maxExpansions = 2)
        assertTrue(total.enter("shared"))
        total.leave("shared")
        assertTrue(total.enter("shared"))
        total.leave("shared")
        assertFalse(total.enter("shared"))
    }
}
