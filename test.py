#!/usr/bin/env python3
"""
patchpush - TUI Termux: paste patch + commit message -> check -> apply -> add -> commit -> push
            + tab WORKFLOW: daftar run GitHub Actions + detail job/step realtime

Pemakaian:
    python patchpush.py [path_repo] [-R owner/repo | url_github]

    -R  : repo GitHub untuk tab workflow. Kalau kosong, diambil otomatis dari remote "origin".

Tab (baris paling atas, bisa di-tap):
    Ctrl+T          : pindah tab PATCH <-> WORKFLOW

Tab PATCH:
    Ctrl+N / Ctrl+P : pindah field (atau tap field)
    Ctrl+R          : jalankan proses
    Ctrl+L          : kosongkan field aktif
    Ctrl+W          : tampil/sembunyikan ringkasan workflow (tap ringkasan = buka tab WORKFLOW)
    Scroll patch    : geser jari / mouse wheel / PgUp PgDn / panah

Tab WORKFLOW:
    Daftar run      : ↑/↓ pilih, Enter atau tap = buka detail, r = refresh
    Detail run      : ↑/↓ pilih step, Enter atau tap step/job = buka log, l = log run yang gagal,
                      Backspace/Esc/q atau tap "Kembali" = balik, r = refresh
    Log step        : ↑/↓ PgUp PgDn Home End / geser jari = scroll, e atau tombol [Copy Error] = salin baris error,
                      c atau tombol [Copy Semua] = salin seluruh log step, r = muat ulang, Backspace = balik
                      (butuh: pkg install termux-api + aplikasi Termux:API untuk clipboard)
                      Step yang masih berjalan tampil LIVE (auto-refresh ~3 dtk, layar mengikuti baris terbaru);
                      scroll ke atas = berhenti mengikuti, End / f / tombol ▼ = ikuti lagi

Ctrl+Q / Ctrl+C : keluar.
Patch TIDAK pernah ditulis ke file: dikirim lewat stdin (git apply -), jadi tidak ada file sementara.
"""
import argparse
import base64
import curses
import json
import locale
import os
import re
import shutil
import subprocess
import sys
import textwrap
import threading
import time
from datetime import datetime, timezone

STEPS = ["git apply --check", "git apply", "git add", "git commit", "git push"]
PENDING, RUNNING, OK, FAIL = 0, 1, 2, 3
SYMBOLS = {PENDING: "○", RUNNING: "●", OK: "✔", FAIL: "✘"}
F_MSG, F_PATCH, F_BTN, F_WF = 0, 1, 2, 3
SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
HUNK_RE = re.compile(r"^@@ -\d+(?:,(\d+))? \+\d+(?:,(\d+))? @@")
META_PREFIX = ("---", "+++", "index ", "new file", "deleted file", "similarity",
               "rename ", "old mode", "new mode", "Binary", "copy ")
BTN5 = getattr(curses, "BUTTON5_PRESSED", 0x200000)
BTN4 = getattr(curses, "BUTTON4_PRESSED", 0x10000)
CLICK = curses.BUTTON1_CLICKED | curses.BUTTON1_PRESSED


# ---------------------------------------------------------------- helpers
def classify(lines):
    """Beri label tiap baris patch: add / del / hunk / meta / ctx."""
    kinds, old, new = [], 0, 0
    for ln in lines:
        if ln.startswith("diff "):
            old = new = 0
            kinds.append("meta")
            continue
        m = HUNK_RE.match(ln)
        if m:
            old = int(m.group(1)) if m.group(1) is not None else 1
            new = int(m.group(2)) if m.group(2) is not None else 1
            kinds.append("hunk")
            continue
        if old > 0 or new > 0:
            if ln.startswith("+"):
                new -= 1
                kinds.append("add")
            elif ln.startswith("-"):
                old -= 1
                kinds.append("del")
            elif ln.startswith("\\"):
                kinds.append("meta")
            else:
                old -= 1
                new -= 1
                kinds.append("ctx")
            continue
        if ln.startswith(META_PREFIX):
            kinds.append("meta")
        elif ln.startswith("+"):
            kinds.append("add")
        elif ln.startswith("-"):
            kinds.append("del")
        else:
            kinds.append("ctx")
    return kinds


def parse_github(s):
    s = (s or "").strip()
    m = re.search(r"github\.com[:/]+([^/\s]+)/([^/\s]+?)(?:\.git)?/?$", s)
    if m:
        return f"{m.group(1)}/{m.group(2)}"
    if re.fullmatch(r"[\w-]+/[\w.-]+", s):
        return s
    return None


def parse_iso(s):
    try:
        t = datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except Exception:
        return None
    return t if t.year > 2000 else None


def age_str(iso):
    t = parse_iso(iso)
    if not t:
        return ""
    d = max(0, int((datetime.now(timezone.utc) - t).total_seconds()))
    if d < 60:
        return f"{d}d"
    if d < 3600:
        return f"{d // 60}m"
    if d < 86400:
        return f"{d // 3600}j"
    return f"{d // 86400}h"


def dur_str(start, end):
    s = parse_iso(start)
    if not s:
        return ""
    e = parse_iso(end) or datetime.now(timezone.utc)
    d = max(0, int((e - s).total_seconds()))
    return f"{d}d" if d < 60 else f"{d // 60}m{d % 60:02d}d"


def status_sym(status, conclusion, frame):
    """(simbol, nomor color pair) untuk run/job/step."""
    if status == "completed":
        if conclusion == "success":
            return "✔", 1
        if conclusion in ("cancelled", "skipped", "neutral"):
            return "⊘", 5
        return "✘", 2
    if status == "in_progress":
        return frame, 3
    return "○", 3


def fit(left, tail, room):
    room = max(0, room)
    if len(left) + len(tail) > room:
        left = left[: max(0, room - len(tail))]
    return left.ljust(max(0, room - len(tail))) + tail



ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
TS_RE = re.compile(r"^\ufeff?\d{4}-\d\d-\d\dT[\d:.]+Z ?")
ERR_RE = re.compile("|".join([
    r"##\[error\]",
    r"\b(?:error|Error|ERROR)(?:\[\w+\])?:",      # "error:", "Error:", "error[E0425]:"
    r"\berror (?:TS|CS|C)\d+",                     # TypeScript / C#
    r"\b\w*(?:Exception|Error):",                  # NullPointerException: / TypeError:
    r"Traceback \(most recent call last\)",
    r"\bFAILURE:", r"\bFAILED\b", r"\bFAIL\b",
    r"\* What went wrong",
    r"\b[A-Z][a-z]+ failed\b",                     # "Execution failed", "Compilation failed"
    r"\bnpm ERR!", r"\bfatal:", r"\bpanic:",
    r"exit code [1-9]\d*",
    r"^\s*\d+ errors?\s*$",                        # "7 errors" (javac)
]))
WARN_RE = re.compile(r"##\[warning\]|\bwarning\b", re.I)


def clean_log_line(s):
    s = ANSI_RE.sub("", s.rstrip("\r"))
    return TS_RE.sub("", s).replace("\t", "    ")


def classify_log_line(l):
    if ERR_RE.search(l):
        return "err"
    if WARN_RE.search(l):
        return "warn"
    return ""


