package com.example.hl7poc.secure.key;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import com.example.hl7poc.common.util.Base64Utils;

/**
 * 환경변수로 주입받는 키 공급자. 로컬 개발과 PoC 용이다.
 *
 * <p>운영에서는 {@code KeyStoreKeyProvider}(JCEKS) 나 KMS 연동 구현으로 바꾼다.
 * 빈 클래스만 교체하면 되도록 {@link KeyProvider} 뒤에 두었다.
 *
 * <p><b>기동 시점에 실패시키는 것이 핵심이다.</b> 키가 비어 있는데 조용히 뜨면,
 * 첫 환자 메시지가 들어오는 순간에야 문제를 알게 된다. 그때는 이미 큐에 메시지가
 * 쌓여 있고 DLQ 로 흘러간 뒤다. {@link InitializingBean} 으로 컨텍스트 로딩
 * 단계에서 막는다.
 */
public class EnvKeyProvider implements KeyProvider, InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(EnvKeyProvider.class);

    /** AES-256. 32바이트가 아니면 거부한다. */
    private static final int REQUIRED_KEY_BYTES = 32;

    private String currentKeyId;
    private String encodedKey;

    /**
     * 회전 이전 키들. {@code keyId -> Base64 키}.
     * 과거 암호문을 읽으려면 필요하다. 새로 암호화할 때는 쓰이지 않는다.
     */
    private Map<String, String> previousKeys = new LinkedHashMap<>();

    private Map<String, SecretKey> keys = Collections.emptyMap();

    public void setCurrentKeyId(String currentKeyId) {
        this.currentKeyId = currentKeyId;
    }

    public void setEncodedKey(String encodedKey) {
        this.encodedKey = encodedKey;
    }

    public void setPreviousKeys(Map<String, String> previousKeys) {
        this.previousKeys = (previousKeys == null) ? new LinkedHashMap<String, String>() : previousKeys;
    }

    @Override
    public void afterPropertiesSet() {
        if (isBlank(currentKeyId)) {
            throw new IllegalStateException(
                    "PHI 암호화 키 식별자가 비어 있습니다. 환경변수 HL7POC_PHI_KEY_ID 를 설정하세요.");
        }
        if (isBlank(encodedKey)) {
            // 키 값 자체는 절대 메시지에 넣지 않는다. 예외 메시지는 그대로 로그로 나간다.
            throw new IllegalStateException(
                    "PHI 암호화 키가 비어 있습니다. 환경변수 HL7POC_PHI_KEY 를 설정하세요."
                            + " (생성: openssl rand -base64 32)");
        }

        Map<String, SecretKey> loaded = new HashMap<>();
        loaded.put(currentKeyId, toKey(currentKeyId, encodedKey));
        for (Map.Entry<String, String> e : previousKeys.entrySet()) {
            loaded.put(e.getKey(), toKey(e.getKey(), e.getValue()));
        }
        this.keys = Collections.unmodifiableMap(loaded);

        LOG.info("PHI 키 공급자 준비 완료. currentKeyId={}, 보유 키 수={}",
                currentKeyId, keys.size());
    }

    private SecretKey toKey(String keyId, String base64) {
        byte[] raw;
        try {
            raw = Base64Utils.decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "PHI 키가 Base64 가 아닙니다. keyId=" + keyId, e);
        }
        if (raw.length != REQUIRED_KEY_BYTES) {
            // 길이만 알려 준다. 값은 알려 주지 않는다.
            throw new IllegalStateException("PHI 키 길이가 올바르지 않습니다. keyId=" + keyId
                    + ", 기대=" + REQUIRED_KEY_BYTES + "바이트(AES-256), 실제=" + raw.length + "바이트");
        }
        return new SecretKeySpec(raw, "AES");
    }

    @Override
    public String currentKeyId() {
        return currentKeyId;
    }

    @Override
    public SecretKey currentKey() {
        return keyFor(currentKeyId);
    }

    @Override
    public SecretKey keyFor(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new IllegalArgumentException("알 수 없는 키 식별자입니다: " + keyId
                    + ". 회전 이전 키를 previousKeys 에 남겨 두었는지 확인하세요.");
        }
        return key;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
