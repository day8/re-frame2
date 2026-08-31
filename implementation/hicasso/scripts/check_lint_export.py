#!/usr/bin/env python3
"""The Hicasso lint export's smoke: a consumer's copied config analyses the three macro shapes.

WHY THIS EXISTS (rf2-hic-022, reduced under rf2-r3r00).  The export's whole
job is macro-shape analysis: `defview`, `event` and `defhost` are rewritten to
their `defn` / `fn` / `def` shapes so kondo's ordinary analysis applies.  The
failure mode of a packaged config is SILENCE — an export off the classpath, or
a hook that stops loading, reds nothing on its own; every view name just goes
back to reading as `Unresolved symbol` in consumers' editors.  So this gate
lints one fixture of correct declarations, every documented shape of all three
macros, through the SHIPPED export directory as `--config-dir` — exactly how a
consumer's copied config is loaded — and requires the result to be exactly the
fixture's one sentinel finding.

THE SENTINEL.  `lint-fixtures/macro_shapes.cljs` ends with a view declaring a
prop its body never reads, and the gate pins that `:unused-binding` finding by
row.  Pure silence would also be the output of linting nothing at all; the
sentinel is the proof that kondo's analysis actually ran over the rewritten
forms.

NO SECOND ANALYZER.  The six custom `:re-frame.hicasso/*` behavioral findings
this gate once witnessed were retired (rf2-r3r00): behavior is the runtime's
law, refused loudly at its execution boundary.  The gate now also asserts the
export STAYS macro-shape-only — no `:re-frame.hicasso/*` linter in config.edn,
no `reg-finding!` in the hook — so the analyzer cannot ride back in
unwitnessed.

WHY A CHECKER AND NOT A `deftest` SUITE.  A JVM lane exists only via the
artefact rosters in `scripts/test-jvm-implementation.sh`
(`check_test_lane_bijection.py`, rf2-4hc9p), and a roster entry is only legal
with a matching `test.yml` job (`check_jvm_lane_rosters.py`, rf2-as6bg).
`.github/workflows/` is hot-zone, so that pair is a scheduling decision, and
the two rules are circular for anything shaped like a deftest.  A checker is
this artefact's own idiom — `check_freeze.py` beside this file is the same
shape, a gate over the package with a `--self-test` that proves its own red.

AT THE VERSION CI PINS -- WHEN IT CAN GET IT (rf2-x1mz).  The binary is
resolved through `scripts/lint_kondo.py`, which reads the pin off `lint.yml`
and provisions it -- the npm distribution stops at 2025.10.23, so there is no
other way to get it.  Where that fails (no network, an unpublished platform)
the gate still runs on whatever is available, because a smoke at the wrong
version is weaker evidence rather than none -- but it SAYS SO, loudly.

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
FIXTURE = os.path.join(FIXTURES, "macro_shapes.cljs")
HOOK_FILE = os.path.join(EXPORT_DIR, "hooks", "re_frame", "hicasso.clj")
CONFIG_FILE = os.path.join(EXPORT_DIR, "config.edn")

# The one finding the fixture must produce: its sentinel view's unread prop.
# A row rather than a count, so the finding drifting somewhere else cannot
# pass as the finding staying put.
SENTINEL = {"type": "unused-binding", "row": 55, "name": "unread"}


REPO_ROOT = os.path.dirname(os.path.dirname(ARTEFACT_ROOT))
PINNED_RESOLVER = os.path.join(REPO_ROOT, "scripts", "lint_kondo.py")
_KONDO_COMMAND = []  # one-slot memo; see `_kondo_command`


def _pinned_kondo():
    """The binary at lint.yml's pin, or `None` with the reason printed.

    Delegated rather than re-derived: `scripts/lint_kondo.py` already reads the
    pin off the workflow, knows the per-platform release archives and caches
    the download outside the repository. A second copy of that knowledge here
    is a second thing to keep in step with a pin bump.
    """
    if not os.path.isfile(PINNED_RESOLVER):
        return None
    try:
        proc = subprocess.run([sys.executable, PINNED_RESOLVER, "--print-binary"],
                              capture_output=True, text=True, timeout=300)
    except (OSError, subprocess.SubprocessError) as exc:
        print("NOTE: could not run the pinned clj-kondo resolver (%s)" % exc)
        return None
    if proc.returncode != 0:
        print("NOTE: clj-kondo at lint.yml's pin is unavailable here, so this "
              "smoke runs at whatever version is on PATH. Versions disagree "
              "about findings (rf2-x1mz), so a green below is weaker than CI's.")
        for line in (proc.stderr or "").splitlines():
            print("      " + line)
        return None
    return proc.stdout.strip() or None


def _kondo_command():
    """How to invoke clj-kondo here, preferring the PINNED NATIVE BINARY.

    The binary is what `.github/workflows/lint.yml` installs, at the pin this
    gate asserts against, and it needs no JDK — which is what lets the smoke
    run as a step of the existing `clj-kondo` job rather than waiting for a
    JVM lane that does not exist. The artefact's `:clj-kondo` alias is the
    last fallback, so a developer with neither still gets the gate.
    """
    # Memoised: the self-test calls `check()` once per mutation, so resolving
    # afresh each time would spawn a process per lint AND repeat the notice
    # above several times.
    if _KONDO_COMMAND:
        return list(_KONDO_COMMAND[0])
    pinned = _pinned_kondo()
    if pinned:
        _KONDO_COMMAND.append([pinned])
        return [pinned]
    for name in ("clj-kondo", "clojure"):
        # The RESOLVED path, not the bare name. On Windows an npm-installed
        # tool leaves both an extensionless shell shim and a `.cmd`; only the
        # latter is executable by `subprocess` without a shell, and `which`
        # can hand back either.
        found = (shutil.which(name + ".cmd") or shutil.which(name + ".bat")
                 or shutil.which(name))
        if found:
            cmd = [found] if name == "clj-kondo" else [found, "-M:clj-kondo"]
            _KONDO_COMMAND.append(cmd)
            return list(cmd)
    raise SystemExit(
        "FAIL: neither `clj-kondo` nor `clojure` is on PATH, so the Hicasso "
        "lint export gate cannot run. This is a HARD failure on purpose: it "
        "used to SKIP green, which is a gate reporting success over a case it "
        "never exercised -- the exact defect class it exists to catch.")


def _kondo(paths, config_dir=EXPORT_DIR):
    """Every finding clj-kondo reports for `paths`, as data.

    `:output {:format :json}` rather than parsing the human line format, and
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


