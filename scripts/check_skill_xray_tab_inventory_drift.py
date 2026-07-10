#!/usr/bin/env python3
"""Structural drift gate: the `re-frame2-xray` skill's Dynamic tab inventory
vs the SHIPPED Xray L4-tab registry (`reg-l4-tab!` metadata).

The tour skill enumerates Xray's Dynamic tabs in several places — `SKILL.md`,
`README.md`, `references/panels.md`, `references/shared-components.md`, the
`evals/*` fixtures, and the `package.json` / plugin descriptions. Each carries
a user-visible **label**, a mnemonic, and an order. Those are the volatile
facts that drift fastest as the Xray UI moves.

The regression this gate exists for (rf2-3fc89f.41): the ninth Dynamic tab was
renamed in the runtime from **"Modules"** to **"Frames"** (its `reg-l4-tab!`
`:label` became `"Frames"`; `:module-view` stayed the *internal id*), but the
skill kept telling operators to open a "Modules" tab — including an
objectively-false `evals/README.md` "source of truth" example that claimed the
registration was `{:id :module-view :label "Modules" ...}`. Every existing
skill gate (eval-docs, eval-packaging, package-refs, mcp-drift, doc-slugs,
readme-links, JSON parse, `npm pack`) stayed green because none of them
compares the documented tab metadata to the runtime registry. This gate closes
exactly that gap: it makes the shipped registry the source of truth and fails
if the skill's documented / eval inventory diverges on `:id`, `:label`,
`:mnem`, or `:order`.

The runtime source of truth is the set of `panel-registry/reg-l4-tab!` calls
whose `:modes` include `:dynamic`, under
`tools/xray/src/day8/re_frame2_xray/panels/`, cross-checked against
`focus.cljc`'s `valid-panels` id set (which itself mirrors
`panel-registry/tab-ids-for-mode :dynamic`, guarded by an Xray-side build
test). Ordered by `:order`, that is today:

    Epoch · app-db · Views · Trace · Machine · Routes · Resources · Graph · Frames
    (mnemonics  e     a       v       t       m         r        s          g       u)

The gate is behaviour-neutral: it reads the runtime, it never edits it.

Checks (all against the runtime inventory parsed fresh each run):

  R0  REGISTRY / valid-panels agreement — the Dynamic `reg-l4-tab!` id set
      equals `focus.cljc`'s `valid-panels`. Catches a Dynamic tab added /
      removed in one place but not the other (the skill's cited authoritative
      count would then be wrong).

  A1  INLINE METADATA LITERALS — every `{:id :<kw> ...}` map literal in a skill
      doc that also carries `:label` / `:mnem` / `:order` must match the
      runtime for that id, field by field. This is what catches the false
      `:label "Modules"` claim; it also guards any future inline tuple in ANY
      skill file.

  A2  ORDERED LABEL SENTENCE — the runtime labels, joined in `:order` order with
      "·" separators, must appear (whitespace-insensitive) in each file that
      carries the canonical ordered inventory sentence (SKILL.md, README.md,
      references/panels.md, evals/evals.json). Catches a label rename or a
      reorder in the primary inventory prose (e.g. "… Graph · Modules").

  A3  MNEMONIC SEQUENCE — the runtime mnemonics, joined in `:order` order
      (space-separated, e.g. `e a v t m r s g u`), must appear in SKILL.md,
      references/panels.md, and evals/evals.json.

  A4  LABEL PRESENCE — every runtime Dynamic label appears at least once in
      SKILL.md (so a newly-added tab's user-visible name is actually taught).

  A5  EVAL NINTH-TAB LABEL — evals.json names the highest-`:order` tab by its
      shipped visible label (today "Frames"), so the answer-quality fixtures
      pin the currently-shipped label rather than a stale one.

Pure-Python-stdlib (no PyYAML / Node) to stay fast + CI-portable, mirroring the
sibling `scripts/check_skill_*.py` gates.

Exit code:
    0  no drift
    1  drift detected (printed; `::error::` under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_xray_tab_inventory_drift.py
    python scripts/check_skill_xray_tab_inventory_drift.py --verbose
    python scripts/check_skill_xray_tab_inventory_drift.py --ci
    python scripts/check_skill_xray_tab_inventory_drift.py --self-test

rf2-3fc89f.41.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
SKILL_DIR = REPO_ROOT / "skills" / "re-frame2-xray"
PANELS_DIR = REPO_ROOT / "tools" / "xray" / "src" / "day8" / "re_frame2_xray" / "panels"
FOCUS_CLJC = REPO_ROOT / "tools" / "xray" / "src" / "day8" / "re_frame2_xray" / "focus.cljc"

# Force UTF-8 on output streams — the inventory carries "·" / em-dash, and the
# default Windows console codec (cp1252) would crash on them.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover
        pass

# The skill docs the gate reads, keyed by a stable logical name.
SKILL_FILES = {
    "SKILL.md": SKILL_DIR / "SKILL.md",
    "README.md": SKILL_DIR / "README.md",
    "references/panels.md": SKILL_DIR / "references" / "panels.md",
    "references/chrome.md": SKILL_DIR / "references" / "chrome.md",
    "references/shared-components.md": SKILL_DIR / "references" / "shared-components.md",
    "evals/README.md": SKILL_DIR / "evals" / "README.md",
    "evals/evals.json": SKILL_DIR / "evals" / "evals.json",
}

# Files that must carry the canonical ordered inventory sentence (A2) and the
# mnemonic sequence (A3).
ORDERED_LABEL_FILES = ("SKILL.md", "README.md", "references/panels.md", "evals/evals.json")
MNEM_FILES = ("SKILL.md", "references/panels.md", "evals/evals.json")
LABEL_PRESENCE_FILE = "SKILL.md"
EVAL_NINTH_LABEL_FILE = "evals/evals.json"

_MIDDOT = "·"  # "·"


# ---------------------------------------------------------------------------
# Runtime parsing.
# ---------------------------------------------------------------------------


def _strip_clj_comments(text: str) -> str:
    """Drop Clojure line comments (`;` to end-of-line) so a comment like
    `;; -1 places Epoch leftmost (before Handler's :order 0).` cannot feed a
    spurious `:order 0` into the field extractor. Labels / mnems / ids in these
    maps carry no `;`, so a blunt line-level cut is safe for extraction.
    """
    return "\n".join(line.split(";", 1)[0] for line in text.splitlines())


def _match_brace_block(text: str, start: int) -> str | None:
    """Return the balanced `{...}` block beginning at/after `start` (inclusive
    of the braces), or None. Counts `{`/`}` — `#{...}` sets nest correctly
    because `#{` contributes one `{`.
    """
    open_idx = text.find("{", start)
    if open_idx == -1:
        return None
    depth = 0
    for i in range(open_idx, len(text)):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[open_idx : i + 1]
    return None


_ID_RE = re.compile(r":id\s+:([A-Za-z0-9?*!+._-]+)")
_LABEL_RE = re.compile(r':label\s+\\?"([^"\\]*)')
_MNEM_RE = re.compile(r':mnem\s+\\?"([^"\\]*)')
_ORDER_RE = re.compile(r":order\s+(-?\d+)")
_MODES_RE = re.compile(r":modes\s+#\{([^}]*)\}")


def _parse_tab_map(block: str) -> dict | None:
    """Parse one comment-stripped `reg-l4-tab!` map block into a dict with the
    fields present (`id`, `label`, `mnem`, `order`, `modes`). Returns None if
    there is no `:id`.
    """
    m_id = _ID_RE.search(block)
    if not m_id:
        return None
    out: dict = {"id": m_id.group(1)}
    if (m := _LABEL_RE.search(block)):
        out["label"] = m.group(1)
    if (m := _MNEM_RE.search(block)):
        out["mnem"] = m.group(1)
    if (m := _ORDER_RE.search(block)):
        out["order"] = int(m.group(1))
    if (m := _MODES_RE.search(block)):
        out["modes"] = {
            tok.strip().lstrip(":")
            for tok in m.group(1).split()
            if tok.strip()
        }
    return out


def parse_reg_l4_tabs(source: str) -> list[dict]:
    """Every `reg-l4-tab!` registration in one comment-stripped cljs source."""
    clean = _strip_clj_comments(source)
    tabs: list[dict] = []
    for m in re.finditer(r"reg-l4-tab!", clean):
        block = _match_brace_block(clean, m.end())
        if block is None:
            continue
        parsed = _parse_tab_map(block)
        if parsed is not None:
            tabs.append(parsed)
    return tabs


def dynamic_tabs_from_dir(panels_dir: Path) -> list[dict]:
    """The Dynamic (`:modes` ⊇ {:dynamic}) `reg-l4-tab!` inventory under the
    panels dir, sorted by `:order`.
    """
    tabs: list[dict] = []
    for cljs in sorted(panels_dir.rglob("*.cljs")):
        for tab in parse_reg_l4_tabs(cljs.read_text(encoding="utf-8")):
            if "dynamic" in tab.get("modes", set()):
                tab["_source"] = cljs.name
                tabs.append(tab)
    tabs.sort(key=lambda t: t.get("order", 0))
    return tabs


_VALID_PANELS_RE = re.compile(r"def\s+valid-panels\b.*?#\{([^}]*)\}", re.DOTALL)


def parse_valid_panels(focus_source: str) -> set[str]:
    """The `#{...}` id set from `focus.cljc`'s `valid-panels` def."""
    m = _VALID_PANELS_RE.search(focus_source)
    if not m:
        return set()
    return set(re.findall(r":([A-Za-z0-9?*!+._-]+)", m.group(1)))


