package me.aap.fermata.addon.jarvis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static me.aap.fermata.util.Utils.dynCtx;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Pattern;

import me.aap.utils.log.Log;

/**
 * Разговор во вкладке JARVIS ведёт Qwen с сайта chat.qwen.ai (пожелание
 * 21.10.2026: «берём ИИ Квин с его сайта и обворачиваем его в визуальную
 * оболочку джарвиза»). Сайт открыт в WebView под кругом JARVIS: невидимым,
 * пока не нужен вход. Фраза вписывается в поле сайта и отправляется его
 * кнопкой, ответ читается со страницы скриптом assets/qwen_chat.js.
 *
 * Вход — один раз, по почте (вход через Google в WebView Google не пускает);
 * cookies сайта сохраняются, как в браузере.
 */
final class QwenChat {
	static final String URL = "https://chat.qwen.ai/";
	/** Сколько ждать загрузки страницы и ответа Qwen, мс. */
	private static final long LOAD_WAIT_MS = 25_000;
	private static final long REPLY_WAIT_MS = 60_000;
	private static final long POLL_MS = 700;
	/** Длина ответа для озвучивания: синтезатор берёт ~4000 знаков, слушать длинное в машине тяжело. */
	static final int MAX_SPEECH = 1200;
	/**
	 * Первая фраза разговора — с просьбой отвечать коротко и без разметки:
	 * ответ слушают, а не читают. Видно в истории разговора на сайте.
	 */
	static final String FIRST_PREFIX =
			"Отвечай кратко, по-русски, обычным текстом без списков, таблиц и разметки — " +
					"ответ будет озвучен голосом. ";

	interface Callback {
		void onReply(@NonNull String text);

		/** Сайт просит войти. */
		void onLogin();

		void onError(@NonNull String why);
	}

	private final Handler main = new Handler(Looper.getMainLooper());
	private final WebView web;
	private final String script;
	private boolean loaded;
	private boolean loadStarted;
	/** В этом разговоре уже была фраза — просьба о краткости не повторяется. */
	private boolean started;
	@Nullable
	private Callback pending;
	private long deadline;
	@Nullable
	private String lastText;

