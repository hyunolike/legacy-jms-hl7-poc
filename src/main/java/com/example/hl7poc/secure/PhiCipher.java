package com.example.hl7poc.secure;

/**
 * 환자 식별정보(PHI) 암복호화.
 *
 * <p>{@code null} 과 빈 문자열은 그대로 통과시킨다. HL7 의 선택 필드는 자주
 * 비어 있는데, 빈 값을 암호화하면 "비어 있음"이라는 사실 자체가 암호문으로 바뀌어
 * 조회할 때마다 복호화를 돌려야 한다. 얻는 것 없이 비용만 는다.
 */
public interface PhiCipher {

    /**
     * @return {@code v1:{keyId}:{base64 IV}:{base64 암호문}} 형식. 입력이 null 이거나
     *         비어 있으면 입력을 그대로 돌려준다.
     */
    String encrypt(String plaintext);

    /**
     * @throws com.example.hl7poc.secure.PhiCipherException 형식이 어긋나거나
     *         변조가 감지됐을 때
     */
    String decrypt(String ciphertext);
}
