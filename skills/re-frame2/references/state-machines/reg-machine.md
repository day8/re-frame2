# reg-machine — declaring a state machine

## When to load

Reach for this leaf when authoring a `rf/reg-machine` call: the declaration map's keys, the `:guards` / `:actions` lookup tables, how a machine is dispatched into. For the full xstate→re-frame2 translation catalogue see [`xstate-translation.md`](xstate-translation.md); for the machine `:schemas` contract + snapshot redaction, [`machine-schemas.md`](machine-schemas.md). For parallel regions, tags, `:spawn`, history states, or cancellation, see the sibling leaves (`regions.md`, `tags.md`, `spawn.md`, `history.md`, `cancellation.md`).

## Mental model — think in xstate, then map onto re-frame2

**Standing advice for every machine: sketch it the xstate way (states, transitions, guards, actions, `context`, `invoke`, parallel states, final states), then translate each piece into its re-frame2 equivalent.** xstate is the widely-known JS FSM model and well-represented in your training data. Parity target is XState **v6** semantics (EP-0029), not exact compat with an alpha — many re-frame2 divergences are *toward* v6 (it dropped the v5 helper creators `assign` / `sendTo` / `raise` / `and`/`or`/`not` and the `setup()` registry, which re-frame2 never had).

Most concepts map cleanly. The flags below are where xstate-trained intuition steers you wrong — re-frame2 **renames or omits** the slot:

- **`context` → `:data`.** No `context` slot; the machine's private map is `:data`.
- **`assign({...})` → an action returning `{:data new-data}`** (and/or `{:fx [...]}`). No `[:assign …]` form; symmetric with `reg-event`'s `{:db :fx}` return.
- **`invoke` → `:spawn`** (and `:spawn-all` for fan-out-and-join). One `:spawn` per state (xstate admits a vector); `invoke onError` → `:spawn`'s `:on-error` transition. See [`spawn.md`](spawn.md).
- **No action-vectors, no `{and: [...]}` compound-guard data.** One fn or one named registered compound per `:action` / `:guard` slot.
- **`setup({actors, guards, actions})` → per-machine `:guards` / `:actions` maps.** Machine-scoped, not globally registered; cross-machine reuse is via plain Clojure vars.
- **Timeouts are integer-ms or ISO-8601 (`"PT5S"`), never the `"5s"` shorthand.** `:internal-events` is a **set** (`#{:tick}`), not an array.

The full 28-row translation key — every concept, convergence and divergence, plus history / tags / choice / typed-context / wildcard / `:reenter?` / `stateIn` rows — is the sole carrier at [`xstate-translation.md`](xstate-translation.md). When you reach for an xstate slot that isn't flagged above, check that catalogue rather than assume parity.

## Canonical signature

```
(rf/reg-machine machine-id machine-map)
(rf/reg-machine machine-id opts machine-map)
```

`opts` is the optional registration-metadata map — the canonical Spec 001 MIDDLE slot. It carries the event-vector `:schema` (a Malli validator for the OUTER event vector dispatched at `[machine-id [...]]`, checked at `:where :event`), not the machine's `:data` shape (that is the spec map's `[:schemas :data]`). The framework-owned `:rf/machine?` / `:rf/machine` keys are stamped by the registration home and MUST NOT appear in `opts`.

`reg-machine` is a macro (in `re-frame.core`) that stamps source coords at the call site and registers the machine as a `:event` handler whose registration metadata carries `:rf/machine? true`. The underlying registration fn `reg-machine*` lives in `re-frame.machines.lifecycle-fx.registration` (it is **not** on the `re-frame.core` façade — `facade?=false`; reach for it as `re-frame.machines/reg-machine*`, which takes the same `(machine-id opts machine-map)` middle-slot shape). The machine **is** an event handler — dispatch `[machine-id [:event-name & args]]` to drive it.

The `day8/re-frame2-machines` artefact must be on the classpath and `re-frame.machines` required at app boot; without it, calls throw `:rf.error/machines-artefact-missing` (the late-bind guard in `re-frame.core-machines`).

## Declaration shape

The basic (non-parallel, non-hierarchical) form:

