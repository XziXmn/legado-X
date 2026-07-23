package io.legado.app.model.chapterComment

import android.graphics.Color
import android.webkit.WebView
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.utils.ColorUtils

/**
 * Colors for the in-reader comment panel (native sheet + SOURCE_SCOPED HTML).
 * Paper/ink follow the reading page; interactive accent follows App 主题强调色
 * ([ThemeStore.accentColor]), not reading-style textAccentColor.
 */
internal object ChapterCommentReaderTheme {

    data class Tokens(
        val paper: Int,
        val ink: Int,
        val blue: Int,
        val muted: Int,
        val faint: Int,
        val line: Int,
        val lineSoft: Int,
        val blueSoft: Int,
        val green: Int,
        val greenSoft: Int,
        val rose: Int,
        val scheme: String,
    ) {
        fun css(color: Int): String = ColorUtils.intToString(color)
    }

    fun paperColor(): Int {
        // bgMeanColor is filled by ReadBook.upBg(); before first layout it can still be 0.
        val mean = ReadBookConfig.bgMeanColor
        if (mean != 0) {
            return ColorUtils.stripAlpha(mean)
        }
        val raw = ReadBookConfig.durConfig.curBgStr()
        return runCatching {
            if (raw.startsWith("#")) Color.parseColor(raw)
            else Color.parseColor("#$raw")
        }.getOrElse {
            // Prefer dark paper when body text is light (night-like configs).
            if (ColorUtils.isColorLight(ReadBookConfig.textColor)) 0xFF1C1C1E.toInt()
            else 0xFFEEEEEE.toInt()
        }.let(ColorUtils::stripAlpha)
    }

    fun tokens(): Tokens {
        val paper = paperColor()
        val ink = ColorUtils.stripAlpha(ReadBookConfig.textColor)
        // App 主题设置 → 强调色（与设置页色块一致），避免误用阅读样式默认红。
        val blue = ColorUtils.stripAlpha(ThemeStore.accentColor())
        val lightPaper = ColorUtils.isColorLight(paper)
        val green = if (lightPaper) 0xFF39765E.toInt() else 0xFF7BC9A6.toInt()
        val rose = if (lightPaper) 0xFFA15462.toInt() else 0xFFE08A97.toInt()
        return Tokens(
            paper = paper,
            ink = ink,
            blue = blue,
            muted = ColorUtils.blendColors(ink, paper, 0.42f),
            faint = ColorUtils.blendColors(ink, paper, 0.58f),
            line = ColorUtils.blendColors(ink, paper, 0.82f),
            lineSoft = ColorUtils.blendColors(ink, paper, 0.9f),
            blueSoft = ColorUtils.blendColors(blue, paper, 0.88f),
            green = green,
            greenSoft = ColorUtils.blendColors(green, paper, 0.85f),
            rose = rose,
            scheme = if (lightPaper) "light" else "dark",
        )
    }

    fun styleTag(): String {
        val t = tokens()
        return """
            <style id="legado-reader-theme">
            :root{
              color-scheme:${t.scheme};
              --paper:${t.css(t.paper)};
              --ink:${t.css(t.ink)};
              --muted:${t.css(t.muted)};
              --faint:${t.css(t.faint)};
              --line:${t.css(t.line)};
              --line-soft:${t.css(t.lineSoft)};
              --blue:${t.css(t.blue)};
              --blue-soft:${t.css(t.blueSoft)};
              --green:${t.css(t.green)};
              --green-soft:${t.css(t.greenSoft)};
              --rose:${t.css(t.rose)};
            }
            html,body,.review-sheet{background:var(--paper)!important;color:var(--ink)!important}
            .comment-text,.comment-identity strong,.reply-line p,.reply-body,.sheet-title strong{color:var(--ink)!important}
            .comment-paragraph,.paragraph-quote{color:var(--muted)!important;border-left-color:var(--line)!important}
            .comment-meta,.comment-head time,.empty-state,.load-status,.sheet-title span,.reply-arrow{color:var(--faint)!important}
            .comment-avatar,.avatar-photo{background:var(--line-soft)!important;color:var(--muted)!important}
            .comment-media,.title-badge{border-color:var(--line-soft)!important;background:var(--blue-soft)!important;color:var(--muted)!important}
            .comment-detail-link,.reply-author,.reply-toggle,.fold-toggle,.paragraph-more,.review-tab[aria-selected=true]{color:var(--blue)!important}
            .review-tab{color:var(--muted)!important}
            .review-tabs,.comment-item,.scope-row,.paragraph-group,.reply-line+.reply-line{border-color:var(--line-soft)!important}
            .reply-stack{border-left-color:var(--line)!important}
            .author-badge,.position-badge{background:var(--green-soft)!important;color:var(--green)!important}
            .related-position{background:var(--blue-soft)!important;color:var(--blue)!important}
            .pagination-row a{border-color:var(--line)!important;color:var(--blue)!important}
            .load-status.error{color:var(--rose)!important}
            </style>
        """.trimIndent()
    }

    fun applyCssVariables(view: WebView) {
        val t = tokens()
        val js = """
            (function(){
              var r=document.documentElement; if(!r) return;
              var s=r.style;
              s.setProperty('--paper','${t.css(t.paper)}');
              s.setProperty('--ink','${t.css(t.ink)}');
              s.setProperty('--muted','${t.css(t.muted)}');
              s.setProperty('--faint','${t.css(t.faint)}');
              s.setProperty('--line','${t.css(t.line)}');
              s.setProperty('--line-soft','${t.css(t.lineSoft)}');
              s.setProperty('--blue','${t.css(t.blue)}');
              s.setProperty('--blue-soft','${t.css(t.blueSoft)}');
              s.setProperty('--green','${t.css(t.green)}');
              s.setProperty('--green-soft','${t.css(t.greenSoft)}');
              s.setProperty('--rose','${t.css(t.rose)}');
              r.style.colorScheme='${t.scheme}';
              if(document.body){document.body.style.background='${t.css(t.paper)}';document.body.style.color='${t.css(t.ink)}';}
              var sheet=document.querySelector('.review-sheet');
              if(sheet){sheet.style.background='${t.css(t.paper)}';sheet.style.color='${t.css(t.ink)}';}
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }
}
