package me.aap.fermata.addon.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Адреса вкладки «Заправка» (пожелание 04.11.2026: «прикрутить приложение из Telegram по заправке, чтобы
 * открывать его с вкладки и заправляться через экран авто»).
 *
 * Мини-приложение Telegram вне Telegram не работает (вход — данными Telegram), поэтому вкладка открывает
 * веб-Telegram (web.telegram.org/k), а в нём — ссылку пользователя. Ссылка передаётся параметром
 * {@code #?tgaddr=}: веб-Telegram открывает его как ссылку из сообщения (исходники morethanwords/tweb,
 * {@code appImManager.onHashChangeUnsafe} → {@code openUrl}; ссылки {@code t.me/<бот>/<приложение>?startapp=}
 * и {@code tg://resolve?domain=…&appname=…} разбирает {@code internalLinkProcessor}). Проверено по исходникам
 * 04.11.2026; в живом веб-Telegram из облака не проверялось.
 *
 * Чистая Java без Android — проверяется на JVM ({@code ZapravkaLinksTest}).
 */
public final class ZapravkaLinks {
	/** Веб-Telegram (версия K). */
	public static final String WEB_TELEGRAM = "https://web.telegram.org/k/";

	private static final Pattern USERNAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{3,31}");

	private ZapravkaLinks() {
	}

	/**
	 * Ссылка, введённая пользователем, — в адрес t.me или tg:// для веб-Telegram.
	 * Принимает {@code t.me/bot}, {@code https://t.me/bot/app?startapp=x}, {@code telegram.me/…}, {@code @bot},
	 * {@code bot}, {@code tg://resolve?…}. Пусто или непонятно — {@code null}.
	 */
	@Nullable
	public static String normalize(@Nullable String input) {
		if (input == null) return null;
		String s = input.trim();
		if (s.isEmpty()) return null;
		String low = s.toLowerCase(Locale.ROOT);
		if (low.startsWith("tg://")) return s;
		if (s.startsWith("@")) s = s.substring(1);
		if (USERNAME.matcher(s).matches()) return "https://t.me/" + s;
		if (low.startsWith("http://")) s = s.substring(7);
		else if (low.startsWith("https://")) s = s.substring(8);
		low = s.toLowerCase(Locale.ROOT);
		String rest;
		if (low.startsWith("t.me/")) rest = s.substring(5);
		else if (low.startsWith("www.t.me/")) rest = s.substring(9);
		else if (low.startsWith("telegram.me/")) rest = s.substring(12);
		else if (low.startsWith("telegram.dog/")) rest = s.substring(13);
		else return null;
		if (rest.isEmpty()) return null;
		int end = rest.length();
		for (char c : new char[]{'/', '?', '#'}) {
			int i = rest.indexOf(c);
			if (i >= 0 && i < end) end = i;
		}
		if (!USERNAME.matcher(rest.substring(0, end)).matches()) return null;
		return "https://t.me/" + rest;
	}

	/** Адрес для вкладки: веб-Telegram, открывающий ссылку; без ссылки — просто веб-Telegram (список чатов). */
	@NonNull
	public static String startUrl(@Nullable String input) {
		String link = normalize(input);
		if (link == null) return WEB_TELEGRAM;
		try {
			return WEB_TELEGRAM + "#?tgaddr=" + URLEncoder.encode(link, "UTF-8").replace("+", "%20");
		} catch (UnsupportedEncodingException e) {
			return WEB_TELEGRAM;
		}
	}

	/** Страница Telegram — остаётся во вкладке (веб-Telegram, вход, t.me). */
	public static boolean isTelegramHost(@Nullable String host) {
		if (host == null) return false;
		String h = host.toLowerCase(Locale.ROOT);
		return h.equals("telegram.org") || h.endsWith(".telegram.org") || h.equals("t.me")
				|| h.endsWith(".t.me");
	}

	/**
	 * Своя вкладка А-100 (05.11.2026: «свою вкладку для заправки А-100, чтобы быстрее грузилось») — страница из
	 * assets, раздаётся по https-адресу WebView ({@link #FAST_HOST}); сервер А-100 разрешает запросы с любого
	 * адреса (CORS «*», проверено 05.11.2026). Прежний путь через Telegram — параметр {@code tg}.
	 */
	public static final String FAST_HOST = "appassets.androidplatform.net";
	static final String FAST_PREFIX = "/zp/";
	private static final String[] FAST_FILES = {"zapravka_a100.html", "zapravka_fon.jpg", "zapravka_logo.png",
			"zapravka_klever.png"};

	public static String fastUrl(@Nullable String input) {
		try {
			return "https://" + FAST_HOST + FAST_PREFIX + "zapravka_a100.html?tg=" +
					URLEncoder.encode(startUrl(input), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			return "https://" + FAST_HOST + FAST_PREFIX + "zapravka_a100.html";
		}
	}

	/** Путь запроса к своей вкладке → имя файла в assets; чужие пути — {@code null} (отдаются только эти 4 файла). */
	@Nullable
	public static String fastAsset(@Nullable String host, @Nullable String path) {
		if (!FAST_HOST.equals(host) || (path == null) || !path.startsWith(FAST_PREFIX)) return null;
		String name = path.substring(FAST_PREFIX.length());
		for (String f : FAST_FILES) if (f.equals(name)) return f;
		return null;
	}

	@NonNull
	public static String mimeOf(@NonNull String name) {
		if (name.endsWith(".html")) return "text/html";
		if (name.endsWith(".jpg")) return "image/jpeg";
		if (name.endsWith(".png")) return "image/png";
		return "application/octet-stream";
	}

	/**
	 * Возврат со страницы банка (оплата новой картой, 05.11.2026). Приложение А-100 после WebPay уходит на свои
	 * страницы {@code /pay-success}, {@code /success-tma-redirect}, {@code /pay-fail} (код А-100: PATH) или в бота
	 * {@code t.me/A100_app_bot?startapp=page_fail}. Возвращает «success», «fail» или {@code null} — не возврат.
	 * Адреса проверены по коду приложения; на живой оплате не проверялись.
	 */
	@Nullable
	public static String payResult(@Nullable String host, @Nullable String path, @Nullable String query) {
		if (host == null) return null;
		String p = path == null ? "" : path;
		String q = query == null ? "" : query;
		if ("tgminiapp.a-100.by".equalsIgnoreCase(host)) {
			if (p.startsWith("/pay-success") || p.startsWith("/success-tma-redirect")) return "success";
			if (p.startsWith("/pay-fail")) return "fail";
			return null;
		}
		if ("t.me".equalsIgnoreCase(host) && p.toLowerCase(Locale.ROOT).startsWith("/a100_app_bot")) {
			if (q.contains("startapp=page_fail")) return "fail";
			if (q.contains("startapp=page_success")) return "success";
		}
		return null;
	}

	/** Своя вкладка с результатом оплаты. */
	public static String fastUrlPaid(@Nullable String input, @NonNull String result) {
		return fastUrl(input) + "&paid=" + ("success".equals(result) ? "success" : "fail");
	}
}