# ---------------------------------------------------------------------------
# Skill-doc parsing.
# ---------------------------------------------------------------------------


def parse_inline_literals(text: str) -> list[dict]:
    """Every non-nested `{...}` map literal in a skill doc that carries a
    Clojure `:id :<kw>` plus at least one of `:label` / `:mnem` / `:order`.
    """
    out: list[dict] = []
    for m in re.finditer(r"\{[^{}]*\}", text):
        block = m.group(0)
        if ":id" not in block:
            continue
        parsed = _parse_tab_map(block)
        if parsed is None:
            continue
        if any(k in parsed for k in ("label", "mnem", "order")):
            out.append(parsed)
    return out


def _collapse_ws(text: str) -> str:
    return re.sub(r"\s+", " ", text)


# ---------------------------------------------------------------------------
# Core cross-check.
# ---------------------------------------------------------------------------


def check(
    runtime_tabs: list[dict],
    valid_panels: set[str],
    skill_files: dict[str, str],
) -> list[str]:
    """Return a list of drift findings (empty == clean).

    `runtime_tabs` is the ordered Dynamic inventory; `skill_files` maps the
    logical file name to its text.
    """
    findings: list[str] = []

    if not runtime_tabs:
        return ["R0 runtime: no Dynamic reg-l4-tab! registrations parsed — "
                "cannot verify the skill inventory (parser or source moved)."]

    by_id = {t["id"]: t for t in runtime_tabs}

    # R0 — registry vs valid-panels id agreement.
    runtime_ids = set(by_id)
    if valid_panels and runtime_ids != valid_panels:
        for missing in sorted(runtime_ids - valid_panels):
            findings.append(
                f"R0 valid-panels: Dynamic tab :{missing} is registered via "
                f"reg-l4-tab! but is absent from focus.cljc's valid-panels set."
            )
        for extra in sorted(valid_panels - runtime_ids):
            findings.append(
                f"R0 valid-panels: focus.cljc's valid-panels lists :{extra} but "
                f"no Dynamic reg-l4-tab! registers it."
            )

    # A1 — inline metadata literals across every skill doc.
    for fname, text in skill_files.items():
        for lit in parse_inline_literals(text):
            lid = lit["id"]
            rt = by_id.get(lid)
            if rt is None:
                findings.append(
                    f"A1 inline-literal [{fname}]: `{{:id :{lid} …}}` names a tab "
                    f"the runtime has no Dynamic reg-l4-tab! for."
                )
                continue
            for field in ("label", "mnem", "order"):
                if field in lit and lit[field] != rt.get(field):
                    findings.append(
                        f"A1 inline-literal [{fname}]: :{lid} :{field} documented "
                        f"as {lit[field]!r} but the runtime registers "
                        f"{rt.get(field)!r}."
                    )

    # A2 — ordered label sentence.
    ordered_labels = [t.get("label", f":{t['id']}") for t in runtime_tabs]
    label_sentence = f" {_MIDDOT} ".join(ordered_labels)
    for fname in ORDERED_LABEL_FILES:
        text = skill_files.get(fname, "")
        if label_sentence not in _collapse_ws(text):
            findings.append(
                f"A2 ordered-label [{fname}]: the canonical ordered inventory "
                f"sentence '{label_sentence}' (runtime :order order) is absent "
                f"— a label/order drifted."
            )

    # A3 — mnemonic sequence.
    mnem_seq = " ".join(t.get("mnem", "?") for t in runtime_tabs)
    for fname in MNEM_FILES:
        text = skill_files.get(fname, "")
        if mnem_seq not in _collapse_ws(text):
            findings.append(
                f"A3 mnemonic-seq [{fname}]: the mnemonic sequence '{mnem_seq}' "
                f"(runtime :order order) is absent."
            )

    # A4 — every runtime label present in SKILL.md.
    skill_body = skill_files.get(LABEL_PRESENCE_FILE, "")
    for t in runtime_tabs:
        label = t.get("label")
        if label and label not in skill_body:
            findings.append(
                f"A4 label-presence [{LABEL_PRESENCE_FILE}]: the shipped tab "
                f"label {label!r} (:{t['id']}) does not appear."
            )

    # A5 — evals name the highest-:order tab by its shipped visible label.
    ninth = max(runtime_tabs, key=lambda t: t.get("order", 0))
    ninth_label = ninth.get("label")
    eval_text = skill_files.get(EVAL_NINTH_LABEL_FILE, "")
    if ninth_label and ninth_label not in eval_text:
        findings.append(
            f"A5 eval-label [{EVAL_NINTH_LABEL_FILE}]: the shipped label for the "
            f"highest-:order tab (:{ninth['id']}) is {ninth_label!r} but the eval "
            f"fixtures never name it — the answer-quality layer would pass a "
            f"stale label."
        )

    return findings


