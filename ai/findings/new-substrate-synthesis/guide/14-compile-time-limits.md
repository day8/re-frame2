# 14 — What the compiler forbids

`defview` does not *interpret* hiccup at runtime. It **lowers** the template at
build time into direct element construction (and a JVM twin of the same AST). That
is why there is no interpreter in your bundle, why Xray can list a view's event
sites without running it, and why client and server cannot drift.

The price is a **closed template grammar**. Anything the compiler cannot see through
is a build failure — with a message that names the fix — not a silent runtime
surprise. This page is the catalogue of those walls, and the escapes that still
exist when you genuinely need them.

Read it when a compile error surprises you, or when you are evaluating whether the
trade fits your app. For the machinery behind the walls, see
[12](12-how-it-works.md).

---

## The one-sentence rule

> If the compiler cannot prove the structure, the reactive sites, and the keys of a
> piece of markup **at compile time**, that spelling is illegal in a `defview` body.

"Prove" means: after normalising the control forms it understands (`let`, `if`,
`when`, `cond`, `case`, `for`, …), every tag head, every `sub`/`lease` site, and
every list identity is finite and visible. No runtime-only structure. No infinite
site tables. No unaudited macros that might inject a `sub` the manifest never saw.

---

## Template structure

| You cannot | Why | Do this instead |
|---|---|---|
| Dynamic tag heads — `[(if big? :h1 :h2) title]` | The emitter must know the element constructor at compile time | Two branches, or bind attributes dynamically on a fixed tag |
| Markup-returning `map` — `(map row-view rows)` | Lazy seqs and opaque iterations hide keys and sites | `(for [r rows] [row-view {:key …}])` or extract a child view |
| Unkeyed list items in a `for` | List identity is a proof obligation, not a console warning | Put `{:key …}` on every list child — missing key = **build failure** |
| Keywords in child position — `[:div :oops]` | Silent-text footguns; literal ones fail at build, dynamic ones at dev render | Strings, numbers, nil/false, or nested markup |
| Raw lazy seqs reaching React | Same as `map` — structure must be a realised, keyed tree | `for` → array, or a child view per item |
| Runtime-assembled root forms at `mount` / `render!` / `ui.test/render` | Root identity and frame plans are compile-time contracts | Literal root vectors only; wrap multi-view compositions in one `defview` |

**Still free:** ordinary branching and binding. The compiler *normalises* `if` /
`when` / `cond` / `case` / `let` / `for` into its AST — you are not forced into a
single linear template.

```clojure
;; ✓ branches are fine
(ui/defview heading [{:keys [big? title]}]
  (if big?
    [:h1 title]
    [:h2 title]))

;; ✗ dynamic head is not
;; [(if big? :h1 :h2) title]
```

---

## Reactive sites must be finite

Every `(sub …)` and `(lease …)` is a **compile-indexed site** on the view's single
React bridge ([12](12-how-it-works.md)). Sites may sit in branches — `sub` is not a
hook — but they may not appear inside an unbounded loop body.

| You cannot | Why | Do this instead |
|---|---|---|
| `(sub …)` inside `(for …)` | Infinite / data-dependent site count | Extract a keyed child view; each instance owns its finite sites |
| `(lease …)` inside `(for …)` | Same | Same — lease lives on the child that is visible |
| Unaudited macros around `sub`/`lease` | Expansion could inject, duplicate, or defer a site the manifest never sees | Supported core forms (`and` `or` `when` `cond` `->` …), ordinary functions, or a `defview` boundary |

```clojure
;; ✗
(for [id ids]
  [:li (sub [:orders/by-id id])])

;; ✓
(ui/defview order-row [{:keys [id]}]
  [:li (:title (sub [:orders/by-id id]))])

(ui/defview order-list []
  [:ul (for [id (sub [:orders/visible-ids])]
         [order-row {:key id :id id}])])
```

Conditional reads remain legal:

```clojure
(let [details (when expanded? (sub [:orders/details id]))] …)   ; ✓
```

---

## Expression positions: closed macro grammar

Prop values, condition tests, `for` collections, and other expression positions hold
ordinary Clojure *values* — but their lexical *syntax* is audited so every reactive
call is visible.

| You cannot | Why | Do this instead |
|---|---|---|
| Arbitrary macros in expression position (core or user) | Unaudited expansion can hide reactive calls | Supported transparent core macros, ordinary fns, compute above the template, or another `defview` |
| `sub` / `lease` inside binding patterns or `:or` defaults | Sites must be statement-shaped, indexable | Bind in the body with `let` / `when` |

Accepted in those positions: special/binder forms (`quote`, `fn`, `let`/`loop`/
`letfn`, `try` — with the reactive restrictions above), the audited core set
(`or` `and` `when` `when-not` `cond` `->` `->>` `some->` `some->>` `cond->`
`cond->>`), and ordinary function calls. Everything else is
`:rf.ui.compile/unsupported-form`.

---

## Event handlers and lists

