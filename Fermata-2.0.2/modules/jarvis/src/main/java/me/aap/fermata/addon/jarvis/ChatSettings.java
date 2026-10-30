package me.aap.fermata.addon.jarvis;

import static me.aap.fermata.util.Utils.dynCtx;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.util.Pair;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;

import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.misc.ChangeableCondition;

/**
 * Раздел «Разговор» в настройках JARVIS Auto (пожелание пользователя 30.10.2026): кто ведёт разговор — Qwen или
 * DeepSeek, вторая — запасная; у каждой почта, пароль, режим (у Qwen ещё модель) и поиск. Пароль вводится в окне со
 * скрытыми символами и «показать пароль», хранится зашифрованным ({@link ChatSecrets}).
 */
final class ChatSettings {
	private ChatSettings() {
	}

	static void contribute(Context ctx, PreferenceStore store, PreferenceSet set, ChangeableCondition visibility) {
		PreferenceSet chat = set.subSet(o -> {
			o.title = R.string.jarvis_chat_section;
			o.visibility = visibility;
		});
		chat.addListPref(o -> {
			o.setStringValues(store, ChatPlan.MAIN, Arrays.asList(
					new Pair<>("qwen", "Qwen"), new Pair<>("deepseek", "DeepSeek")));
			o.title = R.string.jarvis_chat_main;
			o.subtitle = me.aap.fermata.R.string.string_format;
			o.formatSubtitle = true;
		});
		chat.addBooleanPref(o -> {
			o.store = store;
			o.pref = ChatPlan.FALLBACK;
			o.title = R.string.jarvis_chat_fallback;
			o.subtitle = R.string.jarvis_chat_fallback_sub;
		});

		// Qwen
		chat.addStringPref(o -> {
			o.store = store;
			o.pref = ChatPlan.QWEN_EMAIL;
			o.title = R.string.jarvis_qwen_email;
			o.trim = true;
		});
		password(ctx, chat, ChatPlan.Site.QWEN, R.string.jarvis_qwen_password);
		chat.addListPref(o -> {
			o.setStringValues(store, ChatPlan.QWEN_MODE, withCurrent(store, ChatPlan.QWEN_MODE, Arrays.asList(
					new Pair<>("", "Как выбрано на сайте"), new Pair<>("Быстрый", "Быстрый"),
					new Pair<>("Авто", "Авто"), new Pair<>("Мышление", "Мышление"))));
			o.title = R.string.jarvis_qwen_mode;
			o.subtitle = me.aap.fermata.R.string.string_format;
			o.formatSubtitle = true;
		});
		chat.addStringPref(o -> {
			o.store = store;
			o.pref = ChatPlan.QWEN_MODEL;
			o.title = R.string.jarvis_qwen_model;
			o.trim = true;
		});
		chat.addListPref(o -> {
			o.setStringValues(store, ChatPlan.QWEN_SEARCH, Arrays.asList(
					new Pair<>("", "Как выбрано на сайте"), new Pair<>("on", "Включён"), new Pair<>("off", "Выключен")));
			o.title = R.string.jarvis_qwen_search;
			o.subtitle = me.aap.fermata.R.string.string_format;
			o.formatSubtitle = true;
		});

		// DeepSeek
		chat.addStringPref(o -> {
			o.store = store;
			o.pref = ChatPlan.DS_EMAIL;
			o.title = R.string.jarvis_ds_email;
			o.trim = true;
		});
		password(ctx, chat, ChatPlan.Site.DEEPSEEK, R.string.jarvis_ds_password);
		chat.addBooleanPref(o -> {
			o.store = store;
			o.pref = ChatPlan.DS_THINK;
			o.title = R.string.jarvis_ds_think;
			o.subtitle = R.string.jarvis_ds_think_sub;
		});
		chat.addBooleanPref(o -> {
			o.store = store;
			o.pref = ChatPlan.DS_SEARCH;
			o.title = R.string.jarvis_ds_search;
		});
	}

	/** Выбранное на сайте значение, которого нет в списке («Thinking»), — оставить видимым и выбранным. */
	private static List<Pair<String, String>> withCurrent(PreferenceStore store,
																												PreferenceStore.Pref<me.aap.utils.function.Supplier<String>> pref,
																												List<Pair<String, String>> base) {
		String cur = store.getStringPref(pref);
		for (Pair<String, String> p : base) if (p.first.equals(cur)) return base;
		List<Pair<String, String>> r = new java.util.ArrayList<>(base);
		r.add(new Pair<>(cur, cur));
		return r;
	}

	/** Кнопка пароля: подпись — задан ли; нажатие — окно ввода. */
	private static void password(Context ctx, PreferenceSet set, ChatPlan.Site site, int title) {
		set.addButton(o -> {
			o.title = title;
			o.csubtitle = dynCtx(ctx).getString(ChatSecrets.has(ctx, site.id) ?
					R.string.jarvis_password_set : R.string.jarvis_password_unset);
			o.onClick = () -> showDialog(ctx, site);
		});
	}

	private static void showDialog(Context ctx, ChatPlan.Site site) {
		try {
			Context c = dynCtx(ctx);
			EditText e = new EditText(ctx);
			e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
			e.setHint(c.getString(R.string.jarvis_password_hint));
			String cur = ChatSecrets.get(ctx, site.id);
			if (cur != null) e.setText(cur);
			CheckBox show = new CheckBox(ctx);
			show.setText(c.getString(R.string.jarvis_password_show));
			show.setOnCheckedChangeListener((b, on) -> {
				int sel = e.getSelectionEnd();
				e.setInputType(InputType.TYPE_CLASS_TEXT | (on ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD :
						InputType.TYPE_TEXT_VARIATION_PASSWORD));
				e.setSelection(Math.max(0, Math.min(sel, e.length())));
			});
			LinearLayout l = new LinearLayout(ctx);
			l.setOrientation(LinearLayout.VERTICAL);
			int pad = (int) (20 * ctx.getResources().getDisplayMetrics().density);
			l.setPadding(pad, pad / 2, pad, 0);
			l.addView(e);
			l.addView(show);
			new AlertDialog.Builder(ctx)
					.setTitle(site.title)
					.setView(l)
					.setPositiveButton(c.getString(R.string.jarvis_password_save), (d, w) -> {
						ChatSecrets.put(ctx, site.id, e.getText().toString());
						Log.i("[CHAT] ", site.title, " password saved");
					})
					.setNeutralButton(c.getString(R.string.jarvis_password_clear), (d, w) -> {
						ChatSecrets.put(ctx, site.id, null);
						Log.i("[CHAT] ", site.title, " password removed");
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		} catch (Exception ex) {
			Log.e(ex, "[CHAT] password dialog failed");
			Toast.makeText(ctx, "Пароль вводится на телефоне", Toast.LENGTH_LONG).show();
		}
	}
}
