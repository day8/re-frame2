#!/usr/bin/env python3
"""Partition-aware + HTTP-fx + API-name + reply-contract drift guard for the
re-frame2-implementor skill (rf2-whsb0c + rf2-6c59ob + rf2-3fc89f.34).

The implementor skill is a control surface: API names and contract shapes copied
from it become a port author's tests, docs, and public surface. Two senior
reviews caught the skill drifting from shipped reality:

  * **rf2-whsb0c** — partition-aware teaching drift after EP-0001 landed the
    two-partition frame (app-db `:rf.db/app` / runtime-db `:rf.db/runtime`):
    stale `register-listener!` / `deregister-listener!` naming, and the
    public-lifecycle verb `destroy-adapter!` blurred with the adapter-spec map's
    internal `:dispose-adapter!` slot.
  * **rf2-6c59ob** — HTTP-fx + data-classification summary drift: the reference
    tour described the Spec 014 managed HTTP lifecycle as "the `:http` fx"
    (the canonical surface is `:rf.http/managed`), and the SKILL.md / tour
    summaries implied that *every* classification site accepts a
    `{:sensitive [paths] :large [paths]}` metadata map — contradicting the
    `reg-machine` schema-first exception (no top-level `:sensitive` / `:large`
    keys). EP-0015 then made the model owner-owned (one declaration surface per
    owner): durable app-db classification is frame-owned (`reg-frame`
    `:sensitive`/`:large {:app-db ...}`); machine `:data`, resource data/params,
    and HTTP bodies classify via per-slot `:sensitive?` / `:large?` schema props
    (the schema-first route — still NO top-level `reg-machine` keys); transient
    payloads classify via `:sensitive`/`:large` registration metadata. Rule 4
    below polices exactly the surviving invariant: a `reg-machine` line must
    never imply top-level `:sensitive` / `:large` keys.

This guard makes each of those re-introductions a build failure. It scans the
user-facing implementor docs and asserts:

  1. **No `deregister-listener!`** — the public trace-listener removal verb is
     `unregister-listener!` (spec/API.md, re_frame/trace.cljc).
  2. **No bare `:http` described as the Spec 014 managed/framework HTTP fx** —
     the managed surface is `:rf.http/managed`; bare `:http` is app/user/
     implementation-specific. (A line may mention bare `:http` only when it
     also marks it user/app/implementation-specific OR explicitly denies it is
     the managed surface.)
  3. **No public `dispose-adapter!`** — the public teardown verb is
     `destroy-adapter!`; `:dispose-adapter!` is the adapter-spec map's internal
     lifecycle slot. A line may use `:dispose-adapter!` / `dispose-adapter!`
     only when it marks it as the adapter-spec map key / internal slot (i.e.
     it is paired with `destroy-adapter!` or the words "slot" / "map key" /
     "adapter-spec" / "internal").
  4. **No `reg-machine` line implying top-level `:sensitive` / `:large`** — the
     machine `:data` exception is schema-first (`:data-schema` props), with NO
     metadata-bearing `reg-machine` arity.
  5. **No `rf2-XXXX` bead ids in user-facing leaves** — SKILL.md, README.md, and
     every `references/*.md` leaf carry NO internal `rf2-` tracker ids (L7 in the
     skill's `spec/design.md`). Bead ids are monorepo-internal workflow noise; an
     external port author has no `bd` and no bead corpus, so a leaked id is
     unexplained context that invites treating bead history as normative evidence.
     Use public, stable evidence instead — spec section links, fixture names, API
     entries, or a fully-qualified public PR link where it helps an external
     reader. The skill's own `spec/` meta-docs MAY mention bead ids (authoring /
     internal context) and are out of this scan's scope.
  6. **Unified reply-address / envelope contract** — after the unified reply-
     addressing migration, the app-facing authoring key is `:reply-to`;
     `:rf/reply-to` is the internal / normalized descriptor it lowers to. HTTP
     `:on-success` / `:on-failure` are SPLIT ROUTING sugar that receive the
     IDENTICAL canonical reply map (which event routes on success vs failure) —
     they do NOT reshape the reply into a second, narrower payload dialect. The
     transient reply map spells the work identity `:rf.reply/work-id`; bare
     `:work/id` is the durable ledger / verification / abstract-attempt identity.
     A user-facing leaf that reintroduces the retired teaching (`:rf/reply-to`
     as the public key / HTTP sugar as a reshaped envelope / bare `:work/id` on
     the reply map) can turn a conforming new port non-conforming. This rule is
     context-sensitive: legitimate INTERNAL `:rf/reply-to` descriptor mentions
     still pass.

Exit code:
    0  no drift detected
    1  drift detected (printed line-by-line; GitHub-Actions ::error:: under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_implementor_partition_drift.py
    python scripts/check_skill_implementor_partition_drift.py --verbose
    python scripts/check_skill_implementor_partition_drift.py --ci
    python scripts/check_skill_implementor_partition_drift.py --self-test

rf2-whsb0c + rf2-6c59ob.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Force UTF-8 on output streams — the corpus carries em-dash / arrows etc. and
# the default Windows console codec (cp1252) would crash on them (rf2 is
# maintained on Windows; the gate also runs on Linux CI).
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover - non-TextIO stream
        pass

SKILL_DIR = REPO_ROOT / "skills" / "re-frame2-implementor"

SCANNED_FILES = [
    SKILL_DIR / "SKILL.md",
    SKILL_DIR / "references" / "phase-2-impl-order.md",
    SKILL_DIR / "references" / "reference-impl-tour.md",
    SKILL_DIR / "references" / "decision-record.md",
    SKILL_DIR / "references" / "phase-1-decisions.md",
    SKILL_DIR / "references" / "cardinal-rules.md",
]


def _beadid_scanned_files() -> list[Path]:
    """User-facing leaves for the no-bead-ids scan (Rule 5): SKILL.md,
    README.md, and every `references/*.md` leaf. Globbed (not hand-listed) so a
    newly-added reference leaf is covered automatically. The skill's `spec/`
    meta-docs are deliberately NOT included — L7 permits bead ids there."""
    files = [SKILL_DIR / "SKILL.md", SKILL_DIR / "README.md"]
    files.extend(sorted((SKILL_DIR / "references").glob("*.md")))
    return files

# --- Rule 1: deregister-listener! is never the verb (it is unregister-listener!).
DEREGISTER_RE = re.compile(r"deregister-listener!")

# --- Rule 2: bare `:http` as the managed / Spec-014 framework surface.
# Fires when a line names bare `:http` as an fx/effect, UNLESS the same line
# marks it user/app/implementation-specific OR denies it is the managed surface.
BARE_HTTP_FX_RE = re.compile(r"`:http`\s*(?:fx|effect)|the\s*`:http`\s*fx", re.IGNORECASE)
HTTP_ALLOW_RE = re.compile(
    r"user-registered|user/app|app/user|app-level|application-level"
    r"|implementation-specific|impl-specific|app/user/implementation"
    r"|NOT a reserved|no reserved bare|not the (?:spec 014|managed)"
    r"|don't describe the managed",
    re.IGNORECASE,
)

# --- Rule 3: public dispose-adapter! (the public verb is destroy-adapter!).
# Fires when a line uses dispose-adapter! / :dispose-adapter! UNLESS the line
# marks it as the adapter-spec map key / internal lifecycle slot.
DISPOSE_ADAPTER_RE = re.compile(r":?dispose-adapter!")
DISPOSE_ALLOW_RE = re.compile(
    r"map key|map's|adapter-spec|adapter spec|internal|slot", re.IGNORECASE
)

# --- Rule 4: reg-machine line implying top-level :sensitive / :large keys.
REG_MACHINE_RE = re.compile(r"reg-machine")
# Phrasing that asserts reg-machine takes the metadata map. We only flag when a
# reg-machine line ALSO carries a {:sensitive ... :large ...}-style claim AND
# does NOT carry the schema-first / no-metadata-key disclaimer.
SENS_LARGE_MAP_RE = re.compile(r":sensitive\b|:large\b")
MACHINE_EXCEPTION_RE = re.compile(
    r"schema-first|data-schema|NO\s*`?:sensitive|no\s+`?:sensitive"
    r"|two-arity|no metadata|not.*reg-machine|never.*reg-machine"
    r"|no top-level|accepts no",
    re.IGNORECASE,
)

# --- Rule 5: no `rf2-XXXX` internal bead ids in the user-facing leaves.
# The id shape is `rf2-` + alphanumerics (some carry a `.N` sub-task suffix,
# e.g. `rf2-d3fb7.1`). Word-boundary the prefix so it doesn't match inside a
# longer token. Scanned over a SEPARATE file set (BEADID_SCANNED_FILES below):
# SKILL.md + README.md + every references/*.md. The skill's own spec/ meta-docs
# are deliberately excluded (L7 permits bead ids there).
BEADID_RE = re.compile(r"\brf2-[a-z0-9]+(?:\.[0-9]+)?\b")

# --- Rule 6: unified reply-address / envelope contract drift (rf2-3fc89f.34).
# Three context-sensitive sub-checks. Each is deliberately narrow so legitimate
# INTERNAL `:rf/reply-to` descriptor mentions (the normalized target) still pass
# — the rules only fire on the retired teaching, not on the token itself.

# 6a — `:rf/reply-to` claimed as the PUBLIC / app-facing / authoring target key.
# The public authoring key is `:reply-to`; `:rf/reply-to` is the internal /
# normalized descriptor it lowers to. Fires on "<public-cue> [reply][target] key
# … `:rf/reply-to`" where NO backtick separates the key-claim from `:rf/reply-to`
# — so a correct "authoring key is `:reply-to` … internal `:rf/reply-to`
# descriptor" line (whose first backtick after "key" is `:reply-to`) does NOT
# match, while "public target key is `:rf/reply-to`" does.
REPLY_PUBLIC_KEY_RE = re.compile(
    r"(?:canonical\s+public|public|app-facing|app-authored|authoring|call-site)"
    r"\s+(?:reply[\s-]*)?(?:target\s+)?key[^`]*`:rf/reply-to`",
    re.IGNORECASE,
)

# 6b — HTTP `:on-success` / `:on-failure` described as RESHAPING the reply into a
# second, narrower payload dialect. They are split ROUTING sugar that receive the
# IDENTICAL canonical reply map. Fires on `reshap*` in a reply / HTTP-sugar
# context UNLESS the line DENIES it (not / never / no reshape) or asserts the
# IDENTICAL / same reply envelope.
RESHAPE_RE = re.compile(r"reshap\w*", re.IGNORECASE)
RESHAPE_CTX_RE = re.compile(
    r":on-success|:on-failure|reply map|reply payload|http payload|envelope",
    re.IGNORECASE,
)
RESHAPE_ALLOW_RE = re.compile(
    r"\bnot\b|n't|\bnever\b|\bno\s+reshap|identical|same\s+(?:canonical\s+)?reply",
    re.IGNORECASE,
)

# 6c — bare `:work/id` placed on the TRANSIENT reply map / payload. Its spelling
# there is `:rf.reply/work-id`; bare `:work/id` is the durable ledger /
# verification / abstract-attempt identity. Fires on a reply-map/payload context
# carrying bare `:work/id` UNLESS the line also spells `:rf.reply/work-id` or
# marks the id as the durable ledger / verification / abstract identity.
REPLYMAP_CTX_RE = re.compile(
    r"reply map|reply payload|http payload|reply envelope", re.IGNORECASE
)
BARE_WORKID_RE = re.compile(r"`:work/id`")
WORKID_ALLOW_RE = re.compile(
    r":rf\.reply/work-id|ledger|durable|verification|abstract", re.IGNORECASE
)


def _slurp(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def line_problems(line: str) -> list[str]:
    """Return drift labels for a single line (empty when clean)."""
    problems: list[str] = []

    if DEREGISTER_RE.search(line):
        problems.append(
            "DEREGISTER-LISTENER: use `unregister-listener!` — `deregister-"
            "listener!` is not the public trace-listener removal verb "
            "(spec/API.md, re_frame/trace.cljc)."
        )

    if BARE_HTTP_FX_RE.search(line) and not HTTP_ALLOW_RE.search(line):
        problems.append(
            "BARE-HTTP-MANAGED: the Spec 014 managed HTTP surface is "
            "`:rf.http/managed` — bare `:http` is app/user/implementation-"
            "specific. Don't describe the managed lifecycle as 'the `:http` "
            "fx' (mark bare `:http` user/app-level if you mention it)."
        )

    if DISPOSE_ADAPTER_RE.search(line) and not DISPOSE_ALLOW_RE.search(line):
        problems.append(
            "PUBLIC-DISPOSE-ADAPTER: the public teardown verb is "
            "`destroy-adapter!`; `:dispose-adapter!` is the adapter-spec map's "
            "internal lifecycle slot. Mark it as the map key / internal slot, "
            "or use `destroy-adapter!` for the public verb."
        )

    if (
        REG_MACHINE_RE.search(line)
        and SENS_LARGE_MAP_RE.search(line)
        and not MACHINE_EXCEPTION_RE.search(line)
    ):
        problems.append(
            "REG-MACHINE-MARKS: `reg-machine` is two-arity and accepts NO "
            "top-level `:sensitive` / `:large` keys — machine `:data` is the "
            "schema-first exception (`:data-schema` props / runtime path-list). "
            "Don't imply `reg-machine` takes the marking metadata map."
        )

    if REPLY_PUBLIC_KEY_RE.search(line):
        problems.append(
            "REPLY-PUBLIC-KEY: the app-facing reply-target authoring key is "
            "`:reply-to` (the unified spelling shared by HTTP / resources / "
            "mutations); `:rf/reply-to` is the internal / normalized descriptor "
            "it lowers to, NOT a public spelling. Don't teach `:rf/reply-to` as "
            "the public / app-facing target key (spec/Managed-Effects.md "
            "§The reply target)."
        )

    if (
        RESHAPE_RE.search(line)
        and RESHAPE_CTX_RE.search(line)
        and not RESHAPE_ALLOW_RE.search(line)
    ):
        problems.append(
            "REPLY-HTTP-RESHAPE: HTTP `:on-success` / `:on-failure` are SPLIT "
            "ROUTING sugar that receive the IDENTICAL canonical reply map (which "
            "event routes on success vs failure) — they do NOT reshape the reply "
            "into a second, narrower payload dialect. Don't describe HTTP reply "
            "sugar as reshaping the envelope (spec/Managed-Effects.md "
            "§The reply target / Spec 014)."
        )

    if (
        REPLYMAP_CTX_RE.search(line)
        and BARE_WORKID_RE.search(line)
        and not WORKID_ALLOW_RE.search(line)
    ):
        problems.append(
            "REPLY-WORKID-SPELLING: the TRANSIENT reply map spells the work "
            "identity `:rf.reply/work-id`; bare `:work/id` is the durable ledger "
            "/ verification / abstract-attempt identity. Don't put bare "
            "`:work/id` on the reply map (one fact, two spellings across the "
            "record↔reply boundary)."
        )

    return problems


def find_drift(files: list[Path]) -> tuple[list[str], int]:
    problems: list[str] = []
    lines_checked = 0
    for path in files:
        if not path.is_file():
            problems.append(
                f"SETUP: expected implementor-skill file missing: "
                f"{path.relative_to(REPO_ROOT)} — the guard's file list drifted "
                "from the skill layout; update SCANNED_FILES."
            )
            continue
        for lineno, line in enumerate(_slurp(path).splitlines(), start=1):
            lines_checked += 1
            for label in line_problems(line):
                rel = path.relative_to(REPO_ROOT)
                problems.append(f"{rel}:{lineno}: {label}\n    {line.strip()}")
    return problems, lines_checked


def find_beadid_drift(files: list[Path]) -> tuple[list[str], int]:
    """Rule 5 scan: no `rf2-XXXX` bead id in any user-facing leaf (L7).

    Returns (drift messages, lines scanned). A missing file is a SETUP error
    — except README.md / a references leaf glob can never go missing silently,
    so the SKILL.md anchor missing is the only hard SETUP signal here."""
    problems: list[str] = []
    lines_checked = 0
    if not (SKILL_DIR / "SKILL.md").is_file():
        problems.append(
            "SETUP: re-frame2-implementor/SKILL.md missing — the no-bead-ids "
            "scan anchor drifted from the skill layout."
        )
    for path in files:
        if not path.is_file():
            continue
        for lineno, line in enumerate(_slurp(path).splitlines(), start=1):
            lines_checked += 1
            for m in BEADID_RE.finditer(line):
                rel = path.relative_to(REPO_ROOT)
                problems.append(
                    f"{rel}:{lineno}: BEAD-ID-LEAK: `{m.group(0)}` is an "
                    "internal tracker id — user-facing leaves (SKILL.md / "
                    "README.md / references/*.md) carry NO `rf2-` ids (L7). "
                    "Replace with public, stable evidence: a spec section link, "
                    "a fixture name, an API entry, or a fully-qualified public "
                    "PR link. (bead ids stay only in the skill's spec/ "
                    f"meta-docs.)\n    {line.strip()}"
                )
    return problems, lines_checked


def run(*, verbose: bool, ci: bool) -> int:
    if not SKILL_DIR.is_dir():
        sys.stderr.write(
            f"error: re-frame2-implementor skill not found at {SKILL_DIR}\n"
        )
        return 2

    problems, lines_checked = find_drift(SCANNED_FILES)

    beadid_files = _beadid_scanned_files()
    beadid_problems, beadid_lines = find_beadid_drift(beadid_files)
    problems.extend(beadid_problems)

    if verbose:
        print(
            f"implementor partition/HTTP/API-name guard: scanned "
            f"{len(SCANNED_FILES)} files ({lines_checked} lines)."
        )
        print(
            f"implementor no-bead-ids guard: scanned {len(beadid_files)} "
            f"user-facing leaves ({beadid_lines} lines)."
        )

    if not problems:
        if verbose:
            print(
                "partition-drift: no deregister-listener!, bare-:http-managed, "
                "public-dispose-adapter!, reg-machine-marks, or bead-id drift "
                "found."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\npartition-drift: {len(problems)} drift issue(s) — the implementor "
        "skill is a control surface; align it to shipped EP-0001 partition / "
        "Spec 014 managed-HTTP / public-API reality, and keep internal bead ids "
        "out of the user-facing leaves (L7)."
    )
    return 1


# ---------------------------------------------------------------------------
# Self-test — exercises the line classifier against in-memory fixtures so the
# guard itself can't silently rot. Mirrors the --self-test convention in the
# sibling check_skill_*.py guards.
# ---------------------------------------------------------------------------

def _self_test() -> int:
    failures = 0

    def expect(line: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(line_problems(line))
        if got != dirty:
            print(
                f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got "
                f"{got} for: {line!r}"
            )
            failures += 1

    # FAIL fixtures — the exact rf2-whsb0c / rf2-6c59ob drift shapes.
    expect(
        "The trace stream — `register-listener!` / `deregister-listener!`, the rich emits.",
        dirty=True, label="A1 deregister-listener",
    )
    expect(
        "EP 014 implementation. The `:http` fx wraps a request lifecycle through a state machine.",
        dirty=True, label="A2 bare :http as managed fx",
    )
    expect(
        "boot wiring is the core's `install-adapter!` / `dispose-adapter!`.",
        dirty=True, label="A3 public dispose-adapter (no slot/map-key cue)",
    )
    expect(
        "The seven first-class marking sites accept `{:sensitive [paths] :large [paths]}` "
        "including reg-machine.",
        dirty=True, label="A4 reg-machine implies :sensitive/:large",
    )

    # PASS fixtures — the corrected wording must NOT flag.
    expect(
        "The trace stream — `register-listener!` / `unregister-listener!`, the rich emits.",
        dirty=False, label="B1 unregister-listener (correct)",
    )
    expect(
        "The Spec 014 surface is `:rf.http/managed`; bare `:http` is app/user/implementation-specific.",
        dirty=False, label="B2 :rf.http/managed + bare :http marked app-level",
    )
    expect(
        "Lower-level bare `:http` is NOT a reserved framework fx — it is app/user-specific.",
        dirty=False, label="B3 bare :http explicitly denied as managed",
    )
    expect(
        "`destroy-adapter!` calls the adapter-spec map's internal `:dispose-adapter!` slot.",
        dirty=False, label="B4 dispose-adapter marked as map slot",
    )
    expect(
        "One lifecycle slot: `:dispose-adapter!` — the adapter-spec map key the adapter implements.",
        dirty=False, label="B5 :dispose-adapter as map key",
    )
    expect(
        "`reg-machine` is two-arity and accepts NO `:sensitive` / `:large` metadata keys.",
        dirty=False, label="B6 reg-machine schema-first exception stated",
    )
    expect(
        "The six metadata-bearing sites accept `{:sensitive [paths] :large [paths]}`.",
        dirty=False, label="B7 six sites, no reg-machine on the line",
    )

    # Rule 6 — reply-address / envelope contract. FAIL fixtures reproduce the
    # exact rf2-3fc89f.34 retired teaching; PASS fixtures are the corrected prose
    # and the legitimate INTERNAL `:rf/reply-to` descriptor mentions.
    expect(
        "The canonical public target key is `:rf/reply-to` (short vector form, "
        "or the internal descriptor form).",
        dirty=True, label="E1 :rf/reply-to taught as the public target key",
    )
    expect(
        "For HTTP, the public `:on-success` / `:on-failure` payload is sugar "
        "reshaped from the internal envelope — the canonical reply map is internal.",
        dirty=True, label="E2 HTTP sugar as a reshaped envelope",
    )
    expect(
        "Do not expose `:status` / `:work/id` / `:completed-at` on the public "
        "HTTP reply payload.",
        dirty=True, label="E3 bare :work/id on the reply payload",
    )
    # PASS — corrected prose and legitimate internal-descriptor references.
    expect(
        "The app-facing authoring key is `:reply-to`; it normalizes to the one "
        "internal / normalized `:rf/reply-to` descriptor it lowers to.",
        dirty=False, label="F1 public :reply-to + internal :rf/reply-to descriptor",
    )
    expect(
        "All family sugar normalizes to the internal `:rf/reply-to` descriptor "
        "(a conformance surface, not an everyday app-facing spelling).",
        dirty=False, label="F2 legitimate internal :rf/reply-to mention",
    )
    expect(
        "`:on-success` and `:on-failure` receive the identical canonical reply "
        "map that `:reply-to` would; they do NOT reshape it into a second dialect.",
        dirty=False, label="F3 HTTP sugar denied-reshape + identical envelope",
    )
    expect(
        "The transient reply map spells the work identity `:rf.reply/work-id`; "
        "bare `:work/id` is the durable ledger / verification identity.",
        dirty=False, label="F4 reply map :rf.reply/work-id, ledger :work/id",
    )
    expect(
        "Ledger-backed work correlates by `:work/id` (the family owns the tuple head).",
        dirty=False, label="F5 bare :work/id in a ledger context (no reply-map)",
    )
    expect(
        "The route loader stores the normalized `:rf/reply-to` target in "
        "runtime-db and completes it via the shared substrate.",
        dirty=False, label="F6 internal :rf/reply-to storage, no public-key claim",
    )

    # Rule 5 — bead-id leak. line_problems() does NOT cover Rule 5 (it scans a
    # separate file set), so exercise BEADID_RE directly.
    def expect_beadid(line: str, *, leaked: bool, label: str) -> None:
        nonlocal failures
        got = bool(BEADID_RE.search(line))
        if got != leaked:
            print(
                f"SELF-TEST FAIL ({label}): expected bead-id leaked={leaked}, "
                f"got {got} for: {line!r}"
            )
            failures += 1

    # LEAK fixtures — the exact rf2-ij6ulc finding-2 shapes.
    expect_beadid(
        "a stray `:rf/runtime` root now HARD-ERRORS (shipped EP-0001 bead 9, rf2-tfepxu).",
        leaked=True, label="C1 plain bead id",
    )
    expect_beadid(
        "the post-v1 deferral withdrawn via rf2-mle6e / PR #2863; the runtime records history.",
        leaked=True, label="C2 bead id beside a PR ref",
    )
    expect_beadid(
        "the dual-partition recompute trigger is a SILENT regression (rf2-d3fb7.1).",
        leaked=True, label="C3 bead id with .N sub-task suffix",
    )

    # CLEAN fixtures — the corrected public-evidence wording must NOT flag.
    expect_beadid(
        "a stray `:rf/runtime` root now HARD-ERRORS (`:rf.error/legacy-runtime-root`).",
        leaked=False, label="D1 spec error keyword, no bead id",
    )
    expect_beadid(
        "the runtime records history per `spec/005-StateMachines.md` §History states.",
        leaked=False, label="D2 spec section link, no bead id",
    )
    expect_beadid(
        "backstopped by `effect-map-shape-bad-fx-entry.edn` and `effect-handler-bad-return.edn`.",
        leaked=False, label="D3 fixture names, no bead id",
    )
    expect_beadid(
        "a fully-qualified public PR link day8/re-frame2#2863 is fine for an external reader.",
        leaked=False, label="D4 public PR ref is not a bead id",
    )

    if failures:
        print(f"self-test: {failures} failure(s).")
        return 1
    print("self-test: all cases passed.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true", help="print summary")
    parser.add_argument(
        "--ci",
        action="store_true",
        help="GitHub-Actions ::error:: prefix (auto on under GITHUB_ACTIONS)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run built-in fixtures instead of scanning the repo",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()

    ci = args.ci or bool(os.environ.get("GITHUB_ACTIONS"))
    return run(verbose=args.verbose, ci=ci)


if __name__ == "__main__":
    raise SystemExit(main())
