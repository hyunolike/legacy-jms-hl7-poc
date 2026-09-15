package com.example.hl7poc.adt.service;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.hl7poc.adt.dao.AdtMessageDao;
import com.example.hl7poc.adt.dao.ProcessedMessageDao;
import com.example.hl7poc.adt.dao.ProcessingLogDao;
import com.example.hl7poc.adt.domain.AdtMessageRecord;
import com.example.hl7poc.adt.domain.ProcessStatus;
import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.dto.PatientInfo;
import com.example.hl7poc.common.dto.ProcessResult;
import com.example.hl7poc.common.hl7.AckCode;
import com.example.hl7poc.common.hl7.Hl7MessageContext;
import com.example.hl7poc.common.util.TraceContext;
import com.example.hl7poc.secure.HmacBlindIndex;
import com.example.hl7poc.secure.PhiCipher;
import com.example.hl7poc.ws.client.HospitalBNotifier;

/**
 * ADT 처리 본체.
 *
 * <p>순서가 설계의 전부다.
 * <pre>
 *   1) 멱등성 선점   — 가장 먼저. 중복이면 여기서 끝낸다.
 *   2) PHI 암호화     — DB 에 닿기 전에.
 *   3) DB 적재
 *   4) 병원 B 전달    — 실패하면 1~3 이 함께 롤백된다.
 *   5) 상태 갱신
 * </pre>
 *
 * <p><b>선점을 가장 먼저 하는 이유</b>: 중복 메시지에 암호화와 SOAP 호출을 태우면
 * 비용도 비용이지만, 병원 B 에 같은 입원 통보가 두 번 간다. 상대 시스템이 멱등하지
 * 않다면 그쪽 데이터가 깨진다.
 *
 * <p><b>선점이 "잠금"이 아니라는 점</b>은 짚고 넘어가야 한다. 선점 INSERT 도 같은
 * 트랜잭션 안이라, 4)에서 실패하면 선점도 함께 사라진다. 그래서 재시도가 정상적으로
 * 다시 처리할 수 있다. 선점이 막는 것은 (a) 커밋 이후의 재전송과 (b) 동시에 들어온
 * 같은 메시지다.
 */
public class AdtProcessingServiceImpl implements AdtProcessingService {

    private static final Logger LOG = LoggerFactory.getLogger(AdtProcessingServiceImpl.class);

    private final ProcessedMessageDao processedMessageDao;
    private final AdtMessageDao adtMessageDao;
    private final ProcessingLogDao processingLogDao;
    private final PhiCipher phiCipher;
    private final HmacBlindIndex blindIndex;
    private final HospitalBNotifier hospitalBNotifier;

    public AdtProcessingServiceImpl(ProcessedMessageDao processedMessageDao,
                                    AdtMessageDao adtMessageDao,
                                    ProcessingLogDao processingLogDao,
                                    PhiCipher phiCipher,
                                    HmacBlindIndex blindIndex,
                                    HospitalBNotifier hospitalBNotifier) {
        this.processedMessageDao = processedMessageDao;
        this.adtMessageDao = adtMessageDao;
        this.processingLogDao = processingLogDao;
        this.phiCipher = phiCipher;
        this.blindIndex = blindIndex;
        this.hospitalBNotifier = hospitalBNotifier;
    }