def parse_job_log(text, step_name):
    """Output `gh run view --log` = 'job<TAB>step<TAB>waktu pesan'.
    Return (lines, kinds, note). Isi blok '##[group]Run ...' (script yang di-echo GitHub + env)
    diberi kind 'script' supaya tidak dianggap error."""
    raw = []
    for ln in text.splitlines():
        parts = ln.split("\t", 2)
        if len(parts) == 3:
            if step_name is None or parts[1].strip() == step_name:
                raw.append(clean_log_line(parts[2]))
        elif step_name is None:
            raw.append(clean_log_line(ln))
    note = ""
    if step_name is not None and not raw and text.strip():
        for ln in text.splitlines():
            parts = ln.split("\t", 2)
            raw.append(clean_log_line(parts[2] if len(parts) == 3 else ln))
        note = "(nama step tidak cocok, menampilkan seluruh job)"
    lines, kinds = build_kinds(raw)
    return lines, kinds, note


TS_CAP_RE = re.compile(r"^\ufeff?(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.\d+)?Z ?")


def build_kinds(raw):
    """Beri label tiap baris; isi blok '##[group]Run ...' = 'script' (bukan error)."""
    lines, kinds, in_script = [], [], False
    for l in raw:
        if l.strip() == "##[endgroup]":
            in_script = False
            continue
        if l.startswith("##[group]"):
            in_script = l.startswith("##[group]Run ")
            lines.append(l)
            kinds.append("group")
            continue
        lines.append(l)
        kinds.append("script" if in_script else classify_log_line(l))
    return lines, kinds


def parse_api_log(text, step):
    """Log job mentah dari API (bisa dibaca saat job masih berjalan) tidak berlabel step,
    jadi dipotong memakai jendela waktu step (ketelitian sekitar 1 detik)."""
    start = end = None
    if step is not None:
        start = parse_iso(step.get("startedAt") or "")
        if start is None:
            return [], [], "(step belum mulai)"
        if step.get("status") == "completed":
            end = parse_iso(step.get("completedAt") or "")
    raw, keep = [], step is None
    for ln in text.splitlines():
        m = TS_CAP_RE.match(ln)
        if m and step is not None:
            ts = parse_iso(m.group(1) + "Z")
            keep = ts is not None and ts >= start and (end is None or ts <= end)
        if keep:   # baris tanpa timestamp = lanjutan baris sebelumnya
            raw.append(clean_log_line(ln))
    lines, kinds = build_kinds(raw)
    return lines, kinds, ""


def extract_errors(lines, kinds):
    """Baris error + max 2 baris sebelum + max 3 sesudah (tidak menembus blok script/group).
    Rentang berdekatan digabung. Blok error yang identik (log Gradle sering mengulang) hanya diambil sekali."""
    idx = [i for i, k in enumerate(kinds) if k == "err"]
    if not idx:
        return "", 0
    blocked = ("script", "group")
    n = len(lines)
    seen, keep, covered = set(), [], set()
    for i in idx:
        b = i
        while b < min(n - 1, i + 3) and kinds[b + 1] not in blocked:
            b += 1
        key = tuple(l.strip() for l in lines[i:b + 1])
        if key in seen:
            covered.update(range(i, b + 1))   # blok duplikat: jangan ikut sebagai konteks
        else:
            seen.add(key)
            keep.append(i)
    rng = []
    for i in keep:
        b = i
        while b < min(n - 1, i + 3) and kinds[b + 1] not in blocked and (b + 1) not in covered:
            b += 1
        a = i
        while a > max(0, i - 2) and kinds[a - 1] not in blocked and (a - 1) not in covered:
            a -= 1
        if rng and a <= rng[-1][1] + 1:
            rng[-1][1] = max(rng[-1][1], b)
        else:
            rng.append([a, b])
    out = []
    for k, (a, b) in enumerate(rng):
        if k:
            out.append("...")
        out += lines[a:b + 1]
    return "\n".join(out), len(keep)


def copy_text(text):
    """Salin ke clipboard. Return (ok, pesan)."""
    for cmd in (["termux-clipboard-set"], ["wl-copy"], ["xclip", "-selection", "clipboard"], ["xsel", "-ib"]):
        exe = shutil.which(cmd[0])
        if not exe:
            continue
        try:
            r = subprocess.run([exe] + cmd[1:], input=text, text=True, capture_output=True, timeout=15)
            if r.returncode == 0:
                return True, cmd[0]
        except subprocess.TimeoutExpired:
            return False, f"{cmd[0]} timeout (cek aplikasi Termux:API)"
        except Exception:
            continue
    if len(text) <= 70000:
        try:
            with open("/dev/tty", "w") as t:
                t.write("\x1b]52;c;" + base64.b64encode(text.encode()).decode() + "\x07")
                t.flush()
            return True, "OSC52 (kalau tidak masuk: pkg install termux-api)"
        except Exception:
            pass
    return False, "tidak ada clipboard. Jalankan: pkg install termux-api (+ app Termux:API)"


# ---------------------------------------------------------------- editor
class Editor:
    def __init__(self, single=False):
        self.single = single
        self.clear()

    def clear(self):
        self.lines = [""]
        self.r = self.c = self.top = self.left = 0
        self.rev = 0
        self._kinds = None
        self._kinds_rev = -1

    @property
    def text(self):
        return "\n".join(self.lines)

    def kinds(self):
        if self._kinds_rev != self.rev:
            self._kinds = classify(self.lines)
            self._kinds_rev = self.rev
        return self._kinds

    def insert(self, s):
        self.rev += 1
        for ch in s:
            if ch in ("\n", "\r"):
                if self.single:
                    continue
                line = self.lines[self.r]
                self.lines[self.r] = line[: self.c]
                self.lines.insert(self.r + 1, line[self.c:])
                self.r += 1
                self.c = 0
            elif ch == "\t" and self.single:
                continue
            elif ch.isprintable() or ch == "\t":
                line = self.lines[self.r]
                self.lines[self.r] = line[: self.c] + ch + line[self.c:]
                self.c += 1

    def backspace(self):
        self.rev += 1
        if self.c > 0:
            line = self.lines[self.r]
            self.lines[self.r] = line[: self.c - 1] + line[self.c:]
            self.c -= 1
        elif self.r > 0:
            prev = self.lines[self.r - 1]
            self.c = len(prev)
            self.lines[self.r - 1] = prev + self.lines[self.r]
            del self.lines[self.r]
            self.r -= 1

    def delete(self):
        self.rev += 1
        line = self.lines[self.r]
        if self.c < len(line):
            self.lines[self.r] = line[: self.c] + line[self.c + 1:]
        elif self.r < len(self.lines) - 1:
            self.lines[self.r] = line + self.lines[self.r + 1]
            del self.lines[self.r + 1]

    def scroll(self, n):
        self.r = max(0, min(len(self.lines) - 1, self.r + n))
        self.c = min(self.c, len(self.lines[self.r]))

    def move(self, key):
        if key == curses.KEY_LEFT:
            if self.c > 0:
                self.c -= 1
            elif self.r > 0:
                self.r -= 1
                self.c = len(self.lines[self.r])
        elif key == curses.KEY_RIGHT:
            if self.c < len(self.lines[self.r]):
                self.c += 1
            elif self.r < len(self.lines) - 1:
                self.r += 1
                self.c = 0
        elif key == curses.KEY_UP:
            self.scroll(-1)
        elif key == curses.KEY_DOWN:
            self.scroll(1)
        elif key == curses.KEY_HOME:
            self.c = 0
        elif key == curses.KEY_END:
            self.c = len(self.lines[self.r])
        elif key == curses.KEY_PPAGE:
            self.scroll(-10)
        elif key == curses.KEY_NPAGE:
            self.scroll(10)

    def handle(self, key):
        if isinstance(key, str):
            if key in ("\x7f", "\b"):
                self.backspace()
            elif key in ("\n", "\r"):
                if self.single:
                    return False
                self.insert("\n")
            elif key == "\t":
                if self.single:
                    return False
                self.insert("\t")
            else:
                self.insert(key)
            return True
        if key == curses.KEY_BACKSPACE:
            self.backspace()
        elif key == curses.KEY_DC:
            self.delete()
        elif key in (curses.KEY_LEFT, curses.KEY_RIGHT, curses.KEY_UP, curses.KEY_DOWN,
                     curses.KEY_HOME, curses.KEY_END, curses.KEY_PPAGE, curses.KEY_NPAGE):
            self.move(key)
        else:
            return False
        return True

    def scroll_to_cursor(self, h, w):
        if self.r < self.top:
            self.top = self.r
        if self.r >= self.top + h:
            self.top = self.r - h + 1
        if self.c < self.left:
            self.left = self.c
        if self.c >= self.left + w:
            self.left = self.c - w + 1


