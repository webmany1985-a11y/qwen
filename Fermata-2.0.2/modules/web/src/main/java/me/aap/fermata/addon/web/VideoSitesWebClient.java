package me.aap.fermata.addon.web;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import me.aap.fermata.ui.activity.TabMediaRules;
import me.aap.utils.log.Log;

/**
 * Вкладка «Видео»: отдаёт свою главную страницу ({@link VideoSitesPage}) с её файлами (скрипт, фон) и
 * данными афиш — их берёт у воркера афиш сама, последний удачный ответ хранит в файле (без сети ряды
 * остаются); выполняет действия страницы — открыть фильм (запомнить в «Продолжить»), продолжить с секунды,
 * добавить, изменить, удалить сайт. Действия принимаются только с главной: чужой сайт их не вызовет.
 */
public class VideoSitesWebClient extends FermataWebClient {
	static final String AFISHA = "https://tv-afisha.jarvisauto.workers.dev";
	private static final String CONT_PREFS = "jarvis_video_continue";
	private static final String CONT_KEY = "list";
	private static final String RYADY_FILE = "video_ryady.json";
	private final VideoSitesAddon addon;
	/** До какого времени страница фильма, открытая с главной, запускается сама (assets/zona_play.js). */
	private long autoPlayUntil;
	@Nullable
	private static String playScript;

	VideoSitesWebClient(VideoSitesAddon addon) {
		this.addon = addon;
	}

	@Nullable
	@Override
	public WebResourceResponse shouldInterceptRequest(@NonNull WebView view,
																										@NonNull WebResourceRequest request) {
		Uri u = request.getUrl();
		if (AdHosts.isAd(u.getHost())) {
			// Н6: реклама и счётчики не грузятся (сборка жены 16.11; сборка пользователя — по его просьбе 16.11)
			return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
		}
		if (VideoSitesPage.HOST.equals(u.getHost())) {
			try {
				WebResourceResponse r = local(view.getContext(), u, request.isForMainFrame());
				if (r != null) return r;
			} catch (Exception ex) {
				Log.e(ex, "Video: local page ", u);
			}
		}
		return super.shouldInterceptRequest(view, request);
	}

