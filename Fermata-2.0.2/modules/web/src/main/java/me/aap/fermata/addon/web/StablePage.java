package me.aap.fermata.addon.web;

import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;

import me.aap.utils.log.Log;

/**
 * Вкладки «Видео» и «Кинопоиск» (Н7, 16.11.2026, сначала сборка жены, затем по просьбе пользователя и его: «открывается не целиком, листается непонятно, можно
 * зацепить — всё сбивается»; «как YouTube»). Страница стоит, как у вкладки YouTube: по ширине экрана, без увеличения
 * двумя пальцами и двойным касанием, без сдвига всей страницы вбок; листается только вверх-вниз (ряды фильмов внутри
 * страницы листаются вбок, как и раньше).
 */
final class StablePage {
	/** Только верхняя страница: виртуальная ширина = ширине экрана, масштаб 1, без увеличения; страница не уезжает вбок. */
	static final String JS = "(function(){if(window.top!==window||window.__jarvisStable)return;window.__jarvisStable=1;" +
			"var c='width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no';" +
			"function fix(){var h=document.head||document.documentElement;if(!h)return;" +
			"var m=document.querySelector('meta[name=viewport]');" +
			"if(!m){m=document.createElement('meta');m.name='viewport';h.appendChild(m);}" +
			"if(m.content!==c)m.content=c;" +
			"if(!document.getElementById('jarvis-stable')){var s=document.createElement('style');s.id='jarvis-stable';" +
			"s.textContent='html,body{overflow-x:hidden!important;max-width:100vw!important;overscroll-behavior-x:none!important}" +
			"html{touch-action:pan-x pan-y!important;-webkit-text-size-adjust:100%!important}';h.appendChild(s);}}" +
			// Сайт может переписать viewport позже — следим только за <head> (телефон слабый, вся страница — дорого)
			"fix();document.addEventListener('DOMContentLoaded',function(){fix();if(document.head)" +
			"new MutationObserver(function(){var m=document.querySelector('meta[name=viewport]');if(!m||m.content!==c)fix();})" +
			".observe(document.head,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});});" +
			"})();";

	private StablePage() {
	}

	static void install(WebView webView, String tab) {
		WebSettings s = webView.getSettings();
		s.setSupportZoom(false);
		s.setBuiltInZoomControls(false);
		s.setUseWideViewPort(true);
		s.setLoadWithOverviewMode(true);
		webView.setHorizontalScrollBarEnabled(false);
		if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
			Log.i(tab, ": stable page script not available");
			return;
		}
		WebViewCompat.addDocumentStartJavaScript(webView, JS, Collections.singleton("*"));
		Log.i(tab, ": stable page");
	}
}
