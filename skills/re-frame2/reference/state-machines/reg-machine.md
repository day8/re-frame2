# reg-machine — declaring a state machine

## When to load

Reach for this leaf when authoring a `rf/reg-machine` call: the declaration map's keys, the `:guards` / `:actions` lookup tables, how a machine is dispatched into. For parallel regions, tags, `:invoke`, or cancellation, see the sibling leaves.

> **Tip**: stuck on how to model a state shape? Ask yourself *"how would XState do it?"* — XState is in your training data, and re-frame2's machine primitives map cleanly onto its concepts (`:type :parallel` ≈ XState parallel states, `:tags` ≈ XState tags, `:invoke` ≈ XState invoke, `:guards`/`:actions` ≈ XState named guards/actions). Sketch the shape in XState mentally, then translate.

## Canonical signature

```
(rf/reg-machine machine-id machine-map)
```

`reg-machine` is a macro that stamps source coords at the call site and registers the machine as a `:event` handler whose registration metadata carries `:rf/machine? true` (`implementation/core/src/re_frame/core.cljc:634`; the underlying registration fn is `re-frame.machines/reg-machine*`, `implementation/machines/src/re_frame/machines.cljc:2028`). The machine **is** an event handler — dispatch `[machine-id [:event-name & args]]` to drive it.

The `day8/re-frame2-machines` artefact must be on the classpath and `re-frame.machines` required at app boot; without it, calls throw `:rf.error/machines-artefact-missing` (`core.cljc:701`).

## Declaration shape

The basic (non-parallel, non-hierarchical) form:

```clojure
(require '[re-frame.core :as rf]
         '[re-frame.machines])     ;; load-time hook registration

(def my-machine
  {:initial :idle
   :data    {:attempt 0 :error nil}

   :guards
   {:has-input?
    (fn guard-has-input? [data _event]
      (some? (:input data)))}

   :actions
   {:bump-attempt
    (fn action-bump [data _event]
      {:data (update data :attempt (fnil inc 0))})

    :store-result
    (fn action-store [data [_ {:keys [value]}]]
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

The machine map's top-level keys are documented in Spec 005 §Transition table top-level keys: `:initial` (the entry state for non-parallel machines), `:data` (initial shared data), `:guards` and `:actions` (named lookup tables), `:states` (the transition table). For parallel machines, `:type :parallel` + `:regions` replaces `:initial` + `:states` — see `regions.md`.

## State-node shape

Every state node is a map. Recognised slots (see `implementation/machines/src/re_frame/machines.cljc` and Spec 005 §State nodes):

- `:on` — a map of `event-keyword → transition-spec` (see Transitions below).
- `:entry` / `:exit` — singular action references or fns, fired on entering / leaving the node.
- `:always` — eventless microstep table (`:always [{:guard ... :target ...} ...]`).
- `:after` — delayed transition table, `:after {<ms-or-sub-vec-or-fn> <transition-spec>}`.
- `:invoke` — declarative child spawn (see `invoke.md`).
- `:invoke-all` — spawn-and-join sugar (see `invoke.md`).
- `:tags` — a set of keywords describing this state's per-axis intent (see `tags.md`).
- `:states` + `:initial` — nested compound state (deepest-wins resolution).

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

The transition's `:target` may be a single keyword (sibling-level) or a vector path (absolute, for cross-level transitions). Per `machines.cljc:344` (`normalise-on-clause`).

## Guards / actions — keyword reference or inline fn

`:guards` and `:actions` at the machine top level are lookup tables. Inside an `:on` transition, `:guard` and `:action` accept **either** a keyword that resolves through those tables, **or** an inline fn:

```clojure
;; Inline — preferred only for one-line trivialities.
{:on {:start {:guard  (fn [data _ev] (some? (:input data)))
              :target :working}}}

