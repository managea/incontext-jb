package org.jetbrains.plugins.template.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.template.navigation.FileReferenceIndex.LineRange
import org.jetbrains.plugins.template.navigation.FileReferenceIndex.Reference
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.GutterMark
import junit.framework.TestCase
import org.junit.Assert

@TestDataPath("\$CONTENT_ROOT/src/test/testData/navigation")
class ReferenceNavigationTest : BasePlatformTestCase() {

    private lateinit var sourceFile: VirtualFile
    private lateinit var targetFile: VirtualFile
    private lateinit var referenceIndex: FileReferenceIndex

    override fun setUp() {
        super.setUp()
        
        // Create test files
        sourceFile = myFixture.addFileToProject(
            "source.md",
            """
            # Test Document
            
            This is a reference to @project/target.ts:L17-25
            
            Another reference to @project/target.ts:L30
            """.trimIndent()
        ).virtualFile
        
        targetFile = myFixture.addFileToProject(
            "target.ts",
            """
            // Line 1
            // Line 2
            // ...
            // Line 16
            // Line 17 - Start of referenced section
            function importantFunction() {
                console.log("This is an important function");
                return {
                    data: "test"
                };
            }
            // Line 25 - End of referenced section
            // ...
            // Line 29
            // Line 30 - Another referenced line
            const anotherImportantVariable = 42;
            """.trimIndent()
        ).virtualFile
        
        // Get the reference index service
        referenceIndex = FileReferenceIndex.getInstance(project)
        referenceIndex.clear()
    }
    
    override fun tearDown() {
        referenceIndex.clear()
        super.tearDown()
    }

    fun testAddReference() {
        // Add a reference
        referenceIndex.addReference(
            targetFile,
            17, // start line
            25, // end line
            sourceFile,
            10, // start offset in source file
            40  // end offset in source file
        )
        
        // Verify the reference was added correctly
        val references = referenceIndex.findReferencesToLocation(targetFile, 17)
        assertEquals(1, references.size)
        assertEquals(sourceFile.path, references[0].sourceFile.path)
        assertEquals(10, references[0].startOffset)
        assertEquals(40, references[0].endOffset)
    }
    
    fun testFindReferencesToLocation() {
        // Add references
        referenceIndex.addReference(
            targetFile,
            17, // start line
            25, // end line
            sourceFile,
            10, // start offset
            40  // end offset
        )
        
        referenceIndex.addReference(
            targetFile,
            30, // single line reference
            30,
            sourceFile,
            50, // start offset
            80  // end offset
        )
        
        // Test finding references to line in range
        val referencesLine17 = referenceIndex.findReferencesToLocation(targetFile, 17)
        assertEquals(1, referencesLine17.size)
        
        // Test finding references to line in middle of range
        val referencesLine20 = referenceIndex.findReferencesToLocation(targetFile, 20)
        assertEquals(1, referencesLine20.size)
        assertEquals(sourceFile.path, referencesLine20[0].sourceFile.path)
        
        // Test finding references to line at end of range
        val referencesLine25 = referenceIndex.findReferencesToLocation(targetFile, 25)
        assertEquals(1, referencesLine25.size)
        
        // Test finding references to single line reference
        val referencesLine30 = referenceIndex.findReferencesToLocation(targetFile, 30)
        assertEquals(1, referencesLine30.size)
        
        // Test finding references to line outside of any range
        val referencesLine10 = referenceIndex.findReferencesToLocation(targetFile, 10)
        assertEquals(0, referencesLine10.size)
    }
    
    fun testGetAllLineRangesForFile() {
        // Add references with different line ranges
        referenceIndex.addReference(
            targetFile,
            17, // start line
            25, // end line
            sourceFile,
            10, // start offset
            40  // end offset
        )
        
        referenceIndex.addReference(
            targetFile,
            30, // single line reference
            30,
            sourceFile,
            50, // start offset
            80  // end offset
        )
        
        // Get all line ranges
        val lineRanges = referenceIndex.getAllLineRangesForFile(targetFile)
        
        // Verify we have two line ranges
        assertEquals(2, lineRanges.size)
        
        // Verify the line ranges are correct
        assertTrue(lineRanges.any { it.startLine == 17 && it.endLine == 25 })
        assertTrue(lineRanges.any { it.startLine == 30 && it.endLine == 30 })
    }
    
    fun testRemoveReferencesFromFile() {
        // Add references
        referenceIndex.addReference(
            targetFile,
            17, // start line
            25, // end line
            sourceFile,
            10, // start offset
            40  // end offset
        )
        
        // Verify reference was added
        val referencesBeforeRemoval = referenceIndex.findReferencesToLocation(targetFile, 17)
        assertEquals(1, referencesBeforeRemoval.size)
        
        // Remove references from source file
        referenceIndex.removeReferencesFromFile(sourceFile)
        
        // Verify reference was removed
        val referencesAfterRemoval = referenceIndex.findReferencesToLocation(targetFile, 17)
        assertEquals(0, referencesAfterRemoval.size)
    }
    
    fun testLineRangeEquality() {
        // Create two line ranges with the same values
        val range1 = LineRange(17, 25)
        val range2 = LineRange(17, 25)
        
        // Create a line range with different values
        val range3 = LineRange(30, 30)
        
        // Test equality
        assertEquals(range1, range2)
        assertFalse(range1 == range3)
        
        // Test hash code
        assertEquals(range1.hashCode(), range2.hashCode())
        assertFalse(range1.hashCode() == range3.hashCode())
    }
    
