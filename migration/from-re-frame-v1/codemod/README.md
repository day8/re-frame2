# `reg-event` migration codemod (EP-0018 Slice E)

A standalone scanner + conservative codemod for the v1 → v2 event-registration
collapse defined by [EP-0018](../../../docs/EP/EP-0018-one-event-registration.md):
the three public registrars (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`)
collapse to one public form, `reg-event` (semantically today's
`reg-event-fx`). The rule it implements is [MIGRATION M-73](../README.md#m-73-one-event-registration-form-reg-event-db--reg-event-fx-removed-reg-event-ctx-demoted-ep-0018).

It is self-contained: it operates on source text via
[rewrite-clj](https://github.com/clj-commons/rewrite-clj) (a zipper over the
node tree, so formatting and comments survive a rewrite) and never loads,
requires, or executes re-frame2 itself. That means it runs against any v1 corpus
on a bare JVM with `clojure` on the path — no re-frame2 build in the loop.

## What it does

| v1 form | Action | Result |
|---|---|---|
| `reg-event-fx` | **rename** | `reg-event` (body byte-for-byte unchanged — `reg-event` *is* `reg-event-fx`); a v1 interceptor chain in the middle slot is normalized (or flagged) per the chain rules below |
| simple `reg-event-db` | **rewrite** | `reg-event`; the `db` param is destructured (`{:keys [db]}`) and the body is wrapped `{:db BODY}`; the chain rules below apply to the middle slot |
| `reg-event-db` whose first param is a non-`db` symbol (e.g. a path-scoped slice `c`) | **rewrite** | `reg-event`; the param is rebound `{c :db}` — the db value back under its original name — so every body reference to `c` stays resolved while the body is left byte-for-byte unchanged. (Rebinding to `{:keys [db]}` here would orphan the body's `c` references — `rf2-xhfxcs.15`.) |
| any form whose chain has an entry with no derivable v2 reference | **flag** (`:interceptors`) | left unchanged — unresolved **M-70 Type B**; see the chain rules below |
| already-renamed `reg-event` carrying a v1 chain survivor | **rewrite** / **flag** (`:interceptors`) | the rescan (below) repairs or flags partially migrated trees; valid `reg-event` forms produce **no** finding |
| nil-capable `reg-event-db` | **flag** (`:nil-capable`) | left unchanged — D7: under v2 a bare `nil` is a no-op and `{:db nil}` coerces to `{:db {}}`, so the author chooses the intended reading |
| complex `reg-event-db` | **flag** (`:complex`) | left unchanged — non-literal handler (var / higher-order / multi-arity) or a destructured first param |
| `reg-event-ctx` | **flag** (`:ctx`) | left unchanged — withdrawn from the public surface; rewrite the full-context work to a **registered interceptor** (`reg-interceptor`, referenced by id in `:interceptors`; EP-0022) by hand |

Detection is alias-agnostic: `rf/reg-event-db`, `re-frame.core/reg-event-db`,
and bare `reg-event-db` are all recognised, and the rename preserves whatever
alias/namespace was on the symbol.

### Interceptor chains — the M-70 × M-73 composition

v2 chains are **reference-only** (EP-0022): a chain entry is a bare keyword id
or an `[id arg]` 2-vector, carried in metadata `:interceptors`. v1 chains
carried inline **values** — `(rf/path …)` calls, custom interceptor vars — in
a positional vector middle slot or a metadata `:interceptors` vector. Leaving
those in place emits output v2 **rejects at namespace load**
(`:rf.error/path-removed` / `:rf.error/reg-event-bad-middle-slot` /
`:rf.error/inline-interceptor-removed`), so the codemod normalizes what is
mechanical and flags the rest (`rf2-8odvg`):

- **The standard `path` constructor is the one mechanical lowering.**
  `(rf/path p…)` (any alias; args flattened as v1 `path` did) becomes the
  framework factory ref `[:rf.interceptor/path [p…]]` — recognised in
  metadata-`:interceptors`, positional-vector, bare-middle, and
  metadata-plus-vector source shapes. Positional chains are wrapped (or
  merged) into the one metadata-map `:interceptors` form; entry declaration
  order is preserved.
- **Entries that are already v2 refs are preserved verbatim.**
- **Anything else flags the whole site** (`:flag :interceptors`, source
  unchanged): a custom value, a var, `(rf/debug)`, a `path` call with a
  non-literal arg — its registered id cannot be derived without author
  intent, so it is an unresolved **M-70 Type B** finding. Register the
  interceptor with `reg-interceptor`, reference it by id, and re-run. The
  codemod never reports a site as rewritten while its output would be
  rejected by v2.
- **Already-renamed `reg-event` forms are rescanned** for these invalid
  survivors, so a re-run recovers a partially migrated tree. Valid
  `reg-event` registrations produce no finding.

### The D7 nil flag

`BODY` in a `reg-event-db` always evaluates to the new app-db, so `{:db BODY}` is
a faithful, semantics-preserving wrap regardless of how complex `BODY` is — with
one subtlety. If `BODY` can evaluate to `nil` (a `when` / `if`-without-else /
`cond` / `and` / `or` / bare `get` / `some->` tail, a literal `nil`, …) the
codemod does not silently rewrite it. Under the new model a bare `nil`
return is a clean no-op (and `{:db nil}` coerces to `{:db {}}` — see `rf2-ekq28v`),
so the author may now prefer that reading over faithfully reproducing the v1
"write nil to app-db" footgun. The codemod flags these for human review.

The nil analysis is conservative: it answers "non-nil" only for bodies it can
prove are non-nil (a literal collection, or a builder headed by `assoc` /
`assoc-in` / `update` / `merge` / `dissoc` / … or a `->` thread ending in one of
those). Anything it is unsure about it flags — the safe direction for D7.

## Usage

From this directory (`migration/from-re-frame-v1/codemod`):

```bash
# Scan a file set: report every retired-registrar site (file:line:col + suggested target).
clojure -M:run PATH ...

# Dry-run the codemod: print findings, write nothing.
clojure -M:run --rewrite PATH ...

# Apply the conservative codemod IN PLACE (rewrites + flags; flagged sites untouched).
clojure -M:run --rewrite --write PATH ...
```

`PATH` may be a file or a directory; directories are walked recursively for
`.clj` / `.cljc` / `.cljs` sources.

## Programmatic API

The atomic-flip slice calls the codemod corpus-wide via these fns in
`re-frame.migration.reg-event-codemod`:

```clojure
(scan-string s opts)   ;; -> [finding ...]
(scan-file   path)     ;; -> [finding ...]
(scan-paths  paths)    ;; -> [finding ...]   (files + dirs, recursive)
(rewrite-string s)     ;; -> {:source out :findings [...]}
(rewrite-file!  path {:write? bool})  ;; -> {:path .. :changed? .. :findings [...] :source ..}
(rewrite-paths! paths {:write? bool}) ;; -> [{...} ...]
```

A `finding` is a map:

```clojure
{:file   "path or nil"
 :line   42 :col 3
 :form   :reg-event-db | :reg-event-fx | :reg-event-ctx | :reg-event
 :action :rewrite | :rename | :flag
 :flag   nil | :nil-capable | :complex | :ctx
         | :interceptors                          ;; unresolved M-70 Type B
 :target :reg-event | nil
 :note   "human-readable explanation"}
```

## Tests

```bash
# Unit suite — self-contained (no re-frame2 on the classpath).
clojure -M:test

# Runtime integration proof (rf2-8odvg) — evaluates the codemod's emitted
# output against the REAL v2 re-frame.core reg-event contract via a
# :local/root dep on implementation/core. Kept in its own alias so :test
# preserves the self-containment property above.
clojure -M:integration
```

The migration tests in `test/` exercise the full coverage matrix over
representative v1 snippets: simple `-db`, `-db` with a path interceptor (every
mechanical chain shape — metadata / positional / bare / metadata-plus-vector —
lowered to `[:rf.interceptor/path [p…]]` refs), custom inline interceptors
flagged as unresolved M-70 Type B, the `reg-event` invalid-survivor rescan,
`-fx` rename, `-ctx`, nil-capable bodies (`when` / `if` / `get` / `cond` / `and` / `or` /
`some->` / literal `nil`), complex `-db` (var / multi-arity / destructured db
param), alias-agnostic detection, shape non-corruption (untouched code
round-trips byte-for-byte), comment/whitespace preservation, idempotence, and the
filesystem entry points.

The integration tests in `test-integration/` register the emitted output
against the actual v2 core and pin the negative controls: the pre-fix
preserved-inline output throws `:rf.error/path-removed`, a surviving
positional middle slot throws `:rf.error/reg-event-bad-middle-slot`, an inline
interceptor value throws `:rf.error/inline-interceptor-removed`, and an
unregistered ref throws `:rf.error/unregistered-interceptor` — proving the
harness observes registration rather than parsing.