```clojure
(require '[re-frame.core :as rf]
         '[re-frame.machines])     ;; load-time hook registration

(rf/defmachine my-machine
  {:initial :idle
   :data    {:attempt 0 :error nil}

   :guards
   {:has-input?
    (fn guard-has-input? [{:keys [data]}]
      (some? (:input data)))}

   :actions
   {:bump-attempt
    (fn action-bump [{:keys [data]}]
      {:data (update data :attempt (fnil inc 0))})

    :store-result
    (fn action-store [{data :data [_ {:keys [value]}] :event}]
      {:data (assoc data :result value :error nil)})}

   :states
   {:idle
    {:on {:start {:target :working
                  :guard  :has-input?
                  :action :bump-attempt}}}

    :working
    {:on {:succeeded {:target :done    :action :store-result}
          :failed    {:target :idle}}}

    :done {}}})

(rf/reg-machine :my/feature my-machine)

;; Drive it:
(rf/dispatch [:my/feature [:start]])
```

**`defmachine` is `def` for a literal machine map.** Use it for the `def`-then-register shape and `reg-machine` directly when registering an inline literal. The reason is source capture: `reg-machine`'s compile-time literal-walk sees only the symbol `my-machine` at its call site, so it captures **nothing** per-element; `defmachine` walks the literal at the *definition* site and co-locates `:source-coords` / `:source-code` onto each `:guards` / `:actions` / `:on-spawn-actions` entry, so the source travels into `reg-machine` **with the value**. Write plain `def` and the machine still runs — but `(rf/handler-meta :machine-guard [id guard-id])`, the Xray machine inspector and the Epoch machine-cascade have no per-element source to render. Both forms yield identical capture; the choice is only whether the spec value is named. (Spec 005 §Value-registered machines — `defmachine`.)

The machine map's top-level keys are documented in Spec 005 §Transition table top-level keys: `:initial` (the entry state for non-parallel machines), `:data` (initial shared data), `:schemas` (the machine-level schema map — `[:schemas :data]` is the optional validator for `:data`; see [`machine-schemas.md`](machine-schemas.md)), `:guards` and `:actions` (named lookup tables), `:states` (the transition table), and the optional `:internal-events` set (see §Private `:internal-events`). For parallel machines, `:type :parallel` + `:regions` replaces `:initial` + `:states` — see `regions.md`.

## Private `:internal-events`

Declare the events a machine raises and handles **only itself** in a top-level `:internal-events` **set** — machine plumbing that is *not* part of the public event surface. An external dispatch of one (from a view, a test, or another handler) is **refused** at the machine dispatch boundary; an internal `:raise` of it is handled normally.

```clojure
(def gate
  {:initial :waiting
   :internal-events #{:tick}                          ;; private — only the machine raises it
   :actions {:arm (fn [_] {:fx [[:raise [:tick]]]})}  ;; raise it internally…
   :states
   {:waiting {:on {:go {:target :armed :action :arm}}}
    :armed   {:on {:tick {:target :checking}}}         ;; …and handle it with an ordinary :on clause
    :checking {}}})

(rf/dispatch [:my/gate [:go]])    ;; runs :arm → raises :tick → drives :armed → :checking
(rf/dispatch [:my/gate [:tick]])  ;; REFUSED at the boundary — no transition, an error trace fires
```