# ---------------------------------------------------------------- GitHub Actions poller
class WorkflowPoller(threading.Thread):
    LIST_FIELDS = "databaseId,number,status,conclusion,displayTitle,workflowName,headBranch,event,createdAt"
    VIEW_FIELDS = LIST_FIELDS + ",jobs"

    def __init__(self, slug, cwd):
        super().__init__(daemon=True)
        self.slug = slug
        self.cwd = cwd
        self.lock = threading.Lock()
        self.runs = []
        self.error = "" if slug else "Remote bukan GitHub. Pakai: -R owner/repo"
        self.updated = 0.0
        self.detail_id = None
        self.detail = None
        self.detail_err = ""
        self.detail_updated = 0.0
        self.wake = threading.Event()
        self.last_list = 0.0
        self.boost_until = 0.0
        self.alive = True
        self.fatal = False

    def _gh(self, args, timeout=25):
        env = dict(os.environ, GH_PROMPT_DISABLED="1", NO_COLOR="1")
        return subprocess.run(["gh"] + args, cwd=self.cwd, env=env, capture_output=True, text=True,
                              stdin=subprocess.DEVNULL, timeout=timeout)

    def _first_line(self, p):
        return next((l for l in ((p.stderr or "") + (p.stdout or "")).splitlines() if l.strip()), "gh error")

    def poll_now(self, boost=0):
        if boost:
            self.boost_until = time.time() + boost
        self.last_list = 0.0
        self.wake.set()

    def set_detail(self, run_id):
        with self.lock:
            self.detail_id = run_id
            self.detail = None
            self.detail_err = ""
            self.detail_updated = 0.0
        self.wake.set()

    def fetch_list(self):
        try:
            p = self._gh(["run", "list", "-R", self.slug, "--limit", "30", "--json", self.LIST_FIELDS])
            if p.returncode != 0:
                with self.lock:
                    self.error = self._first_line(p)
            else:
                data = json.loads(p.stdout or "[]")
                with self.lock:
                    self.runs, self.error, self.updated = data, "", time.time()
        except FileNotFoundError:
            with self.lock:
                self.error = "gh belum ada. Jalankan: pkg install gh && gh auth login"
            self.fatal = True
        except Exception as e:
            with self.lock:
                self.error = str(e)[:80]
        self.last_list = time.time()

    def fetch_detail(self):
        rid = self.detail_id
        if rid is None:
            return
        try:
            p = self._gh(["run", "view", str(rid), "-R", self.slug, "--json", self.VIEW_FIELDS])
            with self.lock:
                if self.detail_id != rid:
                    return
                if p.returncode != 0:
                    self.detail_err = self._first_line(p)
                else:
                    self.detail = json.loads(p.stdout or "{}")
                    self.detail_err, self.detail_updated = "", time.time()
        except Exception as e:
            with self.lock:
                self.detail_err = str(e)[:80]

    def fetch_log(self, rid):
        try:
            p = self._gh(["run", "view", str(rid), "-R", self.slug, "--log-failed"], timeout=60)
        except Exception as e:
            return f"Gagal mengambil log: {e}"
        if p.returncode != 0:
            return self._first_line(p)
        out = (p.stdout or "").strip()
        if not out:
            return "Tidak ada log step gagal (run belum selesai atau tidak ada step yang gagal)."
        return out[-30000:]

    def fetch_job_log(self, rid, job_id):
        try:
            p = self._gh(["run", "view", str(rid), "-R", self.slug, "--log", "--job", str(job_id)], timeout=90)
        except Exception as e:
            return False, str(e)[:120]
        if p.returncode != 0:
            return False, self._first_line(p)
        return True, p.stdout or ""

    def fetch_job_log_live(self, job_id):
        """Log job lewat REST API: bisa dipakai saat job masih berjalan."""
        try:
            p = self._gh(["api", f"repos/{self.slug}/actions/jobs/{job_id}/logs"], timeout=60)
        except Exception as e:
            return False, str(e)[:120]
        if p.returncode != 0:
            return False, self._first_line(p)
        return True, p.stdout or ""

    def run(self):
        if not self.slug:
            return
        while self.alive:
            with self.lock:
                active = any(r.get("status") != "completed" for r in self.runs)
                live = self.detail_id is not None and (
                    self.detail is None or self.detail.get("status") != "completed")
            if live:
                tick, list_iv = 2, 6
            elif time.time() < self.boost_until:
                tick, list_iv = 2, 2
            elif active:
                tick, list_iv = 3, 3
            else:
                tick, list_iv = 10, 10
            if time.time() - self.last_list >= list_iv - 0.1:
                self.fetch_list()
                if self.fatal:
                    return
            if self.detail_id is not None:
                self.fetch_detail()
            self.wake.wait(tick)
            self.wake.clear()

    def snapshot(self):
        with self.lock:
            return list(self.runs), self.error, self.updated

    def detail_snapshot(self):
        with self.lock:
            return self.detail, self.detail_err, self.detail_updated


