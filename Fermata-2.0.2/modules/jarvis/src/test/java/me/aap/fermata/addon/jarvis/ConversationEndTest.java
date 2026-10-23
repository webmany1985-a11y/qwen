package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Какими словами заканчивается разговор во вкладке JARVIS. */
public class ConversationEndTest {

	@Test
	public void endWords() {
		for (String s : new String[]{"спасибо", "Всё", "все спасибо", "хватит", "отбой", "Стоп",
				"пока", "спасибо, Джарвис", "ничего"}) {
			assertTrue(s, JarvisFragment.isConversationEnd(s));
		}
	}

	@Test
	public void questionsGoOn() {
		for (String s : new String[]{"Минск", "а завтра", "всё равно расскажи", "спасибо, а какая погода завтра",
				"хватит ли бензина до Минска", "стоп-кран"}) {
			assertFalse(s, JarvisFragment.isConversationEnd(s));
		}
	}
}
