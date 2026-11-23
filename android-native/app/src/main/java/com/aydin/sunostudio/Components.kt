package com.aydin.sunostudio

import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Pal {
    val BG = Color.parseColor("#0A0919")
    val SURFACE = Color.parseColor("#13112B")
    val SURFACE2 = Color.parseColor("#1D1A3F")
    val STROKE = Color.parseColor("#2D2966")
    val TEXT = Color.parseColor("#F5F3FF")
    val MUTED = Color.parseColor("#A9A5D0")
    val DIM = Color.parseColor("#6F6B9E")
    val GOLD = Color.parseColor("#F6C453")
    val VIOLET = Color.parseColor("#8B5CF6")
    val CYAN = Color.parseColor("#22D3EE")
    val MAGENTA = Color.parseColor("#EC4899")
    val RED = Color.parseColor("#FF4D5E")
    val BLUE = Color.parseColor("#2F7DFF")
    val GREEN = Color.parseColor("#34D399")
    val DANGER = Color.parseColor("#FF7A7A")
}

fun Context.dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(n: Float): Float = n * resources.displayMetrics.density

fun String.fa(): String {
    val sb = StringBuilder(length)
    for (ch in this) sb.append(if (ch in '0'..'9') ('۰' + (ch - '0')) else ch)
    return sb.toString()
}

fun roundedBg(ctx: Context, fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = ctx.dpf(radiusDp)
        if (strokeDp > 0) setStroke(ctx.dp(strokeDp), stroke)
    }

fun gradientBg(
    ctx: Context,
    colors: IntArray,
    radiusDp: Float,
    orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.LEFT_RIGHT
): GradientDrawable = GradientDrawable(orientation, colors).apply { cornerRadius = ctx.dpf(radiusDp) }

fun withRipple(ctx: Context, content: Drawable, radiusDp: Float): RippleDrawable {
    val mask = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = ctx.dpf(radiusDp)
    }
    return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
}

fun makeText(ctx: Context, text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun LinearLayout.addBlock(v: View, heightPx: Int = WRAP, topDp: Int = 0, weight: Float = 0f) {
    val p = LinearLayout.LayoutParams(MATCH, heightPx, weight)
    p.topMargin = context.dp(topDp)
    addView(v, p)
}

fun readClipboard(ctx: Context): String {
    return try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) "" else
            clip.getItemAt(0).coerceToText(ctx)?.toString()?.trim() ?: ""
    } catch (_: Exception) {
        ""
    }
}

/** Thin rounded progress bar: determinate or an animated indeterminate sweep. */
class ThinProgress(ctx: Context) : View(ctx) {
    var colors: IntArray = intArrayOf(Pal.VIOLET, Pal.CYAN)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.SURFACE2 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var indeterminate = false
    private var phase = 0f
    private var anim: ValueAnimator? = null

    fun setProgress(p: Float) {
        stopAnim()
        indeterminate = false
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    fun setIndeterminate() {
        if (indeterminate) return
        indeterminate = true
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun reset() {
        stopAnim()
        indeterminate = false
        progress = 0f
        invalidate()
    }

    private fun stopAnim() {
        anim?.cancel()
        anim = null
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val r = h / 2f
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        canvas.save()
        if (rtl) canvas.scale(-1f, 1f, w / 2f, h / 2f)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
        if (indeterminate) {
            val len = w * 0.38f
            val x = (w + len) * phase - len
            fill.shader = LinearGradient(x, 0f, x + len, 0f, colors, null, Shader.TileMode.CLAMP)
            canvas.save()
            canvas.clipRect(0f, 0f, w, h)
            canvas.drawRoundRect(x, 0f, x + len, h, r, r, fill)
            canvas.restore()
        } else if (progress > 0f) {
            val pw = (w * progress).coerceAtLeast(h)
            fill.shader = LinearGradient(0f, 0f, pw, 0f, colors, null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, 0f, pw, h, r, r, fill)
        }
        canvas.restore()
    }
}

class GradientButton(ctx: Context, private var colors: IntArray, iconRes: Int, label: String) : LinearLayout(ctx) {
    private val labelView = TextView(ctx)
    private val iconView = ImageView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        applyColors()
        iconView.setImageResource(iconRes)
        iconView.setColorFilter(Color.WHITE)
        addView(iconView, LayoutParams(ctx.dp(22), ctx.dp(22)))
        labelView.text = label
        labelView.textSize = 16f
        labelView.setTextColor(Color.WHITE)
        labelView.setTypeface(labelView.typeface, Typeface.BOLD)
        labelView.setPadding(ctx.dp(10), 0, 0, 0)
        addView(labelView, LayoutParams(WRAP, WRAP))
    }

    private fun applyColors() {
        background = withRipple(context, gradientBg(context, colors, 16f), 16f)
    }

    fun setStyle(c: IntArray, iconRes: Int, label: String) {
        colors = c
        applyColors()
        iconView.setImageResource(iconRes)
        labelView.text = label
    }

    fun setBusy(busy: Boolean) {
        isEnabled = !busy
        alpha = if (busy) 0.5f else 1f
    }
}

class Segmented(
    ctx: Context,
    labels: List<String>,
    private val accent: IntArray,
    private val onChange: (Int) -> Unit
) : LinearLayout(ctx) {
    var selected = 0
        private set
    private val items = ArrayList<TextView>()

    init {
        orientation = HORIZONTAL
        background = roundedBg(ctx, Pal.SURFACE, 14f, Pal.STROKE, 1)
        setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        labels.forEachIndexed { i, l ->
            val t = TextView(ctx)
            t.text = l
            t.gravity = Gravity.CENTER
            t.textSize = 13f
            t.maxLines = 1
            t.setOnClickListener { select(i, true) }
            addView(t, LayoutParams(0, MATCH, 1f))
            items.add(t)
        }
        select(0, false)
    }

    fun select(i: Int, notify: Boolean) {
        selected = i
        items.forEachIndexed { idx, t ->
            if (idx == i) {
                t.background = gradientBg(context, accent, 11f)
                t.setTextColor(Color.WHITE)
                t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                t.background = null
                t.setTextColor(Pal.MUTED)
                t.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            }
        }
        if (notify) onChange(i)
    }
}

class UrlField(ctx: Context, hint: String, accent: Int, private val onSubmit: () -> Unit) : LinearLayout(ctx) {
    val edit = EditText(ctx)
    private val action = ImageView(ctx)
    var changeListener: (() -> Unit)? = null

