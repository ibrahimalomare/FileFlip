package com.fileflip.app

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RemoteConverter {
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private suspend fun request(r: Request): Response = suspendCancellableCoroutine { c ->
        val call=client.newCall(r); c.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) { if(c.isActive) c.resumeWithException(e) }
            override fun onResponse(call: Call,response: Response) {
                c.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
    suspend fun convert(files: List<InputFile>,o: Options,root: File,endpoint: String,token: String,progress: (String)->Unit): ResultFile {
        val base=endpoint.trim().trimEnd('/')
        val url=java.net.URI(base)
        require(url.scheme=="https" && !url.host.isNullOrBlank() && url.userInfo==null && url.query==null && url.fragment==null) { "Enter a valid HTTPS server address." }
        require(token.length>=24) { "Enter your server access token (at least 24 characters)." }
        fun req(path: String)=Request.Builder().url(base+path).header("Authorization","Bearer $token")
        suspend fun json(r: Request): JSONObject = request(r).use { response ->
            val text=response.body?.string().orEmpty()
            val obj=try { JSONObject(text) } catch(_: Exception) { error("Server returned an invalid response (${response.code}).") }
            check(response.isSuccessful) { obj.optString("message","Server error ${response.code}") }; obj
        }
        val payload=File(root,"upload.zip")
        val names=JSONArray()
        val options=JSONObject().put("target",o.target).put("pages",o.pages).put("pageSize",o.pageSize)
            .put("orientation",if(o.landscape) "landscape" else "portrait").put("sheets",o.sheets).put("range",o.range)
            .put("language",o.language).put("imageMode",o.imageMode).put("background",o.background)
            .put("rotations",JSONArray(files.map { it.rotation }))
        progress("Preparing upload")
        ZipOutputStream(payload.outputStream()).use { z ->
            files.forEachIndexed { i,f ->
                currentCoroutineContext().ensureActive()
                val name="input-$i.${f.kind}"; names.put(name); z.putNextEntry(ZipEntry(name))
                f.file.inputStream().use { input ->
                    val buffer=ByteArray(65536)
                    while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0) break; z.write(buffer,0,n) }
                }; z.closeEntry()
            }
            z.putNextEntry(ZipEntry("manifest.json")); z.write(JSONObject().put("files",names).put("options",options).toString().toByteArray()); z.closeEntry()
        }
        require(payload.length()<=Rules.MAX_BYTES) { "Upload exceeds 50 MB. Select fewer files." }
        var id: String?=null
        try {
            progress("Uploading securely")
            id=json(req("/jobs").post(payload.asRequestBody("application/zip".toMediaType())).build()).getString("id")
            require(id.matches(Regex("[a-f0-9]{32}"))) { "Invalid server job identifier." }
            payload.delete()
            var status: JSONObject
            val start=System.currentTimeMillis()
            while(true) {
                currentCoroutineContext().ensureActive()
                check(System.currentTimeMillis()-start<240_000) { "Server timed out. Try a smaller file." }
                status=json(req("/jobs/$id").get().build())
                when(status.getString("state")) {
                    "done" -> break
                    "failed" -> error(status.optString("message","Conversion failed."))
                    else -> progress(status.optString("message","Processing on server"))
                }
                delay(900)
            }
            val name=status.getString("file")
            require(name.matches(Regex("[a-zA-Z0-9-]+\\.(pdf|docx|xlsx|png|jpg|zip)"))) { "Invalid result name." }
            val output=File(root,name)
            progress("Downloading result")
            request(req("/jobs/$id/result").get().build()).use { response ->
                check(response.isSuccessful) { "Download failed. Please convert again." }
                val body=response.body ?: error("Empty result.")
                val expected=status.getLong("size"); require(expected in 1..100L*1024*1024) { "Result is too large." }
                body.byteStream().use { input -> output.outputStream().use { dest ->
                    val buffer=ByteArray(65536); var total=0L
                    while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0) break; total+=n; check(total<=expected) { "Invalid result size." }; dest.write(buffer,0,n) }
                    check(total==expected) { "Download interrupted. Convert again to retry." }
                } }
            }
            val warnings=status.optJSONArray("warnings") ?: JSONArray()
            val preview=status.optJSONArray("preview") ?: JSONArray()
            return ResultFile(output,(0 until warnings.length()).joinToString("\n") { warnings.getString(it) },preview.toString())
        } finally {
            payload.delete()
            if(id!=null) withContext(NonCancellable) {
                try { withTimeout(10_000) { request(req("/jobs/$id").delete().build()).close() } } catch(_: Exception) { /* TTL removes disconnected jobs. */ }
            }
        }
    }
}
