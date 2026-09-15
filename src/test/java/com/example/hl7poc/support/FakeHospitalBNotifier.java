package com.example.hl7poc.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.exception.PermanentProcessingException;
import com.example.hl7poc.common.exception.TransientProcessingException;
import com.example.hl7poc.ws.client.HospitalBNotifier;

/**
 * 병원 B 연동 대역. 실제 SOAP 클라이언트는 6단계 산출물이다.
 *
 * <p>테스트가 응답을 지정할 수 있어야 롤백 경로를 볼 수 있다. 실제 상대 시스템으로는
 * "타임아웃을 내 달라"고 부탁할 수 없기 때문이다.
 */
public class FakeHospitalBNotifier implements HospitalBNotifier {

    public enum Behavior {
        SUCCEED,
        /** 타임아웃/연결 실패 — 재시도 대상 */
        FAIL_TRANSIENT,
        /** 상대의 업무적 거절 — 재시도 무의미 */
        FAIL_PERMANENT
    }

    private volatile Behavior behavior = Behavior.SUCCEED;
    private final List<String> receivedControlIds = new CopyOnWriteArrayList<>();

    @Override
    public void notifyAdtEvent(AdtEvent event) {
        receivedControlIds.add(event.getHeader().getMessageControlId());
        switch (behavior) {
            case FAIL_TRANSIENT:
                throw new TransientProcessingException("WS-TIMEOUT",
                        "병원 B 가 응답하지 않습니다(테스트용).");
            case FAIL_PERMANENT:
                throw new PermanentProcessingException("WS-REJECTED",
                        "병원 B 가 거절했습니다(테스트용).");
            default:
                break;
        }
    }

    public void reset(Behavior behavior) {
        this.behavior = behavior;
        this.receivedControlIds.clear();
    }

    /** 실제로 전달된 횟수. 중복 메시지가 두 번 전달되지 않는지 확인하는 데 쓴다. */
    public int callCount() {
        return receivedControlIds.size();
    }

    public List<String> receivedControlIds() {
        return receivedControlIds;
    }
}