    var text: String
        get() = edit.text.toString().trim()
        set(v) {
            edit.setText(v)
            edit.setSelection(edit.text.length)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBg(ctx, Pal.SURFACE, 16f, Pal.STROKE, 1)
        setPadding(ctx.dp(14), 0, ctx.dp(6), 0)

        edit.background = null
        edit.hint = hint
        edit.setHintTextColor(Pal.DIM)
        edit.setTextColor(Pal.TEXT)
        edit.textSize = 14f
        edit.setSingleLine(true)
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        edit.imeOptions = EditorInfo.IME_ACTION_GO
        edit.textDirection = View.TEXT_DIRECTION_LTR
        edit.gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                onSubmit()
                true
            } else false
        }
        addView(edit, LayoutParams(0, MATCH, 1f))

        action.setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        action.setColorFilter(accent)
        action.isClickable = true
        action.background = withRipple(ctx, GradientDrawable().apply { setColor(Color.TRANSPARENT) }, 20f)
        addView(action, LayoutParams(ctx.dp(44), ctx.dp(44)))
        refreshIcon()
        action.setOnClickListener {
            if (edit.text.isNullOrEmpty()) {
                val clip = readClipboard(context)
                if (clip.isNotBlank()) text = Net.extractUrl(clip)
            } else {
                edit.setText("")
            }
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshIcon()
                changeListener?.invoke()
            }
        })
    }

    private fun refreshIcon() {
        action.setImageResource(if (edit.text.isNullOrEmpty()) R.drawable.ic_paste else R.drawable.ic_close)
    }
}

class PreviewCard(ctx: Context, private val iconRes: Int, private val accent: Int) : LinearLayout(ctx) {
    private val cover = ImageView(ctx)
    private val titleView = TextView(ctx)
    private val metaView = TextView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = withRipple(ctx, roundedBg(ctx, Pal.SURFACE, 18f, Pal.STROKE, 1), 18f)
        setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))

        cover.clipToOutline = true
        cover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, ctx.dpf(12f))
            }
        }
        addView(cover, LayoutParams(ctx.dp(72), ctx.dp(72)))

        val col = LinearLayout(ctx)
        col.orientation = VERTICAL
        titleView.textSize = 15f
        titleView.setTextColor(Pal.TEXT)
        titleView.setTypeface(titleView.typeface, Typeface.BOLD)
        titleView.maxLines = 2
        titleView.ellipsize = TextUtils.TruncateAt.END
        metaView.textSize = 12f
        metaView.setTextColor(Pal.MUTED)
        metaView.maxLines = 2
        metaView.ellipsize = TextUtils.TruncateAt.END
        col.addView(titleView, LayoutParams(MATCH, WRAP))
        val mp = LayoutParams(MATCH, WRAP)
        mp.topMargin = ctx.dp(4)
        col.addView(metaView, mp)
        val cp = LayoutParams(0, WRAP, 1f)
        cp.marginStart = ctx.dp(12)
        addView(col, cp)
        placeholder("پیش‌نمایش", "")
    }

    private fun showIcon() {
        cover.scaleType = ImageView.ScaleType.CENTER_INSIDE
        cover.setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
        cover.setImageResource(iconRes)
        cover.setColorFilter(accent)
        cover.background = roundedBg(context, Pal.SURFACE2, 12f)
    }

    fun placeholder(title: String, meta: String) {
        showIcon()
        titleView.text = title
        titleView.setTextColor(Pal.MUTED)
        metaView.text = meta
        setOnClickListener(null)
        isClickable = false
    }

    fun filled(title: String, meta: String) {
        showIcon()
        titleView.text = title
        titleView.setTextColor(Pal.TEXT)
        metaView.text = meta
    }

    fun setMeta(meta: String) {
        metaView.text = meta
    }

    fun setCover(bmp: Bitmap) {
        cover.clearColorFilter()
        cover.setPadding(0, 0, 0, 0)
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.setImageBitmap(bmp)
    }
}

