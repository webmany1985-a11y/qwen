package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Что уходит JARVIS, что — Qwen, и как ответ Qwen готовится к озвучиванию. */
public class QwenChatTest {

	@Test
	public void phoneCommandsGoToJarvis() {
		assertTrue(QwenChat.isPhoneCommand("позвони маме"));
		assertTrue(QwenChat.isPhoneCommand("Открой YouTube"));
		assertTrue(QwenChat.isPhoneCommand("джарвис, включи музыку"));
		assertTrue(QwenChat.isPhoneCommand("сделай громче"));
		assertTrue(QwenChat.isPhoneCommand("проложи маршрут до Минска"));
		assertTrue(QwenChat.isPhoneCommand("напиши смс Ивану"));
		assertTrue(QwenChat.isPhoneCommand("что с домашкой у Софии"));
		assertTrue(QwenChat.isPhoneCommand("Марк сделал уроки?"));
		assertTrue(QwenChat.isPhoneCommand("что задали Софии"));
		// Школа и дети из «Домашки» (30.10.2026)
		assertTrue(QwenChat.isPhoneCommand("какие уроки завтра у Марка"));
		assertTrue(QwenChat.isPhoneCommand("во сколько заканчиваются уроки"));
		assertTrue(QwenChat.isPhoneCommand("расписание Софии на понедельник"));
		assertTrue(QwenChat.isPhoneCommand("четвертные оценки Софии"));
		assertTrue(QwenChat.isPhoneCommand("где Марк"));
		assertTrue(QwenChat.isPhoneCommand("Джарвис, где сейчас дети?"));
		assertTrue(QwenChat.isPhoneCommand("София уже дома?"));
		assertTrue(QwenChat.isPhoneCommand("Марк пришёл домой"));
		assertTrue(QwenChat.isPhoneCommand("что с питанием у Марка"));
		assertTrue(QwenChat.isPhoneCommand("есть ли задолженность"));
		assertTrue(QwenChat.isPhoneCommand("какие кружки у Софии"));
	}

	@Test
	public void phoneCommandFoundInAnyAlternative() {
		assertEquals("Включи музыку",
				QwenChat.phoneCommand(java.util.Arrays.asList("музыку", "Включи музыку")));
		assertEquals(null, QwenChat.phoneCommand(java.util.Arrays.asList("как дела", "как дело")));
	}

	@Test
	public void conversationGoesToQwen() {
		assertFalse(QwenChat.isPhoneCommand("какая погода завтра в Минске"));
		assertFalse(QwenChat.isPhoneCommand("расскажи анекдот"));
		assertFalse(QwenChat.isPhoneCommand("напиши стихотворение про осень"));
		assertFalse(QwenChat.isPhoneCommand("что такое договор подряда"));
		assertFalse(QwenChat.isPhoneCommand("кто открыл Америку"));
		assertFalse(QwenChat.isPhoneCommand("где находится Эйфелева башня"));
		assertFalse(QwenChat.isPhoneCommand("когда заканчивается сезон дождей"));
		assertFalse(QwenChat.isPhoneCommand("сколько стоит билет в Брест"));
		assertFalse(QwenChat.isPhoneCommand(null));
	}

	@Test
	public void markupRemovedForSpeech() {
		assertEquals("Итог: два плюс два — четыре.", QwenChat.forSpeech("**Итог:** два плюс два — четыре."));
		assertEquals("Первое Второе", QwenChat.forSpeech("- Первое\n- Второе"));
		assertEquals("Смотрите сайт.", QwenChat.forSpeech("Смотрите [сайт](https://example.com)."));
		assertEquals("Код: .", QwenChat.forSpeech("Код: ```java\nint a;\n```."));
		assertEquals("Заголовок Текст", QwenChat.forSpeech("## Заголовок\nТекст"));
	}

	@Test
	public void longReplyCutAtSentence() {
		StringBuilder b = new StringBuilder();
		while (b.length() < 3000) b.append("Это одно предложение ответа. ");
		String s = QwenChat.forSpeech(b.toString());
		assertTrue(s.length() <= QwenChat.MAX_SPEECH);
		assertTrue(s.endsWith("."));
	}
}
