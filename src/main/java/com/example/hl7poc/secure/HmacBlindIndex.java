package com.example.hl7poc.secure;

import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.InitializingBean;

import com.example.hl7poc.common.util.Base64Utils;

/**
 * 블라인드 인덱스. 암호화된 컬럼을 검색 가능하게 만드는 장치다.
 *
 * <p><b>문제</b>: 환자 ID 를 암호화해서 저장하면 IV 가 매번 달라 같은 환자라도
 * 암호문이 달라진다. {@code WHERE patient_id_enc = ?} 가 성립하지 않는다.
 *
 * <p><b>해법</b>: 같은 입력에 항상 같은 출력을 내는 값을 따로 저장하고 그걸로 찾는다.
 *
 * <p><b>왜 평문 SHA-256 이 아니라 HMAC 인가</b>: 환자번호나 주민등록번호처럼 값
 * 공간이 좁은 데이터는 전수 해시 테이블을 만들 수 있다. {@code PAT000001} 부터
 * {@code PAT999999} 까지 해시를 미리 계산해 두면 평문 해시는 그대로 뚫린다.
 * 키를 섞은 HMAC 은 키를 모르면 이 계산을 할 수 없다.
 *
 * <p><b>암호화 키와 다른 키를 쓴다</b>. 하나가 유출돼도 나머지가 남도록 분리한다.
 *
 * <p><b>⚠ 정규화 규칙은 한 번 정하면 바꿀 수 없다.</b> 공백 처리나 대소문자
 * 규칙을 나중에 바꾸면 이미 저장된 모든 인덱스가 무효가 되고, 과거 데이터는
 * 검색되지 않는다. 바꾸려면 전수 재계산이 필요하다.
 */
public class HmacBlindIndex implements InitializingBean {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String UTF8 = "UTF-8";
    private static final int MIN_KEY_BYTES = 32;

    private String encodedKey;
    private SecretKeySpec key;

    public void setEncodedKey(String encodedKey) {
        this.encodedKey = encodedKey;
    }

    @Override
    public void afterPropertiesSet() {
        if (encodedKey == null || encodedKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "블라인드 인덱스 키가 비어 있습니다. 환경변수 HL7POC_BLIND_INDEX_KEY 를 설정하세요."
                            + " (생성: openssl rand -base64 32)");
        }
        byte[] raw;
        try {
            raw = Base64Utils.decode(encodedKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("블라인드 인덱스 키가 Base64 가 아닙니다.", e);
        }
        if (raw.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("블라인드 인덱스 키가 너무 짧습니다. 최소 "
                    + MIN_KEY_BYTES + "바이트, 실제 " + raw.length + "바이트");
        }
        this.key = new SecretKeySpec(raw, ALGORITHM);
    }

    /**
     * @return 소문자 hex 64자. DB 의 {@code patient_id_hash VARCHAR(64)} 와 맞춘다.
     *         입력이 비어 있으면 null.
     */
    public String index(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            // Mac 은 스레드 세이프하지 않다. 컨슈머가 10개까지 늘어나는 구조에서
            // 필드로 공유하면 해시가 조용히 섞인다.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(normalize(value).getBytes(UTF8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException | UnsupportedEncodingException e) {
            throw new PhiCipherException("블라인드 인덱스 계산에 실패했습니다.", e);
        }
    }

    /** 앞뒤 공백만 제거한다. 대소문자는 건드리지 않는다(식별자는 대소문자가 의미를 가질 수 있다). */
    private static String normalize(String value) {
        return value.trim();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
