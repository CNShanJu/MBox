package com.github.tvbox.osc.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ImportFileStagingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun partialCopyFailureDeletesOnlyItsTemporaryFile() {
        val other = temporary.newFile("other-request.zip").apply { writeText("keep") }
        var partial: File? = null
        val failure = IOException("provider disconnected")
        val result = runCatching {
            ImportFileStaging.copyToTemp(temporary.root) {
                partial = it
                it.writeText("partial")
                throw failure
            }
        }
        assertSame(failure, result.exceptionOrNull())
        assertFalse(partial!!.exists())
        assertEquals("keep", other.readText())
    }

    @Test fun copyOpenFailureAlsoCleansAndSuccessfulCopyRemainsForCaller() {
        val before = temporary.root.listFiles()!!.toSet()
        assertTrue(runCatching {
            ImportFileStaging.copyToTemp(temporary.root) { throw IOException("cannot open") }
        }.isFailure)
        assertEquals(before, temporary.root.listFiles()!!.toSet())
        val success = ImportFileStaging.copyToTemp(temporary.root) { it.writeText("complete") }
        assertEquals("complete", success.readText())
    }

    @Test fun samePackageStagingIsIsolatedAndCancellationCannotRemovePublishedPackage() {
        val target = File(temporary.root, "same_seed")
        lateinit var firstStage: File
        lateinit var secondStage: File
        ImportFileStaging.withDirectory(temporary.root, target.name) { first ->
            firstStage = first
            File(first, "config.json").writeText("first")
            ImportFileStaging.withDirectory(temporary.root, target.name) { second ->
                secondStage = second
                assertNotEquals(first, second)
                assertEquals("first", File(first, "config.json").readText())
                File(second, "config.json").writeText("second")
                val published = ImportFileStaging.publishValidated(second, target, { false },
                    { File(it, "config.json").readText() }, { it.isNotEmpty() })
                assertEquals("second", published)
                assertFalse(second.exists())
            }
            assertTrue(runCatching {
                ImportFileStaging.publishValidated(first, target, { true },
                    { File(it, "config.json").readText() }, { true })
            }.isFailure)
        }
        assertFalse(firstStage.exists())
        assertFalse(secondStage.exists())
        assertEquals("second", File(target, "config.json").readText())
    }

    @Test fun secondPublishReusesExistingPackageWithoutOverwriting() {
        val target = temporary.newFolder("seed")
        File(target, "config.json").writeText("original")
        ImportFileStaging.withDirectory(temporary.root, target.name) { staging ->
            File(staging, "config.json").writeText("new content")
            val result = ImportFileStaging.publishValidated(staging, target, { false },
                { File(it, "config.json").readText() }, { true })
            assertEquals("original", result)
        }
        assertEquals("original", File(target, "config.json").readText())
        assertEquals(listOf(target), temporary.root.listFiles()!!.toList())
    }

    @Test fun scanRewritesOnlyFinalPathNeverStagingPath() {
        val target = File(temporary.root, "seed")
        ImportFileStaging.withDirectory(temporary.root, target.name) { staging ->
            File(staging, "config.json").writeText("unrewritten")
            val result = ImportFileStaging.publishValidated(staging, target, { false }, { directory ->
                assertEquals(target, directory)
                assertFalse(staging.exists())
                File(directory, "config.json").writeText(directory.canonicalPath)
                directory
            }, { it.isDirectory })
            assertEquals(target, result)
            assertEquals(target.canonicalPath, File(target, "config.json").readText())
        }
        assertTrue(target.exists())
    }

    @Test fun invalidScanOrExceptionRollsBackNewTargetAndStaging() {
        for (throwOnScan in listOf(false, true)) {
            val target = File(temporary.root, "seed_$throwOnScan")
            lateinit var stage: File
            val result = runCatching {
                ImportFileStaging.withDirectory(temporary.root, target.name) { staging ->
                    stage = staging
                    File(staging, "bad.txt").writeText("not a config")
                    ImportFileStaging.publishValidated(staging, target, { false }, {
                        if (throwOnScan) throw IOException("scan failed")
                        false
                    }, { it })
                }
            }
            assertTrue(result.isFailure)
            assertFalse(stage.exists())
            assertFalse(target.exists())
        }
    }

    @Test fun cancellationDuringValidationRollsBackOnlyNewPublication() {
        val target = File(temporary.root, "seed")
        var cancelled = false
        assertTrue(runCatching {
            ImportFileStaging.withDirectory(temporary.root, target.name) { staging ->
                File(staging, "config.json").writeText("valid")
                ImportFileStaging.publishValidated(staging, target, { cancelled }, {
                    cancelled = true
                    true
                }, { it })
            }
        }.isFailure)
        assertFalse(target.exists())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationDuringReuseNeverDeletesExistingPackage() {
        val target = temporary.newFolder("seed")
        File(target, "config.json").writeText("referenced")
        var cancelled = false
        assertTrue(runCatching {
            ImportFileStaging.withDirectory(temporary.root, target.name) { staging ->
                ImportFileStaging.publishValidated(staging, target, { cancelled }, {
                    cancelled = true
                    true
                }, { it })
            }
        }.isFailure)
        assertEquals("referenced", File(target, "config.json").readText())
    }

    @Test fun renameFailureDoesNotReturnStagingAsPermanentDirectory() {
        val target = File(temporary.root, "missing-parent/seed")
        var scanned = false
        lateinit var stage: File
        val result = runCatching {
            ImportFileStaging.withDirectory(temporary.root, "seed") { staging ->
                stage = staging
                ImportFileStaging.publishValidated(staging, target, { false }, {
                    scanned = true
                    it
                }, { true })
            }
        }
        assertTrue(result.isFailure)
        assertFalse(scanned)
        assertFalse(stage.exists())
        assertFalse(target.exists())
    }
}
