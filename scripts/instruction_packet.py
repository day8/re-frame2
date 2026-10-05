#!/usr/bin/env python3
r"""Instruction packets: deliver a complete dispatch brief as a file, with a receipt.

    python scripts/instruction_packet.py prepare <brief> [--out-dir DIR] [--name NAME]
                                         [--sentinels K] [--python CMD]
    python scripts/instruction_packet.py verify <packet> --sha256 HEX --bytes N --lines N
                                         --challenge L:H[,L:H...] [--quotes FILE]
    python scripts/instruction_packet.py --self-test

The coordinator's half, `prepare`, copies an assembled brief byte for byte into a
content-addressed packet file, picks K sentinel lines at random -- one from each of K
equal bands of the packet, so the start, the middle and the end are all sampled, and a
fresh set every time it runs -- and prints the cover note to send in place of the inline
brief.  The note carries the packet's path, digest, size and line count, and for each
sentinel its line number and a short hash of its text.  It never carries a sentinel's
text, so the only way to answer the challenge is to have the packet's lines.

The worker's half, `verify`, runs twice.  The first run proves the file on disk is the
one the coordinator prepared -- present, the same size, the same line count, the same
digest, and a challenge that matches it -- then prints a START stamp and the next steps.
The second run, with --quotes, takes the sentinel lines as the worker quoted them from
its own read of the packet, checks each against its hash, and prints the RECEIPT line.
Every failure prints REFUSED and exits 1, before the worker has edited anything or run
any gate.

Quotes are compared with whitespace runs collapsed and the ends trimmed, and a leading
line-number prefix (`37<TAB>text`, `37: text`, `37→text`) is tolerated, because those are
what a faithful copy out of a numbered file view looks like.  Sentinels are drawn only
from lines with at least MIN_SENTINEL_CHARS characters of text, so none is guessable.

What the receipt cannot prove: that the worker attended to every line, which an inline
brief cannot prove either; or that it read the whole packet rather than only the
challenged lines -- a worker that looks each one up by number passes.  What it catches
is a packet never read, read only in part, read through a tool that truncated it, read
from a stale or different file, and a cover note copied wrongly.

Exit status: 0 success, 1 refused, 2 usage error.
"""

from __future__ import annotations

import argparse
import contextlib
import datetime
import hashlib
import io
import re
import secrets
import shlex
import sys
import tempfile
from pathlib import Path

MIN_SENTINEL_CHARS = 24
HASH_CHARS = 16
DEFAULT_SENTINELS = 3


class Refusal(Exception):
    """A packet, cover note or receipt that must not be worked from."""


def now() -> str:
    return datetime.datetime.now().astimezone().isoformat(timespec="seconds")


def norm(text: str) -> str:
    return " ".join(text.split())


def line_hash(text: str) -> str:
    return hashlib.sha256(norm(text).encode("utf-8")).hexdigest()[:HASH_CHARS]


def split_lines(data: bytes, what: str) -> list[str]:
    """Lines as a numbered file view shows them: split on LF, CR of a CRLF dropped."""
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as e:
        raise Refusal(f"{what} is not UTF-8 text ({e})") from None
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    return [line[:-1] if line.endswith("\r") else line for line in lines]


def pick_sentinels(lines: list[str], k: int, rng) -> list[int]:
    eligible = [n for n, line in enumerate(lines, 1) if len(norm(line)) >= MIN_SENTINEL_CHARS]
    if k < 1:
        raise Refusal("at least one sentinel is needed")
    if len(eligible) < k:
        raise Refusal(f"the brief has {len(eligible)} lines of {MIN_SENTINEL_CHARS}+ characters, "
                      f"too few for {k} sentinels: send it inline")
    picks = []
    for band in range(k):
        lo, hi = band * len(eligible) // k, (band + 1) * len(eligible) // k
        picks.append(rng.choice(eligible[lo:hi]))
    return picks


