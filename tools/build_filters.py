"""
빌드할 때 광고 차단 목록을 받아 앱에 넣을 형태로 정리한다.
 - assets/adblock/hosts.txt     : 막을 주소(한 줄에 하나)
 - assets/adblock/cosmetic.txt  : 숨길 화면 요소.  "도메인<TAB>선택자"  (도메인이 비면 모든 사이트)
목록을 못 받아도 빌드는 계속된다(앱 안의 기본 목록으로 동작).
"""
import os
import re
import sys
import urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/adblock"
os.makedirs(OUT, exist_ok=True)

HOSTS_SOURCES = [
    "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
    "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext",
]
ABP_SOURCES = [
    # 한국 사이트 전용 목록 (주소가 바뀌었을 수 있어 여러 개 시도)
    ["https://raw.githubusercontent.com/List-KR/List-KR/master/filter-uBlockOrigin.txt",
     "https://raw.githubusercontent.com/List-KR/List-KR/master/filter.txt",
     "https://raw.githubusercontent.com/List-KR/List-KR/main/filter.txt"],
    ["https://easylist.to/easylist/easylist.txt"],
]

# 일부 목록에 들어 있지만 막으면 사이트가 깨지는 주소
ALLOW = {
    "localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0",
    "ip6-localhost", "ip6-loopback",
}

# 크로미움 CSS가 이해 못하는 확장 문법은 건너뛴다
BAD_SEL = re.compile(r":-abp-|:has-text\(|:upward\(|:style\(|:remove\(|:xpath\(|:matches-|:min-text|"
                     r":watch-attr|:others\(|:matches-path|\+js\(|^\+js|:if\(|:if-not\(|:nth-ancestor")
HOST_RE = re.compile(r"^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$")


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


hosts = set()
cosmetic = set()
report = []

for url in HOSTS_SOURCES:
    try:
        n0 = len(hosts)
        for line in fetch(url).splitlines():
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            parts = line.split()
            h = (parts[1] if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1") else parts[0]).lower()
            if h not in ALLOW and HOST_RE.match(h):
                hosts.add(h)
        report.append(f"{url.split('/')[2]} +{len(hosts) - n0}")
    except Exception as e:
        report.append(f"FAIL {url}: {e}")

for candidates in ABP_SOURCES:
    text = None
    for url in candidates:
        try:
            text = fetch(url)
            if "[Adblock" in text[:500] or "!" in text[:5] or "##" in text:
                break
            text = None
        except Exception:
            text = None
    if text is None:
        report.append(f"FAIL {candidates[0]}")
        continue
    nh, nc = len(hosts), len(cosmetic)
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("!") or line.startswith("["):
            continue
        if "#@#" in line or "#?#" in line or "#$#" in line or "#%#" in line or "##^" in line:
            continue
        if "##" in line:
            dom, sel = line.split("##", 1)
            if not sel or BAD_SEL.search(sel) or "\t" in sel or len(sel) > 300:
                continue
            doms = [d.strip().lower() for d in dom.split(",") if d.strip()]
            if any(d.startswith("~") for d in doms):
                continue  # 예외 지정이 있는 규칙은 단순화를 위해 건너뜀
            if not doms:
                cosmetic.add("\t" + sel)
            else:
                for d in doms:
                    if HOST_RE.match(d):
                        cosmetic.add(d + "\t" + sel)
            continue
        if line.startswith("@@"):
            continue
        # ||example.com^  또는 ||example.com^$third-party 같은 "주소 전체 차단" 규칙만 사용
        m = re.match(r"^\|\|([a-z0-9.-]+)\^(\$(.*))?$", line, re.I)
        if m:
            opts = (m.group(3) or "").lower()
            if any(o in opts for o in ("domain=", "~third-party", "first-party", "1p", "redirect", "removeparam",
                                       "csp", "badfilter", "important", "header")):
                continue
            h = m.group(1).lower().strip(".")
            if HOST_RE.match(h) and h not in ALLOW:
                hosts.add(h)
    report.append(f"{candidates[0].split('/')[2]}/{candidates[0].split('/')[4] if 'githubusercontent' in candidates[0] else ''} hosts+{len(hosts) - nh} hide+{len(cosmetic) - nc}")

with open(os.path.join(OUT, "hosts.txt"), "w") as f:
    f.write("\n".join(sorted(hosts)))
with open(os.path.join(OUT, "cosmetic.txt"), "w") as f:
    f.write("\n".join(sorted(cosmetic)))

print(f"hosts={len(hosts)} cosmetic={len(cosmetic)} | " + " | ".join(report))
