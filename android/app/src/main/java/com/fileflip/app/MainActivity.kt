package com.fileflip.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONArray
import java.util.Locale

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: FlipViewModel=viewModel()
            val s by vm.ui.collectAsStateWithLifecycle()
            val dark=s.theme=="Dark" || (s.theme=="System" && isSystemInDarkTheme())
            val colors=if(dark) darkColorScheme(primary=Color(0xFFB4C5FF),secondary=Color(0xFF8DD9C8)) else lightColorScheme(primary=Color(0xFF315BCC),secondary=Color(0xFF006B5A))
            MaterialTheme(colorScheme=colors) { Surface(Modifier.fillMaxSize()) { FileFlip(vm,s) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileFlip(vm: FlipViewModel,s: UiState) {
    val context=androidx.compose.ui.platform.LocalContext.current
    var settings by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<ResultFile?>(null) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.select(it) }
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if(uri!=null) saving?.let { vm.save(it,uri) }; saving=null }
    fun openResult(r: ResultFile,share: Boolean) {
        try {
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",r.file)
            val intent=Intent(if(share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                if(share) { type=Rules.mime(r.file.extension); putExtra(Intent.EXTRA_STREAM,uri) } else setDataAndType(uri,Rules.mime(r.file.extension))
                clipData=android.content.ClipData.newRawUri("FileFlip result",uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(if(share) Intent.createChooser(intent,"Share converted file") else intent)
        } catch(_: Exception) { vm.error("No compatible viewer is available. Save the file and open it in an app that supports this format.") }
    }
    Scaffold(topBar={ TopAppBar(title={ Text("FileFlip",fontWeight=FontWeight.Bold) },actions={ IconButton(onClick={settings=true},enabled=!s.busy) { Icon(Icons.Default.Settings,"Settings") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Convert your files easily.",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
            Text("Documents, images, and spreadsheets. Local processing whenever possible.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                listOf("1  Select","2  Configure","3  Convert","4  Save").forEach { Text(it,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary) }
            }
            Button(onClick={picker.launch(arrayOf("application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/pdf","image/jpeg","image/png","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))},enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                Icon(Icons.Default.Add,"",Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text(if(s.files.isEmpty()) "Select files" else "Replace selected files")
            }
            if(s.files.isNotEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Selected files",style=MaterialTheme.typography.titleMedium)
                    s.files.forEachIndexed { index,f ->
                        Column {
                            Text(f.name,fontWeight=FontWeight.Medium)
                            Text("${f.kind.uppercase()} • ${size(f.size)}"+(if(f.kind in listOf("doc","xls")) " • legacy OLE; server validates" else ""),style=MaterialTheme.typography.bodySmall)
                            if(f.kind in listOf("jpg","png")) Row(verticalAlignment=Alignment.CenterVertically) {
                                IconButton(onClick={vm.move(index,-1)},enabled=!s.busy && index>0) { Icon(Icons.Default.ArrowUpward,"Move ${f.name} earlier") }
                                IconButton(onClick={vm.move(index,1)},enabled=!s.busy && index<s.files.lastIndex) { Icon(Icons.Default.ArrowDownward,"Move ${f.name} later") }
                                IconButton(onClick={vm.rotate(index)},enabled=!s.busy) { Icon(Icons.Default.RotateRight,"Rotate ${f.name} 90 degrees") }
                                Text("${f.rotation}°",style=MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                } }
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Convert to",style=MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Rules.outputs(s.files).forEach { target -> FilterChip(selected=s.options.target==target,onClick={vm.options(s.options.copy(target=target))},label={Text(target.uppercase())},enabled=!s.busy) }
                    }
                    val o=s.options
                    val images=s.files.all { it.kind in listOf("jpg","png") }
                    val excel=s.files.first().kind in listOf("xls","xlsx")
                    val local=Rules.local(s.files,o)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(if(local) Icons.Default.PhoneAndroid else Icons.Default.CloudUpload,null,tint=MaterialTheme.colorScheme.secondary)
                        Text(if(local) "On this device • Works offline" else "Internet required • Consent before upload",style=MaterialTheme.typography.labelLarge)
                    }
                    if(o.target in listOf("png","jpg") && !images || s.files.first().kind=="pdf" && o.target in listOf("docx","xlsx")) {
                        OutlinedTextField(o.pages,{vm.options(o.copy(pages=it))},label={Text("Pages (blank = all)")},placeholder={Text("1,3-5")},enabled=!s.busy,modifier=Modifier.fillMaxWidth(),singleLine=true)
                    }
                    if(images && o.target=="docx") {
                        Text("Word content")
                        ChoiceRow(listOf("embed","ocr"),o.imageMode,!s.busy,{if(it=="embed") "Embedded images" else "Editable OCR"}) { vm.options(o.copy(imageMode=it)) }
                    }
                    if((images && o.target in listOf("pdf","docx") && o.imageMode=="embed") || (images && o.target=="pdf") || excel) {
                        ChoiceRow(listOf("A4","Letter"),o.pageSize,!s.busy) { vm.options(o.copy(pageSize=it)) }
                        Row(verticalAlignment=Alignment.CenterVertically) { Switch(o.landscape,{vm.options(o.copy(landscape=it))},enabled=!s.busy); Spacer(Modifier.width(8.dp)); Text("Landscape pages") }
                        if(images) {
                            Text("Margins: ${o.margin} pt")
                            Slider(o.margin.toFloat(),{vm.options(o.copy(margin=it.toInt()))},valueRange=0f..72f,steps=5,enabled=!s.busy)
                        }
                    }
                    if(excel) {
                        OutlinedTextField(o.sheets,{vm.options(o.copy(sheets=it))},label={Text("Sheets (blank = all)")},placeholder={Text("Sheet1,Sheet2")},enabled=!s.busy,modifier=Modifier.fillMaxWidth(),singleLine=true)
                        OutlinedTextField(o.range,{vm.options(o.copy(range=it.uppercase()))},label={Text("Print/table range (optional)")},placeholder={Text("A1:F30")},enabled=!s.busy,modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Text("Blank range uses existing print areas. Word exports cached cell values; recalculate and save your workbook first.",style=MaterialTheme.typography.bodySmall)
                    }
                    if(o.target=="jpg") {
                        Text("JPG cannot preserve transparency. Choose the background color.",style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(o.background,{vm.options(o.copy(background=it))},label={Text("Background color")},placeholder={Text("#FFFFFF")},singleLine=true,enabled=!s.busy,modifier=Modifier.fillMaxWidth())
                    }
                    if(!local && (images || s.files.first().kind=="pdf") && o.target in listOf("docx","xlsx")) {
                        OutlinedTextField(o.language,{vm.options(o.copy(language=it))},label={Text("OCR languages")},supportingText={Text("eng, ara, or eng+ara; language packs must be installed on server")},enabled=!s.busy,modifier=Modifier.fillMaxWidth(),singleLine=true)
                    }
                    if(o.target=="xlsx") Text("Only usable tables become cells. Review the extracted table preview before saving. OCR, merged cells, and borderless tables may need correction.",style=MaterialTheme.typography.bodySmall)
                    if(s.files.first().kind=="pdf" && o.target=="docx") Text("Editable text with page breaks, plus OCR for scanned pages. Graphics and exact page layout are not reconstructed.",style=MaterialTheme.typography.bodySmall)
                    if(s.files.first().kind in listOf("doc","docx")) Text("Office rendering uses server fonts. Missing fonts and complex layouts may shift. Legacy DOC/XLS output is not supported.",style=MaterialTheme.typography.bodySmall)
                    if(o.target in listOf("png","jpg")) Text("Multiple exported images are packaged as a ZIP.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={if(local) vm.convert() else consent=true},enabled=!s.busy,modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.SwapHoriz,null); Spacer(Modifier.width(8.dp)); Text("Convert to ${o.target.uppercase()}") }
                } }
            }
            if(s.busy) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(s.progress)
                Text("Keep the app open. Progress shows the current processing stage.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick=vm::cancel) { Text("Cancel") }
            } }
            s.error?.let { message -> Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) { Column(Modifier.padding(16.dp)) { Text(message,color=MaterialTheme.colorScheme.onErrorContainer); TextButton(onClick={vm.error(null)}) {Text("Dismiss")} } } }
            if(!s.busy && s.progress=="Saved to your selected location.") Text(s.progress,color=MaterialTheme.colorScheme.primary)
            s.result?.let { r -> ResultCard(r,onPreview={openResult(r,false)},onSave={saving=r; saver.launch(r.file.name)},onShare={openResult(r,true)},onDelete={vm.delete(r)}) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("Recent conversions",style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={clear=true},enabled=!s.busy && s.history.isNotEmpty()) { Text("Clear") }
            }
            if(s.history.isEmpty()) Text("Your completed conversions will appear here.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            s.history.take(30).forEach { r -> OutlinedCard(onClick={vm.chooseResult(r)},modifier=Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) { Icon(Icons.Default.Description,null); Column {Text(r.file.name); Text(size(r.file.length()),style=MaterialTheme.typography.bodySmall)} } } }
            Text("Files stay private. Clearing history deletes local results, but not copies you saved or shared.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text("Upload for conversion?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("This conversion needs Office rendering, OCR, or table extraction that is not installed on your device.")
        Text("Upload ${s.files.size} file(s), ${size(s.files.sumOf { it.size })}, to:\n${s.endpoint.ifBlank { "No server configured" }}")
        Text("The server processes the files temporarily and deletes them after download/cancellation, or after about 15 minutes if disconnected. Only use a server you trust.")
    }},confirmButton={TextButton(onClick={consent=false; vm.convert()},enabled=s.endpoint.startsWith("https://") && s.token.length>=24) {Text("Upload and convert")}},dismissButton={TextButton(onClick={consent=false; settings=true}) {Text("Server settings")}})
    if(clear) AlertDialog(onDismissRequest={clear=false},title={Text("Clear conversion history?")},text={Text("This deletes all local converted results. Saved and shared copies remain unchanged.")},confirmButton={TextButton(onClick={clear=false; vm.clearHistory()}) {Text("Delete local results")}},dismissButton={TextButton(onClick={clear=false}) {Text("Cancel")}})
    if(settings) AlertDialog(onDismissRequest={settings=false},title={Text("Settings")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Appearance")
        ChoiceRow(listOf("System","Light","Dark"),s.theme,true,onSelect=vm::theme)
        Text("Document conversion server",style=MaterialTheme.typography.titleSmall)
        OutlinedTextField(s.endpoint,vm::endpoint,label={Text("HTTPS server URL")},placeholder={Text("https://convert.example.com")},singleLine=true)
        OutlinedTextField(s.token,vm::token,label={Text("Access token")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        Text("The token stays in memory for this session. No private key is bundled in the app. Local conversions need no server.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={settings=false}) {Text("Done")}})
}

@Composable
fun ChoiceRow(values: List<String>,selected: String,enabled: Boolean,label: (String)->String={it},onSelect: (String)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected==value,{onSelect(value)},label={Text(label(value))},enabled=enabled) }
    }
}
@Composable
fun ResultCard(r: ResultFile,onPreview: ()->Unit,onSave: ()->Unit,onShare: ()->Unit,onDelete: ()->Unit) {
    var reviewed by remember(r.file.path) { mutableStateOf(r.file.extension!="xlsx") }
    val tables=remember(r.preview) { try { JSONArray(r.preview) } catch(_:Exception) { JSONArray() } }
    ElevatedCard(Modifier.fillMaxWidth(),colors=CardDefaults.elevatedCardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Conversion complete",style=MaterialTheme.typography.titleLarge)
            Text("${r.file.name} • ${size(r.file.length())}")
            if(r.warnings.isNotBlank()) Text(r.warnings,style=MaterialTheme.typography.bodySmall)
            if(tables.length()>0) {
                Text("Table preview • first 15 rows / 10 columns per table",style=MaterialTheme.typography.labelLarge)
                Column(Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    for(i in 0 until tables.length()) {
                        val table=tables.getJSONObject(i); Text(table.getString("sheet"),fontWeight=FontWeight.Bold)
                        val rows=table.getJSONArray("rows")
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            for(j in 0 until rows.length()) {
                                val row=rows.getJSONArray(j)
                                Row { for(k in 0 until row.length()) Surface(Modifier.width(140.dp).padding(1.dp),color=MaterialTheme.colorScheme.surface) { Text(row.optString(k),Modifier.padding(8.dp),style=MaterialTheme.typography.bodySmall) } }
                            }
                        }
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(reviewed,{reviewed=it}); Text("I reviewed the extracted cells",style=MaterialTheme.typography.bodyMedium) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onPreview) { Text("Preview") }
                Button(onClick=onSave,enabled=reviewed) { Text("Save") }
                OutlinedButton(onClick=onShare,enabled=reviewed) { Text("Share") }
                IconButton(onClick=onDelete) { Icon(Icons.Default.Delete,"Delete local result") }
            }
        }
    }
}
fun size(bytes: Long): String = if(bytes<1024*1024) "${(bytes+1023)/1024} KB" else String.format(Locale.US,"%.1f MB",bytes/1048576.0)
