package com.example.hl7poc.common.util;

import java.util.Base64;

/**
 * Base64 인코딩/디코딩 얇은 래퍼.
 *
 * <p>Java 8 의 {@link Base64} 를 쓴다. 이 클래스를 따로 둔 이유는 Java 7 이하로
 * 내려야 할 때 교체 지점을 한 곳으로 모으기 위해서다(레거시 환경에서는 실제로
 * 일어나는 일이다).
 */
public final class Base64Utils {

    private Base64Utils() {
    }

    public static String encode(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    public static byte[] decode(String encoded) {
        return Base64.getDecoder().decode(encoded);
    }
}
