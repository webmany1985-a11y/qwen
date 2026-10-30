package me.aap.fermata.addon.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import me.aap.utils.log.Log;

/**
 * Пароли сайтов разговора (Qwen, DeepSeek) — только на телефоне и только зашифрованными (пожелание пользователя
 * 30.10.2026: «строки ввода логина и пароля, которые должны сохраняться и подгружаться заново»). Ключ AES-GCM живёт в
 * Android Keystore и наружу не выходит; в настройках Fermata, журнале и репозитории пароля нет.
 * Не расшифровалось (ключ сменился после переустановки) — пароля нет, его нужно ввести заново.
 */
final class ChatSecrets {
	private static final String KEY_ALIAS = "jarvis_chat_secrets";
	private static final String PREFS = "jarvis_chat_secrets";

	private ChatSecrets() {
	}

	static void put(@NonNull Context ctx, @NonNull String site, @Nullable String password) {
		SharedPreferences sp = prefs(ctx);
		if ((password == null) || password.isEmpty()) {
			sp.edit().remove(site).apply();
			return;
		}
		try {
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, key());
			byte[] iv = c.getIV();
			byte[] data = c.doFinal(password.getBytes(StandardCharsets.UTF_8));
			sp.edit().putString(site, Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
					Base64.encodeToString(data, Base64.NO_WRAP)).apply();
		} catch (Exception ex) {
			Log.e(ex, "[CHAT] password not saved for ", site);
		}
	}

	@Nullable
	static String get(@NonNull Context ctx, @NonNull String site) {
		String v = prefs(ctx).getString(site, null);
		if (v == null) return null;
		try {
			int i = v.indexOf(':');
			byte[] iv = Base64.decode(v.substring(0, i), Base64.NO_WRAP);
			byte[] data = Base64.decode(v.substring(i + 1), Base64.NO_WRAP);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
			return new String(c.doFinal(data), StandardCharsets.UTF_8);
		} catch (Exception ex) {
			Log.e(ex, "[CHAT] password not readable for ", site);
			return null;
		}
	}

	static boolean has(@NonNull Context ctx, @NonNull String site) {
		return prefs(ctx).contains(site);
	}

	private static SharedPreferences prefs(Context ctx) {
		return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private static SecretKey key() throws Exception {
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
		KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
		g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.build());
		return g.generateKey();
	}
}
