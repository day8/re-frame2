#!/usr/bin/env python3
"""The Hicasso lint export's witness: each check fires, and correct code is silent.

WHY THIS EXISTS (rf2-hic-022).  A lint rule with no test is a rumour, and the
half that decides whether a lint layer is worth shipping is not the positive
case.  A rule with only positive fixtures fires on valid code and nobody
notices until it is annoying people, at which point the whole layer gets
ignored.  So every check the export publishes is asserted twice:

  * a POSITIVE case in `lint-fixtures/positive.cljs` that must trip it, at the
    exact source rows named below;
  * a NEGATIVE case in `lint-fixtures/negative.cljs` -- correct code written to
    sit as close to the mistake as correct code can get -- which must produce
    NO finding of ANY kind.  Not merely none of ours: the export also supplies
    `defview` / `hfn` / `defhost` their `defn` / `fn` / `def` shapes, so an
    `Unresolved symbol` there would mean a view's props had stopped resolving.

WHY A CHECKER AND NOT A `deftest` SUITE.  It was a `deftest` suite first, in
`test/re_frame/hicasso/lint_export_test.clj`, and two of the repo's own gates
refused it -- correctly.  A JVM lane exists only via the artefact rosters in
`scripts/test-jvm-implementation.sh` (`check_test_lane_bijection.py`, rf2-4hc9p:
"a file selected by nothing runs nowhere, in any lane, ever"), and a roster
entry is only legal with a matching `test.yml` job
(`check_jvm_lane_rosters.py`, rf2-as6bg).  `.github/workflows/` is hot-zone, so
that pair is a scheduling decision rather than something rf2-hic-022 could
take, and the two rules are circular for anything shaped like a deftest.

A checker is not a workaround for that -- it is this artefact's OWN idiom.
`check_freeze.py` sits beside this file and is the same shape: a gate over the
package, with a `--self-test` that proves its own red.  This one runs the real
clj-kondo, at the version CI pins, with the SHIPPED export directory as its
`--config-dir`, which is exactly how a consumer's copied config is loaded.
There is no second description of the rules here and no mock: a rule that
passes this gate is the rule the artefact publishes.

    python scripts/check_lint_export.py             run the gate
    python scripts/check_lint_export.py --self-test prove the gate fires

Run from `implementation/` as `npm run test:hicasso-lint`, which chains both.
"""

import argparse
import json
import re
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ARTEFACT_ROOT = os.path.dirname(HERE)
EXPORT_DIR = os.path.join(ARTEFACT_ROOT, "resources", "clj-kondo.exports",
                          "day8", "re-frame2-hicasso")
FIXTURES = os.path.join(ARTEFACT_ROOT, "lint-fixtures")
HOOK_FILE = os.path.join(EXPORT_DIR, "hooks", "re_frame", "hicasso.clj")

# The rows each check must fire on, in `lint-fixtures/positive.cljs`. Rows
# rather than a bare count: a check that starts firing somewhere ELSE while
# still firing the right NUMBER of times is precisely the collapse a count
# cannot see.
EXPECTED = {
    # ERRORS -- true invariants (operator ruling, rf2-hic-022).
    "direct-view-call":             [25, 144, 162, 167, 170, 174, 178, 183,
                                     187],
    "function-in-head-position":    [99, 102, 105, 110, 114],
    # WARNINGS -- heuristics and assistance. Nothing blocks a build.
    "deferred-read":                [32, 37, 43],
    "parked-read":                  [52, 56, 60, 200, 209],
    "unkeyed-mapped-child":         [68, 73, 76, 79],
    "nameless-interactive-element": [86, 89, 92, 123, 126],
}


def _kondo_command():
    """How to invoke clj-kondo here, preferring the NATIVE BINARY.

    The binary is what `.github/workflows/lint.yml` installs, at the pin this
    gate asserts against, and it needs no JDK — which is what lets the fixture
    witness run as a step of the existing `clj-kondo` job rather than waiting
    for a JVM lane that does not exist. The artefact's `:clj-kondo` alias is
    the local fallback, so a developer with no binary still gets the gate.
    """
    for name in ("clj-kondo", "clojure"):
        # The RESOLVED path, not the bare name. On Windows an npm-installed
        # tool leaves both an extensionless shell shim and a `.cmd`; only the
        # latter is executable by `subprocess` without a shell, and `which`
        # can hand back either.
        found = (shutil.which(name + ".cmd") or shutil.which(name + ".bat")
                 or shutil.which(name))
        if found:
            return [found] if name == "clj-kondo" else [found, "-M:clj-kondo"]
    raise SystemExit(
        "FAIL: neither `clj-kondo` nor `clojure` is on PATH, so the Hicasso "
        "lint export gate cannot run. This is a HARD failure on purpose: it "
        "used to SKIP green, which is a gate reporting success over a case it "
        "never exercised -- the exact defect class it exists to catch.")