def check(export_dir=EXPORT_DIR):
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

    # --- the export stays macro-shape-only (rf2-r3r00) --------------------
    with open(os.path.join(export_dir, "config.edn"), encoding="utf-8") as fh:
        config = fh.read()
    for line in config.splitlines():
        line = line.split(";;", 1)[0]
        if ":re-frame.hicasso/" in line:
            failures.append(
                "config.edn declares a behavioral :re-frame.hicasso/* linter; "
                "the export is macro-shape analysis only, and a custom "
                "analyzer must not ride back in unwitnessed (rf2-r3r00): %s"
                % line.strip())
    with open(os.path.join(export_dir, "hooks", "re_frame", "hicasso.clj"),
              encoding="utf-8") as fh:
        hook = fh.read()
    if "reg-finding!" in hook:
        failures.append(
            "the hook calls reg-finding!; the export is macro-shape analysis "
            "only, and a behavioral finding must not ride back in unwitnessed "
            "(rf2-r3r00)")

    # --- the smoke: correct declarations, and exactly the sentinel --------
    findings = _kondo([FIXTURE], config_dir=export_dir)
    expected = [f for f in findings
                if f.get("type") == SENTINEL["type"]
                and f.get("row") == SENTINEL["row"]
                and SENTINEL["name"] in f.get("message", "")]
    unexpected = [f for f in findings if f not in expected]
    for f in unexpected:
        failures.append(
            "lint-fixtures/macro_shapes.cljs:%s:%s fired %s -- correct "
            "declarations must produce nothing but the sentinel: %s"
            % (f.get("row"), f.get("col"), f.get("type"),
               f.get("message", "")[:120]))
    if not expected:
        failures.append(
            "the sentinel did not fire: expected %(type)s at row %(row)d "
            "(unused binding %(name)r). Silence here means kondo's analysis "
            "never ran over the rewritten forms -- a smoke that expected "
            "nothing would stay green while linting nothing." % SENTINEL)

    return failures


# ---------------------------------------------------------------------------
# The self-test -- proving the gate's red, not merely its green
# ---------------------------------------------------------------------------

def self_test():
    """Break the export one way at a time and prove the gate reds on each.

    A gate that has only ever been observed green is a gate nobody has tested.
    The first three cases are the acceptance's negative control (rf2-r3r00):
    each of the three rewrites made unavailable in turn must red the smoke.
    """
    # AND A GATE NOBODY HAS TESTED IS ALSO WHAT THIS BECOMES UNDER `python -O`
    # (rf2-uyhh) — or `PYTHONOPTIMIZE` in the environment, which needs no flag
    # at the call site. Either strips every `assert`, leaving a function that
    # runs to its success line having verified nothing: a control failing
    # GREEN, the one direction that never announces itself. The check is
    # empirical rather than a reading of `__debug__`, so it also catches a
    # `.pyc` compiled under `-O` and run without it.
    try:
        assert False
    except AssertionError:
        pass
    else:
        raise SystemExit(
            "assertions are disabled (python -O / PYTHONOPTIMIZE), so this "
            "self-test would prove nothing. Re-run without -O."
        )

    cases = [
        # The negative control: each rewrite unavailable in turn. Dropping a
        # hook registration from the shipped config is exactly what a consumer
        # with a broken copy would have, and every name that macro defines or
        # binds must go back to reading as unresolved.
        ("the defview rewrite unavailable reds", CONFIG_FILE,
         "re-frame.hicasso/defview hooks.re-frame.hicasso/defview\n   ", "",
         "unresolved-symbol"),
        ("the event rewrite unavailable reds", CONFIG_FILE,
         "re-frame.hicasso/event   hooks.re-frame.hicasso/event\n   ", "",
         "unresolved-symbol"),
        ("the defhost rewrite unavailable reds", CONFIG_FILE,
         "re-frame.hicasso/defhost hooks.re-frame.hicasso/defhost", "",
         "unresolved-symbol"),
        # The sentinel gone silent: read the unread prop and no finding is
        # left, which must red rather than pass -- pure silence is also what
        # linting nothing at all produces.
        ("a silent fixture reds", FIXTURE,
         "[:p shown])", "[:p shown unread])",
         "sentinel"),
        # The floor: a behavioral linter riding back into the shipped config
        # reds without needing a fixture to witness it.
        ("a behavioral linter riding back in reds", CONFIG_FILE,
         "{;; `re-frame.hicasso.native`",
         "{:linters {:re-frame.hicasso/direct-view-call {:level :error}}\n"
         " ;; `re-frame.hicasso.native`",
         "behavioral"),
    ]

    ok = True
    for label, path, old, new, expect in cases:
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
              "re-frame2-hicasso/README.md for what the export does -- and "
              "what it deliberately does not.")
        return 1

    print("OK: the shipped export gives a consumer ordinary analysis of all "
          "three macro shapes, and the sentinel proves the analysis ran.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
