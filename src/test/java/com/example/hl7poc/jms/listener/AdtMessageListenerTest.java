package com.example.hl7poc.jms.listener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;

import javax.jms.Destination;
import javax.jms.TextMessage;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;

import com.example.hl7poc.adt.service.AdtProcessingService;
import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.dto.PatientInfo;
import com.example.hl7poc.common.dto.ProcessResult;
import com.example.hl7poc.common.exception.Hl7ParseException;
import com.example.hl7poc.common.exception.PermanentProcessingException;
import com.example.hl7poc.common.exception.TransientProcessingException;
import com.example.hl7poc.common.hl7.AckBuilder;
import com.example.hl7poc.common.hl7.AckCode;
import com.example.hl7poc.common.hl7.Hl7MessageContext;
import com.example.hl7poc.common.hl7.Hl7Parser;
import com.example.hl7poc.common.util.TraceContext;
import com.example.hl7poc.jms.publisher.AckPublisher;

/**
 * 리스너의 <b>오류 분류</b> 검증.
 *
 * <p>이 클래스의 유일한 책임은 "롤백할 것인가, 격리하고 커밋할 것인가"를 정하는
 * 것이다. 그 분기만 집중해서 본다. 파싱과 업무 로직은 각자의 테스트가 있다.
 */
public class AdtMessageListenerTest {

    private static final String RAW = "MSH|^~\\&|HIS|HOSP_A|...";

    private Hl7Parser parser;
    private AdtProcessingService service;
    private AckBuilder ackBuilder;
    private AckPublisher ackPublisher;
    private JmsTemplate parkTemplate;
    private Destination parkQueue;
    private AdtMessageListener listener;
    private TextMessage jmsMessage;

    @Before
    public void setUp() throws Exception {
        parser = mock(Hl7Parser.class);
        service = mock(AdtProcessingService.class);
        ackBuilder = mock(AckBuilder.class);
        ackPublisher = mock(AckPublisher.class);
        parkTemplate = mock(JmsTemplate.class);
        parkQueue = mock(Destination.class);

        listener = new AdtMessageListener(parser, service, ackBuilder, ackPublisher);
        listener.setParkTemplate(parkTemplate);
        listener.setParkQueue(parkQueue);

        jmsMessage = mock(TextMessage.class);
        when(jmsMessage.getText()).thenReturn(RAW);
        when(jmsMessage.getJMSMessageID()).thenReturn("ID:test-1");
    }

    /**
     * {@link AdtEvent} 와 {@link PatientInfo} 는 final 이라 mock 할 수 없다.
     * 불변으로 설계한 대가인데, 어차피 값 객체라 실제로 만드는 편이 읽기도 쉽다.
     */
    private AdtEvent sampleEvent(Hl7MessageContext h) {
        PatientInfo patient = new PatientInfo("PAT000001", "홍", "길동", "19850214", "M",
                "서울특별시 종로구 세종대로 1 03172", "010-0000-0001");
        return new AdtEvent(h, patient, "I", "WARD5 501 A", "ACC0000001", new Date(), null);
    }

    private Hl7MessageContext header(String controlId) {
        return new Hl7MessageContext.Builder()
                .messageControlId(controlId)
                .messageType("ADT^A01")
                .triggerEvent("A01")
                .sendingFacility("HOSP_A")
                .version("2.5")
                .sourceMessage(null)
                .build();
    }

    // ------------------------------------------------------------------
    // 정상
    // ------------------------------------------------------------------

    @Test
    public void 정상_처리되면_AA_를_기록하고_발행한다() {
        Hl7MessageContext h = header("MSG001");
        AdtEvent event = sampleEvent(h);
        when(parser.parseHeader(RAW)).thenReturn(h);
        when(parser.toAdtEvent(h)).thenReturn(event);
        when(service.process(event)).thenReturn(ProcessResult.accepted());
        when(ackBuilder.buildAck(h, AckCode.AA, null, null)).thenReturn("ACK-AA");

        listener.onMessage(jmsMessage);

        verify(service).recordAck("HOSP_A", "MSG001", AckCode.AA, "ACK-AA");
        verify(ackPublisher).publish("ACK-AA", "MSG001");
        verifyNoInteractions(parkTemplate);
    }

