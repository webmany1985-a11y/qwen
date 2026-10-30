package me.aap.fermata.addon.jarvis;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * Qwen с сайта chat.qwen.ai (пожелание 21.10.2026: «берём ИИ Квин с его сайта и обворачиваем его в визуальную
 * оболочку джарвиза»). Страница — в WebView ({@link WebChat}), ответ читает assets/qwen_chat.js.
 *
 * <p>Модель, режим (быстрый, авто, мышление) и поиск — из настроек JARVIS Auto ({@link ChatPlan}); выбранное на самой
 * странице (долгое касание шара) тоже запоминается туда. Вход — почтой и паролем из настроек, сам, когда сайт
 * выкинул из аккаунта (страница входа: «Войти с паролем», почта, пароль — сверено в «Домашке» 29.10.2026).
 */
final class QwenChat extends WebChat {
	static final String URL = "https://chat.qwen.ai/";
	static final String LOGIN_URL = "https://chat.qwen.ai/auth?action=signin";
	/** Длина ответа для озвучивания: синтезатор берёт ~4000 знаков, слушать длинное в машине тяжело. */
	static final int MAX_SPEECH = 1200;
	/**
	 * Первая фраза разговора — с просьбой отвечать коротко и без разметки:
	 * ответ слушают, а не читают. Видно в истории разговора на сайте.
	 */
	static final String FIRST_PREFIX =
			"Отвечай кратко, по-русски, обычным текстом без списков, таблиц и разметки — " +
					"ответ будет озвучен голосом. ";

	QwenChat(Context ctx) {
		super(ctx, ChatPlan.Site.QWEN, "qwen_chat.js");
		migrate(ctx);
	}

	@Override
	String url() {
		return URL;
	}

	@Override
	String loginUrl() {
		return LOGIN_URL;
	}

	@Override
	String js() {
		return "__jq";
	}

	@Override
	String email(PreferenceStore ps) {
		return ps.getStringPref(ChatPlan.QWEN_EMAIL);
	}

	@Override
	String loginScript(String email, String password) {
		return fillForm(email, password, "/^(Войти с паролем|Sign in with password|Log in with password)$/i",
				"/^(Войти|Sign in|Log in)$/i");
	}

	/** {model, thinking, search} для qwen_chat.js: пустое — как выбрано на сайте. */
	@Override
	JSONObject wanted() {
		PreferenceStore ps = prefs();
		JSONObject o = new JSONObject();
		try {
			String m = ps.getStringPref(ChatPlan.QWEN_MODEL).trim();
			String t = ps.getStringPref(ChatPlan.QWEN_MODE).trim();
			String s = ps.getStringPref(ChatPlan.QWEN_SEARCH).trim();
			if (!m.isEmpty()) o.put("model", m);
			if (!t.isEmpty()) o.put("thinking", t);
			if (!s.isEmpty()) o.put("search", "on".equals(s));
		} catch (Exception ignore) {
		}
		return o;
	}

	/** Выбранное на странице Qwen — в настройки (модель всегда; режим и поиск — если их меняли). */
	@Override
	void remember(@NonNull JSONObject o) {
		PreferenceStore ps = prefs();
		String m = o.optString("model").trim();
		if (!m.isEmpty()) ps.applyStringPref(ChatPlan.QWEN_MODEL, m);
		String t = o.isNull("thinking") ? "" : o.optString("thinking").trim();
		if (!t.isEmpty()) ps.applyStringPref(ChatPlan.QWEN_MODE, t);
		if (!o.isNull("search")) ps.applyStringPref(ChatPlan.QWEN_SEARCH, o.optBoolean("search") ? "on" : "off");
		Log.i("[QWEN] remembered: model ", m, ", thinking ", t.isEmpty() ? "(unchanged)" : t,
				", web search ", o.isNull("search") ? "(unchanged)" : o.optBoolean("search"));
	}

	/** Выбор, запомненный до 30.10.2026 (свой файл настроек), — в настройки JARVIS Auto, один раз. */
	private static void migrate(Context ctx) {
		android.content.SharedPreferences sp = ctx.getApplicationContext()
				.getSharedPreferences("jarvis_qwen", Context.MODE_PRIVATE);
		if (sp.getAll().isEmpty()) return;
		PreferenceStore ps = prefs();
		String m = sp.getString("model", "");
		String t = sp.getString("thinking", "");
		if ((m != null) && !m.isEmpty() && ps.getStringPref(ChatPlan.QWEN_MODEL).isEmpty())
			ps.applyStringPref(ChatPlan.QWEN_MODEL, m);
		if ((t != null) && !t.isEmpty() && ps.getStringPref(ChatPlan.QWEN_MODE).isEmpty())
			ps.applyStringPref(ChatPlan.QWEN_MODE, t);
		if (sp.contains("search") && ps.getStringPref(ChatPlan.QWEN_SEARCH).isEmpty())
			ps.applyStringPref(ChatPlan.QWEN_SEARCH, sp.getBoolean("search", false) ? "on" : "off");
		sp.edit().clear().apply();
		Log.i("[QWEN] settings moved to JARVIS Auto settings: model ", m, ", thinking ", t);
	}