    @Override
    public ProcessResult process(AdtEvent event) {
        final Hl7MessageContext header = event.getHeader();
        final String facility = header.getSendingFacility();
        final String controlId = header.getMessageControlId();

        // ── 1) 멱등성 선점 ────────────────────────────────────────────────
        if (!processedMessageDao.tryClaim(facility, controlId)) {
            AckCode storedCode = processedMessageDao.findAckCode(facility, controlId);
            String storedAck = processedMessageDao.findAckPayload(facility, controlId);
            processingLogDao.log(facility, controlId, "DEDUP_SKIP",
                    "이미 처리된 메시지. 저장된 ACK=" + (storedAck == null ? "없음" : storedCode));
            LOG.info("STEP=DEDUP_SKIP 이미 처리한 메시지입니다. 업무 처리를 건너뜁니다. storedAck={}",
                    storedCode);
            return ProcessResult.duplicate(storedCode, storedAck);
        }
        processingLogDao.log(facility, controlId, "DEDUP_OK", null);

        // ── 2) PHI 암호화 ─────────────────────────────────────────────────
        final PatientInfo patient = event.getPatient();
        final String patientIdHash = blindIndex.index(patient.getPatientId());
        TraceContext.setPatientKey(patientIdHash);

        AdtMessageRecord record = toRecord(event, patientIdHash);
        processingLogDao.log(facility, controlId, "ENCRYPTED", null);
        LOG.debug("STEP=ENCRYPTED {}", patient);

        // ── 순서 역전 방어 ────────────────────────────────────────────────
        // 메시지 그룹으로 순서를 잡아도 컨슈머가 죽어 그룹이 재배정되는 순간에는
        // 흔들린다. 버리지는 않는다 — 늦게 온 것이 진짜 최신일 수도 있고(송신 측
        // 시계 오차), 데이터를 버리는 쪽이 더 위험하다. 기록만 남긴다.
        Date latest = adtMessageDao.findLatestEventTime(patientIdHash);
        if (latest != null && header.getMessageDateTime() != null
                && header.getMessageDateTime().before(latest)) {
            processingLogDao.log(facility, controlId, "OUT_OF_ORDER",
                    "이미 저장된 최신 이벤트보다 과거 메시지입니다.");
            LOG.warn("STEP=OUT_OF_ORDER 저장된 최신 이벤트({})보다 과거 메시지({})입니다."
                            + " 순서가 뒤집혀 도착했을 수 있습니다.",
                    latest, header.getMessageDateTime());
        }

        // ── 3) DB 적재 ────────────────────────────────────────────────────
        record.setStatus(ProcessStatus.PERSISTED);
        final long id = adtMessageDao.insert(record);
        processingLogDao.log(facility, controlId, "PERSISTED", "id=" + id);
        LOG.info("STEP=PERSISTED id={} trigger={}", id, event.getTriggerEvent());

        // ── 4) 병원 B 전달 ────────────────────────────────────────────────
        // 여기서 던져지는 예외가 트랜잭션 전체를 되돌린다. 일시 오류면 재시도,
        // 영구 오류면 리스너가 격리한다. 구분은 구현체의 책임이다.
        hospitalBNotifier.notifyAdtEvent(event);

        // ── 5) 상태 갱신 ──────────────────────────────────────────────────
        adtMessageDao.updateStatus(id, ProcessStatus.FORWARDED, null);
        processingLogDao.log(facility, controlId, "FORWARDED", null);
        LOG.info("STEP=FORWARDED id={}", id);

        return ProcessResult.accepted();
    }

    @Override
    public void recordAck(String sendingFacility, String messageControlId,
                          AckCode ackCode, String ackPayload) {
        processedMessageDao.storeAck(sendingFacility, messageControlId, ackCode, ackPayload);
        processingLogDao.log(sendingFacility, messageControlId, "ACK_SENT", "code=" + ackCode);
    }

    @Override
    public void recordParked(String sendingFacility, String messageControlId,
                             String errorCode, String errorText) {
        processingLogDao.log(sendingFacility, messageControlId, "PARKED",
                errorCode + " " + errorText);
    }

    private AdtMessageRecord toRecord(AdtEvent event, String patientIdHash) {
        Hl7MessageContext h = event.getHeader();
        PatientInfo p = event.getPatient();

        AdtMessageRecord r = new AdtMessageRecord();
        r.setSendingApplication(h.getSendingApplication());
        r.setSendingFacility(h.getSendingFacility());
        r.setReceivingApplication(h.getReceivingApplication());
        r.setReceivingFacility(h.getReceivingFacility());
        r.setMessageDateTime(h.getMessageDateTime());
        r.setMessageType(h.getMessageType());
        r.setTriggerEvent(h.getTriggerEvent());
        r.setMessageControlId(h.getMessageControlId());
        r.setProcessingId(h.getProcessingId());
        r.setHl7Version(h.getVersion());

        r.setPatientIdHash(patientIdHash);
        r.setPatientIdEnc(phiCipher.encrypt(p.getPatientId()));
        r.setPatientNameEnc(phiCipher.encrypt(p.getFullName()));
        r.setPatientDobEnc(phiCipher.encrypt(p.getBirthDate()));
        r.setPatientPhoneEnc(phiCipher.encrypt(p.getPhone()));
        // 성별은 단독으로는 식별성이 거의 없어 평문을 허용한다. 통계 조회가 잦다.
        r.setPatientSex(p.getSex());

        r.setPatientClass(event.getPatientClass());
        r.setAssignedLocation(event.getAssignedLocation());
        r.setAdmitDateTime(event.getAdmitDateTime());
        r.setDischargeDateTime(event.getDischargeDateTime());

        r.setRawMsgSha256(h.getRawSha256());
        return r;
    }
}
