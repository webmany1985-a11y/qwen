package me.aap.fermata.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Разбор настроек голоса JARVIS (shared_prefs/jarvis.xml). */
public class JarvisVoiceTest {

	@Test
	public void readsRatePitchVoice() {
		String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
				"    <string name=\"api_key\">secret</string>\n" +
				"    <float name=\"speech_rate\" value=\"1.15\" />\n" +
				"    <float name=\"speech_pitch\" value=\"0.75\" />\n" +
				"    <string name=\"voice_id\">ru-ru-x-ruc-local</string>\n</map>\n";
		JarvisVoice.Settings s = JarvisVoice.parse(xml);
		assertEquals(1.15f, s.rate, 1e-6f);
		assertEquals(0.75f, s.pitch, 1e-6f);
		assertEquals("ru-ru-x-ruc-local", s.voice);
	}

	@Test
	public void defaultsWhenAbsentOrOutOfRange() {
		JarvisVoice.Settings s = JarvisVoice.parse(
				"<map><float name=\"speech_rate\" value=\"7.0\" /></map>");
		assertEquals(JarvisVoice.DEFAULT_RATE, s.rate, 0f);
		assertEquals(JarvisVoice.DEFAULT_PITCH, s.pitch, 0f);
		assertNull(s.voice);
	}
}
