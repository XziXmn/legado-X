package io.legado.app.ui.book.read.comment

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.isVisible
import io.legado.app.databinding.ViewChapterCommentPanelBinding
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.chapterComment.ChapterCommentReaderTheme
import io.legado.app.model.chapterComment.ChapterCommentWebPage
import io.legado.app.model.chapterComment.SourceScopedWebController
import io.legado.app.utils.gone
import io.legado.app.utils.visible
import kotlin.math.roundToInt

/**
 * In-reader chapter-comment shell (scheme B).
 *
 * Visual: top-rounded card, bottom flush, light elevation. No title / close chrome —
 * dismiss via upper scrim or system back only.
 */
class ChapterCommentPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val binding = ViewChapterCommentPanelBinding.inflate(LayoutInflater.from(context), this)
    private val webController = SourceScopedWebController()
    private var pooledWebView: PooledWebView? = null
    private var sheetAnimator: ValueAnimator? = null
    private var closing = false

    var isOpen: Boolean = false
        private set

    /** True while open, opening, loading, or error — blocks a second open. */
    val isBusy: Boolean
        get() = isOpen || closing

    var onRequestClose: (() -> Unit)? = null
    var onRetry: (() -> Unit)? = null

    init {
        gone()
        isClickable = true
        binding.commentScrim.setOnClickListener { requestClose() }
        binding.btnCommentRetry.setOnClickListener { onRetry?.invoke() }
        // Consume sheet touches so they do not fall through to ReadView.
        binding.commentSheet.isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0 && isOpen) {
            applySheetHeight(h)
        }
    }

    fun openLoading() {
        closing = false
        if (isOpen) {
            showState(State.LOADING)
            return
        }
        ensureOpenChrome()
        showState(State.LOADING)
    }

    fun showPage(page: ChapterCommentWebPage) {
        if (closing) return
        if (!isOpen) {
            ensureOpenChrome()
        }
        ensureWebView()
        showState(State.CONTENT)
        pooledWebView?.realWebView?.onResume()
        webController.load(
            finalUrl = page.finalUrl,
            html = page.html,
            headers = page.headers,
            networkContext = page.networkContext,
        )
    }

    fun showError(message: String) {
        if (closing) return
        if (!isOpen) {
            ensureOpenChrome()
        }
        binding.tvCommentError.text = message
        showState(State.ERROR)
    }

    fun close(animated: Boolean = true) {
        if (closing) return
        if (!isOpen && !isVisible) {
            releaseWeb()
            return
        }
        closing = true
        sheetAnimator?.cancel()
        if (!animated || width == 0 || binding.commentSheet.height == 0) {
            finishClose()
            return
        }
        val sheet = binding.commentSheet
        val start = sheet.translationY
        val end = sheet.height.toFloat().coerceAtLeast(1f)
        sheetAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = CLOSE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                sheet.translationY = it.animatedValue as Float
                binding.commentScrim.alpha = (1f - it.animatedFraction).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishClose()
                }

                override fun onAnimationCancel(animation: Animator) {
                    finishClose()
                }
            })
            start()
        }
    }

    /** @return true if consumed. */
    fun onBackPressed(): Boolean {
        if (!isOpen || closing) return false
        if (webController.goBackOrFalse()) return true
        requestClose()
        return true
    }

    fun release() {
        sheetAnimator?.cancel()
        sheetAnimator = null
        releaseWeb()
        isOpen = false
        closing = false
        gone()
    }

    fun pauseWeb() {
        pooledWebView?.realWebView?.onPause()
    }

    fun resumeWeb() {
        if (isOpen && !closing) {
            pooledWebView?.realWebView?.onResume()
        }
    }

    private fun requestClose() {
        onRequestClose?.invoke()
    }

    private fun ensureOpenChrome() {
        isOpen = true
        closing = false
        visible()
        applySheetSurfaceFromReaderTheme()
        val parentH = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        applySheetHeight(parentH)
        binding.commentScrim.alpha = 0f
        animateOpen()
    }

    /** Match the sheet surface to the current reading page background. */
    private fun applySheetSurfaceFromReaderTheme() {
        val radius = 16f * resources.displayMetrics.density
        val paper = ChapterCommentReaderTheme.paperColor()
        binding.commentSheet.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(paper)
            cornerRadii = floatArrayOf(
                radius, radius, radius, radius,
                0f, 0f, 0f, 0f,
            )
        }
    }

    private fun animateOpen() {
        sheetAnimator?.cancel()
        val sheet = binding.commentSheet
        sheet.post {
            if (!isOpen || closing) return@post
            applySheetHeight(height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels)
            val start = sheet.height.toFloat().coerceAtLeast(1f)
            sheet.translationY = start
            sheetAnimator = ValueAnimator.ofFloat(start, 0f).apply {
                duration = OPEN_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    sheet.translationY = it.animatedValue as Float
                    binding.commentScrim.alpha = it.animatedFraction.coerceIn(0f, 1f)
                }
                start()
            }
        }
    }

    private fun finishClose() {
        sheetAnimator = null
        isOpen = false
        closing = false
        releaseWeb()
        binding.commentSheet.translationY = 0f
        binding.commentScrim.alpha = 1f
        showState(State.LOADING)
        gone()
    }

    private fun ensureWebView() {
        if (pooledWebView != null) return
        val pooled = WebViewPool.acquire(context)
        pooledWebView = pooled
        binding.webViewContainer.removeAllViews()
        binding.webViewContainer.addView(
            pooled.realWebView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        webController.attach(pooled.realWebView)
        pooled.realWebView.resumeTimers()
    }

    private fun releaseWeb() {
        webController.detach()
        pooledWebView?.let { pooled ->
            binding.webViewContainer.removeView(pooled.realWebView)
            WebViewPool.release(pooled)
        }
        pooledWebView = null
    }

    private fun applySheetHeight(parentHeight: Int) {
        val target = (parentHeight * HEIGHT_RATIO).roundToInt().coerceAtLeast(1)
        val lp = binding.commentSheet.layoutParams as LayoutParams
        if (lp.height != target || lp.gravity != Gravity.BOTTOM) {
            lp.height = target
            lp.gravity = Gravity.BOTTOM
            binding.commentSheet.layoutParams = lp
        }
    }

    private fun showState(state: State) {
        binding.commentLoading.isVisible = state == State.LOADING
        binding.commentError.isVisible = state == State.ERROR
        binding.webViewContainer.isVisible = state == State.CONTENT
    }

    private enum class State { LOADING, CONTENT, ERROR }

    companion object {
        private const val HEIGHT_RATIO = 0.78f
        private const val OPEN_MS = 220L
        private const val CLOSE_MS = 180L
    }
}
