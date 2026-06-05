# conformance

How the port consumes the conformance corpus. The corpus is the **acceptance test** for "this is a re-frame2 implementation"; without it passing, the port is "inspired by re-frame2" but not conformant.

The corpus lives at [`spec/conformance/`](https://github.com/day8/re-frame2/tree/main/spec/conformance) in the re-frame2 repo. The full contract is at [`spec/conformance/README.md`](https://day8.github.io/re-frame2/spec/conformance/). This leaf walks the operational shape — how to build the harness, what the fixtures look like, how to score, when a failure is a spec gap vs an implementation bug.

## What the corpus is

A directory of EDN files, one per fixture, each describing one canonical interaction. Two complementary fixture modes (per [`spec/conformance/README.md` §Fixture format](https://day8.github.io/re-frame2/spec/conformance/#fixture-format)):

- **Mode A — dispatch-driven.** A frame configuration plus initial `app-db`, a sequence of dispatches, and the expected emissions after drain: final `app-db`, sub values, routed effects, trace events. Most fixtures use Mode A.
- **Mode B — pure / direct-call.** No frame, no dispatch loop. Direct invocations of pure primitives via `:fixture/calls`, each record naming the primitive in `:call` and carrying its own call-local expectation. The reserved `:call` operators the corpus uses are `:machine-transition`, `:reg-machine`, `:match-url`, `:route-url`, `:round-trip`, `:assert-rank-greater`, and `:render-to-string` (the set is additive — new pure primitives may register a new op in a later fixture spec version; existing ops are never redefined). The `:reg-machine` op pins the machine **registration-error taxonomy** (`:rf.error/machine-*` via the [009 `:rf.error/id` ex-data contract](https://day8.github.io/re-frame2/spec/009-Instrumentation/#the-thrown-error-shape--the-rferrorid-ex-data-contract)): each `:reg-machine` call carries a candidate `:definition` and either an `:expect-error` (the `:rf.error/<category>` the registration-time validator must throw) or none (a well-formed control that must validate silently). Fixtures using it declare the `:fsm/registration-validation` capability; a port that validates lazily declares that capability in `known-skipped-capabilities` (see [§Capability tagging](#capability-tagging)).

Each fixture carries a `:fixture/capabilities` set — the capability tags it exercises. The harness runs every fixture whose capabilities are a subset of the port's claimed list (D7 of the decision record).

## The harness — ~300 lines per host

The contract from [`spec/conformance/README.md` §How an implementation runs the corpus](https://day8.github.io/re-frame2/spec/conformance/#how-an-implementation-runs-the-corpus):

1. **Read** all `.edn` files in `fixtures/`. If your host has no EDN reader, ship a small one (~200 lines for any host with hash-maps and vectors) or translate the corpus locally as part of harness bootstrap. Each fixture also declares a `:fixture/spec-version`; the harness should surface (and per the corpus README's §Versioning, reject) implementations that haven't moved with a spec-shape bump — don't silently run mismatched-version fixtures.
2. **For each fixture**, classify its `:fixture/capabilities`. If `⊆ claimed-capabilities`, run it. Otherwise the harness MUST distinguish two out-of-claim flavours — see [§The two out-of-claim flavours](#the-two-out-of-claim-flavours) — and report "skipped (out-of-claim)" only for capabilities on the explicit `known-skipped-capabilities` allowlist; an unknown capability (in neither set) MUST FAIL the suite with a diagnostic naming it.
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
:fsm/*            FSM-richness axis — flat / hierarchical / eventless-always / delayed-after /
                  tags / parallel-regions / final-states / history / registration-validation
:actor/*          actor-model axis — own-state / spawn-destroy / cross-actor-fx / invoke /
                  spawn-and-join / system-id
:flow/*           Flows axis (EP 013, D3 Q8) — wildcard family; ~19 sub-tags at corpus HEAD
                  (basic / trace / init / reg-v / poke / toggle / topo / dirty-check /
                   frame-scoped / hot-reload / lifecycle-emits-traces / …) — enumerate
                  from the fixtures, not this list
:rf.http/managed  managed-HTTP (EP 014, D3 Q9)
:routing/*        Q2 yes
:ssr/*            Q3 yes
:schemas/*        Q4 yes (regardless of mechanism)
```

The decision record's D7 captures the claimed tag set. The harness uses the claim as the filter; only matching fixtures run. **The fixtures are authoritative for SCORING — only a fixture-backed capability can run, so match the fixtures, not any prose list.** Both the Implementor-Checklist's family list and the conformance README's prose enumeration usually lag the fixtures (they omit `:flow/*` and its sub-tags, `:rf.http/managed`, `:fsm/final-states`, `:fsm/history`, `:fsm/registration-validation`). Enumerate the claimable vocabulary directly from `spec/conformance/fixtures/*` at the pinned commit — e.g. `grep -rho ':fsm/[a-z-]*' spec/conformance/fixtures/ | sort -u` (and the same for `:flow/`, `:actor/`). If you ship the optional EP 005 history surface, EP 013 (Flows), or EP 014 (HTTP), declare `:fsm/history` / `:flow/*` / `:rf.http/managed` in D7 (and, for `:flow/*`, satisfy or skip each sub-tag) or those fixtures trip the unknown-capability diagnostic below.

> **The "fixtures lead the prose" rule is not universal — it can invert.** Fixtures are authoritative for *what runs (scoring)*; the conformance README's capability table + the owning Spec are authoritative for *what exists to be claimed (the vocabulary)*. The two can diverge in **either** direction. The common case is corpus-ahead (fixtures carry a tag the prose forgot). But `:actor/*` is corpus-**behind**: the README ([`spec/conformance/README.md` §Capability tagging](https://day8.github.io/re-frame2/spec/conformance/)) and Spec 005 declare all six (`:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/invoke`, `:actor/spawn-and-join`, `:actor/system-id`), but the fixtures currently back only four — `grep ':actor/' spec/conformance/fixtures/` yields just `:actor/invoke`, `:actor/spawn-and-join`, `:actor/spawn-destroy`, `:actor/system-id`. `:actor/own-state` and `:actor/cross-actor-fx` are **real, spec-mandated capabilities with no fixture yet** (a corpus gap, not non-existent tags). So `grep`-the-fixtures *under-claims* the actor axis: enumerate `:actor/*` from the README + Spec 005, cross-check against the fixtures, and put a fixture-less spec capability on `known-skipped-capabilities` **only if you don't implement it** — never because the grep missed it.

A port that claims `:core/* + :fsm/flat + :actor/own-state` runs every `:core/*` fixture, every `:fsm/flat` fixture, and every `:actor/own-state` fixture — and skips the hierarchical FSM fixtures, the `:spawn` fixtures, the routing fixtures, etc.

### The two out-of-claim flavours

Per [`spec/conformance/README.md` §Capability tagging](https://day8.github.io/re-frame2/spec/conformance/), a fixture whose capabilities aren't a subset of the claim is **not** simply "skipped." The harness MUST distinguish:

- **Intentional out-of-claim** — the capability is on the harness's explicit `known-skipped-capabilities` allowlist (e.g. a flat-FSM-only port listing `:fsm/hierarchical`, or a lazy-validating port listing `:fsm/registration-validation`). Reported as "skipped (out-of-claim)" — does **not** fail the suite.
- **Typo / claim-set drift** — the capability is in neither the claimed set nor the allowlist. The suite **MUST FAIL** with a diagnostic naming the unknown capability. The remedy is either to add it to `claimed-capabilities` (with runtime backing to match) or to `known-skipped-capabilities` (an explicit decision not to claim it).

The reference harness keeps `known-skipped-capabilities` empty today (every corpus capability is claimed); the allowlist exists so future divergence is an explicit decision, never silent rot. A harness that silently skips unknown capabilities is the shape the spec now forbids — build the fail-on-unknown one.

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

## When the corpus itself is incomplete

The corpus is a living artefact — it grows as the spec grows. If your port implements a surface the corpus has no fixture for (e.g. a specific error category from EP 009, or a `:fsm/tags` interaction), that's not a port-side problem — that's a corpus gap. File it as a GitHub issue per [`cardinal-rules.md` §§8–9](cardinal-rules.md), ideally including a draft fixture in the body.

## Reporting conformance

The port's README should state:

- **Claimed capability tags** — copied from D7 of the decision record.
- **Conformance score** — the most-recent harness result, e.g. `78 / 78 :core/* + :fsm/flat + :actor/own-state`.
- **Date / commit hash of the corpus** the score was measured against. The corpus changes; pinning the score to a corpus commit lets downstream consumers verify.

This is the public contract: when the score is `N / N`, the port is a conformant re-frame2 implementation against its claim. When it's `N-k / N`, the port is k-fixtures-from-conformant. Either way, the consumer knows where they stand.
