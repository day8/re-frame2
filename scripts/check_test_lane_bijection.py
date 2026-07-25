#!/usr/bin/env python3
"""Build-file <-> lane-selector bijection for every test lane in the repo.

WHY THIS EXISTS (rf2-4hc9p).  The per-lane test-count floor (rf2-qqzmf) makes a
lane that ran ZERO tests go red.  It cannot see a lane that ran SOME of what it
should: nine `.cljc` suites once sat on the `:node-test` build's classpath with
namespaces ending `-test` instead of `-cljs-test`, so the `cljs-test$` selector
skipped every one of them while the lane still reported thousands of passing
tests and exited 0 (rf2-ezbzvm).  A floor cannot catch that, and neither can a
calibrated per-lane number -- the collapse hides inside the headroom.

What CAN catch it is a bijection.  Every file that defines a test and sits on a
lane's classpath should be reachable by some lane's selector, and every lane's
selector should reach something.  Both halves are decidable from the build
configuration plus the source tree, with no runtime and no registry read.

    scripts/check_test_lane_bijection.py            scan the repo
    scripts/check_test_lane_bijection.py --verbose  ... and list every lane
    scripts/check_test_lane_bijection.py --self-test  prove the gate fires

THE LANES ARE READ, NEVER LISTED.  A lane roster inside this script would be
the staleness class the gate exists to catch, so every lane comes from the file
that defines it:

  * CLJS lanes  -- every `shadow-cljs.edn` in the tree: each build whose
    `:target` is `:node-test` or `:browser-test`, its `:ns-regexp`, and its
    source roots (the build's own `:source-paths` if it overrides, else the
    config's, else -- for the `:deps true` / `:deps {:aliases [...]}` shape --
    the sibling `deps.edn`).  Selection is shadow-cljs's `re-find` semantics.
    A config with no sibling `deps.edn` has no resolvable classpath and is not
    a lane (that is what separates a live build from a template payload).
  * JVM lanes   -- the artefact rosters in `scripts/test-jvm-implementation.sh`
    and `scripts/test-jvm-tools.sh`, each artefact's `deps.edn` `:test` alias
    supplying its discovery dirs (`--dir`, cognitect's default `test`) and its
    namespace regex (`-r`, cognitect's default full-match `.*\\-test$`).

A new artefact, a new build, a renamed directory or a retightened regex is
therefore picked up by construction.

WHAT COUNTS AS A TEST FILE.  A file that evaluates at least one test-defining
form AT THE TOP LEVEL.  Naming is not consulted, so a test file that does not
follow the `_test` filename convention is still in the universe.  Top level is
decided by reading the enclosing forms, not by the column the form starts in:

  * `(deftest ...)`, bare or namespace-qualified (`cljs.test/deftest`);
  * the same, inside a reader conditional (`#?(:cljs (deftest ...))`) and/or a
    `do` -- both are transparent to the reader, so what they wrap IS top level.
    Column 0 was the original test, and it could not see either shape;
  * a call to a macro whose expansion emits deftests.  The roster of such
    macros is DERIVED, not listed: any `(defmacro NAME ...)` in the scanned
    tree whose body contains a `deftest` form makes a top-level `(NAME ...)`
    call a test definition.  The macro's own file stays a non-test file, since
    the deftest there sits inside the `defmacro`, which is not transparent.

Comments and string bodies are masked before any of this, because the repo's
test files discuss `deftest` in prose at length.

CLASSIFIED BY THE DECLARED NAMESPACE.  A lane selector is applied by the
runner to the namespace the file DECLARES, so this gate reads `(ns ...)` rather
than deriving a name from the file path.  Deriving it was a second false green:
editing only the declared suffix moves the file out of a lane's selector while
a path-derived name goes on matching, so the lane silently sheds the file and
the gate goes on reporting it covered.

THE FIVE RULES

  B1  ORPHAN FILE.  A test file must be selected by at least one lane that can
      load it (`.clj` -> JVM lanes, `.cljs` -> CLJS lanes, `.cljc` -> either).
      A file selected by nothing runs nowhere, in any lane, ever.

  B2  DUAL-RUNTIME SYMMETRY -- the nine-file class.  A `.cljc` test file under
      a test root the CLJS build OWNS must be selected by a CLJS lane.  A
      `.cljc` file is cross-runtime by construction and the repo's convention
      is that its `.cljc` tests run in both runtimes; being JVM-discovered is
      not evidence that the CLJS half runs.
        A file that really is single-lane DECLARES it, in the one place the
      declaration cannot drift from the thing it describes: its own namespace,
      via the `-jvm-test` suffix.  The declaration has teeth both ways -- such
      a namespace must be JVM-discovered (it claims a lane, so it must have
      one) and must NOT be CLJS-selected (or the declaration is false).
        "Owns" is read off the build config, not decided here: a
      `:source-paths` entry that stays inside the shadow-cljs project is a tree
      the CLJS build owns, while a `../`-escaping entry is a foreign tree
      borrowed onto the classpath, whose lane is defined by its own `deps.edn`
      elsewhere.  Borrowed trees are held by B1 only.  (Today that line falls
      between `implementation/**` and `tools/**`; the `tools/{story,xray,
      machines-viz}` test trees carry `.cljc` suites that no CLJS lane selects
      and are tracked separately -- see rf2-odlm3.)

  B3  PHANTOM PATH.  Every root a lane declares must exist.  A renamed or
      deleted directory silently empties the lane that pointed at it, and the
      lane stays green on whatever is left.

  B4  EMPTY SELECTOR.  A lane's selector must reach at least one test file --
      the static half of the rf2-qqzmf floor, catching a typo'd regex before a
      compile rather than after.  Inverted for a lane that declares itself a
      classpath probe with `--probe` in its own `:test` alias: that lane must
      reach ZERO, and gaining a test makes it red and tells it to drop the
      flag.  Same declaration, same teeth, one tier earlier than the runner's
      own runtime check.

  B5  UNREADABLE NAMESPACE.  A test file must declare exactly one namespace.
      With none, or with two that disagree, there is no name to apply a
      selector to -- and a gate that quietly fell back to a path-derived guess
      would be back to the false green B5 exists to remove.

Counts belong in a PR body where they carry a date; this file states rules.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

TEST_EXTS = (".clj", ".cljc", ".cljs")
JVM_EXTS = (".clj", ".cljc")
CLJS_EXTS = (".cljs", ".cljc")

#: Cognitect test-runner v0.5.1 defaults (src/cognitect/test_runner.clj):
#: `(or (:dir options) #{"test"})` and `[#".*\-test$"]` applied with
#: `re-matches`.
COGNITECT_DEFAULT_DIRS = ("test",)
COGNITECT_DEFAULT_REGEX = r".*\-test$"

#: shadow-cljs's default `:ns-regexp` for a `:node-test` / `:browser-test`
#: build that does not declare one.
SHADOW_DEFAULT_NS_REGEXP = r"-test$"

#: A `.cljc` test namespace declaring that the JVM lane is its only lane.
JVM_ONLY_SUFFIX = "-jvm-test"

#: The token immediately after an opening delimiter -- a form's head.
FORM_HEAD = re.compile(r"[^\s()\[\]{}\"';`~@,^]+")

#: A head that is `deftest`, bare or namespace-qualified (`cljs.test/deftest`).
DEFTEST_HEAD = re.compile(r"(?:[^\s()\[\]{}/]+/)?deftest\Z")

#: The same as an opening form, for scanning a `defmacro` body.
DEFTEST_FORM = re.compile(r"\((?:[^\s()\[\]{}/]+/)?deftest[\s()\[\]{}]")

DEFMACRO_FORM = re.compile(r"\(defmacro\s+([^\s()\[\]{}]+)")

#: Forms the reader sees through: what they wrap is still top level.  A reader
#: conditional is recorded as `#?` whatever platform keyword heads it.
TRANSPARENT_HEADS = frozenset({"#?", "do"})

#: `(ns name)`, tolerating any number of metadata prefixes (`^{:doc ...}`,
#: `^:no-doc`).  Anchored at column 0: an `ns` form is never nested.
NS_FORM = re.compile(r"^\(ns\s+(?:\^(?:\{[^}]*\}|[^\s()\[\]{}]+)\s+)*([^\s()\[\]{}]+)",
                     re.MULTILINE)


#: A character literal, a string, or a `;` comment -- the three spans whose
#: contents are not structure.  The char-literal branch is FIRST and is the
#: only captured one, so a `\\"` or `\\;` survives (it is code) while a string
#: or a comment is dropped; `sub(r"\\1")` therefore needs no Python callback,
#: which is what keeps a 40 MB scan at C speed.
MASKABLE = re.compile(r'(\\.)|"[^"\\]*(?:\\.[^"\\]*)*"|;[^\n]*', re.DOTALL)
DELIMITERS = re.compile(r"[()\[\]{}]")


def mask_source(text: str) -> str:
    """Drop `;` comments and string bodies, leaving code and its line structure.

    Every recogniser below reads CODE only.  The repo's test files discuss
    `deftest` in docstrings and comments at length -- one of them spends a
    paragraph on how a map-form `use-fixtures` "SILENTLY SKIPS every deftest"
    -- so an unmasked scan reads prose as structure.

    A comment's own newline is outside the match and survives, and a string is
    replaced in place, so no newline is ever ADDED: a form that began a line
    still begins one, and a form that did not cannot come to.  Newlines INSIDE
    a string go with it, which is the point -- a docstring that quotes an
    `(ns ...)` form must not read as a second declaration.
    """
    return MASKABLE.sub(r"\1", text)


def top_level_heads(masked: str):
    """Yield the head of every form the reader evaluates at the top level.

    One pass over the delimiters, carrying the stack of enclosing heads.  A
    form counts as top level when every form enclosing it is transparent -- a
    reader conditional or a `do`.  That is what makes `#?(:cljs (deftest ...))`
    and `#?(:clj (do ... (deftest ...)))` reachable while a `deftest` inside a
    `defmacro` (the suite generator) correctly stays out of reach.
    """
    stack = []
    for match in DELIMITERS.finditer(masked):
        i = match.start()
        if match.group(0) in "([{":
            if masked[max(i - 3, 0):i] == "#?@" or masked[max(i - 2, 0):i] == "#?":
                head = "#?"
            else:
                m = FORM_HEAD.match(masked, i + 1)
                head = m.group(0) if m else ""
            if all(h in TRANSPARENT_HEADS for h in stack):
                yield head
            stack.append(head)
        elif stack:
            stack.pop()


def deftest_emitting_macros(masked_texts) -> set:
    """Names of macros whose expansion emits deftests, derived from the tree.

    A roster of generator macros written here would be the staleness class the
    gate exists to catch, so it is read off the `defmacro` forms themselves: a
    macro whose body contains a `deftest` form emits tests, and a top-level
    call to it therefore defines them.
    """
    names = set()
    for masked in masked_texts:
        for m in DEFMACRO_FORM.finditer(masked):
            try:
                body = masked[m.start():match_delimiter(masked, m.start())]
            except ValueError:
                continue
            if DEFTEST_FORM.search(body):
                names.add(m.group(1))
    return names


def defines_tests(masked: str, macro_names) -> bool:
    """One rule and no column heuristic.  A `(deftest` in column 0 was measured
    against the walk and saved 0.05s over the whole tree, which does not buy a
    second way to be wrong."""
    for head in top_level_heads(masked):
        if DEFTEST_HEAD.match(head):
            return True
        if head in macro_names:
            return True
        slash = head.rfind("/")
        if slash != -1 and head[slash + 1:] in macro_names:
            return True
    return False


def declared_namespaces(masked: str):
    """Every distinct namespace the file declares, in order of appearance."""
    seen = []
    for m in NS_FORM.finditer(masked):
        if m.group(1) not in seen:
            seen.append(m.group(1))
    return seen


#: Build output and dependency trees.  Nothing under them defines a lane or a
#: test the lanes select, and walking them dominates the runtime.
PRUNED_DIRS = frozenset({".git", "node_modules", ".shadow-cljs", ".cpcache",
                         "out", "target", "site", ".venv", "__pycache__"})


# --------------------------------------------------------------------------
# EDN / shell reading.  Enough of a reader to pull vectors, maps and strings
# out of the two configuration shapes this gate consults; not a general one.
# --------------------------------------------------------------------------

def strip_edn_comments(text: str) -> str:
    """Drop `;` line comments, respecting string literals."""
    out = []
    in_str = esc = False
    i = 0
    while i < len(text):
        c = text[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            out.append(c)
        elif c == ";":
            while i < len(text) and text[i] != "\n":
                i += 1
            continue
        else:
            out.append(c)
        i += 1
    return "".join(out)


def unescape_edn_string(s: str) -> str:
    """`\\\\.` in EDN source is one backslash plus a dot in the value."""
    return re.sub(r"\\(.)", lambda m: m.group(1) if m.group(1) in '\\"' else "\\" + m.group(1), s)


def match_delimiter(text: str, i: int) -> int:
    """`i` indexes an opening delimiter; return the index just past its match."""
    depth = 0
    in_str = esc = False
    while i < len(text):
        c = text[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c in "{[(":
            depth += 1
        elif c in "}])":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError("unbalanced delimiters")


def read_string_vector(text: str, key: str):
    """The vector of strings at `key`, or None when the key is absent."""
    m = re.search(re.escape(key) + r"\s*\[", text)
    if not m:
        return None
    end = match_delimiter(text, m.end() - 1)
    body = text[m.end():end - 1]
    return [unescape_edn_string(s) for s in re.findall(r'"((?:[^"\\]|\\.)*)"', body)]


def read_map_entries(text: str):
    """Yield `(key, body)` for each `key {...}` pair in a map body."""
    i = 0
    pattern = re.compile(r"([:A-Za-z0-9_.\-/]+)\s*\{")
    while i < len(text):
        m = pattern.search(text, i)
        if not m:
            return
        open_idx = m.end() - 1
        end = match_delimiter(text, open_idx)
        yield m.group(1), text[open_idx:end]
        i = end


def read_shell_array(text: str, name: str):
    """The entries of a `name=( ... )` bash array, comments dropped."""
    m = re.search(re.escape(name) + r"=\(", text)
    if not m:
        return []
    end = match_delimiter(text, m.end() - 1)
    entries = []
    for line in text[m.end():end - 1].splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            entries.append(line)
    return entries


# --------------------------------------------------------------------------
# Lane model
# --------------------------------------------------------------------------

@dataclass
class Lane:
    name: str
    runtime: str                  # "cljs" | "jvm"
    roots: list                   # (absolute Path, owned-by-cljs-build bool)
    regex: "re.Pattern"
    full_match: bool              # cognitect uses re-matches; shadow re-find
    probe: bool = False
    defined_in: str = ""
    missing_roots: list = field(default_factory=list)

    def selects(self, ns: str) -> bool:
        return bool(self.regex.fullmatch(ns) if self.full_match else self.regex.search(ns))


def deps_edn_paths(deps: Path, alias_names):
    """The source roots a `:deps`-resolved shadow build gets from a deps.edn.

    shadow-cljs's `:deps true` / `:deps {:aliases [...]}` shape has no
    `:source-paths` of its own -- the classpath comes from the sibling deps.edn.
    That is `:paths`, plus each named alias's `:extra-paths`, plus the `:paths`
    of each `:local/root` project it depends on (one level, which is as deep as
    any test root in this repo sits).
    """
    text = strip_edn_comments(deps.read_text(encoding="utf-8"))
    project = deps.parent
    declared = read_string_vector(text, ":paths")
    entries = [project / p for p in (["src"] if declared is None else declared)]

    top_deps = ""
    deps_map_at = re.search(r":deps\s*\{", text)
    if deps_map_at:
        top_deps = text[deps_map_at.end() - 1:match_delimiter(text, deps_map_at.end() - 1)]

    scopes = [top_deps]
    aliases_at = re.search(r":aliases\s*\{", text)
    if aliases_at:
        body = text[aliases_at.end() - 1:match_delimiter(text, aliases_at.end() - 1)]
        for name, alias_body in read_map_entries(body[1:-1]):
            if name in alias_names:
                scopes.append(alias_body)
                entries += [project / p for p in (read_string_vector(alias_body, ":extra-paths") or [])]

    for scope in scopes:
        for root in re.findall(r':local/root\s+"((?:[^"\\]|\\.)*)"', scope):
            sibling = (project / unescape_edn_string(root) / "deps.edn").resolve()
            if sibling.is_file():
                sibling_text = strip_edn_comments(sibling.read_text(encoding="utf-8"))
                sibling_paths = read_string_vector(sibling_text, ":paths")
                entries += [sibling.parent / p
                            for p in (["src"] if sibling_paths is None else sibling_paths)]
    return entries


def cljs_lanes(repo: Path):
    """Every `:node-test` / `:browser-test` build in every shadow-cljs.edn.

    Configs without a sibling `deps.edn` are skipped: their classpath cannot be
    resolved, which is what distinguishes a live lane from a template payload
    (`tools/template/resources/**`) or a fragment probe.
    """
    lanes = []
    configs = []
    for dirpath, dirnames, filenames in os.walk(repo):
        dirnames[:] = [d for d in dirnames if d not in PRUNED_DIRS]
        if "shadow-cljs.edn" in filenames:
            configs.append(Path(dirpath) / "shadow-cljs.edn")
    for config in sorted(configs):
        if not (config.parent / "deps.edn").is_file():
            continue
        text = strip_edn_comments(config.read_text(encoding="utf-8"))
        project = config.parent
        shared = read_string_vector(text, ":source-paths")
        # `:deps {:aliases [:cljs-test]}` names the deps.edn aliases whose
        # `:extra-paths` join the build's classpath; `:deps true` names none.
        deps_aliases = []
        deps_at = re.search(r":deps\s*\{", text)
        if deps_at:
            deps_body = text[deps_at.end() - 1:match_delimiter(text, deps_at.end() - 1)]
            deps_aliases = re.findall(r"[:A-Za-z0-9_.\-/]+", " ".join(
                re.findall(r":aliases\s*\[([^]]*)\]", deps_body)))

        builds_at = re.search(r":builds\s*\{", text)
        if not builds_at:
            continue
        builds_body = text[builds_at.end() - 1:match_delimiter(text, builds_at.end() - 1)]

        for name, body in read_map_entries(builds_body[1:-1]):
            target = re.search(r":target\s+(:[a-z\-]+)", body)
            if not target or target.group(1) not in (":node-test", ":browser-test"):
                continue
            regexp = re.search(r':ns-regexp\s+"((?:[^"\\]|\\.)*)"', body)
            # shadow-cljs's own default when a test build omits the key.  Model
            # it rather than erroring: a gate that refuses a legal config is a
            # false red, and B4 still holds the build to reaching something.
            selector = unescape_edn_string(regexp.group(1)) if regexp else SHADOW_DEFAULT_NS_REGEXP
            own = read_string_vector(body, ":source-paths")
            if own is not None:
                declared = [project / p for p in own]
            elif shared is not None:
                declared = [project / p for p in shared]
            else:
                declared = deps_edn_paths(config.parent / "deps.edn", set(deps_aliases))
            roots, missing = [], []
            for entry in declared:
                path = Path(entry).resolve()
                # A `../`-escaping entry is a foreign tree borrowed onto the
                # classpath; anything else is a tree this build owns (B2).
                owned = project.resolve() == path or project.resolve() in path.parents
                if path.is_dir():
                    roots.append((path, owned))
                else:
                    missing.append(os.path.relpath(path, project))
            lanes.append(Lane(name=f"{config.parent.relative_to(repo).as_posix()}:{name}"
                              if config.parent != repo else name,
                              runtime="cljs", roots=roots,
                              regex=re.compile(selector),
                              full_match=False,
                              defined_in=config.relative_to(repo).as_posix(),
                              missing_roots=missing))
    return lanes


def jvm_lanes(repo: Path):
    """Every artefact on the two JVM lane rosters, read through its deps.edn."""
    rosters = [(repo / "scripts" / "test-jvm-implementation.sh", "artefacts"),
               (repo / "scripts" / "test-jvm-tools.sh", "tools")]
    lanes = []
    for script, array in rosters:
        text = script.read_text(encoding="utf-8")
        for artefact in read_shell_array(text, array):
            deps = repo / artefact / "deps.edn"
            if not deps.is_file():
                raise SystemExit(f"error: {script.name} lists {artefact}, which has no deps.edn")
            body = strip_edn_comments(deps.read_text(encoding="utf-8"))
            alias = re.search(r":test\s*\{", body)
            if not alias:
                raise SystemExit(f"error: {artefact}/deps.edn has no :test alias "
                                 f"but {script.name} runs `clojure -M:test` in it")
            alias_body = body[alias.end() - 1:match_delimiter(body, alias.end() - 1)]
            opts = read_string_vector(alias_body, ":main-opts") or []

            dirs, regexes = [], []
            for flag, value in zip(opts, opts[1:]):
                if flag in ("-d", "--dir"):
                    dirs.append(value)
                elif flag in ("-r", "--namespace-regex"):
                    regexes.append(value)
            base = repo / artefact
            roots, missing = [], []
            for entry in dirs or COGNITECT_DEFAULT_DIRS:
                path = (base / entry).resolve()
                (roots if path.is_dir() else missing).append((path, False) if path.is_dir() else entry)
            lanes.append(Lane(name=artefact, runtime="jvm", roots=roots,
                              regex=re.compile(regexes[0] if regexes else COGNITECT_DEFAULT_REGEX),
                              full_match=True, probe="--probe" in opts,
                              defined_in=f"{artefact}/deps.edn",
                              missing_roots=missing))
    return lanes


# --------------------------------------------------------------------------
# The universe: files under a lane root that define tests at the top level
# --------------------------------------------------------------------------

@dataclass
class TestFile:
    path: Path
    #: The namespace the file DECLARES -- the name a lane selector is applied
    #: to.  None when the declaration is missing or self-contradictory, which
    #: B5 reports and which removes the file from selector-based reasoning.
    ns: str
    ext: str
    #: True when some CLJS lane root that OWNS its tree contains this file.
    on_owned_cljs_root: bool = False
    #: Every ancestor directory, as strings, so "is this file under one of the
    #: lane's roots?" is a set intersection rather than a walk per (lane, root).
    ancestors: frozenset = frozenset()
    #: Set when the `(ns ...)` declaration could not be read (B5).
    ns_problem: str = ""


def discover(lanes, repo: Path):
    """Every test-defining file under a lane root, keyed by path.

    A root is walked once and a file is read once no matter how many lanes share
    it -- every CLJS build in `implementation/shadow-cljs.edn` shares one
    `:source-paths` vector, so the naive shape re-reads the whole tree per lane.

    Two passes over that one read: the first derives the roster of macros whose
    expansion emits deftests, the second classifies each file against it.  The
    order matters -- a file whose only test definition is a call to such a macro
    is invisible until the roster exists.
    """
    sources = {}            # absolute path -> raw text
    walked = {}             # root -> [(path, ext)] of candidate files
    found = {}

    def scan(root: Path):
        if root in walked:
            return walked[root]
        hits = []
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in PRUNED_DIRS]
            for filename in filenames:
                ext = os.path.splitext(filename)[1]
                if ext not in TEST_EXTS:
                    continue
                path = Path(dirpath) / filename
                key = str(path)
                if key not in sources:
                    try:
                        sources[key] = path.read_text(encoding="utf-8")
                    except (UnicodeDecodeError, OSError):
                        sources[key] = ""
                hits.append((path, ext))
        walked[root] = hits
        return hits

    for lane in lanes:
        for root, _owned in lane.roots:
            scan(root)

    # Pass 1 -- the generator-macro roster, read off the tree's own `defmacro`
    # forms.  Only files that carry both tokens can contribute one.
    macro_names = deftest_emitting_macros(
        mask_source(text) for text in sources.values()
        if "defmacro" in text and "deftest" in text)

    # Pass 2 -- classify.  The substring pre-filter keeps masking off the ~40%
    # of scanned files that mention neither token, which is what holds the
    # runtime: masking is the only new work, and it is a callback-free `sub`.
    classified = {}

    def classify(key: str):
        if key in classified:
            return classified[key]
        text = sources[key]
        if "deftest" not in text and not any(name in text for name in macro_names):
            classified[key] = (False, None, "")
            return classified[key]
        masked = mask_source(text)
        if not defines_tests(masked, macro_names):
            classified[key] = (False, None, "")
            return classified[key]
        names = declared_namespaces(masked)
        if len(names) == 1:
            classified[key] = (True, names[0], "")
        elif not names:
            classified[key] = (True, None, "declares no `(ns ...)`")
        else:
            classified[key] = (
                True, None, "declares more than one namespace: " + ", ".join(names))
        return classified[key]

    for lane in lanes:
        for root, owned in lane.roots:
            for path, ext in walked[root]:
                key = str(path)
                is_test, ns, problem = classify(key)
                if not is_test:
                    continue
                entry = found.get(key)
                if entry is None:
                    entry = TestFile(path=path, ns=ns, ext=ext,
                                     ancestors=frozenset(str(p) for p in path.parents),
                                     ns_problem=problem)
                    found[key] = entry
                if lane.runtime == "cljs" and owned:
                    entry.on_owned_cljs_root = True
    return list(found.values())


def loadable_by(lane: Lane, test_file: TestFile) -> bool:
    exts = CLJS_EXTS if lane.runtime == "cljs" else JVM_EXTS
    return test_file.ext in exts


def select(lanes, files):
    """One pass, both directions: lanes-per-file (B1/B2) and files-per-lane (B4)."""
    selected_by = {id(f): [] for f in files}
    reached_by = {id(lane): [] for lane in lanes}
    for lane in lanes:
        lane_roots = frozenset(str(root) for root, _owned in lane.roots)
        for test_file in files:
            if test_file.ns is None:
                continue        # no name to select on; B5 owns it
            if not loadable_by(lane, test_file):
                continue
            if lane_roots.isdisjoint(test_file.ancestors):
                continue
            if lane.selects(test_file.ns):
                selected_by[id(test_file)].append(lane)
                reached_by[id(lane)].append(test_file)
    return selected_by, reached_by


def check(lanes, files, repo: Path):
    """Return a list of failure strings.  Empty means the bijection holds."""
    failures = []

    # B3 -- a lane pointing at a directory that is not there.
    for lane in lanes:
        for entry in lane.missing_roots:
            failures.append(
                f"B3 phantom lane path: {lane.runtime} lane `{lane.name}` declares root "
                f"`{entry}` in {lane.defined_in}, which does not exist. The lane runs "
                f"whatever is left and stays green.")

    selected_by, reached_by = select(lanes, files)

    def rel(p: Path) -> str:
        try:
            return str(p.relative_to(repo)).replace(os.sep, "/")
        except ValueError:
            return str(p)

    ordered = sorted(files, key=lambda f: (f.ns or "", str(f.path)))

    # B5 -- no readable namespace, so no selector can be applied at all.
    for test_file in ordered:
        if test_file.ns is None:
            failures.append(
                f"B5 unreadable namespace: {rel(test_file.path)} defines tests but "
                f"{test_file.ns_problem}. A lane selector is applied to the DECLARED "
                f"namespace, so this file's lane membership is undecidable.")

    # B1 -- selected by nothing at all.
    for test_file in ordered:
        if test_file.ns is None:
            continue
        if not selected_by[id(test_file)]:
            failures.append(
                f"B1 orphan test file: {rel(test_file.path)} defines tests as `{test_file.ns}`, "
                f"which no lane selector reaches. It runs in no lane.")

    # B2 -- a `.cljc` on a CLJS-owned root that no CLJS lane selects.
    for test_file in ordered:
        if test_file.ns is None:
            continue
        if test_file.ext != ".cljc" or not test_file.on_owned_cljs_root:
            continue
        cljs_hits = [l for l in selected_by[id(test_file)] if l.runtime == "cljs"]
        jvm_hits = [l for l in selected_by[id(test_file)] if l.runtime == "jvm"]
        declared_jvm_only = test_file.ns.endswith(JVM_ONLY_SUFFIX)
        if declared_jvm_only:
            if cljs_hits:
                failures.append(
                    f"B2 false single-lane declaration: {rel(test_file.path)} names itself "
                    f"`{JVM_ONLY_SUFFIX}` yet CLJS lane(s) {[l.name for l in cljs_hits]} select "
                    f"`{test_file.ns}`. Drop the suffix or retighten the selector.")
            if not jvm_hits:
                failures.append(
                    f"B2 unbacked single-lane declaration: {rel(test_file.path)} names itself "
                    f"`{JVM_ONLY_SUFFIX}` but no JVM lane discovers `{test_file.ns}`, so the "
                    f"lane it claims does not run it.")
        elif not cljs_hits:
            failures.append(
                f"B2 partially emptied CLJS lane: {rel(test_file.path)} is a `.cljc` test on a "
                f"CLJS build source path, but no CLJS selector reaches `{test_file.ns}` -- its "
                f"CLJS half never runs while the lane reports a green count. Rename the "
                f"namespace to a selected suffix, or declare the JVM lane as its only lane "
                f"with `{JVM_ONLY_SUFFIX}`.")

    # B4 -- a selector that reaches nothing (or a probe that reaches something).
    for lane in lanes:
        reached = reached_by[id(lane)]
        if lane.probe:
            if reached:
                failures.append(
                    f"B4 probe lane gained coverage: `{lane.name}` declares `--probe` in "
                    f"{lane.defined_in} (it claims resolution, not coverage) yet now reaches "
                    f"{[rel(f.path) for f in reached]}. It is claiming coverage — drop "
                    f"`--probe` and take the test-count floor.")
        elif not reached:
            failures.append(
                f"B4 empty lane selector: {lane.runtime} lane `{lane.name}` "
                f"({lane.defined_in}) selects `{lane.regex.pattern}`, which reaches no test "
                f"file on its roots. The lane will discover nothing.")
    return failures


# --------------------------------------------------------------------------
# Self-test -- a synthetic repo per case, so the real parsers are exercised
# --------------------------------------------------------------------------

SELF_TEST_SHADOW = """{:source-paths ["core/src" "core/test" "../borrowed/test"]
 :builds
 {:node-test {:target :node-test :ns-regexp "cljs-test$"}}}
