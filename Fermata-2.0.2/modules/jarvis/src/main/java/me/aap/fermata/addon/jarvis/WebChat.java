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

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import me.aap.fermata.FermataApplication;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * Разговор с ИИ через его сайт, открытый в WebView под кругом JARVIS (невидимым, пока не нужен вход). Фраза
 * вписывается в поле сайта и отправляется, ответ читается со страницы скриптом из assets. Общая часть для Qwen
 * ({@link QwenChat}) и DeepSeek ({@link DeepSeekChat}).
 *
 * <p>Вход (пожелание пользователя 30.10.2026): если сайт выкинул из аккаунта, а почта и пароль сохранены в настройках,
 * — входит сам: страница входа, почта и пароль, «Войти», и тот же вопрос заново. Не вышло (проверка «я не робот»,
 * код на почту, неверный пароль) — {@link Callback#onLogin()}: войти вручную.
 */
abstract class WebChat {
	interface Callback {
		void onReply(@NonNull String text);

		/** Нужен вход вручную: сам войти не смог. */
		void onLogin();

		void onError(@NonNull String why);
	}

	/** Сколько ждать загрузки страницы, мс. */
	private static final long LOAD_WAIT_MS = 25_000;
	private static final long LOGIN_WAIT_MS = 25_000;
	private static final long POLL_MS = 700;

	final ChatPlan.Site site;
	private final String tag;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final WebView web;
	@Nullable
	private final String script;
	private boolean loaded;
	private boolean loadStarted;
	/** В этом разговоре уже была фраза — просьба о краткости не повторяется. */
	private boolean started;
	@Nullable
	private Callback pending;
	private long deadline;
	private long replyWait = ChatPlan.SITE_WAIT_MS;
	@Nullable
	private String lastText;
	/** Сам входил при этом вопросе — второй раз не пробует (иначе круг: вход → «войдите» → вход). */
	private boolean triedLogin;

	@SuppressLint("SetJavaScriptEnabled")
	WebChat(Context ctx, ChatPlan.Site site, String scriptAsset) {
		this.site = site;
		this.tag = "[" + site.title.toUpperCase() + "] ";
		script = readScript(ctx, scriptAsset);
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
				Log.i(tag, "page loaded: ", url);
				loaded = true;
				inject();
				CookieManager.getInstance().flush();
			}
		});
		web.setWebChromeClient(new WebChromeClient() {
			@Override
			public boolean onConsoleMessage(ConsoleMessage m) {
				if (m.message().startsWith(tag.trim())) Log.i(m.message());
				return true;
			}
		});
	}

	/** Адрес разговора. */
	abstract String url();

	/** Адрес страницы входа. */
	abstract String loginUrl();

	/** Объект скрипта страницы: «__jq», «__jds». */
	abstract String js();

	/** Что поставить на сайте перед вопросом: {model, thinking, search} — из настроек. */
	abstract JSONObject wanted();

	/** Запомнить выбранное на открытой странице (после того как её спрятали). */
	abstract void remember(@NonNull JSONObject settings);

	/** Скрипт входа: заполнить почту и пароль и нажать «Войти»; вернуть строку «sent» или причину. */
	abstract String loginScript(String email, String password);

	/** Почта для входа из настроек. */
	abstract String email(PreferenceStore ps);

	static PreferenceStore prefs() {
		return FermataApplication.get().getPreferenceStore();
	}

	/** Загрузить страницу, если ещё не начали: не сразу при открытии вкладки — «Слушаю» не ждёт сайта. */
	void load() {
		if (loadStarted) return;
		loadStarted = true;
		Log.i(tag, "loading page");
		web.loadUrl(url());
	}

	WebView getView() {
		return web;
	}

	/** Задать вопрос: ответ — в главном потоке через cb; ждать ответа не дольше waitMs. Предыдущий отменяется. */
	void ask(@NonNull String text, long waitMs, @NonNull Callback cb) {
		cancel();
		pending = cb;
		lastText = null;
		replyWait = waitMs;
		triedLogin = false;
		Log.i(tag, "ask «", text, "», loaded=", loaded, ", wait ", waitMs / 1000, " s");
		load();
		sendWhenReady(cb, text, System.currentTimeMillis() + LOAD_WAIT_MS);
	}

	private void sendWhenReady(Callback cb, String text, long until) {
		if (pending != cb) return;
		if (!loaded) {
			if (System.currentTimeMillis() > until) {
				fail(cb, "страница " + site.title + " не загрузилась");
				return;
			}
			main.postDelayed(() -> sendWhenReady(cb, text, until), 500);
			return;
		}
		inject();
		String q = started ? text : QwenChat.FIRST_PREFIX + text;
		web.evaluateJavascript("window." + js() + " ? " + js() + ".send(" + JSONObject.quote(q) + ", " +
				wanted() + ") : null", r -> {
			if (pending != cb) return;
			JSONObject o = parse(r);
			if (o == null) {
				// Скрипт ещё не встал (страница перезагружалась) — повторить
				if (System.currentTimeMillis() > until) fail(cb, "страница " + site.title + " не отвечает");
				else main.postDelayed(() -> sendWhenReady(cb, text, until), 500);
				return;
			}
			if (!o.optBoolean("ok")) {
				Log.i(tag, "send failed: ", o);
				if (o.optBoolean("login")) login(cb, text);
				else if (System.currentTimeMillis() > until) fail(cb, "нет поля для вопроса");
				else main.postDelayed(() -> sendWhenReady(cb, text, until), 1000);
				return;
			}
			started = true;
			deadline = System.currentTimeMillis() + replyWait;
			main.postDelayed(() -> poll(cb, text), POLL_MS);
		});
	}

	private void poll(Callback cb, String text) {
		if (pending != cb) return;
		web.evaluateJavascript("window." + js() + " ? " + js() + ".poll() : null", r -> {
			if (pending != cb) return;
			JSONObject o = parse(r);
			String state = (o == null) ? "" : o.optString("state");
			if ("login".equals(state)) {
				login(cb, text);
				return;
			}
			String t = (o == null) ? "" : o.optString("text");
			if (!t.isEmpty()) lastText = t;
			if ("done".equals(state)) {
				pending = null;
				Log.i(tag, "reply via ", o.optString("how"), ", ", t.length(), " chars: «", shorten(t, 300), "»");
				cb.onReply(t);
				return;
			}
			if (System.currentTimeMillis() > deadline) {
				pending = null;
				String last = lastText;
				Log.i(tag, "reply timeout, have ", (last == null) ? 0 : last.length(), " chars, last poll ", o);
				if (last != null) cb.onReply(last);
				else cb.onError(site.title + " не ответил за " + (replyWait / 1000) + " с");
				return;
			}
			main.postDelayed(() -> poll(cb, text), POLL_MS);
		});
	}

	/** Сайт просит войти: почта и пароль есть — войти самому и повторить вопрос; нет или не вышло — вручную. */
	private void login(Callback cb, String text) {
		started = false;
		String email = email(prefs()).trim();
		String password = email.isEmpty() ? null : ChatSecrets.get(web.getContext(), site.id);
		if (triedLogin || email.isEmpty() || (password == null) || password.isEmpty()) {
			pending = null;
			Log.i(tag, "login required", triedLogin ? " (own login did not help)" :
					email.isEmpty() ? " (no email in settings)" : " (no password in settings)");
			cb.onLogin();
			return;
		}
		triedLogin = true;
		Log.i(tag, "login required — signing in with saved email and password");
		loaded = false;
		web.loadUrl(loginUrl());
		long until = System.currentTimeMillis() + LOAD_WAIT_MS;
		String js = loginScript(email, password);
		main.postDelayed(() -> fillLogin(cb, text, js, until), 2500);
	}

	private void fillLogin(Callback cb, String text, String js, long until) {
		if (pending != cb) return;
		if (!loaded) {
			if (System.currentTimeMillis() > until) {
				manualLogin(cb, "страница входа не загрузилась");
				return;
			}
			main.postDelayed(() -> fillLogin(cb, text, js, until), 500);
			return;
		}
		web.evaluateJavascript(js, r -> {
			if (pending != cb) return;
			String res = (r == null) ? "" : r;
			if (!res.contains("sent")) {
				manualLogin(cb, "форма входа не найдена: " + shorten(res, 120));
				return;
			}
			long end = System.currentTimeMillis() + LOGIN_WAIT_MS;
			main.postDelayed(() -> checkLogin(cb, text, end), 1500);
		});
	}

	private void checkLogin(Callback cb, String text, long until) {
		if (pending != cb) return;
		web.evaluateJavascript(LOGIN_CHECK, r -> {
			if (pending != cb) return;
			JSONObject o = parse(r);
			if ((o != null) && o.optBoolean("input") && !o.optBoolean("form")) {
				Log.i(tag, "signed in");
				loaded = false;
				web.loadUrl(url());
				sendWhenReady(cb, text, System.currentTimeMillis() + LOAD_WAIT_MS);
				return;
			}
			String err = (o == null) ? "" : o.optString("err");
			if (!err.isEmpty()) {
				manualLogin(cb, err);
				return;
			}
			if (System.currentTimeMillis() > until) manualLogin(cb, "вход не завершился за 25 с");
			else main.postDelayed(() -> checkLogin(cb, text, until), 1000);
		});
	}

	private void manualLogin(Callback cb, String why) {
		pending = null;
		// Причину — в журнал; текст ошибки сайта не содержит ни почты, ни пароля
		Log.i(tag, "own login failed: ", why);
		cb.onLogin();
	}

	/**
	 * Вошли ли: на странице поле вопроса (textarea) и нет формы входа (поля пароля). err — сообщение об ошибке
	 * со страницы входа; «я не робот» — тоже ошибка: входить вручную.
	 */
	private static final String LOGIN_CHECK = "(function(){" +
			"function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}" +
			"var ta=[].slice.call(document.querySelectorAll('textarea')).filter(vis)[0];" +
			"var pw=[].slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];" +
			"var body=(document.body&&document.body.innerText)||'';" +
			"var robot=/confirm you are human|Human Verification|я не робот|captcha/i.test(body+document.title);" +
			"var err=[].slice.call(document.querySelectorAll('[class*=error],[class*=Error],.ant-message,[role=alert]'))" +
			".filter(vis).map(function(e){return (e.innerText||'').trim();}).filter(function(x){return x&&x.length<200;}).join(' ');" +
			"if(robot)err='сайт просит подтвердить, что вы не робот';" +
			"return JSON.stringify({input:!!ta,form:!!pw,err:err});})()";

	private void fail(Callback cb, String why) {
		pending = null;
		Log.i(tag, "error: ", why);
		cb.onError(why);
	}

	/** Запомнить выбранное сейчас на странице (после того как страницу спрятали). */
	void rememberModel() {
		web.evaluateJavascript("window." + js() + " ? " + js() + ".settings() : null", r -> {
			JSONObject o = parse(r);
			if (o != null) remember(o);
		});
	}

	/** Новый разговор: следующая фраза — снова с просьбой о краткости. */
	void restartConversation() {
		started = false;
	}

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
	static JSONObject parse(@Nullable String r) {
		if ((r == null) || "null".equals(r)) return null;
		try {
			Object v = new JSONTokener(r).nextValue();
			return (v instanceof String) ? new JSONObject((String) v) : null;
		} catch (Exception ex) {
			return null;
		}
	}

	@Nullable
	private static String readScript(Context ctx, String name) {
		try (InputStream in = dynCtx(ctx).getAssets().open(name)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			return out.toString(UTF_8.name());
		} catch (Exception ex) {
			Log.e(ex, "[CHAT] failed to load ", name);
			return null;
		}
	}

	static String shorten(String s, int max) {
		return (s.length() <= max) ? s : s.substring(0, max) + "…";
	}

	/**
	 * Заполнить форму входа: поля под React — значение родным сеттером и событие input. Пароль — поле type=password,
	 * почта — первое видимое текстовое поле рядом. Флажки согласия отмечаются. Затем — кнопка входа (одна из подписей
	 * {@code buttons}). Возвращает «sent», если форма нашлась.
	 */
	static String fillForm(String email, String password, String switchToPassword, String buttons) {
		return "(function(){" +
				"function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}" +
				"function byText(re){return [].slice.call(document.querySelectorAll('button,[role=button],a,div,span'))" +
				".filter(function(b){return vis(b)&&re.test((b.innerText||'').trim());})" +
				".sort(function(a,b){return (a.innerText||'').length-(b.innerText||'').length;})[0];}" +
				"function setv(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
				"d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));" +
				"el.dispatchEvent(new Event('change',{bubbles:true}));}" +
				"var pw=[].slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];" +
				"if(!pw){var sw=byText(" + switchToPassword + ");if(sw)sw.click();}" +
				"var tries=0;(function step(){" +
				"pw=[].slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];" +
				"var em=[].slice.call(document.querySelectorAll('input')).filter(function(i){return vis(i)&&" +
				"i.type!=='password'&&i.type!=='file'&&i.type!=='hidden'&&i.type!=='checkbox'&&i.type!=='radio';})[0];" +
				"if((!pw||!em)&&tries++<20){setTimeout(step,250);return;}" +
				"if(!pw||!em)return;setv(em," + JSONObject.quote(email) + ");setv(pw," + JSONObject.quote(password) + ");" +
				"[].slice.call(document.querySelectorAll('input[type=checkbox]')).forEach(function(c){if(!c.checked)c.click();});" +
				"setTimeout(function(){var go=byText(" + buttons + ");if(go)go.click();},400);})();" +
				"return (document.querySelector('input[type=password]')||document.querySelector('input'))?'sent':'no form';})()";
	}
}