;; Keyword reference — preferred for anything non-trivial, because the
;; registered id appears in trace events and tools can jump-to-source.
{:on {:start {:guard :has-input? :target :working}}}
```

Per the inspectability bias (`SKILL.md` cardinal rule 5 / Spec 005 §Inspectability bias): named entries surface in `:rf.machine/*` trace events as the registered keyword, not as an opaque `#object[Function ...]`. Reach for the inline form only when the body is trivial.

### Guard / action contract

Both fns receive `(data event)` — `:data` directly, not the snapshot wrapper. Actions return `{:data new-data :fx [...]}` (either key optional); guards return truthy/falsey. See `implementation/machines/src/re_frame/machines.cljc:146` (`call-guard`) and `:156` (`call-action`).

A 3-arity escape hatch `^:rf.machine/wants-ctx (fn [data event {:state ... :meta ...}] ...)` exists for introspecting the snapshot's discrete state, but reach for it only when 2-arity cannot express what you need (Spec 005 §3-arity escape hatch). The metadata flag is the explicit opt-in; without it the runtime calls the fn as 2-arity.

## Subscribing to a machine

The framework ships two subs:

```clojure
@(rf/sub-machine :my/feature)                  ;; the whole snapshot
@(rf/has-tag? :my/feature :loading)            ;; tag containment-bit
```

`sub-machine` is sugar over `(subscribe [:rf/machine machine-id])` and returns the snapshot map `{:state ... :data ... :tags ...}`. `has-tag?` is sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])` — see `tags.md`. Both live in `implementation/core/src/re_frame/core.cljc:1076-1098`.

Project off the snapshot with ordinary `reg-sub`:

```clojure
(rf/reg-sub :feature/data
  :<- [:rf/machine :my/feature]
  (fn sub-data [snap _] (get-in snap [:data :result])))
```

## Querying registered machines

- `(rf/handler-meta :event :my/feature)` — registration metadata, including `:rf/machine? true`, `:rf/machine` (the spec map), `:ns` / `:line` / `:file`.
- `(re-frame.machines/machines)` — every registered machine-id.
- `(re-frame.machines/machine-meta :my/feature)` — the spec map back.

## Common gotchas

- **The artefact must be loaded.** `(:require [re-frame.machines])` at the namespace declaring `rf/reg-machine` (or at app boot before any machine call). Forgetting it throws `:rf.error/machines-artefact-missing` with `:recovery :no-recovery`.
- **`:rf.machine/*` and `:rf/*` are reserved.** Names like `:rf.machine/spawn`, `:rf/machines`, `:rf/spawned`, `:rf/after-epoch` belong to the runtime. Pick your own feature prefix for event keywords.
- **Guards see `:data`, not the snapshot.** `(fn [data event] ...)` — the body inspects `(:input data)`, not `(get-in snap [:data :input])`. Same for actions.
- **Actions return an effect map.** `{:data new-data}` (or `{:fx [...]}` or both). Returning a bare data map silently does nothing; `nil` is a no-op.
- **Use `reg-machine` (macro), not `reg-machine*` (fn).** The macro stamps per-element source coords that tools rely on (`core.cljc:634`, Spec 005 §Source-coord stamping). Reach for `reg-machine*` only for programmatic registration with computed ids.
- **Re-registration replaces.** Last-write-wins, per the standard registrar semantics; the prior snapshot at `[:rf/machines <id>]` survives (the snapshot is in `app-db`, the spec is in the registrar). Hot-reload survives a machine re-declaration.

## Deeper material

For the full transition-table grammar, guard/action effect-map shape, hierarchical state cascading, and machine-snapshot semantics, see `SKILL-REDIRECT.md` → *EP — State machines (005)*.

---

*Derived from `implementation/machines/src/re_frame/machines.cljc` (registration + transition table) and `implementation/core/src/re_frame/core.cljc` (the `reg-machine` macro + `sub-machine` / `has-tag?` sugar) @ main `89bd9c3`. Re-verify line numbers after machine-registration refactors (e.g. rf2-oz9t nested-validation).*
