# re-frame2-ui-migrator (W1)

The **doc-10 mechanical migrator**: rewrites Reagent view code into
[`re-frame.ui`](../../implementation/ui) compiled-view code, per the canonical
`MIG-01…35` rule table
(`ai/findings/new-substrate-synthesis/prep/w1-migrator-rule-table.md`, the source
of record for the obligation). This is the first S6 adoption workstream (W1 in
`11-adoption-workstreams.md`); the repo migration is its first consumer, and it
ships alongside the W2 migration skill — the tool's flag vocabulary and the
skill's Type A/B guidance are kept in agreement.

## What it is

A **scanner + conservative codemod** that operates purely on source *text* via
[rewrite-clj](https://github.com/clj-commons/rewrite-clj) (a zipper over the node
tree, so formatting and comments survive). It never loads, requires, or executes
re-frame2 / re-frame.ui, so it runs against any Reagent corpus on a bare JVM with
no re-frame2 build in the loop. It is bundle-isolated by construction: not on any
shadow-cljs `:source-path`, and nothing under `implementation/` requires it.

## Tiers (the rule table's M / D / R)

- **M — mechanical.** The migrator rewrites the source (Form-1 → `ui/defview` +
  map-arg call sites, `@(subscribe …)` → `(sub …)`, dispatch-lifting, key-meta →
  prop, `[:>` → direct head, prop respelling, mount, `capture-frame` → `(frame)`,
  `dangerouslySetInnerHTML` → `ui/html`, callback ref → `ui/raw-fn`, adapter boot,
  `ui/spread`, …).
- **D — guided.** A decision is the human's; the migrator emits a **flag** with
  the prepared flag text (and a suggested rewrite where the table gives one),
  never an auto-rewrite. `MIG-27` is non-gating (C-13a: a plain fn prop on an
  internal view is legal and opaque).
- **R — reject.** No compiled / ruled equivalent exists (`add-watch` store, dynamic
  tag head, re-com wrapper, introspection / scheduler pokes, effectful sub body);
  the migrator emits a reject flag.

## The whole-view law (§Ordering 1)

The unit of migration is the **whole view**. Each view candidate is gate-scanned
first; if **any** gating rule hits (state / lifecycle decisions, rejects, no-ruled-
spelling cases), the view is left unconverted on the compat tier and its findings
reported. There are no half-migrated bodies. Re-running the tool over migrated
code is a no-op (idempotent).

## Usage

```bash
# scan a file/dir set, print findings (rule id, coords, tier, action, flag text)
clojure -M:run src/

# dry-run the mechanical rewrite (print the would-be findings)
clojure -M:run --rewrite src/my_view.cljs

# rewrite in place
clojure -M:run --rewrite --write src/my_view.cljs
```

Programmatic:

```clojure
(require '[re-frame.ui.migrator :as m])
(m/scan-string  s opts)     ;=> [finding …]
(m/rewrite-string s opts)   ;=> {:source out :findings [finding …]}
(m/scan-file  path) (m/scan-paths paths)
(m/rewrite-file! path {:write? true})
```

A `finding`:

```clojure
{:file "…" :line 42 :col 3
 :rule "MIG-04" :tier :M :action :rewrite   ; :rewrite | :flag | :reject
 :held? false            ; detected in a gated view; not applied
 :note "prepared flag text (D/R) or short description (M)"
 :suggest "suggested rewrite text or nil"}
```

## Staged targets

Every rewrite target is a shipped `re-frame.ui` export **except** the SSR
serialisation path (`re-frame.ssr/emit-ui-tree`, S5 — `MIG-23` stays a flag) and
the outward `ui/->react` bridge (S6). `ui/sub` is arity-1, so `MIG-03`
(explicit-frame ops) stays a flag.

## Tests

```bash
clojure -M:test    # one+ fixture per MIG rule + adversarial cases + idempotence
```

Registered in `scripts/test-jvm-tools.sh` (the JVM tool matrix).
