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
		set.addBooleanPref(o -> {
			o.store = store;
			o.pref = CAR_MIC;
			o.title = R.string.jarvis_car_mic;
			o.subtitle = R.string.jarvis_car_mic_sub;
			o.visibility = visibility;
		});
	}
}
