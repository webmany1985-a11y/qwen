package me.aap.fermata.addon.jarvis;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Маршрут на работу», «поехали домой», «проложи маршрут до вокзала» — куда
 * ехать (пожелание 23.10.2026: «открыть Гугл карты с маршрутом до работы»).
 * Чистая логика, без Android.
 */
final class RoutePhrase {
	/** Работа и дом — особые цели: их адреса берутся из настроек вкладки. */
	static final String WORK = "work";
	static final String HOME = "home";

	private static final Pattern ROUTE = Pattern.compile(
			"^(джарвис[\\s,]+)?(проложи(те)?\\s+)?(маршрут|дорогу|путь|навигаци[юя]|поехали|едем|поедем|вези|отвези|веди)" +
					"(\\s+(меня|нас))?(\\s+(на|до|в|во|к|ко)(?=\\s))?\\s*(?<dest>.*)$");
	private static final Pattern WORK_WORDS = Pattern.compile("^(работ[ауые]|офис|на работу|службу|службы)$");
	private static final Pattern HOME_WORDS = Pattern.compile("^(дом|домой|дому|дома)$");

	private RoutePhrase() {
	}

	/** Цель маршрута: {@link #WORK}, {@link #HOME}, адрес или место словами; null — не маршрут. */
	@Nullable
	static String destination(@Nullable String phrase) {
		if (phrase == null) return null;
		String p = phrase.trim().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[.!?]+$", "");
		Matcher m = ROUTE.matcher(p);
		if (!m.matches()) return null;
		String dest = m.group("dest").trim();
		if (dest.isEmpty()) return null;
		if (WORK_WORDS.matcher(dest).matches()) return WORK;
		if (HOME_WORDS.matcher(dest).matches()) return HOME;
		return dest;
	}
}
