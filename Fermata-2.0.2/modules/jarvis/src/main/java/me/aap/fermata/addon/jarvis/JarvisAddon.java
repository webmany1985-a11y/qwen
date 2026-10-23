package me.aap.fermata.addon.jarvis;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * Вкладка «JARVIS»: круг ассистента и голосовой ввод. Фразу сначала
 * выполняет сама Fermata, непонятую — JARVIS (см. {@link JarvisFragment}).
 */
@Keep
@SuppressWarnings("unused")
public class JarvisAddon implements FermataFragmentAddon {
	private static final AddonInfo info = FermataAddon.findAddonInfo(JarvisAddon.class.getName());
	/** Микрофон машины по Bluetooth на время фразы (пробно, 22.10.2026). Только в машине. */
	static final PreferenceStore.Pref<BooleanSupplier> CAR_MIC = PreferenceStore.Pref.b("JARVIS_CAR_MIC", false);
	/** Адреса для «маршрут на работу», «поехали домой» (23.10.2026). Пусто — слова «Работа», «Дом». */
	static final PreferenceStore.Pref<me.aap.utils.function.Supplier<String>> WORK_ADDRESS =
			PreferenceStore.Pref.s("JARVIS_WORK_ADDRESS", "");
	static final PreferenceStore.Pref<me.aap.utils.function.Supplier<String>> HOME_ADDRESS =
			PreferenceStore.Pref.s("JARVIS_HOME_ADDRESS", "");
	/** ClientID приложения на oauth.yandex.ru (права iot:view, iot:control) — для ворот. */
	static final PreferenceStore.Pref<me.aap.utils.function.Supplier<String>> YANDEX_CLIENT_ID =
			PreferenceStore.Pref.s("JARVIS_YANDEX_CLIENT_ID", "");

	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.jarvis_addon;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new JarvisFragment();
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		// Голос — свой, как в JARVIS (пожелание 23.10.2026: без root и без JARVIS)
		me.aap.fermata.jarvis.JarvisVoice.loadVoices(ctx);
		set.addListPref(o -> {
			o.setStringValues(store, me.aap.fermata.jarvis.JarvisVoice.VOICE,
					me.aap.fermata.jarvis.JarvisVoice.voices());
			// Голоса движка читаются в фоне — список обновляется при нажатии
			o.initList = l -> l.setStringValues(store, me.aap.fermata.jarvis.JarvisVoice.VOICE,
					me.aap.fermata.jarvis.JarvisVoice.voices());
			o.title = R.string.jarvis_voice;
			o.subtitle = me.aap.fermata.R.string.string_format;
			o.formatSubtitle = true;
			o.visibility = visibility;
		});
		set.addFloatPref(o -> {
			o.store = store;
			o.pref = me.aap.fermata.jarvis.JarvisVoice.PITCH;
			o.title = R.string.jarvis_voice_pitch;
			o.scale = 0.05f;
			o.seekMin = 10;
			o.seekMax = 30;
			o.visibility = visibility;
		});
		set.addFloatPref(o -> {
			o.store = store;
			o.pref = me.aap.fermata.jarvis.JarvisVoice.RATE;
			o.title = R.string.jarvis_voice_rate;
			o.scale = 0.05f;
			o.seekMin = 10;
			o.seekMax = 30;
			o.visibility = visibility;
		});
		set.addButton(o -> {
			o.title = R.string.jarvis_voice_test;
			o.onClick = () -> me.aap.utils.voice.TextToSpeech.create(ctx, new java.util.Locale("ru"))
					.onCompletion((t, err) -> {
						if (err != null) {
							me.aap.utils.log.Log.e(err, "JARVIS voice: test failed");
							return;
						}
						me.aap.fermata.jarvis.JarvisVoice.apply(t);
						t.speak("Слушаю. Так звучит голос JARVIS.").onCompletion((r, e) -> t.close());
					});
			o.visibility = visibility;
		});
		set.addBooleanPref(o -> {
			o.store = store;
			o.pref = CAR_MIC;
			o.title = R.string.jarvis_car_mic;
			o.subtitle = R.string.jarvis_car_mic_sub;
			o.visibility = visibility;
		});
		set.addStringPref(o -> {
			o.store = store;
			o.pref = WORK_ADDRESS;
			o.title = R.string.jarvis_work_address;
			o.visibility = visibility;
		});
		set.addStringPref(o -> {
			o.store = store;
			o.pref = HOME_ADDRESS;
			o.title = R.string.jarvis_home_address;
			o.visibility = visibility;
		});
		set.addStringPref(o -> {
			o.store = store;
			o.pref = YANDEX_CLIENT_ID;
			o.title = R.string.jarvis_yandex_client_id;
			o.visibility = visibility;
		});
	}
}