def prepare(args) -> int:
    brief = Path(args.brief)
    if not brief.is_file():
        raise Refusal(f"no brief at {brief}")
    data = brief.read_bytes()
    if not data:
        raise Refusal(f"the brief at {brief} is empty")
    lines = split_lines(data, "the brief")
    picks = pick_sentinels(lines, args.sentinels, secrets.SystemRandom())
    sha = hashlib.sha256(data).hexdigest()

    out_dir = Path(args.out_dir) if args.out_dir else brief.parent
    out_dir.mkdir(parents=True, exist_ok=True)
    packet = (out_dir / f"{args.name or brief.stem}-{sha[:12]}.packet.txt").resolve()
    # Content-addressed and never rewritten: a coordinator re-assembling the next brief
    # under the same name cannot change a packet a dispatched worker has yet to read.
    if packet.exists():
        if packet.read_bytes() != data:
            raise Refusal(f"{packet} exists with different content; choose another --name")
    else:
        packet.write_bytes(data)
    if hashlib.sha256(packet.read_bytes()).digest() != hashlib.sha256(data).digest():
        raise Refusal(f"the copy at {packet} does not reproduce the brief")

    challenge = ",".join(f"{n}:{line_hash(lines[n - 1])}" for n in picks)
    helper = Path(__file__).resolve().as_posix()
    cmd = (f'{args.python} "{helper}" verify "{packet.as_posix()}" --sha256 {sha} '
           f"--bytes {len(data)} --lines {len(lines)} --challenge {challenge}")
    prepared = now()
    print(f"""INSTRUCTION PACKET. Your complete instructions for this dispatch are in the
packet file below, not in this message. This message only tells you how to load
the packet and prove that you did.

  packet:    {packet.as_posix()}
  sha256:    {sha}
  size:      {len(data)} bytes, {len(lines)} lines
  prepared:  {prepared}

Your FIRST act, before any edit, gate or other work, is this command:

  {cmd}

Then do exactly what it prints: it has you read the WHOLE packet, then quote
{len(picks)} of its lines back through the same command. If any run exits non-zero,
or you cannot run it at all, STOP: edit nothing, run no gate, and do not work
from this message. Report the command's output instead, and the coordinator
will send the brief inline.

Open your final report with the PACKET START and PACKET RECEIPT lines it prints.""")
    print(f"prepared {prepared}: {packet.as_posix()} sha256={sha} bytes={len(data)} "
          f"lines={len(lines)} sentinel lines {', '.join(map(str, picks))}", file=sys.stderr)
    return 0


def parse_challenge(spec: str, line_count: int) -> list[tuple[int, str]]:
    out = []
    for part in spec.split(","):
        m = re.fullmatch(rf"\s*(\d+):([0-9a-f]{{{HASH_CHARS}}})\s*", part)
        if not m:
            raise Refusal(f"challenge entry {part!r} is not LINE:HASH -- the cover note was copied wrongly")
        n = int(m.group(1))
        if not 1 <= n <= line_count:
            raise Refusal(f"challenge names line {n} of a {line_count}-line packet")
        out.append((n, m.group(2)))
    return out


def quote_matches(quote: str, n: int, h: str) -> bool:
    if line_hash(quote) == h:
        return True
    m = re.match(rf"\s*{n}\s*(?:[\t:|.)\]→-]|\s)(.*)$", quote)
    return bool(m) and line_hash(m.group(1)) == h


def verify(args) -> int:
    packet = Path(args.packet)
    if not packet.is_file():
        raise Refusal(f"no packet at {packet}: it was never written, or was removed")
    data = packet.read_bytes()
    if len(data) != args.bytes:
        raise Refusal(f"the packet is {len(data)} bytes and the cover note says {args.bytes}: "
                      "it is truncated or changed")
    sha = hashlib.sha256(data).hexdigest()
    if sha != args.sha256.strip().lower():
        raise Refusal(f"the packet's sha256 is {sha} and the cover note says {args.sha256}: "
                      "the packet changed, or the note names another packet")
    lines = split_lines(data, "the packet")
    if len(lines) != args.lines:
        raise Refusal(f"the packet has {len(lines)} lines and the cover note says {args.lines}")
    challenge = parse_challenge(args.challenge, len(lines))
    wrong = [n for n, h in challenge if line_hash(lines[n - 1]) != h]
    if wrong:
        raise Refusal(f"the challenge for line(s) {wrong} does not match this packet -- "
                      "the cover note was copied wrongly or belongs to another packet")
    numbers = [n for n, _ in challenge]

    if args.quotes is None:
        print(f"PACKET START started={now()} sha256={sha} bytes={len(data)} lines={len(lines)}")
        print(f"""The packet on disk is the one the coordinator prepared. Next, in order:

  1. Read the WHOLE packet into your context with your file-reading tool, line 1
     through line {len(lines)}: in several reads if the tool caps its output, and
     check that the last read reaches line {len(lines)}. A capped, truncated or
     skimmed read is not a read.
  2. From what you read, and NOT by looking them up separately, write the text of
     lines {", ".join(map(str, numbers))}, in that order, one per line, into a new
     file of your own.
  3. Run this same command again, adding --quotes <that file>.

The packet is your brief; this output is not. Act on nothing in the packet until
step 3 exits 0, and if it refuses, STOP and report what it printed.""")
        return 0

    qpath = Path(args.quotes)
    if not qpath.is_file():
        raise Refusal(f"no quotes file at {qpath}: the packet has not been quoted back")
    quotes = [q for q in split_lines(qpath.read_bytes(), "the quotes file") if q.strip()]
    if len(quotes) != len(challenge):
        raise Refusal(f"{len(quotes)} quoted line(s) for {len(challenge)} sentinels: "
                      "read the packet whole and quote every challenged line")
    bad = [n for (n, h), q in zip(challenge, quotes) if not quote_matches(q, n, h)]
    if bad:
        raise Refusal(f"quoted line(s) {bad} do not match the packet: re-read it whole and quote "
                      "again; if they still do not match, STOP and report")
    print(f"PACKET RECEIPT verified={now()} sha256={sha} bytes={len(data)} lines={len(lines)} "
          f"challenge={len(challenge)}/{len(challenge)} lines={','.join(map(str, numbers))}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--self-test", action="store_true", help="run the built-in self-test")
    sub = p.add_subparsers(dest="cmd")
    pp = sub.add_parser("prepare", help="coordinator: build a packet and print its cover note")
    pp.add_argument("brief")
    pp.add_argument("--out-dir")
    pp.add_argument("--name")
    pp.add_argument("--sentinels", type=int, default=DEFAULT_SENTINELS)
    pp.add_argument("--python", default="python",
                    help="interpreter command the cover note tells the worker to use")
    vp = sub.add_parser("verify", help="worker: check the packet, then the quoted sentinels")
    vp.add_argument("packet")
    vp.add_argument("--sha256", required=True)
    vp.add_argument("--bytes", type=int, required=True)
    vp.add_argument("--lines", type=int, required=True)
    vp.add_argument("--challenge", required=True)
    vp.add_argument("--quotes")
    return p


