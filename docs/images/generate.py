#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""손그림체(sketchy) SVG 생성기.

README 에 쓰는 기술 로고와 시스템 구조도를 만든다.

왜 직접 그리는가
  - 외부 이미지에 의존하지 않는다. 링크가 죽으면 README 가 깨진다.
  - 각 기술의 공식 로고를 그대로 쓰지 않고 손으로 그린 듯한 아이콘으로 대체한다.
  - 흔들림(jitter)을 SVG 필터가 아니라 경로 좌표에 직접 넣는다. GitHub 은 SVG 의
    <filter>/<style> 를 걸러 낼 수 있어서, 필터에 기대면 렌더링이 환경을 탄다.

배경을 종이색으로 칠하는 것도 의도다. 투명 배경이면 GitHub 다크 모드에서 검은
잉크가 보이지 않는다.

    python3 docs/images/generate.py
"""
import math
import os
import random

INK = "#2F2A26"
PAPER = "#FDF8EF"
RED = "#C3543F"
BLUE = "#3E6B8C"
GREEN = "#5C8A56"
AMBER = "#C8873B"
PURPLE = "#7A5C8E"
GRAY = "#8A8178"

FONT = "'Comic Sans MS','Comic Neue','Chalkboard SE','Segoe Print','Bradley Hand',cursive"
MONO = "'Courier New',monospace"

OUT = os.path.dirname(os.path.abspath(__file__))


# ---------------------------------------------------------------------------
# 손그림 프리미티브
# ---------------------------------------------------------------------------

def _j(rng, v, amp):
    return v + rng.uniform(-amp, amp)


def line(rng, x1, y1, x2, y2, stroke=INK, w=2.0, amp=2.8, passes=2):
    """손으로 그은 선.

    직선을 여러 조각으로 나눠 법선 방향으로 흔든다. 한 번의 곡선(Q)으로 휘게 하면
    너무 매끈해서 손그림으로 보이지 않는다. 두 번 덧그어 연필 자국처럼 만든다.
    """
    dx, dy = x2 - x1, y2 - y1
    L = math.hypot(dx, dy) or 1.0
    nx, ny = -dy / L, dx / L
    ux, uy = dx / L, dy / L
    segs = max(2, min(10, int(L / 26) + 2))
    out = []
    for p in range(passes):
        a = amp * (1.0 if p == 0 else 1.45)
        pts = []
        for i in range(segs + 1):
            t = i / segs
            edge = 0.3 if i in (0, segs) else 1.0
            off = rng.uniform(-a, a) * edge
            slide = rng.uniform(-a, a) * 0.45 * edge
            pts.append((x1 + dx * t + nx * off + ux * slide,
                        y1 + dy * t + ny * off + uy * slide))
        d = "M%.1f,%.1f " % pts[0] + " ".join("L%.1f,%.1f" % q for q in pts[1:])
        out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.1f" '
                   'stroke-linecap="round" stroke-linejoin="round" opacity="%.2f"/>'
                   % (d, stroke, w * (1.0 if p == 0 else 0.62), 0.95 if p == 0 else 0.4))
    return "".join(out)


def rect(rng, x, y, w, h, stroke=INK, fill=None, sw=2.0, amp=2.6, r=6):
    """모서리가 둥근 사각형. 채우기는 매끈하게, 테두리는 손으로."""
    out = []
    if fill:
        out.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="%d" '
                   'fill="%s" opacity="0.9"/>' % (x, y, w, h, r, fill))
    out.append(line(rng, x + r, y, x + w - r, y, stroke, sw, amp))
    out.append(line(rng, x + w, y + r, x + w, y + h - r, stroke, sw, amp))
    out.append(line(rng, x + w - r, y + h, x + r, y + h, stroke, sw, amp))
    out.append(line(rng, x, y + h - r, x, y + r, stroke, sw, amp))
    # 모서리. 반지름을 조금씩 흔들어 "자로 그리지 않은" 느낌을 낸다.
    for (ax, ay, bx, by, cx2, cy2) in (
            (x + w - r, y, x + w, y, x + w, y + r),
            (x + w, y + h - r, x + w, y + h, x + w - r, y + h),
            (x + r, y + h, x, y + h, x, y + h - r),
            (x, y + r, x, y, x + r, y)):
        jx, jy = _j(rng, 0, 1.4), _j(rng, 0, 1.4)
        out.append('<path d="M%.1f,%.1f Q%.1f,%.1f %.1f,%.1f" fill="none" stroke="%s" '
                   'stroke-width="%.1f" stroke-linecap="round"/>'
                   % (ax + jx, ay + jy, bx, by, cx2 + jx, cy2 + jy, stroke, sw))
    return "".join(out)


def ellipse(rng, cx, cy, rx, ry, stroke=INK, fill=None, sw=2.0, amp=2.0, passes=2):
    out = []
    if fill:
        out.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="%s" opacity="0.9"/>'
                   % (cx, cy, rx, ry, fill))
    for p in range(passes):
        pts = []
        steps = 18
        start = rng.uniform(0, 0.4)
        for i in range(steps + 1):
            t = start + (i / steps) * (2 * math.pi + rng.uniform(0.05, 0.3))
            a = amp * (1.0 if p == 0 else 1.4)
            pts.append((cx + rx * math.cos(t) + _j(rng, 0, a),
                        cy + ry * math.sin(t) + _j(rng, 0, a)))
        d = "M%.1f,%.1f " % pts[0] + " ".join("L%.1f,%.1f" % q for q in pts[1:])
        out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.1f" '
                   'stroke-linecap="round" stroke-linejoin="round" opacity="%.2f"/>'
                   % (d, stroke, sw * (1.0 if p == 0 else 0.7), 0.95 if p == 0 else 0.4))
    return "".join(out)


def poly(rng, pts, stroke=INK, fill=None, sw=2.0, amp=2.2, close=True):
    out = []
    if fill:
        out.append('<polygon points="%s" fill="%s" opacity="0.9"/>'
                   % (" ".join("%.1f,%.1f" % p for p in pts), fill))
    seq = list(pts) + ([pts[0]] if close else [])
    for i in range(len(seq) - 1):
        out.append(line(rng, seq[i][0], seq[i][1], seq[i + 1][0], seq[i + 1][1],
                        stroke, sw, amp))
    return "".join(out)


def arrow(rng, x1, y1, x2, y2, stroke=INK, sw=2.2, head=10, amp=2.4, dashed=False):
    out = []
    if dashed:
        n = max(2, int(math.hypot(x2 - x1, y2 - y1) / 14))
        for i in range(n):
            t0, t1 = i / n, (i + 0.55) / n
            out.append(line(rng, x1 + (x2 - x1) * t0, y1 + (y2 - y1) * t0,
                            x1 + (x2 - x1) * t1, y1 + (y2 - y1) * t1, stroke, sw, amp, 1))
    else:
        out.append(line(rng, x1, y1, x2, y2, stroke, sw, amp))
    ang = math.atan2(y2 - y1, x2 - x1)
    for s in (+1, -1):
        a = ang + s * 2.5
        out.append(line(rng, x2, y2, x2 + head * math.cos(a), y2 + head * math.sin(a),
                        stroke, sw, 0.8, 1))
    return "".join(out)


def text(x, y, s, size=15, fill=INK, anchor="middle", family=FONT, weight="normal",
         rotate=None, opacity=1.0):
    rot = ' transform="rotate(%.1f %.1f %.1f)"' % (rotate, x, y) if rotate else ""
    return ('<text x="%.1f" y="%.1f" font-family="%s" font-size="%d" fill="%s" '
            'text-anchor="%s" font-weight="%s" opacity="%.2f"%s>%s</text>'
            % (x, y, family, size, fill, anchor, weight, opacity, rot,
               s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")))


def svg(w, h, body, bg=PAPER):
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" '
            'height="%d" role="img">\n<rect width="%d" height="%d" rx="14" fill="%s"/>\n%s\n</svg>\n'
            % (w, h, w, h, w, h, bg, body))


# ---------------------------------------------------------------------------
# 기술 아이콘 (공식 로고가 아니라 손으로 그린 대체 아이콘)
# ---------------------------------------------------------------------------

def icon_java(rng, cx, cy, s=1.0):
    """커피잔 + 김"""
    o = []
    for i, dx in enumerate((-9, 0, 9)):
        o.append('<path d="M%.1f,%.1f q%.1f,%.1f 0,%.1f q%.1f,%.1f 0,%.1f" fill="none" '
                 'stroke="%s" stroke-width="%.1f" stroke-linecap="round"/>'
                 % (cx + dx * s, cy - 14 * s, 5 * s, -6 * s, -11 * s, -5 * s, -6 * s, -10 * s,
                    RED, 2.0 * s))
    o.append(poly(rng, [(cx - 15 * s, cy - 4 * s), (cx + 15 * s, cy - 4 * s),
                        (cx + 11 * s, cy + 16 * s), (cx - 11 * s, cy + 16 * s)],
                  INK, BLUE, 2.0 * s))
    o.append(ellipse(rng, cx + 18 * s, cy + 3 * s, 6 * s, 6 * s, INK, None, 2.0 * s, 1.0, 1))
    return "".join(o)


def icon_spring(rng, cx, cy, s=1.0):
    """잎사귀"""
    o = ['<path d="M%.1f,%.1f C%.1f,%.1f %.1f,%.1f %.1f,%.1f C%.1f,%.1f %.1f,%.1f %.1f,%.1f Z" '
         'fill="%s" stroke="%s" stroke-width="%.1f" stroke-linejoin="round" opacity="0.92"/>'
         % (cx - 16 * s, cy + 14 * s,
            cx - 18 * s, cy - 10 * s, cx + 2 * s, cy - 20 * s, cx + 17 * s, cy - 15 * s,
            cx + 19 * s, cy + 2 * s, cx + 4 * s, cy + 17 * s, cx - 16 * s, cy + 14 * s,
            GREEN, INK, 2.0 * s)]
    o.append(line(rng, cx - 13 * s, cy + 12 * s, cx + 12 * s, cy - 11 * s, PAPER, 1.8 * s, 0.8, 1))
    return "".join(o)


def icon_activemq(rng, cx, cy, s=1.0):
    """큐에 줄 선 메시지 봉투들"""
    o = []
    for i, dx in enumerate((-13, 0, 13)):
        o.append(rect(rng, cx + dx * s - 8 * s, cy - 9 * s + i * 0.5, 15 * s, 12 * s,
                      INK, AMBER if i == 0 else PAPER, 1.8 * s, 1.0, 3))
        o.append(line(rng, cx + dx * s - 8 * s, cy - 9 * s, cx + dx * s - 0.5 * s, cy - 2 * s,
                      INK, 1.2 * s, 0.7, 1))
        o.append(line(rng, cx + dx * s + 7 * s, cy - 9 * s, cx + dx * s - 0.5 * s, cy - 2 * s,
                      INK, 1.2 * s, 0.7, 1))
    o.append(arrow(rng, cx - 20 * s, cy + 13 * s, cx + 20 * s, cy + 13 * s, INK, 1.8 * s, 6 * s, 1.0))
    return "".join(o)


def icon_postgres(rng, cx, cy, s=1.0):
    """코끼리 머리"""
    o = [ellipse(rng, cx, cy - 2 * s, 15 * s, 13 * s, INK, BLUE, 2.0 * s)]
    o.append('<path d="M%.1f,%.1f q%.1f,%.1f %.1f,%.1f q%.1f,%.1f %.1f,%.1f" fill="none" '
             'stroke="%s" stroke-width="%.1f" stroke-linecap="round"/>'
             % (cx - 2 * s, cy + 9 * s, 2 * s, 10 * s, 7 * s, 10 * s,
                4 * s, 0, 3 * s, -5 * s, INK, 2.2 * s))
    o.append(ellipse(rng, cx - 16 * s, cy - 4 * s, 7 * s, 9 * s, INK, BLUE, 1.8 * s, 1.0, 1))
    o.append(ellipse(rng, cx + 16 * s, cy - 4 * s, 7 * s, 9 * s, INK, BLUE, 1.8 * s, 1.0, 1))
    o.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>' % (cx - 6 * s, cy - 4 * s, 2.0 * s, PAPER))
    o.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>' % (cx + 6 * s, cy - 4 * s, 2.0 * s, PAPER))
    return "".join(o)


def icon_hl7(rng, cx, cy, s=1.0):
    """심전도 파형"""
    o = [rect(rng, cx - 19 * s, cy - 15 * s, 38 * s, 30 * s, INK, "#F3E3E0", 2.0 * s, 1.2, 5)]
    pts = [(-15, 2), (-9, 2), (-6, -8), (-2, 9), (2, -11), (6, 4), (10, 2), (15, 2)]
    d = "M" + " L".join("%.1f,%.1f" % (cx + px * s, cy + py * s) for px, py in pts)
    o.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.1f" stroke-linecap="round" '
             'stroke-linejoin="round"/>' % (d, RED, 2.4 * s))
    return "".join(o)


def icon_ant(rng, cx, cy, s=1.0):
    """개미"""
    o = [ellipse(rng, cx + 11 * s, cy + 2 * s, 9 * s, 7 * s, INK, INK, 1.8 * s, 1.0, 1),
         ellipse(rng, cx, cy, 6 * s, 5 * s, INK, INK, 1.8 * s, 1.0, 1),
         ellipse(rng, cx - 11 * s, cy - 2 * s, 6 * s, 6 * s, INK, INK, 1.8 * s, 1.0, 1)]
    for dx, dy in ((-4, 0), (2, 0), (8, 0)):
        o.append(line(rng, cx + dx * s, cy + 2 * s, cx + (dx - 4) * s, cy + 13 * s, INK, 1.8 * s, 0.8, 1))
        o.append(line(rng, cx + dx * s, cy - 1 * s, cx + (dx - 4) * s, cy - 11 * s, INK, 1.8 * s, 0.8, 1))
    o.append(line(rng, cx - 13 * s, cy - 6 * s, cx - 20 * s, cy - 15 * s, INK, 1.6 * s, 0.8, 1))
    o.append(line(rng, cx - 15 * s, cy - 4 * s, cx - 23 * s, cy - 9 * s, INK, 1.6 * s, 0.8, 1))
    return "".join(o)


def icon_docker(rng, cx, cy, s=1.0):
    """고래 + 컨테이너"""
    o = []
    for i in range(4):
        o.append(rect(rng, cx - 18 * s + i * 9 * s, cy - 4 * s, 8 * s, 7 * s, INK, BLUE, 1.5 * s, 0.8, 2))
    for i in range(3):
        o.append(rect(rng, cx - 18 * s + i * 9 * s, cy - 12 * s, 8 * s, 7 * s, INK, BLUE, 1.5 * s, 0.8, 2))
    o.append('<path d="M%.1f,%.1f q%.1f,%.1f %.1f,%.1f q%.1f,%.1f %.1f,%.1f Z" fill="%s" '
             'stroke="%s" stroke-width="%.1f" stroke-linejoin="round" opacity="0.92"/>'
             % (cx - 24 * s, cy + 5 * s, 14 * s, 14 * s, 44 * s, 0,
                -6 * s, -3 * s, -44 * s, 0, BLUE, INK, 2.0 * s))
    return "".join(o)


def icon_soap(rng, cx, cy, s=1.0):
    """XML 봉투 + 비눗방울"""
    o = [rect(rng, cx - 17 * s, cy - 11 * s, 30 * s, 22 * s, INK, PURPLE, 2.0 * s, 1.2, 4)]
    o.append(line(rng, cx - 17 * s, cy - 11 * s, cx - 2 * s, cy + 2 * s, PAPER, 1.8 * s, 0.8, 1))
    o.append(line(rng, cx + 13 * s, cy - 11 * s, cx - 2 * s, cy + 2 * s, PAPER, 1.8 * s, 0.8, 1))
    o.append(ellipse(rng, cx + 17 * s, cy - 14 * s, 6 * s, 6 * s, INK, None, 1.6 * s, 0.9, 1))
    o.append(ellipse(rng, cx + 25 * s, cy - 5 * s, 4 * s, 4 * s, INK, None, 1.4 * s, 0.8, 1))
    return "".join(o)


ICONS = {
    "java": (icon_java, "Java 11", RED),
    "spring": (icon_spring, "Spring 5.3", GREEN),
    "activemq": (icon_activemq, "ActiveMQ 5.18", AMBER),
    "postgres": (icon_postgres, "PostgreSQL 15", BLUE),
    "hl7": (icon_hl7, "HAPI HL7 v2", RED),
    "ant": (icon_ant, "Apache Ant", INK),
    "docker": (icon_docker, "Docker", BLUE),
    "soap": (icon_soap, "JAX-WS SOAP", PURPLE),
}


def write_logos():
    for name, (fn, label, _c) in ICONS.items():
        rng = random.Random(hash(name) & 0xFFFF)
        body = fn(rng, 48, 44, 1.15)
        body += text(48, 82, label.split()[0] if len(label.split()[0]) <= 10 else label,
                     13, INK, "middle")
        path = os.path.join(OUT, "logos", "%s.svg" % name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(svg(96, 96, body))
        print("  logos/%s.svg" % name)


# ---------------------------------------------------------------------------
# 배너
# ---------------------------------------------------------------------------

def write_banner():
    rng = random.Random(42)
    o = []
    o.append(rect(rng, 14, 14, 852, 172, INK, "#FFFDF7", 2.4, 2.0, 12))
    o.append(text(440, 78, "legacy-jms-hl7-poc", 40, INK, "middle", FONT, "bold"))
    o.append(text(440, 112, "Hospital-to-hospital HL7 v2 ADT bridge, built the legacy way",
                  17, GRAY, "middle"))
    o.append(line(rng, 210, 92, 670, 92, AMBER, 3.0, 2.0, 2))
    xs = [150, 245, 340, 435, 530, 625, 720]
    keys = ["java", "spring", "activemq", "postgres", "hl7", "soap", "docker"]
    for x, k in zip(xs, keys):
        o.append(ICONS[k][0](rng, x, 152, 0.72))
    return _write("banner.svg", svg(880, 200, "".join(o)))


def _write(name, content):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(content)
    print("  %s" % name)


# ---------------------------------------------------------------------------
# 시스템 구조도
# ---------------------------------------------------------------------------

def elbow(rng, pts, stroke=INK, sw=2.3, head=10, amp=2.2, label=None, label_at=None,
          label_color=None):
    """꺾인 화살표. 마지막 구간 끝에 화살촉을 단다."""
    o = []
    for i in range(len(pts) - 1):
        o.append(line(rng, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], stroke, sw, amp))
    (x1, y1), (x2, y2) = pts[-2], pts[-1]
    ang = math.atan2(y2 - y1, x2 - x1)
    for sgn in (+1, -1):
        a = ang + sgn * 2.5
        o.append(line(rng, x2, y2, x2 + head * math.cos(a), y2 + head * math.sin(a),
                      stroke, sw, 0.8, 1))
    if label and label_at:
        o.append(text(label_at[0], label_at[1], label, 12.5,
                      label_color or stroke, "middle", MONO))
    return "".join(o)


def tech_mark(rng, key, cx, cy, scale=0.62, label_dy=26, size=12):
    """아이콘 + 기술명. 구조도 안에서는 늘 짝으로 쓴다.

    아이콘만 두면 무엇인지 알아보기 어렵고, 이름만 두면 눈에 안 들어온다.
    """
    fn, label, _color = ICONS[key]
    return fn(rng, cx, cy, scale) + text(cx, cy + label_dy, label, size, INK, "middle",
                                         FONT, "bold")


def write_architecture():
    rng = random.Random(7)
    W, H = 1220, 1000
    o = []

    def box(x, y, w, h, title, lines, fill, icon=None):
        b = [rect(rng, x, y, w, h, INK, fill, 2.4, 2.4, 9)]
        tx = x + w / 2
        if icon:
            b.append(tech_mark(rng, icon, x + 44, y + 40, 0.72, 27, 11.5))
            b.append(text(tx + 26, y + 30, title, 18, INK, "middle", FONT, "bold"))
            for i, ln in enumerate(lines):
                b.append(text(tx + 26, y + 56 + i * 20, ln, 13, GRAY, "middle", MONO))
        else:
            b.append(text(tx, y + 30, title, 18, INK, "middle", FONT, "bold"))
            for i, ln in enumerate(lines):
                b.append(text(tx, y + 56 + i * 20, ln, 13, GRAY, "middle", MONO))
        return "".join(b)

    o.append(text(610, 34, "System Architecture", 24, INK, "middle", FONT, "bold"))
    o.append(line(rng, 430, 44, 790, 44, AMBER, 3.0, 2.4))

    # ── ① 병원 A 시뮬레이터 ────────────────────────────────────────────────
    o.append(box(56, 62, 336, 112, "Hospital A Simulator",
                 ["HospitalASimulator.main()", "ADT^A01 / ADT^A03"], "#FFF3E2", "java"))

    o.append(elbow(rng, [(224, 176), (224, 222)], INK, 2.5, 11, 2.2))
    o.append(text(240, 196, "HL7 v2 (ER7) over JMS", 13, INK, "start", MONO))
    o.append(text(240, 212, "JMSXGroupID = patient", 12, GRAY, "start", MONO))

    # ── ② ActiveMQ ─────────────────────────────────────────────────────────
    o.append(rect(rng, 56, 226, 1104, 146, INK, "#FFF7E6", 2.7, 2.6, 10))
    o.append(tech_mark(rng, "activemq", 116, 268, 0.78, 30, 12))
    o.append(text(176, 262, "ActiveMQ Classic 5.18", 19, INK, "start", FONT, "bold"))
    o.append(text(176, 282, "javax.jms  ·  runs in Docker", 12.5, GRAY, "start", MONO))

    for label, color, x in (("Q.HL7.ADT.REQ", GREEN, 430), ("Q.HL7.ADT.ACK", BLUE, 610),
                            ("Q.HL7.ADT.PARK", PURPLE, 790), ("DLQ.Q.HL7.ADT.REQ", RED, 985)):
        o.append(rect(rng, x - 84, 306, 168, 48, INK, PAPER, 2.0, 1.8, 6))
        o.append(text(x, 336, label, 12.5, color, "middle", MONO, "bold"))

    o.append(elbow(rng, [(224, 374), (224, 424)], INK, 2.5, 11, 2.2))
    o.append(text(240, 394, "DefaultMessageListenerContainer", 12.5, INK, "start", MONO))
    o.append(text(240, 410, "concurrentConsumers 3 -> 10", 12, GRAY, "start", MONO))

    # ── ③ 브리지 본체 ──────────────────────────────────────────────────────
    BX, BY, BW, BH = 56, 428, 700, 344
    o.append(rect(rng, BX, BY, BW, BH, INK, "#F2F7F1", 2.9, 2.8, 12))
    o.append(tech_mark(rng, "spring", BX + 52, BY + 40, 0.72, 27, 11.5))
    o.append(text(BX + 104, BY + 32, "HL7 ADT Bridge", 21, INK, "start", FONT, "bold"))
    o.append(text(BX + 104, BY + 52, "main() + ClassPathXmlApplicationContext", 12.5,
                  GRAY, "start", MONO))
    o.append(tech_mark(rng, "java", BX + BW - 52, BY + 40, 0.66, 26, 11.5))

    layers = [
        ("jms", "AdtMessageListener", "classify: retry or park", GREEN, None),
        ("common", "HapiHl7Parser + HapiAckBuilder", "HAPI confined to this layer", RED, "hl7"),
        ("adt", "AdtProcessingService + DAO", "idempotency + transaction", BLUE, None),
        ("secure", "AES-256-GCM + HMAC index", "PHI never stored in plaintext", PURPLE, None),
        ("ws", "HospitalBSoapClient", "timeouts + error classification", AMBER, "soap"),
    ]
    LY = BY + 74
    for i, (pkg, cls, note, color, side_icon) in enumerate(layers):
        y = LY + i * 54
        o.append(rect(rng, BX + 26, y, BW - 52, 46, INK, PAPER, 2.0, 1.8, 7))
        o.append(text(BX + 42, y + 20, pkg, 15, color, "start", FONT, "bold"))
        o.append(text(BX + 42, y + 38, cls, 13, INK, "start", MONO))
        o.append(text(BX + BW - 42, y + 30, note, 12, GRAY, "end"))

    # 계층 옆에 붙는 기술. 화살표/라벨과 겹치지 않는 빈 자리에만 둔다.
    o.append(tech_mark(rng, "hl7", 808, 500, 0.66, 27, 11.5))

    # ── ④ 병원 B mock ──────────────────────────────────────────────────────
    o.append(box(880, 440, 288, 128, "Hospital B (mock)",
                 ["MockHospitalBServer.main()", "Endpoint.publish :9090", "?wsdl"],
                 "#F4EFF7", "soap"))
    o.append(elbow(rng, [(756, LY + 4 * 54 + 22), (864, LY + 4 * 54 + 22), (864, 524),
                         (874, 524)], AMBER, 2.4, 10, 2.0))
    o.append(text(1024, 592, "SOAP / HTTP", 12.5, AMBER, "middle", MONO, "bold"))
    o.append(text(1024, 610, "connect 3s / read 5s", 11.5, GRAY, "middle", MONO))

    # ── ⑤ PostgreSQL ───────────────────────────────────────────────────────
    o.append(box(880, 636, 288, 130, "PostgreSQL 15",
                 ["processed_message (UQ)", "adt_message (*_enc)", "processing_log"],
                 "#EAF1F6", "postgres"))
    o.append(elbow(rng, [(756, LY + 2 * 54 + 22), (822, LY + 2 * 54 + 22), (822, 690),
                         (874, 690)], BLUE, 2.4, 10, 2.0))
    o.append(text(786, 600, "JDBC", 12, BLUE, "middle", MONO, "bold"))

    # ── 기술 범례 ──────────────────────────────────────────────────────────
    o.append(rect(rng, 56, 800, 1112, 146, GRAY, "#FBF6EC", 1.8, 2.2, 10))
    o.append(text(612, 830, "Tech Stack", 17, INK, "middle", FONT, "bold"))
    keys = ["java", "spring", "activemq", "postgres", "hl7", "soap", "ant", "docker"]
    for i, k in enumerate(keys):
        cx = 122 + i * 138
        o.append(tech_mark(rng, k, cx, 884, 0.74, 32, 12.5))

    o.append(text(1150, 40, "hand-drawn", 12, GRAY, "end", FONT, "normal", -4, 0.65))
    return _write("architecture.svg", svg(W, H, "".join(o)))


def write_flow():
    """메시지 한 건의 처리 흐름 (해피 패스 + 실패 분기)"""
    rng = random.Random(11)
    W, H = 1180, 470
    o = []
    steps = [
        ("RECEIVED", "onMessage", GREEN),
        ("PARSED", "MSH only", GREEN),
        ("DEDUP", "claim (fac, MSH-10)", BLUE),
        ("ENCRYPTED", "AES-256-GCM", PURPLE),
        ("PERSISTED", "adt_message", BLUE),
        ("FORWARDED", "Hospital B", AMBER),
        ("ACK_SENT", "MSA|AA", GREEN),
    ]
    x0, y0, bw, bh, gap = 46, 96, 138, 76, 20
    for i, (name, note, color) in enumerate(steps):
        x = x0 + i * (bw + gap)
        o.append(rect(rng, x, y0, bw, bh, INK, PAPER, 2.1, 1.4, 8))
        o.append(text(x + bw / 2, y0 + 30, name, 13.5, color, "middle", MONO, "bold"))
        o.append(text(x + bw / 2, y0 + 52, note, 11.5, GRAY, "middle", MONO))
        if i < len(steps) - 1:
            o.append(arrow(rng, x + bw + 2, y0 + bh / 2, x + bw + gap - 3, y0 + bh / 2, INK, 2.0, 8))

    o.append(text(590, 52, "one message, all the way through", 20, INK, "middle", FONT, "bold"))
    o.append(line(rng, 400, 62, 780, 62, AMBER, 2.6, 1.6))

    # 실패 분기
    branches = [
        (150, RED, "Hl7ParseException", "-> PARK + AR  (commit)"),
        (470, PURPLE, "PermanentProcessing", "-> PARK + AE  (commit)"),
        (800, BLUE, "TransientProcessing", "-> rollback, retry 3x -> DLQ"),
    ]
    for x, color, title, note in branches:
        o.append(arrow(rng, x, y0 + bh + 6, x, y0 + bh + 62, color, 2.0, 8, 1.2, True))
        o.append(rect(rng, x - 128, y0 + bh + 66, 256, 62, color, PAPER, 2.0, 1.3, 8))
        o.append(text(x, y0 + bh + 92, title, 13, color, "middle", MONO, "bold"))
        o.append(text(x, y0 + bh + 112, note, 12, GRAY, "middle", MONO))

    o.append(rect(rng, 46, 396, 1088, 50, GRAY, "#FBF6EC", 1.6, 1.2, 8))
    o.append(text(590, 427,
                  "every step is logged with MDC  [ctrl=MSH-10  type=MSH-9  pt=hash(patient)]",
                  13.5, INK, "middle", MONO))
    return _write("flow.svg", svg(W, H, "".join(o)))


if __name__ == "__main__":
    print("생성 중...")
    write_logos()
    write_banner()
    write_architecture()
    write_flow()
    print("완료")
