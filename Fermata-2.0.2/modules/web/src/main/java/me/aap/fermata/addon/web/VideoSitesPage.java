package me.aap.fermata.addon.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Главная страница вкладки «Видео» (пожелание 15.11.2026 — «как Дом ТВ»): фон «Дом ТВ», большая карточка
 * выбранного фильма, поиск, ряды афиш (данные — {@link #RYADY} и {@link #POISK}, их отдаёт
 * {@link VideoSitesWebClient} из воркера афиш), «Продолжить просмотр», строка сервисов и «Мои сайты»
 * («Добавить», «Изменить», «Удалить»). Страница своя (адрес {@link #HOME}); действия — переходы на
 * {@link #ACTION}?do=…, которые клиент выполняет, только если они пришли с этой страницы.
 * Отрисовка рядов — assets/video_home.js. Чистая логика без Android — проверяется тестом.
 */
final class VideoSitesPage {
	static final String HOST = "jarvis-video.local";
	static final String HOME = "https://" + HOST + "/";
	static final String ACTION = HOME + "act";
	static final String RYADY = HOME + "ryady.json";
	static final String POISK = HOME + "poisk.json";
	static final String SCRIPT = HOME + "home.js";
	static final String FON = HOME + "fon.jpg";
	static final String ZONA_DEFAULT = "https://w1.zona.im/";
	static final String LIFT_DEFAULT = "https://liftw.ws/";
	static final String YOUTUBE = "https://m.youtube.com/";

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
		return host(url).replaceFirst("(?i)^www\\.", "");
	}

	@NonNull
	static String host(@NonNull String url) {
		String h = url.replaceFirst("(?i)^https?://", "");
		int i = h.indexOf('/');
		if (i >= 0) h = h.substring(0, i);
		i = h.indexOf('?');
		if (i >= 0) h = h.substring(0, i);
		return h.toLowerCase(Locale.ROOT);
	}

	/** Сохранённый сайт пользователя, в имени сайта которого есть word («zona», «lift»); нет — null. */
	@Nullable
	static String savedSite(@NonNull Map<String, String> sites, @NonNull String word) {
		for (String u : sites.keySet()) {
			if (host(u).contains(word)) return u;
		}
		return null;
	}

	/** HTML без ряда «Продолжить». */
	@NonNull
	static String html(@NonNull Map<String, String> sites, @Nullable String editUrl) {
		return html(sites, editUrl, Collections.emptyList());
	}

	/** HTML главной страницы. sites — адрес → название, по порядку добавления. */
	@NonNull
	static String html(@NonNull Map<String, String> sites, @Nullable String editUrl,
										 @NonNull List<VideoContinue.Entry> cont) {
		StringBuilder b = new StringBuilder(8192);
		b.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
				.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
				.append("<title>Видео</title><style>").append(CSS).append("</style></head><body>")
				.append("<div id=\"bg\"></div><div id=\"page\">")
				.append("<header id=\"hero\"><img id=\"hp\" alt=\"\" src=\"\" referrerpolicy=\"no-referrer\"><div class=\"hi\"><div id=\"hn\">Видео</div>")
				.append("<div id=\"hm\"></div><div class=\"hb\"><a id=\"hw\" class=\"btn off\" href=\"#\">▶ Смотреть</a>")
				.append("<form id=\"sf\"><input id=\"q\" type=\"search\" placeholder=\"Поиск фильма или сериала\">")
				.append("<button type=\"submit\">Найти</button></form></div></div></header>");

		// Строка сервисов (решение пользователя: только Zona, Lift, YouTube)
		String zona = savedSite(sites, "zona");
		String lift = savedSite(sites, "lift");
		b.append("<nav class=\"svc\">");
		service(b, (zona != null) ? zona : ZONA_DEFAULT, "Zona");
		service(b, (lift != null) ? lift : LIFT_DEFAULT, "Lift");
		service(b, YOUTUBE, "YouTube");
		b.append("</nav><div id=\"msg\"></div><div id=\"rows\"></div>");

		// «Мои сайты»
		b.append("<section id=\"my\"><h2>Мои сайты</h2>");
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
		b.append("<form id=\"edit\" method=\"get\" action=\"").append(attr(ACTION)).append("\">")
				.append("<input type=\"hidden\" name=\"do\" value=\"").append(edit ? "save" : "add").append("\">")
				.append("<input type=\"hidden\" name=\"old\" value=\"").append(attr(url)).append("\">")
				.append("<div>").append(edit ? "Изменить сайт" : "Добавить сайт").append("</div>")
				.append("<input name=\"name\" placeholder=\"Название\" value=\"").append(attr(name)).append("\">")
				.append("<input name=\"url\" placeholder=\"Адрес сайта\" inputmode=\"url\" value=\"")
				.append(attr(url)).append("\">")
				.append("<button type=\"submit\">Сохранить</button></form></section></div>");

		b.append("<script>var D={act:").append(js(ACTION)).append(",edit:").append(edit).append(",cont:[");
		for (int i = 0; i < cont.size(); i++) {
			VideoContinue.Entry e = cont.get(i);
			if (i > 0) b.append(',');
			b.append("{z:").append(js(e.url)).append(",n:").append(js(e.name)).append(",a:").append(js(e.poster))
					.append(",g:").append(e.year).append(",r:").append(String.format(Locale.US, "%.1f", e.rating))
					.append(",s:").append(e.serial).append(",p:").append(progress(e)).append('}');
		}
		b.append("]};</script><script src=\"").append(attr(SCRIPT)).append("\"></script></body></html>");
		return b.toString();
	}

	/** Ответ воркера похож на афишу (объект с полем key-массивом) — его можно отдать странице и хранить. */
	static boolean isAfisha(@Nullable String s, @NonNull String key) {
		if (s == null) return false;
		String t = s.trim();
		return t.startsWith("{") && t.endsWith("}") && t.contains("\"" + key + "\":[");
	}

	/** Сколько просмотрено, 0…100 (для полоски на афише). */
	static int progress(@NonNull VideoContinue.Entry e) {
		if (!(e.duration > 0) || !(e.time > 0)) return 0;
		return (int) Math.max(0, Math.min(100, Math.round(e.time * 100 / e.duration)));
	}

	private static void service(StringBuilder b, String url, String name) {
		b.append("<a class=\"sv\" href=\"").append(attr(url)).append("\">").append(text(name)).append("</a>");
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

	/** Строка JavaScript в кавычках; «</» и разделители строк экранируются — не закроют тег script. */
	@NonNull
	static String js(@NonNull String s) {
		StringBuilder b = new StringBuilder(s.length() + 2).append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '<' -> b.append("\\u003c");
				case '>' -> b.append("\\u003e");
				case '&' -> b.append("\\u0026");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case ' ' -> b.append("\\u2028");
				case ' ' -> b.append("\\u2029");
				default -> {
					if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
					else b.append(c);
				}
			}
		}
		return b.append('"').toString();
	}

	private static String text(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String attr(String s) {
		return text(s).replace("\"", "&quot;").replace("'", "&#39;");
	}

	// Цвета и затемнение — как у «Дом ТВ» (GlavnayaActivity: градиент rgb(8,12,20) 60→150→235, акцент 255,179,107)
	private static final String CSS =
			"*{box-sizing:border-box}html,body{margin:0;height:100%}" +
					"body{background:#080c14;color:#fff;font:17px sans-serif;-webkit-tap-highlight-color:transparent}" +
					"#bg{position:fixed;inset:0;background:url(/fon.jpg) center/cover no-repeat #080c14;z-index:-2}" +
					"#bg:after{content:'';position:absolute;inset:0;background:linear-gradient(rgba(8,12,20,.45)," +
					"rgba(8,12,20,.72) 45%,rgba(8,12,20,.93))}" +
					"#page{padding:0 14px 40px}" +
					"#hero{position:sticky;top:0;z-index:5;display:flex;gap:16px;align-items:flex-end;margin:0 -14px;padding:10px 18px;" +
					"background:rgba(8,12,20,.95);box-shadow:0 8px 16px rgba(8,12,20,.6)}" +
					"#hp{width:100px;height:146px;object-fit:cover;border-radius:10px;background:rgba(30,37,48,.85);" +
					"box-shadow:0 6px 18px rgba(0,0,0,.6);flex:none}#hp[src=''],#hp.bad{visibility:hidden}" +
					".hi{flex:1;min-width:0}#hn{font-size:28px;font-weight:bold;text-shadow:0 2px 6px rgba(0,0,0,.8);" +
					"white-space:nowrap;overflow:hidden;text-overflow:ellipsis}" +
					"#hm{color:rgba(255,255,255,.85);font-size:16px;margin:6px 0 10px;min-height:20px}" +
					"#hm .st{color:#ffb36b}#hm .tag{border:1px solid rgba(255,255,255,.5);border-radius:6px;" +
					"padding:1px 7px;margin-left:8px;font-size:13px}" +
					".hb{display:flex;gap:10px;flex-wrap:wrap;align-items:center}" +
					".btn{display:inline-block;background:rgba(255,255,255,.94);color:#0f1218;text-decoration:none;" +
					"font-weight:bold;font-size:18px;padding:10px 22px;border-radius:24px}.btn.off{opacity:.4}" +
					"#sf{display:flex;gap:6px;flex:1;min-width:220px;max-width:460px}" +
					"#sf input{flex:1;min-width:0;font-size:17px;padding:9px 14px;border-radius:22px;border:1px solid " +
					"rgba(255,255,255,.35);background:rgba(8,12,20,.6);color:#fff}" +
					"#sf button,#edit button{font-size:17px;padding:9px 16px;border-radius:22px;border:0;background:#ffb36b;color:#111}" +
					".svc{display:flex;gap:10px;margin:4px 0 6px;flex-wrap:wrap}" +
					".sv{color:#fff;text-decoration:none;font-size:17px;padding:8px 18px;border-radius:12px;" +
					"background:rgba(8,12,20,.55);border:1px solid rgba(255,255,255,.25)}" +
					"#msg{color:rgba(255,255,255,.75);font-size:15px;margin:6px 2px}" +
					"h2{font-size:14px;letter-spacing:.06em;text-transform:uppercase;color:rgba(255,255,255,.7);margin:16px 2px 8px}" +
					".row{display:flex;gap:12px;overflow-x:auto;padding:4px 2px 8px;scroll-snap-type:x proximity;" +
					"scrollbar-width:none}.row::-webkit-scrollbar{display:none}" +
					".card{flex:none;width:122px;color:#fff;text-decoration:none;scroll-snap-align:start}" +
					".card .ph{position:relative;width:122px;height:178px;border-radius:8px;overflow:hidden;" +
					"background:rgba(30,37,48,.85);border:3px solid transparent}" +
					".card.sel .ph{border-color:#ffb36b}.card img{width:100%;height:100%;object-fit:cover;display:block}" +
					".card .nm{font-size:14px;margin-top:5px;color:rgba(255,255,255,.85);white-space:nowrap;overflow:hidden;" +
					"text-overflow:ellipsis}.card .pr{position:absolute;left:0;bottom:0;height:5px;background:#ffb36b}" +
					".card .ni{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;" +
					"text-align:center;padding:8px;font-size:14px;color:rgba(255,255,255,.75)}" +
					"#my{margin-top:8px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px}" +
					".site{background:rgba(27,34,44,.82);border:1px solid rgba(255,255,255,.12);border-radius:14px;padding:12px 14px}" +
					".open{display:block;color:#fff;text-decoration:none;font-size:21px;padding:6px 0}" +
					".url{color:#9aa4b1;font-size:13px;word-break:break-all}" +
					".acts a{color:#ffb36b;font-size:15px;margin-right:18px;text-decoration:none}" +
					"#edit{background:rgba(27,34,44,.82);border-radius:14px;padding:14px;margin-top:14px}" +
					"#edit input{width:100%;font-size:17px;padding:10px;margin:6px 0;border-radius:8px;" +
					"border:1px solid #444;background:rgba(8,12,20,.8);color:#fff}" +
					".empty{color:#9aa4b1}";
}
