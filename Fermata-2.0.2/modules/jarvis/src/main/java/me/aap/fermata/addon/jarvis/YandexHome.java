package me.aap.fermata.addon.jarvis;

import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Ворота через Умный дом Яндекса (пожелание 23.10.2026: «у меня это
 * интегрировано в умный дом от Яндекса»). Блок Sonoff уже добавлен туда через
 * eWeLink — JARVIS запускает его так же, как Алиса.
 *
 * API для пользовательских приложений (yandex.ru/dev/dialogs/smart-home,
 * «concepts/platform-protocol»): хост https://api.iot.yandex.net, заголовок
 * «Authorization: Bearer токен»; GET v1.0/user/info — умный дом пользователя
 * (права iot:view); POST v1.0/scenarios/{id}/actions — запуск сценария и
 * POST v1.0/devices/actions — включение устройства (iot:control).
 *
 * Что запускать: сценарий, в названии которого есть «ворот» и глагол
 * («Открыть ворота»); иначе любой сценарий с «ворот»; иначе устройство с
 * «ворот» в названии — его включение (блок с импульсом — «Inching» — сам
 * отпустит реле). Чистая логика выбора — {@link #pick}, проверяется тестом.
 */
final class YandexHome {
	private static final String API = "https://api.iot.yandex.net/v1.0/";

	/** «Открой ворота» и подобное: true — открыть, false — закрыть, null — не про ворота. */
	@Nullable
	static Boolean gateCommand(@Nullable String phrase) {
		if (phrase == null) return null;
		String p = phrase.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
		if (!GATE.matcher(p).find()) return null;
		if (OPEN.matcher(p).find()) return true;
		if (CLOSE.matcher(p).find()) return false;
		return null;
	}

	private static final Pattern GATE = Pattern.compile("(^|\\s)ворот");
	private static final Pattern OPEN = Pattern.compile("(^|\\s)(открой|открыть|откройте|отвори|открывай)(\\s|$)");
	private static final Pattern CLOSE = Pattern.compile("(^|\\s)(закрой|закрыть|закройте|затвори|закрывай)(\\s|$)");

	/** Что запустить: сценарий или устройство. */
	static final class Target {
		final boolean scenario;
		final String id;
		final String name;

		Target(boolean scenario, String id, String name) {
			this.scenario = scenario;
			this.id = id;
			this.name = name;
		}
	}

	/** Выбрать сценарий или устройство «ворот» из ответа v1.0/user/info. */
	@VisibleForTesting
	@Nullable
	static Target pick(@NonNull JSONObject info, boolean open) {
		String verb = open ? "откр" : "закр";
		JSONArray sc = info.optJSONArray("scenarios");
		Target any = null;
		if (sc != null) {
			for (int i = 0; i < sc.length(); i++) {
				JSONObject s = sc.optJSONObject(i);
				if (s == null) continue;
				String n = s.optString("name").toLowerCase(Locale.ROOT).replace('ё', 'е');
				if (!n.contains("ворот")) continue;
				if (n.contains(verb)) return new Target(true, s.optString("id"), s.optString("name"));
				// Сценарий с противоположным глаголом не берём: «Закрыть ворота» на «открой»
				if ((any == null) && !n.contains(open ? "закр" : "откр")) {
					any = new Target(true, s.optString("id"), s.optString("name"));
				}
			}
		}
		if (any != null) return any;
		JSONArray dv = info.optJSONArray("devices");
		if (dv != null) {
			for (int i = 0; i < dv.length(); i++) {
				JSONObject d = dv.optJSONObject(i);
				if (d == null) continue;
				String n = d.optString("name").toLowerCase(Locale.ROOT).replace('ё', 'е');
				if (n.contains("ворот")) return new Target(false, d.optString("id"), d.optString("name"));
			}
		}
		return null;
	}

	/** Токен из адреса, куда Яндекс вернул после входа: …#access_token=…&expires_in=… */
	@VisibleForTesting
	@Nullable
	static String tokenFromRedirect(@Nullable String url) {
		if (url == null) return null;
		int h = url.indexOf('#');
		if (h < 0) return null;
		for (String kv : url.substring(h + 1).split("&")) {
			if (kv.startsWith("access_token=")) {
				String t = kv.substring("access_token=".length());
				return t.isEmpty() ? null : t;
			}
		}
		return null;
	}

	/** Адрес входа в Яндекс ID для приложения пользователя (права iot:view, iot:control). */
	static String authorizeUrl(String clientId) {
		return "https://oauth.yandex.ru/authorize?response_type=token&client_id=" +
				android.net.Uri.encode(clientId.trim());
	}

	static final String REDIRECT = "https://oauth.yandex.ru/verification_code";

	/** Ворота: найти и запустить. Результат — что сказать. Не в главном потоке. */
	static String gate(@NonNull String token, boolean open) throws Exception {
		JSONObject info = new JSONObject(request("GET", "user/info", token, null));
		Target t = pick(info, open);
		if (t == null) {
			me.aap.utils.log.Log.i("JARVIS gate: no scenario or device with «ворот», scenarios ",
					count(info, "scenarios"), ", devices ", count(info, "devices"));
			return "В Умном доме Яндекса не нашёл ни сценария, ни устройства «Ворота»";
		}
		me.aap.utils.log.Log.i("JARVIS gate: ", t.scenario ? "scenario «" : "device «", t.name, "»");
		JSONObject r;
		if (t.scenario) {
			r = new JSONObject(request("POST", "scenarios/" + t.id + "/actions", token, null));
		} else {
			JSONObject state = new JSONObject().put("instance", "on").put("value", true);
			JSONObject action = new JSONObject().put("type", "devices.capabilities.on_off").put("state", state);
			JSONObject dev = new JSONObject().put("id", t.id).put("actions", new JSONArray().put(action));
			JSONObject body = new JSONObject().put("devices", new JSONArray().put(dev));
			r = new JSONObject(request("POST", "devices/actions", token, body.toString()));
		}
		boolean ok = "ok".equals(r.optString("status"));
		me.aap.utils.log.Log.i("JARVIS gate: result ", r.optString("status"), " ", r.optString("message"));
		if (!ok) return "Умный дом не выполнил команду: " + r.optString("message", "ошибка");
		return open ? "Открываю ворота" : "Закрываю ворота";
	}

	private static int count(JSONObject info, String key) {
		JSONArray a = info.optJSONArray(key);
		return (a == null) ? -1 : a.length();
	}

	/** Ответ сервера с ошибкой авторизации — нужен новый вход. */
	static final class AuthException extends Exception {
		AuthException(String m) {
			super(m);
		}
	}

	private static String request(String method, String path, String token, @Nullable String body)
			throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
		try {
			c.setRequestMethod(method);
			c.setConnectTimeout(10_000);
			c.setReadTimeout(15_000);
			c.setRequestProperty("Authorization", "Bearer " + token);
			if (body != null) {
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				try (OutputStream o = c.getOutputStream()) {
					o.write(body.getBytes(UTF_8));
				}
			}
			int code = c.getResponseCode();
			if ((code == 401) || (code == 403)) throw new AuthException("HTTP " + code);
			InputStream in = (code < 400) ? c.getInputStream() : c.getErrorStream();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (in != null) {
				try (InputStream i = in) {
					byte[] buf = new byte[8192];
					for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
				}
			}
			String s = out.toString(UTF_8.name());
			if (code >= 400) me.aap.utils.log.Log.i("JARVIS gate: HTTP ", code, " ", s);
			return s.isEmpty() ? "{}" : s;
		} finally {
			c.disconnect();
		}
	}
}