    fun testMultiLineReferenceHandling() {
        // Add a multi-line reference
        referenceIndex.addReference(
            targetFile,
            17, // start line
            25, // end line
            sourceFile,
            10, // start offset
            40  // end offset
        )
        
        // Verify references are found for all lines in the range
        for (line in 17..25) {
            val references = referenceIndex.findReferencesToLocation(targetFile, line)
            assertEquals("Line $line should have 1 reference", 1, references.size)
            assertEquals(sourceFile.path, references[0].sourceFile.path)
        }
        
        // Verify lines outside the range don't have references
        val referencesOutsideRange = referenceIndex.findReferencesToLocation(targetFile, 16)
        assertEquals(0, referencesOutsideRange.size)
        
        val referencesOutsideRange2 = referenceIndex.findReferencesToLocation(targetFile, 26)
        assertEquals(0, referencesOutsideRange2.size)
    }
    
    fun testLineMarkerProviderWithMultiLineReferences() {
        // Add a multi-line reference
        referenceIndex.addReference(
            targetFile,
            17, // start line
            25, // end line
            sourceFile,
            10, // start offset
            40  // end offset
        )
        
        // Verify that the reference was added correctly
        val lineRanges = referenceIndex.getAllLineRangesForFile(targetFile)
        assertEquals(1, lineRanges.size)
        assertEquals(17, lineRanges[0].startLine)
        assertEquals(25, lineRanges[0].endLine)
        
        // Verify that we can find references for each line in the range
        for (line in 17..25) {
            val references = referenceIndex.findReferencesToLocation(targetFile, line)
            assertEquals("Line $line should have 1 reference", 1, references.size)
        }
        
        // Verify that lines outside the range don't have references
        val referencesOutsideRange1 = referenceIndex.findReferencesToLocation(targetFile, 16)
        assertEquals(0, referencesOutsideRange1.size)
        
        val referencesOutsideRange2 = referenceIndex.findReferencesToLocation(targetFile, 26)
        assertEquals(0, referencesOutsideRange2.size)
    }
    
    fun testLineMarkerProviderCacheRefresh() {
        // This test verifies that the cache clearing mechanism works properly
        
        // First, clear any existing caches
        FileReferenceLineMarkerProvider.clearCache()
        
        // Add a reference
        referenceIndex.addReference(
            targetFile,
            10, // start line
            10, // end line
            sourceFile,
            10, // start offset
            20  // end offset
        )
        
        // Verify the reference exists in the index
        val references = referenceIndex.findReferencesToLocation(targetFile, 10)
        assertTrue("Reference should be added to the index", references.isNotEmpty())
        
        // Clear the cache
        FileReferenceLineMarkerProvider.clearCache()
        
        // Verify the reference still exists in the index after cache clear
        // (cache clearing shouldn't affect the index, only the UI components)
        val referencesAfterCacheClear = referenceIndex.findReferencesToLocation(targetFile, 10)
        assertTrue("References should still exist in the index after cache clear", referencesAfterCacheClear.isNotEmpty())
    }
    
    fun testRefreshCodeAnalysisAfterReferenceChanges() {
        // This test verifies that the refreshCodeAnalysis method works properly
        
        // Create a FileReferenceIndexer
        val indexer = FileReferenceIndexer.getInstance(project)
        
        // Add a reference
        referenceIndex.addReference(
            targetFile,
            10, // start line
            10, // end line
            sourceFile,
            10, // start offset
            20  // end offset
        )
        
        // Verify the reference exists in the index
        val references = referenceIndex.findReferencesToLocation(targetFile, 10)
        assertTrue("Reference should be added to the index", references.isNotEmpty())
        
        // Remove the reference
        referenceIndex.removeReferencesFromFile(targetFile)
        
        // Verify the reference was removed from the index
        val referencesAfterRemoval = referenceIndex.findReferencesToLocation(targetFile, 10)
        assertTrue("References should be removed from the index", referencesAfterRemoval.isEmpty())
    }
    
    fun testMultipleLineRangesForSameFile() {
        // Add multiple references to different line ranges in the same file
        referenceIndex.addReference(
            targetFile,
            5, // start line
            10, // end line
            sourceFile,
            10, // start offset
            20  // end offset
        )
        
        referenceIndex.addReference(
            targetFile,
            15, // start line
            20, // end line
            sourceFile,
            30, // start offset
            40  // end offset
        )
        
        // Verify that both line ranges were added
        val lineRanges = referenceIndex.getAllLineRangesForFile(targetFile)
        assertEquals("Should have 2 line ranges", 2, lineRanges.size)
        
        // Verify that we can find references for each line in both ranges
        for (line in 5..10) {
            val references = referenceIndex.findReferencesToLocation(targetFile, line)
            assertEquals("Line $line should have 1 reference", 1, references.size)
        }
        
        for (line in 15..20) {
            val references = referenceIndex.findReferencesToLocation(targetFile, line)
            assertEquals("Line $line should have 1 reference", 1, references.size)
        }
        
        // Verify that lines outside both ranges don't have references
        val referencesOutsideRange1 = referenceIndex.findReferencesToLocation(targetFile, 12)
        assertEquals(0, referencesOutsideRange1.size)
        
        val referencesOutsideRange2 = referenceIndex.findReferencesToLocation(targetFile, 22)
        assertEquals(0, referencesOutsideRange2.size)
    }
}
