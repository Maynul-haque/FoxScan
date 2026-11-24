package com.rebelroot.foxscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.rebelroot.foxscan.engine.ImageOps
import com.rebelroot.foxscan.engine.Quad
import com.rebelroot.foxscan.engine.QuadDetector
import com.rebelroot.foxscan.engine.QuadStreamAnalyzer
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    companion object { const val TAG = "FoxScan" }
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: DocStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DocStore(filesDir)
        setContent { FoxScanApp(store) }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}

data class Page(
    val file: File,
    val bitmap: Bitmap? = null,
)

class DocStore(private val root: File) {
    val dir: File get() = File(root, "docs").apply { mkdirs() }
    fun newPageFile(): File = File(dir, "page_%d.jpg".format(System.currentTimeMillis()))
    fun pages(): List<File> =
        dir.listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
    fun delete(f: File) { f.delete() }
}

@Composable
fun FoxScanApp(store: DocStore) {
    var screen by remember { mutableStateOf("scan") }
    var pages by remember { mutableStateOf(listOf<Page>()) }
    var editingIndex by remember { mutableIntStateOf(-1) }

    fun reloadPages() {
        pages = store.pages().map { Page(it, BitmapFactory.decodeFile(it.absolutePath)) }
    }

    LaunchedEffect(Unit) { reloadPages() }

    Surface(color = Color(0xFF101418)) {
        when (screen) {
            "scan" -> ScanScreen(
                onCaptured = { file ->
                    reloadPages()
                    editingIndex = pages.indexOfFirst { it.file == file }
                    screen = if (editingIndex >= 0) "edit" else "pages"
                },
                onOpenPages = { reloadPages(); screen = "pages" },
                store = store,
            )
            "pages" -> PagesScreen(
                pages = pages,
                onEdit = { i -> editingIndex = i; screen = "edit" },
                onDelete = { f -> store.delete(f); reloadPages() },
                onExport = { screen = "export" },
                onScanMore = { screen = "scan" },
            )
            "edit" -> EditScreen(
                page = pages.getOrNull(editingIndex),
                onDone = { reloadPages(); screen = "pages" },
                onBack = { screen = "pages" },
            )
            "export" -> ExportScreen(
                pages = pages,
                onBack = { screen = "pages" },
            )
        }
    }
}

// --------------------------------------------------------------- scan screen

