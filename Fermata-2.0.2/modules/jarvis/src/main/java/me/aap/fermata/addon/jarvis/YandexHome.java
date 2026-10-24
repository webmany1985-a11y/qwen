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
		/** Группа устройств Яндекса («Свет на террасе» из нескольких ламп). */
		final boolean group;
		final String id;
		final String name;

		Target(boolean scenario, String id, String name) {
			this(scenario, false, id, name);
		}

		Target(boolean scenario, boolean group, String id, String name) {
			this.scenario = scenario;
			this.group = group;
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
		/** Канал, громкость, звук: тип умения и функция Яндекса; null — включение/выключение. */
		@Nullable
		final String capType;
		@Nullable
		final String instance;
		final double value;
		final boolean relative;
		/** Что сказать после выполнения. */
		@Nullable
		final String say;

		Action(Target target, boolean on) {
			this(target, on, null, null, 0, false, null);
		}

		Action(Target target, boolean on, @Nullable String capType, @Nullable String instance,
					 double value, boolean relative, @Nullable String say) {
			this.target = target;
			this.on = on;
			this.capType = capType;
			this.instance = instance;
			this.value = value;
			this.relative = relative;
			this.say = say;
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
		// Канал и громкость телевизора — раньше «включи …»: «включи пятый канал»
		Action tv = tvCommand(p, info);
		if (tv != null) return tv;
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
		if (d != null) return new Action(d, on);
		// «Включи свет на террасе» — сценарий «Свет на террасе» / «Выключить свет на террасе»
		Target sc = scenarioForRest(info, rest, on);
		return (sc == null) ? null : new Action(sc, on);
	}

	private static final Pattern VERB = Pattern.compile(
			"^(включи(ть)?|выключи(ть)?|отключи(ть)?|запусти(ть)?|погаси(ть)?|зажги|вкл|выкл)$");
	private static final Pattern OFF_NAME = Pattern.compile("(^|\\s)(выкл|отключ|погас)");

	/** Значимые слова: без глаголов и предлогов («на», «в»). */
	private static String content(String s) {
		StringBuilder b = new StringBuilder();
		for (String w : norm(s).split(" ")) {
			if ((w.length() <= 2) || VERB.matcher(w).matches()) continue;
			if (b.length() > 0) b.append(' ');
			b.append(w);
		}
		return b.toString();
	}

	/**
	 * Сценарий, чьё название без глаголов и предлогов — те же слова, что во
	 * фразе после «включи/выключи». «Выключи …» — только сценарий с «выкл»/
	 * «отключ» в названии; «включи …» — только без них.
	 */
	@Nullable
	private static Target scenarioForRest(JSONObject info, String rest, boolean on) {
		JSONArray sc = info.optJSONArray("scenarios");
		if (sc == null) return null;
		String r = content(rest);
		if (r.isEmpty()) return null;
		Target hit = null;
		for (int i = 0; i < sc.length(); i++) {
			JSONObject s = sc.optJSONObject(i);
			if ((s == null) || !s.optBoolean("is_active", true)) continue;
			String raw = norm(s.optString("name"));
			if (OFF_NAME.matcher(raw).find() == on) continue;
			String n = content(raw);
			if (n.isEmpty() || !covers(r, n) || !covers(n, r)) continue;
			if (hit != null) return null; // два подходящих — не угадывать
			hit = new Target(true, s.optString("id"), s.optString("name"));
		}
		return hit;
	}

	/** Названия устройств (с комнатами), групп и сценариев — в журнал, когда фраза не нашлась. */
	static String names(@Nullable JSONObject info) {
		if (info == null) return "-";
		java.util.Map<String, String> rooms = new java.util.HashMap<>();
		JSONArray rs = info.optJSONArray("rooms");
		if (rs != null) for (int i = 0; i < rs.length(); i++) {
			JSONObject r = rs.optJSONObject(i);
			if (r != null) rooms.put(r.optString("id"), r.optString("name"));
		}
		StringBuilder b = new StringBuilder();
		for (String key : new String[]{"devices", "groups", "scenarios"}) {
			JSONArray a = info.optJSONArray(key);
			b.append(key).append(": ");
			if (a != null) for (int i = 0; i < a.length(); i++) {
				JSONObject o = a.optJSONObject(i);
				if (o == null) continue;
				b.append('«').append(o.optString("name")).append('»');
				String room = rooms.get(o.optString("room"));
				if ((room != null) && !room.isEmpty()) b.append(" (").append(room).append(')');
				if (!key.equals("scenarios")) b.append(" [").append(caps(o)).append(']');
				b.append(i + 1 < a.length() ? ", " : "");
			}
			b.append("; ");
		}
		return b.toString();
	}

	/** Похоже ли на команду Умному дому — стоит ли спросить его состав, если он ещё не загружен. */
	static boolean looksLike(@Nullable String phrase) {
		if (phrase == null) return false;
		String p = norm(phrase).replaceFirst("^джарвис\\s*,?\\s*", "");
		return SCENARIO.matcher(p).matches() || ON.matcher(p).matches() || OFF.matcher(p).matches() ||
				TV_WORDS.matcher(p).find();
	}

	private static final Pattern TV_WORDS = Pattern.compile("(^|\\s)(канал|громче|погромче|тише|потише|громкость|звук)");

	// --- Телевизор: каналы и громкость (пожелание 24.10.2026) ---

	private static final String RANGE = "devices.capabilities.range";
	private static final String TOGGLE = "devices.capabilities.toggle";
	/** На сколько шагов «громче»/«тише» без числа: у ИК-пульта шаг — одно нажатие. */
	static final int VOLUME_STEP = 3;

	/**
	 * «Включи пятый канал», «переключи на 12 канал», «следующий канал»,
	 * «громче на телевизоре», «тише на 5», «выключи звук на телевизоре».
	 * Канал — у единственного телевизора с каналами можно не называть его;
	 * громкость и звук — только если назван телевизор или комната: «громче»
	 * без этого может быть о музыке. null — не команда телевизору.
	 */
	@Nullable
	private static Action tvCommand(String p, JSONObject info) {
		String[] w = p.split(" ");
		int ch = -1;
		for (int i = 0; i < w.length; i++) if (w[i].startsWith("канал")) ch = i;
		if (ch >= 0) {
			Integer rel = null;
			Integer num = null;
			for (String x : w) {
				if (x.matches("следующ\\p{L}*|вперед|вперёд|плюс|дальше")) rel = 1;
				else if (x.matches("предыдущ\\p{L}*|назад|минус|прошл\\p{L}*")) rel = -1;
			}
			if (ch > 0) num = number(w[ch - 1]);
			if ((num == null) && (ch + 1 < w.length)) {
				num = number(w[ch + 1]);
				if ((num == null) && w[ch + 1].equals("номер") && (ch + 2 < w.length)) num = number(w[ch + 2]);
			}
			if ((num == null) && (rel == null)) return null;
			JSONObject d = capDevice(info, p, RANGE, "channel", false);
			if (d == null) return null;
			Target t = target(d);
			if (rel != null) {
				return new Action(t, true, RANGE, "channel", rel, true,
						(rel > 0) ? "Следующий канал" : "Предыдущий канал");
			}
			if (!randomAccess(d, RANGE, "channel")) {
				return new Action(t, true, null, null, 0, false,
						"Телевизор в Умном доме переключает каналы только вперёд и назад");
			}
			return new Action(t, true, RANGE, "channel", num, false, "Включаю " + num + " канал");
		}
		boolean louder = p.matches(".*(^|\\s)(громче|погромче)(\\s|$).*");
		boolean quieter = p.matches(".*(^|\\s)(тише|потише)(\\s|$).*");
		if (louder || quieter) {
			JSONObject d = capDevice(info, p, RANGE, "volume", true);
			if (d == null) return null;
			Integer n = null;
			for (int i = 0; i + 1 < w.length; i++) if (w[i].equals("на")) n = (n != null) ? n : number(w[i + 1]);
			int step = (n != null) ? n : VOLUME_STEP;
			return new Action(target(d), true, RANGE, "volume", louder ? step : -step, true,
					louder ? "Громче" : "Тише");
		}
		if (p.matches(".*(^|\\s)громкость(\\s|$).*")) {
			Integer n = null;
			for (String x : w) if (n == null) n = number(x);
			if (n == null) return null;
			JSONObject d = capDevice(info, p, RANGE, "volume", true);
			if (d == null) return null;
			if (!randomAccess(d, RANGE, "volume")) {
				return new Action(target(d), true, null, null, 0, false,
						"Громкость у телевизора в Умном доме меняется только громче и тише");
			}
			return new Action(target(d), true, RANGE, "volume", n, false, "Громкость " + n);
		}
		boolean muteOff = p.matches("^(выключи(ть)?|отключи(ть)?|убери)\\s+звук.*") || p.contains("без звука");
		boolean muteOn = p.matches("^(включи(ть)?|верни)\\s+звук.*");
		if (muteOff || muteOn) {
			JSONObject d = capDevice(info, p, TOGGLE, "mute", true);
			if (d == null) return null;
			return new Action(target(d), true, TOGGLE, "mute", muteOff ? 1 : 0, false,
					muteOff ? "Выключаю звук" : "Включаю звук");
		}
		return null;
	}

	private static Target target(JSONObject d) {
		return new Target(false, d.optString("id"), d.optString("name"));
	}

	@Nullable
	private static JSONObject capability(JSONObject d, String type, String instance) {
		JSONArray caps = d.optJSONArray("capabilities");
		if (caps == null) return null;
		for (int i = 0; i < caps.length(); i++) {
			JSONObject c = caps.optJSONObject(i);
			if ((c == null) || !type.equals(c.optString("type"))) continue;
			JSONObject pr = c.optJSONObject("parameters");
			if ((pr != null) && instance.equals(pr.optString("instance"))) return c;
		}
		return null;
	}

	/** Можно ли задать значение сразу (канал 5), а не только шагами — у ИК-пульта бывает только шагами. */
	private static boolean randomAccess(JSONObject d, String type, String instance) {
		JSONObject c = capability(d, type, instance);
		JSONObject pr = (c == null) ? null : c.optJSONObject("parameters");
		return (pr == null) || pr.optBoolean("random_access", true);
	}

	/**
	 * Устройство с умением: названное во фразе (по названию или комнате), иначе —
	 * единственное такое, если называть не обязательно.
	 */
	@Nullable
	private static JSONObject capDevice(JSONObject info, String p, String type, String instance,
																			boolean mustName) {
		JSONArray dv = info.optJSONArray("devices");
		if (dv == null) return null;
		java.util.Map<String, String> rooms = rooms(info);
		java.util.List<JSONObject> all = new java.util.ArrayList<>();
		java.util.List<JSONObject> named = new java.util.ArrayList<>();
		for (int i = 0; i < dv.length(); i++) {
			JSONObject d = dv.optJSONObject(i);
			if ((d == null) || (capability(d, type, instance) == null)) continue;
			all.add(d);
			String room = rooms.get(d.optString("room"));
			if (covers(p, d.optString("name")) || ((room != null) && !room.isEmpty() && covers(p, room))) {
				named.add(d);
			}
		}
		if (named.size() == 1) return named.get(0);
		if (!named.isEmpty() || mustName) return null;
		return (all.size() == 1) ? all.get(0) : null;
	}

	private static java.util.Map<String, String> rooms(JSONObject info) {
		java.util.Map<String, String> rooms = new java.util.HashMap<>();
		JSONArray rs = info.optJSONArray("rooms");
		if (rs != null) for (int i = 0; i < rs.length(); i++) {
			JSONObject r = rs.optJSONObject(i);
			if (r != null) rooms.put(r.optString("id"), r.optString("name"));
		}
		return rooms;
	}

	private static final String[] ORDINAL = {"перв", "втор", "трет", "четверт", "пят", "шест", "седьм", "восьм",
			"девят", "десят", "одиннадцат", "двенадцат", "тринадцат", "четырнадцат", "пятнадцат",
			"шестнадцат", "семнадцат", "восемнадцат", "девятнадцат", "двадцат"};
	private static final String[] CARDINAL = {"один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь",
			"девять", "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
			"шестнадцать", "семнадцать", "восемнадцать", "девятнадцать", "двадцать"};
	private static final Pattern ORD_END = Pattern.compile("(ый|ой|ий|ого|его|ую|ая|ье|ьего|ья|ьей|ьему|ому|ым|ом)");

	/** «5», «пятый», «пятого», «пять», «одну» → число; иначе null. */
	@VisibleForTesting
	@Nullable
	static Integer number(String w) {
		if (w.matches("\\d{1,4}")) return Integer.parseInt(w);
		if (w.equals("одну") || w.equals("одна")) return 1;
		if (w.equals("две")) return 2;
		for (int i = 0; i < CARDINAL.length; i++) if (w.equals(CARDINAL[i])) return i + 1;
		// Длинные корни раньше: «пятнадцатый» — не «пят…»
		for (int i = ORDINAL.length - 1; i >= 0; i--) {
			String r = ORDINAL[i];
			if (w.startsWith(r) && ORD_END.matcher(w.substring(r.length())).matches()) return i + 1;
		}
		return null;
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
		// Группы («Свет на террасе» из нескольких ламп) — как устройства
		java.util.Set<JSONObject> groups = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		JSONArray gs = info.optJSONArray("groups");
		if (gs != null) for (int i = 0; i < gs.length(); i++) {
			JSONObject g = gs.optJSONObject(i);
			if ((g == null) || !hasOnOff(g)) continue;
			if (covers(p, g.optString("name"))) {
				hits.add(g);
				groups.add(g);
			}
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
		// Лишние слова — не эта команда: «включи пятый канал на телевизоре» не «включи телевизор»
		String room = rooms.get(d.optString("room"));
		if (!extra(p, d.optString("name"), room).isEmpty()) return null;
		return new Target(false, groups.contains(d), d.optString("id"), d.optString("name"));
	}

	private static final java.util.Set<String> FILLER = new java.util.HashSet<>(java.util.Arrays.asList(
			"пожалуйста", "мне", "нам", "тогда", "давай", "давайте", "сейчас", "быстро", "весь", "всё", "все",
			"там", "тут", "этот", "эту", "это", "уже", "ещё", "еще", "срочно", "ка"));

	/** Слова фразы, которых нет ни в названии устройства, ни в комнате (без предлогов и вежливости). */
	@VisibleForTesting
	static String extra(String phrase, String name, @Nullable String room) {
		StringBuilder b = new StringBuilder();
		for (String w : content(phrase).split(" ")) {
			if (w.isEmpty() || FILLER.contains(w)) continue;
			if (covers(norm(name), w)) continue;
			if ((room != null) && !room.isEmpty() && covers(norm(room), w)) continue;
			if (b.length() > 0) b.append(' ');
			b.append(w);
		}
		return b.toString();
	}

	/** Умения устройства коротко: «on, channel, volume, mute». */
	private static String caps(JSONObject d) {
		JSONArray caps = d.optJSONArray("capabilities");
		if (caps == null) return "?";
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < caps.length(); i++) {
			JSONObject c = caps.optJSONObject(i);
			if (c == null) continue;
			JSONObject pr = c.optJSONObject("parameters");
			String inst = (pr != null) ? pr.optString("instance") : "";
			if (inst.isEmpty()) inst = c.optString("type").replace("devices.capabilities.", "");
			if ((pr != null) && pr.has("random_access") && !pr.optBoolean("random_access")) inst += "(шагами)";
			if (b.length() > 0) b.append(", ");
			b.append(inst);
		}
		return b.toString();
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
		if ((a.capType == null) && (a.say != null)) return a.say; // ответ без запроса
		me.aap.utils.log.Log.i("JARVIS home: ", t.scenario ? "scenario «" : t.group ? "group «" : "device «", t.name, "» ",
				t.scenario ? "" : (a.capType != null) ? a.instance + " " + a.value + (a.relative ? " relative" : "") :
						(a.on ? "on" : "off"));
		JSONObject r;
		if (a.capType != null) {
			JSONObject state = new JSONObject().put("instance", a.instance);
			if (TOGGLE.equals(a.capType)) state.put("value", a.value != 0);
			else state.put("value", a.value);
			if (a.relative) state.put("relative", true);
			JSONObject action = new JSONObject().put("type", a.capType).put("state", state);
			JSONObject dev = new JSONObject().put("id", t.id).put("actions", new JSONArray().put(action));
			JSONObject body = new JSONObject().put("devices", new JSONArray().put(dev));
			r = new JSONObject(request("POST", "devices/actions", token, body.toString()));
		} else if (t.scenario) {
			r = new JSONObject(request("POST", "scenarios/" + t.id + "/actions", token, null));
		} else if (t.group) {
			// v1.0/groups/{id}/actions — включение группы целиком
			JSONObject state = new JSONObject().put("instance", "on").put("value", a.on);
			JSONObject action = new JSONObject().put("type", "devices.capabilities.on_off").put("state", state);
			JSONObject body = new JSONObject().put("actions", new JSONArray().put(action));
			r = new JSONObject(request("POST", "groups/" + t.id + "/actions", token, body.toString()));
		} else {
			JSONObject state = new JSONObject().put("instance", "on").put("value", a.on);
			JSONObject action = new JSONObject().put("type", "devices.capabilities.on_off").put("state", state);
			JSONObject dev = new JSONObject().put("id", t.id).put("actions", new JSONArray().put(action));
			JSONObject body = new JSONObject().put("devices", new JSONArray().put(dev));
			r = new JSONObject(request("POST", "devices/actions", token, body.toString()));
		}
		me.aap.utils.log.Log.i("JARVIS home: result ", r.optString("status"), " ", r.optString("message"));
		if (!succeeded(r)) return "Умный дом не выполнил команду: " + r.optString("message", "ошибка");
		if (a.say != null) return a.say;
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
	/** 403: вход есть, но приложению на oauth.yandex.ru не выданы права на Умный дом. */
	static final class NoAccessException extends Exception {
		NoAccessException(String m) {
			super(m);
		}
	}

	static final String NO_ACCESS = "Яндекс не дал доступ к Умному дому: у приложения на oauth.yandex.ru " +
			"нет прав на Умный дом. Добавьте права на просмотр и управление устройствами, " +
			"затем скажите команду снова и войдите заново";

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
			if (code == 403) throw new NoAccessException("HTTP 403");
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
