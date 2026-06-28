# reg-machine — declaring a state machine

## When to load

Reach for this leaf when authoring a `rf/reg-machine` call: the declaration map's keys, the `:guards` / `:actions` lookup tables, how a machine is dispatched into. For parallel regions, tags, `:spawn`, history states, or cancellation, see the sibling leaves (`regions.md`, `tags.md`, `spawn.md`, `history.md`, `cancellation.md`).

## Mental model — think in xstate, then map onto re-frame2

**Standing advice for every machine: sketch it the xstate way (states, transitions, guards, actions, `context`, `invoke`, parallel states, final states), then translate each piece into its re-frame2 equivalent.** xstate is the widely-known JS FSM model and well-represented in your training data.

> **Parity reference is the XState v6 direction (EP-0029)**, not v5. v6 is on the `alpha` dist-tag but its alpha.1/alpha.2 surface is settled enough to act on. The posture is **directional, not exact-alpha-chasing**: re-frame2 aims at v6's design principles (plain functions over declarative topology, broader optional schemas, explicit timeouts, choice states, internal events) and rejects exact compat with an alpha. Many re-frame2 divergences are *toward* v6 — it removes the v5 helper creators (`assign`, `sendTo`, `raise`, `enqueueActions`, `and`/`or`/`not`/`stateIn`) and the `setup()` registry, which re-frame2 never had. When your training recalls a v5 helper creator, reach for the re-frame2 data-first form below, not a Clojure clone.

Most concepts map cleanly. A handful of slots re-frame2 **deliberately renames or omits** — intentional (the spec documents each), and exactly where xstate-trained intuition steers you wrong. The table below is the translation key; watch the flagged divergence rows.

