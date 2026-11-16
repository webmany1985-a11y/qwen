package me.aap.fermata.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Кнопка «Обновить приложение» JARVIS Auto жены (16.11.2026). */
public class ObnovlenieTest {
	@Test
	public void parsesManifest() {
		Obnovlenie.Vypusk v = Obnovlenie.parse(
				"{\"nomer\": 270, \"fajl\": \"jarvis-zhena-270.apk\", \"razmer\": 68403456, \"chastej\": 3}\n");
		assertNotNull(v);
		assertEquals(270, v.nomer);
		assertEquals("jarvis-zhena-270.apk", v.fajl);
		assertEquals(68403456L, v.razmer);
	}

	@Test
	public void rejectsBadManifest() {
		assertNull(Obnovlenie.parse(null));
		assertNull(Obnovlenie.parse(""));
		assertNull(Obnovlenie.parse("<html>404</html>"));
		// номер в имени не тот — не ставить чужой файл
		assertNull(Obnovlenie.parse("{\"nomer\": 270, \"fajl\": \"jarvis-zhena-269.apk\", \"razmer\": 5}"));
		// имя не по образцу
		assertNull(Obnovlenie.parse("{\"nomer\": 270, \"fajl\": \"../x.apk\", \"razmer\": 5}"));
		assertNull(Obnovlenie.parse("{\"nomer\": 270, \"fajl\": \"jarvis-zhena-270.apk\", \"razmer\": 0}"));
	}

	@Test
	public void newerOnlyWhenNumberGrows() {
		Obnovlenie.Vypusk v = Obnovlenie.parse("{\"nomer\":270,\"fajl\":\"jarvis-zhena-270.apk\",\"razmer\":1}");
		assertTrue(Obnovlenie.novee(v, 269));
		assertFalse(Obnovlenie.novee(v, 270));
		assertFalse(Obnovlenie.novee(v, 271));
		assertFalse(Obnovlenie.novee(null, 1));
	}

	@Test
	public void url() {
		assertEquals("https://x.workers.dev/s/jarvis-zhena.json", Obnovlenie.url("https://x.workers.dev/s/", "jarvis-zhena.json"));
		assertEquals("https://x.workers.dev/s/a.apk", Obnovlenie.url("https://x.workers.dev/s", "a.apk"));
	}
}
