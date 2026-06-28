# conformance

How the port consumes the conformance corpus — the **acceptance test** for "this is a re-frame2 implementation"; without it passing, the port is "inspired by re-frame2" but not conformant.

The corpus lives at [`spec/conformance/`](https://github.com/day8/re-frame2/tree/main/spec/conformance); the full contract is [`spec/conformance/README.md`](https://day8.github.io/re-frame2/spec/conformance/). This leaf walks the operational shape — building the harness, what the fixtures look like, scoring, and spec-gap vs implementation-bug diagnosis.

## What the corpus is

A directory of EDN files, one per fixture, each describing one canonical interaction. Two complementary fixture modes (per [`spec/conformance/README.md` §Fixture format](https://day8.github.io/re-frame2/spec/conformance/#fixture-format)):

- **Mode A — dispatch-driven.** A frame configuration plus initial `app-db`, a sequence of dispatches, and the expected emissions after drain: final `app-db`, sub values, routed effects, trace events. Most fixtures use Mode A. A Mode-A dispatch record MAY carry a flat `:rf.cofx` map in its dispatch opts to pin the recordable coeffects (`:rf/time-ms`, and optionally other owner-qualified facts — EP-0010 recording / EP-0017 authoring); when present the harness MUST thread it onto the envelope verbatim and the durable-write expectation reads from it, so the fixture is deterministic regardless of the wall clock. A fixture that omits it expects the runtime to stamp `:rf/time-ms` itself — assert the durable timestamp came from the token, not a fresh ambient read.
- **Mode B — pure / direct-call.** No frame, no dispatch loop. Direct invocations of pure primitives via `:fixture/calls`, each record naming the primitive in `:call` and carrying its own call-local expectation. **The reserved `:call` operator set is additive and the fixtures are authoritative — derive the live set from the corpus at your pinned commit, not from any prose list (this one included).** Enumerate it directly: `grep -rhoE ':call\s+:[a-z][a-z.]*[a-z/-]*' spec/conformance/fixtures/ | sort -u`, and cross-check against the [`spec/conformance/README.md` §Fixture format](https://day8.github.io/re-frame2/spec/conformance/#fixture-format) operator list. The op set spans several EP families — at a recent corpus state that union is the FSM/routing/SSR ops (`:machine-transition`, `:reg-machine`, `:match-url`, `:route-url`, `:round-trip`, `:assert-rank-greater`, `:render-to-string`, and the three SSR-streaming ops `:ssr.streaming/render-shell` / `:ssr.streaming/render-continuation` / `:ssr.streaming/build-final-payload`), the **EP-0012 canonical-identity + `:rf/path`-algebra ops** (`:canonical-bytes`, `:canonical-identical`, `:canonical-distinct`, `:path-instantiate`, and the path-law ops `:path-get` / `:path-lookup` / `:path-put` / `:path-over` / `:path-compose` / `:path-prefix` / `:path-overlap`), the **EP-0015 data-classification projector ops** (`:project-egress`, `:redact-headers`, `:ssr-apply-policy`), and the **EP-0014 derivation/process-graph op** (`:derivation-graph`). New pure primitives may register a new op in a later fixture spec version; existing ops are never redefined. A harness that hard-codes a stale list and rejects an unrecognised `:call` op as malformed will fail current fixtures or misdiagnose a harness gap as a spec gap — drive the op set from the fixtures, treating any list above as illustrative of the *families* you may meet, never a frozen enumeration.
  - The **EP-0012 path/canonical ops** pin the shared `:rf/path` algebra + CEDN-1 identity foundation (cardinal rule 11): `:canonical-bytes` / `:canonical-identical` / `:canonical-distinct` assert key-order-irrelevant canonical identity; `:path-instantiate` asserts template normalization (`'?name` sugar → the `[:rf.path/param …]` data form); the `:path-*` law ops pin the get/put/over/compose/prefix/overlap + root-path laws. A port implementing only CEDN bytes and template instantiation but not the path-law ops does **not** pass EP-0012 conformance. These fixtures carry the **`:identity/*` capability family** (`:identity/cedn1`, `:identity/cedn1-path-algebra-golden`) — not `:core/*`. The path+identity foundation is pattern-required (cardinal rule 11), so a conformant port claims `:identity/*` and runs these — they are not D3-gated, but they ARE a distinct claim from `:core/*`.
  - The **EP-0015 projector ops** pin the data-classification egress boundary (Spec 015, decision block D5b): `:project-egress` is the centralised record-level projector under a resolved `:rf.egress/profile` (optionally against a registered `:frame`); `:redact-headers` is the HTTP-header carrier denylist; `:ssr-apply-policy` is the SSR hydration-payload allowlist projection (taking `:expect` for the projected slice or `:expect-error` for the fail-closed `:rf.error/id`). These fixtures carry the **`:data-classification/*` capability family** (`:data-classification/marks` plus the per-scenario tags like `:data-classification/frame-sensitive-app-db-redacts`, `:data-classification/sensitive-wins-over-large`, `:data-classification/project-egress-fails-closed-no-frame`, …) — not `:core/*`. Spec 015 is **v1-required** (not D3-gated), so a conformant port claims `:data-classification/*` and runs these — enumerate the exact sub-tags from `grep -rho ':data-classification/[a-z0-9-]*' spec/conformance/fixtures/ | sort -u` at your pinned commit.
  - The **EP-0014 `:derivation-graph` op** composes the cross-family derivation/process graph (subs / flows / resources / routes / machines as one `{:mode :nodes :edges}` graph) and asserts normalized node + edge shapes. It is graded against a **split capability pair** so a host cannot overclaim: the broad `:derivation/algebra-graph` and the narrow `:derivation/algebra-graph-subs-machines` (the subs+machines static subset) — a graph host spanning only subscriptions + machines claims the subset and allowlists the broad capability as a known-skip. The graph surface is gated by D3 Q6 (whether you ship Tool-Pair inspection); a port with no inspection surface records that as a `known-skipped-capabilities` reason. See [`decision-record.md` D7 note on the derivation/process algebra](decision-record.md).
  - The **SSR-streaming ops** drive Spec 011 streaming SSR (shipped — per [`spec/011-SSR.md` §Streaming SSR](https://day8.github.io/re-frame2/spec/011-SSR/#streaming-ssr)): `:ssr.streaming/render-shell` walks the root render-tree and returns the shell HTML plus the continuation list; `:ssr.streaming/render-continuation` renders one continuation subtree; `:ssr.streaming/build-final-payload` builds the canonical `__rf_payload` chunk after all continuations drain. The fixtures that exercise them (`ssr-streaming.edn`, `ssr-streaming-nested.edn`, `ssr-hydration-mismatch.edn`) carry the capability gates `:ssr/render-to-string`, `:ssr/suspense-boundary`, `:ssr/hydration-payload`, `:ssr/chunked-response` — a Q3=yes port either implements all four and runs the streaming fixtures, or puts the streaming-specific tags on `known-skipped-capabilities` to intentionally skip them. (Note: Spec 011's API table names the first two functions with a trailing `!` — `render-shell!` / `render-continuation!` — but the conformance `:call` op names match the fixtures' bare forms above; the harness keys on the fixture op name.)
  - The `:reg-machine` op pins the machine **registration-error taxonomy** (`:rf.error/machine-*` via the [009 `:rf.error/id` ex-data contract](https://day8.github.io/re-frame2/spec/009-Instrumentation/#the-thrown-error-shape--the-rferrorid-ex-data-contract)): each `:reg-machine` call carries a candidate `:definition` and either an `:expect-error` (the `:rf.error/<category>` the registration-time validator must throw) or none (a well-formed control that must validate silently). Fixtures using it declare the `:fsm/registration-validation` capability; a port that validates lazily declares that capability in `known-skipped-capabilities` (see [§Capability tagging](#capability-tagging)).

Each fixture carries a `:fixture/capabilities` set — the capability tags it exercises. The harness runs every fixture whose capabilities are a subset of the port's claimed list (D7 of the decision record).

## The harness — ~300 lines per host

The contract from [`spec/conformance/README.md` §How an implementation runs the corpus](https://day8.github.io/re-frame2/spec/conformance/#how-an-implementation-runs-the-corpus):

1. **Read** all `.edn` files in `fixtures/`. If your host has no EDN reader, ship a small one (~200 lines for any host with hash-maps and vectors) or translate the corpus locally as part of harness bootstrap. Each fixture also declares a `:fixture/spec-version`; the harness should surface (and per the corpus README's §Versioning, reject) implementations that haven't moved with a spec-shape bump — don't silently run mismatched-version fixtures.
2. **For each fixture**, first check host-applicability, then classify capabilities. A fixture carrying `:fixture/dynamic-host-only? true` (see [§Static hosts and dynamic-host-only fixtures](#static-hosts-and-dynamic-host-only-fixtures)) asserts a runtime-validation trace that a statically-typed host cannot produce — a static host filters those out *before* the capability check. Then classify the fixture's `:fixture/capabilities`: if `⊆ claimed-capabilities`, run it. Otherwise the harness MUST distinguish two out-of-claim flavours — see [§The two out-of-claim flavours](#the-two-out-of-claim-flavours) — and report "skipped (out-of-claim)" only for capabilities on the explicit `known-skipped-capabilities` allowlist; an unknown capability (in neither set) MUST FAIL the suite with a diagnostic naming it.
3. **Bootstrap the runtime** per the fixture's `:fixture/registry` (the kinds + ids the fixture expects to be registered).
4. **Realise the handler bodies** from `:fixture/handlers` via the EDN-handler-body DSL (below).
5. **Create a frame** per `:fixture/frame-config`.
6. **Run the dispatches** in `:fixture/dispatches` (Mode A), or the calls in `:fixture/calls` (Mode B).
7. **Capture observables** — for Mode A: final `app-db`, sub values, trace emissions, effects routed; for Mode B: the call-local expectation.
8. **Compare** the observables against `:fixture/expect`. Partial-match semantics for trace events; literal match for `app-db`; ordered match for routed effects.
9. **Report** per-fixture: pass / fail / not-exercised, plus aggregate score: `passed / claimed-applicable`.

The harness is the same shape for every host; the differences are entirely host-mechanical (EDN read, registry bootstrap, frame creation, dispatch invocation).

## The EDN-handler-body DSL

Fixtures describe event-handler and sub bodies as **EDN data**, not host code. The harness reads the data and realises it into native closures.

The DSL is small — ~10 operations cover the common cases:

```clojure
;; In a fixture:
:fixture/handlers
{:event {:counter/inc        [[:update [:count] [:fn :inc]]]
         :counter/initialise [[:set [:count] 0]]}
 :sub   {:count             [[:get [:count]]]}}
```

Each handler body is a vector of operations. Each operation is `[<op> <args...>]`. The harness interprets the operations against the dispatch envelope and returns the result.

Common ops (per the corpus's existing fixtures):

- `[:set path value]` — set `app-db` at `path` to `value`.
- `[:update path [:fn op]]` — apply `op` (`:inc`, `:dec`, `:not`, …) at `path`.
- `[:get path]` — read `app-db` at `path` (used in sub bodies).
- `[:assoc-in path value]`, `[:dissoc-in path]` — path-shaped mutators.
- `[:dispatch event-vector]` — schedule a dispatch as an effect.
- `[:fx [...]]` — return a literal `:fx` vector.

The interpreter is ~50 lines per host. The CLJS reference's interpreter lives in `implementation/core/src/re_frame/conformance.cljc` (namespace `re-frame.conformance` — `realise-event-handler` / `realise-sub` / `realise-fx-handler`); copy the dispatch-style and adapt the literal-op handlers. It's a `.cljc`, so it is both JVM- and CLJS-runnable.

**Spec-gap signal.** When a fixture uses an op that isn't documented anywhere — that's a spec gap (ask for the DSL to be documented in `spec/conformance/README.md`). File it as a GitHub issue per [`cardinal-rules.md` §§8–9](cardinal-rules.md).

## Capability tagging

From [`spec/conformance/README.md` §Capability tagging](https://day8.github.io/re-frame2/spec/conformance/):

```
:core/*           pattern-required basics — always run
:identity/*       v1-required — the EP-0012 :rf/path algebra + CEDN-1 canonical
                  identity foundation (:identity/cedn1, :identity/cedn1-path-algebra-golden)
:data-classification/*  v1-required — the Spec 015 egress/redaction contract
                  (:data-classification/marks + per-scenario sub-tags); enumerate
                  from the fixtures
:fsm/*            FSM-richness axis — flat / hierarchical / eventless-always / delayed-after /
                  tags / parallel-regions / final-states / history / registration-validation
:actor/*          actor-model axis — own-state / spawn-destroy / cross-actor-fx / declarative-spawn /
                  spawn-and-join / system-id
:flow/*           Flows axis (EP 013, D3 Q8) — wildcard family; ~19 sub-tags at corpus HEAD
                  (basic / trace / init / reg-v / poke / toggle / topo / dirty-check /
                   frame-scoped / hot-reload / lifecycle-emits-traces / …) — enumerate
                  from the fixtures, not this list
:rf.http/managed  managed-HTTP (EP 014, D3 Q9)
:routing/*        Q2 yes
:ssr/*            Q3 yes
:schemas/*        Q4 yes (regardless of mechanism)
:derivation/*     derivation/process graph-inspection (EP-0014, D3 Q6 / Tool-Pair;
                  normative home: spec/Derivations.md) —
                  the SPLIT pair :derivation/algebra-graph (broad) +
                  :derivation/algebra-graph-subs-machines (subs+machines subset);
                  a graph host spanning only subs+machines claims the subset and
                  known-skips the broad capability
:rf.resource/* :rf.mutation/*  Resources (EP 016, D3 Q10) — server-state read
                  models + named causal writes. SPEC-DEFINED but corpus-behind:
                  the spec mandates the family, but the fixtures ship NO resource
                  tags yet (`grep ':rf.resource/' spec/conformance/fixtures/`
                  returns nothing). Self-test from the spec + your own unit tests
                  until fixtures land — same corpus-behind shape as :actor/own-state
```

`:identity/*` and `:data-classification/*` are **v1-required** (like `:core/*`) — not D3-gated — because the EP-0012 path+identity foundation (cardinal rule 11) and Spec 015 data classification (D5b) are both v1 obligations. A conformant port claims all three and runs their fixtures.

The decision record's D7 captures the claimed tag set. The harness uses the claim as the filter; only matching fixtures run. **The fixtures are authoritative for SCORING — only a fixture-backed capability can run, so match the fixtures, not any prose list.** Both the Implementor-Checklist's family list and the conformance README's prose enumeration usually lag the fixtures (they omit `:flow/*` and its sub-tags, `:rf.http/managed`, `:fsm/final-states`, `:fsm/history`, `:fsm/registration-validation`). Enumerate the claimable vocabulary directly from `spec/conformance/fixtures/*` at the pinned commit — e.g. `grep -rho ':fsm/[a-z-]*' spec/conformance/fixtures/ | sort -u` (and the same for `:flow/`, `:actor/`). If you ship the optional EP 005 history surface, EP 013 (Flows), or EP 014 (HTTP), declare `:fsm/history` / `:flow/*` / `:rf.http/managed` in D7 (and, for `:flow/*`, satisfy or skip each sub-tag) or those fixtures trip the unknown-capability diagnostic below.

> **The "fixtures lead the prose" rule is not universal — it can invert.** Fixtures are authoritative for *what runs (scoring)*; the conformance README's capability table + the owning Spec are authoritative for *what exists to be claimed (the vocabulary)*. The two can diverge in **either** direction. The common case is corpus-ahead (fixtures carry a tag the prose forgot). But `:actor/*` is corpus-**behind**: the README ([`spec/conformance/README.md` §Capability tagging](https://day8.github.io/re-frame2/spec/conformance/)) and Spec 005 declare all six (`:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/declarative-spawn`, `:actor/spawn-and-join`, `:actor/system-id`), but the fixtures currently back only four — `grep ':actor/' spec/conformance/fixtures/` yields just `:actor/declarative-spawn`, `:actor/spawn-and-join`, `:actor/spawn-destroy`, `:actor/system-id`. `:actor/own-state` and `:actor/cross-actor-fx` are **real, spec-mandated capabilities with no fixture yet** (a corpus gap, not non-existent tags). So `grep`-the-fixtures *under-claims* the actor axis: enumerate `:actor/*` from the README + Spec 005, cross-check against the fixtures, and put a fixture-less spec capability on `known-skipped-capabilities` **only if you don't implement it** — never because the grep missed it.

A port that claims `:core/* + :fsm/flat + :actor/own-state` runs every `:core/*` fixture, every `:fsm/flat` fixture, and every `:actor/own-state` fixture — and skips the hierarchical FSM fixtures, the `:spawn` fixtures, the routing fixtures, etc.

### The two out-of-claim flavours

Per [`spec/conformance/README.md` §Capability tagging](https://day8.github.io/re-frame2/spec/conformance/), a fixture whose capabilities aren't a subset of the claim is **not** simply "skipped." The harness MUST distinguish:

- **Intentional out-of-claim** — the capability is on the harness's explicit `known-skipped-capabilities` allowlist (e.g. a flat-FSM-only port listing `:fsm/hierarchical`, or a lazy-validating port listing `:fsm/registration-validation`). Reported as "skipped (out-of-claim)" — does **not** fail the suite.
- **Typo / claim-set drift** — the capability is in neither the claimed set nor the allowlist. The suite **MUST FAIL** with a diagnostic naming the unknown capability. The remedy is either to add it to `claimed-capabilities` (with runtime backing to match) or to `known-skipped-capabilities` (an explicit decision not to claim it).

The reference harness keeps `known-skipped-capabilities` empty today (every corpus capability is claimed); the allowlist exists so future divergence is an explicit decision, never silent rot. A harness that silently skips unknown capabilities is the shape the spec now forbids — build the fail-on-unknown one.

### Static hosts and dynamic-host-only fixtures

Some fixtures carry `:fixture/dynamic-host-only? true` — at corpus HEAD the schema-validation fixtures do (`schema-event-payload-validates.edn:23`, `schema-cofx-validates.edn`, `schema-sub-return-validates.edn`, `schema-app-db-slice-validates.edn:23`, `error-schema-failure.edn:14`). The flag means the fixture asserts a **runtime** validation trace (e.g. a malformed payload emits `:rf.error/schema-validation-failure` and the handler is skipped). A statically-typed host that claims `:schemas/*` **via host types** (D5 = yes-via-host-types — the type checker rejects the malformed value at the call site before any frame exists) cannot produce that runtime trace at all: the bad value never compiles, so there's nothing to dispatch and nothing to observe.

This is the one place the D7 rule "claim `:schemas/*` if D5 ≠ no, regardless of mechanism" needs a mechanism-aware refinement, because the *vocabulary* claim (shape description exists) and the *fixture* claim (runtime trace observable) diverge for a static host:

- **Dynamic-host port** (runtime validator — Malli-style, or a hand-rolled predicate pass): runs every `:schemas/*` fixture normally. `:fixture/dynamic-host-only?` is a no-op for you.
- **Static-host port** (`yes-via-host-types`): you still claim the shape-description capability you actually provide, but you do **not** claim the runtime-trace sub-capabilities the dynamic-host-only fixtures assert (`:schemas/runtime`, `:schemas/event-payload`, …). Put those runtime tags on `known-skipped-capabilities` with an explicit static-host reason — e.g. `:reason "static host — malformed values rejected at compile time; no runtime schema trace to assert"`. That records a **documented static-host conformance path**, not claim-set drift: the harness reports the dynamic-host-only schema fixtures as "skipped (out-of-claim)" rather than failing them, and a reader sees *why*.

The harness honours the flag at filter time: when the host is static, drop `:fixture/dynamic-host-only?` fixtures before the capability-subset check (they can never pass, and forcing a runtime validator solely to satisfy them is misleading work the corpus explicitly marks inapplicable). When the host is dynamic, the flag changes nothing.

A minimal static-host conformance manifest fragment:

```clojure
;; D5 = yes-via-host-types — shape is described by the host type system.
{:host/dynamic?              false
 :claimed-capabilities       #{:core/* :schemas/shape-description}   ; what the type system provides
 :known-skipped-capabilities {:schemas/runtime       {:reason "static host — malformed values rejected at compile time; no runtime schema trace"}
                              :schemas/event-payload {:reason "static host — malformed event payloads are a compile error; no runtime :rf.error/schema-validation-failure to assert"}}}
```

Report the static-host path explicitly in the README's conformance section (per [§Reporting conformance](#reporting-conformance)) so a downstream consumer reads the skip as a host-mechanism fact, not a missing surface.

## Diagnosis — spec gap vs implementation bug

When a fixture fails, the question is: who's at fault?

**Implementation bug.** The fixture exercises a well-specified surface that the port has implemented incorrectly. Symptoms:

- The spec for the relevant EP is unambiguous about the expected behaviour.
- Other ports could pass this fixture from the spec alone.
- The failure is a copy-paste error, a typo, a mistaken mechanism choice.

**Action:** fix the port. The conformance corpus is doing its job — surfacing a bug.

**Spec gap.** The fixture exercises a surface the spec is silent on or ambiguous about. Symptoms:

- The expected behaviour isn't justified by anything in `spec/`.
- The fixture's expectation seems to reflect a choice the CLJS reference made that isn't normative.
- An AI armed only with `spec/` + the corpus + this skill couldn't reproduce the expectation without consulting `implementation/`.

**Action:** don't patch the port to match — file the gap as a GitHub issue per [`cardinal-rules.md` §§8–9](cardinal-rules.md). The spec needs to grow to cover the case; once it does, the port (and every other port) can target the explicit contract.

The framing from [`spec/conformance/README.md`](https://day8.github.io/re-frame2/spec/conformance/) is normative here: *"A fixture an AI cannot reproduce without consulting outside sources is a **spec gap**, not an implementation gap."*

## Running the harness

The mechanics depend on the host. Typical shape:

```
$ <port-toolchain> conformance run --claimed=":core/* :fsm/flat :actor/own-state"

Loading corpus from ../re-frame2/spec/conformance/fixtures ... 136 fixtures.
Filtering by claimed capabilities ... 78 applicable, 58 not exercised.

PASS  counter-inc-once                 :core/event-handler :core/sub :core/trace
PASS  closed-effect-map                :core/event-handler
FAIL  sub-cache-invalidation           :core/sub :core/substrate
      Expected: {[:total] 7}
      Actual:   {[:total] 5}
PASS  ...
SKIP  fsm-hierarchical-exit-cascade    :fsm/hierarchical   (not claimed)
...

Score: 77 / 78 applicable. 1 failure.
```

Wire the harness into the port's CI; every commit should report the score. Conformance regressions caught at commit time are far cheaper than at release time.

**The harness is tooling — the file-path security obligation applies.** If your harness translates the corpus to a host-native form, regenerates fixtures, or writes results into a scratch directory configured by an env-var override, it MUST constrain that path to a closed allowed-prefix list and fail fast on an escape (no silent `$HOME`/`/` write). This is a spec-pinned MUST for any writing tool, the harness included — see [`phase-2-impl-order.md` §Tooling-security obligations](phase-2-impl-order.md#tooling-security-obligations-any-port-that-ships-tooling).

## When the corpus itself is incomplete

The corpus is a living artefact — it grows as the spec grows. If your port implements a surface the corpus has no fixture for (e.g. a specific error category from EP 009, or a `:fsm/tags` interaction), that's not a port-side problem — that's a corpus gap. File it as a GitHub issue per [`cardinal-rules.md` §§8–9](cardinal-rules.md), ideally including a draft fixture in the body.

The **recordable-coeffect contract** (EP-0010 recording / EP-0017 authoring) is `:core/*` (envelope stamp + declared-only delivery + durable-write determinism — see [`phase-2-impl-order.md` EP 002 §The world-input contract](phase-2-impl-order.md#the-world-input-contract-ep-0010)). Where a dedicated fixture for a sub-behaviour lags (e.g. child-token fresh-stamping, or the declared-only delivery filter), self-test it from the spec anyway: dispatch with a pinned `:rf.cofx`, declare + write a durable timestamp/id, restore the epoch (or replay the recorded token), and assert the durable value is reproduced from the token rather than re-read from a moved clock. A divergence on restore is the observable failure; treat a missing fixture for it as a corpus gap to file, not a reason to skip the behaviour.

## Reporting conformance

The port's README should state:

- **Claimed capability tags** — copied from D7 of the decision record.
- **Conformance score** — the most-recent harness result, e.g. `78 / 78 :core/* + :fsm/flat + :actor/own-state`.
- **Date / commit hash of the corpus** the score was measured against. The corpus changes; pinning the score to a corpus commit lets downstream consumers verify.

This is the public contract: when the score is `N / N`, the port is a conformant re-frame2 implementation against its claim. When it's `N-k / N`, the port is k-fixtures-from-conformant. Either way, the consumer knows where they stand.