| xstate concept | re-frame2 equivalent | Notes / deliberate divergence |
|---|---|---|
| **states** (`state.value`) | `:states` map + the snapshot's `:state` slot | Flat → single keyword; compound → vector path; parallel → region-name→keyword map. Convergence. |
| **transitions** (`on: { EVENT: ... }`) | `:on {event-keyword transition-spec}` on a state node | Convergence. Bare-target / explicit-map / guarded-vector forms. |
| **guards** (`guard` / named guards) | `:guard` on a transition + the top-level `:guards` map | Convergence on the *name*. **Divergence:** no `{and: [...]}` compound-guard data form — compose with one fn or one named registered compound. Guards receive one context map `{:keys [data event state meta]}` and destructure what they need. |
| **actions** (`actions` / named actions) | `:action` / `:entry` / `:exit` + the top-level `:actions` map | Convergence on the *name*. **Divergence:** no action-vector `[a1 a2 a3]` per slot — one fn or one named registered compound. `:entry`/`:exit` are a single fn or single keyword, never vectors. |
| **`assign({...})`** | action returns `{:data new-data}` (and/or `{:fx [...]}`) | **Divergence (name/shape):** no `[:assign {...}]` form. Symmetric with `reg-event`'s `{:db :fx}` return. The invariant matches xstate's `assign` though: callbacks may only update `:data` — they cannot nudge the machine into an undeclared state. |
| **`context`** (extended state) | `:data` (the machine's private map, distinct from `app-db`) | **Divergence (name):** re-frame2 calls the slot `:data`, tracking FSM / `gen_statem` "state data" vocabulary and avoiding re-frame's already-overloaded "context" (interceptor pipeline + React context). |
| **typed `context`** (v6 `schemas`) | `[:schemas :data]` (the machine-level `:schemas` map's data-context schema; EP-0029 A3) | Convergence on the role — both declare the context's shape and make it tool-renderable. **Divergence (enforcement):** XState's typed context is compile-time-only and erased at runtime; re-frame2's `[:schemas :data]` is an *actually-running* validation in dev (via the optional registered validator, e.g. Malli; and an opt-in at production boundaries), at zero production cost. **Divergence (name):** re-frame2 calls the slot `:data` (not `context`), so the schema lives at `[:schemas :data]`. See §Declaring a `[:schemas :data]` schema. |
| **`invoke`** (state-bound child actor) | `:spawn` (and `:spawn-all` for fan-out-and-join) | **Divergence (name):** the most semantically-loaded slot is renamed on purpose, to break the "almost-correct xstate code" trap and align with the imperative `:rf.machine/spawn` fx. No `:onSnapshot`/`autoForward`/multiple-`:invoke`-per-state. See `spawn.md`. |
| **`invoke onError`** (child→parent failure **transition**) | `:spawn`'s `:on-error` transition + `:error? true` final leaf | Convergence — re-frame2 ships `invoke onError` first-class as a control-flow **transition** (not observability-only). The child designates an error terminal with `:final? true :error? true`; the parent's `:spawn` declares `:on-error` (an `:on`-shaped transition spec). See `spawn.md` §`:on-error`. |
| **`onDone`** (child→parent completion) | `:final?` leaf + parent `:spawn`'s `:on-done` + `:output-key` | Convergence — re-frame2 ships first-class final-state-with-parent-notification. See `spawn.md`. |
| **`output`** (typed completion output) | `[:schemas :output]` validating the `:output-key` payload (EP-0029 A8) | Convergence on the role — both declare the completion-output shape. **Divergence:** re-frame2 keeps completion *event-shaped* (no long-lived `snapshot.output`); `[:schemas :output]` validates the `:output-key` payload *as it flows* at finalize time, best-effort fail-loud (`:where :machine-output`, `:rollback? false`). See §Declaring a `[:schemas :output]` schema. |
| **parallel states** (`type: 'parallel'`) | `:type :parallel` + `:regions {...}` | Convergence (name + concept). `:data` shared; `:tags` is the union across active regions. See `regions.md`. |
| **history states** (`type: 'history'`, `history: 'deep'`) | `:type :history` pseudo-state under a compound's `:states` (`:deep?` / `:default-target`) | Convergence (name + concept). Shallow by default; `:deep? true` for deep. Recording rides the snapshot's `:rf/history` slot — so undo / time-travel / SSR get it free. See `history.md`. |
| **final states** (`type: 'final'`) | `:final? true` on a leaf state | Convergence on the concept. **Note the divergence:** a `:final?` singleton (or every-region-final parallel machine) **auto-destroys** — "final means final." Omit `:final?` for a persistent terminal state. See `spawn.md`. |
| **tags** (`tags: [...]`) | `:tags #{...}` on a state node + `machine-has-tag?` | Convergence. See `tags.md`. |
| **eventless / always transitions** | `:always [{:guard ... :target ...} ...]` | Convergence (re-frame2's term for xstate/SCXML transient transitions). |
| **delayed transitions** (`after`) | `:after {<ms> transition-spec}` | Convergence on the name. No recurring timers / pause-resume in v1. |
| **state / actor timeouts** (`timeout` / `onTimeout`) | `:timeout <ms-or-ISO-8601>` + `:on-timeout <transition>` on a state, or on a `:spawn` spec | Convergence on the concept (a named deadline that lowers onto `:after`). **Divergence:** the duration is integer-ms **or** an ISO-8601 string (`"PT5S"`); the XState `"5s"`/`"10ms"` readable shorthand is **rejected**. |
| **choice / transient states** (`type: 'choice'`) | `:type :choice` + `:choice [{:guard ... :target ...} ... {:target <default>}]` on a state | Convergence on the concept (an immediate decision node that resolves on entry to the first guard-passing candidate; lowers onto `:always`). **Divergence:** the routing is a declarative candidate **array**, NOT XState's `choice`-*function* (a function may never be the edge). A choice state must include an unguarded default candidate and only routes (no `:entry` / `:on` / `:after` / `:timeout` / `:spawn` / …). |
| **`raise` (self-event)** | `:raise` inside an action's `:fx` | **Divergence:** sugar for atomic self-dispatch — there is no per-actor mailbox to insert in front of. |
| **internal events** (v6 `internalEvents`) | top-level `:internal-events #{:tick …}` (a **set** of event-ids) | Convergence on the concept (private events the machine raises + handles itself; an external dispatch of one is refused at the machine dispatch boundary, while an internal `:raise` of it is handled normally). **Divergence:** the declaration is a Clojure **set** (`#{:tick}`), NOT XState's array — membership is the natural shape, order irrelevant, duplicates impossible. A reserved framework event (e.g. the synthetic creation marker) cannot be declared internal. |
| **`sendTo` / `sender` (reply to a request)** | include the reply event in the request vector | **Divergence:** no new API; the event vector carries its own reply target. |
| **`ActorRef` runtime objects** | snapshots at `[:rf.runtime/machines :snapshots <id>]` in the runtime-db partition | **Divergence (architecture):** data-oriented, agent-friendly, no live-object leak footguns. Read via `(subscribe [:rf/machine <id>])`. |
| **`setup({actors, guards, actions})`** | per-machine `:guards` / `:actions` maps in the spec | **Divergence:** machine-scoped (not globally registered) — each machine has its own guard/action namespace, validated at registration; cross-machine reuse is via plain Clojure vars. |
| **three creation modes** (`createActor` / `invoke` / `spawn`) | one mechanism, two patterns: singleton via `reg-event`, dynamic via `:spawn` / `[:rf.machine/spawn ...]` | **Divergence:** lifetime is encoded by the snapshot's presence in the runtime-db partition (`[:rf.runtime/machines :snapshots <id>]`) + registration lifetime, not by which constructor you call. |

The deliberate-divergence rows are catalogued in Spec 005 §Lessons from xstate and §Deliberate omissions vs xstate (and the full table in CP-5-MachineGuide §Lessons from xstate). When you reach for an xstate slot that isn't in the table — an action vector, `{and: [...]}`, multiple `:invoke` per state — that's a signal to stop and check the divergence rows rather than assume parity. (`invoke onError` *is* in the table — it maps to `:spawn`'s `:on-error` transition; don't hand-roll a dispatch-back where the declarative transition fits.)

## Canonical signature

```
(rf/reg-machine machine-id machine-map)
(rf/reg-machine machine-id opts machine-map)
```

`opts` is the optional registration-metadata map — the canonical Spec 001 MIDDLE slot. It carries the event-vector `:schema` (a Malli validator for the OUTER event vector dispatched at `[machine-id [...]]`, checked at `:where :event`), not the machine's `:data` shape (that is the spec map's `[:schemas :data]`). The framework-owned `:rf/machine?` / `:rf/machine` keys are stamped by the registration home and MUST NOT appear in `opts`.

`reg-machine` is a macro (in `re-frame.core`) that stamps source coords at the call site and registers the machine as a `:event` handler whose registration metadata carries `:rf/machine? true`. The underlying registration fn `reg-machine*` lives in `re-frame.machines.lifecycle-fx.registration` (it is **not** re-exported under `re-frame.core` — `facade?=false`, front-porch shrink; reach for it as `re-frame.machines/reg-machine*`, which takes the same `(machine-id opts machine-map)` middle-slot shape). The machine **is** an event handler — dispatch `[machine-id [:event-name & args]]` to drive it.

The `day8/re-frame2-machines` artefact must be on the classpath and `re-frame.machines` required at app boot; without it, calls throw `:rf.error/machines-artefact-missing` (the late-bind guard in `re-frame.core-machines`).

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

The machine map's top-level keys are documented in Spec 005 §Transition table top-level keys: `:initial` (the entry state for non-parallel machines), `:data` (initial shared data), `:schemas` (the machine-level schema map — `[:schemas :data]` is the optional validator for `:data`; see §Declaring a `[:schemas :data]` schema), `:guards` and `:actions` (named lookup tables), `:states` (the transition table), and the optional `:internal-events` set (see §Private `:internal-events`). For parallel machines, `:type :parallel` + `:regions` replaces `:initial` + `:states` — see `regions.md`.

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

There is **no positional `(data event)` arity and no opt-in 3-arity escape hatch** — the runtime always delivers the full context map and the destructure pattern decides what's bound. `:state` and `:meta` are available for introspection with no flag (Spec 005 §Snapshot introspection — `:state` / `:meta`). The uniform single-map shape is deliberate: it eliminates the paste-from-`:guard`-into-`:on-spawn` trap (an `id` silently bound to the event vector, or vice-versa) that slot-specific positional signatures would create.

## Declaring a `[:schemas :data]` schema

A machine's `:data` slot is its *context* in xstate terms — the value it carries across transitions. A machine spec MAY declare an optional machine-level **`:schemas`** map (EP-0029 A3, the v6-direction successor to v5's `types: {} as …`); its **`[:schemas :data]`** entry is the validator for that `:data`. The map is unqualified, like `:data` / `:guards` / `:actions`:

```clojure
(def AuthData
  [:map
   [:retries :int]
   [:token   {:sensitive? true} [:maybe :string]]])

(rf/reg-machine :session/auth
  {:initial :anon
   :data    {:retries 0 :token nil}
   :schemas {:data AuthData}
   :states  {:anon           {:on {:login :authenticating}}
             :authenticating {...}
             :authed         {...}}})
```

The data-context schema lives at `[:schemas :data]` (not XState v6's `schemas.context`) because re-frame2 calls the slot `:data`, not `context` — the schema names exactly what it validates. The schema governs the user-domain `:data` only: the snapshot's `:state` is validated structurally at registration (an unknown transition target fails with `:rf.error/machine-unresolved-target`), and the reserved `:rf/*` snapshot slots are framework-owned.

`:schemas` is a **closed** sub-key map. `:data` and `:output` are the live, wired categories; `:events`, `:tags`, and `:meta` are accepted declaration-only categories (abstract values, no wired behaviour yet). An unknown sub-key — including `:input` — fails loud at registration with `:rf.error/machine-bad-schemas-key`; a non-map `:schemas` fails with `:rf.error/machine-bad-schemas`.

**Validation is optional and schema-library-agnostic.** The `[:schemas :data]` value is opaque: machine core requires neither Malli nor any other schema library. Validation runs through an optional registered validator adapter (Malli is the framework default); a project with no adapter still uses the `:schemas` grammar at zero validation cost.

**What it buys you — two things (and a third surface declares snapshot redaction):**

1. **Validation.** In dev builds (`re-frame.interop/debug-enabled?` true) the runtime validates `:data` against the schema at every macrostep-commit boundary, at bootstrap, and at spawn. A violation emits `:rf.error/schema-validation-failure` with `:where :machine-data` and rolls back the whole cascade (same lifecycle position and rollback as the `:where :app-db` check). Under `:advanced` + `goog.DEBUG=false` the validation site DCEs to a no-op — dev-only by default; for production validation at a system boundary (e.g. an SSR-hydrate restoring a machine snapshot from the wire) reach for the `:rf.schema/at-boundary` interceptor on that event.

2. **Declared context shape.** With `[:schemas :data]` present, a machine visualiser renders the context shape **authoritatively** from the declared `[:map [k type] …]` entries — the re-frame2 analog of XState's typed context (Stately's `Context:` header). Without a schema, a viz can only *infer* key→type from one sample of the initial `:data`, which a partial map can mislead.

> **The `[:schemas :data]` `:sensitive?` prop does NOT redact snapshot egress.** A `:sensitive?` / `:large?` Malli prop on a `:data` slot drives **only** the schema's own *validation-failure-trace* redaction — when a `:rf.error/schema-validation-failure` record ships, the marked slot is redacted in *that record*. It does **not** redact `:data` in the `:before` / `:after` / `:snapshot` slots of a normal transition trace. Durable machine `:data` snapshot redaction is declared on the machine definition (below).

### Declaring a `[:schemas :output]` schema — the completion payload

The other wired `:schemas` category is **`:output`** — it validates a machine's **completion-output payload**: the value a finishing machine selects from its final state's `:data` via `:output-key` (the `result` its parent's `:spawn :on-done` receives). This is the xstate **`output`** concept (v6 `schemas.output`), but re-frame2 keeps completion *event-shaped* — there is **no** long-lived `snapshot.output` slot — so `[:schemas :output]` schemas the value *as it flows*, validated at the moment the machine reaches its final state:

```clojure
(rf/reg-machine :auth-flow
  {:initial :running
   :schemas {:output :string}                 ;; the :output-key payload must be a string
   :states  {:running {:on {:ok {:target :done
                                  :action (fn [{data :data ev :event}]
                                            {:data (assoc data :token (second ev))})}}}
             :done    {:final?     true
                       :output-key :token}}})  ;; ← this :token value is validated
```

Unlike the `:data` boundary, output validation is **best-effort fail-loud**: the machine has *already* finished when its output is checked, so a violation emits `:rf.error/schema-validation-failure` with `:where :machine-output` (and `:phase :completion`) **loudly**, but the completion still flows with **nothing to roll back** (`:rollback? false`) — a schema typo surfaces without deadlocking a finishing machine. Same dev-only posture as `:data` (`debug-enabled?`-gated, DCE'd in production) and the same optional validator adapter. A machine with no `[:schemas :output]` reports its `:output-key` payload unvalidated.

### Redacting `:data` at snapshot egress — the machine declaration

Durable machine `:data` classification travels with the **machine definition**, declared as top-level `:sensitive` / `:large` keys on the `reg-machine` spec, **projection-relative to one actor snapshot's `:data`**:

```clojure
(rf/reg-machine :session/auth
  {:sensitive [[:data :token]]        ;; redacts :token in every actor's snapshot egress
   :large     [[:data :avatar]]
   :initial   :anon
   :data      {:retries 0 :token nil}
   :schemas   {:data AuthData}        ;; still VALIDATES :data (and drives validation-FAILURE-trace redaction)
   :states    {...}})
```

The runtime **lowers** each declared path per spawned actor at spawn / first-boot — re-rooting `[:data :token]` to the instance's absolute snapshot path in the per-frame elision registry — and **drops** it on destroy (any cause). So a `:spawn`-generated `<type>#n` is classified with **zero per-instance author code**, exactly as XState carries `context` shape on the definition and applies it per actor. The marked slot renders as `:rf/redacted` (sensitive) or `:rf.size/large-elided` (large) in every `:rf.machine/transition` / `:rf.machine/snapshot-updated` egress (the `:before` / `:after` / `:snapshot` slots) before crossing the trace bus / epoch-capture / AI-MCP boundary. A malformed declaration is rejected fail-loud with `:rf.error/invalid-machine-classification`.

The `:sensitive` / `:large` declaration is a **top-level key on the machine spec map** — projection-relative, value-independent, per-instance. The framework-wide handler-metadata `:sensitive?` annotation that once stamped a whole cascade is **removed** (classification is path-based, owner-declared). Classification is **fail-open**: an undeclared `:data` slot ships raw, and there is no propagation, so a secret a guard copies into another slot ships raw until you declare *that* slot. Full model: [`../cross-cutting/privacy-and-elision.md`](../cross-cutting/privacy-and-elision.md).

A machine with **no** `[:schemas :data]` schema is unchanged: its `:data` is free-form and unvalidated, and a viz infers (and badges as inferred) its context shape.

See Spec 005 §Schema validation and §`[:schemas :data]` is the re-frame2 analog of XState typed context for the full contract.

## Subscribing to a machine

The framework ships two subs:

```clojure
@(rf/subscribe [:rf/machine :my/feature])              ;; the whole snapshot
@(rf/machine-has-tag? :my/feature :loading)            ;; tag containment-bit
```

The canonical machine read is the `[:rf/machine machine-id]` subscription vector — it returns the snapshot map `{:state ... :data ... :tags ...}`. `machine-has-tag?` is sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])` — see `tags.md` — and is re-exported from `re-frame.core`.

Project off the snapshot with ordinary `reg-sub`:

```clojure
(rf/reg-sub :feature/data
  :<- [:rf/machine :my/feature]
  (fn sub-data [snap _] (get-in snap [:data :result])))
```

## As an event handler

When a machine drives a discrete event-driven flow (boot, websocket-connection lifecycle), wrap its declaration with `re-frame.machines/make-machine-handler` and register the result under `rf/reg-event`. (The `make-machine-handler` wrapper lives on the owning `re-frame.machines` namespace — it is no longer re-exported from `re-frame.core`, per the front-porch shrink; the `reg-machine` / `defmachine` registration macros stay on the `rf/` façade.) The wrapper produces the event-handler fn that dispatches into the machine on every invocation:

```clojure
(rf/reg-event :app/boot
  (re-frame.machines/make-machine-handler
    {:initial :configuring
     :data    {:phase :configuring :config nil}
     :states  ...
     :guards  ...
     :actions ...}))
```

`make-machine-handler` is the "wrap this machine as an event-handler" sugar. See `references/cross-cutting/api-cheatsheet.md` §Machines for the contract row; `patterns/boot.md` and `patterns/websocket.md` carry worked examples.

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
- **Re-registration replaces.** Last-write-wins, per the standard registrar semantics; the prior snapshot at `[:rf.runtime/machines :snapshots <id>]` survives (the snapshot is in runtime-db, the spec is in the registrar). Hot-reload survives a machine re-declaration.

## Deeper material

For the full transition-table grammar, guard/action effect-map shape, hierarchical state cascading, and machine-snapshot semantics, see `SKILL-REDIRECT.md` → *EP — State machines (005)*.

---

*Derived from the `re-frame.machines.*` sub-namespaces (`transition`, `lifecycle-fx.registration`, …) and `re-frame.core` / `re-frame.core-machines` (the `reg-machine` macro + the `[:rf/machine machine-id]` subscription vector + `machine-has-tag?` sugar) @ main `89bd9c3`. Citations are symbol-level (machines.cljc was split into sub-namespaces); re-verify symbol homes after machine-registration refactors.*
