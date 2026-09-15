package com.example.hl7poc.support;

/**
 * {@code app-context-jms.xml} 의 {@code ackPublisher} 빈을 가리기 위한 빈 껍데기.
 *
 * <p>실제 {@code AckPublisher} 는 7단계 산출물이라 아직 클래스가 없다. 같은 id 로
 * 뒤에 정의하면 앞의 정의가 버려지고 클래스 로딩도 일어나지 않는다 —
 * Spring XML 에서 import 순서가 실제로 의미를 갖는 유일한 경우다.
 */
public class StubAckPublisher {
}
