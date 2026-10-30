package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ChatPlanTest {

	@Test
	public void mainThenFallback() {
		assertEquals(Arrays.asList(ChatPlan.Site.QWEN, ChatPlan.Site.DEEPSEEK), ChatPlan.order("qwen", true));
		assertEquals(Arrays.asList(ChatPlan.Site.DEEPSEEK, ChatPlan.Site.QWEN), ChatPlan.order("deepseek", true));
		// Запасная выключена — только основная
		assertEquals(Collections.singletonList(ChatPlan.Site.DEEPSEEK), ChatPlan.order("deepseek", false));
		// Неизвестное значение — Qwen, как было до 30.10.2026
		assertEquals(ChatPlan.Site.QWEN, ChatPlan.order("", true).get(0));
	}

	@Test
	public void waitDependsOnMode() {
		assertEquals(25_000L, ChatPlan.qwenWait("Быстрый"));
		assertEquals(40_000L, ChatPlan.qwenWait("Авто"));
		assertEquals(60_000L, ChatPlan.qwenWait("Мышление"));
		assertEquals(60_000L, ChatPlan.qwenWait("Thinking"));
		assertEquals(45_000L, ChatPlan.qwenWait(""));
		assertEquals(60_000L, ChatPlan.deepseekWait(true));
		assertEquals(25_000L, ChatPlan.deepseekWait(false));
	}

	@Test
	public void labelsMatchByFirstLetters() {
		assertTrue(ChatPlan.sameLabel("Мышление (Thinking)", "Мышление"));
		assertTrue(ChatPlan.sameLabel("быстрый", "Быстрый"));
		assertFalse(ChatPlan.sameLabel("Авто", "Быстрый"));
		assertFalse(ChatPlan.sameLabel("", "Быстрый"));
	}

	@Test
	public void loginScriptCarriesCredentialsQuoted() {
		String js = WebChat.fillForm("a@b.c", "p\"w'1", "/^x$/", "/^y$/");
		assertTrue(js.contains("\"a@b.c\""));
		assertTrue(js.contains("\"p\\\"w'1\""));
	}
}