    @Test
    public void 중복이면_저장된_ACK_를_그대로_재전송한다() {
        Hl7MessageContext h = header("MSG001");
        AdtEvent event = sampleEvent(h);
        when(parser.parseHeader(RAW)).thenReturn(h);
        when(parser.toAdtEvent(h)).thenReturn(event);
        when(service.process(event))
                .thenReturn(ProcessResult.duplicate(AckCode.AE, "저장해둔-ACK-AE"));

        listener.onMessage(jmsMessage);

        // 새로 만들지 않는다. 최초에 AE 를 보냈는데 재전송에 AA 를 보내면
        // 송신 측 상태가 뒤집힌다.
        verify(ackBuilder, never()).buildAck(any(), any(), any(), any());
        verify(ackPublisher).publish("저장해둔-ACK-AE", "MSG001");
        verify(service, never()).recordAck(anyString(), anyString(), any(), anyString());
    }

    // ------------------------------------------------------------------
    // 재시도 무의미한 오류 → 격리 + 커밋
    // ------------------------------------------------------------------

    @Test
    public void 파싱_실패는_격리하고_AR_을_보내며_예외를_던지지_않는다() {
        when(parser.parseHeader(RAW))
                .thenThrow(new Hl7ParseException("MSH 가 깨졌습니다"));
        when(ackBuilder.buildRejectAck(eq(RAW), eq("HL7-PARSE"), anyString()))
                .thenReturn("ACK-AR");

        listener.onMessage(jmsMessage);   // 예외가 나오면 테스트 실패

        // 격리 큐로 원문이 간다
        verify(parkTemplate).convertAndSend(eq(parkQueue), eq((Object) RAW),
                any(MessagePostProcessor.class));
        // MSH-10 을 모르므로 상관관계 키 없이 발행한다
        verify(ackPublisher).publish(eq("ACK-AR"), isNull());
        // 헤더를 못 읽었으므로 업무 서비스는 건드리지 않는다
        verifyNoInteractions(service);
    }

    @Test
    public void 업무적_처리불가는_격리하고_AE_를_보내며_예외를_던지지_않는다() {
        Hl7MessageContext h = header("MSG102");
        when(parser.parseHeader(RAW)).thenReturn(h);
        when(parser.toAdtEvent(h)).thenThrow(
                new PermanentProcessingException("HL7-NO-PATIENT-ID", "PID-3 가 없습니다"));
        when(ackBuilder.buildAck(eq(h), eq(AckCode.AE), eq("HL7-NO-PATIENT-ID"), anyString()))
                .thenReturn("ACK-AE");

        listener.onMessage(jmsMessage);

        verify(parkTemplate).convertAndSend(eq(parkQueue), eq((Object) RAW),
                any(MessagePostProcessor.class));
        verify(service).recordParked(eq("HOSP_A"), eq("MSG102"),
                eq("HL7-NO-PATIENT-ID"), anyString());
        verify(service).recordAck("HOSP_A", "MSG102", AckCode.AE, "ACK-AE");
        verify(ackPublisher).publish("ACK-AE", "MSG102");
    }

    @Test
    public void 격리_메시지에_오류_코드가_헤더로_붙는다() throws Exception {
        Hl7MessageContext h = header("MSG102");
        when(parser.parseHeader(RAW)).thenReturn(h);
        when(parser.toAdtEvent(h)).thenThrow(
                new PermanentProcessingException("HL7-NO-PATIENT-ID", "PID-3 가 없습니다"));

        listener.onMessage(jmsMessage);

        ArgumentCaptor<MessagePostProcessor> captor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(parkTemplate).convertAndSend(eq(parkQueue), eq((Object) RAW), captor.capture());

        // 실제로 헤더를 세팅하는지 확인한다. 격리 큐를 뒤질 때 이 헤더가 없으면
        // 원문을 일일이 열어 봐야 한다.
        javax.jms.Message parked = mock(javax.jms.Message.class);
        captor.getValue().postProcessMessage(parked);
        verify(parked).setStringProperty("X_ERROR_CODE", "HL7-NO-PATIENT-ID");
        verify(parked).setStringProperty("X_MSG_CONTROL_ID", "MSG102");
        verify(parked).setStringProperty("X_SENDING_FACILITY", "HOSP_A");
    }

