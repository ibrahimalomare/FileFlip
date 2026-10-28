package com.fileflip.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

data class UiState(
    val files: List<InputFile> = emptyList(), val options: Options = Options(), val busy: Boolean = false,
    val progress: String = "", val error: String? = null, val result: ResultFile? = null,
    val history: List<ResultFile> = emptyList(), val endpoint: String = "", val token: String = "", val theme: String = "System"
)
class FlipViewModel(app: Application): AndroidViewModel(app) {
    private val context=getApplication<Application>()
    private val prefs=context.getSharedPreferences("settings",0)
    private val results=File(context.filesDir,"results").apply { mkdirs() }
    private val imports=File(context.cacheDir,"imports").apply { mkdirs() }
    private val state=MutableStateFlow(UiState(busy=true,progress="Loading local history",endpoint=prefs.getString("endpoint","") ?: "",theme=prefs.getString("theme","System") ?: "System"))
    val ui=state.asStateFlow()
    private var work: Job?=null
    init {
        viewModelScope.launch(Dispatchers.IO) {
            imports.listFiles()?.forEach { it.deleteRecursively() }
            File(context.cacheDir,"work").deleteRecursively()
            val records=try { JSONArray(prefs.getString("history","[]")) } catch(_:Exception) { JSONArray() }
            val recent=(0 until records.length()).mapNotNull { i ->
                val r=records.optJSONObject(i) ?: return@mapNotNull null
                val name=r.optString("file")
                if(name!=File(name).name) return@mapNotNull null
                val f=File(results,name)
                if(f.exists()) ResultFile(f,r.optString("warnings"),r.optString("preview")) else null
            }
            state.update { it.copy(history=recent,busy=false) }
        }
    }
    fun options(o: Options) { state.update { it.copy(options=o,error=null) } }
    fun endpoint(value: String) { state.update { it.copy(endpoint=value) }; prefs.edit().putString("endpoint",value).apply() }
    fun token(value: String) { state.update { it.copy(token=value) } } // session-only: never packaged or persisted
    fun theme(value: String) { state.update { it.copy(theme=value) }; prefs.edit().putString("theme",value).apply() }
    fun error(message: String?) { state.update { it.copy(error=message) } }
    fun rotate(index: Int) { state.update { s -> s.copy(files=s.files.mapIndexed { i,f -> if(i==index) f.copy(rotation=(f.rotation+90)%360) else f }) } }
    fun move(index: Int, delta: Int) { state.update { s ->
        val list=s.files.toMutableList(); val next=index+delta
        if(next in list.indices) { val f=list.removeAt(index); list.add(next,f) }; s.copy(files=list)
    } }
    private fun detect(file: File,name: String): String {
        val head=ByteArray(8).also { bytes -> file.inputStream().use { it.read(bytes) } }
        if(head.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) return "pdf"
        if(head.contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a))) return "png"
        if(head.size>=3 && head[0]==0xff.toByte() && head[1]==0xd8.toByte() && head[2]==0xff.toByte()) return "jpg"
        if(head.size>=2 && head[0]==0x50.toByte() && head[1]==0x4b.toByte()) {
            ZipFile(file).use { z ->
                var total=0L; var count=0
                val entries=z.entries()
                while(entries.hasMoreElements()) { val e=entries.nextElement(); total+=maxOf(0,e.size); count++; require(total<150L*1024*1024 && count<20000) { "Expanded document is too large." } }
                if(z.getEntry("word/document.xml")!=null) return "docx"
                if(z.getEntry("xl/workbook.xml")!=null) return "xlsx"
            }
        }
        if(head.contentEquals(byteArrayOf(0xd0.toByte(),0xcf.toByte(),0x11,0xe0.toByte(),0xa1.toByte(),0xb1.toByte(),0x1a,0xe1.toByte()))) {
            val ext=name.substringAfterLast('.',"").lowercase()
            require(ext in listOf("doc","xls")) { "Encrypted Office or unsupported OLE file. Unlock it first." }
            return ext // OLE container identified; server verifies the actual internal stream before converting.
        }
        throw IllegalArgumentException("Unsupported or invalid file: $name")
    }
    fun select(uris: List<Uri>) {
        if(state.value.busy || uris.isEmpty()) return
        work=viewModelScope.launch {
            state.update { it.copy(busy=true,progress="Reading selected files",error=null) }
            val imported=mutableListOf<InputFile>()
            try {
                withContext(Dispatchers.IO) {
                    require(uris.size<=20) { "Select at most 20 files." }
                    var total=0L
                    uris.forEachIndexed { i,uri ->
                        ensureActive()
                        var name="File ${i+1}"
                        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) name=c.getString(0) ?: name }
                        val f=File(imports,UUID.randomUUID().toString())
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { out ->
                                val buffer=ByteArray(65536)
                                while(true) { ensureActive(); val n=input.read(buffer); if(n<0) break; total+=n
                                    require(total<=Rules.MAX_BYTES) { "Selection exceeds 50 MB. Select fewer or smaller files." }
                                    check(imports.usableSpace>8L*1024*1024) { "Insufficient storage. Free space and retry." }; out.write(buffer,0,n)
                                }
                            } } ?: throw IllegalStateException("Cannot open the selected file.")
                            require(f.length()>0) { "The selected file is empty." }
                            imported.add(InputFile(f,name,detect(f,name),f.length()))
                        } catch(e: Exception) { f.delete(); throw e }
                    }
                    val outputs=Rules.outputs(imported)
                    require(outputs.isNotEmpty()) { "Select a single Office/PDF document, or select images together." }
                    state.value.files.forEach { it.file.delete() }
                    state.update { it.copy(files=imported.toList(),options=Options(target=outputs.first()),result=null) }
                }
            } catch(e: CancellationException) { imported.forEach { it.file.delete() }; error("Selection cancelled.") } catch(e: Exception) { imported.forEach { it.file.delete() }; error(friendly(e)) } finally { state.update { it.copy(busy=false) } }
        }
    }
    fun convert() {
        val snapshot=state.value
        if(snapshot.busy || snapshot.files.isEmpty()) return
        work=viewModelScope.launch {
            state.update { it.copy(busy=true,error=null,result=null,progress="Preparing conversion") }
            val root=File(context.cacheDir,"work/${UUID.randomUUID()}")
            try {
                val result=withContext(Dispatchers.IO) {
                    root.mkdirs(); check(root.usableSpace>maxOf(100L*1024*1024,snapshot.files.sumOf { it.size }*3)) { "Insufficient storage. Free at least 150 MB and retry." }
                    val update: (String)->Unit = { message -> state.update { it.copy(progress=message) } }
                    val converted=if(Rules.local(snapshot.files,snapshot.options)) {
                        ResultFile(LocalConverter().convert(snapshot.files,snapshot.options,root,update),if(snapshot.options.target=="docx") "Images are embedded; their text is not editable." else "")
                    } else RemoteConverter().convert(snapshot.files,snapshot.options,root,snapshot.endpoint,snapshot.token,update)
                    ensureActive()
                    val dest=File(results,"FileFlip-${System.currentTimeMillis()}.${converted.file.extension}")
                    try { converted.file.copyTo(dest); ensureActive() } catch(e: Exception) { dest.delete(); throw e }
                    converted.copy(file=dest)
                }
                state.update { it.copy(result=result,history=(listOf(result)+it.history)) }; persistHistory()
            } catch(e: CancellationException) { error("Conversion cancelled. Partial local output was removed. Disconnected server jobs expire automatically.") } catch(e: OutOfMemoryError) { error("Not enough memory. Use smaller images or fewer pages.") } catch(e: Exception) { error(friendly(e)) } finally { withContext(Dispatchers.IO+NonCancellable) { root.deleteRecursively() }; state.update { it.copy(busy=false) } }
        }
    }
    fun cancel() { work?.cancel() }
    fun chooseResult(r: ResultFile) { state.update { it.copy(result=r) } }
    private fun persistHistory() {
        val a=JSONArray(); state.value.history.forEach { a.put(JSONObject().put("file",it.file.name).put("warnings",it.warnings).put("preview",it.preview)) }
        prefs.edit().putString("history",a.toString()).apply()
    }
    fun delete(r: ResultFile) {
        viewModelScope.launch(Dispatchers.IO) {
            if(r.file.exists() && !r.file.delete()) { error("Could not delete the local result."); return@launch }
            state.update { s -> s.copy(history=s.history.filterNot { it.file==r.file },result=if(s.result?.file==r.file) null else s.result) }; persistHistory()
        }
    }
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val failed=state.value.history.filter { it.file.exists() && !it.file.delete() }
            state.update { it.copy(history=failed,result=null,error=if(failed.isEmpty()) null else "Some local results could not be deleted.") }; persistHistory()
        }
    }
    fun save(r: ResultFile,uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri,"w")?.use { out -> r.file.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("Destination is not writable.")
                state.update { it.copy(progress="Saved to your selected location.",error=null) }
            } catch(e: Exception) { error("Save failed. Check destination permissions and storage. ${friendly(e)}") }
        }
    }
    private fun friendly(e: Exception): String = when(e) {
        is java.net.UnknownHostException -> "Cannot reach the server. Check the address and internet connection."
        is java.net.SocketTimeoutException -> "Network timed out. Check your connection and retry."
        is javax.net.ssl.SSLException -> "Secure connection failed. The server needs a valid trusted HTTPS certificate."
        is SecurityException -> "This file is inaccessible or password-protected. Unlock it or select it again."
        is java.io.IOException -> "File or network operation failed. Check available storage, file access and connectivity."
        else -> e.message ?: "Conversion failed. Verify that the file opens normally."
    }
}
