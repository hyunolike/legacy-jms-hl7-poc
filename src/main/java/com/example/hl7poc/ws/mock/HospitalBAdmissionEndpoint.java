package com.example.hl7poc.ws.mock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jws.WebService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.hl7poc.ws.HospitalBAdmissionPort;
import com.example.hl7poc.ws.dto.AdmissionNotice;
import com.example.hl7poc.ws.dto.AdmissionNoticeResponse;

/**
 * 병원 B 엔드포인트의 mock 구현.
 *
 * <p>상대 시스템으로는 "타임아웃을 내 달라", "거절해 달라"고 부탁할 수 없다.
 * 그래서 mock 이 필요하다. 연동 테스트에서 정작 확인해야 하는 것은 정상 경로가
 * 아니라 <b>실패 경로</b>이기 때문이다.
 *
 * <p>동작을 {@link Behavior} 로 바꿀 수 있고, 받은 통보를 기록해 두어 테스트가
 * "정말 한 번만 갔는지"를 확인할 수 있다.
 */
@WebService(endpointInterface = "com.example.hl7poc.ws.HospitalBAdmissionPort",
            serviceName = HospitalBAdmissionPort.SERVICE_NAME,
            portName = HospitalBAdmissionPort.PORT_NAME,
            targetNamespace = HospitalBAdmissionPort.NAMESPACE)
public class HospitalBAdmissionEndpoint implements HospitalBAdmissionPort {

    private static final Logger LOG = LoggerFactory.getLogger(HospitalBAdmissionEndpoint.class);

    public enum Behavior {
        /** 정상 접수 */
        ACCEPT,
        /** 업무적 거절 — 재시도해도 같은 답이 온다 */
        REJECT,
        /** 응답을 지연시킨다. 클라이언트 읽기 타임아웃 확인용 */
        SLOW,
        /** 서버 내부 오류 — SOAP Fault. 일시 오류로 볼 수 있다 */
        FAULT
    }

    private volatile Behavior behavior = Behavior.ACCEPT;
    private volatile long slowMillis = 10_000L;

    private final AtomicInteger sequence = new AtomicInteger();
    private final List<String> received = new CopyOnWriteArrayList<>();
    /**
     * 수신한 통보 원본. 테스트가 "무엇이 실제로 넘어왔는지"를 확인할 수 있어야 한다.
     * 인코딩 사고는 왕복시켜 보지 않으면 드러나지 않는다.
     */
    private final List<AdmissionNotice> notices = new CopyOnWriteArrayList<>();

    @Override
    public AdmissionNoticeResponse notifyAdmission(AdmissionNotice notice) {
        if (notice == null) {
            return AdmissionNoticeResponse.rejected("통보 본문이 비어 있습니다.");
        }
        received.add(notice.getMessageControlId());
        notices.add(notice);

        // 마스킹된 toString 을 쓴다. 상대 시스템 로그도 결국 누군가 본다.
        LOG.info("[병원B] 통보 수신: {} (behavior={})", notice, behavior);

        switch (behavior) {
            case REJECT:
                return AdmissionNoticeResponse.rejected("등록되지 않은 환자입니다.");

            case SLOW:
                try {
                    Thread.sleep(slowMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AdmissionNoticeResponse.accepted(nextReceiptId());

            case FAULT:
                // 런타임 예외를 던지면 JAX-WS 가 SOAP Fault 로 바꿔 준다.
                throw new IllegalStateException("병원 B 내부 오류(테스트용)");

            default:
                if (isBlank(notice.getMessageControlId()) || isBlank(notice.getPatientId())) {
                    // 상대도 최소한의 검증은 한다. 필수값이 비면 거절이다.
                    return AdmissionNoticeResponse.rejected("필수 항목이 없습니다.");
                }
                return AdmissionNoticeResponse.accepted(nextReceiptId());
        }
    }

    private String nextReceiptId() {
        return String.format("HOSPB-%06d", sequence.incrementAndGet());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ------------------------------------------------------------------
    // 테스트 제어
    // ------------------------------------------------------------------

    public void setBehavior(Behavior behavior) {
        this.behavior = behavior;
    }

    public void setSlowMillis(long slowMillis) {
        this.slowMillis = slowMillis;
    }

    public List<String> receivedControlIds() {
        return received;
    }

    public List<AdmissionNotice> receivedNotices() {
        return notices;
    }

    /** 마지막으로 받은 통보. 없으면 null. */
    public AdmissionNotice lastNotice() {
        return notices.isEmpty() ? null : notices.get(notices.size() - 1);
    }

    public void reset() {
        this.behavior = Behavior.ACCEPT;
        this.received.clear();
        this.notices.clear();
        this.sequence.set(0);
    }
}
