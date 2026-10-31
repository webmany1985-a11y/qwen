package me.aap.fermata.jarvis;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FullVolumeTest {

	@Test
	public void raisesToMaxAndRestores() {
		FullVolume v = new FullVolume();
		assertEquals(15, v.begin(5, 15));
		assertEquals(5, v.end(15));
	}

	@Test
	public void alreadyMaxNothingToDo() {
		FullVolume v = new FullVolume();
		assertEquals(-1, v.begin(15, 15));
		assertEquals(-1, v.end(15));
	}

	@Test
	public void overlappingSpeechRestoresOnceAfterLast() {
		FullVolume v = new FullVolume();
		assertEquals(15, v.begin(4, 15));
		assertEquals(-1, v.begin(15, 15));
		assertEquals(-1, v.end(15));
		assertEquals(4, v.end(15));
	}

	@Test
	public void userChangedVolumeDuringSpeechIsKept() {
		FullVolume v = new FullVolume();
		assertEquals(15, v.begin(4, 15));
		assertEquals(-1, v.end(10));
		// Следующая речь — снова от текущей
		assertEquals(15, v.begin(10, 15));
		assertEquals(10, v.end(15));
	}

	@Test
	public void extraEndIgnored() {
		FullVolume v = new FullVolume();
		assertEquals(-1, v.end(7));
		assertEquals(15, v.begin(7, 15));
		assertEquals(7, v.end(15));
	}
}
