package me.aap.fermata.jarvis;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Список голосов в настройках — как в JARVIS (core/VoicePlan.selectable). */
public class JarvisVoiceTest {

	private static JarvisVoice.VoiceInfo v(String name, String lang, boolean net, boolean notInstalled) {
		return new JarvisVoice.VoiceInfo(name, lang, net, notInstalled);
	}

	@Test
	public void russianLocalInstalledSorted() {
		List<String> r = JarvisVoice.selectable(Arrays.asList(
				v("ru-ru-x-ruf-local", "ru", false, false),
				v("ru-ru-x-dfc-network", "ru", true, false),
				v("ru-RU-language", "ru", false, false),
				v("ru-ru-x-ruc-local", "ru", false, false),
				v("ru-ru-x-rue-local", "ru", false, true),
				v("en-us-x-sfg-local", "en", false, false)), "ru");
		assertEquals(Arrays.asList("ru-ru-x-ruc-local", "ru-ru-x-ruf-local"), r);
	}

	@Test
	public void onlyDefaultAliasLeft() {
		assertEquals(Arrays.asList("ru-RU-language"),
				JarvisVoice.selectable(Arrays.asList(v("ru-RU-language", "ru", false, false)), "ru"));
	}

	@Test
	public void volumeGain() {
		assertEquals(0, JarvisVoice.gainMb(1f));
		assertEquals(602, JarvisVoice.gainMb(2f));
		assertEquals(1204, JarvisVoice.gainMb(4f));
		assertEquals(1204, JarvisVoice.gainMb(9f)); // не громче ×4
		assertEquals(0, JarvisVoice.gainMb(0.5f)); // тише — не усилителем
	}
}
