package com.example.hl7poc.secure;

import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

import com.example.hl7poc.common.util.Base64Utils;
import com.example.hl7poc.secure.key.KeyProvider;

/**
 * AES-256-GCM 기반 PHI 암복호화.
 *
 * <p>암호문 형식: <code>v1:{keyId}:{base64 IV}:{base64 암호문+태그}</code>
 *
 * <p><b>왜 형식에 버전과 keyId 를 박는가</b>
 * <ul>
 *   <li>{@code v1} — 나중에 알고리즘을 바꿔도 과거 데이터를 읽을 수 있다. 버전이
 *       없으면 "이 컬럼의 값이 어느 방식으로 암호화됐는지"를 알 방법이 없어서,
 *       전수 재암호화 전에는 알고리즘을 못 바꾼다.</li>
 *   <li>{@code keyId} — 키를 회전해도 과거 데이터를 읽을 수 있다. 이게 없으면
 *       키 회전이 곧 데이터 유실이다.</li>
 * </ul>
 *
 * <p><b>왜 GCM 인가</b>: CBC 는 암호화만 하고 무결성은 보장하지 않는다. DB 가
 * 털린 뒤 암호문 비트를 뒤집는 공격(비트 플리핑)이 통한다. GCM 은 인증 태그로
 * 변조를 탐지한다.
 *
 * <p><b>AAD 에 {@code v1:keyId} 를 묶는 이유</b>: 앞의 접두부는 암호화 대상이
 * 아니라 평문으로 노출된다. AAD 로 묶어 두면 접두부를 다른 keyId 로 바꿔치기해도
 * 복호화가 실패한다. 묶지 않으면 접두부만 조작해 복호화 실패를 유도하거나
 * 다른 키로 오인하게 만들 수 있다.
 *
 * <p>스레드 세이프하다. {@link Cipher} 는 스레드 세이프하지 않으므로 호출마다
 * 새로 만든다. {@link SecureRandom} 은 스레드 세이프해서 공유한다.
 */
public class AesGcmPhiCipher implements PhiCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String VERSION = "v1";
    private static final String UTF8 = "UTF-8";

    /** GCM 권장 IV 길이. 12바이트가 아니면 내부적으로 해시를 한 번 더 돌아 느려진다. */
    private static final int IV_BYTES = 12;

    /** 인증 태그 길이(비트). 128 이 최대이자 권장값이다. */
    private static final int TAG_BITS = 128;

    private static final int PARTS = 4;

    private final KeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmPhiCipher(KeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        final String keyId = keyProvider.currentKeyId();

        byte[] iv = new byte[IV_BYTES];
        // IV 는 매 호출 새로 만든다. GCM 에서 같은 키로 IV 를 재사용하면
        // 평문을 복원할 수 있는 수준으로 무너진다. 상수 IV 는 치명적 결함이다.
        secureRandom.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.currentKey(),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(keyId));
            byte[] ct = cipher.doFinal(plaintext.getBytes(UTF8));

            return VERSION + ':' + keyId + ':' + Base64Utils.encode(iv) + ':' + Base64Utils.encode(ct);

        } catch (GeneralSecurityException | UnsupportedEncodingException e) {
            // 평문은 절대 메시지에 넣지 않는다.
            throw new PhiCipherException("PHI 암호화에 실패했습니다. keyId=" + keyId, e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }

        // limit 을 주어 Base64 안의 ':' 가 잘리지 않게 한다(Base64 표준 알파벳에는
        // ':' 가 없지만, 형식이 바뀔 때를 대비한 방어다).
        String[] parts = ciphertext.split(":", PARTS);
        if (parts.length != PARTS) {
            throw new PhiCipherException("PHI 암호문 형식이 아닙니다. 필드 수=" + parts.length);
        }
        if (!VERSION.equals(parts[0])) {
            throw new PhiCipherException("지원하지 않는 암호문 버전입니다: " + parts[0]);
        }

        final String keyId = parts[1];
        try {
            byte[] iv = Base64Utils.decode(parts[2]);
            byte[] ct = Base64Utils.decode(parts[3]);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.keyFor(keyId),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(keyId));
            return new String(cipher.doFinal(ct), UTF8);

        } catch (GeneralSecurityException e) {
            // AEADBadTagException 도 여기로 온다. 변조되었거나 키가 다르다.
            throw new PhiCipherException(
                    "PHI 복호화에 실패했습니다(변조되었거나 키가 다릅니다). keyId=" + keyId, e);
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            throw new PhiCipherException("PHI 암호문을 해석할 수 없습니다. keyId=" + keyId, e);
        }
    }

    /** 평문으로 노출되는 접두부를 인증 대상에 포함시킨다. */
    private static byte[] aad(String keyId) {
        try {
            return (VERSION + ':' + keyId).getBytes(UTF8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 을 쓸 수 없습니다.", e);
        }
    }
}
