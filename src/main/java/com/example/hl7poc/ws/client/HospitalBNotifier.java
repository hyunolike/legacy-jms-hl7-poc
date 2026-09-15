package com.example.hl7poc.ws.client;

import com.example.hl7poc.common.dto.AdtEvent;

/**
 * 병원 B 로 입퇴원 이벤트를 전달한다. (구현은 6단계)
 *
 * <p>구현체는 실패를 두 가지로 나눠 던져야 한다.
 * <ul>
 *   <li>{@code TransientProcessingException} — 연결 실패, 타임아웃, 5xx.
 *       잠시 뒤 다시 하면 될 수 있다.</li>
 *   <li>{@code PermanentProcessingException} — 상대의 업무적 거절(없는 환자,
 *       스키마 불일치). 다시 보내도 같은 답이 온다.</li>
 * </ul>
 * 이 구분을 구현체가 하지 않고 전부 한 종류로 던지면, 브리지의 재시도 정책이
 * 통째로 무의미해진다.
 */
public interface HospitalBNotifier {

    void notifyAdtEvent(AdtEvent event);
}