# ---------------------------------------------------------------------------
# Live-file entry point.
# ---------------------------------------------------------------------------


def run_live() -> list[str]:
    runtime_tabs = dynamic_tabs_from_dir(PANELS_DIR)
    valid_panels = parse_valid_panels(FOCUS_CLJC.read_text(encoding="utf-8"))
    skill_files = {name: p.read_text(encoding="utf-8") for name, p in SKILL_FILES.items()}
    return check(runtime_tabs, valid_panels, skill_files)


# ---------------------------------------------------------------------------
# Self-test.
# ---------------------------------------------------------------------------


def _run_self_test() -> int:
    failures = 0

    # A representative shipped runtime slice (id/label/mnem/order/modes) plus a
    # Static tab that must be ignored by the Dynamic filter.
    runtime_src = """
    (panel-registry/reg-l4-tab!
      {:id :epoch :label "Epoch" :mnem "e" :modes #{:dynamic}
       ;; -1 places Epoch leftmost (before Handler's :order 0).
       :order -1 :panel Panel})
    (panel-registry/reg-l4-tab!
      {:id :machines
       ;; singular label; internal id stays :machines
       :label "Machine" :mnem "m" :modes #{:dynamic} :order 4 :panel Panel})
    (panel-registry/reg-l4-tab!
      {:id :module-view :label "Frames" :mnem "u" :modes #{:dynamic} :order 9 :panel Panel})
    (panel-registry/reg-l4-tab!
      {:id :machines :label "Machines" :mnem "m" :modes #{:static} :order 0 :panel Panel})
    """
    parsed = [t for t in parse_reg_l4_tabs(runtime_src) if "dynamic" in t.get("modes", set())]
    parsed.sort(key=lambda t: t.get("order", 0))

    def expect(label, cond):
        nonlocal failures
        if not cond:
            failures += 1
            print(f"SELF-TEST FAIL [parse]: {label}")

    expect("3 dynamic tabs", len(parsed) == 3)
    expect("epoch order -1 (comment :order 0 ignored)",
           any(t["id"] == "epoch" and t.get("order") == -1 for t in parsed))
    expect("machine label singular",
           any(t["id"] == "machines" and t.get("label") == "Machine" for t in parsed))
    expect("static machines excluded",
           all(not (t["id"] == "machines" and t.get("label") == "Machines") for t in parsed))
    expect("valid-panels parse",
           parse_valid_panels("(def valid-panels \"doc\" #{:epoch :machines :module-view})")
           == {"epoch", "machines", "module-view"})

    # Synthetic runtime inventory for the check() fixtures.
    rt = [
        {"id": "epoch", "label": "Epoch", "mnem": "e", "order": -1, "modes": {"dynamic"}},
        {"id": "machines", "label": "Machine", "mnem": "m", "order": 4, "modes": {"dynamic"}},
        {"id": "module-view", "label": "Frames", "mnem": "u", "order": 9, "modes": {"dynamic"}},
    ]
    vp = {"epoch", "machines", "module-view"}
    ordered = f"Epoch {_MIDDOT} Machine {_MIDDOT} Frames"
    mnem = "e m u"

    def files(**overrides) -> dict[str, str]:
        base = {
            "SKILL.md": f"Tabs: {ordered} (mnemonics {mnem}). app-db etc.",
            "README.md": f"Inventory {ordered}.",
            "references/panels.md": f"{ordered}\nmnemonics {mnem}",
            "references/chrome.md": "chrome",
            "references/shared-components.md": "shared",
            "evals/README.md": "evals readme",
            "evals/evals.json": f'"list {ordered} (mnemonics {mnem})"',
        }
        base.update(overrides)
        return base

    cases: list[tuple[str, list[dict], set, dict, bool]] = [
        ("clean", rt, vp, files(), True),
        # A1: an inline literal claims the retired label.
        ("inline stale label", rt, vp,
         files(**{"evals/README.md": 'e.g. {:id :module-view :label "Modules" :mnem "u" :order 9}.'}),
         False),
        # A1: an inline literal with the correct label passes.
        ("inline correct label", rt, vp,
         files(**{"evals/README.md": 'e.g. {:id :module-view :label "Frames" :mnem "u" :order 9}.'}),
         True),
        # A1: wrong order in an inline literal.
        ("inline wrong order", rt, vp,
         files(**{"SKILL.md": f"Tabs: {ordered} (mnemonics {mnem}). app-db. "
                              '{:id :module-view :label "Frames" :mnem "u" :order 8}'}),
         False),
        # A2: ordered sentence carries the stale label.
        ("ordered stale label", rt, vp,
         files(**{"README.md": f"Inventory Epoch {_MIDDOT} Machine {_MIDDOT} Modules."}),
         False),
        # A2: reordered inventory.
        ("ordered reorder", rt, vp,
         files(**{"SKILL.md": f"Tabs: Epoch {_MIDDOT} Frames {_MIDDOT} Machine (mnemonics {mnem})."}),
         False),
        # A3: mnemonic sequence drift.
        ("mnem drift", rt, vp,
         files(**{"references/panels.md": f"{ordered}\nmnemonics e u m"}),
         False),
        # A4: a shipped label missing from SKILL.md.
        ("label missing in SKILL", rt, vp,
         files(**{"SKILL.md": f"Tabs: Epoch {_MIDDOT} Machine {_MIDDOT} Frames (mnemonics {mnem})."
                              .replace("Frames", "Frms")}),
         False),
        # A5: evals never name the ninth label.
        ("eval ninth label absent", rt, vp,
         files(**{"evals/evals.json": f'"list Epoch {_MIDDOT} Machine {_MIDDOT} Modules (mnemonics {mnem})"'}),
         False),
        # R0: valid-panels omits a registered tab.
        ("valid-panels mismatch", rt, {"epoch", "machines"}, files(), False),
    ]

    for label, rtabs, vpanels, fmap, want_clean in cases:
        result = check(rtabs, vpanels, fmap)
        is_clean = not result
        if is_clean != want_clean:
            failures += 1
            print(
                f"SELF-TEST FAIL [check]: {label!r} expected "
                f"{'clean' if want_clean else 'drift'}, got "
                f"{'clean' if is_clean else result}"
            )

    if failures:
        print(f"\n{failures} self-test failure(s).")
        return 1
    print("self-test: all fixtures pass.")
    return 0


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------


