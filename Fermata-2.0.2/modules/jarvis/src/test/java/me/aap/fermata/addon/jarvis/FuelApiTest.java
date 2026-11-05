package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** Заправка голосом: разбор ответов А-100 (поля — по журналу 05.11.2026) и приём ответов распознавания. */
public class FuelApiTest {

	@Test
	public void stations() throws Exception {
		JSONObject j = new JSONObject("{\"data\":[" +
				"{\"ID\":1,\"NUMBER\":14,\"NAME\":\"ул. Тростенецкая, 16\",\"POINT\":\"53.8456,27.6340\",\"ACTIVE\":\"Y\"," +
				"\"FUEL\":[{\"NAME\":\"АИ-95\",\"CODE\":\"95\"}]}," +
				"{\"ID\":2,\"NUMBER\":15,\"NAME\":\"закрытая\",\"POINT\":\"53.8455,27.6342\",\"ACTIVE\":\"N\"}," +
				"{\"ID\":3,\"NUMBER\":7,\"NAME_RU\":\"пр-т Независимости\",\"POINT\":\"53.93,27.65\"}]}");
		List<FuelApi.Station> l = FuelApi.parseStations(j);
		assertEquals(2, l.size()); // закрытая скрыта
		assertEquals("пр-т Независимости", l.get(1).name);
		assertEquals("95", l.get(0).fuelCode("АИ-95"));
		List<FuelApi.Station> s = FuelApi.sortByDistance(l, 53.93, 27.65);
		assertEquals("7", s.get(0).number);
		assertTrue(s.get(0).distance < 50);
	}

	@Test
	public void readyCalcCards() throws Exception {
		FuelApi.Ready r = FuelApi.parseReady(new JSONObject("{\"data\":{\"pump_number\":3,\"azs_number\":14,\"nozzle_number\":2," +
				"\"fuel_name\":\"АИ-95\",\"price\":2.5,\"amount\":0,\"liters\":0}}"));
		assertEquals(2, r.nozzle);
		assertEquals(2.5, r.price, 1e-9);
		FuelApi.Calc c = FuelApi.parseCalc(new JSONObject("{\"data\":{\"amount\":50.0,\"liters\":20}}"), 20);
		assertEquals(50.0, c.amount, 1e-9);
		JSONObject me = new JSONObject("{\"data\":{\"token\":{},\"user\":{\"id\":7,\"bank_cards\":[" +
				"{\"id\":12,\"is_main\":false},{\"id\":11,\"is_main\":true}]}}}");
		assertEquals(7, FuelApi.userId(me));
		assertEquals(11, FuelApi.mainCard(me).optLong("id"));
		assertNull(FuelApi.mainCard(new JSONObject("{\"data\":{\"user\":{\"id\":7,\"bank_cards\":[]}}}")));
		assertEquals("777", FuelApi.mainLoyalty(new JSONObject("{\"data\":[{\"number\":\"1\"},{\"number\":\"777\",\"is_main\":true}]}")));
		FuelApi.Station st = FuelApi.parseStations(new JSONObject("{\"data\":[{\"NUMBER\":14,\"NAME\":\"x\",\"POINT\":\"1,2\"," +
				"\"FUEL\":[{\"NAME\":\"АИ-95\",\"CODE\":\"95\"}]}]}")).get(0);
		JSONObject b = FuelApi.payBody(st, 3, r, c, 11, "777");
		assertEquals("card", b.getString("payment_method"));
		assertEquals("FUEL", b.getString("type"));
		assertEquals(14, b.getInt("azs_number"));
		assertEquals(3, b.getInt("pump_number"));
		assertEquals(2, b.getInt("nozzle_number"));
		assertEquals(20, b.getInt("liters"));
		assertEquals("95", b.getString("fuel_code"));
		assertEquals(11, b.getLong("bank_card_id"));
		assertEquals("777", b.getString("loyalty_card_number"));
	}

	@Test
	public void grammarAccept() {
		FuelDialog.State P = FuelDialog.State.ASK_PAY;
		assertEquals("оплатить", FuelGrammar.accept(P, "оплатить", Double.POSITIVE_INFINITY));
		assertEquals("плати", FuelGrammar.accept(P, "плати", 2.5));
		assertEquals("", FuelGrammar.accept(P, "плати", 0.3)); // «платье» → «плати» с малым запасом — переспросить
		assertEquals("", FuelGrammar.accept(P, "плати шестьдесят", 9)); // склейка — не фраза словаря
		assertEquals("", FuelGrammar.accept(P, "", 9));
		assertEquals("двадцать литров", FuelGrammar.accept(P, "двадцать литров", 0)); // не оплата — запас не нужен
		assertEquals("", FuelGrammar.accept(FuelDialog.State.ASK_AMOUNT, "двадцать пятьдесят литров", 9));
		assertEquals("третьей", FuelGrammar.accept(FuelDialog.State.ASK_PUMP, "третьей", 9));
		assertEquals("стоп заправка", FuelGrammar.accept(FuelDialog.State.ASK_PUMP, "стоп заправка", 9));
		// словари: ловушки есть в шаге оплаты, «оплачивай» (нет в модели) — нет
		assertTrue(FuelGrammar.phrases(P).contains("платье"));
		assertTrue(!FuelGrammar.phrases(P).contains("оплачивай"));
		assertEquals("двадцать один", FuelGrammar.words(21));
		assertEquals("сто двадцать", FuelGrammar.words(120));
	}
}
