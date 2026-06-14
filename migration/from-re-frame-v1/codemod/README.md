# `reg-event` migration codemod (EP-0018 Slice E)

A standalone scanner + conservative codemod for the v1 → v2 event-registration
collapse defined by [EP-0018](../../../docs/EP/EP-0018-one-event-registration.md):
the three public registrars (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`)
collapse to one public form, **`reg-event`** (semantically today's
`reg-event-fx`). The rule it implements is [MIGRATION M-73](../README.md#m-73-one-event-registration-form-reg-event-db--reg-event-fx-removed-reg-event-ctx-demoted-ep-0018).

It is **self-contained**: it operates on source *text* via
[rewrite-clj](https://github.com/clj-commons/rewrite-clj) (a zipper over the
node tree, so formatting and comments survive a rewrite) and never loads,
requires, or executes re-frame2 itself. That means it runs against any v1 corpus
on a bare JVM with `clojure` on the path — no re-frame2 build in the loop.

## What it does

| v1 form | Action | Result |
|---|---|---|
| `reg-event-fx` | **rename** | `reg-event` (body byte-for-byte unchanged — `reg-event` *is* `reg-event-fx`) |
| simple `reg-event-db` | **rewrite** | `reg-event`; the `db` param is destructured (`{:keys [db]}`) and the body is wrapped `{:db BODY}`. Path-interceptor metadata in the middle slot is preserved. |
| `reg-event-db` whose first param is a non-`db` symbol (e.g. a path-scoped slice `c`) | **rewrite** | `reg-event`; the param is rebound `{c :db}` — the db value back under its original name — so every body reference to `c` stays resolved while the body is left byte-for-byte unchanged. (Rebinding to `{:keys [db]}` here would orphan the body's `c` references — `rf2-xhfxcs.15`.) |
| nil-capable `reg-event-db` | **flag** (`:nil-capable`) | left unchanged — D7: under v2 a bare `nil` is a no-op and `{:db nil}` coerces to `{:db {}}`, so the author chooses the intended reading |
| complex `reg-event-db` | **flag** (`:complex`) | left unchanged — non-literal handler (var / higher-order / multi-arity) or a destructured first param |
| `reg-event-ctx` | **flag** (`:ctx`) | left unchanged — withdrawn from the public surface; rewrite to an interceptor (`->interceptor`) by hand |

Detection is **alias-agnostic**: `rf/reg-event-db`, `re-frame.core/reg-event-db`,
and bare `reg-event-db` are all recognised, and the rename preserves whatever
alias/namespace was on the symbol.

### The D7 nil flag

`BODY` in a `reg-event-db` always evaluates to the new app-db, so `{:db BODY}` is
a faithful, semantics-preserving wrap regardless of how complex `BODY` is — with
one subtlety. If `BODY` can evaluate to `nil` (a `when` / `if`-without-else /
`cond` / `and` / `or` / bare `get` / `some->` tail, a literal `nil`, …) the
codemod **does not silently rewrite it**. Under the new model a bare `nil`
return is a clean no-op (and `{:db nil}` coerces to `{:db {}}` — see `rf2-ekq28v`),
so the author may now prefer that reading over faithfully reproducing the v1
"write nil to app-db" footgun. The codemod flags these for human review.

The nil analysis is **conservative**: it answers "non-nil" only for bodies it can
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
 :form   :reg-event-db | :reg-event-fx | :reg-event-ctx
 :action :rewrite | :rename | :flag
 :flag   nil | :nil-capable | :complex | :ctx     ;; set when :action :flag
 :target :reg-event | nil
 :note   "human-readable explanation"}
```

## Tests

```bash
clojure -M:test
```

The migration tests in `test/` exercise the full coverage matrix over
representative v1 snippets: simple `-db`, `-db` with a path interceptor, `-fx`
rename, `-ctx`, nil-capable bodies (`when` / `if` / `get` / `cond` / `and` / `or` /
`some->` / literal `nil`), complex `-db` (var / multi-arity / destructured db
param), alias-agnostic detection, shape non-corruption (untouched code
round-trips byte-for-byte), comment/whitespace preservation, idempotence, and the
filesystem entry points.
