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
}