    // ------------------------------------------------------------------
    // 재시도 대상 오류 → 롤백
    // ------------------------------------------------------------------

    @Test
    public void 일시적_오류는_예외를_다시_던져_롤백을_유도한다() {
        Hl7MessageContext h = header("MSG001");
        AdtEvent event = sampleEvent(h);
        when(parser.parseHeader(RAW)).thenReturn(h);
        when(parser.toAdtEvent(h)).thenReturn(event);
        when(service.process(event)).thenThrow(
                new TransientProcessingException("WS-TIMEOUT", "병원 B 응답 없음"));

        try {
            listener.onMessage(jmsMessage);
            fail("예외가 전파되어야 롤백된다");
        } catch (TransientProcessingException expected) {
            assertEquals("WS-TIMEOUT", expected.getErrorCode());
        }

        // 아직 성공도 실패도 확정되지 않았다. ACK 를 보내면 안 된다.
        verifyNoInteractions(ackPublisher);
        // 격리하지 않는다. 다시 시도할 메시지다.
        verifyNoInteractions(parkTemplate);
    }

    @Test
    public void 분류되지_않은_예외는_덮지_않고_그대로_전파한다() {
        when(parser.parseHeader(RAW)).thenThrow(new IllegalStateException("예상 못한 버그"));

        try {
            listener.onMessage(jmsMessage);
            fail("예외가 전파되어야 한다");
        } catch (IllegalStateException expected) {
            assertEquals("예상 못한 버그", expected.getMessage());
        }
        // 조용히 격리해 버리면 버그를 못 보고 지나간다.
        verifyNoInteractions(parkTemplate);
        verifyNoInteractions(ackPublisher);
    }

    @Test
    public void 격리_큐가_설정되지_않았으면_메시지를_버리지_않고_롤백한다() {
        listener.setParkQueue(null);
        when(parser.parseHeader(RAW)).thenThrow(new Hl7ParseException("MSH 가 깨졌습니다"));

        try {
            listener.onMessage(jmsMessage);
            fail("설정 누락으로 메시지를 잃으면 안 된다");
        } catch (TransientProcessingException expected) {
            assertEquals("CONFIG-NO-PARK-QUEUE", expected.getErrorCode());
        }
    }

    // ------------------------------------------------------------------
    // MDC
    // ------------------------------------------------------------------

    @Test
    public void 처리가_끝나면_MDC_를_반드시_지운다() {
        Hl7MessageContext h = header("MSG001");
        AdtEvent event = sampleEvent(h);
        when(parser.parseHeader(RAW)).thenReturn(h);
        when(parser.toAdtEvent(h)).thenReturn(event);
        when(service.process(event)).thenReturn(ProcessResult.accepted());

        listener.onMessage(jmsMessage);

        // 지우지 않으면 스레드 풀에서 다음 메시지가 앞 메시지의 Control ID 를
        // 달고 로그를 남긴다. 평소엔 안 보이다가 장애 조사 때 추적을 망친다.
        assertNull(MDC.get(TraceContext.KEY_MSG_CONTROL_ID));
        assertNull(MDC.get(TraceContext.KEY_MSG_TYPE));
    }

    @Test
    public void 예외가_나도_MDC_를_지운다() {
        Hl7MessageContext h = header("MSG001");
        when(parser.parseHeader(RAW)).thenReturn(h);
        when(parser.toAdtEvent(h)).thenThrow(
                new TransientProcessingException("DB-DOWN", "커넥션 없음"));

        try {
            listener.onMessage(jmsMessage);
            fail();
        } catch (TransientProcessingException ignored) {
            // 기대한 예외
        }
        assertNull(MDC.get(TraceContext.KEY_MSG_CONTROL_ID));
    }

    @Test
    public void 본문이_비어_있는_메시지는_한_번만_읽고_분류된다() throws Exception {
        when(jmsMessage.getText()).thenReturn(null);
        when(ackBuilder.buildRejectAck(anyString(), anyString(), anyString()))
                .thenReturn("ACK-AR");

        listener.onMessage(jmsMessage);

        verify(jmsMessage, times(1)).getText();
        verify(ackPublisher).publish(eq("ACK-AR"), isNull());
    }
}