The `:on {:tick …}` clause is **how** the machine handles the raised event — that is the expected shape, not a collision. `:internal-events` only adds the external-dispatch refusal. Two registration rules fail loud: `:internal-events` must be a **set** of keywords (a vector — XState's array form — is rejected), and no member may be a reserved framework event (e.g. the synthetic creation marker).

## State-node shape

Every state node is a map. Recognised slots (see the `re-frame.machines` façade docstring + `re-frame.machines.transition`, and Spec 005 §State nodes):

- `:on` — a map of `event-keyword → transition-spec` (see Transitions below).
- `:entry` / `:exit` — singular action references or fns, fired on entering / leaving the node.
- `:always` — eventless microstep table (`:always [{:guard ... :target ...} ...]`).
- `:after` — delayed transition table, `:after {<ms-or-sub-vec-or-fn> <transition-spec>}`.
- `:timeout` / `:on-timeout` — a named wall-clock deadline (a positive-integer ms **or** an ISO-8601 duration string such as `"PT5S"` — the XState `"5s"`/`"10ms"` shorthand is rejected). `:timeout` requires `:on-timeout`; it lowers onto the `:after` timer (distinct intent, one mechanism — `:timeout` and `:after` coexist). A `:spawn` spec may carry its own `:timeout` / `:on-timeout` to bound the child's lifetime.
- `:type :choice` + `:choice` — a **transient / choice** state: a decision node that resolves immediately on entry to the first guard-passing candidate (`:choice [{:guard ... :target ...} ... {:target <default>}]`). The candidate vector is declarative data (not a function — that form is rejected) and must include an unguarded default; a choice state only routes (no `:entry` / `:exit` / `:on` / `:always` / `:after` / `:timeout` / `:spawn` / …). Lowers onto `:always`.
- `:spawn` — declarative child spawn (see `spawn.md`).
- `:spawn-all` — spawn-and-join sugar (see `spawn.md`).
- `:tags` — a set of keywords describing this state's per-axis intent (see `tags.md`).
- `:states` + `:initial` — nested compound state (deepest-wins resolution).
- `:type :history` — a **pseudo-state** sibling under a compound's `:states` (carries `:deep?` / `:default-target`, nothing else); a transition target that restores the compound's last-active configuration. Not an occupiable state. See `history.md`.

## Transition shape

The value under an `:on` event keyword is one of:

```clojure
{:on {:start :working}}                              ;; bare target keyword
{:on {:start {:target :working}}}                    ;; explicit map
{:on {:start {:target :working :action :bump-attempt}}}
{:on {:start {:target :working :guard  :has-input?}}}
{:on {:start [{:guard :a? :target :x}                ;; guarded vector — first match wins
              {:guard :b? :target :y}
              {:target :z}]}}
```

The transition's `:target` may be a single keyword (sibling-level) or a vector path (absolute, for cross-level transitions). Per `normalise-on-clause` in `re-frame.machines.transition`.

An `:on` **key** is one of **three event-descriptor tiers**, resolved most-specific-first *at each level* before the walk moves up to an ancestor: the exact event id, the namespace wildcard `:ns/*` (`:mouse/*`), then the total wildcard `:*`. A guard-blocked candidate is not *enabled*, so it falls through to the next-coarser tier at the same level. Add **`:reenter? true`** to make a self / ancestor target **external** (exit + entry fire, `:after` timers restart, `:spawn` children respawn); without it such a target still re-resolves the target's descendants to `:initial`, and only a **targetless** transition leaves the configuration untouched. Both are catalogued in [`xstate-translation.md`](xstate-translation.md).

## Guards / actions — keyword reference or inline fn

`:guards` and `:actions` at the machine top level are lookup tables. Inside an `:on` transition, `:guard` and `:action` accept **either** a keyword that resolves through those tables, **or** an inline fn:

```clojure
;; Inline — preferred only for one-line trivialities.
{:on {:start {:guard  (fn [{:keys [data]}] (some? (:input data)))
              :target :working}}}

;; Keyword reference — the DEFAULT for anything non-trivial, because the
;; registered id is a stable, reusable name that trace events, tools, and
;; AIs can address and jump-to-source against.
{:on {:start {:guard :has-input? :target :working}}}
```

Per the inspectability bias (Spec 005 §Inspectability bias): named entries surface in `:rf.machine/*` trace events as the registered keyword. The bias is **not** about source visibility — an inline fn's `:source-code` text is co-located on its enclosing node in dev, so visualisers and Xray render an inline body fine. The named keyword is the default because the id is a **name** — reusable, addressable, clearer for humans/tools/AIs: it resolves against `:guards`/`:actions` (via `(machine-meta <id>)`), labels a diagram arrow, and can be stubbed by id in tests, where an anonymous closure has no public name. Reach for inline only when the body is a single non-branching expression that gains no meaning from a name.

### Guard / action contract

Every callback receives **one context map** — `(fn [{:keys [data event state meta] cofx :rf.cofx}] ...)` — and destructures the keys it needs. `data` is the snapshot's `:data` slot (a plain map); `event` is the inbound event vector; `state` is the discrete FSM keyword; `meta` is any user `:meta` on the snapshot. The `:rf.cofx` key's **name** contains a dot, so it can't ride inside `:keys` — bind it with the explicit `cofx :rf.cofx` pair (Spec 005 §Guard/action contract). **`:rf.cofx` is the causal recordable-coeffect record (EP-0010 recording / EP-0017 authoring)** — the same flat `{:rf/time-ms …}` map the dispatching event handler saw, surfaced onto the machine ctx so a bare-fn guard/action deciding on a host fact reads `(:rf/time-ms cofx)` not an ambient `js/Date` / `(random-uuid)`. Present when the dispatch carried a causal token; **absent for pure-fn callers** (conformance corpus / JVM fixtures driving the engine without a router coeffect). To have the framework *ensure* a fact, declare it via **consumer attachment** (`:rf.cofx/requires` on the named guard/action entry; the fact arrives flat beside the destructure). Machine `:data` is **durable** (survives snapshot/restore + replay), so any host fact a guard/action folds into `:data` MUST come from this record, never an ambient read — same durable-write rule as event handlers (Spec 002 §Recordable coeffects; Spec 005 §Machines). Actions return `{:data new-data :fx [...]}` (either key optional); guards return truthy/falsey. See `call-guard` / `call-action` in `re-frame.machines.transition`.

**Flat / compound machines get exactly `{:data :event :state :meta}`. A callback running inside a parallel *region* additionally carries `:tags` and `:all-state`** — the machine-wide tag union and the region-name → active-state map, which are re-frame2's cross-region `stateIn` substitute. Those two keys appear **only** for region callbacks (`:all-state` is the region marker), so a flat machine's ctx is unchanged. See `regions.md` §Cross-region coordination.

There is **no positional `(data event)` arity and no opt-in 3-arity escape hatch** — the runtime always delivers the full context map and the destructure pattern decides what's bound. `:state` and `:meta` are available for introspection with no flag (Spec 005 §Snapshot introspection — `:state` / `:meta`). The uniform single-map shape avoids the paste-from-`:guard`-into-`:on-spawn` trap (an `id` silently bound to the event vector, or vice-versa).

## Machine schemas and snapshot redaction

A machine spec MAY declare an optional machine-level **`:schemas`** map (EP-0029 A3) — `[:schemas :data]` validates the `:data` context (the re-frame2 analog of XState typed context), `[:schemas :output]` validates the `:output-key` completion payload. Durable `:data` egress redaction is a **separate** surface: top-level `:sensitive` / `:large` path vectors on the spec, projection-relative to one actor's snapshot. (The Malli `:sensitive?` *prop* inside `[:schemas :data]` redacts only validation-failure traces, not normal snapshot egress.) The full contract — declaration grammar, dev-only validation lifecycle, best-effort output validation, and per-instance classification lowering — is the sole carrier at [`machine-schemas.md`](machine-schemas.md).

## Subscribing to a machine

The framework ships two subs:

```clojure
@(rf/subscribe [:rf/machine :my/feature])                     ;; the whole snapshot
@(rf/subscribe [:rf.machine/has-tag? :my/feature :loading])   ;; tag containment-bit
```

The canonical machine read is the `[:rf/machine machine-id]` subscription vector — it returns the snapshot map `{:state ... :data ... :tags ...}`. `[:rf.machine/has-tag? machine-id tag]` is the companion derived subscription — see `tags.md` — read the same way, with the ordinary `subscribe`; there is no named-read-sugar fn over either.

Project off the snapshot with ordinary `reg-sub`:

```clojure
(rf/reg-sub :feature/data
  :<- [:rf/machine :my/feature]
  (fn sub-data [snap _] (get-in snap [:data :result])))
```

## Driving a machine as a discrete event-driven flow

A machine that drives a discrete event-driven flow — application boot, a websocket-connection lifecycle — needs **no special registration form**. `reg-machine` already registers the machine **as an event handler** (see §Canonical signature above): author the flow with `reg-machine`, then drive it by dispatching the nested `[machine-id [:event-name & args]]` form. A frame's `:initial-events` births it at frame creation with the reserved creation marker:

```clojure
(rf/reg-machine :app/boot
  {:initial :configuring
   :data    {:phase :configuring :config nil}
   :states  {...}
   :guards  {...}
   :actions {...}})

;; drive it — e.g. from the frame's :initial-events:
(rf/dispatch [:app/boot [:rf.machine/start]])
```

When the flow also validates its **outer** event vector, pass the optional metadata MIDDLE slot — `(rf/reg-machine :app/boot {:schema BootEvent} boot-machine)`; when it validates its **`:data`**, declare `[:schemas :data]` inside the spec map (see [`machine-schemas.md`](machine-schemas.md)), which **requires** the `reg-machine` home. `patterns/boot.md` and `patterns/websocket.md` carry the worked flows.

> **Advanced — `re-frame.machines/make-machine-handler`.** The lower-level factory that builds the raw event-handler fn `reg-machine` registers for you (owned by `re-frame.machines`, **not** on the `re-frame.core` façade; the `reg-machine` / `defmachine` registration macros stay on the `rf/` façade). It is a **schema-less escape hatch** for programmatic composition — **not** a normal authoring path. Registering it by hand, `(rf/reg-event id meta (make-machine-handler spec))`, does **not** stamp the `:rf/machine?` / `:rf/machine` registration metadata or the per-element source coordinates that machine introspection, `(machine-meta id)`, visualisers, and Xray resolve through — so the machine is invisible to those tools. And a spec carrying a `[:schemas :data]` schema **throws `:rf.error/machine-schema-requires-reg-machine`** on that path: the post-commit `:data` validator resolves the schema *through* the missing metadata stamp, so it would silently validate nothing — the framework fails loud at construction instead. Reach for it only when you genuinely need the bare handler fn programmatically; author normal machines with `reg-machine`. See `references/cross-cutting/api-cheatsheet.md` §Machines for the contract row.

## Querying registered machines

- `(rf/handler-meta :event :my/feature)` — registration metadata, including `:rf/machine? true`, `:rf/machine` (the spec map), `:ns` / `:line` / `:file`.
- `(re-frame.machines/machines)` — every registered machine-id.
- `(re-frame.machines/machine-meta :my/feature)` — the spec map back.

## Common gotchas

- **The artefact must be loaded.** `(:require [re-frame.machines])` at the namespace declaring `rf/reg-machine` (or at app boot before any machine call). Forgetting it throws `:rf.error/machines-artefact-missing` with `:recovery :no-recovery`.
- **`:rf.machine/*`, `:rf/*`, and `:rf.runtime/*` are reserved.** Names like `:rf.machine/spawn` and the reserved runtime-db children `:rf.runtime/machines`, `:rf.runtime/routing`, `:rf.runtime/elision`, … belong to the runtime (they live in the framework's runtime-db partition, not your app-db). Pick your own feature prefix for event keywords.
- **Callbacks receive one context map, not the raw snapshot.** `(fn [{:keys [data event]}] ...)` — the body inspects `(:input data)`, not `(get-in snap [:data :input])`. `data` is already the snapshot's `:data` slot. Same shape for guards, actions, `:entry`, `:exit`.
- **Actions return an effect map.** `{:data new-data}` (or `{:fx [...]}` or both). Returning a bare data map silently does nothing; `nil` is a no-op.
- **Use `reg-machine` (macro), not `reg-machine*` (fn).** The macro stamps per-element source coords that tools rely on (`re-frame.core` macro layer, Spec 005 §Source-coord stamping). Reach for `reg-machine*` only for programmatic registration with computed ids.
- **Naming the spec? Use `rf/defmachine`, not `def`.** `(def m {…})` + `(rf/reg-machine :id m)` is the one shape that silently loses per-element source — `reg-machine` sees a symbol, not a literal, so there is nothing to walk. `defmachine` is the drop-in `def` replacement that stamps the value itself. It does **not** register; pair it with `reg-machine` as usual.
- **Re-registration replaces.** Last-write-wins, per the standard registrar semantics; the prior snapshot at `[:rf.runtime/machines :snapshots <id>]` survives (the snapshot is in runtime-db, the spec is in the registrar). Hot-reload survives a machine re-declaration.

## Deeper material

For the full transition-table grammar, guard/action effect-map shape, hierarchical state cascading, and machine-snapshot semantics, see `SKILL-REDIRECT.md` → *EP — State machines (005)*.

---

*Derived from the `re-frame.machines.*` sub-namespaces (`transition`, `lifecycle-fx.registration`, …) and `re-frame.core` / `re-frame.core-machines` (the `reg-machine` macro + the `[:rf/machine machine-id]` and `[:rf.machine/has-tag? machine-id tag]` subscription vectors) @ main `89bd9c3`. Citations are symbol-level (machines.cljc was split into sub-namespaces); re-verify symbol homes after machine-registration refactors.*
