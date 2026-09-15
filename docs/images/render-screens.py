#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""README 의 "주요 동작 화면" 이미지를 만든다.

이 스크립트는 화면을 <b>지어내지 않는다</b>. 실제로 브리지·mock·시뮬레이터를 띄우고
얻은 출력을 /tmp/cap/*.raw 로 모아 둔 뒤, 그 텍스트를 터미널 창 모양으로 렌더링할
뿐이다. 손대는 것은 모든 줄에 똑같이 붙는 로거 접두부(날짜, 패키지명, 비어 있는 MDC)를
줄이는 것까지다.

렌더링은 Chromium 헤드리스로 한다. 창 높이는 추정하지 않고, 페이지에 심어 둔 스크립트가
알려 주는 실제 높이를 읽어 두 번째 패스에서 쓴다. 한글은 두 칸을 차지해 줄바꿈 계산이
계속 어긋났기 때문이다.

    # 1) 스택을 띄우고 시나리오를 돌려 /tmp/cap/*.raw 수집 (docs/08 참고)
    # 2) python3 docs/images/render-screens.py        -> /tmp/cap/html/*.html
    # 3) Chromium 으로 각 html 을 캡처               -> docs/images/screens/*.png
"""
import io, os, re, html, sys, unicodedata

CAP = '/tmp/cap'
OUT = '/tmp/cap/html'
os.makedirs(OUT, exist_ok=True)

CSS = '''<style>
*{box-sizing:border-box} html,body{margin:0;padding:0;background:#1E1B18}
body{padding:0;background:#1E1B18;font-family:'DejaVu Sans Mono',monospace}
.win{background:#1E1B18}
.bar{background:#2F2A26;padding:8px 12px;display:flex;align-items:center;gap:7px}
.bar i{width:11px;height:11px;border-radius:50%;display:inline-block}
.bar .r{background:#C3543F}.bar .y{background:#C8873B}.bar .g{background:#5C8A56}
.bar span{color:#CFC6BA;font-size:12.5px;margin-left:10px}
pre{margin:0;padding:13px 16px 26px;color:#E6DFD5;font-size:12.5px;line-height:1.5;white-space:pre-wrap;word-break:break-word}
.p{color:#5C8A56;font-weight:bold}.cmd{color:#EDD9A3}
.step{color:#7FB5E6;font-weight:bold}.warn{color:#E0A85C}.err{color:#E06C5A}
.ok{color:#8FCF86}.dim{color:#8A8178}.hl{color:#D7A3E0}
</style>'''


def tidy(text):
    """실제 출력 그대로 두되, 모든 줄에 똑같이 붙는 부분만 줄인다."""
    out = []
    for ln in text.splitlines():
        ln = ln.rstrip()
        if not ln or 'Picked up' in ln:
            continue
        ln = re.sub(r'^\s*\[java\] ', '', ln)
        ln = re.sub(r'^\d{4}-\d\d-\d\d ', '', ln)           # 날짜 제거, 시각은 유지
        ln = ln.replace('[ctrl=- type=- pt=-] ', '')         # 비어 있는 MDC
        ln = ln.replace('[main] ', '')
        ln = ln.replace('adtRequestListenerContainer-', 'container-')
        ln = re.sub(r' c\.e\.[\w.]+ - ', '  ', ln)
        ln = re.sub(r' com\.example\.hl7poc\.\w+ - ', '  ', ln)
        out.append(ln)
    return "\n".join(out)


def color(body):
    body = html.escape(body)
    body = re.sub(r'(STEP=[A-Z_]+)', r'<b class="step">\1</b>', body)
    body = re.sub(r'\b(WARN)\b', r'<span class="warn">\1</span>', body)
    body = re.sub(r'\b(ERROR)\b', r'<span class="err">\1</span>', body)
    body = re.sub(r'\b(INFO|DEBUG)\b', r'<span class="dim">\1</span>', body)
    body = re.sub(r'(MSA\|AA\|\S*)', r'<span class="ok">\1</span>', body)
    body = re.sub(r'(MSA\|A[ER]\|\S*)', r'<span class="warn">\1</span>', body)
    body = re.sub(r'(v1:k1:[\w+/=]+)', r'<span class="hl">\1</span>', body)
    return body


COLS = 148          # 이 폭에서 줄바꿈된다고 보고 높이를 계산한다
CHAR_W = 7.53
LINE_H = 18.9


def write(name, title, prompt, body, cols=None):
    """터미널 창 HTML 을 쓰고, 화면에 맞는 창 크기를 함께 출력한다.

    줄바꿈을 감안해 높이를 계산한다. 한 줄로 가정하면 긴 로그가 있는 화면에서
    아래쪽이 잘린다.
    """
    rows = 1                                     # 프롬프트 줄
    for ln in body.splitlines():
        # 한글/한자는 두 칸을 차지한다. 글자 수로 세면 줄바꿈이 더 일찍 일어나
        # 계산한 높이보다 실제가 길어져 아래가 잘린다.
        cells = sum(2 if unicodedata.east_asian_width(c) in 'WF' else 1 for c in ln)
        rows += max(1, -(-cells // COLS))        # 올림 나눗셈
    w = int(COLS * CHAR_W) + 70
    h = int(rows * LINE_H) + 80
    # 높이는 추정하지 않는다. 한글 폭·줄바꿈 때문에 계산이 계속 어긋났다.
    # 렌더링 후 브라우저가 알려 준 scrollHeight 를 읽어 두 번째 패스에서 쓴다.
    doc = ('<html><head><meta charset="utf-8">' + CSS + '</head><body>'
           '<div class="win"><div class="bar"><i class="r"></i><i class="y"></i>'
           '<i class="g"></i><span>%s</span></div><pre><span class="p">$</span> '
           '<span class="cmd">%s</span>\n%s</pre></div>'
           '<div id="H" style="position:absolute;left:-9999px"></div>'
           '<script>addEventListener("load",function(){'
           'var w=document.querySelector(".win").getBoundingClientRect();'
           'document.getElementById("H").textContent="HEIGHT="+'
           '(Math.ceil(w.height)+30);});</script>'
           '</body></html>'
           % (html.escape(title), html.escape(prompt), color(body)))
    io.open(os.path.join(OUT, name + '.html'), 'w', encoding='utf-8').write(doc)
    print("%s %d %d" % (name, w, h))


def read(p):
    return io.open(os.path.join(CAP, p), encoding='utf-8').read()


bridge = tidy(read('bridge.raw'))
L = bridge.splitlines()

# ① 기동
boot = [l for l in L if any(k in l for k in ('PHI 키 공급자', 'SOAP 클라이언트 준비',
                                            '평문 HTTP', '기동 완료'))]
write('01-bridge-boot', 'ant run-bridge', 'ant run-bridge', "\n".join(boot))

# ② 정상 처리 (입원 → 퇴원)
happy = [l for l in L if ('SIM202609152108280001' in l or 'SIM202609152108280002' in l)
         and 'STEP=' in l]
write('02-happy-path', 'ant run-bridge  (입원 → 퇴원)',
      'ant run-simulator -Dargs="admit-discharge"', "\n".join(happy))

# ③ 멱등성 (중복)
dedup = [l for l in L if 'SIM202609152108350001' in l and 'STEP=' in l]
write('03-idempotency', 'ant run-bridge  (같은 메시지 2회)',
      'ant run-simulator -Dargs="duplicate"', "\n".join(dedup))

# ④ 시뮬레이터 ACK 수신
write('04-ack', 'ant run-simulator', 'ant run-simulator -Dargs="ack 8"',
      "\n".join(tidy(read('ack.txt')).splitlines()[:18]))

# ⑤ 장애 → 재시도 → DLQ
retry = [l for l in L if 'SIM202609152109410001' in l
         and re.search(r'STEP=(RECEIVED|PERSISTED|RETRYABLE_ERROR)', l)]
write('05-retry-dlq', '병원 B 장애 → 재시도 2s / 4s / 8s → DLQ',
      'ant run-simulator -Dargs="send a01 1"    # 병원 B 는 내려간 상태',
      "\n".join(retry))

# ⑥ DLQ 도구
dlq = tidy(read('dlq-list.txt')) + "\n\n$ ant run-dlq -Dargs=\"replay ALL\"\n" \
      + tidy(read('dlq-replay.txt'))
write('06-dlq-tool', 'ant run-dlq', 'ant run-dlq -Dargs="list"', dlq)

# ⑦ 재처리 성공
replayed = [l for l in L if 'SIM202609152109410001' in l and 'STEP=' in l][-7:]
write('07-replay-ok', '재투입 후 정상 처리', 'ant run-dlq -Dargs="replay ALL"',
      "\n".join(replayed))

# ⑧ DB
db = (read('db1.txt').rstrip() + "\n\nhl7poc=# SELECT step, count(*) FROM processing_log "
      "GROUP BY step ORDER BY 2 DESC;\n" + read('db2.txt').rstrip()
      + "\n\nhl7poc=# -- 평문이 남아 있는지 검사\n" + read('db3.txt').rstrip())
write('08-db', 'psql — 적재 결과와 PHI 점검',
      'docker exec -it hl7poc-postgres psql -U hl7poc -d hl7poc', db)
