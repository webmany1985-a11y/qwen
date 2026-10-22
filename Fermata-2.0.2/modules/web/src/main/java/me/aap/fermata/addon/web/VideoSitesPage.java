package me.aap.fermata.addon.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * Стартовая страница вкладки «Видео»: сайты пользователя крупными кнопками,
 * «Добавить», «Изменить», «Удалить». Страница своя (адрес {@link #HOME}),
 * её отдаёт {@link VideoSitesWebClient}; действия — переходы на
 * {@link #ACTION}?do=…, которые клиент выполняет, только если они пришли
 * с этой страницы. Чистая логика без Android — проверяется тестом.
 */
final class VideoSitesPage {
	static final String HOME = "https://jarvis-video.local/";
	static final String ACTION = HOME + "act";

	private VideoSitesPage() {
	}

	/** Адрес, как его ввёл человек, — в полный: «rutube.ru» → «https://rutube.ru». Пусто — null. */
	@Nullable
	static String normalizeUrl(@Nullable String url) {
		if (url == null) return null;
		String u = url.trim();
		if (u.isEmpty()) return null;
		String l = u.toLowerCase(Locale.ROOT);
		if (l.startsWith("http://") || l.startsWith("https://")) return u;
		if (l.contains("://") || l.startsWith("javascript:") || l.startsWith("data:")) return null;
		return "https://" + u;
	}

	/** Название по умолчанию — имя сайта из адреса. */
	@NonNull
	static String defaultName(@NonNull String url) {
		String h = url.replaceFirst("(?i)^https?://", "");
		int i = h.indexOf('/');
		if (i > 0) h = h.substring(0, i);
		return h.replaceFirst("(?i)^www\\.", "");
	}

	/** HTML стартовой страницы. sites — адрес → название, по порядку добавления. */
	@NonNull
	static String html(@NonNull Map<String, String> sites, @Nullable String editUrl) {
		StringBuilder b = new StringBuilder(4096);
		b.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
				.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
				.append("<title>Мои сайты</title><style>")
				.append("body{margin:0;padding:16px;background:#0b0f14;color:#f2e6c9;font:18px sans-serif}")
				.append("h1{font-size:22px;color:#ffb23a;margin:0 0 12px}")
				.append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px}")
				.append(".site{background:#1b222c;border:1px solid #3a2f1a;border-radius:14px;padding:14px}")
				.append(".open{display:block;color:#fff;text-decoration:none;font-size:22px;padding:10px 0}")
				.append(".url{color:#9aa4b1;font-size:13px;word-break:break-all}")
				.append(".acts a{color:#ffb23a;font-size:15px;margin-right:18px;text-decoration:none}")
				.append("form{background:#1b222c;border-radius:14px;padding:14px;margin-top:16px}")
				.append("input{width:100%;box-sizing:border-box;font-size:18px;padding:10px;margin:6px 0;")
				.append("border-radius:8px;border:1px solid #444;background:#0b0f14;color:#fff}")
				.append("button{font-size:18px;padding:10px 18px;border-radius:8px;border:0;background:#ffb23a;color:#000}")
				.append(".empty{color:#9aa4b1}</style></head><body><h1>Мои сайты</h1>");

		if (sites.isEmpty()) {
			b.append("<p class=\"empty\">Пока пусто. Впишите адрес сайта ниже и нажмите «Сохранить».</p>");
		} else {
			b.append("<div class=\"grid\">");
			for (Map.Entry<String, String> e : sites.entrySet()) {
				String url = e.getKey();
				b.append("<div class=\"site\"><a class=\"open\" href=\"").append(attr(url)).append("\">")
						.append(text(e.getValue())).append("</a><div class=\"url\">").append(text(url))
						.append("</div><div class=\"acts\"><a href=\"").append(attr(action("edit", url)))
						.append("\">Изменить</a><a href=\"").append(attr(action("remove", url)))
						.append("\">Удалить</a></div></div>");
			}
			b.append("</div>");
		}

		String name = "";
		String url = "";
		if ((editUrl != null) && sites.containsKey(editUrl)) {
			url = editUrl;
			name = sites.get(editUrl);
		}
		boolean edit = !url.isEmpty();
		b.append("<form method=\"get\" action=\"").append(attr(ACTION)).append("\">")
				.append("<input type=\"hidden\" name=\"do\" value=\"").append(edit ? "save" : "add").append("\">")
				.append("<input type=\"hidden\" name=\"old\" value=\"").append(attr(url)).append("\">")
				.append("<div>").append(edit ? "Изменить сайт" : "Добавить сайт").append("</div>")
				.append("<input name=\"name\" placeholder=\"Название\" value=\"").append(attr(name)).append("\">")
				.append("<input name=\"url\" placeholder=\"Адрес сайта\" inputmode=\"url\" value=\"")
				.append(attr(url)).append("\">")
				.append("<button type=\"submit\">Сохранить</button></form></body></html>");
		return b.toString();
	}

	static String action(String what, String url) {
		return ACTION + "?do=" + what + "&old=" + encode(url);
	}

	private static String encode(String s) {
		try {
			return java.net.URLEncoder.encode(s, "UTF-8");
		} catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String text(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String attr(String s) {
		return text(s).replace("\"", "&quot;").replace("'", "&#39;");
	}
}
