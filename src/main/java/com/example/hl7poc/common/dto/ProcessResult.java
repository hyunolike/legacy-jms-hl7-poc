package com.example.hl7poc.common.dto;

import com.example.hl7poc.common.hl7.AckCode;

/**
 * 업무 처리 결과. 리스너가 이 값을 보고 어떤 ACK 를 보낼지 정한다.
 */
public final class ProcessResult {

    private final AckCode ackCode;
    private final boolean duplicate;

    /**
     * 중복 수신일 때, 최초 처리 시 보냈던 ACK 원문.
     *
     * <p>중복이라고 새 ACK 를 만들어 보내면 안 되는 경우가 있다. 최초에 AE 를
     * 보냈는데 재전송에 AA 를 보내면 송신 측 상태가 뒤집힌다. 저장해 둔 것을
     * 그대로 돌려주는 편이 안전하다.
     */
    private final String storedAckPayload;

    private ProcessResult(AckCode ackCode, boolean duplicate, String storedAckPayload) {
        this.ackCode = ackCode;
        this.duplicate = duplicate;
        this.storedAckPayload = storedAckPayload;
    }

    public static ProcessResult accepted() {
        return new ProcessResult(AckCode.AA, false, null);
    }

    public static ProcessResult duplicate(AckCode originalAckCode, String storedAckPayload) {
        return new ProcessResult(
                (originalAckCode == null) ? AckCode.AA : originalAckCode, true, storedAckPayload);
    }

    public AckCode getAckCode() { return ackCode; }
    public boolean isDuplicate() { return duplicate; }
    public String getStoredAckPayload() { return storedAckPayload; }

    @Override
    public String toString() {
        return "ProcessResult{ack=" + ackCode + ", duplicate=" + duplicate
                + ", storedAck=" + (storedAckPayload == null ? "없음" : "있음") + "}";
    }
}
