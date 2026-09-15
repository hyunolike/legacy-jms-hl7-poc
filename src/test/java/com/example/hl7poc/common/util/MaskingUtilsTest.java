package com.example.hl7poc.common.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import com.example.hl7poc.common.dto.PatientInfo;

/**
 * 로그 마스킹 규칙 검증. 입력은 모두 가상의 합성 데이터다.
 *
 * <p>핵심 요구사항 두 가지를 고정한다.
 * <ol>
 *   <li>마스킹 결과에 원본 값이 통째로 남아 있지 않을 것</li>
 *   <li>같은 값은 같은 마스킹 결과를 낼 것 (로그 대조가 가능해야 하므로)</li>
 * </ol>
 */
public class MaskingUtilsTest {

    @Test
    public void 이름은_첫_글자만_남긴다() {
        assertEquals("홍**", MaskingUtils.maskName("홍길동"));
        assertEquals("김*", MaskingUtils.maskName("김영"));
        assertEquals("-", MaskingUtils.maskName(null));
        assertEquals("-", MaskingUtils.maskName("  "));
    }

    @Test
    public void 한_글자_이름은_통째로_가린다() {
        // 첫 글자만 남기면 원본 그대로가 되어 마스킹이 무의미해진다.
        assertEquals("*", MaskingUtils.maskName("홍"));
    }

    @Test
    public void 환자_식별자는_앞뒤만_남긴다() {
        assertEquals("PAT****01", MaskingUtils.maskPatientId("PAT000001"));
        assertEquals("******", MaskingUtils.maskPatientId("ABC123"));
        assertEquals("-", MaskingUtils.maskPatientId(null));
    }

    @Test
    public void 생년월일은_연도만_남긴다() {
        assertEquals("1985****", MaskingUtils.maskBirthDate("19850214"));
        // HL7 은 부분 날짜를 허용한다. 연도만 온 경우 더 가릴 것이 없다.
        assertEquals("1985", MaskingUtils.maskBirthDate("1985"));
        assertEquals("1985**", MaskingUtils.maskBirthDate("198502"));
    }

    @Test
    public void 전화번호는_앞_3자리와_뒤_4자리만_남긴다() {
        assertEquals("010-****-0001", MaskingUtils.maskPhone("010-0000-0001"));
        // 구분자가 없어도 같은 결과여야 한다. 상대 시스템마다 표기가 다르다.
        assertEquals("010-****-0001", MaskingUtils.maskPhone("01000000001"));
        assertEquals("*******", MaskingUtils.maskPhone("1234567"));
    }

    @Test
    public void 주소는_시군구까지만_남긴다() {
        assertEquals("서울특별시 종로구 ***",
                MaskingUtils.maskAddress("서울특별시 종로구 세종대로 1"));
        assertEquals("서울특별시 ***", MaskingUtils.maskAddress("서울특별시"));
    }

    @Test
    public void 통째로_가릴_때는_길이만_남긴다() {
        assertEquals("***(5)", MaskingUtils.maskAll("12345"));
        assertEquals("-", MaskingUtils.maskAll(""));
    }

    @Test
    public void PatientInfo_의_toString_은_항상_마스킹된다() {
        PatientInfo p = new PatientInfo("PAT000001", "홍", "길동", "19850214", "M",
                "서울특별시 종로구 세종대로 1 03172", "010-0000-0001");
        String s = p.toString();

        // 로그 한 줄을 실수로 log.info("환자={}", patient) 로 짜도 새지 않아야 한다.
        assertFalse("환자번호가 그대로 남으면 안 된다", s.contains("PAT000001"));
        assertFalse("이름이 그대로 남으면 안 된다", s.contains("홍길동"));
        assertFalse("생년월일이 그대로 남으면 안 된다", s.contains("19850214"));
        assertFalse("전화번호가 그대로 남으면 안 된다", s.contains("0000-0001"));
        assertFalse("상세 주소가 그대로 남으면 안 된다", s.contains("세종대로"));
    }
}
