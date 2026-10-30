package me.aap.fermata.addon.jarvis;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * DeepSeek с сайта chat.deepseek.com (пожелание пользователя 30.10.2026: «добавить также дипсик в обёртку, как сделан
 * Квин»). Страница — в WebView ({@link WebChat}), ответ читает assets/deepseek_chat.js. Режим — «DeepThink»
 * (с рассуждением) и поиск в интернете из настроек JARVIS Auto ({@link ChatPlan}); выбора модели у сайта нет.
 * Вход — почтой и паролем из настроек. Проверку «я не робот» сайт может показать — тогда вход вручную.
 */
final class DeepSeekChat extends WebChat {
	static final String URL = "https://chat.deepseek.com/";
	static final String LOGIN_URL = "https://chat.deepseek.com/sign_in";

	DeepSeekChat(Context ctx) {
		super(ctx, ChatPlan.Site.DEEPSEEK, "deepseek_chat.js");
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
		return "__jds";
	}

	@Override
	String email(PreferenceStore ps) {
		return ps.getStringPref(ChatPlan.DS_EMAIL);
	}

	@Override
	String loginScript(String email, String password) {
		return fillForm(email, password, "/^(Пароль|Вход по паролю|Password|Password login|密码登录)$/i",
				"/^(Войти|Вход|Log in|Login|Sign in|登录)$/i");
	}

	@Override
	JSONObject wanted() {
		PreferenceStore ps = prefs();
		JSONObject o = new JSONObject();
		try {
			o.put("thinking", ps.getBooleanPref(ChatPlan.DS_THINK));
			o.put("search", ps.getBooleanPref(ChatPlan.DS_SEARCH));
		} catch (Exception ignore) {
		}
		return o;
	}

	/** Переключённое на странице DeepSeek — в настройки. */
	@Override
	void remember(@NonNull JSONObject o) {
		PreferenceStore ps = prefs();
		if (!o.isNull("thinking")) ps.applyBooleanPref(ChatPlan.DS_THINK, o.optBoolean("thinking"));
		if (!o.isNull("search")) ps.applyBooleanPref(ChatPlan.DS_SEARCH, o.optBoolean("search"));
		Log.i("[DEEPSEEK] remembered: DeepThink ", o.isNull("thinking") ? "(unchanged)" : o.optBoolean("thinking"),
				", web search ", o.isNull("search") ? "(unchanged)" : o.optBoolean("search"));
	}
}
