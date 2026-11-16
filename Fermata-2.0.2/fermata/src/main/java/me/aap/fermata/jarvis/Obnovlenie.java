package me.aap.fermata.jarvis;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Обновление JARVIS Auto кнопкой «Обновить приложение» (16.11.2026, по образцу «Домашки»; сборка жены и сборка
 * пользователя): сборка лежит в Cloudflare (KV + воркер jarvis-zhena-vypusk, адрес с неугадываемой частью), без ключа
 * GitHub. Описание выпуска — {@code <имя>.json}: {"nomer": 270, "fajl": "jarvis-zhena-270.apk", "razmer": 68403456},
 * имя — {@link #ZHENA} или {@link #AUTO}; nomer — versionCode.
 *
 * Ставится сборка только через KingInstaller (способ «Shizuku»): иначе в записи об установке не будет Google Play и
 * Android Auto приложение в машину не пустит. Чистая логика без Android — проверяется тестом.
 */
public final class Obnovlenie {
	/** Пакет KingInstaller и его окно установки (тот же вызов, что в qwen/vypusk/ustanovit-na-telefon.sh). */
	public static final String KING_PKG = "com.example.kinginstaller";
	public static final String KING_ACTIVITY = KING_PKG + ".ApkHandler";
	/** Имена выпусков: сборка жены и сборка пользователя (с 16.11.2026 обе обновляются кнопкой). */
	public static final String ZHENA = "jarvis-zhena";
	public static final String AUTO = "jarvis-auto";

	private static final Pattern NOMER = Pattern.compile("\"nomer\"\\s*:\\s*(\\d{1,9})");
	private static final Pattern FAJL = Pattern.compile("\"fajl\"\\s*:\\s*\"([a-z-]{1,32}-\\d{1,9}\\.apk)\"");
	private static final Pattern RAZMER = Pattern.compile("\"razmer\"\\s*:\\s*(\\d{1,12})");

	/** Выпуск: номер сборки (= versionCode), имя файла, размер в байтах. */
	public static final class Vypusk {
		public final int nomer;
		@NonNull
		public final String fajl;
		public final long razmer;

		Vypusk(int nomer, @NonNull String fajl, long razmer) {
			this.nomer = nomer;
			this.fajl = fajl;
			this.razmer = razmer;
		}
	}

	private Obnovlenie() {
	}

	/** Описание выпуска {@code imya}: «jarvis-zhena.json». */
	@NonNull
	public static String manifest(@NonNull String imya) {
		return imya + ".json";
	}

	/** Разобрать описание выпуска imya; null — пусто, не то, чужое приложение или номер в имени файла не совпадает. */
	@Nullable
	public static Vypusk parse(@Nullable String json, @NonNull String imya) {
		if (json == null) return null;
		Matcher n = NOMER.matcher(json), f = FAJL.matcher(json), r = RAZMER.matcher(json);
		if (!n.find() || !f.find() || !r.find()) return null;
		int nomer = Integer.parseInt(n.group(1));
		String fajl = f.group(1);
		if (!fajl.equals(imya + "-" + nomer + ".apk")) return null;
		long razmer = Long.parseLong(r.group(1));
		if ((nomer <= 0) || (razmer <= 0)) return null;
		return new Vypusk(nomer, fajl, razmer);
	}

	/** Выпуск новее установленной сборки. */
	public static boolean novee(@Nullable Vypusk v, int installed) {
		return (v != null) && (v.nomer > installed);
	}

	/** Адрес файла: база без «/» в конце + имя. */
	@NonNull
	public static String url(@NonNull String base, @NonNull String name) {
		String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
		return b + "/" + name;
	}
}