	@Override
	public boolean shouldOverrideUrlLoading(@NonNull WebView view,
																					@NonNull WebResourceRequest request) {
		Uri u = request.getUrl();
		String scheme = u.getScheme();
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			Log.i("Video: app link blocked ", u);
			return true;
		}
		if (u.toString().startsWith(VideoSitesPage.ACTION)) {
			String from = view.getUrl();
			if ((from == null) || !from.startsWith(VideoSitesPage.HOME)) {
				Log.i("Video: action not from the start page — ignored");
				return true;
			}
			view.loadUrl(perform(view, u));
			return true;
		}
		if (TikTokWebClient.isAppLink(u)) {
			Log.i("Video: store link blocked ", u);
			return true;
		}
		if (AdHosts.isAd(u.getHost())) {
			Log.i("Video: ad page blocked ", u.getHost());
			return true;
		}
		return super.shouldOverrideUrlLoading(view, request);
	}

	@Override
	public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
		// Ушли со страницы фильма — панели приложения обратно (их прятал zona_play.js, Н6)
		if (view instanceof FermataWebView w) {
			FermataChromeClient c = w.getWebChromeClient();
			if ((c != null) && c.isPageFullScreen()) c.setPageFullScreen(false);
		}
		super.onPageStarted(view, url, favicon);
	}

	@Override
	public void onPageFinished(WebView view, String url) {
		super.onPageFinished(view, url);
		if ((url == null) || (System.currentTimeMillis() > autoPlayUntil)) return;
		Uri u = Uri.parse(url);
		if ((u.getHost() == null) || !u.getHost().contains("zona")) return;
		String js = playScript(view.getContext());
		if (js == null) return;
		Log.i("Video: film page, auto play ", url);
		view.evaluateJavascript(js, null);
	}

	@Nullable
	private static String playScript(Context ctx) {
		if (playScript != null) return playScript;
		try {
			return playScript = new String(asset(ctx, "zona_play.js"), StandardCharsets.UTF_8);
		} catch (Exception ex) {
			Log.e(ex, "Video: zona_play.js");
			return null;
		}
	}

	/** Файлы и данные главной страницы; null — не её адрес. Вызывается не в главном потоке. */
	@Nullable
	private WebResourceResponse local(Context ctx, Uri u, boolean mainFrame) throws Exception {
		String path = u.getPath();
		if (isHome(u)) {
			if (!mainFrame) return null;
			String html = VideoSitesPage.html(addon.getBookmarks(), u.getQueryParameter("edit"),
					continueList(ctx));
			return response("text/html", html.getBytes(StandardCharsets.UTF_8), 200, null);
		}
		if ("/home.js".equals(path)) return response("application/javascript", asset(ctx, "video_home.js"), 200, null);
		if ("/fon.jpg".equals(path)) return response("image/jpeg", asset(ctx, "video_fon.jpg"), 200, null);
		if ("/ryady.json".equals(path)) return ryady(ctx);
		if ("/poisk.json".equals(path)) {
			String q = u.getQueryParameter("q");
			byte[] b = ((q == null) || q.trim().isEmpty()) ? null : fetch(AFISHA + "/poisk?q=" + Uri.encode(q.trim()));
			if ((b == null) || !VideoSitesPage.isAfisha(new String(b, StandardCharsets.UTF_8), "filmy"))
				return response("application/json", "{}".getBytes(StandardCharsets.UTF_8), 503, null);
			return response("application/json", b, 200, null);
		}
		return null;
	}

	/** Ряды афиш: свежие у воркера (и в файл), нет связи — последние сохранённые. */
	private WebResourceResponse ryady(Context ctx) {
		File f = new File(ctx.getFilesDir(), RYADY_FILE);
		byte[] b = fetch(AFISHA + "/ryady");
		if ((b != null) && VideoSitesPage.isAfisha(new String(b, StandardCharsets.UTF_8), "ryady")) {
			File tmp = new File(f.getPath() + ".tmp");
			try (FileOutputStream out = new FileOutputStream(tmp)) {
				out.write(b);
			} catch (Exception ex) {
				Log.e(ex, "Video: save afisha");
			}
			if (!tmp.renameTo(f)) Log.i("Video: afisha cache not saved");
			return response("application/json", b, 200, null);
		}
		if (f.isFile()) {
			try (InputStream in = new java.io.FileInputStream(f)) {
				Log.i("Video: no network — saved afisha");
				return response("application/json", read(in), 200, "old");
			} catch (Exception ex) {
				Log.e(ex, "Video: read afisha cache");
			}
		}
		return response("application/json", "{}".getBytes(StandardCharsets.UTF_8), 503, null);
	}

	@Nullable
	private static byte[] fetch(String url) {
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL(url).openConnection();
			c.setConnectTimeout(8000);
			c.setReadTimeout(12000);
			c.setRequestProperty("Accept", "application/json");
			if (c.getResponseCode() != 200) return null;
			try (InputStream in = c.getInputStream()) {
				return read(in);
			}
		} catch (Exception ex) {
			Log.i("Video: afisha not loaded: ", ex.getMessage());
			return null;
		} finally {
			if (c != null) c.disconnect();
		}
	}

	private static byte[] read(InputStream in) throws java.io.IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
		return out.toByteArray();
	}

	private static byte[] asset(Context ctx, String name) throws java.io.IOException {
		try (InputStream in = me.aap.fermata.util.Utils.dynCtx(ctx).getAssets().open(name)) {
			return read(in);
		}
	}

	private static WebResourceResponse response(String mime, byte[] b, int code, @Nullable String cache) {
		Map<String, String> h = new java.util.HashMap<>();
		h.put("Cache-Control", "no-store");
		if (cache != null) h.put("X-Jarvis-Cache", cache);
		return new WebResourceResponse(mime, "utf-8", code, (code == 200) ? "OK" : "Unavailable", h,
				new ByteArrayInputStream(b));
	}

	private static SharedPreferences contPrefs(Context ctx) {
		return ctx.getApplicationContext().getSharedPreferences(CONT_PREFS, Context.MODE_PRIVATE);
	}

	static List<VideoContinue.Entry> continueList(Context ctx) {
		try {
			return VideoContinue.decode(contPrefs(ctx).getString(CONT_KEY, null));
		} catch (RuntimeException ex) {
			Log.e(ex, "Video: continue list");
			return Collections.emptyList();
		}
	}

	private static void saveContinue(Context ctx, List<VideoContinue.Entry> l) {
		contPrefs(ctx).edit().putString(CONT_KEY, VideoContinue.encode(l)).apply();
	}

	/** Плеер на странице фильма сообщил секунду — запомнить для «Продолжить просмотр». */
	static void onPlayed(Context ctx, @Nullable String pageUrl, double time, double duration) {
		List<VideoContinue.Entry> l = continueList(ctx);
		if (VideoContinue.played(l, pageUrl, time, duration)) saveContinue(ctx, l);
	}

	/** Выполнить действие главной страницы; вернуть, какую страницу показать. */
	private String perform(WebView view, Uri u) {
		String what = u.getQueryParameter("do");
		String old = u.getQueryParameter("old");
		if ("open".equals(what) || "cont".equals(what)) {
			String film = VideoSitesPage.normalizeUrl(u.getQueryParameter("u"));
			if ((film == null) || film.startsWith(VideoSitesPage.HOME)) return VideoSitesPage.HOME;
			Context ctx = view.getContext();
			List<VideoContinue.Entry> l = continueList(ctx);
			VideoContinue.Entry e = new VideoContinue.Entry(film, str(u, "n"), str(u, "a"),
					(int) num(u, "g"), num(u, "r"), "1".equals(u.getQueryParameter("s")), 0, 0);
			l = VideoContinue.opened(l, e);
			saveContinue(ctx, l);
			if ("cont".equals(what)) {
				double at = TabMediaRules.resumeAt(l.get(0).time, l.get(0).duration);
				Log.i("Video: continue at ", at);
				TabMediaScript.resumeAt(view, at);
			}
			// Фильм — на зеркале Zona из афиши (воркер выбирает рабочее): на сохранённом зеркале w1.zona.im другой движок
			// сайта, и фильм на нём сам не запускался (замечание 15.11.2026). Запуск — assets/zona_play.js
			autoPlayUntil = System.currentTimeMillis() + 60_000L;
			return film;
		}
		if ("remove".equals(what) && (old != null)) {
			addon.removeBookmark(old);
			Log.i("Video: site removed");
		} else if ("edit".equals(what) && (old != null)) {
			return VideoSitesPage.HOME + "?edit=" + Uri.encode(old);
		} else if ("add".equals(what) || "save".equals(what)) {
			String url = VideoSitesPage.normalizeUrl(u.getQueryParameter("url"));
			if (url != null) {
				String name = u.getQueryParameter("name");
				if ((name == null) || name.trim().isEmpty()) name = VideoSitesPage.defaultName(url);
				if ("save".equals(what) && (old != null) && !old.equals(url)) addon.removeBookmark(old);
				addon.addBookmark(name.trim(), url);
				Log.i("Video: site saved");
			}
		}
		return VideoSitesPage.HOME;
	}

	private static String str(Uri u, String k) {
		String v = u.getQueryParameter(k);
		return (v == null) ? "" : v.trim();
	}

	private static double num(Uri u, String k) {
		try {
			double d = Double.parseDouble(str(u, k));
			return Double.isNaN(d) || Double.isInfinite(d) ? 0 : d;
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	static boolean isHome(Uri u) {
		return "jarvis-video.local".equals(u.getHost()) && ((u.getPath() == null) ||
				u.getPath().isEmpty() || "/".equals(u.getPath()));
	}
}
