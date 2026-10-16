package me.aap.fermata.addon.jarvis;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * Вкладка «JARVIS»: круг ассистента и голосовой ввод. Фразу сначала
 * выполняет сама Fermata, непонятую — JARVIS (см. {@link JarvisFragment}).
 */
@Keep
@SuppressWarnings("unused")
public class JarvisAddon implements FermataFragmentAddon {
	private static final AddonInfo info = FermataAddon.findAddonInfo(JarvisAddon.class.getName());

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
}
