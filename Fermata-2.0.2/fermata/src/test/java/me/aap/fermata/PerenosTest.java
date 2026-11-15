package me.aap.fermata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PerenosTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static byte[] zip(String... nameText) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(b)) {
			for (int i = 0; i < nameText.length; i += 2) {
				z.putNextEntry(new ZipEntry(nameText[i]));
				if (nameText[i + 1] != null) z.write(nameText[i + 1].getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
		return b.toByteArray();
	}

	private static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	@Test
	public void unpacksOnceAndOverwrites() throws IOException {
		File d = tmp.newFolder("data");
		File prefs = new File(d, "shared_prefs/fermata.xml");
		prefs.getParentFile().mkdirs();
		Files.write(prefs.toPath(), "staroe".getBytes(StandardCharsets.UTF_8));
		byte[] z = zip("shared_prefs/fermata.xml", "<map/>", "app_webview/Default/Local Storage/leveldb/CURRENT", "M1",
				"app_webview/Default/IndexedDB/", null);
		assertEquals(2, Perenos.apply(d, new ByteArrayInputStream(z)));
		assertEquals("<map/>", read(prefs));
		assertEquals("M1", read(new File(d, "app_webview/Default/Local Storage/leveldb/CURRENT")));
		assertTrue(new File(d, "app_webview/Default/IndexedDB").isDirectory());
		assertTrue(new File(d, Perenos.MARKER).isFile());
		// второй запуск: метка есть — настройки, изменённые на новом телефоне, не перетираются
		Files.write(prefs.toPath(), "novoe".getBytes(StandardCharsets.UTF_8));
		assertEquals(0, Perenos.apply(d, new ByteArrayInputStream(z)));
		assertEquals("novoe", read(prefs));
	}

	@Test
	public void rejectsPathOutsideDataDir() throws IOException {
		File d = tmp.newFolder("data2");
		try {
			Perenos.apply(d, new ByteArrayInputStream(zip("../vne.txt", "x")));
			fail();
		} catch (IOException expected) {
		}
		assertFalse(new File(d.getParentFile(), "vne.txt").exists());
		assertFalse(new File(d, Perenos.MARKER).exists());
	}
}