@Composable
fun ScanScreen(store: DocStore, onCaptured: (File) -> Unit, onOpenPages: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasPermission = it }

    var auto by remember { mutableStateOf(true) }
    var stateText by remember { mutableStateOf("") }
    var liveQuad by remember { mutableStateOf<Quad?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var flash by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val analyzer = remember { QuadStreamAnalyzer() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun doCapture() {
        val ic = imageCapture ?: return
        if (capturing) return
        capturing = true
        flash = true
        ic.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(result: ImageProxy) {
                    val bmp = resultToBitmap(result)
                    result.close()
                    val file = store.newPageFile()
                    ImageOps.saveJpeg(bmp, file)
                    flash = false
                    capturing = false
                    analyzer.reset()
                    onCaptured(file)
                }
                override fun onError(exception: ImageCaptureException) {
                    flash = false; capturing = false
                    Log.e(MainActivity.TAG, "capture failed", exception)
                }
            },
        )
    }

    LaunchedEffect(hasPermission) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { previewSize = it },
        ) {
            if (hasPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            try {
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                    .build()
                                val capture = ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                    .build()
                                imageCapture = capture
                                analysis.setAnalyzer(analysisExecutor) { img ->
                                    try {
                                        val rot = img.imageInfo.rotationDegrees
                                        val plane = img.planes[0]
                                        val luma = ByteArray(plane.buffer.remaining())
                                        plane.buffer.get(luma)
                                        val q = QuadDetector.detect(
                                            luma, plane.rowStride,
                                            img.width, img.height, rot,
                                        )
                                        img.close()
                                        val nowUs = System.nanoTime() / 1000
                                        val st = analyzer.addQuad(q, nowUs)
                                        val shown =
                                            if (st >= QuadStreamAnalyzer.State.ABOUT_TO_TRIGGER)
                                                analyzer.displayQuad else q
                                        mainHandler.post {
                                            liveQuad = shown
                                            stateText = when (st) {
                                                QuadStreamAnalyzer.State.SEARCHING ->
                                                    if (q == null) "ডকুমেন্ট খুঁজছি..." else "স্থির করুন..."
                                                QuadStreamAnalyzer.State.ABOUT_TO_TRIGGER ->
                                                    "প্রস্তুত, স্থির থাকুন..."
                                                QuadStreamAnalyzer.State.TRIGGER -> "এখন তুলছি!"
                                                QuadStreamAnalyzer.State.STABLE -> "ক্যাপচার হয়েছে"
                                            }
                                            if (st == QuadStreamAnalyzer.State.TRIGGER && auto) {
                                                doCapture()
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        try { img.close() } catch (_: Throwable) {}
                                        Log.e(MainActivity.TAG, "analysis failed", t)
                                    }
                                }
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview, analysis, capture,
                                )
                            } catch (t: Throwable) {
                                Log.e(MainActivity.TAG, "camera bind failed", t)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                )
                Canvas(Modifier.fillMaxSize()) {
                    val q = liveQuad
                    if (q != null && q.isValid && !q.isEmpty()) {
                        val col = when {
                            stateText.startsWith("এখন") -> Color(0xFF4CE15A)
                            stateText.startsWith("প্রস্তুত") -> Color(0xFFFFD54A)
                            else -> Color(0xFF64B5F6)
                        }
                        val pts = q.points().map {
                            Offset(it.first * size.width, it.second * size.height)
                        }
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(path, col.copy(alpha = 0.25f))
                        drawPath(path, col, style = Stroke(width = 4f))
                    }
                }
                if (flash) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)))
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stateText, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TextButton(onClick = onOpenPages) { Text("পেজ", color = Color.White) }
                        OutlinedButton(
                            onClick = { auto = !auto },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (auto) Color(0xFF4CE15A) else Color.White,
                            ),
                        ) { Text(if (auto) "অটো: চালু" else "অটো: বন্ধ") }
                        Button(
                            onClick = { doCapture() },
                            enabled = !capturing,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            modifier = Modifier.size(64.dp),
                        ) { Text("") }
                    }
                }
            } else {
                Text(
                    "ক্যামেরার অনুমতি দরকার",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** takePicture default format is JPEG. */
private fun resultToBitmap(result: ImageProxy): Bitmap {
    val buf = result.planes[0].buffer
    val bytes = ByteArray(buf.remaining())
    buf.get(bytes)
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rot = result.imageInfo.rotationDegrees
    return if (rot != 0) {
        val m = Matrix(); m.postRotate(rot.toFloat())
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    } else bmp
}

// -------------------------------------------------------------- pages screen

@Composable
fun PagesScreen(
    pages: List<Page>,
    onEdit: (Int) -> Unit,
    onDelete: (File) -> Unit,
    onExport: () -> Unit,
    onScanMore: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("পেজ (${pages.size})", color = Color.White)
            Row {
                TextButton(onClick = onScanMore) { Text("স্ক্যান", color = Color(0xFF64B5F6)) }
                Button(onClick = onExport, enabled = pages.isNotEmpty()) { Text("PDF করো") }
            }
        }
        if (pages.isEmpty()) {
            Text("কোনো পেজ নেই। স্ক্যান করে শুরু করো।", color = Color.Gray)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(pages) { i, page ->
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF1B2129), RoundedCornerShape(10.dp))
                            .padding(8.dp).clickable { onEdit(i) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        page.bitmap?.let {
                            Image(
                                it.asImageBitmap(), null,
                                Modifier.size(width = 64.dp, height = 88.dp),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("পেজ ${i + 1}", color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDelete(page.file) }) {
                            Text("মুছে ফেলো", color = Color(0xFFEF5350))
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- edit screen

@Composable
fun EditScreen(page: Page?, onDone: () -> Unit, onBack: () -> Unit) {
    if (page == null || page.bitmap == null) {
        Text("পেজ পাওয়া যায়নি", color = Color.White, modifier = Modifier.padding(24.dp))
        return
    }
    val bmp = page.bitmap!!
    var quad by remember(bmp) {
        mutableStateOf(ImageOps.detectQuadOnBitmap(bmp) ?: fullQuad())
    }
    var filter by remember { mutableStateOf(ImageOps.Filter.MAGIC) }
    var rotation by remember { mutableIntStateOf(0) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var dragCorner by remember { mutableStateOf(-1) }
    var saving by remember { mutableStateOf(false) }

    fun save() {
        if (saving) return
        saving = true
        val q = quad
        var out = ImageOps.warp(bmp, q)
        out = ImageOps.rotate(out, rotation)
        out = ImageOps.applyFilter(out, filter)
        ImageOps.saveJpeg(out, page.file)
        saving = false
        onDone()
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) { Text("← ব্যাক", color = Color.White) }
            Button(onClick = { save() }, enabled = !saving) { Text("সেভ") }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { boxSize = it },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bmp.asImageBitmap(), null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(
                Modifier
                    .matchParentSize()
                    .pointerInput(bmp) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                dragCorner = nearestCorner(pos, quad, boxSize, bmp)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                if (dragCorner >= 0 && boxSize != IntSize.Zero) {
                                    val fit = fitRect(bmp, boxSize)
                                    quad = moveCorner(
                                        quad, dragCorner,
                                        (change.position.x - fit.left) / fit.width / fitScaleX(fit, bmp),
                                        (change.position.y - fit.top) / fit.height / fitScaleY(fit, bmp),
                                    )
                                }
                            },
                            onDragEnd = { dragCorner = -1 },
                        )
                    },
            ) {
                if (boxSize != IntSize.Zero) {
                    val fit = fitRect(bmp, boxSize)
                    val pts = quad.points().map {
                        Offset(
                            fit.left + it.first * bmp.width * fitScaleX(fit, bmp),
                            fit.top + it.second * bmp.height * fitScaleY(fit, bmp),
                        )
                    }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    drawPath(path, Color(0xFF4CE15A).copy(alpha = 0.25f))
                    drawPath(path, Color(0xFF4CE15A), style = Stroke(width = 4f))
                    pts.forEach { drawCircle(Color.White, radius = 12f, center = it) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = filter == ImageOps.Filter.MAGIC,
                onClick = { filter = ImageOps.Filter.MAGIC },
                label = { Text("ম্যাজিক") },
            )
            FilterChip(
                selected = filter == ImageOps.Filter.GRAY,
                onClick = { filter = ImageOps.Filter.GRAY },
                label = { Text("গ্রে") },
            )
            FilterChip(
                selected = filter == ImageOps.Filter.BW,
                onClick = { filter = ImageOps.Filter.BW },
                label = { Text("B&W") },
            )
            FilterChip(
                selected = filter == ImageOps.Filter.ORIGINAL,
                onClick = { filter = ImageOps.Filter.ORIGINAL },
                label = { Text("র কালার") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { rotation += 90 }) { Text("↻ ঘোরাও") }
            TextButton(onClick = { quad = fullQuad() }) { Text("ফুল ফ্রেম") }
            TextButton(onClick = { quad = ImageOps.detectQuadOnBitmap(bmp) ?: quad }) {
                Text("অটো-কাট")
            }
        }
    }
}

private fun fullQuad() = Quad(0.03f, 0.03f, 0.97f, 0.03f, 0.97f, 0.97f, 0.03f, 0.97f)

private data class Fit(val left: Float, val top: Float, val width: Float, val height: Float)

private fun fitRect(bmp: Bitmap, box: IntSize): Fit {
    if (box == IntSize.Zero) return Fit(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
    val s = minOf(box.width / bmp.width.toFloat(), box.height / bmp.height.toFloat())
    val w = bmp.width * s; val h = bmp.height * s
    return Fit((box.width - w) / 2f, (box.height - h) / 2f, w, h)
}

private fun fitScaleX(fit: Fit, bmp: Bitmap) = fit.width / bmp.width
private fun fitScaleY(fit: Fit, bmp: Bitmap) = fit.height / bmp.height

private fun nearestCorner(pos: Offset, q: Quad, box: IntSize, bmp: Bitmap): Int {
    if (box == IntSize.Zero) return -1
    val fit = fitRect(bmp, box)
    val pts = q.points().mapIndexed { i, p ->
        Triple(
            i,
            fit.left + p.first * bmp.width * fitScaleX(fit, bmp),
            fit.top + p.second * bmp.height * fitScaleY(fit, bmp),
        )
    }
    return pts.minByOrNull { hypot(it.second - pos.x, it.third - pos.y) }?.first ?: -1
}

private fun moveCorner(q: Quad, which: Int, nx: Float, ny: Float): Quad {
    val x = nx.coerceIn(0f, 1f); val y = ny.coerceIn(0f, 1f)
    return when (which) {
        0 -> q.copy(tlX = x, tlY = y)
        1 -> q.copy(trX = x, trY = y)
        2 -> q.copy(brX = x, brY = y)
        else -> q.copy(blX = x, blY = y)
    }
}

private fun hypot(a: Float, b: Float) = kotlin.math.sqrt(a * a + b * b)

// ------------------------------------------------------------- export screen

@Composable
fun ExportScreen(pages: List<Page>, onBack: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var doneFile by remember { mutableStateOf<File?>(null) }

    fun export() {
        if (busy) return
        busy = true
        Thread {
            try {
                val bmps = pages.mapNotNull { p ->
                    var b = p.bitmap ?: BitmapFactory.decodeFile(p.file.absolutePath)
                    if (b != null) {
                        b = ImageOps.rotate(b, 0)
                    }
                    b
                }
                val out = File(context.filesDir, "docs/export_%d.pdf".format(System.currentTimeMillis()))
                ImageOps.exportPdf(bmps, out)
                mainPost {
                    busy = false; doneFile = out
                }
            } catch (t: Throwable) {
                Log.e("FoxScan", "pdf failed", t)
                mainPost { busy = false }
            }
        }.start()
    }

    fun share(f: File) {
        val uri = FileProvider.getUriForFile(context, "com.rebelroot.foxscan.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, "PDF শেয়ার"))
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← ব্যাক", color = Color.White) }
        }
        Text("PDF এক্সপোর্ট", color = Color.White)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { export() }, enabled = !busy && pages.isNotEmpty()) {
            Text(if (busy) "বানাচ্ছি..." else "${pages.size} পেজ থেকে PDF বানাও")
        }
        doneFile?.let { f ->
            Spacer(Modifier.height(12.dp))
            Text("রেডি: ${f.name}", color = Color(0xFF4CE15A))
            Button(onClick = { share(f) }) { Text("শেয়ার করো") }
        }
    }
}

private fun mainPost(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}
