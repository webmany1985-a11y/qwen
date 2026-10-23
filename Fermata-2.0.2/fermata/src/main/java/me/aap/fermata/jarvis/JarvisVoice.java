package me.aap.fermata.jarvis;

import android.content.Context;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.FermataApplication;
import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.voice.TextToSpeech;

/**
 * Голос JARVIS для речи во вкладке JARVIS — голос, темп и тембр из настроек
 * JARVIS Auto (Настройки → Дополнения → JARVIS), как в JARVIS.
 *
 * Раньше эти три значения читались из закрытых настроек JARVIS через root
 * (замечание 16.10.2026: «голос отличается от того, который я установил у
 * оригинального Джарвиса»). Пожелание 23.10.2026: выбор голоса — в своих
 * настройках, без root и без JARVIS (телефон жены). При первом запуске
 * берутся значения, прочитанные раньше у JARVIS, — голос не меняется.
 */
public final class JarvisVoice {
	static final float DEFAULT_RATE = 0.95f;
	static final float DEFAULT_PITCH = 0.9f;

	/** Имя голоса движка («ru-ru-x-ruf-local»); пусто — голос движка по умолчанию. */
	public static final PreferenceStore.Pref<Supplier<String>> VOICE =
			PreferenceStore.Pref.s("JARVIS_VOICE", "");
	public static final PreferenceStore.Pref<DoubleSupplier> RATE =
			PreferenceStore.Pref.f("JARVIS_RATE", DEFAULT_RATE);
	public static final PreferenceStore.Pref<DoubleSupplier> PITCH =
			PreferenceStore.Pref.f("JARVIS_PITCH", DEFAULT_PITCH);

	/** Где лежали значения, прочитанные у JARVIS через root (сборки до 34). */
	private static final String LEGACY_STORE = "jarvis_voice";

	/** Русские голоса движка для списка в настройках: (имя, «Голос N»). */
	private static volatile List<Pair<String, String>> voices = Collections.emptyList();
	private static volatile boolean migrated;

	private JarvisVoice() {
	}

	/** Настроить синтезатор. Вызывать после установки языка. */
	public static void apply(@NonNull TextToSpeech tts) {
		PreferenceStore ps = prefs();
		String voice = ps.getStringPref(VOICE);
		tts.setSpeechRate(ps.getFloatPref(RATE));
		tts.setPitch(ps.getFloatPref(PITCH));
		if (!voice.isEmpty() && !tts.setVoice(voice)) {
			Log.i("JARVIS voice: voice ", voice, " not found in TTS engine");
		}
	}

	private static PreferenceStore prefs() {
		PreferenceStore ps = FermataApplication.get().getPreferenceStore();
		if (!migrated) migrate(ps);
		return ps;
	}

	/** Значения, прочитанные раньше у JARVIS, — в свои настройки, один раз. */
	private static synchronized void migrate(PreferenceStore ps) {
		if (migrated) return;
		migrated = true;
		try {
			android.content.SharedPreferences old = FermataApplication.get()
					.getSharedPreferences(LEGACY_STORE, Context.MODE_PRIVATE);
			if (!old.contains("rate") || ps.hasPref(RATE)) return;
			try (PreferenceStore.Edit e = ps.editPreferenceStore()) {
				e.setFloatPref(RATE, old.getFloat("rate", DEFAULT_RATE));
				e.setFloatPref(PITCH, old.getFloat("pitch", DEFAULT_PITCH));
				String v = old.getString("voice", null);
				if (v != null) e.setStringPref(VOICE, v);
			}
			old.edit().clear().apply();
			Log.i("JARVIS voice: settings taken from JARVIS (read earlier)");
		} catch (Throwable ex) {
			Log.e(ex, "JARVIS voice: migration failed");
		}
	}

	/** Голоса для списка в настройках: «По умолчанию» и голоса движка ({@link #loadVoices}). */
	public static List<Pair<String, String>> voices() {
		List<Pair<String, String>> r = new ArrayList<>();
		r.add(new Pair<>("", "По умолчанию"));
		r.addAll(voices);
		// Выбранный голос, которого нет в списке (список ещё не загружен), — оставить видимым
		String cur = prefs().getStringPref(VOICE);
		if (!cur.isEmpty()) {
			boolean found = false;
			for (Pair<String, String> p : r) found |= cur.equals(p.first);
			if (!found) r.add(new Pair<>(cur, cur));
		}
		return r;
	}

	/** Прочитать голоса движка в фоне — при открытии настроек. */
	public static void loadVoices(Context ctx) {
		if (!voices.isEmpty()) return;
		android.speech.tts.TextToSpeech[] h = new android.speech.tts.TextToSpeech[1];
		h[0] = new android.speech.tts.TextToSpeech(ctx.getApplicationContext(), status -> {
			android.speech.tts.TextToSpeech t = h[0];
			try {
				if ((status != android.speech.tts.TextToSpeech.SUCCESS) || (t == null)) return;
				List<VoiceInfo> all = new ArrayList<>();
				for (android.speech.tts.Voice v : t.getVoices()) {
					all.add(new VoiceInfo(v.getName(), v.getLocale().getLanguage(),
							v.isNetworkConnectionRequired(), v.getFeatures().contains(
							android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)));
				}
				List<String> names = selectable(all, "ru");
				List<Pair<String, String>> l = new ArrayList<>(names.size());
				for (int i = 0; i < names.size(); i++) l.add(new Pair<>(names.get(i), "Голос " + (i + 1)));
				voices = l;
				Log.i("JARVIS voice: Russian voices ", names);
			} catch (Exception ex) {
				Log.e(ex, "JARVIS voice: voices not read");
			} finally {
				if (t != null) t.shutdown();
			}
		});
	}

	@VisibleForTesting
	static final class VoiceInfo {
		final String name;
		final String language;
		final boolean network;
		final boolean notInstalled;

		VoiceInfo(String name, String language, boolean network, boolean notInstalled) {
			this.name = name;
			this.language = language;
			this.network = network;
			this.notInstalled = notInstalled;
		}
	}

	/**
	 * Как в JARVIS (core/VoicePlan.selectable): русские, без сетевых и
	 * неустановленных, по имени; «…-language» — повтор голоса по умолчанию,
	 * если есть другие. Номера «Голос N» тогда совпадают с JARVIS.
	 */
	@VisibleForTesting
	static List<String> selectable(List<VoiceInfo> all, String language) {
		List<String> usable = new ArrayList<>();
		for (VoiceInfo v : all) {
			if (language.equals(v.language) && !v.network && !v.notInstalled) usable.add(v.name);
		}
		Collections.sort(usable);
		List<String> real = new ArrayList<>();
		for (String n : usable) if (!n.endsWith("-language")) real.add(n);
		return real.isEmpty() ? usable : real;
	}
}
