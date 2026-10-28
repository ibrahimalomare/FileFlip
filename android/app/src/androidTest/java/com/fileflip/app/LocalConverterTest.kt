package com.fileflip.app
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
@RunWith(AndroidJUnit4::class)
class LocalConverterTest {
    @Test fun encodesJpegAndBuildsActualPdfAndDocx() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.cacheDir,"test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source=File(root,"transparent.png")
            val bitmap=Bitmap.createBitmap(100,60,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.TRANSPARENT)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            val input=listOf(InputFile(source,"transparent.png","png",source.length()))
            val engine=LocalConverter()
            val jpg=engine.convert(input,Options(target="jpg",background="#00FF00"),root) {}
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(jpg.path,bounds)
            assertEquals("image/jpeg",bounds.outMimeType)
            val decoded=BitmapFactory.decodeFile(jpg.path); assertTrue(Color.green(decoded.getPixel(10,10))>245); decoded.recycle()
            val pdf=engine.convert(input+input,Options(target="pdf"),root) {}
            ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { assertEquals(2,it.pageCount) } }
            val doc=engine.convert(input,Options(target="docx"),root) {}
            ZipFile(doc).use { z -> assertNotNull(z.getEntry("word/document.xml")); assertNotNull(z.getEntry("word/media/image0.png")) }
            val rendered=engine.convert(listOf(InputFile(pdf,"test.pdf","pdf",pdf.length())),Options(target="png",pages="2"),root) {}
            assertNotNull(BitmapFactory.decodeFile(rendered.path))
        } finally { root.deleteRecursively() }
    }
}
