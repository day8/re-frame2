# Conformance Corpus

> **Type:** Reference
> The conformance test suite for re-frame2 implementations. The corpus is the verification mechanism for [Goal 2 — AI-implementable from the spec alone](../000-Vision.md#ai-implementable-from-the-spec-alone): the claim that the spec is sufficiently complete for an AI armed only with `/spec/` + this corpus to produce a working reference implementation in any host language. A spec gap surfaces as a fixture an AI cannot reproduce without consulting outside sources; closing the gap is a spec-level remediation, not an implementation-level one.

## Naming convention

Fixture filenames mirror their `:fixture/id` exactly, with the slash that separates the namespace from the local name rewritten as a hyphen and the rest of the id preserved. A fixture with `:fixture/id :counter/inc-once` lives in `counter-inc-once.edn`. Both filename and id use kebab-case; no underscores.

## What this is

A set of **fixture files** in EDN format, each describing one canonical interaction. Two complementary fixture shapes cover the spec (full detail in [§Fixture format](#fixture-format)):

- **Dispatch-driven fixtures** (Mode A) — a frame configuration plus initial `app-db`, a sequence of dispatched events, and the expected emissions after drain: final `app-db`, trace events, effects routed to fx, return values from subscriptions.
- **Pure / direct-call fixtures** (Mode B) — direct invocations of pure primitives (machine transitions, URL ↔ params helpers, hiccup → HTML rendering) with call-local expectations. No frame, no dispatch loop; JVM-runnable.

An implementation conforms if it produces matching emissions for every fixture in the corpus whose capabilities are a subset of the port's claimed list.

## Why EDN

EDN is the natural data format for the CLJS reference. For other-host implementations:

- A small EDN reader exists in nearly every host language (or can be written in ~200 lines for any language with hash-maps and vectors).
- The structure is host-language-agnostic — keywords map to namespaced strings or branded enums per the host's identity primitive.
- The corpus is itself **machine-readable data** — implementers in a new language read the corpus, generate host-native test code, and report.

A JSON-translated corpus may be published in the future for hosts where EDN is too much friction; if and when it ships, the JSON form will be mechanically derived from the EDN source so the two cannot drift. Until then, the EDN files in `fixtures/` are the canonical source. Implementors targeting non-EDN hosts either ship a small EDN reader (~200 lines for any host with hash-maps and vectors) or translate the corpus locally as part of harness bootstrap.

## Fixture format

Each fixture is an EDN map. A fixture exercises the spec in **one of two modes**:

- **Mode A — dispatch-driven.** A frame is created, a sequence of events is dispatched, and the harness compares the post-drain observables (`app-db`, sub values, trace emissions, effects routed) against a single top-level `:fixture/expect`. Use this mode for event/sub/fx/trace/error semantics that only make sense inside a running frame. Most fixtures in the corpus use this mode.
- **Mode B — pure / direct-call.** No frame, no dispatch loop. The fixture lists one or more direct calls to a re-frame primitive (e.g. `machine-transition`, `match-url`, `route-url`, `render-to-string`) and each call carries its **own** expectation inline. Use this mode for primitives that are pure functions of their inputs — machine transitions, URL ↔ params helpers, hiccup → HTML rendering. JVM-runnable; nothing about the substrate's wiring is exercised.

A fixture chooses one mode; the runner executes whichever of `:fixture/dispatches` and `:fixture/calls` is present. (A few fixtures may include calls after a dispatch sequence to assert pure-function output against the post-drain registry; that is still Mode A — the dispatches are the load-bearing part and `:fixture/expect` is the primary contract.)

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

The expectation keys inside `:fixture/expect` are partial-match by convention: `:trace-emissions` matches each trace event by its specified keys (absent keys ignored), `:final-app-db` is a **submap** compare (every declared key must match; extra actual keys are tolerated), `:effects-routed` matches the routed-fx pairs in declaration order. Because `:final-app-db`'s submap match tolerates extras, a **negative** companion key `:final-app-db-absent` — a vector of `get-in`-shaped paths that must be ABSENT from the final app-db (the tip key not present; a present-with-`nil` leaf counts as present) — pins contracts the positive submap cannot, e.g. EP-0017 declared-only delivery (a port that over-delivers undeclared coeffects still passes the positive check but fails the absent-path check). See [§Fixture lifecycle](#fixture-lifecycle) for the full comparison contract.

### Mode B — pure / direct-call

For pure primitives — machine transitions, URL helpers, render-to-string — the fixture skips the frame entirely. `:fixture/calls` is a vector of call records; each record names the primitive in `:call`, supplies its arguments, and carries its **own** expectation alongside (typically `:expect`, or operation-specific keys like `:expect-next-snapshot` + `:expect-effects` for `:machine-transition`).

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

The reserved `:call` operators currently used by the corpus are `:machine-transition`, `:reg-machine`, `:reg-frame` (the EP-0027 construction-registration assertion — mirror of `:reg-machine`, taking a `:config` plus optional `:frame-id` and an `:expect-error` `:rf.error/id` for the retired-construction-key discriminators, or no `:expect-error` for a well-formed control; see [§The `:reg-frame` op](#the-reg-frame-op--construction-error-taxonomy) below), `:assemble-image` (the EP-0026 image-API assertion — assembles a descriptor `:pool` + `:images` specs into a sealed generation, asserting the resolver winner by descriptor coordinate, the shadow report, the generation kinds, and the fail-loud `:rf.error/id` taxonomy; see [§The `:assemble-image` op](#the-assemble-image-op--ep-0026-image-api) below), `:match-url`, `:route-url`, `:round-trip`, `:assert-rank-greater`, `:render-to-string`, the EP-0012 CEDN-1 identity ops (`:canonical-bytes`, `:canonical-identical`, `:canonical-distinct`, `:path-instantiate`), the EP-0012 `:rf/path` algebra LAW ops (`:path-get`, `:path-lookup`, `:path-put`, `:path-over`, `:path-compose`, `:path-prefix`, `:path-overlap` — pinning the get/put/over/compose/prefix/overlap + root-path + present-nil-vs-missing + vector-index laws of [Conventions §Path laws](../Conventions.md#the-rfpath-algebra), so a port implementing only CEDN bytes and template instantiation does **not** pass EP-0012 conformance), the EP-0015 data-classification projector ops (`:project-egress` — the centralised egress projector under a resolved `:rf.egress/profile`, optionally against a registered `:frame`; `:redact-headers` — the HTTP header carrier denylist under a frame's `:frame-extras` extension set; `:ssr-apply-policy` — the SSR hydration-payload allowlist projection, taking `:expect` for the projected slice or `:expect-error` for the fail-closed `:rf.error/id`), and the EP-0014 derivation/process-algebra op (`:derivation-graph` — composes the cross-family [derivation/process graph](../Derivations.md#graph-inspection--internal-but-structured) over the host's loaded contributors and asserts normalized node + edge shapes: `:mode :static` (default) or `:live` with `:frame`; `:expect-nodes` are submaps matched against the composed node at `:id` — lowering + storage/evaluation/lifecycle classification + `:refinement`; `:expect-edges` must be present and `:expect-absent-edges` absent; `:expect-graph` is a submap matched against the WHOLE graph map, pinning the graph-level `:mode`/`:frame` shape so a live graph that drops or misreports `{:mode :live :frame …}` fails even when its nodes/edges are correct — rf2-ska8zk). The op is graded against a SPLIT capability pair so a host's claim cannot overclaim the EP-0014 surface: the broad `:derivation/algebra-graph` (exercised by `derivation-graph-algebra-full.edn` across subs / flows / resources / routes / route-owned activation / static + live modes / the resource authority-vs-local-storage split) and the narrow `:derivation/algebra-graph-subs-machines` (the subs+machines static subset, `derivation-graph-algebra.edn`) — a graph host spanning only subscriptions + machines claims the subset and allowlists the broad capability as a known-skip. The set is additive — new pure primitives may register a new `:call` op in subsequent fixture spec versions; existing ops cannot be redefined.

#### The `:reg-machine` op — registration-error taxonomy

The `:reg-machine` op pins the machine **registration-error taxonomy** (the [009 §thrown-error shape](../009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract) — the `:rf.error/id` ex-data contract) at the conformance layer. A re-frame2 machine spec is validated at registration time by a pure leaf fn (`validate-machine!` in the CLJS reference); each malformed spec throws an `ex-info` whose `:rf.error/id` ex-data slot names a `:rf.error/machine-*` category. The taxonomy is a spec-normative contract surface — consumers (Xray's error widget, the pair-tool overlay, error listeners) `case` on `:rf.error/id`, never the message string — so it deserves a corpus pin independent of any host's full registration machinery.

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

The host runs the call by invoking its registration-time validator on `:definition` and comparing the thrown `:rf.error/id` against `:expect-error` (pass iff equal); a control call (no `:expect-error`) passes iff the validator does not throw. Fixtures using `:reg-machine` declare the `:fsm/registration-validation` capability alongside the FSM/actor capability the malformed shape exercises (`:fsm/hierarchical`, `:fsm/final-states`, `:actor/spawn-and-join`, …). A host that does not implement registration-time validation (e.g. a port that validates lazily) declares `:fsm/registration-validation` in its `known-skipped-capabilities` allowlist.

The validator runs against the **raw `:definition`** — `:reg-machine` does NOT realise `:fixture/handlers` (unlike `:machine-transition`, which merges realised handler bodies). A spec-normative validator follows the **full guard/action resolution chain** (a `:guards` / `:actions` keyword entry may indirect through further keyword hops to a fn terminal) — testing only first-key membership lets a multi-hop chain whose terminal hop is missing slip past registration and throw at runtime instead, defeating fail-fast. Consequently a `:reg-machine` **control** that declares a `:guard` / `:action` / `:entry` / `:exit` keyword ref must give that ref a terminal the resolver accepts: the canonical co-located registered-entry shape `{:fn …}` (what `reg-machine` stamps), since pure EDN cannot embed a bare fn. Structural-taxonomy controls whose rule is orthogonal to guard/action resolution (e.g. the self-loop-`:target` check) should simply omit the refs.

#### The `:reg-frame` op — construction-error taxonomy

The `:reg-frame` op is the EP-0027 construction analogue of `:reg-machine`: it pins the frame **construction-error taxonomy** (the same [009 §thrown-error shape](../009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract) `:rf.error/id` ex-data contract) at the conformance layer. EP-0027 retired the legacy construction keys `:on-create` and `:initial-db`; a conformant `reg-frame` must reject either with a stable `:rf.error/id` discriminator (`:rf.error/on-create-retired` / `:rf.error/initial-db-retired`) rather than silently ignoring the old shape, so a port can't quietly accept the retired config.

A `:reg-frame` call carries the candidate frame `:config` (and an optional `:frame-id`) and either an `:expect-error` (the `:rf.error/<category>` keyword the construction must throw) or no `:expect-error` (a well-formed `:initial-events` config that must register silently):

```clojure
;; Excerpt — full file at fixtures/construction-retired-keys-fail-loud.edn
{:fixture/id           :construction/retired-keys-fail-loud
 :fixture/capabilities #{:core/event-handler :core/frame}
 :fixture/calls
 [;; The retired :on-create key fails loud with a stable discriminator.
  {:call         :reg-frame
   :frame-id     :ctor/oc
   :config       {:on-create [:ctor/seed {:n 0}]}
   :expect-error :rf.error/on-create-retired}

  ;; The retired :initial-db key fails loud with its own discriminator.
  {:call         :reg-frame
   :frame-id     :ctor/idb
   :config       {:initial-db {:n 0}}
   :expect-error :rf.error/initial-db-retired}

  ;; Control: a well-formed :initial-events config must register silently.
  {:call       :reg-frame
   :frame-id   :ctor/ok
   :config     {:initial-events [[:ctor/seed {:n 0}]]}}]}
```

The host runs the call by invoking `reg-frame` (or the host equivalent) on `:config` and comparing the thrown `:rf.error/id` against `:expect-error` (pass iff equal); a control call (no `:expect-error`) passes iff `reg-frame` does not throw. The runner tears the registered frame down afterward (best-effort) so a well-formed control does not leak into the final-app-db snapshot. Fixtures using `:reg-frame` declare `:core/frame`.

#### The `:assemble-image` op — EP-0026 image API

The `:assemble-image` op pins the EP-0026 image-API surface — `:select-ns` namespace selection, image-order layering (the later image wins), the shadow report, and the fail-loud collision / duplicate-image-id / framework-standard / retired-key / inline-grammar taxonomy — at the conformance layer. It is a **pure** Mode-B call (no frame loop): a function of a fixture-supplied descriptor **pool** plus **image specs**, exactly mirroring the reference implementation's `re-frame.image` constructor + `re-frame.image-assembly` explicit-pool assembler.

A call carries:

- `:pool` — a vector of **registered** descriptor maps the `:select-ns` selector projects from. Each carries `:rf.provenance/ns` (the source-code namespace — selection is **by this string**, never by the registration-id's namespace), `:kind`, `:id`, and a `:handler-fn` sentinel.
- `:standards` — an optional vector of `[kind id]` framework standards the runner `register-standard!`s before assembling (the protected base — an app descriptor colliding with one fails loud).
- `:images` — a vector of `rf/image` spec maps (`:id` / `:select-ns` / `:registrations`, or — for the negative cases — a retired key or a malformed inline tuple). **Omit** `:images` (or pass `:default? true`) for the default-image path (the implicit selector over the whole pool).
- `:images-literal` — an alternative to `:images` for the `make-frame` **boundary** case: the runner passes the literal value to `make-frame` (whose `:images []` validation fires before any frame record is created). Used only for the `:images []` → `:rf.error/make-frame-bad-images` fixture.

and **either** `:expect-error <:rf.error/id>` (a fail-loud case — the throw from `rf/image` construction OR `assemble`) **or** positive expectations:

- `:expect-resolves` — a vector of `{:kind :id :coordinate <coord-map>}`; the sealed resolver MUST resolve `[kind id]` to a descriptor whose **descriptor coordinate** equals `:coordinate`. The coordinate is the pure-EDN who-won signal: `{:ns "…"}` for a namespace-selected descriptor, `{:image <id> :inline [section id]}` for an inline one, `{:standard true}` for a framework standard.
- `:expect-present` / `:expect-absent` — vectors of `[kind id]` that MUST / MUST NOT be resolver keys.
- `:expect-kinds` — the exact `:rf.gen/kinds` set.
- `:expect-gen-absent` — top-level generation keys that MUST NOT appear on the sealed generation (e.g. the retired `:rf.gen/requires` image-capability slot).
- `:expect-shadows` — the exact `:rf.gen/shadows` report vector (each entry is `{:registration [kind id] :image <loser> :shadowed-by <winner>}`, deterministically ordered; a chain names the **final** winner per loser).

Inline `:registrations` bodies cannot be host fns in pure EDN, so the runner realises each inline body to a **fresh** host no-op (a distinct fn per entry, so two inline entries for one `[kind id]` stay distinct registrations rather than deduping). Impl identity is never asserted — who-won is read from the descriptor coordinate, which is independent of the body. Fixtures using `:assemble-image` declare `:core/image`. The op `register-standard!`/`clear-standards!`/`clear-generation-cache!`s around each call so a standard or cached generation from one call never leaks into the next.

> **Default-image scope note.** The op's omit-`:images` / `:default? true` branch exercises the **shipped** pure assembler (`assemble-default`): the whole-pool projection + framework standards, with a cross-namespace `[kind id]` collision failing loud. EP-0026 also rules that `make-frame {}` (omit `:images` at the *frame* boundary) should resolve the default generation, but that omit→default boundary wiring is **deferred** (follow-up bead rf2-59orj0): the shipped `make-frame {}` still carries no generation. The `image-default-image.edn` fixture deliberately pins only the shipped assembler path + the `:images []` error, not the deferred `make-frame {}`=default behavior.

### Capability tagging

Per [Goal 6 — Hierarchical FSM substrate with implementor-chosen capabilities](../000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) and [005 §Capability matrix](../005-StateMachines.md#capability-matrix), conformance is **graded against each port's claimed capability list** rather than all-or-nothing. Every fixture self-declares which capabilities it exercises via `:fixture/capabilities`. The harness only runs fixtures whose capability set is a subset of the port's claimed list; un-runnable fixtures are reported as "not exercised" rather than "failed."

Capability tag conventions:

- `:core/*` — pattern-required basics every conformant port supports (event handler, frame, dispatch envelope, sub, trace, fx, error). `:core/image` is the EP-0026 image-API surface (`:select-ns` selection, image-order layering, the shadow report, the collision / retired-key / inline-grammar fail-loud taxonomy, the default-image projection) exercised by the `image-*.edn` fixtures via the `:assemble-image` call op.
- `:fsm/*` — FSM-richness axis (`:fsm/flat`, `:fsm/hierarchical`, `:fsm/eventless-always`, `:fsm/delayed-after`, `:fsm/tags`, `:fsm/parallel-regions`, `:fsm/final-states`, `:fsm/registration-validation`).
- `:actor/*` — actor-model axis (`:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/declarative-spawn`, `:actor/spawn-and-join`, `:actor/system-id`).
- `:routing/*`, `:ssr/*`, `:schemas/*` — per-spec capabilities for ports that ship them.

A flat-FSM-only port declares `:capabilities #{:core/event-handler ... :fsm/flat :actor/own-state :actor/spawn-destroy ...}` in its harness manifest; the corpus runs every fixture whose capabilities are a subset and skips the rest. The aggregate score is `passed / claimed-applicable` — an accounting of what works for the claimed list.

A port's harness MUST distinguish two flavours of out-of-claim capability so silent rot can't mask coverage gaps (per ``):

- **Intentional out-of-claim** — the port's `claimed-capabilities` deliberately excludes the capability (e.g. a flat-FSM-only port skipping `:fsm/hierarchical`). The harness MAINTAINS an explicit `known-skipped-capabilities` allowlist; capabilities in this set produce "skipped (out-of-claim)" reports without failing the suite.
- **Typo / claim-set drift** — a fixture's capability appears in neither the claimed set nor the allowlist. The harness silently skipped these; the contract is that the suite FAILS with a diagnostic naming the unknown capability. The remedy is either to add the capability to `claimed-capabilities` (with the runtime backing to match) or to add it to `known-skipped-capabilities` (an explicit decision not to claim it).

The reference harness (`implementation/core/test/re_frame/conformance_test.clj` and `implementation/core/test/re_frame/conformance_corpus_cljs_test.cljs`) keeps `known-skipped-capabilities` as an empty set today: every capability referenced by a corpus fixture is also claimed. The allowlist exists so that any future divergence requires an explicit decision rather than silent drift.

See [§Capability tagging worked example](#capability-tagging-worked-example) immediately below for the actual tags carried by representative fixtures in the corpus today.

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
| `http-managed-get-success.edn` | `#{:core/event-handler :core/sub :core/fx :rf.http/managed}` | The managed-HTTP fx happy path. Builds on the core triad and adds the managed-HTTP capability from [Spec 014](../014-HTTPRequests.md). |

Read the chosen tag namespaces from the [conventions list above](#capability-tagging) and refer back to this table when authoring a new fixture or a port's harness manifest: copy the tag from the closest existing fixture rather than coining a new one.

### Handler bodies as data

Since the corpus is host-agnostic, handler bodies can't be CLJS lambdas — they must be data the host realises into native closures.

The convention:

```clojure
;; A handler body is a small DSL the corpus harness interprets.
{:counter/initialise [[:set [:count] 0]]                      ;; sets db's :count to 0
 :counter/inc        [[:update [:count] [:fn :inc]]]}         ;; updates db's :count via :inc
```

The harness in each host implements a small interpreter for this DSL — `[:set path value]`, `[:update path fn]`, `[:dispatch event]`, etc. A complete interpreter is ~50 lines per host. The DSL is itself versioned and described by `:rf/fixture-handler-body` in [Spec-Schemas](../Spec-Schemas.md#rffixture-file).

This keeps the corpus pure data; no host-specific code ships in the fixtures.

### Handler-body DSL ops

The complete DSL operator set. The schemas live in [Spec-Schemas §`:rf/fixture-handler-body`](../Spec-Schemas.md#rffixture-file); below is the operator reference.

**Data ops** (mutate or read `db`):

| Op | Signature | Meaning |
|---|---|---|
| `[:set path value]` | path = vector of keywords | `assoc-in` `db` at `path` with the literal `value` (resolved via the value DSL below). |
| `[:update path fn-form]` | path = vector; `fn-form` = `[:fn ...]` | `update-in` `db` at `path` applying `fn-form`. |
| `[:get path]` | path = vector | (Sub bodies) read `db` at `path` and return the value. |
| `[:reduce-input sub-id reduce-fn-form map-fn-form]` | ids/fn-forms | (Sub bodies) compute `(reduce reduce-fn (map map-fn input-sub-value))`. |

**Effect ops** (emit fx):

| Op | Signature | Meaning |
|---|---|---|
| `[:fx fx-id args]` | id, args | Emit a single fx as if returned in `:fx`. |
| `[:fx [[fx-id args] [fx-id args] ...]]` | vector of pairs | Emit multiple fx in declaration order. |
| `[:dispatch event-vec]` | event vector | Convenience for `[:fx :dispatch event-vec]`. |
| `[:dispatch-sync event-vec]` | event vector | (fx handler bodies) invokes `dispatch-sync` synchronously through the runner. The router's `:in-drain?` guard catches the call and emits `:rf.error/dispatch-sync-in-handler` (Cross-Spec Interaction §14). Used by fixtures pinning the ban; outside of that the `:dispatch` async path is the canonical chain shape. |
| `[:reg-frame-capture path frame-id config]` | get-in path, frame-id, frame-config | (fx handler bodies) invokes `reg-frame` mid-cascade (the `do-fx` walk runs under the router's handler scope) and CAPTURES the thrown `:rf.error/id` into the originating frame's app-db at `path` (`:rf/no-error` when no throw). Pins the EP-0027 handler-time construction guard (`:rf.error/frame-construction-in-handler`) as a pure-data `:final-app-db` observable — a user fx throw is otherwise swallowed by `do-fx` as `:rf.error/fx-handler-exception`, never surfacing the guard's own discriminator. |
| `[:reset-frame-capture path frame-id]` | get-in path, frame-id | (fx handler bodies) invokes `reset-frame!` mid-cascade and CAPTURES the thrown `:rf.error/id` into app-db at `path` (`:rf/no-error` when no throw), the same capture shape as `:reg-frame-capture`. Pins the EP-0027 handler-time reset guard (`:rf.error/frame-reset-in-handler`). |

In addition, `:fixture/dispatches` accepts two harness-level map forms (alongside event-vector entries) that drive registrar-level operations between dispatches:

| Form | Meaning |
|---|---|
| `{:destroy-frame <frame-id>}` | Call `destroy-frame!` on the named frame. The machine-cascade teardown hook fires (`:rf.machine.lifecycle/destroyed` per active machine), the sub-cache disposes, the substrate releases frame-scoped resources, and `:rf.frame/destroyed` trace fires. Used by Cross-Spec Interaction §1's fixture. |
| `{:reset-frame <frame-id>}` | Call `reset-frame!` on the named frame (EP-0027 §Reset). Re-dispatches the frame's RECORDED `:initial-events` through the CURRENT handlers — a destroy + re-register of the durable construction config (best-effort, no snapshot), re-emitting `:rf.frame/created`. Returns the frame to its constructed state regardless of any runtime mutation. Used by `construction-reset-replays-initial-events.edn`. |
| `{:reg-sub <sub-id> :body <sub-body-dsl>}` | Re-register the sub with a new body realised via the conformance sub-DSL interpreter. The registrar's replacement hook fires (`:rf.registry/handler-replaced` with `:kind :sub`), invalidating the cache slot for that query-id. Used by Cross-Spec Interaction §18's fixture. |
| `{:event <event-vec> :expect-error <:rf.error/id> & opts}` | Dispatch `:event` (forwarding any remaining opts, e.g. `:rf.cofx` / `:rf.world/inputs`) and assert it **throws** an error whose `:rf.error/id` ex-data slot equals `:expect-error`. For boundary / context-assembly errors that escape `dispatch-sync` before any handler runs — the EP-0017 cofx delivery errors (`:rf.error/missing-required-cofx`, `:rf.error/unregistered-cofx`) and the `:rf.world/inputs` retirement (`:rf.error/world-inputs-renamed`). The same `:expect-error` convention the Mode-B `:reg-machine` / `:path-instantiate` ops use, lifted to Mode-A dispatches. A dispatch that does not throw, throws a different `:rf.error/id`, or throws a generic (non-`ex-info`) error is a fixture failure. (A dispatch WITHOUT `:expect-error` that throws still propagates as a fixture-level error.) |

**Control / failure ops:**

| Op | Signature | Meaning |
|---|---|---|
| `[:throw "message"]` | string | Throw a host-native exception with the given message. Used by error fixtures. |
| `[:return-raw value]` | any | (event-fx only) Return `value` **verbatim** as the handler's effect-map — reflection forms inside it are resolved, but the well-shaped `{:db .. :fx ..}` builder is bypassed entirely. This is the only op that can author a **malformed** effect-map, so it is the seam the proactive fx shape-policing negative fixtures use (`effect-map-shape-*.edn`, `effect-handler-bad-return.edn`). The `:set` / `:update` / `:fx` ops always produce a well-shaped map, so those categories were unreachable from a fixture before this op. A body carrying `:return-raw` is always realised as event-fx so the raw return reaches the runtime's shape-policing site (`commit-fx-effects` in the CLJS reference); any sibling steps are ignored — the raw return is the whole result. |
| `[:noop]` | — | Explicit no-op; useful for default fx registrations the fixture overrides. |

**Reflection / value ops** (used as arguments to data ops; not standalone steps):

| Form | Meaning |
|---|---|
| `[:event-arg n]` | The n-th element of the event vector (0-based). `[:event-arg 1]` is typical for the first user-supplied arg. |
| `[:event-arg n default-val]` | The n-th element of the event vector; `default-val` if the n-th element is `nil`. The 3rd element is **always** a default-for-nil — it is never type-dispatched, even when it's a keyword and the resolved value happens to be a map. For key-access into a map argument, use `:get-event-arg`. |
| `[:get-event-arg n :key]` | `:key`-access into the n-th element of the event vector — equivalent to `(get (nth event n) :key)`. The n-th element is expected to be a map. |
| `[:get-event-arg n :key default-val]` | Same as above with a default if `:key` is missing or its value is `nil`. |
| `[:fn :keyword]` | Reference a host-builtin function by keyword. |
| `[:fn :keyword arg1 arg2 ...]` | Partial application: `[:fn :conj :should-not-fire]` is `(fn [x] (conj x :should-not-fire))`. |

#### Handler-body DSL builtins

The reserved set of `:fn` builtins each host implements. The corpus uses only registered builtins. This is the canonical fixture-spec-1.0 builtin vocabulary; the reference interpreter (`re-frame.conformance`) and `spec/Spec-Schemas.md` carry the same set. The table is grouped by purpose so future additions are visibly additive.

| Group | Builtin | Arity | Maps to |
|---|---|---|---|
| numeric | `:inc` | 1 | numeric increment (nil-tolerant — implicit-zero start) |
| numeric | `:dec` | 1 | numeric decrement (nil-tolerant — implicit-zero start) |
| numeric | `:+`, `:-`, `:*`, `:/` | 2+ | arithmetic |
| comparison | `:>=`, `:<=`, `:>`, `:<` | 2 (with partial second arg) | numeric comparison |
| equality | `:=`, `:not=` | 2 (with partial second arg) | value (in)equality |
| boolean | `:and`, `:or` | 1+ | boolean conjunction / disjunction (truthy) |
| boolean | `:not` | 1 | boolean negation |
| collection | `:conj` | 1 (with partial second arg) | append-to-collection |
| collection | `:assoc` | 2 (with partial keys/values) | map assoc |
| collection | `:dissoc` | 1 (with partial keys) | map dissoc |
| collection | `:count` | 1 | collection count (throws on string/char/nil — used by the sub-exception fixtures) |
| identity | `:identity` | 1 | identity |
| fixture | `:item-amount` | 1 | `(* (:qty item) (:price item))` — used by `sub-chain.edn` |

Implementations register each builtin by name during harness bootstrap. The set is stable and additive — new builtins may be added in subsequent fixture spec versions; existing builtins cannot be redefined. (No type-predicate builtins — `:keyword?` / `:number?` / `:string?` — are in fixture-spec-1.0; a future revision that needs them adds them additively.)

### Fixture lifecycle

Each fixture defines an **invariant the implementation upholds**. The harness:

1. **Bootstraps the registrar** — for each kind in `:fixture/registry`, register every id with the supplied metadata.
2. **Realises handler bodies** — for each `:fixture/handlers` entry, interpret the DSL ops into a host-native closure and bind it to the id under the kind.
3. **Creates the frame** — apply `:fixture/frame-config` via `make-frame` (or the host equivalent); this fires the frame's `:initial-events` seeded into it.
4. **Runs `:fixture/dispatches`** — one event vector per call, each via `dispatch-sync`. Each settles to fixed point before the next.
5. **Runs `:fixture/calls`** (if present) — direct invocations of pure primitives (`machine-transition`, `reg-machine`, `reg-frame`, `match-url`, `route-url`, `render-to-string`, `round-trip`, `assert-rank-greater`, and the data-classification projector ops). Each call carries its own expectation; mismatches surface as fixture-level failures.
6. **Captures observables** (Mode A) — final `app-db`, sub values (per `:fixture/expect :sub-values`), trace events emitted, effects routed.
7. **Compares** (Mode A) — partial-match per assertion. `:trace-emissions` partial-matches each trace event by its specified keys; absent keys are ignored. `:final-app-db` is a **submap** compare (declared keys must match; extra actual keys tolerated); `:final-app-db-absent` (a vector of `get-in`-shaped paths) asserts each path's tip key is ABSENT. `:effects-routed` matches the routed-fx pairs in declaration order. A dispatch carrying `:expect-error` asserts that dispatch threw the named `:rf.error/id`.

For Mode B fixtures, comparison happens inline at each call; there is no top-level `:fixture/expect` to evaluate after drain.

The harness reports per-fixture pass/fail; aggregate score is the count of passing fixtures over total fixtures.

Each fixture is **a single file** of <200 lines including registrations and expectations. Adding a fixture is a small focused change.

## How an implementation runs the corpus

1. Read all `.edn` files in `fixtures/`.
2. For each fixture:
   a. Bootstrap the host's runtime with the fixture's registry.
   b. Realise handler bodies via the DSL interpreter.
   c. **If `:fixture/dispatches` is present (Mode A):**
      - Create a frame per `:fixture/frame-config`.
      - Run each dispatch.
      - After drain, capture: final `app-db`, sub values, emitted trace events, effects routed.
      - Compare actuals against `:fixture/expect`.
   d. **If `:fixture/calls` is present (Mode B):**
      - For each call record, invoke the named primitive with the supplied arguments.
      - Compare the result against the call-local expectation (`:expect`, or operation-specific keys like `:expect-next-snapshot` + `:expect-effects` for `:machine-transition`, or `:expect-error` for `:reg-machine` / `:reg-frame`).
3. Report pass/fail per fixture; total conformance score.

A conformant runner asserts not only `(zero? failed)` but a **non-zero runnable-fixture floor** (`(pos? count-run)` plus a below-current expected minimum): an empty or all-skipped corpus must go RED rather than pass vacuously having exercised nothing. The corpus IS the spec-validation mechanism, so a path bug, a fixtures-dir rename, or a capability-vocabulary rename that orphans every fixture is a hard failure, not silent green (rf2-3hamsq).

The harness is small (~300 lines per host).

## Versioning

The corpus is versioned alongside the spec. Each fixture file declares the spec version it was authored against:

```clojure
{:fixture/id        :counter/inc-once
 :fixture/spec-version "1.0"
 ...}
```

When the spec changes shape (new required key in `:rf/dispatch-envelope`, new error category), affected fixtures bump their `:spec-version` and the corpus's harness check rejects implementations that haven't moved with the spec.

## Fixtures

See `fixtures/` for the actual files. Each fixture is one EDN file; each exercises one shape of the spec.

| Fixture | `:fixture/id` | Coverage |
|---|---|---|
| `counter-inc-once.edn` | `:counter/inc-once` | Trivial event handler; sub computation; trace emission |
| `counter-inc-multi.edn` | `:counter/inc-multi` | Multi-event drain to fixed point; final state visible to subs |
| `frame-multi-instance.edn` | `:frame/multi-instance` | Multi-frame isolation with shared registrar |
| `frame-lifecycle.edn` | `:frame/lifecycle` | `:initial-events` and `:on-destroy` events; lifecycle trace emissions |
| `construction-initial-events-ordered.edn` | `:construction/initial-events-ordered` | EP-0027 §Construction: a frame's `:initial-events` drain SYNCHRONOUSLY, in declaration order, each settling before the next, BEFORE the constructor returns; the frame's app-db is fully settled by the time it is observable |
| `construction-set-db-then-init-event.edn` | `:construction/set-db-then-init-event` | EP-0027 §`:rf/set-db` + §Construction: the framework-standard `[:rf/set-db {…}]` seeds app-db as the first setup step; a follow-up init event in the SAME cascade reads the seeded db (the seed is an ordinary in-order setup step, not an out-of-band channel) |
| `construction-reset-replays-initial-events.edn` | `:construction/reset-replays-initial-events` | EP-0027 §Reset (Spec 002 §`reset-frame!`): the `{:reset-frame <frame-id>}` harness step re-dispatches the frame's RECORDED `:initial-events` through the CURRENT handlers, returning a runtime-mutated frame to its constructed state; the reset is a destroy + re-register so it re-emits `:rf.frame/created` (a replay that bypassed the construction engine would not) |
| `construction-provenance.edn` | `:construction/provenance` | EP-0027 §Provenance: each `:initial-events` setup dispatch carries `:source :frame-init` (hoisted top-level on the `:rf.event/dispatched` trace) + its 0-based `:rf.frame/init-step-index` (under `:tags`), in declaration order; a subsequent runtime dispatch is NOT `:frame-init` — the tags discriminate construction from runtime |
| `construction-initial-events-map-form.edn` | `:construction/initial-events-map-form` | EP-0027 §Construction: a `:initial-events` step may be the map form `{:event … :opts …}`; the step's `:opts {:rf.cofx {:rf/time-ms …}}` passes the recordable-coeffect envelope through the ordinary `dispatch-sync` opt surface, read by the handler verbatim. The bare-vector and map step shapes compose in one config |
| `construction-shape-validation.edn` | `:construction/shape-validation` | EP-0027 §Strict shape (Mode-B `:reg-frame` call op): `reg-frame` preflight-validates `:initial-events` and fails loud with the right `:rf.error/id` discriminator — `:rf.error/initial-events-bare-event` / `-bad-step` / `-bad-event` / `-bad-opts` — leaving no frame registered; a well-formed config is the must-NOT-throw control |
| `construction-retired-keys-fail-loud.edn` | `:construction/retired-keys-fail-loud` | EP-0027 §Retirement (Mode-B `:reg-frame` call op): the retired construction keys `:on-create` / `:initial-db` fail loud at `reg-frame` with stable discriminators `:rf.error/on-create-retired` / `:rf.error/initial-db-retired` (never a silent ignore); a well-formed `:initial-events` config is the must-NOT-throw control |
| `construction-in-handler-guard.edn` | `:construction/in-handler-guard` | EP-0027 §Handler-time guard: `reg-frame` called while an event-handler cascade is in flight fails loud `:rf.error/frame-construction-in-handler`. An fx body invokes `reg-frame` mid-cascade via the `:reg-frame-capture` op, which captures the thrown `:rf.error/id` into app-db (the guard throw is otherwise swallowed by `do-fx` as `:rf.error/fx-handler-exception`); `:final-app-db` pins the construction-guard discriminator |
| `construction-reset-in-handler-guard.edn` | `:construction/reset-in-handler-guard` | EP-0027 §Reset (handler-time guard): `reset-frame!` called mid-cascade fails loud `:rf.error/frame-reset-in-handler` BEFORE any teardown, leaving the frame alive and unchanged. An fx body invokes `reset-frame!` mid-cascade via the `:reset-frame-capture` op, capturing the thrown `:rf.error/id` into app-db; `:final-app-db` pins the dedicated discriminator (NOT the construction-in-handler id, which names the wrong cause) and the untouched app-db |
| `dispatch-envelope.edn` | `:dispatch/envelope` | Envelope shape (`:event`, `:frame`, `:source`, `:trace-id`) surfacing in cofx |
| `cofx-envelope-preserved.edn` | `:cofx/envelope-preserved` | EP-0017 Slice-A: the dispatch envelope's `:rf.cofx` recordable-coeffect map is delivered into the event context under `:rf.cofx`, preserved verbatim (a caller-supplied `:rf/time-ms` rides through unchanged). Expected app-db INCLUDES `:rf.cofx` — the `dispatch-envelope.edn` fixture's expected output omits it, so a port could drop the key and still pass the weaker fixture |
| `cofx-declared-only-delivery.edn` | `:cofx/declared-only-delivery` | EP-0017 Slice-A: a handler receives EXACTLY its `:rf.cofx/requires` declarations, flat; a REGISTERED-but-undeclared coeffect is withheld. The `:final-app-db-absent` check FAILS a port that over-delivers (submap matching alone tolerates the extra) |
| `cofx-missing-vs-unregistered.edn` | `:cofx/missing-vs-unregistered` | EP-0017 Slice-A: a declared-but-absent REGISTERED provided coeffect is `:rf.error/missing-required-cofx`; a declared coeffect with NO registration is `:rf.error/unregistered-cofx`. Two distinct error ids (per-dispatch `:expect-error`) — a port that conflates them, throws generically, or fails to throw FAILS |
| `cofx-world-inputs-retired.edn` | `:cofx/world-inputs-retired` | EP-0017 Slice-A: the retired `:rf.world/inputs` dispatch opt is the hard error `:rf.error/world-inputs-renamed` (per-dispatch `:expect-error`); the canonical `:rf.cofx` replacement is accepted (control dispatch runs). A port that aliases the retired key or fails to reject it FAILS |
| `drain-depth-limit.edn` | `:drain/depth-limit` | Drain-depth-exceeded error + `:rf.error/drain-depth-exceeded` trace |
| `sub-chain.edn` | `:sub/chain` | `:<-` chained subs; static dependency topology |
| `fx-db-first.edn` | `:fx/db-first` | `:db` commits atomically before any `:fx` entry runs; the first `:fx` entry's handler observes the post-`:db` state |
| `fx-ordering-source-order.edn` | `:fx/ordering-source-order` | `:fx` entries process in source order; the dispatched events accumulate in the runtime FIFO in the same order |
| `fx-override-by-id.edn` | `:fx/override-by-id` | Pattern-level id-valued override seam |
| `fx-platforms.edn` | `:fx/platforms` | `:platforms` gating on `reg-fx` for SSR |
| `error-handler-exception.edn` | `:error/handler-exception` | Structured `:rf.error/handler-exception` trace + cascade halt |
| `error-no-such-handler.edn` | `:error/no-such-handler` | Dispatch with no registered handler; cascade continues |
| `error-schema-failure.edn` | `:error/schema-failure` | `:rf.error/schema-validation-failure` (dynamic-host only) |
| `error-fx-handler-exception.edn` | `:error/fx-handler-exception` | `:rf.error/fx-handler-exception` (fx throws during effect resolution) |
| `error-sub-exception.edn` | `:error/sub-exception` | `:rf.error/sub-exception` (sub computation throws) |
| `error-override-fallthrough.edn` | `:error/override-fallthrough` | `:rf.error/override-fallthrough` (override id is not registered) |
| `effect-map-shape-bad-top-level-key.edn` | `:effect-map-shape/bad-top-level-key` | Spec 009 §`:rf.error/effect-map-shape` case (a): a non-`:db`/`:fx` top-level key is proactively policed (`:offending-key` flagged, dropped, `:db` still applies); a fail-closed gate that never throws |
| `effect-map-shape-bad-fx-value.edn` | `:effect-map-shape/bad-fx-value` | Spec 009 case (b): a non-sequential `:fx` value (`{:fx :oops}`) is policed + dropped, `:db` still applies, **no** raw host exception after the commit |
| `effect-map-shape-bad-fx-entry.edn` | `:effect-map-shape/bad-fx-entry` | Spec 009 case (c): a bare-keyword `:fx` entry is policed + dropped while **sibling entries still run** — the precise silent-drop case |
| `effect-map-shape-surplus-entry-field.edn` | `:effect-map-shape/surplus-entry-field` | Spec 009 case (c): a 3-field `:fx` entry is dropped wholesale (NOT silently truncated to a 2-tuple + fired) — the other half of the same fix |
| `effect-handler-bad-return.edn` | `:effect-handler/bad-return` | Spec 009 §`:rf.error/effect-handler-bad-return`: a non-map / non-`nil` handler return is policed + no-op'd; `nil` stays the legal no-op (control) |
| `ssr-hydration-mismatch.edn` | `:ssr/hydration-mismatch` | `:rf.ssr/hydration-mismatch` (server-hash ≠ client-hash) |
| `machine-transition.edn` | `:rf.machine/transition` | Pure machine-transition; canonical grammar (`{:state :data}` snapshot, single-fn `:action` slot) |
| `hierarchical-compound-transition.edn` | `:machine/hierarchical-compound-transition` | Sibling-leaf transition inside a compound; verifies the LCA (parent) does NOT exit/re-enter |
| `hierarchical-cross-level-transition.edn` | `:machine/hierarchical-cross-level-transition` | Deeply-nested leaf to top-level sibling; LCA-based exit cascade fires every intermediate ancestor deepest-first; vector-form `:target`. |
| `hierarchical-parent-fallthrough.edn` | `:machine/hierarchical-parent-fallthrough` | Deepest-wins resolution: event declared on a parent is found via leaf-up walk; leaf-level handler overrides; unknown event leaves snapshot unchanged. |
| `spawn-on-entry-destroy-on-exit.edn` | `:machine/spawn-on-entry-destroy-on-exit` | Declarative `:spawn` on a state node desugars at registration time to entry/exit `:rf.machine/spawn` / `:rf.machine/destroy` fx; verifies the spawn fx args, the `:on-spawn` callback updating the parent's `:data`, and the exit destroy fx targeting the recorded child id. |
| `spawn-ordering-deterministic-in-one-cascade.edn` | `:machine/spawn-ordering-deterministic-in-one-cascade` | EP-0029 A7 ordering lock: two `:spawn`-bearing nodes crossed by one entry cascade (an outer compound `:spawn` whose `:initial` descendant also `:spawn`s) allocate shallowest-first — the outer is `#1` and its fx fires first, the inner is `#2`, the shared `:rf/spawn-counter` ends at 2. Deterministic, declaration-order allocation at the pure machine-transition layer. |
| `always-single-microstep.edn` | `:machine/always-single-microstep` | Eventless `:always` transition fires once after entry under a guard that just became true; one microstep settles to the target; verifies microstep counter and per-microstep + macrostep trace events; the intermediate "guard now true" state is NOT externally observable (atomic commit). |
| `always-depth-exceeded.edn` | `:machine/always-depth-exceeded` | A cycle of `:always` transitions across two states never settles; the microstep loop hits the configured `:always-depth-limit` (5 in this fixture) and emits `:rf.error/machine-always-depth-exceeded`. Snapshot uncommitted; recovery is `:no-recovery`. |
| `after-single-delay.edn` | `:machine/after-single-delay` | Single `:after` entry fires after the configured delay; verifies `:rf.machine.timer/scheduled` at state entry, `:rf.machine.timer/fired` at expiry, and the snapshot transitions to the target after the test-clock advances past the delay. |
| `after-stale-detection.edn` | `:machine/after-stale-detection` | A real `:on` event arrives before the `:after` timer expires; the machine transitions out of the state; the epoch advances; the eventual timer firing is detected as stale (`:rf.machine.timer/stale-after`) and silently ignored. The "real event beats the timer" race the epoch mechanism handles. |
| `after-hierarchy.edn` | `:machine/after-hierarchy` | `:after` on a parent compound state remains active while the snapshot is in any child; sibling-leaf transitions inside the parent do NOT cancel the parent's `:after`. Exiting the parent advances the epoch and the parent-level timer's eventual firing is stale. |
| `machine-reg-error-compound-state-missing-initial.edn` | `:machine/reg-error-compound-state-missing-initial` | `:reg-machine` Mode-B: a compound state-node declaring `:states` but no `:initial` is rejected at registration with `:rf.error/machine-compound-state-missing-initial`; the `:initial`-bearing control validates silently. |
| `machine-reg-error-always-self-loop.edn` | `:machine/reg-error-always-self-loop` | `:reg-machine` Mode-B: an `:always` entry whose keyword/vector `:target` resolves to its own declaring state throws `:rf.error/machine-always-self-loop`; an internal (no-`:target`) `:always` and a sibling-target `:always` validate silently. |
| `machine-reg-error-final-state-shape.edn` | `:machine/reg-error-final-state-shape` | `:reg-machine` Mode-B: a compound `:final?` state, a transition-bearing `:final?` state, and a non-final `:output-key` state throw `:rf.error/machine-final-state-compound` / `-final-state-has-transitions` / `-output-key-without-final` respectively; well-formed `:final?` leaves (with `:output-key` / `:entry` / `:exit`) validate silently. |
| `machine-reg-error-spawn-all-shape.edn` | `:machine/reg-error-spawn-all-shape` | `:reg-machine` Mode-B: a node declaring both `:spawn` and `:spawn-all` throws `:rf.error/machine-spawn-all-with-spawn`; two `:spawn-all` children sharing an `:id` throw `:rf.error/machine-spawn-all-duplicate-id`; the distinct-`:id` control validates silently. |
| `routing-match-url.edn` | `:routing/match-url` | Bidirectional URL ↔ params; round-trip property |
| `routing-navigate.edn` | `:routing/navigate` | `:rf.route/navigate` updates `app-db` + emits `:rf.nav/push-url` fx |
| `route-ranking-precedence.edn` | `:routing/ranking-precedence` | Deterministic 6-rule ranking cascade resolves overlapping routes; equal-score warning |
| `route-stale-nav-token-suppression.edn` | `:routing/stale-nav-token-suppression` | Older route load arrives after a fresh navigation; the carried nav-token is stale; runtime suppresses the result and emits `:rf.route.nav-token/stale-suppressed` |
| `route-fragment-change.edn` | `:routing/fragment-change` | `:fragment` is part of the route slice; fragment-only changes do NOT re-fire `:on-match`; path changes do |
| `route-navigation-blocked.edn` | `:routing/navigation-blocked` | `:can-leave` guard rejects a navigation; `:rf/pending-navigation` is set; URL unchanged; `:rf.route/continue` resumes; `:rf.route/cancel` abandons |
| `ssr-render-to-string.edn` | `:ssr/render-to-string` | Pure hiccup → HTML emission with text/attr escaping; void elements; doctype option |
| `ssr-hydrate.edn` | `:ssr/hydrate` | `:rf/hydrate` seeds `app-db` from server payload; subsequent dispatches operate on hydrated state |
| `ssr-redirect.edn` | `:ssr/redirect` | `:rf.server/redirect` truncates HTML; response carries `:status 302` + `:location` only (per [011 §Redirect precedence](../011-SSR.md#redirect-precedence)) |
| `ssr-set-status.edn` | `:ssr/set-status` | `:rf.server/set-status 404` populates the response accumulator; HTML body still renders |
| `ssr-cookie.edn` | `:ssr/cookie` | `:rf.server/set-cookie` adds a structured cookie to `:cookies`; host adapter would serialise to `Set-Cookie:` |
| `ssr-head-emits.edn` | `:ssr/head-emits` | Active route's `:head` resolves to a registered head fn; rendered HTML's `<head>` contains the expected title/meta/link tags |
| `ssr-head-hydration.edn` | `:ssr/head-hydration` | Hydration payload carries the unified `:rf/render-hash` (covering head + body in v1); client recomputes; matches; no `:rf.ssr/hydration-mismatch` (`:failing-id :rf.ssr/head-mismatch`) emitted. A dedicated `:rf/head` / `:rf/head-hash` payload channel is reserved for the post-v1 `reg-head` extension. |
| `ssr-error-sanitisation.edn` | `:ssr/error-sanitisation` | Handler throws; trace carries full detail; public response shape is locked generic-500; rendered HTML contains no internal detail |
| `ssr-error-known-mapping.edn` | `:ssr/error-known-mapping` | Default projector maps `:rf.error/no-such-handler` (routing context) → `{:status 404 :code :not-found ...}` public-error |
| `epoch-record-shape.edn` | `:epoch/record-shape` | Per-dispatch `:rf/epoch-record` carries `:event-id`, `:trigger-event`, `:db-before` / `:db-after` snapshot pair, and `:outcome :ok` — the Tool-Pair time-travel contract |
| `epoch-ring-multi-dispatch.edn` | `:epoch/ring-multi-dispatch` | Multiple settled drains append to the epoch-history ring in oldest-first order; each record's `:db-before` chains from the previous `:db-after` |
| `trace-buffer-filter-categories.edn` | `:trace-buffer/filter-categories` | One cascade reaches all four trace-bus categories — `:rf.event` (lifecycle), `:rf.fx/handled`, `:rf.sub/run`, `:rf.error/handler-exception` — so the trace-buffer's filter axes (`:op-type` / `:operation` / `:severity`) have reachable data per category |
| `view-registration.edn` | `:view/registration` | `reg-view` registers a view body that consumes a sub; the registrar accepts the registration and the dependent sub returns the live post-drain value (the data a render-trigger would consume). Render-time observables remain out of scope per the §Render-time observables note below |
| `http-interceptor-before-transforms.edn` | `:rf.http.interceptor/before-transforms` | `:rf.fx/reg-http-interceptor` registers a request-side `:before` interceptor (Spec 014 §Middleware); the `:rf.http.interceptor/registered` trace fires at registration, and the body executes against the live ctx on a subsequent canned-success request — observable via a marker the body dispatches into app-db |
| `http-interceptor-clear.edn` | `:rf.http.interceptor/clear` | `:rf.fx/clear-http-interceptor` unregisters an interceptor by id; the `:rf.http.interceptor/cleared` trace fires; a subsequent request runs WITHOUT the cleared body — the dispatched-marker counter advances on the pre-clear request and STAYS at one through the post-clear request |
| `routing-history-popstate.edn` | `:routing/history-popstate` | Popstate-driven URL change (`[:rf.route/handle-url-change url]`): URL round-trips into `:rf/route` slice; `:rf.nav/scroll` fires with popstate-default `:restore` strategy; runtime does NOT push or replace the URL (it came from the browser) |
| `routing-history-replace.edn` | `:routing/history-replace` | Programmatic navigation with `{:replace? true}` routes through `:rf.nav/replace-url` instead of `:rf.nav/push-url` — replaceState semantics so the back button skips the intermediate URL |
| `hot-reload-handler-replaced-trace.edn` | `:hot-reload/handler-replaced-trace` | Per Spec 001 §Hot-reload trace surface: a re-registration emits `:rf.registry/handler-replaced` carrying `:kind` + `:id` + `:different-fn?`. Exercised through flow re-registration (the only pure-data surface today through which the corpus can re-register a slot mid-dispatch); the trace contract is cross-kind |
| `cross-spec-frame-destroy-with-machines.edn` | `:cross-spec/frame-destroy-with-machines` | Cross-Spec #1 (Frames × Machines): `destroy-frame!` while a singleton machine snapshot is live emits `:rf.machine.lifecycle/destroyed` BEFORE `:rf.frame/destroyed`, carrying `:reason :parent-frame-destroyed` |
| `cross-spec-machine-microstep-subscribe.edn` | `:cross-spec/machine-microstep-subscribe` | Cross-Spec #2 (Frames × Machines): subs return the COMMITTED post-cascade snapshot, never an intermediate microstep value. The sub-cache invalidation fires once after macrostep commit, not per-microstep |
| `cross-spec-machines-under-ssr.edn` | `:cross-spec/machines-under-ssr` | Cross-Spec #4 (Machines × SSR): under `:platform :server`, entering an `:after`-bearing state emits `:rf.machine.timer/skipped-on-server` in place of `/scheduled` — no host-clock timer is installed; the machine's snapshot still lands at the `:after`-bearing state |
| `cross-spec-dispatch-sync-in-handler.edn` | `:cross-spec/dispatch-sync-in-handler` | Cross-Spec #14 (Drain loop × Substrate): a handler that triggers `dispatch-sync` mid-drain (directly or transitively via a naive fx-side chain) trips the router's `:in-drain?` guard and emits `:rf.error/dispatch-sync-in-handler` with `:no-recovery`; the would-be leaf handler does NOT run. Pins the substrate-level ban; render-time observables are out-of-scope so the fx path is the corpus's testable seam |
| `cross-spec-server-error-projection.edn` | `:cross-spec/server-error-projection` | Cross-Spec #16 (Errors × SSR): an fx handler throws on a server frame; the runtime emits `:rf.error/fx-handler-exception` with the original exception detail; the active error projector maps it to the locked generic-500 public-error on the request frame's response accumulator. The trace stream preserves full detail; the public-error is sanitised — the projector IS the sanitisation seam |
| `cross-spec-hot-reload-sub-mid-cascade.edn` | `:cross-spec/hot-reload-sub-mid-cascade` | Cross-Spec #18 (Subscriptions × Hot-reload): re-registering a sub via the harness's `{:reg-sub <id> :body <body>}` step disposes the per-frame cache slot for that query-id; subsequent subscribes build against the new body. The registrar emits `:rf.registry/handler-replaced` with `:kind :sub` + `:id` + `:different-fn?`; the post-replacement sub-value confirms the cache was invalidated and the new body is active |
| `data-classification-frame-sensitive-app-db-redacts.edn` | `:data-classification/frame-sensitive-app-db-redacts` | Spec 015 §Tests: a path classified `:sensitive` via the EP-0025 commit-plane `:sensitive` effect (`:source :effect`), after an event writes a token there, redacts to `:rf/redacted` in the t1 `:rf.event/db-pending` trace slot; an unmarked sibling rides raw; the committed app-db carries the raw value (redaction is trace-egress only) |
| `data-classification-frame-large-app-db-elides.edn` | `:data-classification/frame-large-app-db-elides` | Spec 015 §Tests / §10: a path classified `:large` (commit-plane `:large` effect) elides to the `:rf.size/large-elided` marker (carrying `:path` / `:bytes` / `:type` / `:reason :effect` / `:handle`) in the t1 `:rf.event/db-pending` trace slot; an unmarked sibling rides raw |
| `data-classification-sensitive-wins-over-large.edn` | `:data-classification/sensitive-wins-over-large` | Spec 015 §Tests: a path classified both `:sensitive` and `:large` projects as the bare `:rf/redacted` sentinel — no `:rf.size/large-elided` marker (which would leak path / size / type / handle) is emitted; sensitive dominates the size axis, regardless of classification order |
| `data-classification-clear-axis-independent.edn` | `:data-classification/clear-axis-independent` | Spec 015 §Two independent axes: the four commit-plane effects carry TWO independent axes — `:clear-large` removes a path from the large axis without disturbing its standing `:sensitive` classification (and vice-versa). A both-classified path that is `:clear-large`'d still redacts to `:rf/redacted` (the surviving sensitive axis) — per-axis set/unset symmetry, not a single coupled bit |
| `data-classification-event-arg-sensitive-path-redacts.edn` | `:data-classification/event-arg-sensitive-path-redacts` | Spec 015 §Registration-owned transient classification: a `reg-event-*` with `:sensitive [[:password]]` registration metadata, dispatched with `{:password "secret"}`, projects `[event-id {:password :rf/redacted}]` in the `:rf.event/db-changed` trace `:rf.event/v` slot; the handler body sees the unredacted payload — event args are a registration-owned transient payload, only the trace surface redacts |
| `data-classification-machine-data-declared-redacts.edn` | `:data-classification/machine-data-declared-redacts` | Spec 015 §Subsystem projection-relative classification (EP-0025 final): a `reg-machine` declaring `:sensitive [[:data :token]]` PROJECTION-RELATIVE on its spec (lowered per actor instance at spawn, `:source :machine`), after a transition writes a token into `:data`, redacts `[:data :token]` in the `:rf.machine/transition` / `:rf.machine/snapshot-updated` trace `:after` snapshot; the `[:schemas :data]` schema validates but no longer classifies (the EP-0005 schema→classification bridge is reversed); no frame annotation, no author-side absolute runtime path |
| `data-classification-project-egress-omits-event-args-off-box.edn` | `:data-classification/project-egress-omits-event-args-off-box` | Spec 015 §Tests / issue 4: `project-egress` (Mode-B `:project-egress` call op) of an `:rf.observe/handled-event` record under `:rf.egress/off-box-observability` carries the summary fields and OMITS the `:event` args slot entirely |
| `data-classification-project-egress-fails-closed-no-frame.edn` | `:data-classification/project-egress-fails-closed-no-frame` | Spec 015 §Tests / EP-0002 fail-closed: `project-egress` of a tree value with NO known frame fails closed to `:rf/redacted` — it does not synthesise `:rf/default` |
| `data-classification-observability-sink-receives-projected-record.edn` | `:data-classification/observability-sink-receives-projected-record` | Spec 015 §9: the PROJECTION a frame `:observability` sink consumes — a handled-event record with `:event` omitted off-box, an error record with a frame-sensitive token inside `:event` redacted. The runtime ROUTING leg is pinned e2e by `re-frame.observability-routing-cljs-test` |
| `data-classification-http-carrier-extends-defaults.edn` | `:data-classification/http-carrier-extends-defaults` | Spec 015 §Tests / Spec 014 §Privacy / EP-0025 §HTTP carriers: an app-declared `:rf.http/managed` `:carriers` HTTP header carrier (Mode-B `:redact-headers` call op) redacts IN ADDITION to the immutable built-in defaults; no app can remove a default |
| `data-classification-ssr-hydration-allowlist-first.edn` | `:data-classification/ssr-hydration-allowlist-first` | Spec 015 §Tests / Spec 011 §14: SSR hydration is allowlist-first (Mode-B `:ssr-apply-policy` call op) — only the allowlisted slice crosses; an absent policy fails closed (`:rf.error/ssr-missing-payload-policy`); a sensitive child of an allowlisted slice still redacts under the `:rf.egress/ssr-hydration` profile (defence-in-depth) |
| `data-classification-epoch-export-projects-no-storage-mutation.edn` | `:data-classification/epoch-export-projects-no-storage-mutation` | Spec 015 §15 / disposition 6: the committed epoch record's `:db-after` carries the RAW value (storage-side redaction removed; replay-faithful by construction) while the SAME event's t1 trace egress redacts it — egress redacts, durable storage stays raw |
| `data-classification-route-query-declared-redacts.edn` | `:data-classification/route-query-declared-redacts` | Spec 015 §Tests / Spec 012 §Route data classification (EP-0025 reg-route subsystem row): a `reg-route` declaring `:sensitive [[:query :token]]` PROJECTION-RELATIVE, lowered per-frame at activation (`:source :route`, re-rooted under `[:rf.runtime/routing :current …]`), redacts the slice `:query :token` at egress — `project-egress` (Mode-B `:project-egress` call op) of a route-slice value under `:rf.egress/off-box-observability` projects `:rf/redacted` at the declared path while an unmarked sibling rides verbatim; the committed runtime-db slice carries the raw token |
| `data-classification-route-change-drops-leaving-classification.edn` | `:data-classification/route-change-drops-leaving-classification` | Spec 015 §Tests / Spec 012 §Route data classification: the route SINGLETON-DROP (no machine analogue) — navigating from a `:sensitive` route to a route declaring none clears the leaving route's `:source :route` entries; `project-egress` of the now-current (plain) route-slice value shows the token field rides RAW (no leak forward) |
| `image-select-ns-selection.edn` | `:image/select-ns-selection` | EP-0026 §Namespace Selection (Mode-B `:assemble-image` call op): `:select-ns :include` selects by source-namespace **provenance** (not by the id namespace — the v2/v3 same-id reuse pins it); a **global** `:exclude` subtracts the matched namespaces; a zero-match `:include` pattern fails assembly with `:rf.error/image-zero-match` (even alongside a matching pattern) while an `:exclude` matching nothing is a no-op |
| `image-order-resolution.edn` | `:image/order-resolution` | EP-0026 §Layered Resolution: the **later** image in `:images` wins a cross-image `[kind id]` (the test-doubles override shape); reversing image order reverses the winner; a multi-image chain resolves to the last image; image order is the only precedence (not pool / registrar order). Who-won is read from the resolved descriptor coordinate |
| `image-within-image-collision.edn` | `:image/within-image-collision` | EP-0026 §Layered Resolution: two **selected** descriptors for one `[kind id]` is `:rf.error/image-duplicate-id` (ambiguous, order-independent); two **inline** entries, or an **inline** entry colliding with a **selected** one, is `:rf.error/image-within-image-collision` (an override must be a later image). The same registration selected twice is a dedupe, not a collision |
| `image-duplicate-image-id.edn` | `:image/duplicate-image-id` | EP-0026 §Image Keys: two images sharing an `:id` within one `:images` composition fail loud with `:rf.error/image-duplicate-image-id` (the shadow report identifies images by id); two **anonymous** images (no `:id`) compose cleanly |
| `image-shadow-report.edn` | `:image/shadow-report` | EP-0026 §Shadow Report: a cross-image shadow does **not** fail assembly — it resolves (later wins) and is reported as a flat `{:registration [kind id] :image <loser> :shadowed-by <winner>}` entry; a **chain** names the FINAL winner for every loser; two earlier images shadowed by the same later one is two entries; a disjoint composition reports `[]` |
| `image-standard-collision.edn` | `:image/standard-collision` | EP-0026 §Framework Standard Registrations: a public app descriptor (selected OR inline) colliding with a framework standard's `[kind id]` fails loud with `:rf.error/image-standard-replacement-forbidden`, regardless of image position (even a later image cannot shadow a standard — there is no public `:replace-standard` opt-in); a standard with no colliding app id is unioned in |
| `image-retired-keys.edn` | `:image/retired-keys` | EP-0026 §Image Keys / §Capability Removal: `:include-ns` / `:exclude-ns` / `:replace` / `:replace-standard` / `:rf.image/requires` all fail loud at `rf/image` with `:rf.error/invalid-image` (never aliased, never ignored). The capability removal is **surgical**: a sealed generation carries no `:rf.gen/requires`, and an unrelated `:rf.capability/*`-named registration still assembles fine (the host-service vocabulary is untouched) |
| `image-default-image.edn` | `:image/default-image` | EP-0026 §Default Image (the **shipped** `assemble-default` path): omitting `:images` projects the whole pool + framework standards; an empty pool is a valid empty projection (no zero-match fail); a cross-namespace same-`[kind id]` collision fails loud with `:rf.error/image-duplicate-id` (no last-write-wins); `:images []` is `:rf.error/make-frame-bad-images`. Deliberately does NOT pin the deferred `make-frame {}`=default boundary (rf2-59orj0) |
| `image-inline-grammar.edn` | `:image/inline-grammar` | EP-0026 §Inline Registration Grammar: the four supported kinds (`:reg-event` / `:reg-sub` / `:reg-fx` / `:reg-cofx`) lower from `[id body]` and `[id metadata body]` tuples (per-kind metadata, incl. the `:reg-cofx` grade, rides on the descriptor); an unsupported inline section, a typo'd section, a metadata-only `[id metadata]` tuple, a too-short tuple, and a too-long tuple all fail loud with `:rf.error/invalid-image` |

The three pure data-classification `:call` ops (`:project-egress`, `:redact-headers`, `:ssr-apply-policy`) join the reserved Mode-B operator set (§Mode B — pure / direct-call); they exercise the centralised egress projector and the HTTP-header / SSR-hydration boundary projectors as pure functions of their inputs.

Coverage spans the main categories: handlers, frames, frame construction (EP-0027 ordered `:initial-events` drain, `:rf/set-db` seed, `reset-frame!` replay, retired-key fail-loud), the EP-0026 image API (`:select-ns` selection, image-order layering, the shadow report, the collision / retired-key / inline-grammar fail-loud taxonomy, the default-image projection), envelope, subs, fx, errors, machines, routing, SSR, hydration, epoch, trace bus, view registration, HTTP request interceptors, and data-classification commit-plane classification + egress projection.

### The EP-0025 classification model — what the `data-classification/` fixtures pin

The `data-classification/` category exercises the **EP-0025 (final) data-classification contract**: a **path-based, fail-open, commit-plane-effect** model. The shipped model is deliberately simple — there is **no propagation / taint**, **no value-match**, **no frame `:sensitive {:app-db}` annotation**, and **no imperative marks API**. The contract is:

- **Durable app-db classification is four commit-plane effects.** A `reg-event-*` handler classifies a durable app-db path by returning one of the four classification effects alongside its `:db`: `:sensitive` and `:large` declare a path on each axis, and `:clear-sensitive` / `:clear-large` remove a path from its axis. These write a per-frame **elision registry** under `:source :effect`. The two axes are **independent** — clearing one never disturbs the other (the `clear-axis-independent.edn` fixture pins this), ordinary per-axis set/unset symmetry rather than a single coupled "classified?" bit. When both axes apply to a path, **sensitive dominates** the size axis: a both-classified path projects as the bare `:rf/redacted` sentinel, never a `:rf.size/large-elided` marker (which would leak path / size / type / handle).
- **Event args are registration-owned transient payload.** A `reg-event-*` declares a `:sensitive` / `:large` arg-path on its **registration metadata** (rooted at the dispatched `[event-id arg-map]` shape); the trace surface redacts the declared path while the handler body sees the raw payload. No app-db policy is involved.
- **Subsystems declare their own classification projection-relative.** A machine declares its durable `:data` classification on its `reg-machine` spec (a `:sensitive` / `:large` vector of `:data`-rooted `:rf/path` vectors, like XState's `context` shape); the runtime LOWERS each declaration **per actor instance at spawn**, re-rooting it to the absolute snapshot path and writing it `{:source :machine}` into the **same** per-frame elision registry the four commit-plane effects populate — and drops it on destroy. The machine's `[:schemas :data]` schema validates but no longer classifies (EP-0025 reverses the EP-0005 schema→classification bridge). Provenance is the `:source` slot: `:effect` (commit-plane), `:machine` (subsystem-lowered), and any future subsystem source. A `:clear-*` is **source-scoped** — it removes the effect-sourced declaration without disturbing a machine-sourced one at the same path, and a subsystem teardown drops only its own `:source` entries.
- **Redaction is a TRACE-EGRESS concern only — fail-open is the design point.** The committed app-db, the committed machine-snapshot runtime-db, and the durable epoch ring all carry the **raw** value; only the projected egress surface (trace `:tags`, observability records, off-box export) redacts. Storage-side redaction was removed (EP-0010 / EP-0015 §15) so the epoch ring stays replay-faithful by construction. The egress projector itself **fails closed** at its OWN boundary — a frameless `project-egress` redacts the whole tree rather than synthesise a permissive `:rf/default` frame — but the in-process durable planes are fail-open so handlers, replay, and restore always see real values.

#### The `:fixture/classification-effects` harness step

A fixture installs durable-path classification host-agnostically via the optional top-level `:fixture/classification-effects` key — an ORDERED vector of classification-effect op-maps the harness applies against the established frame scope (after `reg-frame`, before static `reg-flow` registration). Because pure-EDN fixtures cannot return effects from a handler body, `:fixture/classification-effects` is the harness shorthand that writes the elision registry **exactly as the four commit-plane effects would** (`:source :effect`):

```clojure
:fixture/classification-effects
[{:sensitive    [[:auth :token]]}  ;; classify a path on the sensitive axis (the :sensitive effect)
 {:large        [[:auth :token]]}  ;; classify the SAME path on the large axis (the :large effect)
 {:clear-large  [[:auth :token]]}] ;; declassify ONE axis (the :clear-large effect)
```

Each op-map carries exactly one of the four commit-plane effect keys — `:sensitive` / `:large` (additive classification on that axis), or `:clear-sensitive` / `:clear-large` (remove the named paths on that axis ONLY; the other axis at the same path survives — commit-plane per-axis independence). Each value is a vector of `:rf/path` vectors (a `get-in`-shaped path). The ordered-vector form lets a fixture pin the set / clear sequencing (e.g. the `clear-axis-independent.edn` axis-independence case). The declarations feed the emit-time trace-egress projection — sensitive slots render `:rf/redacted` and large slots render the `:rf.size/large-elided` marker inside the relevant trace `:tags` (e.g. the `:rf.event/db` slot on the t1 `:rf.event/db-pending` trace event) before any listener observes them. The machine-`:data` fixture needs NO `:fixture/classification-effects` op — the machine lifecycle lowers its projection-relative declaration `{:source :machine}` at boot. Fixtures using `:fixture/classification-effects` (or a machine projection-relative declaration) declare the `:data-classification/classification-effects` capability.

## Render-time observables (out of scope)

The corpus is host-agnostic pure-data event/sub/fx semantics. Render-time observables — React-context propagation through `frame-provider`, Reagent reactivity, component lifecycle, and the like — are not currently expressible as fixtures because the corpus has no render capability and no harness side that mounts a component tree to capture what happens during render. These behaviours are verified by host-side unit tests instead (e.g. `runtime_cljs_test.cljs` covers `frame-provider`'s establish-context and nested-override behaviour for the CLJS reference).

If a port (CLJS, JVM SSR, future hosts) needs to claim conformance for render-time behaviour, this README will grow a `:reagent/render` (or equivalent) capability tag and a host-side fixture runner that mounts the fixture's tree and captures the observable. Tracked as future work — see bead `` for context.

## Cross-references

- [Implementor-Checklist.md](../Implementor-Checklist.md) — decision-ordered companion to [000 §Host-profile matrix](../000-Vision.md#host-profile-matrix); Part 1 capability declarations and Part 3 (Conformance) tell the implementor which fixtures will run.
- [Spec-Schemas.md §Conformance](../Spec-Schemas.md#conformance) — the conformance test corpus question.
- [Spec-Schemas.md](../Spec-Schemas.md) — the shapes implementations validate / match against.
- [009 §Error contract](../009-Instrumentation.md) — error-event shape that error fixtures expect.
- [011-SSR.md](../011-SSR.md) — SSR fixtures' protocol.
- [012-Routing.md](../012-Routing.md) — routing fixtures' protocol.
- [Pattern-StaleDetection.md](../Pattern-StaleDetection.md) — the meta-pattern shared by `after-stale-detection.edn` and `route-stale-nav-token-suppression.edn`.