class StatusView(ctx: Context, accent: IntArray) : LinearLayout(ctx) {
    val bar = ThinProgress(ctx)
    private val text = TextView(ctx)

    init {
        orientation = VERTICAL
        bar.colors = accent
        addView(bar, LayoutParams(MATCH, ctx.dp(6)))
        text.textSize = 13f
        text.maxLines = 4
        text.ellipsize = TextUtils.TruncateAt.END
        text.setLineSpacing(0f, 1.15f)
        text.setPadding(0, ctx.dp(10), 0, 0)
        addView(text, LayoutParams(MATCH, WRAP))
    }

    fun info(s: String) {
        text.setTextColor(Pal.MUTED)
        text.text = s
    }

    fun good(s: String) {
        text.setTextColor(Pal.GREEN)
        text.text = s
    }

    fun bad(s: String) {
        text.setTextColor(Pal.DANGER)
        text.text = s
    }
}

fun screenHeader(
    ctx: Context,
    iconRes: Int,
    colors: IntArray,
    title: String,
    subtitle: String,
    actionIcon: Int = 0,
    onAction: (() -> Unit)? = null
): LinearLayout {
    val row = LinearLayout(ctx)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL

    val badge = ImageView(ctx)
    badge.setImageResource(iconRes)
    badge.setColorFilter(Color.WHITE)
    badge.setPadding(ctx.dp(11), ctx.dp(11), ctx.dp(11), ctx.dp(11))
    badge.background = gradientBg(ctx, colors, 15f, GradientDrawable.Orientation.TL_BR)
    row.addView(badge, LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(46)))

    val col = LinearLayout(ctx)
    col.orientation = LinearLayout.VERTICAL
    col.addView(makeText(ctx, title, 20f, Pal.TEXT, true))
    val sub = makeText(ctx, subtitle, 12f, Pal.MUTED)
    sub.maxLines = 1
    sub.ellipsize = TextUtils.TruncateAt.END
    col.addView(sub)
    val cp = LinearLayout.LayoutParams(0, WRAP, 1f)
    cp.marginStart = ctx.dp(12)
    row.addView(col, cp)

    if (actionIcon != 0 && onAction != null) {
        val b = ImageView(ctx)
        b.setImageResource(actionIcon)
        b.setColorFilter(Pal.MUTED)
        b.setPadding(ctx.dp(9), ctx.dp(9), ctx.dp(9), ctx.dp(9))
        b.background = withRipple(ctx, roundedBg(ctx, Pal.SURFACE, 20f, Pal.STROKE, 1), 20f)
        b.isClickable = true
        b.setOnClickListener { onAction() }
        row.addView(b, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
    }
    return row
}

class NavItem(val label: String, val iconRes: Int, val color: Int)

class NavBar(ctx: Context, private val items: List<NavItem>, private val onSelect: (Int) -> Unit) : LinearLayout(ctx) {
    private class Cell(val pill: LinearLayout, val icon: ImageView, val label: TextView)

    private val cells = ArrayList<Cell>()
    private val baseHeight = ctx.dp(66)

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Pal.SURFACE)
        items.forEachIndexed { i, item ->
            val cell = LinearLayout(ctx)
            cell.orientation = VERTICAL
            cell.gravity = Gravity.CENTER
            cell.isClickable = true
            cell.setOnClickListener { onSelect(i) }

            val pill = LinearLayout(ctx)
            pill.gravity = Gravity.CENTER
            val icon = ImageView(ctx)
            icon.setImageResource(item.iconRes)
            pill.addView(icon, LayoutParams(ctx.dp(22), ctx.dp(22)))
            cell.addView(pill, LayoutParams(ctx.dp(54), ctx.dp(30)))

            val label = TextView(ctx)
            label.text = item.label
            label.textSize = 11f
            label.maxLines = 1
            label.gravity = Gravity.CENTER
            val lp = LayoutParams(WRAP, WRAP)
            lp.topMargin = ctx.dp(2)
            cell.addView(label, lp)

            addView(cell, LayoutParams(0, MATCH, 1f))
            cells.add(Cell(pill, icon, label))
        }
        select(0)
    }

    fun setInsetBottom(px: Int) {
        setPadding(0, 0, 0, px)
        layoutParams?.let {
            it.height = baseHeight + px
            layoutParams = it
        }
    }

    fun select(index: Int) {
        cells.forEachIndexed { i, c ->
            val active = i == index
            val color = items[i].color
            c.icon.setColorFilter(if (active) color else Pal.DIM)
            c.label.setTextColor(if (active) Pal.TEXT else Pal.DIM)
            c.label.setTypeface(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
            c.pill.background = if (active)
                roundedBg(context, (color and 0x00FFFFFF) or 0x2E000000, 15f, (color and 0x00FFFFFF) or 0x66000000, 1)
            else null
            c.pill.animate().scaleX(if (active) 1.06f else 1f).scaleY(if (active) 1.06f else 1f).setDuration(180).start()
        }
    }
}
