#!/usr/bin/env python3
"""The final architecture and retained-mechanism census, made executable
(rf2-hic-087).

`docs/design/hicasso/product/architecture-census.md` publishes three claims
about the finished Hicasso package:

  1. no REJECTED MECHANISM is present — no ViewCell-class per-boundary object
     graph, no second emitter, no compiled-hiccup mode, no per-boundary
     callback-cell table;
  2. every RETAINED tool or diagnostic surface names a real daily consumer;
  3. the `rf2-hic-017` mutable-global roster still closes against the final
     tree.

A census is a claim about the tree at a moment, and the tree moves.  This gate
is what stops the page from becoming a sentence that was once true: it re-runs
the three populations against the working tree and refuses when the tree and
the page disagree, in either direction.

WHY THE ARMS ARE STRUCTURAL AND NOT KEYWORD SCANS.  Every one of the four
rejected mechanisms needs a *thing* in the tree, and each thing has a shape no
amount of rewording can hide:

  * a SECOND EMITTER must either call a renderer that is not React's, or reach
    one through a `:require`.  Both are enumerable.
  * a COMPILED-HICCUP MODE must have a build-time pass, which in ClojureScript
    means a `defmacro`.  The macro roster is frozen at six names, so a seventh
    is the tripwire and nobody has to agree on what "compiler" means.
  * a VIEWCELL-CLASS GRAPH and a PER-BOUNDARY CALLBACK-CELL TABLE both need a
    RETAINED STRUCTURE that today does not exist.  A new module-level mutable
    owner is what that looks like on arrival, so arm 3 catches both — which is
    why the mutable-global re-sweep is not a separate concern from the
    mechanism census but the same tripwire read a second way.

That third point is the design of this gate.  `resource-demand-criteria.md`'s
C5 pre-registers the recogniser — *any retained structure that holds one entry
per read, or per read-and-boundary pair, with a lifecycle of its own* — and
pre-registration is worth nothing if the recogniser is only ever applied by
somebody who remembers to.  Nothing here decides whether a new owner is a
ViewCell; it decides that a new owner has to be looked at.

WHAT THIS GATE DOES NOT DO.  It does not read prose, it does not grade a
justification, and it does not decide whether a consumer is a good one.  A row
whose Consumer cell is filled with a lie passes.  What it enforces is that no
surface exists with no row at all, which is the failure mode a census actually
has: not a wrong answer, an unasked question.

Usage:

    python scripts/check_architecture_census.py            # the three arms
    python scripts/check_architecture_census.py --self-test # the arms bite
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "implementation" / "hicasso" / "src"
PAGE = REPO / "docs" / "design" / "hicasso" / "product" / "architecture-census.md"
GLOBALS_PAGE = REPO / "docs" / "design" / "hicasso" / "product" / "globals.md"
HIC_SCRIPTS = REPO / "implementation" / "hicasso" / "scripts"
PACKAGE_JSON = REPO / "implementation" / "package.json"

# ---------------------------------------------------------------------------
# Arm 1 — the rejected mechanisms
# ---------------------------------------------------------------------------

# The six macros the package publishes.  `h/defview`, `h/event` and `h/defhost`
# on the ordinary facade; `n/props`, `n/$` and `n/defcomponent` on the native
# escape.  Every one is `defn`-class sugar with a function fallback, which is
# what the charter's *No analyzer* constraint permits; none classifies, lowers
# or refuses a body form.  A SEVENTH NAME IS THE TRIPWIRE — not because a
# seventh macro is necessarily a compiler, but because a compiled-hiccup mode
# cannot arrive without one, so the roster is where the question gets asked.
MACRO_ROSTER = {"defview", "event", "defhost", "props", "$", "defcomponent"}

# A renderer this package is allowed to call.  React's own, in its two halves.
# `re-frame.hicasso.server` runs THIS runtime under `react-dom/server`; the
# whole no-second-emitter claim reduces to the alias in front of these calls.
RENDER_CALL = re.compile(
    r"\((?P<alias>[a-zA-Z][a-zA-Z0-9._-]*)/"
    r"(?P<fn>renderToString|renderToStaticMarkup|renderToPipeableStream"
    r"|renderToReadableStream|createRoot|hydrateRoot)\b"
)
REACT_DOM_ALIAS = re.compile(r'\["react-dom(/[a-z]+)?" :as ([a-zA-Z][a-zA-Z0-9._-]*)\]')

# A namespace that emits a view tree by a route other than React.  Requiring
# one from the runtime is a second emitter reachable from the product path,
# whatever the calling code then does with it.
FOREIGN_EMITTER = re.compile(
    r"\[(re-frame\.ssr\.emit|re-frame\.ssr\.head|re-frame\.ui\.|re-frame\.freehand\.)"
)

# ---------------------------------------------------------------------------
# Arm 3 — the mutable-global re-sweep
# ---------------------------------------------------------------------------

# The seven arms.  C1-C6 are `globals.md`'s own, transcribed; C7 is this
# bead's addition and the reason it exists is recorded on the page: the six
# recorded arms regenerate only twenty of the twenty-one rows they produced,
# because a `defonce` whose init is neither a mutable constructor nor a `#js`
# literal nor a dynamic var is invisible to all six.
CENSUS_ARMS = {
    "C1": re.compile(
        r"\((atom|volatile!|js/Map\.|js/WeakMap\.|js/Set\.|empty-cache|react/createContext)[ )]"
    ),
    "C2": re.compile(r"^\s{0,7}#js [\{\[]"),
    "C5": re.compile(r":dynamic"),
    "C7": re.compile(r"^\(defonce\b"),
}

# Hits inside a `defn` are excluded STRUCTURALLY rather than by a hand-kept
# list, because that is what they have in common: `codec`'s per-owner
# `js/Map.`, `collector`'s per-call observation-opts atom, `intent`'s `armed?`
# volatile, `roots`'s per-root adoption window and the two hook dependency
# arrays are all allocated by a function, one per call, and none of them is a
# module-level owner.  `globals.md` names all six by hand under *What the
# searches return that is not an owner*; attributing every hit to its
# ENCLOSING TOP-LEVEL FORM makes the same cut without a list to maintain, and
# `mount.cljs`'s `!root` — the seventh, and the one that got its own section —
# falls out with the docstrings before the arms ever run.


def git_ls(pathspec: str) -> list[Path]:
    out = subprocess.run(
        ["git", "-C", str(REPO), "ls-files", pathspec],
        capture_output=True, text=True, check=True,
    ).stdout.split()
    return [REPO / p for p in out]


def strip_docstrings(text: str) -> str:
    """Blank out string literals, keeping line structure.

    Every arm here is about CODE.  Hicasso's sources carry very long
    docstrings that quote code — the `!root` example, the retired
    `counter`/`watchers` writers, `renderToString` named in prose forty times
    — and an arm that reads them measures the commentary rather than the
    tree.  `globals.md` learned this the expensive way and names four such
    hits by hand; this does it once, structurally.
    """
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "\\" and i + 1 < n:
            out.append("  ")
            i += 2
        elif c == ";":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == '"':
            j, i = i, i + 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i = min(i + 1, n)
            out.append("".join(ch if ch == "\n" else " " for ch in text[j:i]))
        else:
            out.append(c)
            i += 1
    return "".join(out)


def sources() -> list[tuple[str, str, str]]:
    """`(short-path, raw, code-only)` for every tracked Hicasso source."""
    rows = []
    for p in git_ls("implementation/hicasso/src"):
        raw = p.read_text(encoding="utf-8")
        short = p.relative_to(SRC / "re_frame").as_posix()
        rows.append((short, raw, strip_docstrings(raw)))
    return rows


def declaring_ns(short: str) -> str:
    """The declaring namespace of a source, spelled as `globals.md` spells it.

    In ClojureScript a namespace IS its path, so the path is the declaration:
    no parser is required, and there is no way for the spelling in an `ns`
    form to drift from the file the compiler loaded it out of.  `sources()`
    hands out paths relative to `re_frame/` and the rosters abbreviate away
    the shared `re-frame.hicasso.` prefix, so `hicasso/impl/collector.cljs` is
    `impl.collector` and the facade `hicasso.cljc` is `hicasso`.  Underscores
    become hyphens, which is the compiler's own munging read backwards
    (`presence_react.cljs` declares `impl.presence-react`).

    This is what makes an owner *identity* comparable, and a bare `def` name
    is not one.  Both `evidence.cljs` and `impl/evidence.cljs` exist in this
    tree, as do `overlay.cljs` and `impl/overlay.cljs`; a name is an address
    only once its namespace is in front of it.  Matching bare names let a new
    owner inherit an unrelated roster row and exit green — the fail-open the
    merged-PR audit of #8258 reproduced against this gate.
    """
    parts = re.sub(r"\.clj[sc]$", "", short).split("/")
    if parts[0] == "hicasso":
        parts = parts[1:] or ["hicasso"]
    return ".".join(parts).replace("_", "-")


# ---------------------------------------------------------------------------
# The page's own tables
# ---------------------------------------------------------------------------

def table_after(marker: str, text: str) -> list[list[str]]:
    """The rows of the markdown table that follows an HTML-comment marker."""
    at = text.find(marker)
    if at < 0:
        return []
    rows = []
    started = False
    for line in text[at + len(marker):].splitlines():
        s = line.strip()
        if not s.startswith("|"):
            if started:
                break
            continue
        started = True
        cells = [c.strip() for c in s.strip("|").split("|")]
        if all(set(c) <= set("-: ") for c in cells):
            continue
        rows.append(cells)
    return rows[1:] if rows else []


def rostered_surfaces(text: str) -> dict[str, str]:
    """Surface name -> its Consumer cell, from the retained-tooling tables."""
    out = {}
    for row in table_after("<!-- census:tooling -->", text):
        if len(row) < 3:
            continue
        for name in re.findall(r"`([^`]+)`", row[0]):
            out[name] = row[2]
    return out


# ---------------------------------------------------------------------------
# The arms
# ---------------------------------------------------------------------------

def top_level_defs(code: str):
    """`(name, body)` for every top-level `def`/`defonce` form.

    Line-at-a-time attribution cannot do this job: `error.cljc` writes
    `(defonce ^:private !sources` on one line and `(atom {})` three lines
    later, so the arm that finds the constructor is looking at a line that
    carries no name.  Reading whole forms also retires the exclusion list —
    an allocation inside a `defn` has no enclosing `def` and is simply not a
    candidate.
    """
    i, n = 0, len(code)
    while i < n:
        if code[i] == "(" and (i == 0 or code[i - 1] == "\n"):
            depth, j = 0, i
            while j < n:
                if code[j] == "(":
                    depth += 1
                elif code[j] == ")":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            form = code[i:j + 1]
            m = re.match(r"\((?:defonce|def)\s+(?:\^[^\s]+\s+)*([!a-zA-Z*][^\s\[(]*)", form)
            if m:
                yield m.group(1), form
            i = j + 1
        else:
            i += 1


def arm_mechanism(srcs) -> list[str]:
    findings = []
    macros = set()
    for short, raw, code in srcs:
        # From the RAW text: shadow-cljs spells an npm require as a STRING
        # literal, `["react-dom/client" :as react-dom-client]`, and the
        # docstring stripper has already blanked it out of `code`.
        aliases = set(m.group(2) for m in REACT_DOM_ALIAS.finditer(raw))
        for m in re.finditer(r"\(defmacro\s+([^\s\[]+)", code):
            macros.add(m.group(1))
        for m in RENDER_CALL.finditer(code):
            if m.group("alias") not in aliases:
                findings.append(
                    f"{short}: `{m.group('alias')}/{m.group('fn')}` is a render call on an "
                    f"alias that is not a `react-dom` require — a renderer that is not "
                    f"React's is a SECOND EMITTER"
                )
        for m in FOREIGN_EMITTER.finditer(code):
            findings.append(
                f"{short}: requires `{m.group(1)}…` — a namespace that emits a view tree "
                f"by a route other than React"
            )
    extra = macros - MACRO_ROSTER
    missing = MACRO_ROSTER - macros
    if extra:
        findings.append(
            f"macro roster: {sorted(extra)} is not on the frozen roster. A build-time "
            f"pass over body forms is a COMPILED-HICCUP MODE; if this macro is `defn`-class "
            f"sugar, add it to MACRO_ROSTER and give it a row on the census page"
        )
    if missing:
        findings.append(f"macro roster: {sorted(missing)} has disappeared from the tree")
    return findings


def arm_tooling(page: str) -> list[str]:
    findings = []
    rostered = rostered_surfaces(page)
    if not rostered:
        return ["retained-tooling: no `<!-- census:tooling -->` table found on the page"]

    population: list[tuple[str, str]] = []
    for p in sorted(HIC_SCRIPTS.glob("*")):
        if p.is_file() and p.name != "README.md":
            population.append((f"implementation/hicasso/scripts/{p.name}", "checker"))
    scripts = json.loads(PACKAGE_JSON.read_text(encoding="utf-8"))["scripts"]
    for name in sorted(scripts):
        if "hicasso" in name.lower():
            population.append((name, "npm script"))
    for ns in ("re-frame.hicasso.evidence", "re-frame.hicasso.tool"):
        population.append((ns, "tool namespace"))

    for name, kind in population:
        cell = rostered.get(name)
        if cell is None:
            findings.append(
                f"retained tooling: the {kind} `{name}` has no row on the census page. "
                f"Every retained tool or diagnostic surface names its real daily consumer, "
                f"or is filed for removal"
            )
        elif not cell or cell in {"—", "-"}:
            findings.append(
                f"retained tooling: `{name}`'s Consumer cell is empty. Name the consumer, "
                f"or file it for removal and say so in the cell"
            )
    return findings


def globals_roster() -> set[str]:
    """Every owner IDENTITY `globals.md` publishes, from both of its rosters.

    Both, and not just the mutable one, because the arms cannot tell them
    apart: `adoption-context` is a `defonce` of a React context and sits on
    the *identities* roster, and C1 and C7 both find it.

    The identity is `impl.<ns>/<name>`, exactly as the page spells it in its
    first column, and the namespace half is kept rather than discarded — see
    `declaring_ns`.  A roster of bare names is a roster of the wrong thing.
    """
    text = GLOBALS_PAGE.read_text(encoding="utf-8")
    names = set()
    for line in text.splitlines():
        s = line.strip()
        if not s.startswith("|"):
            continue
        first = s.strip("|").split("|")[0].strip()
        m = re.fullmatch(r"`(impl\.[a-z-]+)/([^`]+)`", first)
        if m:
            names.add(f"{m.group(1)}/{m.group(2)}")
    return names


def open_corrections(page: str) -> set[str]:
    """Owners named in the census page's OPEN CORRECTIONS table.

    A finding filed against `globals.md` cannot be fixed by the PR that finds
    it — `correction-ledger.md`'s protocol is explicit that a corrective PR is
    a separate change, and that a row closes on a re-run rather than on a
    merge.  So between filing and closing there is an interval in which a real
    owner has no roster row, and a gate with no way to say that would have to
    be red for the whole interval, which is the state in which a gate gets
    disabled.

    This is NOT an allowlist.  An entry costs a published row naming the
    finding and its bead, it is visible to any reader of the page, and it
    expires by construction: when the corrective PR adds the roster row, this
    row is removed with it and the arm goes back to enforcing the plain rule.
    A new owner nobody has filed anything about still reds.

    Identities here are `impl.<ns>/<name>` for the same reason they are on the
    roster: a filed finding about one namespace's owner excuses that owner and
    not a same-named one somewhere else.
    """
    names = set()
    for row in table_after("<!-- census:open-corrections -->", page):
        for cell in row:
            for m in re.finditer(r"`(impl\.[a-z-]+)/([^`]+)`", cell):
                names.add(f"{m.group(1)}/{m.group(2)}")
    return names


def arm_globals(srcs, page: str = "") -> list[str]:
    """Every module-level owner the arms find must have a row in `globals.md`.

    ONE DIRECTION, deliberately.  The reverse — every rostered row is found by
    some arm — is the check that produced this bead's own correction, and it
    cannot be automated honestly: five rows on the identities roster are plain
    `def`s of React classes and components, which no textual arm distinguishes
    from any other `def`.  Enforcing it here would mean either a sixth of the
    roster permanently red or an allowlist that fails open.  The direction
    that IS enforced is the one that matters for *"later work introduced no
    unjustified global"*: a new owner arrives with no row, and reds.

    FULLY QUALIFIED END TO END.  What is found, what is rostered and what is
    reported are all `impl.<ns>/<name>`.  Keying by the bare `def` name — which
    is what this arm did until the merged-PR audit of #8258 caught it — let a
    new owner in ANY namespace inherit the row of a same-named owner in another
    one and exit green; a planted `impl.planted/!cells` was absolved by
    `impl.collector/!cells` and returned no finding at all.  The self-test's
    own positive control missed it because it only ever planted a name nothing
    else owned, which is a control that cannot tell "the gate works" from "the
    gate matched the wrong row".
    """
    found: dict[str, str] = {}
    for short, _raw, code in srcs:
        ns = declaring_ns(short)
        for name, form in top_level_defs(code):
            for arm, rx in CENSUS_ARMS.items():
                if any(rx.search(ln) for ln in form.splitlines()):
                    found.setdefault(f"{ns}/{name}", f"{short} ({arm})")
                    break
    known = globals_roster() | open_corrections(page)
    return [
        f"mutable-global sweep: `{owner}` in {site} is a module-level owner with no row "
        f"in `globals.md`. Scope it, justify it in place — or, if it is a retained "
        f"per-read or per-read-and-boundary structure, it is the VIEWCELL-CLASS GRAPH "
        f"and the PER-BOUNDARY CALLBACK-CELL TABLE the kill rules refuse"
        for owner, site in sorted(found.items())
        if owner not in known
    ]


# ---------------------------------------------------------------------------

def run() -> int:
    srcs = sources()
    page = PAGE.read_text(encoding="utf-8") if PAGE.exists() else ""
    if not page:
        print(f"architecture-census: {PAGE} is missing", file=sys.stderr)
        return 1
    arms = [
        ("mechanism", arm_mechanism(srcs)),
        ("retained-tooling", arm_tooling(page)),
        ("mutable-globals", arm_globals(srcs, page)),
    ]
    bad = 0
    for name, findings in arms:
        for f in findings:
            print(f"architecture-census [{name}]: {f}", file=sys.stderr)
        bad += len(findings)
    if bad:
        print(f"\narchitecture-census: {bad} finding(s).", file=sys.stderr)
        return 1
    print(
        f"architecture-census: {len(srcs)} sources, "
        f"{len(MACRO_ROSTER)} rostered macros, {len(globals_roster())} rostered globals — "
        f"three arms, 0 findings."
    )
    return 0


def self_test() -> int:
    """Every arm, shown to bite.

    A census whose arms are never made to fail is an assertion with a
    script-shaped decoration on it.  Each case below is the smallest edit that
    would land the mechanism the arm refuses.

    A CONTROL HAS TO DISCRIMINATE.  Every planted source below is spelled the
    way `sources()` spells a real one, because `declaring_ns` now reads the
    path and a seed on a path shape that cannot occur tests nothing.  And the
    duplicate-owner pair at the end is here because this suite passed a gate
    that was matching the wrong roster row: it planted only names nothing else
    owned, so a green told it the arm bit when the arm had merely failed to
    find anything.  That pair asserts in BOTH directions — the duplicate reds,
    and the legitimately rostered original stays accepted in the same run.  A
    control that reds on both would be a different bug wearing this one's face.
    """
    ok = True

    def check(label, cond):
        nonlocal ok
        print(f"  {'ok  ' if cond else 'FAIL'}  {label}")
        ok = ok and cond

    real = sources()

    PLANTED = "hicasso/impl/planted.cljs"

    seeded = list(real) + [(PLANTED, "", '(defmacro deftemplate [& body])\n')]
    check("a seventh macro is a compiled-hiccup tripwire",
          any("macro roster" in f for f in arm_mechanism(seeded)))

    seeded = list(real) + [
        (PLANTED, "", '(ns x (:require [own-renderer :as r]))\n(r/renderToString e)\n')]
    check("a render call on a non-react-dom alias is a second emitter",
          any("SECOND EMITTER" in f for f in arm_mechanism(seeded)))

    seeded = list(real) + [(PLANTED, "", '(ns x (:require [re-frame.ssr.emit :as e]))\n')]
    check("requiring a foreign emitter namespace is a finding",
          any("view tree" in f for f in arm_mechanism(seeded)))

    seeded = list(real) + [
        (PLANTED, "", '(defonce !callback-cells (atom {}))\n')]
    check("a new module-level owner is a ViewCell/callback-cell tripwire",
          any("`impl.planted/!callback-cells`" in f for f in arm_globals(seeded)))
    check("an open correction is not an allowlist for anything else",
          any("`impl.planted/!callback-cells`" in f
              for f in arm_globals(seeded, PAGE.read_text(encoding="utf-8"))))

    # The duplicate-owner pair.  `impl.collector/!cells` is rostered; a second
    # `!cells` in another namespace is a different owner and has no row.
    seeded = list(real) + [(PLANTED, "", '(defonce !cells (atom {}))\n')]
    findings = arm_globals(seeded, PAGE.read_text(encoding="utf-8"))
    check("a same-named owner in another namespace does not inherit the rostered row",
          any("`impl.planted/!cells`" in f for f in findings))
    check("...and the rostered `impl.collector/!cells` is still accepted in that run",
          not any("`impl.collector/!cells`" in f for f in findings))

    check("a docstring is not code",
          strip_docstrings('(def x "a (defonce !y (atom 0)) example")').count("defonce") == 0)

    page = PAGE.read_text(encoding="utf-8") if PAGE.exists() else ""
    check("a surface with no row is a finding",
          any("no row on the census page" in f
              for f in arm_tooling(page.replace(
                  "implementation/hicasso/scripts/check_optional_module_reachability.py", "REMOVED"))))
    check("an empty Consumer cell is a finding",
          any("Consumer cell is empty" in f
              for f in arm_tooling(re.sub(
                  r"(\| `implementation/hicasso/scripts/check_optional_module_reachability\.py`[^|]*\|[^|]*\|)[^|]*\|",
                  r"\1 |", page))))

    print("self-test: " + ("all arms bite." if ok else "AN ARM DID NOT BITE."))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--self-test", action="store_true",
                    help="prove each arm fails on the mechanism it refuses")
    args = ap.parse_args()
    return self_test() if args.self_test else run()


if __name__ == "__main__":
    sys.exit(main())
