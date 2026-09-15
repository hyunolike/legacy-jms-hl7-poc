package com.example.hl7poc.secure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.security.SecureRandom;

import org.junit.BeforeClass;
import org.junit.Test;

import com.example.hl7poc.common.util.Base64Utils;

/**
 * 블라인드 인덱스 검증.
 *
 * <p>요구사항은 두 가지가 동시에 성립해야 한다는 것이다.
 * <ul>
 *   <li>같은 값 → 항상 같은 인덱스 (그래야 WHERE 절로 찾을 수 있다)</li>
 *   <li>인덱스만으로는 원래 값을 알 수 없다</li>
 * </ul>
 */
public class HmacBlindIndexTest {

    private static HmacBlindIndex index;
    private static String key;

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64Utils.encode(raw);
    }

    private static HmacBlindIndex build(String encodedKey) {
        HmacBlindIndex h = new HmacBlindIndex();
        h.setEncodedKey(encodedKey);
        h.afterPropertiesSet();
        return h;
    }

    @BeforeClass
    public static void setUp() {
        key = randomKey();
        index = build(key);
    }

    @Test
    public void 같은_값은_항상_같은_인덱스를_낸다() {
        String a = index.index("PAT000001");
        String b = index.index("PAT000001");
        assertEquals(a, b);
    }

    @Test
    public void 다른_값은_다른_인덱스를_낸다() {
        assertNotEquals(index.index("PAT000001"), index.index("PAT000002"));
    }

    @Test
    public void 인덱스는_64자_소문자_hex_다() {
        String h = index.index("PAT000001");
        assertEquals("DB 의 VARCHAR(64) 와 맞춘다", 64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
    }

    @Test
    public void 원래_값이_인덱스에_남지_않는다() {
        assertFalse(index.index("PAT000001").contains("PAT"));
        assertFalse(index.index("PAT000001").contains("000001"));
    }

    @Test
    public void 키가_다르면_같은_값이라도_다른_인덱스가_나온다() {
        // 이게 성립하지 않으면 평문 해시와 다를 바 없다.
        HmacBlindIndex other = build(randomKey());
        assertNotEquals(index.index("PAT000001"), other.index("PAT000001"));
    }

    @Test
    public void 앞뒤_공백은_무시한다() {
        assertEquals(index.index("PAT000001"), index.index("  PAT000001  "));
    }

    @Test
    public void 대소문자는_구분한다() {
        // 식별자는 대소문자가 의미를 가질 수 있다. 임의로 접어 버리면
        // 서로 다른 환자가 같은 인덱스를 갖게 될 수 있다.
        assertNotEquals(index.index("pat000001"), index.index("PAT000001"));
    }

    @Test
    public void 빈_값은_null_이다() {
        assertNull(index.index(null));
        assertNull(index.index("   "));
    }

    @Test
    public void 키가_없거나_짧으면_기동_시점에_실패한다() {
        assertRejectedKey(null, "HL7POC_BLIND_INDEX_KEY");
        assertRejectedKey("", "HL7POC_BLIND_INDEX_KEY");
        assertRejectedKey(Base64Utils.encode(new byte[16]), "너무 짧습니다");
    }

    private static void assertRejectedKey(String encodedKey, String expectedFragment) {
        HmacBlindIndex h = new HmacBlindIndex();
        h.setEncodedKey(encodedKey);
        try {
            h.afterPropertiesSet();
            fail("거부되어야 합니다: " + encodedKey);
        } catch (IllegalStateException expected) {
            assertTrue("메시지=" + expected.getMessage(),
                    expected.getMessage().contains(expectedFragment));
        }
    }
}
