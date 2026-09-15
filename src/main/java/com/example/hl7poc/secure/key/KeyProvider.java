package com.example.hl7poc.secure.key;

import javax.crypto.SecretKey;

/**
 * PHI 암호화 키 공급자.
 *
 * <p>키를 어디에 두느냐(환경변수 / JCEKS 키스토어 / KMS)를 이 인터페이스 뒤로
 * 숨긴다. 암호화 코드는 키가 어디서 오는지 몰라야 한다.
 *
 * <p><b>keyId 로 조회하는 메서드가 따로 있는 이유</b>: 키를 회전하면 과거에
 * 저장된 암호문은 예전 키로만 풀린다. 암호문 앞에 박아 둔 keyId 로 그때의 키를
 * 찾을 수 있어야 한다. 이 메서드가 없으면 키 회전이 곧 과거 데이터 유실이다.
 */
public interface KeyProvider {

    /** 새로 암호화할 때 쓸 키의 식별자. */
    String currentKeyId();

    /** 새로 암호화할 때 쓸 키. */
    SecretKey currentKey();

    /**
     * 주어진 식별자의 키. 복호화 경로에서 쓴다.
     *
     * @throws IllegalArgumentException 모르는 keyId 일 때
     */
    SecretKey keyFor(String keyId);
}
