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
				if (n.contains("ворот") && hasOnOff(d)) return new Target(false, d.optString("id"), d.optString("name"));
			}
		}
		return null;
	}

	/** Что сделать в Умном доме по фразе. */
	static final class Action {
		final Target target;
		/** Для устройства: включить (true) или выключить; для сценария не важно. */
		final boolean on;

		Action(Target target, boolean on) {
			this.target = target;
			this.on = on;
		}
	}

	private static final Pattern ON = Pattern.compile("^(включи(ть)?|зажги|запусти(ть)?|активируй)\\s+(.+)$");
	private static final Pattern OFF = Pattern.compile("^(выключи(ть)?|отключи(ть)?|погаси(ть)?)\\s+(.+)$");
	private static final Pattern SCENARIO = Pattern.compile("^(запусти(ть)?|включи(ть)?|выполни(ть)?)\\s+сценари[йя]\\s+(.+)$");

	/**
	 * Команда Умному дому по фразе и ответу v1.0/user/info, или null (тогда
	 * фраза идёт дальше: плеер, команды телефона, Qwen). Сценарий — по названию
	 * («запусти сценарий Утро» или само название); устройство — «включи/выключи»
	 * и его название, можно с комнатой («включи свет в гараже»). Только при
	 * однозначном совпадении: «включи музыку» не трогает чужого.
	 */
	@VisibleForTesting
	@Nullable
	static Action match(@Nullable String phrase, @Nullable JSONObject info) {
		if ((phrase == null) || (info == null)) return null;
		String p = norm(phrase).replaceFirst("^джарвис\\s*,?\\s*", "");
		java.util.regex.Matcher m = SCENARIO.matcher(p);
		if (m.matches()) {
			Target t = scenarioByName(info, m.group(5));
			return (t == null) ? null : new Action(t, true);
		}
		Target exact = scenarioByName(info, p);
		if (exact != null) return new Action(exact, true);
		boolean on;
		String rest;
		if ((m = ON.matcher(p)).matches()) {
			on = true;
			rest = m.group(4);
		} else if ((m = OFF.matcher(p)).matches()) {
			on = false;
			rest = m.group(5);
		} else {
			return null;
		}
		Target d = deviceByName(info, rest);
		return (d == null) ? null : new Action(d, on);
	}

	/** Похоже ли на команду Умному дому — стоит ли спросить его состав, если он ещё не загружен. */
	static boolean looksLike(@Nullable String phrase) {
		if (phrase == null) return false;
		String p = norm(phrase).replaceFirst("^джарвис\\s*,?\\s*", "");
		return SCENARIO.matcher(p).matches() || ON.matcher(p).matches() || OFF.matcher(p).matches();
	}

	private static String norm(String s) {
		return s.trim().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[.!?,]+$", "")
				.replaceAll("\\s+", " ");
	}

	/** Основа слова для сравнения падежей: «гараже» ~ «гараж», «лампу» ~ «лампа». */
	private static String stem(String w) {
		return (w.length() > 4) ? w.substring(0, w.length() - 2) : (w.length() > 3) ? w.substring(0, 3) : w;
	}

	/** Все ли слова названия есть во фразе (с точностью до окончаний). */
	private static boolean covers(String phrase, String name) {
		String[] pw = phrase.split(" ");
		for (String nw : norm(name).split(" ")) {
			if (nw.isEmpty()) continue;
			String ns = stem(nw);
			boolean found = false;
			for (String w : pw) {
				// Короткие слова фразы («в», «на») не считать началом названия
				if (w.startsWith(ns) || ((w.length() > 3) && nw.startsWith(stem(w)))) {
					found = true;
					break;
				}
			}
			if (!found) return false;
		}
		return true;
	}

	@Nullable
	private static Target scenarioByName(JSONObject info, String phrase) {
		JSONArray sc = info.optJSONArray("scenarios");
		if (sc == null) return null;
		String p = norm(phrase);
		for (int i = 0; i < sc.length(); i++) {
			JSONObject s = sc.optJSONObject(i);
			if ((s == null) || !s.optBoolean("is_active", true)) continue;
			String n = norm(s.optString("name"));
			if (!n.isEmpty() && (n.equals(p) || (covers(p, n) && covers(n, p)))) {
				return new Target(true, s.optString("id"), s.optString("name"));
			}
		}
		return null;
	}

	/** Устройство с переключателем, чьё название (и комната, если сказана) есть во фразе. */
	@Nullable
	private static Target deviceByName(JSONObject info, String rest) {
		JSONArray dv = info.optJSONArray("devices");
		if (dv == null) return null;
		java.util.Map<String, String> rooms = new java.util.HashMap<>();
		JSONArray rs = info.optJSONArray("rooms");
		if (rs != null) for (int i = 0; i < rs.length(); i++) {
			JSONObject r = rs.optJSONObject(i);
			if (r != null) rooms.put(r.optString("id"), r.optString("name"));
		}
		String p = norm(rest);
		java.util.List<JSONObject> hits = new java.util.ArrayList<>();
		for (int i = 0; i < dv.length(); i++) {
			JSONObject d = dv.optJSONObject(i);
			if ((d == null) || !hasOnOff(d)) continue;
			if (covers(p, d.optString("name"))) hits.add(d);
		}
		if (hits.size() > 1) {
			// Несколько «Свет» — выбрать по комнате из фразы
			java.util.List<JSONObject> inRoom = new java.util.ArrayList<>();
			for (JSONObject d : hits) {
				String room = rooms.get(d.optString("room"));
				if ((room != null) && !room.isEmpty() && covers(p, room)) inRoom.add(d);
			}
			if (!inRoom.isEmpty()) hits = inRoom;
		}
		if (hits.size() > 1) {
			// «Свет» и «Светильник» на «включи светильник» — название целиком словами фразы
			java.util.List<JSONObject> whole = new java.util.ArrayList<>();
			for (JSONObject d : hits) {
				if ((" " + p + " ").contains(" " + norm(d.optString("name")) + " ")) whole.add(d);
			}
			if (whole.size() == 1) hits = whole;
		}
		if (hits.size() != 1) return null;
		JSONObject d = hits.get(0);
		return new Target(false, d.optString("id"), d.optString("name"));
	}

	private static boolean hasOnOff(JSONObject d) {
		JSONArray caps = d.optJSONArray("capabilities");
		if (caps == null) return true; // в ответе без умений — не отсеивать
		for (int i = 0; i < caps.length(); i++) {
			JSONObject c = caps.optJSONObject(i);
			if ((c != null) && "devices.capabilities.on_off".equals(c.optString("type"))) return true;
		}
		return false;
	}

	/** Умный дом пользователя (v1.0/user/info). Не в главном потоке. */
	static JSONObject info(@NonNull String token) throws Exception {
		return new JSONObject(request("GET", "user/info", token, null));
	}

	/** Выполнить команду; результат — что сказать. Не в главном потоке. */
	static String run(@NonNull String token, @NonNull Action a) throws Exception {
		Target t = a.target;
		me.aap.utils.log.Log.i("JARVIS home: ", t.scenario ? "scenario «" : "device «", t.name, "» ",
				t.scenario ? "" : (a.on ? "on" : "off"));
		JSONObject r;
		if (t.scenario) {
			r = new JSONObject(request("POST", "scenarios/" + t.id + "/actions", token, null));
		} else {
			JSONObject state = new JSONObject().put("instance", "on").put("value", a.on);
			JSONObject action = new JSONObject().put("type", "devices.capabilities.on_off").put("state", state);
			JSONObject dev = new JSONObject().put("id", t.id).put("actions", new JSONArray().put(action));
			JSONObject body = new JSONObject().put("devices", new JSONArray().put(dev));
			r = new JSONObject(request("POST", "devices/actions", token, body.toString()));
		}
		me.aap.utils.log.Log.i("JARVIS home: result ", r.optString("status"), " ", r.optString("message"));
		if (!succeeded(r)) return "Умный дом не выполнил команду: " + r.optString("message", "ошибка");
		if (t.scenario) return "Запускаю «" + t.name + "»";
		return (a.on ? "Включаю " : "Выключаю ") + t.name;
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
		me.aap.utils.log.Log.i("JARVIS gate: result ", r.optString("status"), " ", r.optString("message"));
		if (!succeeded(r)) return "Умный дом не выполнил команду: " + r.optString("message", "ошибка");
		// Устройство (блок с импульсом) не знает направления: тот же импульс, что у кнопки пульта
		if (!t.scenario) return "Подаю импульс воротам";
		return open ? "Открываю ворота" : "Закрываю ворота";
	}

	/**
	 * Выполнено ли: верхний «status»: «ok» либо у каждого устройства
	 * action_result.status «DONE» (формат ответа devices/actions живым
	 * обменом не сверен — принимаем оба признака).
	 */
	@VisibleForTesting
	static boolean succeeded(@NonNull JSONObject r) {
		if ("ok".equals(r.optString("status"))) return true;
		JSONArray dv = r.optJSONArray("devices");
		if (dv == null) return false;
		int n = 0;
		for (int i = 0; i < dv.length(); i++) {
			JSONObject d = dv.optJSONObject(i);
			JSONArray caps = (d == null) ? null : d.optJSONArray("capabilities");
			if (caps == null) continue;
			for (int j = 0; j < caps.length(); j++) {
				JSONObject c = caps.optJSONObject(j);
				JSONObject st = (c == null) ? null : c.optJSONObject("state");
				JSONObject ar = (st == null) ? null : st.optJSONObject("action_result");
				if (ar == null) continue;
				if (!"DONE".equals(ar.optString("status"))) return false;
				n++;
			}
		}
		return n > 0;
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
			c.setConnectTimeout(5_000);
			c.setReadTimeout(10_000);
			c.setRequestProperty("Authorization", "Bearer " + token);
			if (body != null) {
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				try (OutputStream o = c.getOutputStream()) {
					o.write(body.getBytes(UTF_8));
				}
			}
			int code = c.getResponseCode();
			// 401 — вход недействителен; 403 (нет права) — обычная ошибка: вход не стирать
			if (code == 401) throw new AuthException("HTTP " + code);
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
