package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** «Маршрут на работу» и подобное — куда прокладывать маршрут. */
public class RoutePhraseTest {

	@Test
	public void workAndHome() {
		assertEquals(RoutePhrase.WORK, RoutePhrase.destination("маршрут на работу"));
		assertEquals(RoutePhrase.WORK, RoutePhrase.destination("Проложи маршрут до работы"));
		assertEquals(RoutePhrase.WORK, RoutePhrase.destination("поехали на работу"));
		assertEquals(RoutePhrase.HOME, RoutePhrase.destination("поехали домой"));
		assertEquals(RoutePhrase.HOME, RoutePhrase.destination("маршрут домой"));
		assertEquals(RoutePhrase.HOME, RoutePhrase.destination("Джарвис, вези меня домой"));
	}

	@Test
	public void placeByWords() {
		assertEquals("вокзала", RoutePhrase.destination("проложи маршрут до вокзала"));
		assertEquals("минск, улица немига 5", RoutePhrase.destination("маршрут до Минск, улица Немига 5"));
	}

	@Test
	public void notARoute() {
		assertNull(RoutePhrase.destination("какая погода"));
		assertNull(RoutePhrase.destination("маршрут"));
		assertNull(RoutePhrase.destination("во сколько будет автобус 311"));
		assertNull(RoutePhrase.destination("что такое маршрутизатор"));
	}
}