| You cannot | Why | Do this instead |
|---|---|---|
| Loop-capturing vector handlers — `{:on-click [::open (:id t)]}` inside `for` | Per-row committed slots need per-row view instances | Extract a keyed child view that closes over `id` in *its* site |
| Placeholders in a *runtime-forwarded* vector | Placeholders are compiled, not interpreted | Build the literal vector at the DOM site, or use `ui/event` |
| Bare fn on a **foreign** component's callback prop | Invoker phase is unknown — cannot pick the right committed/render form | `ui/event` / `ui/handler` / `ui/render-fn` / `ui/raw-fn` ([04](04-events.md)) |
| Bare fn as `:ref` | `:ref` is not an event property; React calls it during commit with no committed-slot promise | `(ui/raw-fn set-node)` or an object ref |
| An open placeholder vocabulary (`:rf.ui/event`, form-data bags, …) | Host objects and non-EDN payloads are not data | `ui/event` for live-event / file / form mechanics |

**Still free:** a *capture-free* literal vector in a loop
(`{:on-click [:list/refresh]}`) — one shared callback across rows.

---

## Props, children, and identity

| You cannot | Why | Do this instead |
|---|---|---|
| An app prop named `:key` | `:key` is React's list-identity slot | Rename the prop |
| Pass children to a view that did not declare `:children` | Child acceptance is opt-in and compile-checked | Declare `:children` in the props destructure |
| `#js` / hand-rolled camelCase on compiled DOM paths | Conversion is the compiler's job (same table on the server) | Keyword props; let the emitter convert |
| Opt out of view memoisation | Correctness and performance assume value-equal props | Fix inputs (narrow props, data handlers) — a view that "must always re-render" is reading the wrong things |

---

## What is *not* a compile-time wall (but feels like one)

These are real limits of the model, not of the lowerer — listed so you do not hunt
for a forbidden form that is actually a design choice:

| Limit | Home |
|---|---|
| No `:on-mount` / lifecycle-as-event | Domain transitions + `effect` — [04](04-events.md), [03](03-state.md) |
| No cross-frame reads in application code | Frames are isolated — [05](05-frames.md) |
| No fetching inside the view | Events + resources + `lease` — [07](07-servers.md) |
| No fifth state input (ratoms, external stores, …) | Four inputs only — [03](03-state.md) |
| Wave-2 surfaces not in v1 (`ui/portal`, `ui/element`, `data/render`, …) | Demand-gated; see below |

---

## Escapes when you genuinely need runtime structure

The grammar is closed *on the happy path*. The library still has explicit doors —
each visible, attributable, and (where relevant) slower on purpose:

| Need | Escape | Notes |
|---|---|---|
| A React element a foreign lib already built | `(ui/raw el)` | Boundary, not a template feature |
| Runtime-chosen foreign component head (until wave-2) | `ui/raw` of an element you construct | `ui/element` is wave-2 |
| CMS / schema-driven trees (full interpreter) | `re-frame.ui.data/render` | **Wave-2**, separate artefact; cost is visible |
| Dynamic prop maps | `ui/spread` | Runtime conversion on the *same* rule table as the compiler |
| Unescaped HTML you vouch for | `ui/html` | Explicit at the call site |
| Host-only leaf under SSR | `ui/client-only` | Fallback is plain markup — [11](11-ssr.md) |
| Logic the template must not see | Ordinary function, or another `defview` | The preferred recovery for unsupported macros |

```clojure
;; guide:no-fixture — wave-2, does not ship in v1
(require '[re-frame.ui.data :as data])
(data/render tree-from-server)   ; opt-in interpreter; not the default path
```

If an escape starts to look like the main path through your app, the design is
probably wrong for this library — or you want a foreign React island with a thin
`ui/raw` boundary, not a `defview` body full of runtime structure.

---

## How errors show up

- **Build time** for structure and site rules (dynamic heads, loops, keys, unsupported
  forms, missing required props on literal call sites, …). Messages name the fix and
  usually carry file:line.
- **First dev render** for things only a live value can prove (schema failures on
  dynamic props, unknown event ids, a dynamic child that turns out to be a keyword).
- **Production** does not re-teach you — the illegal form never compiled.

A greppable id such as `:rf.ui.compile/unsupported-form` or
`:rf.ui.compile/bad-test-root` is part of the contract; see [09](09-debugging.md) for
the broader failure catalogue.

---

## Why accept this?

Because the other pages' promises are not slogans:

- **No interpreter in the bundle** — [10](10-performance.md)
- **Correct memoisation by default** — props and handlers are values the compiler
  can see — [02](02-views.md), [10](10-performance.md)
- **Static interaction surface** — Xray and headless tests read event vectors without
  executing them — [08](08-testing.md), [09](09-debugging.md)
- **One template, two emitters** — client and server share the AST — [11](11-ssr.md)
- **Finite ownership** — every `sub`/`lease` is a known slot; abandoned renders cannot
  leak — [12](12-how-it-works.md)

The closed grammar is the bill for that machinery. When a compile error fires, it is
usually the compiler refusing to ship a view it cannot defend.
