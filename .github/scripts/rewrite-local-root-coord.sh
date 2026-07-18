#!/usr/bin/env sh
# rewrite-local-root-coord.sh (rf2-ldkuk; extracted from the inline
# release.yml "Rewrite <leaf> :local/root → :mvn/version" step).
#
# # What this does
#
# Every publishable leaf depends on its in-repo siblings via a
# `:local/root` coordinate, e.g.
#
#     {:deps {day8/re-frame2 {:local/root "../../core"}}}
#
# `clein pom` SILENTLY SKIPS :local/root coordinates (proved by rf2-do3m2 /
# #6340), so a pom built straight from that deps.edn carries NO
# day8/re-frame2 dependency at all. This rewrite is therefore load-bearing
# for correctness, not convenience: it swaps the in-tree coordinate for the
# published one
#
#     {:deps {day8/re-frame2 {:mvn/version "1.2.3"}}}
#
# on the throwaway runner checkout, before clein packages the jar.
#
# # Why the match is comment-aware (rf2-ldkuk)
#
# The step this replaces asserted `src.count(before) == 1` over the RAW
# file text. That count is a substring count, so it cannot tell a live
# dependency coordinate from the same characters typed inside a `;;`
# comment — and these deps.edn files document their coordinates in prose
# comments as a matter of house style (eight of the thirteen leaves do it).
# reagent-slim's header comment quotes the :local/root form, which pushed
# its count to 2 and ABORTED that leaf's deploy outright: day8/reagent-slim
# could not be released at all.
#
# Rewording one comment would have restored green while leaving the
# invariant satisfied by luck — the next ordinary comment reintroduces the
# abort. So the match itself is the thing that was wrong: this script
# counts and rewrites only occurrences in CODE, ignoring EDN line comments.
# Comments may now say whatever is clearest.
#
# This is deliberately NOT an EDN parser. The whole rule is "a `;` outside
# a string starts a comment that runs to end of line", with string state
# carried across lines and `\;`-style character literals respected. That is
# enough to tell a coordinate from prose, and nothing more is needed.
#
# # Fail-loud invariants
#
# Aborts (non-zero exit, GitHub-Actions `::error::` line) if:
#   - deps.edn is missing                                    → exit 2
#   - no :local/root "<path>" occurs in code                 → exit 3
#     (reported distinctly when occurrences exist but are ALL in comments,
#      which means the real coordinate was renamed or removed)
#   - more than one occurs in code                           → exit 4
#     (genuinely ambiguous — refuse rather than guess)
# Better a release abort than a published pom missing its framework dep.
#
# # Runner / portability
#
# Linux-runner-only by design: the only caller is release.yml's deploy-leaf
# job, which runs on ubuntu-latest. POSIX sh + python3 — python3 is what
# the inline step it replaces already used, and it is preinstalled on
# ubuntu-latest. No .ps1 sibling (same rationale as
# transform-reagent-slim-ns.sh).
#
# # Usage
#
#   ./.github/scripts/rewrite-local-root-coord.sh LOCAL_ROOT VERSION [DEPS_EDN]
#
# DEPS_EDN defaults to ./deps.edn (release.yml runs it with
# working-directory set to the leaf's directory). The argument exists so
# the unit test can point it at a throwaway fixture.

set -eu

LOCAL_ROOT="${1:?usage: rewrite-local-root-coord.sh LOCAL_ROOT VERSION [DEPS_EDN]}"
VERSION="${2:?usage: rewrite-local-root-coord.sh LOCAL_ROOT VERSION [DEPS_EDN]}"
DEPS_EDN="${3:-deps.edn}"

if [ ! -f "$DEPS_EDN" ]; then
  echo "::error::expected deps.edn at '$DEPS_EDN' — not found"
  exit 2
fi

LOCAL_ROOT="$LOCAL_ROOT" VERSION="$VERSION" DEPS_EDN="$DEPS_EDN" python3 <<'PY'
import os
import pathlib
import sys

local_root = os.environ["LOCAL_ROOT"]
version = os.environ["VERSION"]
deps_edn = pathlib.Path(os.environ["DEPS_EDN"])

before = ':local/root "%s"' % local_root
after = ':mvn/version "%s"' % version

src = deps_edn.read_text()


def split_code(text):
    """Yield (line, code_part) — code_part is the line up to the start of
    its EDN comment, or the whole line when it has none.

    A `;` begins a comment only outside a string literal. String state is
    carried across lines (EDN strings may span them), `\\`-escapes inside
    strings are skipped, and a bare `\\x` outside a string is an EDN
    character literal (`\\;` is a semicolon, not a comment)."""
    in_str = False
    for line in text.split("\n"):
        i, n, cut = 0, len(line), None
        while i < n:
            c = line[i]
            if in_str:
                if c == "\\":
                    i += 2
                    continue
                if c == '"':
                    in_str = False
            else:
                if c == '"':
                    in_str = True
                elif c == "\\":
                    i += 2
                    continue
                elif c == ";":
                    cut = i
                    break
            i += 1
        yield line, (line if cut is None else line[:cut])


lines = list(split_code(src))
code_hits = sum(code.count(before) for _, code in lines)

if code_hits != 1:
    raw_hits = src.count(before)
    detail = "found %d in code" % code_hits
    if code_hits == 0 and raw_hits > 0:
        detail += (
            "; %d occurrence(s) exist but are ALL inside EDN comments, so the "
            "real dependency coordinate is missing" % raw_hits
        )
    elif raw_hits != code_hits:
        detail += " (%d raw, the rest inside comments)" % raw_hits
    print(
        "::error::expected exactly one %s dependency coordinate in %s — %s"
        % (before, deps_edn, detail),
        file=sys.stderr,
    )
    # 3 = absent, 4 = ambiguous.
    sys.exit(3 if code_hits == 0 else 4)

out = []
done = False
for line, code in lines:
    if not done and before in code:
        # Rewrite within the code span only; any trailing comment on this
        # line is preserved verbatim.
        out.append(code.replace(before, after, 1) + line[len(code):])
        done = True
    else:
        out.append(line)

deps_edn.write_text("\n".join(out))
# ASCII-only output: this script's stdout encoding follows the host locale,
# and a non-ASCII arrow would raise UnicodeEncodeError on a non-UTF-8
# console AFTER deps.edn had already been written — a spurious abort with
# the mutation half-applied. Observed on a Windows cp1252 console while
# testing; ubuntu-latest is UTF-8, but there is no reason to depend on it.
print("Rewrote %s -> %s in %s" % (before, after, deps_edn))
PY