# ---------------------------------------------------------------- app
class App:
    def __init__(self, scr, repo, slug):
        self.scr = scr
        self.repo = repo
        self.msg = Editor(single=True)
        self.patch = Editor(single=False)
        self.focus = F_MSG
        self.status = [PENDING] * 5
        self.detail = [""] * 5
        self.note = "Isi message & patch, lalu tekan [ JALANKAN ] atau Ctrl+R"
        self.regions = []
        self.tab_regions = []
        self.wf_rows = []
        self.back_row = None
        self.resume_from = 0
        self.snapshot = None
        self.busy = False
        self.show_wf = True
        self.has256 = curses.COLORS >= 256
        # tab workflow
        self.tab = 0
        self.wf_view = "list"
        self.sel_id = None
        self.wf_top = 0
        self.view_id = None
        self.detail_scroll = 0
        self.follow = True
        self.flash = ""
        self.flash_until = 0.0
        self.det_sel = 0
        self.det_ensure = False
        self.det_items = []
        self.det_rows = []
        self.log_job = None
        self.log_step = None
        self.log_lines = []
        self.log_kinds = []
        self.log_note = ""
        self.log_err = ""
        self.log_loading = False
        self.log_scroll = 0
        self.log_tail = False
        self.log_gen = 0
        self.log_rev = 0
        self.log_fetched = 0.0
        self.log_updated = 0.0
        self.log_was_running = False
        self.log_buttons = []
        self.log_lock = threading.Lock()
        self._wrap_key = None
        self._wrap = []
        self._last_click = 0.0
        self.poller = WorkflowPoller(slug, repo)
        self.poller.start()

    # ---------- util gambar ----------
    def put(self, y, x, s, attr=0):
        H, W = self.scr.getmaxyx()
        if y < 0 or y >= H or x >= W or x < 0:
            return
        s = s[: max(0, W - x - (1 if y == H - 1 else 0))]
        try:
            self.scr.addstr(y, x, s, attr)
        except curses.error:
            pass

    def box(self, y, x, h, w, title, focused):
        attr = curses.color_pair(4) | curses.A_BOLD if focused else curses.color_pair(5)
        self.put(y, x, "┌" + "─" * (w - 2) + "┐", attr)
        for i in range(1, h - 1):
            self.put(y + i, x, "│", attr)
            self.put(y + i, x + w - 1, "│", attr)
        self.put(y + h - 1, x, "└" + "─" * (w - 2) + "┘", attr)
        self.put(y, x + 2, f" {title} ", attr)

    def frame(self):
        return SPINNER[int(time.time() * 6) % len(SPINNER)]

    def kind_attr(self, kind):
        if kind == "add":
            return curses.color_pair(7) | (0 if self.has256 else curses.A_BOLD), True
        if kind == "del":
            return curses.color_pair(8) | (0 if self.has256 else curses.A_BOLD), True
        if kind == "hunk":
            return curses.color_pair(4) | curses.A_BOLD, False
        if kind == "meta":
            return curses.color_pair(3) | curses.A_BOLD, False
        return 0, False

    def draw_editor(self, ed, y, x, h, w, title, focused, diff=False):
        ih, iw = h - 2, w - 2
        ed.scroll_to_cursor(ih, iw)
        kinds = ed.kinds() if diff else None
        if diff:
            title = f"{title}  +{kinds.count('add')} -{kinds.count('del')}  {ed.r + 1}/{len(ed.lines)}"
        self.box(y, x, h, w, title, focused)
        for i in range(ih):
            idx = ed.top + i
            if idx >= len(ed.lines):
                break
            seg = ed.lines[idx][ed.left: ed.left + iw].replace("\t", " ")
            if diff:
                attr, fill = self.kind_attr(kinds[idx])
                if fill:
                    seg = seg.ljust(iw)
                self.put(y + 1 + i, x + 1, seg, attr)
            else:
                self.put(y + 1 + i, x + 1, seg)
        n = len(ed.lines)
        if n > ih:
            thumb = max(1, ih * ih // n)
            pos = ed.top * (ih - thumb) // max(1, n - ih)
            for i in range(thumb):
                self.put(y + 1 + pos + i, x + w - 1, "┃", curses.color_pair(4) | curses.A_BOLD)
        if focused:
            return (y + 1 + ed.r - ed.top, x + 1 + ed.c - ed.left)
        return None

    # ---------- tab bar ----------
    def draw_tabbar(self, W):
        runs, _, _ = self.poller.snapshot()
        ind, icol = "", 5
        if runs:
            if any(r.get("status") != "completed" for r in runs):
                ind, icol = self.frame(), 3
            else:
                ind, icol = status_sym(runs[0].get("status"), runs[0].get("conclusion"), "")
                ind, icol = ind, icol
        x = 0
        self.tab_regions = []
        for idx, label in ((0, "1 PATCH"), (1, "2 WORKFLOW")):
            active = self.tab == idx
            attr = (curses.color_pair(6) | curses.A_BOLD) if active else curses.color_pair(5)
            text = f" {label} "
            w = len(text) + (2 if idx == 1 and ind else 0)
            self.put(0, x, text, attr)
            if idx == 1 and ind:
                iattr = attr if active else curses.color_pair(icol) | curses.A_BOLD
                self.put(0, x + len(text), ind + " ", iattr)
            self.tab_regions.append((x, x + w, idx))
            x += w + 1
        self.put(0, x, "│ Ctrl+T ganti tab"[: max(0, W - x - 1)], curses.A_DIM)

    # ---------- halaman PATCH ----------
    def draw_mini_workflow(self, y, h, W):
        runs, err, upd = self.poller.snapshot()
        slug = self.poller.slug or "-"
        ago = f" · update {int(time.time() - upd)}d lalu" if upd else ""
        self.box(y, 0, h, W, f"GitHub Actions  {slug}{ago}  (tap = buka tab)", False)
        rows = h - 2
        if err and not runs:
            self.put(y + 1, 2, err[: W - 4], curses.color_pair(2))
            return
        if not runs:
            self.put(y + 1, 2, "memuat...", curses.A_DIM)
            return
        frame = self.frame()
        for i, r in enumerate(runs[:rows]):
            sym, col = status_sym(r.get("status"), r.get("conclusion"), frame)
            left = f" {sym} {(r.get('workflowName') or '')[:14]:<14} {r.get('displayTitle', '')}"
            tail = f" {r.get('headBranch', '')} {age_str(r.get('createdAt', ''))}"
            self.put(y + 1 + i, 1, fit(left, tail, W - 3),
                     curses.color_pair(col) | (curses.A_BOLD if col != 5 else 0))

    def draw_patch_page(self, H, W):
        title = f" PATCH → COMMIT → PUSH   repo: {os.path.basename(self.repo) or self.repo}"
        self.put(1, 0, title.ljust(W - 1), curses.color_pair(6) | curses.A_BOLD)

        msg_y, msg_h, status_h, fixed = 2, 3, 7, 14
        avail = H - fixed - 5
        if self.show_wf and avail >= 3:
            wf_h = min(4, avail - 2) + 2
        else:
            wf_h = 0
        patch_y = msg_y + msg_h
        patch_h = max(5, H - fixed - wf_h)
        status_y = patch_y + patch_h
        wf_y = status_y + status_h
        btn_y = wf_y + wf_h
        help_y = btn_y + 1

        c1 = self.draw_editor(self.msg, msg_y, 0, msg_h, W, "Commit message", self.focus == F_MSG)
        c2 = self.draw_editor(self.patch, patch_y, 0, patch_h, W, "Patch", self.focus == F_PATCH, diff=True)
        self.regions += [(msg_y, 0, msg_h, W, F_MSG), (patch_y, 0, patch_h, W, F_PATCH)]
        cur = c1 or c2

        self.box(status_y, 0, status_h, W, "Status", False)
        for i, label in enumerate(STEPS):
            st = self.status[i]
            col = {PENDING: 5, RUNNING: 3, OK: 1, FAIL: 2}[st]
            sym = self.frame() if st == RUNNING else SYMBOLS[st]
            line = f" {sym} {label:<18}"
            self.put(status_y + 1 + i, 1, line, curses.color_pair(col) | curses.A_BOLD)
            det = self.detail[i].replace("\n", " ")
            self.put(status_y + 1 + i, 1 + len(line), det[: max(0, W - 4 - len(line))], curses.color_pair(col))
        self.put(status_y + 6, 2, self.note[: W - 4], curses.A_DIM)

        if wf_h:
            self.draw_mini_workflow(wf_y, wf_h, W)
            self.regions.append((wf_y, 0, wf_h, W, F_WF))

        label = "[ JALANKAN ]" if not self.busy else "[ BERJALAN... ]"
        battr = curses.color_pair(1) | curses.A_BOLD
        if self.focus == F_BTN:
            battr |= curses.A_REVERSE
        self.put(btn_y, max(0, (W - len(label)) // 2), label, battr)
        self.regions.append((btn_y, 0, 1, W, F_BTN))
        self.put(help_y, 0, "^N/^P pindah ^R run ^L hapus ^W ringkasan ^T tab ^Q keluar"[: W - 1], curses.A_DIM)
        return cur if self.focus != F_BTN else None

    # ---------- halaman WORKFLOW ----------
    def runs_index(self, runs):
        ids = [r.get("databaseId") for r in runs]
        if self.sel_id in ids:
            return ids.index(self.sel_id)
        if runs:
            self.sel_id = ids[0]
        return 0

    def move_sel(self, d):
        runs, _, _ = self.poller.snapshot()
        if not runs:
            return
        idx = max(0, min(len(runs) - 1, self.runs_index(runs) + d))
        self.sel_id = runs[idx].get("databaseId")

    def open_detail(self, run_id):
        self.view_id = run_id
        self.wf_view = "detail"
        self.detail_scroll = 0
        self.follow = True
        self.det_sel = 0
        self.poller.set_detail(run_id)

    def close_detail(self):
        self.wf_view = "list"
        self.poller.set_detail(None)

    def draw_wf_list(self, H, W):
        runs, err, upd = self.poller.snapshot()
        y0, h = 2, H - 3
        ago = f"  update {int(time.time() - upd)}d lalu" if upd else ""
        running = sum(1 for r in runs if r.get("status") != "completed")
        okc = sum(1 for r in runs if r.get("conclusion") == "success")
        bad = sum(1 for r in runs if r.get("status") == "completed"
                  and r.get("conclusion") not in ("success", "cancelled", "skipped", "neutral"))
        self.box(y0, 0, h, W, f"Semua run ({len(runs)})  ●{running} ✔{okc} ✘{bad}{ago}", True)
        if err and not runs:
            self.put(y0 + 1, 2, err[: W - 4], curses.color_pair(2))
            return
        if not runs:
            self.put(y0 + 1, 2, "memuat...", curses.A_DIM)
            return
        ih = h - 2
        idx = self.runs_index(runs)
        if idx < self.wf_top:
            self.wf_top = idx
        if idx >= self.wf_top + ih:
            self.wf_top = idx - ih + 1
        self.wf_top = max(0, min(self.wf_top, max(0, len(runs) - ih)))
        frame = self.frame()
        for i in range(ih):
            ri = self.wf_top + i
            if ri >= len(runs):
                break
            r = runs[ri]
            sym, col = status_sym(r.get("status"), r.get("conclusion"), frame)
            left = f" {sym} #{r.get('number', ''):<4} {(r.get('workflowName') or '')[:14]:<14} {r.get('displayTitle', '')}"
            tail = f" {r.get('headBranch', '')} {r.get('event', '')} {age_str(r.get('createdAt', ''))}"
            attr = curses.color_pair(col) | (curses.A_BOLD if col != 5 else 0)
            if ri == idx:
                attr |= curses.A_REVERSE
            self.put(y0 + 1 + i, 1, fit(left, tail, W - 3), attr)
            self.wf_rows.append((y0 + 1 + i, r.get("databaseId")))
        n = len(runs)
        if n > ih:
            thumb = max(1, ih * ih // n)
            pos = self.wf_top * (ih - thumb) // max(1, n - ih)
            for i in range(thumb):
                self.put(y0 + 1 + pos + i, W - 1, "┃", curses.color_pair(4) | curses.A_BOLD)

    def draw_wf_detail(self, H, W):
        det, err, upd = self.poller.detail_snapshot()
        runs, _, _ = self.poller.snapshot()
        frame = self.frame()
        self.put(2, 0, " ◀ Kembali ke daftar".ljust(W - 1), curses.color_pair(6) | curses.A_BOLD)
        self.back_row = 2
        head = det or next((r for r in runs if r.get("databaseId") == self.view_id), None) or {}
        sym, col = status_sym(head.get("status"), head.get("conclusion"), frame)
        st = head.get("conclusion") if head.get("status") == "completed" else head.get("status")
        line = (f" {sym} #{head.get('number', '')} {head.get('workflowName', '')} · "
                f"{head.get('displayTitle', '')}  [{st or '...'}]  {head.get('headBranch', '')} "
                f"{head.get('event', '')} {age_str(head.get('createdAt', ''))}")
        self.put(3, 0, line[: W - 1], curses.color_pair(col) | curses.A_BOLD)

        y0, h = 4, H - 5
        ago = f"  update {int(time.time() - upd)}d lalu" if upd else ""
        self.box(y0, 0, h, W, f"Job & step (tap = buka log){ago}", True)
        ih = h - 2
        if err and not det:
            self.put(y0 + 1, 2, err[: W - 4], curses.color_pair(2))
            return
        if not det:
            self.put(y0 + 1, 2, "memuat...", curses.A_DIM)
            return
        rows, live_idx = [], None
        for job in det.get("jobs") or []:
            js, jc = status_sym(job.get("status"), job.get("conclusion"), frame)
            end = job.get("completedAt") if job.get("status") == "completed" else ""
            rows.append((f" {js} {job.get('name', '')}", dur_str(job.get("startedAt"), end), jc, True, job, None))
            if job.get("status") == "in_progress":
                live_idx = len(rows) - 1
            for s in job.get("steps") or []:
                ss, sc = status_sym(s.get("status"), s.get("conclusion"), frame)
                send = s.get("completedAt") if s.get("status") == "completed" else ""
                rows.append((f"    {ss} {s.get('name', '')}", dur_str(s.get("startedAt"), send), sc, False, job, s))
                if s.get("status") == "in_progress":
                    live_idx = len(rows) - 1
        self.det_items = [(r[4], r[5]) for r in rows]
        if not rows:
            self.put(y0 + 1, 2, "Belum ada job (menunggu runner)...", curses.color_pair(3))
            return
        mx = max(0, len(rows) - ih)
        if self.follow and live_idx is not None:
            self.det_sel = live_idx
            self.detail_scroll = live_idx - ih // 2
        self.det_sel = max(0, min(self.det_sel, len(rows) - 1))
        if self.det_ensure:
            if self.det_sel < self.detail_scroll:
                self.detail_scroll = self.det_sel
            elif self.det_sel >= self.detail_scroll + ih:
                self.detail_scroll = self.det_sel - ih + 1
            self.det_ensure = False
        self.detail_scroll = max(0, min(self.detail_scroll, mx))
        for i in range(ih):
            ri = self.detail_scroll + i
            if ri >= len(rows):
                break
            left, tail, c, bold, job, step = rows[ri]
            attr = curses.color_pair(c) | (curses.A_BOLD if bold or c != 5 else 0)
            if ri == self.det_sel:
                attr |= curses.A_REVERSE
            self.put(y0 + 1 + i, 1, fit(left, tail + " ", W - 3), attr)
            self.det_rows.append((y0 + 1 + i, job, step))
        if len(rows) > ih:
            thumb = max(1, ih * ih // len(rows))
            pos = self.detail_scroll * (ih - thumb) // max(1, mx)
            for i in range(thumb):
                self.put(y0 + 1 + pos + i, W - 1, "┃", curses.color_pair(4) | curses.A_BOLD)

    def draw_wf_log(self, H, W):
        frame = self.frame()
        # tombol
        x = 0
        for name, label in (("back", " ◀ Kembali "), ("err", " Copy Error "),
                            ("all", " Copy Semua "), ("reload", " ⟳ "), ("follow", " ▼ ")):
            self.put(2, x, label, curses.color_pair(6) | curses.A_BOLD)
            self.log_buttons.append((x, x + len(label), name))
            x += len(label) + 1
        with self.log_lock:
            lines, kinds, note, err = self.log_lines, self.log_kinds, self.log_note, self.log_err
            loading, rev = self.log_loading, self.log_rev
        job, step = self.fresh_job_step()
        job = job or {}
        target = step or job
        sym, col = status_sym(target.get("status"), target.get("conclusion"), frame)
        what = step.get("name", "") if step else "Seluruh job"
        live = target.get("status") != "completed"
        parts = []
        if live:
            age = f" {int(time.time() - self.log_updated)}d" if self.log_updated else ""
            parts.append(f"{frame} LIVE{age}")
            if self.log_tail:
                parts.append("ikuti")
        if loading and not live:
            parts.append("memuat...")
        info = ("  " + " · ".join(parts)) if parts else ""
        self.put(3, 0, f" {sym} {what} · {job.get('name', '')}  ({len(lines)} baris){info} {note}"[: W - 1],
                 curses.color_pair(col) | curses.A_BOLD)
        if err:
            self.put(3, max(0, W - 1 - len(err[:40])), err[:40], curses.color_pair(2))

        y0, h = 4, H - 5
        ih, iw = h - 2, W - 2
        wrapped = []
        if lines:
            if self._wrap_key != (rev, iw):
                w = []
                for ln, k in zip(lines, kinds):
                    for i in range(0, max(1, len(ln)), iw):
                        w.append((ln[i:i + iw], k))
                self._wrap, self._wrap_key = w, (rev, iw)
            wrapped = self._wrap
        total = len(wrapped)
        mx = max(0, total - ih)
        if self.log_tail:
            self.log_scroll = mx
        self.log_scroll = max(0, min(self.log_scroll, mx))
        a = self.log_scroll + 1 if total else 0
        b = min(total, self.log_scroll + ih)
        self.box(y0, 0, h, W, f"Log  {a}-{b}/{total}", True)
        if not lines:
            if err:
                self.put(y0 + 1, 2, err[: W - 4], curses.color_pair(2))
            elif loading:
                self.put(y0 + 1, 2, "memuat log...", curses.A_DIM)
            else:
                self.put(y0 + 1, 2, "(log kosong / belum tersedia)", curses.A_DIM)
            return
        for i in range(ih):
            ri = self.log_scroll + i
            if ri >= total:
                break
            text, k = wrapped[ri]
            attr = {"err": curses.color_pair(2) | curses.A_BOLD, "warn": curses.color_pair(3),
                    "group": curses.color_pair(4) | curses.A_BOLD,
                    "script": curses.A_DIM}.get(k, 0)
            self.put(y0 + 1 + i, 1, text, attr)
        if total > ih:
            thumb = max(1, ih * ih // total)
            pos = self.log_scroll * (ih - thumb) // max(1, mx)
            for i in range(thumb):
                self.put(y0 + 1 + pos + i, W - 1, "┃", curses.color_pair(4) | curses.A_BOLD)

    def draw_wf_page(self, H, W):
        slug = self.poller.slug or "-"
        if self.wf_view == "list":
            title = f" WORKFLOW   {slug}"
            help_ = "↑/↓ pilih  Enter/tap buka  r refresh  ^T tab  ^Q keluar"
        elif self.wf_view == "detail":
            title = f" WORKFLOW › detail run   {slug}"
            help_ = "↑/↓ pilih  Enter/tap buka log  l log gagal  Backspace kembali  r refresh"
        else:
            title = f" WORKFLOW › log step   {slug}"
            help_ = "↑/↓ scroll  End ikuti  e copy error  c copy semua  r reload  Backspace"
        self.put(1, 0, title.ljust(W - 1), curses.color_pair(6) | curses.A_BOLD)
        if H < 8:
            self.put(2, 0, "Layar terlalu kecil")
            return None
        if self.wf_view == "list":
            self.draw_wf_list(H, W)
        elif self.wf_view == "detail":
            self.draw_wf_detail(H, W)
        else:
            self.draw_wf_log(H, W)
        flash = self.flash if time.time() < self.flash_until else ""
        self.put(H - 1, 0, (flash or help_)[: W - 1],
                 curses.color_pair(3) | curses.A_BOLD if flash else curses.A_DIM)
        return None

    # ---------- draw utama ----------
    def draw(self):
        self.scr.erase()
        H, W = self.scr.getmaxyx()
        self.regions, self.wf_rows, self.back_row = [], [], None
        self.det_rows, self.log_buttons = [], []
        self.draw_tabbar(W)
        cur = self.draw_patch_page(H, W) if self.tab == 0 else self.draw_wf_page(H, W)
        if cur:
            curses.curs_set(1)
            try:
                self.scr.move(*cur)
            except curses.error:
                pass
        else:
            curses.curs_set(0)
        self.scr.refresh()

    # ---------- overlay ----------
    def show_text(self, title, text):
        pos = 0
        self.scr.timeout(-1)
        while True:
            H, W = self.scr.getmaxyx()
            lines = []
            for ln in text.splitlines() or [""]:
                lines += textwrap.wrap(ln, max(10, W - 4), replace_whitespace=False) or [""]
            self.scr.erase()
            self.put(0, 0, f" {title} ".ljust(W - 1), curses.color_pair(2) | curses.A_BOLD | curses.A_REVERSE)
            for i in range(H - 2):
                if pos + i < len(lines):
                    self.put(1 + i, 1, lines[pos + i])
            self.put(H - 1, 0, "↑/↓ scroll   Enter/q tutup"[: W - 1], curses.A_DIM)
            curses.curs_set(0)
            self.scr.refresh()
            k = self.scr.get_wch()
            mx = max(0, len(lines) - (H - 2))
            if k in ("\n", "\r", "q", "Q", "\x1b"):
                return
            if k == curses.KEY_UP:
                pos = max(0, pos - 1)
            elif k == curses.KEY_DOWN:
                pos = min(mx, pos + 1)
            elif k == curses.KEY_NPAGE:
                pos = min(mx, pos + H - 3)
            elif k == curses.KEY_PPAGE:
                pos = max(0, pos - (H - 3))
            elif k == curses.KEY_MOUSE:
                try:
                    _, _, _, _, b = curses.getmouse()
                except curses.error:
                    continue
                if b & BTN4:
                    pos = max(0, pos - 3)
                elif b & BTN5:
                    pos = min(mx, pos + 3)

    # ---------- git ----------
    def git(self, args, stdin_text=None, timeout=None):
        env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_EDITOR="true")
        try:
            p = subprocess.run(
                ["git"] + args, cwd=self.repo, env=env, text=True,
                input=stdin_text, stdin=None if stdin_text is not None else subprocess.DEVNULL,
                capture_output=True, timeout=timeout,
            )
            return p.returncode == 0, ((p.stdout or "") + (p.stderr or "")).strip()
        except subprocess.TimeoutExpired:
            return False, "Timeout"
        except FileNotFoundError:
            return False, "git tidak ditemukan (pkg install git)"

    def reset_status(self):
        self.status = [PENDING] * 5
        self.detail = [""] * 5

    def run_pipeline(self):
        msg = self.msg.text.strip()
        patch = self.patch.text.replace("\r\n", "\n").replace("\r", "\n")
        if not msg:
            self.note = "Commit message masih kosong!"
            self.focus = F_MSG
            return
        if not patch.strip():
            self.note = "Patch masih kosong!"
            self.focus = F_PATCH
            return
        if not patch.endswith("\n"):
            patch += "\n"

        snap = (msg, patch)
        start = self.resume_from if (self.snapshot == snap and self.resume_from > 0) else 0
        self.snapshot = snap
        if start == 0:
            self.reset_status()
        else:
            for i in range(start):
                self.status[i] = OK
            for i in range(start, 5):
                self.status[i] = PENDING
                self.detail[i] = ""

        self.busy = True
        self.note = "Memproses..." if start == 0 else f"Melanjutkan dari {STEPS[start]}..."

        actions = [
            lambda: self.git(["apply", "--check", "-"], patch),
            lambda: self.git(["apply", "-"], patch),
            lambda: self.git(["add", "-A"]),
            lambda: self.git(["commit", "-m", msg]),
            lambda: self.git(["push"], timeout=180),
        ]

        for i in range(start, 5):
            self.status[i] = RUNNING
            self.draw()
            ok, out = actions[i]()
            if ok:
                self.status[i] = OK
                first = next((l for l in out.splitlines() if l.strip()), "")
                self.detail[i] = first if i >= 3 else ""
                self.resume_from = i + 1 if i >= 1 else 0
            else:
                self.status[i] = FAIL
                self.detail[i] = next((l for l in out.splitlines() if l.strip()), "gagal")
                self.busy = False
                if i == 0:
                    self.resume_from = 0
                    self.note = "Patch error. Perbaiki patch lalu jalankan lagi."
                else:
                    self.resume_from = i
                    self.note = f"{STEPS[i]} gagal. Jalankan lagi untuk melanjutkan dari sini."
                self.draw()
                self.show_text(f"{STEPS[i]} GAGAL", out or "(tanpa output)")
                return

        self.busy = False
        self.msg.clear()
        self.patch.clear()
        self.snapshot = None
        self.resume_from = 0
        self.focus = F_MSG
        self.note = "Selesai! Push berhasil, input dikosongkan. Ctrl+T untuk lihat workflow."
        self.poller.poll_now(boost=90)

    # ---------- input ----------
    def next_focus(self, d):
        self.focus = (self.focus + d) % 3

    def read_keys(self):
        self.scr.timeout(500)  # bangun tiap 0.5 dtk agar spinner & data workflow hidup
        try:
            keys = [self.scr.get_wch()]
        except curses.error:
            return []
        self.scr.timeout(0)
        while len(keys) < 5000:
            try:
                keys.append(self.scr.get_wch())
            except curses.error:
                break
        self.scr.timeout(-1)
        return keys

    def switch_tab(self, t):
        self.tab = t
        if t == 1:
            self.poller.poll_now()

    def on_mouse(self):
        try:
            _, mx, my, _, bstate = curses.getmouse()
        except curses.error:
            return
        wheel = bstate & (BTN4 | BTN5)
        if wheel:
            d = -3 if bstate & BTN4 else 3
            if self.tab == 1:
                if self.wf_view == "list":
                    self.move_sel(d)
                elif self.wf_view == "detail":
                    self.follow = False
                    self.detail_scroll = max(0, self.detail_scroll + d)
                else:
                    self.log_tail = False
                    self.log_scroll = max(0, self.log_scroll + d)
            elif not any(r[4] == F_MSG and r[0] <= my < r[0] + r[2] for r in self.regions):
                self.patch.scroll(d)
            return
        if not bstate & CLICK:
            return
        now = time.time()
        if now - self._last_click < 0.25:
            return
        self._last_click = now
        if my == 0:
            for x0, x1, idx in self.tab_regions:
                if x0 <= mx < x1:
                    self.switch_tab(idx)
            return
        if self.tab == 1:
            if self.wf_view == "list":
                for ry, rid in self.wf_rows:
                    if ry == my:
                        self.sel_id = rid
                        self.open_detail(rid)
                        return
            elif self.wf_view == "detail":
                if self.back_row == my:
                    self.close_detail()
                    return
                for i, (ry, job, step) in enumerate(self.det_rows):
                    if ry == my:
                        self.follow = False
                        self.open_log(job, step)
                        return
            else:
                if my == 2:
                    for x0, x1, name in self.log_buttons:
                        if x0 <= mx < x1:
                            self.log_action(name)
                            return
            return
        for (ry, rx, rh, rw, f) in self.regions:
            if ry <= my < ry + rh and rx <= mx < rx + rw:
                if f == F_WF:
                    self.switch_tab(1)
                    return
                self.focus = f
                if f == F_BTN:
                    self.run_pipeline()
                return

    # ---------- log step ----------
    def set_flash(self, text, secs=3):
        self.flash = text
        self.flash_until = time.time() + secs

    def show_failed_log(self):
        if self.view_id is None:
            return
        self.set_flash("Memuat log run yang gagal...", 120)
        self.draw()
        text = self.poller.fetch_log(self.view_id)
        self.flash_until = 0
        self.show_text("Log run gagal", text)

    def open_log(self, job, step):
        self.wf_view = "log"
        self.log_job, self.log_step = job, step
        with self.log_lock:
            self.log_lines, self.log_kinds, self.log_note, self.log_err = [], [], "", ""
            self.log_rev += 1
        self.log_scroll = 0
        self.log_updated = 0.0
        target = step or job
        self.log_tail = target.get("status") != "completed"
        self.log_was_running = self.log_tail
        self.start_log_fetch()

    def close_log(self):
        self.wf_view = "detail"
        self.log_gen += 1
        self.log_loading = False

    def fresh_job_step(self):
        """Job/step versi terbaru dari polling (status berubah saat run berjalan)."""
        job, step = self.log_job, self.log_step
        det, _, _ = self.poller.detail_snapshot()
        if det and job:
            for j in det.get("jobs") or []:
                if j.get("databaseId") == job.get("databaseId"):
                    job = j
                    if step:
                        for st in j.get("steps") or []:
                            if st.get("number") == step.get("number"):
                                step = st
                                break
                    break
        return job, step

    def start_log_fetch(self):
        job, step = self.fresh_job_step()
        rid = self.view_id
        if not job or job.get("databaseId") is None:
            self.log_err = "Job tidak punya id"
            return
        self.log_gen += 1
        gen = self.log_gen
        self.log_loading = True
        self.log_fetched = time.time()
        sname = step.get("name") if step else None
        jid = job["databaseId"]
        live = job.get("status") != "completed"

        def work():
            ok, lines, kinds, note, err = False, [], [], "", ""
            if not live:   # job selesai: log per step akurat dari gh run view --log
                ok, text = self.poller.fetch_job_log(rid, jid)
                if ok:
                    lines, kinds, note = parse_job_log(text, sname)
                else:
                    err = text
            if not ok:     # job masih berjalan (atau gh menolak): API log + potong per jendela waktu step
                ok, text = self.poller.fetch_job_log_live(jid)
                if ok:
                    lines, kinds, note = parse_api_log(text, step)
                    err = ""
                else:
                    err = text + (" (dicoba lagi otomatis)" if live else "")
            with self.log_lock:
                if gen != self.log_gen:
                    return
                if ok:
                    self.log_lines, self.log_kinds, self.log_note, self.log_err = lines, kinds, note, ""
                    self.log_updated = time.time()
                else:
                    self.log_err = err
                self.log_rev += 1
                self.log_loading = False

        threading.Thread(target=work, daemon=True).start()

    def log_running(self):
        job, step = self.fresh_job_step()
        if not job:
            return False
        return (step or job).get("status") != "completed"

    def log_action(self, name):
        if name == "back":
            self.close_log()
        elif name == "reload":
            self.start_log_fetch()
        elif name == "follow":
            self.log_tail = True
        else:
            self.do_copy(name)

    def do_copy(self, mode):
        with self.log_lock:
            lines, kinds = list(self.log_lines), list(self.log_kinds)
        if not lines:
            self.set_flash("Log kosong, tidak ada yang disalin")
            return
        if mode == "err":
            text, n = extract_errors(lines, kinds)
            if not text:
                self.set_flash("Tidak ada baris error di log ini")
                return
            label = f"{n} baris error (+konteks)"
        else:
            text, label = "\n".join(lines), f"{len(lines)} baris log"
        self.set_flash("Menyalin...", 60)
        self.draw()
        ok, msg = copy_text(text + "\n")
        self.set_flash(f"✔ {label} tersalin via {msg}" if ok else f"✘ Gagal menyalin: {msg}", 6)

    def on_key_wf(self, k):
        back_keys = ("\x7f", "\b", "\x1b", "q", curses.KEY_BACKSPACE, curses.KEY_LEFT)
        if self.wf_view == "log":
            if k in back_keys:
                self.close_log()
            elif k == "e":
                self.do_copy("err")
            elif k == "c":
                self.do_copy("all")
            elif k in ("f", "F"):
                self.log_tail = True
            elif k == "r":
                self.start_log_fetch()
            elif k in (curses.KEY_UP, curses.KEY_DOWN, curses.KEY_PPAGE, curses.KEY_NPAGE,
                       curses.KEY_HOME, curses.KEY_END):
                self.log_tail = False
                d = {curses.KEY_UP: -1, curses.KEY_DOWN: 1, curses.KEY_PPAGE: -12, curses.KEY_NPAGE: 12}
                if k == curses.KEY_HOME:
                    self.log_scroll = 0
                elif k == curses.KEY_END:
                    self.log_scroll = 10 ** 9
                    self.log_tail = True
                else:
                    self.log_scroll = max(0, self.log_scroll + d[k])
            return
        if k == "r":
            self.poller.poll_now()
            return
        if self.wf_view == "list":
            if k == curses.KEY_UP:
                self.move_sel(-1)
            elif k == curses.KEY_DOWN:
                self.move_sel(1)
            elif k == curses.KEY_PPAGE:
                self.move_sel(-10)
            elif k == curses.KEY_NPAGE:
                self.move_sel(10)
            elif k == curses.KEY_HOME:
                self.move_sel(-9999)
            elif k == curses.KEY_END:
                self.move_sel(9999)
            elif k in ("\n", "\r") and self.sel_id is not None:
                self.open_detail(self.sel_id)
        else:
            if k in back_keys:
                self.close_detail()
            elif k == "l":
                self.show_failed_log()
            elif k in ("\n", "\r"):
                if 0 <= self.det_sel < len(self.det_items):
                    job, step = self.det_items[self.det_sel]
                    self.open_log(job, step)
            elif k in (curses.KEY_UP, curses.KEY_DOWN, curses.KEY_PPAGE, curses.KEY_NPAGE,
                       curses.KEY_HOME, curses.KEY_END):
                self.follow = False
                self.det_ensure = True
                d = {curses.KEY_UP: -1, curses.KEY_DOWN: 1, curses.KEY_PPAGE: -10, curses.KEY_NPAGE: 10,
                     curses.KEY_HOME: -10 ** 6, curses.KEY_END: 10 ** 6}[k]
                self.det_sel = max(0, min(max(0, len(self.det_items) - 1), self.det_sel + d))

    def on_key_patch(self, k):
        if k == "\x0e":
            self.next_focus(1)
        elif k == "\x10":
            self.next_focus(-1)
        elif k == "\x12":
            self.run_pipeline()
        elif k == "\x17":
            self.show_wf = not self.show_wf
        elif k == "\x0c":
            if self.focus == F_MSG:
                self.msg.clear()
            elif self.focus == F_PATCH:
                self.patch.clear()
        elif self.focus == F_BTN:
            if k in ("\n", "\r", " "):
                self.run_pipeline()
            elif k == "\t" or k == curses.KEY_DOWN:
                self.next_focus(1)
            elif k == curses.KEY_UP:
                self.next_focus(-1)
        else:
            ed = self.msg if self.focus == F_MSG else self.patch
            if not ed.handle(k):
                if self.focus == F_MSG and k in ("\n", "\r", "\t", curses.KEY_DOWN):
                    self.next_focus(1)

    def loop(self):
        while True:
            if self.tab == 1 and self.wf_view == "log" and not self.log_loading:
                running = self.log_running()
                if (running or self.log_was_running) and time.time() - self.log_fetched >= 3:
                    self.start_log_fetch()
                self.log_was_running = running
            self.draw()
            try:
                keys = self.read_keys()
            except KeyboardInterrupt:
                break
            quit_ = False
            for k in keys:
                if k in ("\x11", "\x03"):
                    quit_ = True
                    break
                if k == "\x14":
                    self.switch_tab(1 - self.tab)
                elif k == curses.KEY_RESIZE:
                    pass
                elif k == curses.KEY_MOUSE:
                    self.on_mouse()
                elif self.tab == 1:
                    self.on_key_wf(k)
                else:
                    self.on_key_patch(k)
            if quit_:
                break
        self.poller.alive = False


def setup_colors():
    curses.start_color()
    try:
        curses.use_default_colors()
        bg = -1
    except curses.error:
        bg = curses.COLOR_BLACK
    curses.init_pair(1, curses.COLOR_GREEN, bg)
    curses.init_pair(2, curses.COLOR_RED, bg)
    curses.init_pair(3, curses.COLOR_YELLOW, bg)
    curses.init_pair(4, curses.COLOR_CYAN, bg)
    curses.init_pair(5, curses.COLOR_WHITE, bg)
    curses.init_pair(6, curses.COLOR_BLACK, curses.COLOR_CYAN)
    if curses.COLORS >= 256:
        curses.init_pair(7, 255, 22)   # baris tambah: latar hijau tua
        curses.init_pair(8, 255, 52)   # baris hapus : latar merah tua
    else:
        curses.init_pair(7, curses.COLOR_GREEN, bg)
        curses.init_pair(8, curses.COLOR_RED, bg)


def main(scr, repo, slug):
    locale.setlocale(locale.LC_ALL, "")
    setup_colors()
    curses.mousemask(curses.ALL_MOUSE_EVENTS)
    scr.keypad(True)
    curses.raw()  # Ctrl+Q/Ctrl+C sampai ke aplikasi (tidak ditelan XON/XOFF)
    App(scr, repo, slug).loop()


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Patch -> commit -> push TUI + GitHub Actions")
    ap.add_argument("repo", nargs="?", default=os.getcwd())
    ap.add_argument("-R", "--gh", help="owner/repo atau URL GitHub untuk tab workflow")
    a = ap.parse_args()
    repo = os.path.abspath(a.repo)
    try:
        r = subprocess.run(["git", "rev-parse", "--is-inside-work-tree"], cwd=repo,
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(f"Bukan git repository: {repo}")
            sys.exit(1)
        if a.gh:
            slug = parse_github(a.gh)
        else:
            o = subprocess.run(["git", "remote", "get-url", "origin"], cwd=repo,
                               capture_output=True, text=True)
            slug = parse_github(o.stdout) if o.returncode == 0 else None
    except FileNotFoundError:
        print("git belum terpasang. Jalankan: pkg install git")
        sys.exit(1)
    os.environ.setdefault("ESCDELAY", "25")
    curses.wrapper(main, repo, slug)