	@SuppressLint("SetJavaScriptEnabled")
	QwenChat(Context ctx) {
		script = readScript(ctx);
		web = new WebView(ctx);
		WebSettings s = web.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setDatabaseEnabled(true);
		s.setMediaPlaybackRequiresUserGesture(true);
		CookieManager cm = CookieManager.getInstance();
		cm.setAcceptCookie(true);
		cm.setAcceptThirdPartyCookies(web, true);
		web.setWebViewClient(new WebViewClient() {
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
				// Ссылки на приложения (intent:, market: …) не открывать
				String scheme = request.getUrl().getScheme();
				return !"https".equals(scheme) && !"http".equals(scheme);
			}

			@Override
			public void onPageFinished(WebView view, String url) {
				Log.i("[QWEN] page loaded: ", url);
				loaded = true;
				inject();
				CookieManager.getInstance().flush();
			}
		});
		web.setWebChromeClient(new WebChromeClient() {
			@Override
			public boolean onConsoleMessage(ConsoleMessage m) {
				if (m.message().startsWith("[QWEN]")) Log.i(m.message());
				return true;
			}
		});
	}

	/**
	 * Загрузить страницу, если ещё не начали. Не сразу при открытии вкладки:
	 * загрузка сайта на несколько секунд задерживала «Слушаю» (журнал
	 * 22.10.2026, 13:17:47 — «Слушаю» через 7 с, микрофон через 26 с).
	 */
	void load() {
		if (loadStarted) return;
		loadStarted = true;
		Log.i("[QWEN] loading page");
		web.loadUrl(URL);
	}

	WebView getView() {
		return web;
	}

	/** Задать вопрос; ответ — в главном потоке через cb. Предыдущий вопрос отменяется. */
	void ask(@NonNull String text, @NonNull Callback cb) {
		cancel();
		pending = cb;
		lastText = null;
		String q = started ? text : FIRST_PREFIX + text;
		Log.i("[QWEN] ask «", text, "», loaded=", loaded);
		load();
		long loadUntil = System.currentTimeMillis() + LOAD_WAIT_MS;
		sendWhenReady(cb, q, loadUntil);
	}

	private void sendWhenReady(Callback cb, String q, long until) {
		if (pending != cb) return;
		if (!loaded) {
			if (System.currentTimeMillis() > until) {
				fail(cb, "страница Qwen не загрузилась");
				return;
			}
			main.postDelayed(() -> sendWhenReady(cb, q, until), 500);
			return;
		}
		inject();
		web.evaluateJavascript("window.__jq ? __jq.send(" + JSONObject.quote(q) + ", " + wanted() +
				") : null", r -> {
			if (pending != cb) return;
			JSONObject o = parse(r);
			if (o == null) {
				// Скрипт ещё не встал (страница перезагружалась) — повторить
				if (System.currentTimeMillis() > until) fail(cb, "страница Qwen не отвечает");
				else main.postDelayed(() -> sendWhenReady(cb, q, until), 500);
				return;
			}
			if (!o.optBoolean("ok")) {
				Log.i("[QWEN] send failed: ", o);
				if (o.optBoolean("login")) login(cb);
				else if (System.currentTimeMillis() > until) fail(cb, "нет поля для вопроса");
				else main.postDelayed(() -> sendWhenReady(cb, q, until), 1000);
				return;
			}
			started = true;
			deadline = System.currentTimeMillis() + REPLY_WAIT_MS;
			main.postDelayed(() -> poll(cb), POLL_MS);
		});
	}

	private void poll(Callback cb) {
		if (pending != cb) return;
		web.evaluateJavascript("window.__jq ? __jq.poll() : null", r -> {
			if (pending != cb) return;
			JSONObject o = parse(r);
			String state = (o == null) ? "" : o.optString("state");
			if ("login".equals(state)) {
				login(cb);
				return;
			}
			String text = (o == null) ? "" : o.optString("text");
			if (!text.isEmpty()) lastText = text;
			if ("done".equals(state)) {
				pending = null;
				Log.i("[QWEN] reply via ", o.optString("how"), ", ", text.length(), " chars: «",
						shorten(text, 300), "»");
				cb.onReply(text);
				return;
			}
			if (System.currentTimeMillis() > deadline) {
				pending = null;
				String t = lastText;
				Log.i("[QWEN] reply timeout, have ", (t == null) ? 0 : t.length(), " chars, last poll ", o);
				if (t != null) cb.onReply(t);
				else cb.onError("Qwen не ответил");
				return;
			}
			main.postDelayed(() -> poll(cb), POLL_MS);
		});
	}

	private void login(Callback cb) {
		pending = null;
		started = false;
		Log.i("[QWEN] login required");
		cb.onLogin();
	}

	private void fail(Callback cb, String why) {
		pending = null;
		Log.i("[QWEN] error: ", why);
		cb.onError(why);
	}

	/**
	 * Что пользователь выбрал на странице Qwen: модель, режим мышления, поиск в
	 * сети (пожелание 22.10.2026: «каждый раз сбрасывается на модель по
	 * умолчанию», «мышление быстрое, поиск в интернете — не сохраняет»). Новый
	 * разговор сайт начинает по умолчанию — перед вопросом скрипт ставит это.
	 *
	 * @return объект для __jq.send: {model, thinking, search}
	 */
	private String wanted() {
		android.content.SharedPreferences sp = web.getContext().getApplicationContext()
				.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		JSONObject o = new JSONObject();
		try {
			o.put("model", sp.getString(KEY_MODEL, null));
			o.put("thinking", sp.getString(KEY_THINKING, null));
			if (sp.contains(KEY_SEARCH)) o.put("search", sp.getBoolean(KEY_SEARCH, false));
		} catch (Exception ignore) {
		}
		return o.toString();
	}

	/** Запомнить выбранное сейчас на странице (после того как страницу спрятали). */
	void rememberModel() {
		web.evaluateJavascript("window.__jq ? __jq.settings() : null", r -> {
			JSONObject o = parse(r);
			if (o == null) return;
			android.content.SharedPreferences.Editor e = web.getContext().getApplicationContext()
					.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
			String m = o.optString("model").trim();
			if (!m.isEmpty()) e.putString(KEY_MODEL, m);
			String t = o.isNull("thinking") ? "" : o.optString("thinking").trim();
			if (!t.isEmpty()) e.putString(KEY_THINKING, t);
			if (!o.isNull("search")) e.putBoolean(KEY_SEARCH, o.optBoolean("search"));
			e.apply();
			Log.i("[QWEN] remembered: model ", m, ", thinking ", t.isEmpty() ? "(unchanged)" : t,
					", web search ", o.isNull("search") ? "(unchanged)" : o.optBoolean("search"));
		});
	}

	private static final String PREFS = "jarvis_qwen";
	private static final String KEY_MODEL = "model";
	private static final String KEY_THINKING = "thinking";
	private static final String KEY_SEARCH = "search";

	void cancel() {
		pending = null;
		main.removeCallbacksAndMessages(null);
	}

	void destroy() {
		cancel();
		CookieManager.getInstance().flush();
		web.destroy();
	}

	private void inject() {
		if (script != null) web.evaluateJavascript(script, null);
	}

	@Nullable
	private static JSONObject parse(@Nullable String r) {
		if ((r == null) || "null".equals(r)) return null;
		try {
			Object v = new JSONTokener(r).nextValue();
			return (v instanceof String) ? new JSONObject((String) v) : null;
		} catch (Exception ex) {
			Log.i("[QWEN] bad script result: ", shorten(r, 200));
			return null;
		}
	}

	@Nullable
	private static String readScript(Context ctx) {
		try (InputStream in = dynCtx(ctx).getAssets().open("qwen_chat.js")) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			return out.toString(UTF_8.name());
		} catch (Exception ex) {
			Log.e(ex, "[QWEN] failed to load qwen_chat.js");
			return null;
		}
	}

	private static String shorten(String s, int max) {
		return (s.length() <= max) ? s : s.substring(0, max) + "…";
	}

	private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?(```|$)");
	private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
	private static final Pattern URL_RE = Pattern.compile("https?://\\S+");
	private static final Pattern MARKS = Pattern.compile("[*_#`>|~]+");
	private static final Pattern BULLET = Pattern.compile("(?m)^\\s*([-•]|\\d+[.)])\\s+");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	/** Ответ Qwen — в текст для синтезатора: без разметки, ссылок и кода, не длиннее MAX_SPEECH. */
	@VisibleForTesting
	static String forSpeech(@Nullable String text) {
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
