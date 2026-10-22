package me.aap.fermata.addon.web;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import me.aap.utils.log.Log;

/**
 * Вкладка «Видео»: отдаёт свою стартовую страницу ({@link VideoSitesPage})
 * и выполняет её действия — добавить, изменить, удалить сайт. Действия
 * принимаются только со стартовой страницы: чужой сайт список не изменит.
 */
public class VideoSitesWebClient extends FermataWebClient {
	private final VideoSitesAddon addon;

	VideoSitesWebClient(VideoSitesAddon addon) {
		this.addon = addon;
	}

	@Nullable
	@Override
	public WebResourceResponse shouldInterceptRequest(@NonNull WebView view,
																										@NonNull WebResourceRequest request) {
		Uri u = request.getUrl();
		if (isHome(u) && request.isForMainFrame()) {
			String html = VideoSitesPage.html(addon.getBookmarks(), u.getQueryParameter("edit"));
			return new WebResourceResponse("text/html", "utf-8",
					new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
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
			view.loadUrl(perform(u));
			return true;
		}
		if (TikTokWebClient.isAppLink(u)) {
			Log.i("Video: store link blocked ", u);
			return true;
		}
		return super.shouldOverrideUrlLoading(view, request);
	}

	/** Выполнить действие стартовой страницы; вернуть, какую страницу показать. */
	private String perform(Uri u) {
		String what = u.getQueryParameter("do");
		String old = u.getQueryParameter("old");
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

	static boolean isHome(Uri u) {
		return "jarvis-video.local".equals(u.getHost()) && ((u.getPath() == null) ||
				u.getPath().isEmpty() || "/".equals(u.getPath()));
	}
}
