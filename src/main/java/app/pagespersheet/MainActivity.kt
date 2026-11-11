package app.pagespersheet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

private const val MARGIN = 14f
private const val GAP = 8f
private val GRIDS: Map<Int, Pair<Int, Int>> = mapOf(
    1 to (1 to 1), 2 to (2 to 1), 4 to (2 to 2), 6 to (3 to 2), 9 to (3 to 3)
)

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var borderBox: CheckBox
    private lateinit var trimBox: CheckBox
    private val printButtons = mutableListOf<Button>()
    private var pdfFile: File? = null
    private val prefs by lazy { getSharedPreferences("options", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIntent(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 1 && resultCode == RESULT_OK && uri != null) loadFromUri(uri)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(28), dp(20), dp(24))

        val title = TextView(this)
        title.text = "Pages per sheet"
        title.textSize = 26f
        title.typeface = Typeface.DEFAULT_BOLD
        root.addView(title)

        status = TextView(this)
        status.text = "Share a label PDF from Shiprocket to this app, or choose a PDF below."
        status.textSize = 16f
        status.setPadding(0, dp(8), 0, dp(20))
        root.addView(status)

        var row: LinearLayout? = null
        listOf(1, 2, 4, 6, 9).forEachIndexed { idx, n ->
            if (idx % 2 == 0) {
                row = LinearLayout(this)
                row!!.orientation = LinearLayout.HORIZONTAL
                root.addView(row)
            }
            val b = Button(this)
            b.text = if (n == 1) "1 per sheet" else "$n per sheet"
            b.textSize = 18f
            b.isAllCaps = false
            b.isEnabled = false
            b.setOnClickListener { startPrint(n) }
            val lp = LinearLayout.LayoutParams(0, dp(72), 1f)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            row!!.addView(b, lp)
            printButtons.add(b)
        }

        borderBox = CheckBox(this)
        borderBox.text = "Draw a border around each page"
        borderBox.isChecked = prefs.getBoolean("border", true)
        borderBox.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("border", c).apply() }
        borderBox.setPadding(dp(4), dp(12), dp(4), dp(12))
        root.addView(borderBox)

        trimBox = CheckBox(this)
        trimBox.text = "Cut away empty space around each page"
        trimBox.isChecked = prefs.getBoolean("trim", true)
        trimBox.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("trim", c).apply() }
        trimBox.setPadding(dp(4), dp(4), dp(4), dp(12))
        root.addView(trimBox)

        val pick = Button(this)
        pick.text = "Choose a PDF instead"
        pick.isAllCaps = false
        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/pdf"
            startActivityForResult(i, 1)
        }
        root.addView(pick)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun setButtons(enabled: Boolean) {
        printButtons.forEach { it.isEnabled = enabled }
    }

    private fun busy(msg: String) {
        setButtons(false)
        status.setTextColor(Color.BLACK)
        status.text = msg
    }

    private fun fail(msg: String) {
        setButtons(false)
        status.setTextColor(0xFFB3261E.toInt())
        status.text = msg
    }

    private fun ready(count: Int) {
        status.setTextColor(Color.BLACK)
        status.text = "$count page" + (if (count == 1) "" else "s") + " ready. Tap how many pages per sheet to print."
        setButtons(true)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val uri = streamUri(i)
                val text = i.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null) {
                    loadFromUri(uri)
                } else if (text != null) {
                    val m = Regex("https?://\\S+").find(text)
                    if (m != null) loadFromUrl(m.value)
                    else fail("Nothing to print was shared. Share a PDF or a link to a PDF.")
                }
            }
            Intent.ACTION_VIEW -> {
                val d = i.data
                if (d != null) loadFromUri(d)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    private fun loadFromUri(uri: Uri) {
        busy("Opening file…")
        Thread {
            try {
                val f = File(cacheDir, "input.pdf")
                val input = contentResolver.openInputStream(uri)
                    ?: throw Exception("Could not open the shared file.")
                input.use { src -> FileOutputStream(f).use { out -> src.copyTo(out) } }
                finishLoad(f)
            } catch (e: Exception) {
                runOnUiThread { fail(e.message ?: "Could not open the file.") }
            }
        }.start()
    }

    private fun loadFromUrl(link: String) {
        busy("Downloading label…")
        Thread {
            try {
                val conn = URL(link).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode !in 200..299) {
                    throw Exception("The link could not be downloaded (code ${conn.responseCode}).")
                }
                val f = File(cacheDir, "input.pdf")
                conn.inputStream.use { src -> FileOutputStream(f).use { out -> src.copyTo(out) } }
                conn.disconnect()
                finishLoad(f)
            } catch (e: Exception) {
                runOnUiThread { fail(e.message ?: "Could not download the link.") }
            }
        }.start()
    }

    private fun finishLoad(f: File) {
        val head = ByteArray(1024)
        val n = FileInputStream(f).use { it.read(head) }
        if (n < 5 || !String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF")) {
            throw Exception("That is not a PDF file.")
        }
        val count = try {
            pdfPageCount(f)
        } catch (e: SecurityException) {
            throw Exception("This PDF is password protected.")
        }
        if (count < 1) throw Exception("This PDF has no pages.")
        runOnUiThread {
            pdfFile = f
            ready(count)
        }
    }

    private fun startPrint(n: Int) {
        val f = pdfFile ?: return
        val landscape = (n == 2 || n == 6)
        val media = if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape()
        else PrintAttributes.MediaSize.ISO_A4
        val attrs = PrintAttributes.Builder()
            .setMediaSize(media)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(
            "Labels",
            NUpAdapter(applicationContext, f, n, borderBox.isChecked, trimBox.isChecked),
            attrs
        )
    }
}