def _kondo(paths, config_dir=EXPORT_DIR):
    """Every finding clj-kondo reports for `paths`, as data.

    `:output {:format :edn}` rather than parsing the human line format, and
    `:cache false` because clj-kondo otherwise writes an analysis cache into
    whatever `--config-dir` names -- which here is the directory a consumer
    copies.
    """
    proc = subprocess.run(
        _kondo_command() +
        ["--config-dir", config_dir,
         "--config", '{:output {:format :json} :cache false}',
         "--lint"] + list(paths),
        cwd=ARTEFACT_ROOT, capture_output=True, text=True,
    )
    out = proc.stdout.strip()
    if not out:
        raise SystemExit(
            "clj-kondo produced no output (exit %d).\nstdout: %s\nstderr: %s"
            % (proc.returncode, proc.stdout, proc.stderr))
    try:
        return json.loads(out)["findings"]
    except (ValueError, KeyError):
        raise SystemExit(
            "clj-kondo output was not the expected JSON (exit %d):\n%s\n%s"
            % (proc.returncode, out[:2000], proc.stderr[:2000]))


def _ours(findings):
    """Findings keyed by our short check name -> sorted rows."""
    by_check = {}
    for f in findings:
        t = f.get("type", "")
        if t.startswith("re-frame.hicasso/"):
            by_check.setdefault(t.split("/", 1)[1], []).append(f["row"])
    return {k: sorted(v) for k, v in by_check.items()}


def _configured_checks():
    """The checks the shipped config.edn declares, read from the file.

    Read rather than listed, so a check added to the export without a fixture
    here cannot ride in unwitnessed.
    """
    with open(os.path.join(EXPORT_DIR, "config.edn"), encoding="utf-8") as fh:
        text = fh.read()
    return {line.split(":re-frame.hicasso/", 1)[1].split()[0]
            for line in text.splitlines()
            if ":re-frame.hicasso/" in line and "{:level" in line}


def check(export_dir=EXPORT_DIR, fixtures=FIXTURES):
    """Every failure this gate can report, as a list of strings."""
    failures = []

    # --- the export is packaged where clj-kondo's --copy-configs looks -----
    for rel in ("config.edn", "README.md", os.path.join("hooks", "re_frame",
                                                        "hicasso.clj")):
        if not os.path.isfile(os.path.join(export_dir, rel)):
            failures.append("export is missing %s" % rel)
    if failures:
        return failures

    # The export reaches a consumer over the CLASSPATH; an export directory
    # that is not on :paths is invisible to --copy-configs, and the failure
    # mode is silence rather than an error.
    with open(os.path.join(ARTEFACT_ROOT, "deps.edn"), encoding="utf-8") as fh:
        deps = fh.read()
    # The `:paths` VECTOR, not merely the word somewhere in the file: this
    # artefact's header comment discusses `:aliases` and `:clein/build` in
    # prose, and a looser test read the comment instead of the form.
    paths = re.search(r":paths\s*\[([^\]]*)\]", deps)
    if not paths or '"resources"' not in paths.group(1):
        failures.append("deps.edn must put \"resources\" on :paths, or the "
                        "export is not on a consumer's classpath")

    # --- every configured check has a fixture -----------------------------
    configured = _configured_checks()
    if configured != set(EXPECTED):
        failures.append(
            "the export declares %s but this gate witnesses %s -- a check "
            "with no fixture is a rumour"
            % (sorted(configured), sorted(EXPECTED)))

    # --- positive: each check fires, at its exact rows --------------------
    positive = _ours(_kondo([os.path.join(fixtures, "positive.cljs")],
                            config_dir=export_dir))
    for name, rows in sorted(EXPECTED.items()):
        got = positive.get(name, [])
        if got != rows:
            failures.append(
                "%s: expected rows %s in lint-fixtures/positive.cljs, got %s"
                % (name, rows, got or "nothing -- the check did not fire"))

    # --- negative: correct code produces NO finding of any kind -----------
    negative = _kondo([os.path.join(fixtures, "negative.cljs")],
                      config_dir=export_dir)
    for f in negative:
        failures.append(
            "lint-fixtures/negative.cljs:%s:%s fired %s -- correct code must "
            "be silent: %s" % (f["row"], f["col"], f.get("type"),
                               f.get("message", "")[:120]))

    # --- the corpus: silent on code nobody wrote to pass ------------------
    # The bead's acceptance names "the tiny consumer app". Until one exists
    # (rf2-hic-008) the artefact's testbeds are the closest real thing: an
    # ordinary Hicasso application, written before any of these checks did.
    corpus = _ours(_kondo([os.path.join(ARTEFACT_ROOT, "testbed")],
                          config_dir=export_dir))
    if corpus:
        failures.append(
            "the lint layer fires on the artefact's own testbed code: %s"
            % sorted(corpus))

    return failures


# ---------------------------------------------------------------------------
# The self-test -- proving the gate's red, not merely its green
# ---------------------------------------------------------------------------

def _mutate(original, old, new):
    with open(HOOK_FILE, "w", encoding="utf-8") as fh:
        fh.write(original.replace(old, new))