"""

#: A second CLJS build whose selector reaches into the `-jvm-test` space, i.e.
#: a widened regex that contradicts a single-lane declaration.
SELF_TEST_SHADOW_WIDENED = SELF_TEST_SHADOW.replace(
    ':ns-regexp "cljs-test$"}}}',
    ':ns-regexp "cljs-test$"}\n  :node-test-wide {:target :node-test :ns-regexp "jvm-test$"}}}')

SELF_TEST_DEPS = """{:paths ["src"]
 :aliases {:test {:extra-paths ["test"]
                  :main-opts ["-m" "re-frame.test-quiet.runner"%s]}}}
"""

SELF_TEST_ROSTER = """#!/usr/bin/env bash
artefacts=(
  implementation/core
  # a comment line, and a blank line, both ignored
)
"""

SELF_TEST_TOOLS_ROSTER = """#!/usr/bin/env bash
tools=(
  borrowed
)
"""


def build_self_test_repo(root: Path, *, files, shadow=SELF_TEST_SHADOW, probe_flag=""):
    (root / "scripts").mkdir(parents=True, exist_ok=True)
    (root / "scripts" / "test-jvm-implementation.sh").write_text(SELF_TEST_ROSTER, encoding="utf-8")
    (root / "scripts" / "test-jvm-tools.sh").write_text(SELF_TEST_TOOLS_ROSTER, encoding="utf-8")
    (root / "implementation").mkdir(parents=True, exist_ok=True)
    (root / "implementation" / "shadow-cljs.edn").write_text(shadow, encoding="utf-8")
    # A shadow config is only a lane when its classpath resolves, which the
    # sibling deps.edn is what establishes.
    (root / "implementation" / "deps.edn").write_text('{:paths []}\n', encoding="utf-8")
    for artefact, flag in (("implementation/core", probe_flag), ("borrowed", "")):
        directory = root / artefact
        (directory / "src").mkdir(parents=True, exist_ok=True)
        (directory / "test").mkdir(parents=True, exist_ok=True)
        (directory / "deps.edn").write_text(SELF_TEST_DEPS % flag, encoding="utf-8")
    for relative, body in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")


GREEN_FILES = {
    # dual-runtime `.cljc`: JVM-discovered AND CLJS-selected
    "implementation/core/test/app/both_cljs_test.cljc": "(ns app.both-cljs-test)\n(deftest t (is true))\n",
    # declared single-lane `.cljc`
    "implementation/core/test/app/only_jvm_test.cljc": "(ns app.only-jvm-test)\n(deftest t (is true))\n",
    # a borrowed tree's JVM-only `.cljc` -- B1 holds, B2 does not apply
    "borrowed/test/app/borrowed_test.cljc": "(ns app.borrowed-test)\n(deftest t (is true))\n",
    # deftests the reader sees through to: one behind a reader conditional, one
    # behind a `do` inside one.  Both are top-level definitions and both are
    # properly selected here, so the green baseline exercises the recogniser
    # positively as well as the mutations below exercising it negatively.
    "implementation/core/test/app/conditional_cljs_test.cljc":
        "(ns app.conditional-cljs-test)\n"
        "#?(:cljs\n   (deftest in-a-reader-conditional (is true)))\n"
        "#?(:clj\n   (do\n     (deftest in-a-do (is true))))\n",
    # a generator: deftest only inside a defmacro, so not a test file at all
    "implementation/core/test/app/shared_suite.clj":
        "(ns app.shared-suite)\n(defmacro define! []\n  `(deftest generated (is true)))\n",
    # ... and its caller, which IS one: the suite arrives by expansion
    "implementation/core/test/app/generated_cljs_test.cljs":
        "(ns app.generated-cljs-test\n"
        "  (:require-macros [app.shared-suite :refer [define!]]))\n(define!)\n",
    # prose is not structure.  A commented-out deftest and a docstring quoting
    # one in column 0 -- the exact shape a raw `^\\(deftest` scan misreads.
    "implementation/core/test/app/prose_helper.clj":
        "(ns app.prose-helper)\n;; (deftest not-a-test (is true))\n"
        '(def example\n  "for the docs:\n(deftest also-not-a-test (is true))\nend")\n',
}


def run_self_test() -> int:
    cases = []

    def case(name, *, expect, files=None, shadow=SELF_TEST_SHADOW, probe_flag=""):
        cases.append((name, expect, files if files is not None else dict(GREEN_FILES),
                      shadow, probe_flag))

    case("A green tree reports no failure", expect=None)

    files = dict(GREEN_FILES)
    files["implementation/core/test/app/skipped_test.cljc"] = "(ns app.skipped-test)\n(deftest t (is true))\n"
    case("B2 fires on a `.cljc` no CLJS selector reaches", expect="B2 partially emptied", files=files)

    case("B2 fires on a false `-jvm-test` declaration",
         shadow=SELF_TEST_SHADOW_WIDENED, expect="B2 false single-lane")

    files = dict(GREEN_FILES)
    files["implementation/core/src/app/unbacked_jvm_test.cljc"] = (
        "(ns app.unbacked-jvm-test)\n(deftest t (is true))\n")
    case("B2 fires on a `-jvm-test` no JVM lane discovers",
         expect="B2 unbacked single-lane", files=files)

    files = dict(GREEN_FILES)
    files["implementation/core/test/app/helper_thing.cljc"] = (
        "(ns app.helper-thing)\n(deftest t (is true))\n")
    case("B1 fires on a test file no lane selects", expect="B1 orphan", files=files)

    case("B3 fires on a lane root that does not exist",
         shadow=SELF_TEST_SHADOW.replace('"core/test"', '"core/renamed-away"'),
         expect="B3 phantom lane path")

    case("B4 fires on a selector that reaches nothing",
         shadow=SELF_TEST_SHADOW.replace('"cljs-test$"', '"never-matches-anything$"'),
         expect="B4 empty lane selector")

    case("B4 fires on a `--probe` lane that gained a test",
         probe_flag=' "--probe"', expect="B4 probe lane gained coverage")

    # The recogniser mutations.  Each plants a test file in one of the shapes
    # the pre-repair column-0 scan could not see, under a namespace no selector
    # reaches.  Seeing the shape is what makes B1 fire; not seeing it was a
    # silent pass.
    files = dict(GREEN_FILES)
    files["implementation/core/test/app/rc_helper.cljc"] = (
        "(ns app.rc-helper)\n#?(:cljs\n   (deftest t (is true)))\n")
    case("B1 sees a deftest behind a reader conditional", expect="B1 orphan", files=files)

    files = dict(GREEN_FILES)
    files["implementation/core/test/app/rc_do_helper.cljc"] = (
        "(ns app.rc-do-helper)\n#?(:clj\n   (do\n     (deftest t (is true))))\n")
    case("B1 sees a deftest behind a reader-conditional `do`",
         expect="B1 orphan", files=files)

    files = dict(GREEN_FILES)
    files["implementation/core/test/app/macro_helper.cljs"] = (
        "(ns app.macro-helper\n"
        "  (:require-macros [app.shared-suite :refer [define!]]))\n(define!)\n")
    case("B1 sees a macro-generated suite entry point", expect="B1 orphan", files=files)

    files = dict(GREEN_FILES)
    files["implementation/core/test/app/qualified_helper.cljc"] = (
        "(ns app.qualified-helper)\n(clojure.test/deftest t (is true))\n")
    case("B1 sees a namespace-qualified deftest", expect="B1 orphan", files=files)

    # The declared-namespace mutation: the FILE keeps its selected path, only
    # the `(ns ...)` moves.  A path-derived name goes on matching `cljs-test$`
    # while the lane, which reads the declaration, has quietly dropped it.
    files = dict(GREEN_FILES)
    files["implementation/core/test/app/both_cljs_test.cljc"] = (
        "(ns app.both-test)\n(deftest t (is true))\n")
    case("B2 fires when only the DECLARED namespace leaves the selector",
         expect="B2 partially emptied", files=files)

    files = dict(GREEN_FILES)
    files["implementation/core/test/app/nameless_cljs_test.cljc"] = "(deftest t (is true))\n"
    case("B5 fires on a test file that declares no namespace",
         expect="B5 unreadable namespace", files=files)

    files = dict(GREEN_FILES)
    files["implementation/core/test/app/two_ns_cljs_test.cljc"] = (
        "(ns app.two-ns-cljs-test)\n(deftest t (is true))\n(ns app.somewhere-else)\n")
    case("B5 fires on two disagreeing namespace declarations",
         expect="B5 unreadable namespace", files=files)

    ok = True
    for name, expect, files, shadow, probe_flag in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            build_self_test_repo(root, files=files, shadow=shadow, probe_flag=probe_flag)
            lanes = cljs_lanes(root) + jvm_lanes(root)
            failures = check(lanes, discover(lanes, root), root)
            if expect is None:
                passed = not failures
                detail = "expected no failure, got: " + "; ".join(failures)
            else:
                passed = any(expect in f for f in failures)
                detail = f"expected a failure containing {expect!r}, got: " + ("; ".join(failures) or "none")
            print(f"  {'ok  ' if passed else 'FAIL'} {name}")
            if not passed:
                print(f"       {detail}")
                ok = False
    print(f"self-test: {'PASS' if ok else 'FAIL'} ({len(cases)} cases)")
    return 0 if ok else 1


# --------------------------------------------------------------------------

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true",
                        help="list every lane and what its selector reaches")
    parser.add_argument("--self-test", action="store_true",
                        help="prove each rule fires on its defect and passes on a clean tree")
    parser.add_argument("--repo-root", default=None,
                        help="scan DIR instead of this script's repository")
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test()

    repo = Path(args.repo_root).resolve() if args.repo_root else Path(__file__).resolve().parent.parent
    lanes = cljs_lanes(repo) + jvm_lanes(repo)
    files = discover(lanes, repo)

    if args.verbose:
        _, reached_by = select(lanes, files)
        for lane in lanes:
            flag = " [probe]" if lane.probe else ""
            print(f"  {lane.runtime:4s} {lane.name:52s} /{lane.regex.pattern}/ "
                  f"-> {len(reached_by[id(lane)])} test file(s){flag}")
        print(f"  {len(lanes)} lanes, {len(files)} test-defining files")

    failures = check(lanes, files, repo)
    if failures:
        print(f"FAIL test-lane bijection: {len(failures)} problem(s)", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print(f"PASS test-lane bijection: {len(files)} test-defining files, "
          f"{len(lanes)} lanes, every file selected and every selector reached")
    return 0


if __name__ == "__main__":
    sys.exit(main())