private fun pdfPageCount(f: File): Int {
    val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
        val r = PdfRenderer(pfd)
        try {
            return r.pageCount
        } finally {
            r.close()
        }
    } finally {
        pfd.close()
    }
}

private class Rendered(val bmp: Bitmap, val crop: Rect, val pxPerPt: Float)

private class NUpAdapter(
    private val ctx: Context,
    private val file: File,
    private val n: Int,
    private val border: Boolean,
    private val trim: Boolean
) : PrintDocumentAdapter() {

    private var attrs: PrintAttributes? = null
    private var total = 0
    private var sheets = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.6f
        color = Color.rgb(110, 110, 110)
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        attrs = newAttributes
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        try {
            total = pdfPageCount(file)
        } catch (e: Exception) {
            callback.onLayoutFailed("Could not read the PDF")
            return
        }
        sheets = (total + n - 1) / n
        val info = PrintDocumentInfo.Builder("labels.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(sheets)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        val cancelled = AtomicBoolean(false)
        cancellationSignal.setOnCancelListener { cancelled.set(true) }
        Thread {
            var doc: PrintedPdfDocument? = null
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                val a = attrs ?: throw IllegalStateException("No print settings")
                val d = PrintedPdfDocument(ctx, a)
                doc = d
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = fd
                val rr = PdfRenderer(fd)
                renderer = rr

                val first = renderPage(rr, 0)
                val refW = first.crop.width() / first.pxPerPt
                val refH = first.crop.height() / first.pxPerPt
                first.bmp.recycle()

                var wasCancelled = false
                for (sheet in 0 until sheets) {
                    if (cancelled.get()) {
                        wasCancelled = true
                        break
                    }
                    if (!inRanges(pages, sheet)) continue
                    val page = d.startPage(sheet)
                    drawSheet(page.canvas, page.info.contentRect, rr, sheet, refW, refH)
                    d.finishPage(page)
                }
                if (wasCancelled) {
                    callback.onWriteCancelled()
                } else {
                    FileOutputStream(destination.fileDescriptor).use { d.writeTo(it) }
                    callback.onWriteFinished(pages)
                }
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            } finally {
                try { renderer?.close() } catch (e: Exception) { }
                try { pfd?.close() } catch (e: Exception) { }
                try { doc?.close() } catch (e: Exception) { }
                try { destination.close() } catch (e: Exception) { }
            }
        }.start()
    }

    private fun inRanges(ranges: Array<PageRange>, sheet: Int): Boolean {
        for (r in ranges) {
            if (sheet >= r.start && sheet <= r.end) return true
        }
        return false
    }

    private fun renderPage(r: PdfRenderer, index: Int): Rendered {
        val p = r.openPage(index)
        try {
            val wPt = p.width.toFloat()
            val hPt = p.height.toFloat()
            var s = 200f / 72f
            if (max(wPt, hPt) * s > 3000f) s = 3000f / max(wPt, hPt)
            val w = max(1, (wPt * s).toInt())
            val h = max(1, (hPt * s).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val crop = if (trim) findBounds(bmp) else Rect(0, 0, w, h)
            return Rendered(bmp, crop, s)
        } finally {
            p.close()
        }
    }

    private fun findBounds(bmp: Bitmap): Rect {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var rowHas = false
            for (x in 0 until w) {
                val c = row[x]
                if (((c shr 16) and 255) < 235 || ((c shr 8) and 255) < 235 || (c and 255) < 235) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    rowHas = true
                }
            }
            if (rowHas) {
                if (y < minY) minY = y
                maxY = y
            }
        }
        if (maxX < 0) return Rect(0, 0, w, h)
        val pad = 6
        return Rect(max(0, minX - pad), max(0, minY - pad), min(w, maxX + 1 + pad), min(h, maxY + 1 + pad))
    }

    private fun drawSheet(
        canvas: Canvas, rect: Rect, r: PdfRenderer, sheet: Int, refW: Float, refH: Float
    ) {
        val availW = rect.width() - 2 * MARGIN
        val availH = rect.height() - 2 * MARGIN
        val g = GRIDS.getValue(n)
        var cols = g.first
        var rows = g.second
        var bestScale = -1f
        val options = if (n == 1) listOf(g) else listOf(g, Pair(g.second, g.first))
        for (opt in options) {
            val cw = (availW - (opt.first - 1) * GAP) / opt.first
            val ch = (availH - (opt.second - 1) * GAP) / opt.second
            val s = min(cw / refW, ch / refH)
            if (s > bestScale + 0.0001f) {
                bestScale = s
                cols = opt.first
                rows = opt.second
            }
        }
        val cellW = (availW - (cols - 1) * GAP) / cols
        val cellH = (availH - (rows - 1) * GAP) / rows

        for (i in 0 until n) {
            val idx = sheet * n + i
            if (idx >= total) break
            val col = i % cols
            val row = i / cols
            val rp = renderPage(r, idx)
            val wPt = rp.crop.width() / rp.pxPerPt
            val hPt = rp.crop.height() / rp.pxPerPt
            val s = min(min(cellW / wPt, cellH / hPt), 1f)
            val dw = wPt * s
            val dh = hPt * s
            val left = rect.left + MARGIN + col * (cellW + GAP) + (cellW - dw) / 2f
            val top = rect.top + MARGIN + row * (cellH + GAP) + (cellH - dh) / 2f
            val dst = RectF(left, top, left + dw, top + dh)
            canvas.drawBitmap(rp.bmp, rp.crop, dst, paint)
            if (border) canvas.drawRect(dst, borderPaint)
            rp.bmp.recycle()
        }
    }
}
