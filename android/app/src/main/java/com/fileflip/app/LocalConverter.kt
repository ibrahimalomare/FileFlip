package com.fileflip.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

class LocalConverter {
    private fun bitmap(input: InputFile): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid or damaged image." }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "Image exceeds 16 megapixels. Resize it first." }
        val raw = BitmapFactory.decodeFile(input.file.path) ?: error("Cannot decode this image.")
        val exif = ExifInterface(input.file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when(exif) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        m.postRotate(input.rotation.toFloat())
        if(m.isIdentity) return raw
        return Bitmap.createBitmap(raw,0,0,raw.width,raw.height,m,true).also { if(it !== raw) raw.recycle() }
    }
    private fun writeBitmap(bitmap: Bitmap, target: String, output: File, background: String) {
        var encoded = bitmap
        if(target == "jpg") {
            encoded = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(encoded).apply { drawColor(Color.parseColor(background)); drawBitmap(bitmap,0f,0f,null) }
        }
        try {
            output.outputStream().use { require(encoded.compress(if(target == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,92,it)) { "Image encoding failed." } }
        } finally { if(encoded !== bitmap) encoded.recycle() }
    }
    private suspend fun bundle(files: List<File>, root: File): File {
        if(files.size == 1) return files.first()
        val zip=File(root,"FileFlip.zip")
        ZipOutputStream(zip.outputStream()).use { z -> files.forEach { f ->
            currentCoroutineContext().ensureActive(); z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
        } }
        files.forEach { it.delete() }; return zip
    }
    suspend fun convert(files: List<InputFile>, o: Options, root: File, progress: (String) -> Unit): File {
        require(o.target != "jpg" || o.background.matches(Regex("#[0-9a-fA-F]{6}"))) { "Use a background color such as #FFFFFF." }
        if(files.first().kind == "pdf") {
            val out=mutableListOf<File>()
            ParcelFileDescriptor.open(files.first().file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    Rules.pages(o.pages,renderer.pageCount).forEach { index ->
                        currentCoroutineContext().ensureActive(); progress("Rendering page ${index+1} of ${renderer.pageCount}")
                        renderer.openPage(index).use { page ->
                            val scale=min(2f,2400f/maxOf(page.width,page.height))
                            val b=Bitmap.createBitmap(maxOf(1,(page.width*scale).toInt()),maxOf(1,(page.height*scale).toInt()),Bitmap.Config.ARGB_8888)
                            try {
                                b.eraseColor(Color.WHITE); page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val f=File(root,"page-${index+1}.${o.target}"); writeBitmap(b,o.target,f,o.background); out.add(f)
                            } finally { b.recycle() }
                        }
                    }
                }
            }
            return bundle(out,root)
        }
        if(o.target == "pdf") {
            val out=File(root,"FileFlip.pdf")
            val pdf = PdfDocument()
            try {
                files.forEachIndexed { i,input ->
                    currentCoroutineContext().ensureActive(); progress("Adding image ${i+1} of ${files.size}")
                    val b=bitmap(input)
                    try {
                        var w=if(o.pageSize=="Letter") 612 else 595; var h=if(o.pageSize=="Letter") 792 else 842
                        if(o.landscape) { val swap=w; w=h; h=swap }
                        val page=pdf.startPage(PdfDocument.PageInfo.Builder(w,h,i+1).create())
                        page.canvas.drawColor(Color.WHITE)
                        val scale=min((w-2f*o.margin)/b.width,(h-2f*o.margin)/b.height)
                        val left=(w-b.width*scale)/2f; val top=(h-b.height*scale)/2f
                        page.canvas.drawBitmap(b,null,RectF(left,top,left+b.width*scale,top+b.height*scale),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        pdf.finishPage(page)
                    } finally { b.recycle() }
                }
                out.outputStream().use { pdf.writeTo(it) }
            } finally {
                pdf.close()
            }
            return out
        }
        if(o.target == "docx") return embeddedDocx(files,o,root,progress)
        val out=files.mapIndexed { i,input ->
            currentCoroutineContext().ensureActive(); progress("Encoding image ${i+1} of ${files.size}")
            val b=bitmap(input); val f=File(root,"image-${i+1}.${o.target}")
            try { writeBitmap(b,o.target,f,o.background) } finally { b.recycle() }; f
        }
        return bundle(out,root)
    }
    private suspend fun embeddedDocx(files: List<InputFile>, o: Options, root: File, progress: (String) -> Unit): File {
        val out=File(root,"FileFlip.docx")
        ZipOutputStream(out.outputStream()).use { z ->
            fun xml(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            xml("[Content_Types].xml","""<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            xml("_rels/.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            val body=StringBuilder(); val rels=StringBuilder()
            var pw=if(o.pageSize=="Letter") 12240 else 11906; var ph=if(o.pageSize=="Letter") 15840 else 16838
            if(o.landscape) { val swap=pw; pw=ph; ph=swap }
            val margin=o.margin*20
            files.forEachIndexed { i,input ->
                currentCoroutineContext().ensureActive(); progress("Embedding image ${i+1} of ${files.size}")
                val b=bitmap(input)
                try {
                    z.putNextEntry(ZipEntry("word/media/image$i.png")); check(b.compress(Bitmap.CompressFormat.PNG,100,z)); z.closeEntry()
                    val scale=min((pw-2*margin)*635.0/b.width,(ph-2*margin-400)*635.0/b.height)
                    val cx=(b.width*scale).toLong(); val cy=(b.height*scale).toLong()
                    if(i>0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                    body.append("""<w:p><w:r><w:drawing><wp:inline><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="${i+1}" name="Image ${i+1}"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="${i+1}" name="Image ${i+1}"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="img$i"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""")
                    rels.append("""<Relationship Id="img$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image$i.png"/>""")
                } finally { b.recycle() }
            }
            xml("word/_rels/document.xml.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>""")
            xml("word/document.xml","""<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>$body<w:sectPr><w:pgSz w:w="$pw" w:h="$ph"/><w:pgMar w:top="$margin" w:right="$margin" w:bottom="$margin" w:left="$margin"/></w:sectPr></w:body></w:document>""")
        }; return out
    }
}
