package com.fileflip.app
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class RulesTest {
    private fun input(kind: String)=InputFile(File("test"),"test.$kind",kind,10)
    @Test fun selectedPagesAreValidatedAndDeduplicated() { assertEquals(listOf(0,2,3,4),Rules.pages("1,3-5,3",5)) }
    @Test(expected=IllegalArgumentException::class) fun outOfBoundsIsRejected() { Rules.pages("1-9",3) }
    @Test fun consentRoutingChangesForOCR() {
        val images=listOf(input("png"))
        assertTrue(Rules.local(images,Options(target="docx",imageMode="embed")))
        assertFalse(Rules.local(images,Options(target="docx",imageMode="ocr")))
        assertFalse(Rules.local(images,Options(target="xlsx")))
    }
    @Test fun mixedDocumentsHaveNoCompatibleOutput() { assertTrue(Rules.outputs(listOf(input("pdf"),input("docx"))).isEmpty()) }
    @Test fun noLegacyOutputIsOffered() { assertFalse(Rules.outputs(listOf(input("doc"))).contains("doc")); assertFalse(Rules.outputs(listOf(input("xls"))).contains("xls")) }
}
