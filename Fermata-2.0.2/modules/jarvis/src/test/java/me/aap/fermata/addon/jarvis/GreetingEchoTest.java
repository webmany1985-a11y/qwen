package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Эхо «Слушаю», пойманное микрофоном, не должно уходить JARVIS. */
public class GreetingEchoTest {

	@Test
	public void echoRemovedCommandKept() {
		List<String> q = JarvisFragment.stripGreeting(
				Arrays.asList("Слушаю включи радио", "слушаю, включи радио", "включи радио"));
		assertEquals(Arrays.asList("включи радио", "включи радио", "включи радио"), q);
	}

	@Test
	public void onlyEchoGivesEmpty() {
		assertTrue(JarvisFragment.stripGreeting(Arrays.asList("Слушаю", "слушаю.", "")).isEmpty());
	}

	@Test
	public void wordInsidePhraseKept() {
		assertEquals(Arrays.asList("я слушаю музыку"),
				JarvisFragment.stripGreeting(Arrays.asList("я слушаю музыку")));
	}

	@Test
	public void repeatPromptEchoRemoved() {
		List<String> q = JarvisFragment.stripGreeting(
				Arrays.asList("Не расслышал, повторите какая погода", "не расслышал повтори какая погода",
						"какая погода"));
		assertEquals(Arrays.asList("какая погода", "какая погода", "какая погода"), q);
	}
}
