package me.aap.fermata.addon.jarvis;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Температура воздуха в левом верхнем углу вкладки со сферой (пожелание 14.11.2026, карточка
 * android-team/tasks/2026-11-14-auto-temperatura-vidzhet). Чистая Java без Android — проверяется тестами:
 * адреса запросов, разбор ответов, вид надписи, когда обновлять и когда значение устарело.
 *
 * Температура — Open-Meteo (без ключа), название населённого пункта — OpenStreetMap (Nominatim) по координатам.
 */
public final class Termometr {
	/** Обновлять температуру не чаще раза в 15 минут. */
	public static final long REFRESH_MS = 15 * 60_000L;
	/** Значение старше часа не показывается — «—°». */
	public static final long STALE_MS = 60 * 60_000L;
	/** Переезд дальше километра — название запрашивается заново. */
	public static final double MOVED_M = 1000.0;
	/** Пусто — значения нет. */
	public static final String NO_TEMP = "—°";
	public static final String CAPTION = "за бортом";

	private static final Pattern TEMP = Pattern.compile(
			"\"current\"\\s*:\\s*\\{[^}]*?\"temperature_2m\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
	private static final Pattern ADDRESS = Pattern.compile("\"address\"\\s*:\\s*\\{([^}]*)\\}");
	/** Порядок важен: деревня раньше «города» — у деревень Nominatim пишет в city сельсовет. */
	private static final String[] PLACE_KEYS = {
			"village", "town", "hamlet", "isolated_dwelling", "city", "municipality", "county"};

	private Termometr() {
	}

	public static String weatherUrl(double lat, double lon) {
		return String.format(Locale.US,
				"https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m", lat, lon);
	}

	public static String placeUrl(double lat, double lon) {
		return String.format(Locale.US,
				"https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&accept-language=ru&lat=%.5f&lon=%.5f",
				lat, lon);
	}

	/** Температура из ответа Open-Meteo; null — ответа нет или он не о том (например, превышен предел). */
	@Nullable
	public static Double parseTemp(@Nullable String json) {
		if (json == null) return null;
		Matcher m = TEMP.matcher(json);
		if (!m.find()) return null;
		try {
			return Double.parseDouble(m.group(1));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Населённый пункт из ответа Nominatim: деревня — её имя, город — город (не район города), между
	 * населёнными пунктами — район. Сельсовет («…сельский Совет») названием не считается. Пусто — не определилось.
	 */
	@NonNull
	public static String parsePlace(@Nullable String json) {
		if (json == null) return "";
		Matcher a = ADDRESS.matcher(json);
		if (!a.find()) return "";
		String addr = a.group(1);
		for (String k : PLACE_KEYS) {
			String v = field(addr, k);
			if (v.isEmpty() || isSelsovet(v)) continue;
			return v;
		}
		return "";
	}

	private static boolean isSelsovet(String v) {
		String l = v.toLowerCase(Locale.ROOT);
		return l.contains("сельсовет") || l.contains("сельский совет") || l.contains("поссовет");
	}

	private static String field(String obj, String key) {
		Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
		return m.find() ? unescape(m.group(1)).trim() : "";
	}

	private static String unescape(String s) {
		if (s.indexOf('\\') < 0) return s;
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ((c != '\\') || (i + 1 >= s.length())) {
				b.append(c);
				continue;
			}
			char n = s.charAt(++i);
			if ((n == 'u') && (i + 4 < s.length())) {
				try {
					b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
					i += 4;
					continue;
				} catch (NumberFormatException ignore) {
				}
			}
			b.append(n);
		}
		return b.toString();
	}

	/** «−3°», «0°», «5°»: целое, настоящий знак минуса; null — «—°». */
	@NonNull
	public static String format(@Nullable Double t) {
		if ((t == null) || t.isNaN() || t.isInfinite()) return NO_TEMP;
		// Половина — от нуля в обе стороны: −3,5 → −4, как 3,5 → 4 (Math.round дал бы −3)
		long r = (t < 0) ? -Math.round(-t) : Math.round(t);
		return (r < 0) ? ("−" + (-r) + "°") : (r + "°");
	}

	/** Подпись под цифрой: «за бортом · Минск» или просто «за бортом». */
	@NonNull
	public static String caption(@Nullable String place) {
		return ((place == null) || place.trim().isEmpty()) ? CAPTION : (CAPTION + " · " + place.trim());
	}

	/** Что показать сейчас: сохранённое значение, если оно не старше часа, иначе «—°». */
	@NonNull
	public static String shown(@Nullable Double t, long savedAt, long now) {
		return ((savedAt <= 0L) || stale(savedAt, now)) ? NO_TEMP : format(t);
	}

	public static boolean stale(long savedAt, long now) {
		return (now - savedAt) > STALE_MS || (savedAt - now) > STALE_MS;
	}

	/** Пора ли запросить температуру заново (часы могли перевести назад — тогда тоже пора). */
	public static boolean due(long lastAt, long now) {
		return (lastAt <= 0L) || (now - lastAt) >= REFRESH_MS || (now < lastAt);
	}

	/** Телефон переехал дальше километра от места, для которого известно название. */
	public static boolean moved(double lat1, double lon1, double lat2, double lon2) {
		return distanceM(lat1, lon1, lat2, lon2) > MOVED_M;
	}

	static double distanceM(double lat1, double lon1, double lat2, double lon2) {
		double r = 6_371_000.0;
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) *
				Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
	}
}