def self_test():
    """Break the export one way at a time and prove the gate reds on each.

    A gate that has only ever been observed green is a gate nobody has tested.
    The first cases are the controls run by hand while the checks were being
    written, made permanent; the rest are the defects that reached main anyway,
    each pinned by the mutation that would bring it back. No count is stated
    here on purpose — the list grows, and a number in prose does not.
    """
    with open(HOOK_FILE, encoding="utf-8") as fh:
        original = fh.read()

    cases = [
        ("a disabled check reds the positive half", "hook",
         "    (run! check-nameless-interactive! elements)\n", "",
         "nameless-interactive-element"),
        # The false positive the negative fixtures caught while the checks were
        # being written: every `:on-click [:a]` in the corpus read as an
        # unnamed anchor, because an event vector and a hiccup element are the
        # same three characters.
        ("an element check reading props maps reds the negative half", "hook",
         "(mapcat element-subforms body)", "(mapcat subforms body)",
         "negative.cljs"),
        # Rows, not counts: deleting one positive case must red even though
        # the check itself still fires four more times.
        ("a check firing at the wrong ROWS reds", "fixture",
         '  [:a {:href "/help"}])', '  [:a {:href "/help"} "Help"])',
         "nameless-interactive-element"),
        # The ERROR-level rule that blocked correct code (merged-PR audit
        # #7794): a self-call check comparing SPELLING alone reports every
        # local that happens to share the view's name -- a parameter, a
        # `let`, a `for` binding. Blind the one helper that reads a binding
        # target and the negative half must red, because an ERROR firing on
        # somebody's `let` is the failure this whole layer exists to avoid.
        ("a self-call check blind to lexical shadowing reds", "hook",
         "  (into #{} (keep token-sexpr) (subforms node)))",
         "  (do node #{}))",
         "direct-view-call"),
        # The SECOND false ERROR (merged-PR audit #7804), and the reason this
        # case reads a fnspec's TAIL rather than its name: the repair above
        # took a `letfn` fnspec to be `[nm params]`, which is the single-arity
        # spelling and nothing else, so no arity of a MULTI-ARITY fnspec was
        # read and every use of one blocked the build again. Hand `fn-locals`
        # the name alone and the grammar rows in the negative half must red.
        ("a letfn fnspec read past its NAME reds", "hook",
         "(fn-locals (:children %))",
         "(fn-locals (take 1 (:children %)))",
         "direct-view-call"),
        # The same claim, one notch narrower, and the reason a shape is not a
        # witness (merged-PR audit #7818). "Every parameter of every arity"
        # was true of the code and untested by the rows: each multi-arity row
        # bound the view's name in EVERY arity, and one recognised arity
        # silences the whole `letfn`, so skipping the first arity left the
        # pinned gate at exit 0. The row that binds only in the first arity
        # is what this mutation reds; `zero-arity`, whose first arity binds
        # nothing, reds the opposite narrowing.
        ("a letfn arity SKIPPED reds", "hook",
         "(keep #(when (api/list-node? %) (first (:children %))) forms))]",
         "(keep #(when (api/list-node? %) (first (:children %))) (rest forms)))]",
         "direct-view-call"),
    ]

    ok = True
    try:
        for label, target, old, new, expect in cases:
            path = HOOK_FILE if target == "hook" else os.path.join(
                FIXTURES, "positive.cljs")
            with open(path, encoding="utf-8") as fh:
                source = fh.read()
            if old not in source:
                # A mutation whose target has moved proves nothing, and would
                # otherwise report a cheerful `ok` forever.
                print("  FAIL %s: the mutation target is gone from %s"
                      % (label, os.path.basename(path)))
                ok = False
                continue
            try:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(source.replace(old, new, 1))
                failures = check()
            finally:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(source)

            hit = any(expect in f for f in failures)
            print("  %s %s" % ("ok  " if hit else "FAIL", label))
            if not hit:
                ok = False
                print("     expected a failure mentioning %r; got: %s"
                      % (expect, failures or "NO FAILURES AT ALL"))
    finally:
        with open(HOOK_FILE, "w", encoding="utf-8") as fh:
            fh.write(original)

    # And green when nothing is broken, which is the other half of the claim.
    failures = check()
    print("  %s the unmutated export passes"
          % ("ok  " if not failures else "FAIL"))
    if failures:
        ok = False
        for f in failures:
            print("     " + f)

    print("check_lint_export self-test: %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="prove the gate's red/green classification, then exit")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    failures = check()
    if failures:
        print("FAIL: Hicasso lint export gate\n")
        for f in failures:
            print("  " + f)
        print("\nSee implementation/hicasso/resources/clj-kondo.exports/day8/"
              "re-frame2-hicasso/README.md for what each check knows -- and, "
              "more usefully, what it refuses to know.")
        return 1

    print("OK: %d checks fire on their fixtures, correct code is silent, and "
          "the artefact's own testbeds are quiet." % len(EXPECTED))
    return 0


if __name__ == "__main__":
    sys.exit(main())
