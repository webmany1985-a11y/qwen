package me.aap.fermata.addon.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import me.aap.fermata.ui.activity.TabMediaRules;

/** Пожелание 14.11.2026: звук только из видимой вкладки, кроме JARVIS; после перезапуска — туда, где шло видео. */
public class TabMediaRulesTest {
	private static final int JARVIS = 1, TIKTOK = 2, YOUTUBE = 3, VIDEO = 4;

	@Test
	public void переходНаДругуюВкладкуОстанавливаетПрежнюю() {
		assertTrue(TabMediaRules.shouldPauseOthers(YOUTUBE, JARVIS, TIKTOK));
		assertTrue(TabMediaRules.shouldPauseOthers(TIKTOK, JARVIS, 0));
	}

	@Test
	public void вкладкаJarvisНичегоНеОстанавливает() {
		assertFalse(TabMediaRules.shouldPauseOthers(JARVIS, JARVIS, TIKTOK));
	}

	@Test
	public void возвратСJarvisНаТуЖеВкладкуНеОстанавливает() {
		assertFalse(TabMediaRules.shouldPauseOthers(TIKTOK, JARVIS, TIKTOK));
	}

	@Test
	public void общийПроигрыватель() {
		// ТВ шло, ушли на TikTok напрямую — пауза
		assertTrue(TabMediaRules.shouldPauseSession(true, false, false));
		// через JARVIS, играет то же, что при уходе на JARVIS — пауза
		assertTrue(TabMediaRules.shouldPauseSession(true, true, true));
		// JARVIS по команде включил новое — не трогать
		assertFalse(TabMediaRules.shouldPauseSession(true, true, false));
		assertFalse(TabMediaRules.shouldPauseSession(false, false, false));
	}

	@Test
	public void продолжитьПослеПерезапуска() {
		long t = 1_000_000L;
		assertTrue(TabMediaRules.shouldResume(VIDEO, true, 0, t));
		// пауза пришла от закрытия окна (машина заглохла)
		assertTrue(TabMediaRules.shouldResume(VIDEO, false, t - 2000, t));
		// поставил на паузу сам задолго до выхода
		assertFalse(TabMediaRules.shouldResume(VIDEO, false, t - 600_000, t));
		assertFalse(TabMediaRules.shouldResume(VIDEO, false, t - 2000, 0));
		// видео не было — вкладка по умолчанию (сфера)
		assertFalse(TabMediaRules.shouldResume(0, true, 0, t));
	}

	@Test
	public void секундаПродолжения() {
		assertEquals(1234.5, TabMediaRules.resumeAt(1234.5, 6000), 0.01);
		assertEquals(0, TabMediaRules.resumeAt(2, 6000), 0.01);
		assertEquals(0, TabMediaRules.resumeAt(5995, 6000), 0.01);
		assertEquals(300, TabMediaRules.resumeAt(300, -1), 0.01);
	}
}