def _is_ci() -> bool:
    return os.environ.get("GITHUB_ACTIONS") == "true"


def main(argv: Iterable[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify the re-frame2-xray skill's Dynamic tab inventory "
            "(id/label/mnem/order) matches the shipped Xray reg-l4-tab! "
            "registry (rf2-3fc89f.41)."
        ),
    )
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument("--ci", action="store_true",
                        help="Emit GitHub-Actions ::error:: lines (auto-on under GITHUB_ACTIONS).")
    parser.add_argument("--self-test", action="store_true",
                        help="Run built-in fixtures and exit.")
    args = parser.parse_args(list(argv))

    if args.self_test:
        return _run_self_test()

    ci = args.ci or _is_ci()

    for p in (PANELS_DIR, FOCUS_CLJC, *SKILL_FILES.values()):
        if not p.exists():
            msg = f"required path not found: {p}"
            print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
            return 2

    try:
        findings = run_live()
    except (OSError, ValueError) as e:  # pragma: no cover
        msg = f"failed to run inventory drift check: {e}"
        print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
        return 2

    if findings:
        print(
            f"Xray tab-inventory drift detected ({len(findings)} "
            f"finding{'' if len(findings) == 1 else 's'}):",
            file=sys.stderr,
        )
        for f in findings:
            print(f"::error::{f}" if ci else f"ERROR: {f}", file=sys.stderr)
        print(
            "\nFix: update skills/re-frame2-xray/** (SKILL.md, README.md, "
            "references/**, evals/**) so the documented Dynamic tab "
            "id/label/mnem/order matches the shipped reg-l4-tab! registry under "
            "tools/xray/src/day8/re_frame2_xray/panels/. (rf2-3fc89f.41)",
            file=sys.stderr,
        )
        return 1

    if args.verbose:
        tabs = dynamic_tabs_from_dir(PANELS_DIR)
        print(f"No Xray tab-inventory drift ({len(tabs)} Dynamic tabs verified).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
