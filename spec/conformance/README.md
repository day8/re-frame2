# Conformance corpus

> **Type:** Reference
> The conformance test suite for re-frame2 implementations. The corpus is the verification mechanism for [Goal 2 — AI-implementable from the spec alone](../000-Vision.md#ai-implementable-from-the-spec-alone): the claim that the spec is sufficiently complete for an AI armed only with `/spec/` + this corpus to produce a working reference implementation in any host language. A spec gap surfaces as a fixture an AI cannot reproduce without consulting outside sources; closing the gap is a spec-level remediation, not an implementation-level one.

## Naming convention

Fixture filenames mirror their `:fixture/id` exactly, with the slash that separates the namespace from the local name rewritten as a hyphen and the rest of the id preserved. A fixture with `:fixture/id :counter/inc-once` lives in `counter-inc-once.edn`. Both filename and id use kebab-case; no underscores.

## What this is

A set of fixture files in EDN format, each describing one canonical interaction. Two complementary fixture shapes cover the spec (full detail in [§Fixture format](#fixture-format)):

- Dispatch-driven fixtures (Mode A) — a frame configuration plus initial `app-db`, a sequence of dispatched events, and the expected emissions after drain: final `app-db`, trace events, effects routed to fx, return values from subscriptions.
- Pure / direct-call fixtures (Mode B) — direct invocations of pure primitives (machine transitions, URL ↔ params helpers, hiccup → HTML rendering) with call-local expectations. No frame, no dispatch loop; JVM-runnable.

An implementation conforms if it produces matching emissions for every fixture in the corpus whose capabilities are a subset of the port's claimed list.

## Why EDN

EDN is the natural data format for the CLJS reference. For other-host implementations:

- A small EDN reader exists in nearly every host language (or can be written in ~200 lines for any language with hash-maps and vectors).
- The structure is host-language-agnostic — keywords map to namespaced strings or branded enums per the host's identity primitive.
- The corpus is itself machine-readable data — implementers in a new language read the corpus, generate host-native test code, and report.

A JSON-translated corpus may be published in the future for hosts where EDN is too much friction; if and when it ships, the JSON form will be mechanically derived from the EDN source so the two cannot drift. Until then, the EDN files in `fixtures/` are the canonical source. Implementors targeting non-EDN hosts either ship a small EDN reader (~200 lines for any host with hash-maps and vectors) or translate the corpus locally as part of harness bootstrap.

The fixtures are plain EDN, so a conforming EDN reader loads every one of them with no resolver and no rewrite: every keyword is written fully qualified — the synthetic machine-timer event is `:rf.machine.timer/after-elapsed`, never Clojure's auto-resolved `::after-elapsed`, which is reader syntax rather than EDN.

## Fixture format

Each fixture is an EDN map. A fixture exercises the spec in one of two modes:

- Mode A — dispatch-driven. A frame is created, a sequence of events is dispatched, and the harness compares the post-drain observables (`app-db`, sub values, trace emissions, effects routed) against a single top-level `:fixture/expect`. Use this mode for event/sub/fx/trace/error semantics that only make sense inside a running frame. Most fixtures in the corpus use this mode.
- Mode B — pure / direct-call. No frame, no dispatch loop. The fixture lists one or more direct calls to a re-frame primitive (for example `machine-transition`, `match-url`, `route-url`, `render-to-string`) and each call carries its own expectation inline. Use this mode for primitives that are pure functions of their inputs — machine transitions, URL ↔ params helpers, hiccup → HTML rendering. JVM-runnable; nothing about the substrate's wiring is exercised.

A fixture chooses one mode; the runner executes whichever of `:fixture/dispatches` and `:fixture/calls` is present. (A few fixtures may include calls after a dispatch sequence to assert pure-function output against the post-drain registry; that is still Mode A — the dispatches are the load-bearing part and `:fixture/expect` is the primary contract.)

### Top-level fixture keys

The fixture grammar, [`fixture-file.edn`](fixture-file.edn), is the closed definition of a fixture: it declares every top-level key, every `:fixture/expect` key, every `:fixture/calls` record and every handler-body step, and annotates each expectation with what it observes and the law it is compared by. Read the key set there; a new setup or expectation key lands in the grammar before any fixture carries it. The keys fall into four groups — metadata (`:fixture/id`, `:fixture/doc`, `:fixture/capabilities`, `:fixture/spec-version`), setup, the run itself (`:fixture/dispatches` for Mode A, `:fixture/calls` for Mode B, `:fixture/clock`) and expectation — and [§Fixture lifecycle](#fixture-lifecycle) says when a harness applies each one.

A harness that meets a key it does not implement **fails the run naming the key** — it must not skip it silently, for the same reason an unrecognised capability fails rather than skipping (see [§Capability tagging](#capability-tagging)). Ignoring a setup key silently changes the scenario; ignoring an expectation key lets a fixture pass having verified nothing. The reference holds itself to this twice: its JVM tests validate every fixture against the grammar, whatever capabilities the fixture claims, and its shared runner grades each expectation with exactly one matcher, tied to the grammar by a test, so an expectation with no matcher fails the fixture on every host.

One key gates rather than sets up: `:fixture/dynamic-host-only? true` marks a fixture asserting a **runtime** validation trace that a statically-typed host claiming schemas through its type system cannot produce — the malformed value never compiles. Such a host filters these out *before* the capability-subset check and records the reason; that is a documented static-host path, not claim-set drift.

### Mode A — dispatch-driven

The classic shape: a starting state (frame configuration plus initial `app-db`), a sequence of events, and one top-level expectation block.

```clojure
{:fixture/id           :counter/inc-once
 :fixture/doc          "Single increment of the counter."
 :fixture/capabilities #{:core/event-handler :core/sub}                ;; per §Capability tagging below — see also §Capability tagging worked example
 :fixture/registry    {:event {:counter/initialise {:doc "Seed."}
                               :counter/inc        {:doc "Increment."}}
                       :sub   {:count             {:doc "Current count."}}
                       :fx    {}}
 :fixture/handlers    {:event {:counter/initialise [[:set [:count] 0]]
                               :counter/inc        [[:update [:count] [:fn :inc]]]}
                       :sub   {:count [[:get [:count]]]}}
 :fixture/frame-config {:initial-events [[:counter/initialise]]}
 :fixture/dispatches   [[:counter/inc]]
 :fixture/expect
 {:final-app-db        {:count 1}
  :sub-values          {[:count] 1}
  :trace-emissions     [{:operation :rf.event/run-start :tags {:rf.trace/event-id :counter/inc}}
                        {:operation :rf.fx/do-fx :tags {}}]
  :effects-routed      []}}
```

The expectation keys inside `:fixture/expect` are partial-match by convention: `:trace-emissions` matches each trace event by its specified keys (absent keys ignored), `:final-app-db` is a submap compare (every declared key must match; extra actual keys are tolerated), `:effects-routed` matches the routed-fx pairs in declaration order. Because `:final-app-db`'s submap match tolerates extras, a negative companion key `:final-app-db-absent` — a vector of `get-in`-shaped paths that must be ABSENT from the final app-db (the tip key not present; a present-with-`nil` leaf counts as present) — pins contracts the positive submap cannot, for example declared-only coeffect delivery (a port that over-delivers undeclared coeffects still passes the positive check but fails the absent-path check). See [§Fixture lifecycle](#fixture-lifecycle) for the full comparison contract.

### The `:fixture/clock` controlled host clock

Some contracts are about **delayed host work** — a machine `:after` timer, an SSR hydration re-arm — and a post-drain expectation block cannot reach them. A trace assertion says a row was emitted; it cannot say the timer behind that row still exists (`:trace-emissions` is an order-preserving **subset** match, so a later cancellation is tolerated), and it cannot say the timer is wired to anything, because a row is not a callback. Waiting out wall time is not an option for a corpus.

`:fixture/clock` closes that gap at the seam Spec 005 §Clock abstraction already names — `re-frame.interop/schedule-after!` and `cancel-scheduled!`. A fixture carrying the key runs its `:fixture/dispatches` with those two primitives replaced by a **recording** realisation, then executes the declared steps against it, before `:fixture/calls` and before `:fixture/expect` is graded. A fixture without the key is untouched and keeps the real host clock.

Everything the steps observe is host-observable at that clock boundary; no port-internal timer table is read. A port that arms through some other internal mechanism conforms so long as it arms through the clock primitive the spec names.

The value is a vector of step maps, executed in order, stopping at the first failure:

| Step | Keys | What it asserts / does |
|---|---|---|
| `:assert-timers` | `:live`, `:delays` | The host holds exactly `:live` timers it armed and has **not** cancelled, armed for exactly `:delays` (in arm order). Either key may be omitted. |
| `:assert-runtime-db` | `:expect`, `:frame` | A **mid-run** runtime-db assertion, submap-compared like `:final-runtime-db`, so a fixture can pin the state *before* a timer expires as well as after. `:frame` defaults to `:rf/default`. |
| `:fire` | `:index` | Invoke the `:index`-th **live** timer's own host callback (default `0`). Whatever that callback does — typically dispatching a synthetic elapsed event — then settles, and the rest of the fixture grades the result. |

`:live` is deliberately *live* rather than *armed*: an arm records a handle, and a `cancel-scheduled!` of that handle marks it dead, so "arms exactly one timer per active declaration" is graded as *still holding one*. A host that arms and immediately cancels holds none and fails, even though its `/scheduled` trace row is identical to a conforming host's. A host that arms nothing holds none either, so a fixture using this step cannot pass vacuously.

```clojure
:fixture/clock
[{:step :assert-timers :live 1 :delays [5000]}
 {:step   :assert-runtime-db
  :expect {:rf.runtime/machines {:snapshots {:auth/probe {:state :waiting}}}}}
 {:step :fire :index 0}]
```

`cross-spec-machine-after-hydration-rearm.edn` is the worked example.

### Mode B — pure / direct-call

For pure primitives — machine transitions, URL helpers, render-to-string — the fixture skips the frame entirely. `:fixture/calls` is a vector of call records; each record names the primitive in `:call`, supplies its arguments, and carries its own expectation alongside (typically `:expect`, or operation-specific keys like `:expect-next-snapshot` + `:expect-effects` for `:machine-transition`).

```clojure
;; Excerpt — full file at fixtures/machine-transition.edn
{:fixture/id           :rf.machine/transition
 :fixture/capabilities #{:fsm/flat}
 :fixture/doc          "Pure machine-transition. Given a definition and snapshot, applying an event yields the next snapshot."

 :fixture/registry
 {:machine-action
  {:traffic-light/log-yellow {:doc "Logs a state transition."}
   :traffic-light/log-red    {:doc "Logs a state transition."}
   :traffic-light/log-green  {:doc "Logs a state transition."}}}

 :fixture/handlers
 {:machine-action
  {:traffic-light/log-yellow [[:fx :log {:level :info :msg "yellow"}]]
   :traffic-light/log-red    [[:fx :log {:level :info :msg "red"}]]
   :traffic-light/log-green  [[:fx :log {:level :info :msg "green"}]]}}

 :fixture/calls
 [{:call                 :machine-transition
   :definition           {:initial :green
                          :data    {}
                          :states  {:green  {:on {:tick {:target :yellow
                                                         :action :traffic-light/log-yellow}}}
                                    :yellow {:on {:tick {:target :red
                                                         :action :traffic-light/log-red}}}
                                    :red    {:on {:tick {:target :green
                                                         :action :traffic-light/log-green}}}}}
   :snapshot             {:state :green :data {}}
   :event                [:tick]
   :expect-next-snapshot {:state :yellow :data {}}
   :expect-effects       [[:log {:level :info :msg "yellow"}]]}

  ;; Unknown event in current state: snapshot unchanged, no effects.
  {:call                 :machine-transition
   :definition           {:initial :green
                          :data    {}
                          :states  {:green {:on {:tick {:target :yellow
                                                        :action :traffic-light/log-yellow}}}}}
   :snapshot             {:state :green :data {}}
   :event                [:emergency-stop]
   :expect-next-snapshot {:state :green :data {}}
   :expect-effects       []}]}
```

Routing and SSR pure-call fixtures use the same shape with different `:call` ops:

```clojure
;; Excerpt — full file at fixtures/routing-match-url.edn
{:fixture/id           :routing/match-url
 :fixture/capabilities #{:routing/match-url}

 :fixture/registry
 {:route
  {:route/article-detail {:path "/articles/:id" :params [:map [:id :string]]}
   :route/search         {:path  "/search"
                          :query [:map [:q :string] [:page {:optional true} :int]]}}}

 :fixture/calls
 [{:call :match-url :url "/articles/intro"
   :expect {:route-id :route/article-detail :params {:id "intro"} :query {} :validation-failed? false}}

  {:call :route-url :route-id :route/article-detail :params {:id "intro"}
   :expect "/articles/intro"}

  ;; Round-trip property: route-url ∘ match-url is identity.
  {:call :round-trip :url "/articles/intro"}]}
```

The `:call` ops, the arguments each record takes and the expectation keys it may carry are the `:conformance/call` entry of [`fixture-file.edn`](fixture-file.edn). The registration-error ops and the image op have sections below. Two more carry a contract beyond their grammar entry:

- `:render-tree-hash` pins the render-tree canonical traversal of [011 §Hydration-mismatch detection](../011-SSR.md#hydration-mismatch-detection). The hash VALUE is a per-host commitment, so `ssr-render-tree-hash.edn` also carries same-hash LAW pairs — attribute-key-order independence and nil pruning — that a port hashing under a different algorithm must still uphold.
- `:derivation-graph` composes the cross-family [derivation/process graph](../Derivations.md#graph-inspection--internal-but-structured) over the host's loaded contributors. It is graded against a SPLIT capability pair so a host's claim cannot overclaim the surface: the broad `:derivation/algebra-graph` (`derivation-graph-algebra-full.edn`) and the narrow `:derivation/algebra-graph-subs-machines` (the subs+machines static subset, `derivation-graph-algebra.edn`). A graph host spanning only subscriptions and machines claims the subset and allowlists the broad capability as a known-skip.

The set is additive — new pure primitives may register a new `:call` op in subsequent fixture spec versions; existing ops cannot be redefined.

#### The `:reg-machine` op — registration-error taxonomy

The `:reg-machine` op pins the machine registration-error taxonomy (the [009 §thrown-error shape](../009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract) — the `:rf.error/id` ex-data contract) at the conformance layer. A re-frame2 machine spec is validated at registration time by a pure leaf fn (`validate-machine!` in the CLJS reference); each malformed spec throws an `ex-info` whose `:rf.error/id` ex-data slot names a `:rf.error/machine-*` category. The taxonomy is a spec-normative contract surface — consumers (Xray's error widget, the pair-tool overlay, error listeners) `case` on `:rf.error/id`, never the message string — so it deserves a corpus pin independent of any host's full registration machinery.

A `:reg-machine` call carries the candidate `:definition` and either an `:expect-error` (the `:rf.error/<category>` keyword the validator must throw) or no `:expect-error` (a well-formed control that must validate silently):

```clojure
;; Excerpt — full file at fixtures/machine-reg-error-compound-state-missing-initial.edn
{:fixture/id           :machine/reg-error-compound-state-missing-initial
 :fixture/capabilities #{:fsm/hierarchical :fsm/registration-validation}
 :fixture/calls
 [;; A compound state declaring :states but no :initial is rejected.
  {:call         :reg-machine
   :definition   {:initial :authenticated
                  :states  {:authenticated {:states {:dashboard {} :settings {}}}}}
   :expect-error :rf.error/machine-compound-state-missing-initial}

  ;; Control: a compound state that DOES declare :initial validates silently.
  {:call       :reg-machine
   :definition {:initial :authenticated
                :states  {:authenticated {:initial :dashboard
                                          :states  {:dashboard {} :settings {}}}}}}]}
```

The host runs the call by invoking its registration-time validator on `:definition` and comparing the thrown `:rf.error/id` against `:expect-error` (pass iff equal); a control call (no `:expect-error`) passes iff the validator does not throw. Fixtures using `:reg-machine` declare the `:fsm/registration-validation` capability alongside the FSM/actor capability the malformed shape exercises (`:fsm/hierarchical`, `:fsm/final-states`, `:actor/spawn-and-join`, …). A host that does not implement registration-time validation (for example a port that validates lazily) declares `:fsm/registration-validation` in its `known-skipped-capabilities` allowlist.

The validator runs against the raw `:definition` — `:reg-machine` does NOT realise `:fixture/handlers` (unlike `:machine-transition`, which merges realised handler bodies). A spec-normative validator follows the full guard/action resolution chain (a `:guards` / `:actions` keyword entry may indirect through further keyword hops to a fn terminal) — testing only first-key membership lets a multi-hop chain whose terminal hop is missing slip past registration and throw at runtime instead, defeating fail-fast. Consequently a `:reg-machine` control that declares a `:guard` / `:action` / `:entry` / `:exit` keyword ref must give that ref a terminal the resolver accepts: the canonical co-located registered-entry shape `{:fn …}` (what `reg-machine` stamps), since pure EDN cannot embed a bare fn. That `{:fn …}` value is opaque to the validator: a `{:fn x}` entry ends the chain for any `x` but `nil` or `false`, and `x` is neither resolved nor invoked, so a control writes a placeholder keyword there — `{:fn :rf.conformance/noop-action}` names nothing registered anywhere. Structural-taxonomy controls whose rule is orthogonal to guard/action resolution (for example the self-loop-`:target` check) should simply omit the refs.

#### The `:make-frame` op — construction-error taxonomy

The `:make-frame` op is the construction analogue of `:reg-machine`: it pins the frame construction-error taxonomy (the same [009 §thrown-error shape](../009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract) `:rf.error/id` ex-data contract) at the conformance layer. The construction keys `:on-create` and `:initial-db` are retired: a conformant `make-frame` must reject either with a stable `:rf.error/id` discriminator (`:rf.error/on-create-retired` / `:rf.error/initial-db-retired`) rather than silently ignoring them, so a port can't quietly accept the retired config.

A `:make-frame` call carries the candidate frame `:config` (and an optional `:frame-id`) and either an `:expect-error` (the `:rf.error/<category>` keyword the construction must throw) or no `:expect-error` (a well-formed `:initial-events` config that must register silently):

```clojure
;; Excerpt — full file at fixtures/construction-retired-keys-fail-loud.edn
{:fixture/id           :construction/retired-keys-fail-loud
 :fixture/capabilities #{:core/event-handler :core/frame}
 :fixture/calls
 [;; The retired :on-create key fails loud with a stable discriminator.
  {:call         :make-frame
   :frame-id     :ctor/oc
   :config       {:on-create [:ctor/seed {:n 0}]}
   :expect-error :rf.error/on-create-retired}

  ;; The retired :initial-db key fails loud with its own discriminator.
  {:call         :make-frame
   :frame-id     :ctor/idb
   :config       {:initial-db {:n 0}}
   :expect-error :rf.error/initial-db-retired}

  ;; Control: a well-formed :initial-events config must register silently.
  {:call       :make-frame
   :frame-id   :ctor/ok
   :config     {:initial-events [[:ctor/seed {:n 0}]]}}]}
```

The host runs the call by invoking `make-frame` (or the host equivalent) on `:config` and comparing the thrown `:rf.error/id` against `:expect-error` (pass iff equal); a control call (no `:expect-error`) passes iff `make-frame` does not throw. The runner tears the registered frame down afterward (best-effort) so a well-formed control does not leak into the final-app-db snapshot. Fixtures using `:make-frame` declare `:core/frame`.

#### The `:reg-route` op — path-pattern validation taxonomy

The `:reg-route` op is the routing analogue of `:reg-machine` / `:make-frame`: it pins the path-pattern registration-error surface (the same [009 §thrown-error shape](../009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract) `:rf.error/id` contract) for the [012 path-pattern grammar](../012-Routing.md#path-pattern-grammar-canonical). A malformed `:path` must throw `:rf.error/invalid-route-pattern` at registration, and the one canonical optional-group spelling is slash-inside (`{/:name}?`); the slash-outside drift (`/{:base}?/about`) is rejected.

A `:reg-route` call carries the candidate `:pattern` string and either an `:expect-error` (`:rf.error/invalid-route-pattern`, the id a malformed pattern must throw) or no `:expect-error` (a well-formed pattern that must validate silently):

```clojure
;; Excerpt — full file at fixtures/routing-pattern-validation.edn
{:fixture/id           :routing/pattern-validation
 :fixture/capabilities #{:routing/pattern-validation}
 :fixture/calls
 [;; Control: the canonical slash-INSIDE optional group registers cleanly.
  {:call :reg-route :pattern "{/:base}?/about"}

  ;; The slash-OUTSIDE spelling is rejected — one canonical spelling only.
  {:call :reg-route :pattern "/{:base}?/about"
   :expect-error :rf.error/invalid-route-pattern}

  ;; A splat that is not the final segment is rejected.
  {:call :reg-route :pattern "/files/*rest/more"
   :expect-error :rf.error/invalid-route-pattern}]}
```

The host runs the call by validating `:pattern` at the registration boundary (the reference invokes the pure `re-frame.routing.match/validate-route-pattern!`) and comparing the thrown `:rf.error/id` against `:expect-error` (pass iff equal); a control call (no `:expect-error`) passes iff validation does not throw. The op is pure — no registrar mutation, no navigation. Fixtures using `:reg-route` declare `:routing/pattern-validation`.

#### The `:assemble-image` op — EP-0026 image API

The `:assemble-image` op pins the image-API surface — `:select-ns` namespace selection, image-order layering (the later image wins), the shadow report, and the fail-loud collision / duplicate-image-id / framework-standard / retired-key / inline-grammar taxonomy — at the conformance layer. It is a pure Mode-B call (no frame loop): a function of a fixture-supplied descriptor pool plus image specs, exactly mirroring the reference implementation's `re-frame.image` constructor + `re-frame.image-assembly` explicit-pool assembler.

A call carries:

- `:pool` — a vector of registered descriptor maps the `:select-ns` selector projects from. Each carries `:rf.provenance/ns` (the source-code namespace — selection is by this string, never by the registration-id's namespace), `:kind`, `:id`, and a `:handler-fn` sentinel.
- `:standards` — an optional vector of `[kind id]` framework standards the runner `register-standard!`s before assembling (the protected base — an app descriptor colliding with one fails loud).
- `:images` — a vector of `rf/image` spec maps (`:id` / `:select-ns` / `:registrations`, or — for the negative cases — a retired key or a malformed inline tuple). Omit `:images` (or pass `:default? true`) for the default-image path (the implicit selector over the whole pool).
- `:images-literal` — an alternative to `:images` for the `make-frame` boundary case: the runner passes the literal value to `make-frame` (whose `:images []` validation fires before any frame record is created). Used only for the `:images []` → `:rf.error/make-frame-bad-images` fixture.

and either `:expect-error <:rf.error/id>` (a fail-loud case — the throw from `rf/image` construction OR `assemble`) or positive expectations:

- `:expect-resolves` — a vector of `{:kind :id :coordinate <coord-map>}`; the sealed resolver MUST resolve `[kind id]` to a descriptor whose descriptor coordinate equals `:coordinate`. The coordinate is the pure-EDN who-won signal: `{:ns "…"}` for a namespace-selected descriptor, `{:image <id> :inline [section id]}` for an inline one, `{:standard true}` for a framework standard.
- `:expect-present` / `:expect-absent` — vectors of `[kind id]` that MUST / MUST NOT be resolver keys.
- `:expect-kinds` — the exact `:rf.gen/kinds` set.
- `:expect-gen-absent` — top-level generation keys that MUST NOT appear on the sealed generation (for example the retired `:rf.gen/requires` image-capability slot).
- `:expect-shadows` — the exact `:rf.gen/shadows` report vector (each entry is `{:registration [kind id] :image <loser> :shadowed-by <winner>}`, deterministically ordered; a chain names the final winner per loser).

Inline `:registrations` bodies cannot be host fns in pure EDN, so the runner realises each inline body to a fresh host no-op (a distinct fn per entry, so two inline entries for one `[kind id]` stay distinct registrations rather than deduping). Impl identity is never asserted — who-won is read from the descriptor coordinate, which is independent of the body. Fixtures using `:assemble-image` declare `:core/image`. The op `register-standard!`/`clear-standards!`/`clear-generation-cache!`s around each call so a standard or cached generation from one call never leaks into the next.

> **Default-image scope note.** The op's omit-`:images` / `:default? true` branch exercises the **shipped** pure assembler (`assemble-default`): the whole-pool projection + framework standards, with a cross-namespace `[kind id]` collision failing loud. That projection is what `make-frame` seals when handed a descriptor pool and no `:images`. `make-frame {}` over the registration store seals nothing: it resolves the store live and raises `:rf.error/ambiguous-registration` where a duplicated `[kind id]` is used, which the reference implementation's live-default-frame tests cover. The `image-default-image.edn` fixture pins the assembler path + the `:images []` error.

### Capability tagging

Per [Goal 6 — Hierarchical FSM substrate with implementor-chosen capabilities](../000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) and [005 §Capability matrix](../005-StateMachines.md#capability-matrix), conformance is graded against each port's claimed capability list rather than all-or-nothing. Every fixture self-declares which capabilities it exercises via `:fixture/capabilities`. The harness only runs fixtures whose capability set is a subset of the port's claimed list; un-runnable fixtures are reported as "not exercised" rather than "failed."

Capability tag conventions (the closed list of tags a fixture may carry is the `:conformance/capability` enum in [`fixture-file.edn`](fixture-file.edn)):

- `:core/*` — pattern-required basics every conformant port supports (event handler, frame, dispatch envelope, sub, trace, fx, error). `:core/image` is the image-API surface (`:select-ns` selection, image-order layering, the shadow report, the collision / retired-key / inline-grammar fail-loud taxonomy, the default-image projection) exercised by the `image-*.edn` fixtures via the `:assemble-image` call op.
- `:fsm/*` — FSM-richness axis (`:fsm/flat`, `:fsm/hierarchical`, `:fsm/eventless-always`, `:fsm/delayed-after`, `:fsm/tags`, `:fsm/parallel-regions`, `:fsm/final-states`, `:fsm/registration-validation`).
- `:actor/*` — actor-model axis (`:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/declarative-spawn`, `:actor/spawn-and-join`).
- `:routing/*`, `:ssr/*`, `:schemas/*` — per-spec capabilities for ports that ship them.
- `:identity/*` — the canonical-identity byte contract: CEDN-1 encoding, identity comparison, and the `:rf/path` instantiate boundary, per [Conventions §The `:rf/path` algebra](../Conventions.md#the-rfpath-algebra). **Not optional** — it backs the required identity primitive, so every port runs these.
- `:flow/*` — the Spec 013 flow substrate (`:flow/basic`, `:flow/topo`, `:flow/dirty-check`, `:flow/toggle`, `:flow/frame-scoped`, `:flow/hot-reload`, `:flow/trace`). **Not optional** — [013](../013-Flows.md) is v1-required.
- `:data-classification/*` — the Spec 015 `:sensitive` / `:large` egress model. **Not optional** — [015](../015-Data-Classification.md) is v1-required.
- `:derivation/*` — the cross-family derivation/process graph ([Derivations](../Derivations.md)), split into the broad `:derivation/algebra-graph` and the narrow `:derivation/algebra-graph-subs-machines` so a host spanning only subs + machines claims the subset. See the `:derivation-graph` call op in [§Mode B](#mode-b--pure--direct-call).
- `:rf.http/managed` — a single tag rather than a `/*` axis: managed HTTP ([014](../014-HTTPRequests.md)) is one contract, claimed whole, for ports that ship it. The `http-managed-*` fixtures supply their own stub bodies and grade override routing, so passing them does not establish the managed-HTTP lifecycle — see [§Managed-HTTP lifecycle (not yet corpus-graded)](#managed-http-lifecycle-not-yet-corpus-graded).
- `:resources/*` — the Spec 016 resource-runtime axis for ports that ship `day8/re-frame2-resources` (`:resources/ensure` — first-load + fresh-skip cache-hit; `:resources/dedupe` — dedupe-join; `:resources/stale-suppression` — stale-reply generation suppression; `:resources/scope-fail-closed` — the scope fail-closed trio; `:resources/owner-gc` — owner release → GC eligibility; `:resources/keep-previous` — refresh-error preserves prior data). Exercised by the `resources-*.edn` fixtures — Mode-A dispatch of `:rf.resource/ensure` / `refetch` / `release-owner` / `gc-fired` plus the framework-internal `:rf.resource.internal/succeeded` / `failed` replies (the reply verification triple — `:resource/key`, `:work/id`, `:generation` — is pure EDN; the runner drives the reply the way `route-stale-nav-token-suppression.edn` drives its continuation), plus the pure `:reg-resource` / `:resolve-scope` call ops for the fail-closed registration + scope-resolution boundary.

A flat-FSM-only port declares `:capabilities #{:core/event-handler ... :fsm/flat :actor/own-state :actor/spawn-destroy ...}` in its harness manifest; the corpus runs every fixture whose capabilities are a subset and skips the rest. The aggregate score is `passed / claimed-applicable` — an accounting of what works for the claimed list.

A machine fixture's tags cover every capability-gated key that any machine definition in it uses — in `:reg-machine` and `:machine-transition` rows and in `:fixture/registry :machine` entries, control rows and error rows alike — because a port that does not claim a capability rejects its keys at registration with `:rf.error/machine-grammar-not-in-v1` ([005 §How conformance is graded](../005-StateMachines.md#how-conformance-is-graded)), which fails the row on a port the tags admit. Capabilities do not imply one another: a fixture whose rows nest `:states` carries `:fsm/hierarchical` beside `:fsm/history`, and one that puts a `:timeout` on a `:spawn` carries `:actor/declarative-spawn` beside `:fsm/timeout`.

A port's harness MUST distinguish two flavours of out-of-claim capability so silent rot can't mask coverage gaps:

- Intentional out-of-claim — the port's `claimed-capabilities` excludes the capability (for example a flat-FSM-only port skipping `:fsm/hierarchical`). The harness MAINTAINS an explicit `known-skipped-capabilities` allowlist; capabilities in this set produce "skipped (out-of-claim)" reports without failing the suite.
- Typo / claim-set drift — a fixture's capability appears in neither the claimed set nor the allowlist. The suite FAILS with a diagnostic naming the unknown capability, because a silent skip would hide the typo. The remedy is either to add the capability to `claimed-capabilities` (with the runtime backing to match) or to add it to `known-skipped-capabilities` (an explicit decision not to claim it).

The reference harness (`implementation/core/test/re_frame/conformance_test.clj` and `implementation/core/test/re_frame/conformance_corpus_cljs_test.cljs`, over the shared `conformance_runner.cljc`) keeps one capability in `known-skipped-capabilities` — `:ssr/render-tree-hash` — because the SSR artefact's own conformance runner claims and grades it; every other capability a corpus fixture references is claimed. The allowlist exists so that any divergence requires an explicit decision rather than silent drift.

See [§Capability tagging worked example](#capability-tagging-worked-example) immediately below for the actual tags carried by representative fixtures in the corpus.

### Capability tagging worked example

The conventions above describe the schema; the corpus itself shows what those tags look like in practice. The following five fixtures are pulled verbatim from `fixtures/` — together they span the main capability axes (core, FSM hierarchy, actor model, flow tracing, managed HTTP). An AI port author landing fresh can use these as a tag-vocabulary reference rather than inventing names.

| Fixture | `:fixture/capabilities` | What the tag set means |
|---|---|---|
| `counter-inc-once.edn` | `#{:core/event-handler :core/sub}` | The simplest pattern-required-only fixture: a single event handler, one sub. Every conformant port runs this. |
| `frame-lifecycle.edn` | `#{:core/event-handler :core/frame :core/trace}` | The default frame's `:initial-events` / `:on-destroy` events fire at frame creation and destruction; the runtime emits `:rf.frame/created` / `:rf.frame/destroyed` trace ops. Verifies both the frame-lifecycle contract and the trace-bus emission. |
| `frame-multi-instance.edn` | `#{:core/event-handler :core/sub :core/frame :core/trace}` | Two frames sharing one registrar with isolated app-db; each trace event carries a per-frame `:frame` tag so the bus is multi-frame addressable. |
| `error-handler-exception.edn` | `#{:core/event-handler :core/error :core/trace}` | A handler throws; the runtime emits a structured `:rf.error/handler-exception` trace with `:op-type :error` and `:recovery :no-recovery`. The trace shape is the primary contract. |
| `after-hierarchy.edn` | `#{:fsm/hierarchical :fsm/delayed-after}` | A parent compound state with an `:after` timer. Only ports that claim both hierarchical FSM and `:after` will run it. |
| `spawn-from-action.edn` | `#{:fsm/flat :actor/spawn-destroy}` | Imperative spawn of a child actor from inside a transition action. Requires the actor-model spawn-destroy capability on top of flat FSMs. |
| `flow-lifecycle-emits-traces.edn` | `#{:core/event-handler :flow/basic :flow/trace}` | The flow primitive emits its five lifecycle trace events. Requires the flow substrate and the flow-trace stream. |
| `http-managed-get-success.edn` | `#{:core/event-handler :core/sub :core/fx :rf.http/managed}` | A GET shaped like the Spec 014 happy path: `:fx-overrides` redirects `:rf.http/managed` to the fixture's own canned-success body, which dispatches the reply. Builds on the core triad and adds the managed-HTTP capability tag from [Spec 014](../014-HTTPRequests.md); what it grades is override routing, not the managed-HTTP lifecycle. |

Read the chosen tag namespaces from the [conventions list above](#capability-tagging) and refer back to this table when authoring a new fixture or a port's harness manifest: copy the tag from the closest existing fixture rather than coining a new one.

### Handler bodies as data

Since the corpus is host-agnostic, handler bodies can't be CLJS lambdas — they must be data the host realises into native closures.

The convention:

```clojure
;; A handler body is a small DSL the corpus harness interprets.
{:counter/initialise [[:set [:count] 0]]                      ;; sets db's :count to 0
 :counter/inc        [[:update [:count] [:fn :inc]]]}         ;; updates db's :count via :inc
```

The harness in each host implements a small interpreter for this DSL — `[:set path value]`, `[:update path fn]`, `[:dispatch event]` and so on. Its grammar — the steps each handler kind's body admits, the value forms and the builtins — is in [`fixture-file.edn`](fixture-file.edn).

This keeps the corpus pure data; no host-specific code ships in the fixtures.

### Handler-body DSL ops

The steps each handler kind's body admits, the value forms and the `[:fn …]` builtins are declared in [`fixture-file.edn`](fixture-file.edn), each with its meaning; a host's interpreter follows those entries. A few carry a reason the grammar line does not:

- `[:return-raw value]` returns `value` verbatim as the effect map, bypassing the well-shaped builder — the only way a fixture can author a **malformed** effect map, which the shape-policing fixtures (`effect-map-shape-*.edn`, `effect-handler-bad-return.edn`) need. A body carrying it is always realised as an effects-out handler, so the raw return reaches the runtime's shape-policing site; its sibling steps are ignored.
- `[:dispatch-sync event]` and `[:make-frame-capture path frame-id config]` pin two in-handler bans: the router's `:in-drain?` guard (`:rf.error/dispatch-sync-in-handler`, Cross-Spec Interaction §14) and the handler-time construction guard (`:rf.error/frame-construction-in-handler`). `:make-frame-capture` writes the thrown `:rf.error/id` into app-db because `do-fx` would otherwise swallow the guard's throw as `:rf.error/fx-handler-exception`.
- `[:event-arg n default]` returns `default` when the n-th event element is nil — never a key lookup, even when `default` is a keyword. Key access into a map argument is `[:get-event-arg n :key]`, with an optional default.
- `[:fn :builtin & args]` in an event, fx or sub body is a partial applied after the runtime's one argument: `[:fn :conj :x]` is `#(conj % :x)`. The builtin vocabulary is stable and additive — a later fixture spec version may add a builtin, never redefine one.

`:fixture/dispatches` also accepts map entries beside event vectors (the grammar's `:conformance/dispatch-entry`): `{:destroy-frame id}` tears a frame down mid-run (Cross-Spec Interaction §1); `{:reg-sub id :body …}` re-registers a sub, which fires `:rf.registry/handler-replaced` (§18); `{:queue [event …]}` queues each event behind a held drain, so every event after the first is external input waiting behind a running family (Spec 002 §Run-to-completion); and `{:event event :expect-error id & opts}` asserts the dispatch **throws** that `:rf.error/id` — the boundary and context-assembly errors (`:rf.error/missing-required-cofx`, `:rf.error/unregistered-cofx`) that escape `dispatch-sync` before any handler runs. A dispatch that does not throw, throws a different id, or throws without one fails the fixture; a dispatch **without** `:expect-error` that throws is a fixture-level error.

### Fixture lifecycle

Each fixture defines an invariant the implementation upholds. The harness:

1. Registers the fixture's vocabulary — the `:fixture/registry` metadata by kind, with the `:fixture/handlers` bodies realised into host-native closures through the DSL interpreter. `:app-schemas` is not a registrar kind: it maps an app-db path to the schema the harness registers there. A `:route` entry writes its path pattern as a `:path` key inside the metadata map, but `reg-route` takes the pattern in its third, value slot and rejects a `:path` metadata key with `:rf.error/route-bad-metadata` ([012 §Authoring-boundary key validation](../012-Routing.md#authoring-boundary-key-validation)), so the harness lifts it out: `{:route/home {:path "/" :doc "Home"}}` registers as `(reg-route :route/home {:doc "Home"} "/")`. Registration order matters to the corpus for routes — [012 rule 6](../012-Routing.md#route-ranking-algorithm) breaks an equal-score tie in favour of the earlier registration, and the equal-score warning names the later one — and an EDN map has no order, so the harness registers `:route` entries in ascending order of each id's printed text (`:route/files.rest`), compared character by character with no locale collation, never in the order a reader yields the map. `:route/files.rest` registers before `:route/files.splat` wherever the file writes them.
2. Creates the frame — `:fixture/frame-config` via `make-frame` (or the host equivalent), with the simulated `:platform` from `:fixture/runtime` merged in unless the config sets its own. A multi-frame fixture creates each of `:fixture/frames` in order instead, and the **first** frame's `:id` becomes the established scope that registration-time frame-local surfaces and bare `dispatch-sync` resolve against. The frame's `:initial-events` fire here.
3. Applies `:fixture/classification-effects` (see [§The `:fixture/classification-effects` harness step](#the-fixtureclassification-effects-harness-step)), then registers the flows whose bodies `:fixture/flow-bodies` carries — flows are frame-scoped, so they register only once the frame exists.
4. Runs `:fixture/dispatches` — each via `dispatch-sync`, settling to fixed point before the next. Where `:fixture/clock` is present, this step runs against the controlled clock described in [§The `:fixture/clock` controlled host clock](#the-fixtureclock-controlled-host-clock).
5. Runs `:fixture/clock` steps (if present) — still inside the controlled clock, so a fired callback releases its own handle through it.
6. Simulates the client's first render after `:rf/hydrate` (if `:fixture/render-after-hydrate` is present), handing its client hash to the hydration-mismatch check against the server hash the hydrate payload carries.
7. Runs `:fixture/calls` (if present) — direct invocations of pure primitives, each carrying its own expectation; a mismatch is a fixture-level failure.
8. Subscribes once to each `:fixture/compute-subs` query, so a sub that *throws* is observed as a sub failure rather than as an absent value.
9. Compares (Mode A) — each `:fixture/expect` key by the law the grammar names for it. `:trace-emissions` partial-matches each trace event by its specified keys; `:final-app-db` is a submap compare (declared keys must match; extra actual keys tolerated); `:final-app-db-absent` asserts each path's tip key is ABSENT; `:effects-routed` matches the routed-fx pairs in declaration order. A dispatch carrying `:expect-error` asserts that dispatch threw the named `:rf.error/id`.

For Mode B fixtures, comparison happens inline at each call; there is no top-level `:fixture/expect` to evaluate after drain.

The harness reports per-fixture pass/fail; aggregate score is `passed / claimed-applicable` — passing fixtures over the fixtures whose capabilities the implementation claims ([§Capability tagging](#capability-tagging)).

Each fixture is a single file of <200 lines including registrations and expectations. Adding a fixture is a small focused change.

## How an implementation runs the corpus

1. Read all `.edn` files in `fixtures/`.
2. For each fixture:
   a. Bootstrap the host's runtime with the fixture's registry.
   b. Realise handler bodies via the DSL interpreter.
   c. If `:fixture/dispatches` is present (Mode A):
      - Create a frame per `:fixture/frame-config`.
      - Run each dispatch — against the controlled clock where `:fixture/clock` is present.
      - Run the `:fixture/clock` steps, if any, still inside that clock.
      - After drain, capture: final `app-db`, sub values, emitted trace events, effects routed.
      - Compare actuals against `:fixture/expect`.
   d. If `:fixture/calls` is present (Mode B):
      - For each call record, invoke the named primitive with the supplied arguments.
      - Compare the result against the call-local expectation (`:expect`, or operation-specific keys like `:expect-next-snapshot` + `:expect-effects` for `:machine-transition`, or `:expect-error` for `:reg-machine` / `:make-frame`).
3. Report pass/fail per fixture; total conformance score.

A conformant runner asserts not only `(zero? failed)` but a non-zero runnable-fixture floor (`(pos? count-run)` plus a below-current expected minimum): an empty or all-skipped corpus must go RED rather than pass vacuously having exercised nothing. The corpus IS the spec-validation mechanism, so a path bug, a fixtures-dir rename, or a capability-vocabulary rename that orphans every fixture is a hard failure, not silent green.

## Versioning

The corpus is versioned alongside the spec. Each fixture file declares the spec version it was authored against:

```clojure
{:fixture/id        :counter/inc-once
 :fixture/spec-version "1.0"
 ...}
```

When the spec changes shape (new required key in `:rf/dispatch-envelope`, new error category), affected fixtures bump their `:spec-version` and the corpus's harness check rejects implementations that haven't moved with the spec.

## Fixtures

See `fixtures/` for the files. Each fixture is one EDN file exercising one shape of the spec, and its `:fixture/doc` states what it pins.

Coverage spans the main categories: handlers, frames, frame construction (ordered `:initial-events` drain, `:rf/set-db` seed, retired-key fail-loud), the image API (`:select-ns` selection, image-order layering, the shadow report, the collision / retired-key / inline-grammar fail-loud taxonomy, the default-image projection), envelope, subs, fx, errors, machines, routing, SSR, hydration, epoch, trace bus, view registration, HTTP request interceptors, and data-classification commit-plane classification + egress projection.

### The EP-0025 classification model — what the `data-classification/` fixtures pin

The `data-classification/` category exercises the data-classification contract: a path-based, fail-open, commit-plane-effect model. The shipped model is simple — there is no propagation / taint, no value-match, no frame `:sensitive {:app-db}` annotation, and no imperative marks API. The contract is:

- Durable app-db classification is four commit-plane effects. A `reg-event-*` handler classifies a durable app-db path by returning one of the four classification effects alongside its `:db`: `:sensitive` and `:large` declare a path on each axis, and `:clear-sensitive` / `:clear-large` remove a path from its axis. These write a per-frame elision registry under `:source :effect`. The two axes are independent — clearing one never disturbs the other (the `clear-axis-independent.edn` fixture pins this), ordinary per-axis set/unset symmetry rather than a single coupled "classified?" bit. When both axes apply to a path, sensitive dominates the size axis: a both-classified path projects as the bare `:rf/redacted` sentinel, never a `:rf.size/large-elided` marker (which would leak path / size / type / handle).
- Event args are registration-owned transient payload. A `reg-event-*` declares a `:sensitive` / `:large` arg-path on its registration metadata (rooted at the dispatched `[event-id arg-map]` shape); the trace surface redacts the declared path while the handler body sees the raw payload. No app-db policy is involved.
- Subsystems declare their own classification projection-relative. A machine declares its durable `:data` classification on its `reg-machine` spec (a `:sensitive` / `:large` vector of `:data`-rooted `:rf/path` vectors, like XState's `context` shape); the runtime LOWERS each declaration per actor instance at spawn, re-rooting it to the absolute snapshot path and writing it `{:source :machine}` into the same per-frame elision registry the four commit-plane effects populate — and drops it on destroy. The machine's `[:schemas :data]` schema validates but does not classify. Provenance is the `:source` slot: `:effect` (commit-plane), `:machine` (subsystem-lowered), and any future subsystem source. A `:clear-*` is source-scoped — it removes the effect-sourced declaration without disturbing a machine-sourced one at the same path, and a subsystem teardown drops only its own `:source` entries.
- Redaction is a TRACE-EGRESS concern only — fail-open is the contract. The committed app-db, the committed machine-snapshot runtime-db, and the durable epoch ring all carry the raw value; only the projected egress surface (trace `:tags`, observability records, off-box export) redacts. There is no storage-side redaction; the epoch ring stays replay-faithful by construction. The egress projector itself fails closed at its OWN boundary — a frameless `project-egress` redacts the whole tree rather than synthesise a permissive `:rf/default` frame — but the in-process durable planes are fail-open so handlers, replay, and restore always see real values.

#### The `:fixture/classification-effects` harness step

A fixture installs durable-path classification host-agnostically via the optional top-level `:fixture/classification-effects` key — an ORDERED vector of classification-effect op-maps the harness applies against the established frame scope (after `make-frame`, before static `reg-flow` registration). Because pure-EDN fixtures cannot return effects from a handler body, `:fixture/classification-effects` is the harness shorthand that writes the elision registry exactly as the four commit-plane effects would (`:source :effect`):

```clojure
:fixture/classification-effects
[{:sensitive    [[:auth :token]]}  ;; classify a path on the sensitive axis (the :sensitive effect)
 {:large        [[:auth :token]]}  ;; classify the SAME path on the large axis (the :large effect)
 {:clear-large  [[:auth :token]]}] ;; declassify ONE axis (the :clear-large effect)
```

Each op-map carries exactly one of the four commit-plane effect keys — `:sensitive` / `:large` (additive classification on that axis), or `:clear-sensitive` / `:clear-large` (remove the named paths on that axis ONLY; the other axis at the same path survives — commit-plane per-axis independence). Each value is a vector of `:rf/path` vectors (a `get-in`-shaped path). The ordered-vector form lets a fixture pin the set / clear sequencing (for example the `clear-axis-independent.edn` axis-independence case). The declarations feed the emit-time trace-egress projection — sensitive slots render `:rf/redacted` and large slots render the `:rf.size/large-elided` marker inside the relevant trace `:tags` (for example the `:rf.event/db` slot on the t1 `:rf.event/db-pending` trace event) before any listener observes them. The machine-`:data` fixture needs NO `:fixture/classification-effects` op — the machine lifecycle lowers its projection-relative declaration `{:source :machine}` at boot. Fixtures using `:fixture/classification-effects` (or a machine projection-relative declaration) declare the `:data-classification/classification-effects` capability.

## Render-time observables (out of scope)

The corpus is host-agnostic pure-data event/sub/fx semantics. Render-time observables — React-context propagation through `frame-provider`, Reagent reactivity, component lifecycle, and the like — are not currently expressible as fixtures because the corpus has no render capability and no harness side that mounts a component tree to capture what happens during render. These behaviours are verified by host-side unit tests instead (for example `runtime_cljs_test.cljs` covers `frame-provider`'s establish-context and nested-override behaviour for the CLJS reference).

If a port (CLJS, JVM SSR, future hosts) needs to claim conformance for render-time behaviour, this README will grow a `:reagent/render` (or equivalent) capability tag and a host-side fixture runner that mounts the fixture's tree and captures the observable. That is future work; no fixture in the corpus claims a render capability.

## Managed-HTTP lifecycle (not yet corpus-graded)

The managed-HTTP request lifecycle of [Spec 014](../014-HTTPRequests.md) — status classification, decode, `:accept`, retry and backoff, abort, and reply-envelope synthesis — is not currently expressible as fixtures, because the harness has no seam below the `:rf.http/managed` fx: a fixture can replace the fx, but cannot feed it a wire response. So each `http-managed-*` fixture supplies its own bodies for `:rf.http/managed` and for the canned stub that `:fx-overrides` redirects it to, and that body dispatches a reply the fixture writes itself. What those fixtures grade is core `:fx-overrides` redirection, args forwarding (`:effects-routed`) and reply dispatch; their scenarios and reply envelopes mirror the Spec 014 sections they name, for readability. A port passes them without any managed-HTTP code, so a pass is no evidence of the lifecycle. The lifecycle is verified by host-side tests instead (for the CLJS reference, `implementation/http/test/`, which also drives the real JVM transport against an in-process HTTP server).

This limit is the lifecycle's, not Spec 014's as a whole: `data-classification-http-carrier-extends-defaults.edn` runs the header-redaction primitive through the `:redact-headers` call op, as part of the Spec 015 egress model.

If a port needs corpus-graded conformance for managed HTTP, this README will grow a transport seam — canned wire responses fed below request normalisation, with controlled completion and cancellation — so that the real fx runs under the fixture. That is future work; until then a port claiming [Q8](../Implementor-Checklist.md#q8-managed-http) verifies the lifecycle with its own tests.

## Cross-references

- [Implementor-Checklist.md](../Implementor-Checklist.md) — decision-ordered companion to [000 §Host-profile matrix](../000-Vision.md#host-profile-matrix); Part 1 capability declarations and Part 3 (Conformance) tell the implementor which fixtures will run.
- [fixture-file.edn](fixture-file.edn) — the closed fixture grammar every fixture validates against.
- [Spec-Schemas.md §Conformance](../Spec-Schemas.md#conformance) — the conformance test corpus question.
- [Spec-Schemas.md](../Spec-Schemas.md) — the shapes implementations validate / match against.
- [009 §Error contract](../009-Instrumentation.md) — error-event shape that error fixtures expect.
- [011-SSR.md](../011-SSR.md) — SSR fixtures' protocol.
- [012-Routing.md](../012-Routing.md) — routing fixtures' protocol.
- [Pattern-StaleDetection.md](../Pattern-StaleDetection.md) — the meta-pattern shared by `after-stale-detection.edn` and `route-stale-nav-token-suppression.edn`.