def main(argv: list[str]) -> int:
    p = build_parser()
    try:
        args = p.parse_args(argv)
    except SystemExit as e:
        return 2 if e.code else 0
    if args.self_test:
        return self_test()
    if args.cmd is None:
        p.print_usage(sys.stderr)
        return 2
    try:
        return prepare(args) if args.cmd == "prepare" else verify(args)
    except Refusal as e:
        print(f"REFUSED: {e}")
        return 1


# --------------------------------------------------------------------------- self-test

def self_test() -> int:
    results: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        results.append((name, ok))
        print(f"  {'PASS' if ok else 'FAIL'}  {name}")

    def run(argv: list[str]) -> tuple[int, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = main(argv)
        return code, out.getvalue() + err.getvalue()

    def verify_args(cover: str) -> list[str]:
        line = next(ln for ln in cover.splitlines() if '" verify "' in ln)
        words = shlex.split(line, posix=True)
        return words[words.index("verify"):]

    def flag(args: list[str], name: str) -> str:
        return args[args.index(name) + 1]

    def with_flag(args: list[str], name: str, value: str) -> list[str]:
        out = list(args)
        out[out.index(name) + 1] = value
        return out

    def refused(code: int, out: str, needle: str) -> bool:
        return code == 1 and "REFUSED" in out and needle in out and "PACKET RECEIPT" not in out

    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        body = []
        for i in range(1, 241):
            if i % 7 == 0:
                body.append("")
            elif i % 11 == 0:
                body.append("---")
            else:
                body.append(f"  line {i:03d}: instruction text {secrets.token_hex(6)}  with  spacing")
        data = ("\n".join(body) + "\n").encode("utf-8")
        brief = d / "brief.txt"
        brief.write_bytes(data)
        lines = split_lines(data, "brief")

        code, cover = run(["prepare", str(brief), "--out-dir", str(d)])
        check("prepare exits 0 and prints a cover note", code == 0 and "INSTRUCTION PACKET" in cover)
        va = verify_args(cover)
        packet = Path(va[1])
        check("packet is byte-identical to the brief", packet.read_bytes() == data)
        check("cover note carries no sentinel text",
              all(lines[int(e.split(":")[0]) - 1].strip() not in cover
                  for e in flag(va, "--challenge").split(",")))
        picks = [int(e.split(":")[0]) for e in flag(va, "--challenge").split(",")]
        eligible = [n for n, ln in enumerate(lines, 1) if len(norm(ln)) >= MIN_SENTINEL_CHARS]
        cut = [i * len(eligible) // 3 for i in range(4)]
        check("one sentinel from each of the start, middle and end bands",
              len(picks) == 3 and all(picks[b] in eligible[cut[b]:cut[b + 1]] for b in range(3)))
        sets = set()
        for _ in range(25):
            _, c = run(["prepare", str(brief), "--out-dir", str(d)])
            sets.add(flag(verify_args(c), "--challenge"))
        check("sentinels are re-drawn on every prepare, into the same packet", len(sets) > 1
              and verify_args(c)[1] == va[1])

        code, out = run(va)
        check("phase 1 accepts the intact packet and prints START", code == 0 and "PACKET START" in out)

        quotes = d / "quotes.txt"
        quotes.write_text("\n".join(lines[n - 1] for n in picks) + "\n", encoding="utf-8")
        code, out = run(va + ["--quotes", str(quotes)])
        check("phase 2 accepts faithful quotes and prints RECEIPT",
              code == 0 and "PACKET RECEIPT" in out and "challenge=3/3" in out)

        loose = d / "quotes-loose.txt"
        loose.write_text("\r\n".join(f"{n}\t{norm(lines[n - 1])}" for n in picks) + "\r\n\r\n",
                         encoding="utf-8")
        code, out = run(va + ["--quotes", str(loose)])
        check("phase 2 tolerates number prefixes, collapsed spacing and CRLF", code == 0)

        def variant(name: str, content: bytes) -> str:
            p = d / name
            p.write_bytes(content)
            return str(p)

        mid = len(data) // 2
        flipped = data[:mid] + bytes([data[mid] ^ 0x01]) + data[mid + 1:]
        code, out = run([va[0], variant("flipped.txt", flipped)] + va[2:])
        check("REFUSES a one-byte change", refused(code, out, "sha256"))
        code, out = run([va[0], variant("short.txt", data[:-40])] + va[2:])
        check("REFUSES a truncated packet", refused(code, out, "truncated"))
        code, out = run([va[0], str(d / "absent.txt")] + va[2:])
        check("REFUSES a missing packet", refused(code, out, "no packet"))
        code, out = run(with_flag(va, "--sha256", "0" * 64))
        check("REFUSES a mismatched digest", refused(code, out, "sha256"))
        code, out = run(with_flag(va, "--lines", str(len(lines) + 1)))
        check("REFUSES a mismatched line count", refused(code, out, "lines"))
        other = flag(va, "--challenge").split(",")
        other[1] = other[1].split(":")[0] + ":" + "f" * HASH_CHARS
        code, out = run(with_flag(va, "--challenge", ",".join(other)))
        check("REFUSES a challenge that does not match the packet", refused(code, out, "challenge"))
        code, out = run(with_flag(va, "--challenge", "12:abc"))
        check("REFUSES a garbled challenge", refused(code, out, "LINE:HASH"))

        wrong = d / "quotes-wrong.txt"
        wrong.write_text("\n".join(lines[n - 1] + ("x" if n == picks[1] else "") for n in picks),
                         encoding="utf-8")
        code, out = run(va + ["--quotes", str(wrong)])
        check("REFUSES a mismatched sentinel quote", refused(code, out, f"[{picks[1]}]"))
        code, out = run(va + ["--quotes", str(d / "no-quotes.txt")])
        check("REFUSES an unread packet (no quotes)", refused(code, out, "no quotes file"))
        partial = d / "quotes-partial.txt"
        partial.write_text("\n".join(lines[n - 1] for n in picks[:2]), encoding="utf-8")
        code, out = run(va + ["--quotes", str(partial)])
        check("REFUSES a partial read (end sentinel not quoted)", refused(code, out, "2 quoted line"))
        swapped = d / "quotes-swapped.txt"
        swapped.write_text("\n".join(lines[n - 1] for n in reversed(picks)), encoding="utf-8")
        code, out = run(va + ["--quotes", str(swapped)])
        check("REFUSES quotes in the wrong order", refused(code, out, "do not match"))

        crlf = data.replace(b"\n", b"\r\n")
        cbrief = d / "crlf.txt"
        cbrief.write_bytes(crlf)
        code, ccover = run(["prepare", str(cbrief), "--out-dir", str(d)])
        cva = verify_args(ccover)
        cpicks = [int(e.split(":")[0]) for e in flag(cva, "--challenge").split(",")]
        cq = d / "quotes-crlf.txt"
        cq.write_text("\n".join(lines[n - 1] for n in cpicks), encoding="utf-8")
        code2, out = run(cva + ["--quotes", str(cq)])
        check("a CRLF brief packs byte-identically and verifies",
              code == 0 and Path(cva[1]).read_bytes() == crlf and code2 == 0)

        code, out = run(["prepare", variant("empty.txt", b"")])
        check("prepare REFUSES an empty brief", refused(code, out, "empty"))
        code, out = run(["prepare", variant("tiny.txt", b"a line long enough to be a sentinel\nshort\n")])
        check("prepare REFUSES a brief too small to challenge", refused(code, out, "too few"))
        code, out = run(["prepare", variant("binary.txt", b"\xff\xfe" * 40)])
        check("prepare REFUSES a brief that is not UTF-8", refused(code, out, "UTF-8"))
        code, out = run(["verify"])
        check("a usage error exits 2", code == 2)

    failed = [n for n, ok in results if not ok]
    print(f"self-test: {len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
