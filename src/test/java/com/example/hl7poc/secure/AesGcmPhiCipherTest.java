package com.example.hl7poc.secure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import com.example.hl7poc.common.util.Base64Utils;
import com.example.hl7poc.secure.key.EnvKeyProvider;

/**
 * AES-256-GCM 암복호화 검증. 모든 평문은 가상의 합성 데이터다.
 */
public class AesGcmPhiCipherTest {

    private static final String KEY_V1 = randomKey();
    private static final String KEY_V2 = randomKey();

    private static AesGcmPhiCipher cipher;

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64Utils.encode(raw);
    }

    private static AesGcmPhiCipher cipherWith(String keyId, String key,
                                              java.util.Map<String, String> previous) {
        EnvKeyProvider provider = new EnvKeyProvider();
        provider.setCurrentKeyId(keyId);
        provider.setEncodedKey(key);
        provider.setPreviousKeys(previous);
        provider.afterPropertiesSet();
        return new AesGcmPhiCipher(provider);
    }

    @BeforeClass
    public static void setUp() {
        cipher = cipherWith("k1", KEY_V1, Collections.<String, String>emptyMap());
    }

    // ------------------------------------------------------------------

    @Test
    public void 암호화하고_다시_복호화하면_원문이_나온다() {
        String plain = "홍길동";
        String enc = cipher.encrypt(plain);
        assertNotEquals(plain, enc);
        assertEquals(plain, cipher.decrypt(enc));
    }

    @Test
    public void 한글과_긴_문자열도_처리한다() {
        String plain = "서울특별시 종로구 세종대로 1 (광화문) 03172";
        assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)));
    }

    @Test
    public void 암호문_형식은_v1_keyId_iv_ct_이다() {
        String enc = cipher.encrypt("PAT000001");
        String[] parts = enc.split(":");
        assertEquals(4, parts.length);
        assertEquals("v1", parts[0]);
        assertEquals("k1", parts[1]);
        // IV 는 12바이트 → Base64 16자
        assertEquals(12, Base64Utils.decode(parts[2]).length);
        assertTrue("암호문에 평문이 남으면 안 된다", !enc.contains("PAT000001"));
    }

    @Test
    public void 같은_평문이라도_매번_다른_암호문이_나온다() {
        // IV 를 재사용하면 GCM 은 평문 복원이 가능한 수준으로 무너진다.
        // 같은 값을 여러 번 암호화해 전부 다른지 본다.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(cipher.encrypt("PAT000001"));
        }
        assertEquals("100번 모두 달라야 한다", 100, seen.size());
    }

    @Test
    public void 빈_값은_암호화하지_않고_그대로_통과시킨다() {
        // HL7 선택 필드는 자주 비어 있다. 빈 값을 암호화하면 조회할 때마다
        // 복호화를 돌려야 하는데 얻는 것이 없다.
        assertNull(cipher.encrypt(null));
        assertEquals("", cipher.encrypt(""));
        assertNull(cipher.decrypt(null));
        assertEquals("", cipher.decrypt(""));
    }

    // ------------------------------------------------------------------
    // 무결성
    // ------------------------------------------------------------------

    @Test
    public void 암호문이_변조되면_복호화가_실패한다() {
        String enc = cipher.encrypt("홍길동");
        String[] parts = enc.split(":");

        // 암호문 본문의 첫 바이트를 뒤집는다.
        byte[] ct = Base64Utils.decode(parts[3]);
        ct[0] ^= 0x01;
        String tampered = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + Base64Utils.encode(ct);

        assertCipherFails(tampered, "변조된 암호문");
    }

    @Test
    public void 접두부의_keyId_를_바꿔치기하면_복호화가_실패한다() {
        // 접두부는 평문으로 노출된다. AAD 로 묶어 두지 않으면 이 조작이 통한다.
        AesGcmPhiCipher twoKeys = cipherWith("k2", KEY_V2,
                Collections.singletonMap("k1", KEY_V1));

        String enc = twoKeys.encrypt("홍길동");          // k2 로 암호화
        String swapped = enc.replaceFirst("^v1:k2:", "v1:k1:");

        assertCipherFails2(twoKeys, swapped, "keyId 바꿔치기");
    }

    @Test
    public void 형식이_어긋나면_거부한다() {
        assertCipherFails("그냥 문자열", "형식 없음");
        assertCipherFails("v1:k1:onlythree", "필드 부족");
        assertCipherFails("v9:k1:AAAA:BBBB", "모르는 버전");
    }

    @Test
    public void 모르는_키로_암호화된_값은_명확히_실패한다() {
        AesGcmPhiCipher other = cipherWith("kX", KEY_V2, Collections.<String, String>emptyMap());
        String enc = other.encrypt("홍길동");
        try {
            cipher.decrypt(enc);   // k1 만 가진 cipher
            fail("모르는 keyId 는 실패해야 한다");
        } catch (IllegalArgumentException | PhiCipherException expected) {
            assertTrue("무엇이 문제인지 알 수 있어야 한다",
                    expected.getMessage().contains("kX"));
        }
    }

    // ------------------------------------------------------------------
    // 키 회전
    // ------------------------------------------------------------------

    @Test
    public void 키를_회전해도_과거_암호문을_읽을_수_있다() {
        // 회전 전: k1 으로 암호화
        AesGcmPhiCipher before = cipherWith("k1", KEY_V1, Collections.<String, String>emptyMap());
        String oldCiphertext = before.encrypt("홍길동");

        // 회전 후: k2 가 현재 키, k1 은 이전 키로 보관
        AesGcmPhiCipher after = cipherWith("k2", KEY_V2,
                Collections.singletonMap("k1", KEY_V1));

        assertEquals("과거 데이터를 읽을 수 있어야 한다", "홍길동", after.decrypt(oldCiphertext));
        assertTrue("새 암호화는 새 키를 쓴다", after.encrypt("김영희").startsWith("v1:k2:"));
    }

    // ------------------------------------------------------------------
    // 키 공급자 fail-fast
    // ------------------------------------------------------------------

    @Test
    public void 키가_비어_있으면_기동_시점에_실패한다() {
        // 첫 환자 메시지가 들어올 때가 아니라 컨텍스트 로딩 때 막아야 한다.
        EnvKeyProvider provider = new EnvKeyProvider();
        provider.setCurrentKeyId("k1");
        provider.setEncodedKey("");
        try {
            provider.afterPropertiesSet();
            fail("기동이 실패해야 한다");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("HL7POC_PHI_KEY"));
        }
    }

    @Test
    public void 키_길이가_AES256_이_아니면_실패한다() {
        EnvKeyProvider provider = new EnvKeyProvider();
        provider.setCurrentKeyId("k1");
        provider.setEncodedKey(Base64Utils.encode(new byte[16]));   // AES-128 길이
        try {
            provider.afterPropertiesSet();
            fail("기동이 실패해야 한다");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("16바이트"));
            assertFalse("키 값 자체를 메시지에 넣으면 안 된다",
                    expected.getMessage().contains("AAAA"));
        }
    }

    // ------------------------------------------------------------------

    private static void assertCipherFails(String ciphertext, String what) {
        assertCipherFails2(cipher, ciphertext, what);
    }

    private static void assertCipherFails2(AesGcmPhiCipher c, String ciphertext, String what) {
        try {
            c.decrypt(ciphertext);
            fail(what + " 은 거부되어야 합니다");
        } catch (PhiCipherException expected) {
            assertFalse("예외 메시지에 평문이 들어가면 안 된다",
                    expected.getMessage().contains("홍길동"));
        }
    }
}