	/** Ответ ИИ — в текст для синтезатора: без разметки, ссылок и кода, не длиннее MAX_SPEECH. */
	@VisibleForTesting
	static String forSpeech(@Nullable String text) {
		return speech(text);
	}

	private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?(```|$)");
	private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
	private static final Pattern URL_RE = Pattern.compile("https?://\\S+");
	private static final Pattern MARKS = Pattern.compile("[*_#`>|~]+");
	private static final Pattern BULLET = Pattern.compile("(?m)^\\s*([-•]|\\d+[.)])\\s+");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	private static String speech(@Nullable String text) {
		if (text == null) return "";
		String t = CODE_BLOCK.matcher(text).replaceAll(" ");
		t = LINK.matcher(t).replaceAll("$1");
		t = URL_RE.matcher(t).replaceAll(" ");
		t = BULLET.matcher(t).replaceAll("");
		t = MARKS.matcher(t).replaceAll(" ");
		t = SPACES.matcher(t).replaceAll(" ").trim();
		if (t.length() <= MAX_SPEECH) return t;
		// Обрезать по концу предложения
		int cut = -1;
		for (int i = MAX_SPEECH; i > MAX_SPEECH / 2; i--) {
			char c = t.charAt(i - 1);
			if ((c == '.') || (c == '!') || (c == '?') || (c == '…')) {
				cut = i;
				break;
			}
		}
		return (cut > 0) ? t.substring(0, cut) : t.substring(0, MAX_SPEECH) + "…";
	}

	/**
	 * Команда телефону — выполняет JARVIS на телефоне, а не Qwen (выбор
	 * пользователя 21.10.2026: «команды — JARVIS, разговор — Qwen»): звонок,
	 * сообщение, открыть приложение, музыка, будильник, навигация, громкость.
	 */
	private static final Pattern PHONE_COMMAND = Pattern.compile(
			"^(?iu)(джарвис[\\s,]+)?(позвони|набери|вызови|звонок|открой|запусти|закрой|включи|выключи|" +
					"отключи|поставь|сделай\\s+(громче|тише)|громче|тише|отправь|напиши\\s+(смс|сообщение)|" +
					"прочитай\\s+(смс|сообщени)|разбуди|будильник|напомни|таймер|проложи|маршрут|" +
					"поехали|навигаци|едем|найди\\s+на\\s+карте|wi-?fi|вай-?фай|блютуз|bluetooth|фонарик)");

	/**
	 * Домашнее задание детей — тоже JARVIS: дневник читает он, Qwen к нему
	 * доступа нет (журнал 22.10.2026, 09:22 и 09:58: «что с домашкой у Софии»).
	 */
	private static final Pattern HOMEWORK = Pattern.compile(
			"(?iu)(домашк|домашн\\S*\\s+задани|сделал[аи]?\\s+урок|задали|дневник|оценк|отметк|" +
					// Школа и дети из «Домашки» (30.10.2026): расписание, конец уроков, четвертные,
					// питание, задолженность, кружки — отвечает JARVIS
					"расписани|(^|\\s)урок|четвертн|итогов\\S*\\s+отметк|питани|задолженн|кружк|факультатив|" +
					"дети\\s+дома)");

	/**
	 * «Где Марк?», «где сейчас дети», «София уже дома?», «Марк пришёл домой?» — где дети, тоже JARVIS (место
	 * знает «Домашка»). Имён здесь нет: одно слово после «где» или перед «дома»; не ребёнок — JARVIS сам
	 * отдаст фразу в разговор.
	 */
	private static final Pattern KIDS_PLACE = Pattern.compile(
			"^(?iu)(джарвис[\\s,]+)?(где\\s+(сейчас\\s+)?[\\p{L}]+|[\\p{L}]+\\s+(уже\\s+)?(дома|пришл\\S*|пришел|пришёл|дошл\\S*|дошел|дошёл|вернул\\S*)(\\s+домой)?)[\\s?!.]*$");

	/**
	 * Вариант распознавания, который — команда телефону, или null. Смотрим все
	 * варианты: первым распознавание может дать обрезанный («музыку» вместо
	 * «включи музыку» — журнал 23.10.2026, 08:24:11, фраза ушла в Qwen).
	 */
	@Nullable
	static String phoneCommand(@Nullable java.util.List<String> alternatives) {
		if (alternatives == null) return null;
		for (String s : alternatives) if (isPhoneCommand(s)) return s;
		return null;
	}

	@VisibleForTesting
	static boolean isPhoneCommand(@Nullable String phrase) {
		if (phrase == null) return false;
		String p = phrase.trim().toLowerCase(Locale.ROOT);
		return PHONE_COMMAND.matcher(p).find() || HOMEWORK.matcher(p).find() || KIDS_PLACE.matcher(p).find();
	}
}
