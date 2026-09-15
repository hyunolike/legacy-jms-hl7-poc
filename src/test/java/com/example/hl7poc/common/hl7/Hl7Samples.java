package com.example.hl7poc.common.hl7;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * 테스트용 HL7 샘플 로더.
 *
 * <p>샘플 파일은 읽기 쉽도록 LF 로 저장돼 있다. 실제 MLLP 트래픽은 CR 을 쓰므로,
 * "파일 그대로"와 "CR 로 바꾼 것"을 둘 다 꺼낼 수 있게 한다. 파서가 두 경우를
 * 똑같이 처리하는지 확인하기 위해서다.
 */
public final class Hl7Samples {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Hl7Samples() {
    }

    /** 저장된 그대로(LF 구분자). */
    public static String load(String fileName) {
        String path = "hl7-samples/" + fileName;
        try (InputStream in = Hl7Samples.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("샘플을 찾을 수 없습니다: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), UTF8);
        } catch (IOException e) {
            throw new IllegalStateException("샘플을 읽을 수 없습니다: " + path, e);
        }
    }

    /** 실제 전송 형태(CR 구분자). */
    public static String loadAsCr(String fileName) {
        return load(fileName).replace("\r\n", "\n").replace("\n", "\r");
    }
}
