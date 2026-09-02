# conformance

How the port consumes the conformance corpus — the **acceptance test** for "this is a re-frame2 implementation". The corpus lives at [`spec/conformance/`](https://github.com/day8/re-frame2/tree/main/spec/conformance); the **full contract is [`spec/conformance/README.md`](https://day8.github.io/re-frame2/spec/conformance/)** — fixture format, the handler-body DSL op table, capability tagging, versioning, the harness steps. This leaf is the operational walk: what to build, how to derive the claimable set, and how to diagnose a failure. Read the README, like every owner, from the verified checkout at your pin (cardinal rule 1) — the links here are citations of live main. Where this leaf and the corpus README disagree, the README (at your pin) wins.

## What the corpus is

EDN fixtures, one canonical interaction each, in two modes (README §Fixture format):

- **Mode A — dispatch-driven.** A frame configuration plus initial `app-db`, a sequence of dispatches, and the expected post-drain observables: final `app-db`, sub values, routed effects, trace events. A dispatch record MAY pin recordable coeffects via a flat `:rf.cofx` map — thread it onto the envelope verbatim, so the fixture is deterministic regardless of the wall clock.
- **Mode B — pure / direct-call.** No frame, no dispatch loop; `:fixture/calls` invoke pure primitives, each record carrying its own expectation.

**Derive, never transcribe.** The Mode-B `:call` operator set, the handler-DSL op set, the capability vocabulary, the dynamic-host-only fixture set, and the fixture count are all facts of the corpus **at your pin** — enumerate them there rather than trusting any prose list (this leaf included):

```bash
ls spec/conformance/fixtures/*.edn | wc -l                                        # fixture count
grep -rhoE '^\{? ?:fixture/[a-z0-9?-]+' spec/conformance/fixtures/ | tr -d '{ ' | sort -u  # top-level fixture keys
grep -rhoE ':call\s+:[a-z][a-z.]*[a-z/-]*' spec/conformance/fixtures/ | sort -u   # Mode-B call ops
grep -rho ':fsm/[a-z-]*' spec/conformance/fixtures/ | sort -u                     # one family's live tags
grep -rl ':fixture/dynamic-host-only?' spec/conformance/fixtures/                 # static-host-inapplicable fixtures
grep -rhoE ':fixture/spec-version\s+"[^"]*"' spec/conformance/fixtures/ | sort -u # spec versions in play
```

The **line anchor** in the key grep is load-bearing, not tidiness. Fixtures also register, dispatch, and handle **event ids in the `:fixture/` namespace** (a fixture that needs a marker event registers one under `:fixture/registry`), and those are values inside the map, not keys of it — an unanchored `:fixture/*` search returns them mixed in with the real keys, and also picks up mentions inside `:fixture/doc` strings and `;;` commentary. Both are false positives for the key floor below: build it against an id and a conforming fixture fails. Anchoring on the top-level map's own keys is the discriminator; once the harness has parsed the fixture, take the key set from the parsed map and skip the text search entirely.

New pure primitives may register a new `:call` op in a later fixture spec version; existing ops are never redefined. A harness that hard-codes a stale list will fail current fixtures or misdiagnose a harness gap as a spec gap.

## The harness (~300 lines per host)

[README §How an implementation runs the corpus](https://day8.github.io/re-frame2/spec/conformance/#how-an-implementation-runs-the-corpus) is the contract. The shape: read every fixture; filter (host-applicability first, then capability subset); bootstrap the registry; realise handler bodies via the DSL; create the frame; run the dispatches or calls; capture observables and compare; report per-fixture plus the aggregate `passed / claimed-applicable`.

Five fail-loud floors the harness owes:

- **Spec version.** Reject a `:fixture/spec-version` the harness hasn't moved with (README §Versioning) — never silently run a mismatched fixture.
- **Unknown capability.** A fixture capability in neither the claim nor the `known-skipped` allowlist FAILS the suite with a diagnostic naming it (§The two out-of-claim flavours below).
- **Unknown op.** An unrecognised handler-DSL op or Mode-B `:call` op fails with a diagnostic naming it; then either grow the interpreter (the designed path) or file the corpus gap (rules 8–9).
- **Unknown top-level fixture key.** A `:fixture/*` key of the fixture map the harness does not implement fails the run naming it. This is the quietest of the five and the reason it is here: the corpus grows setup and expectation keys ahead of the README's §Fixture format, so a harness that reads only the keys it recognises drops a fixture's setup or its expectation on the floor and reports a **pass** — an expectation that was never checked. Derive the live key set at your pin (the grep above); read §Fixture format as a description, never as a closed list.
- **Non-vacuous run.** Assert a non-zero runnable-fixture floor — an empty or all-skipped corpus goes RED, never green having exercised nothing.

The middle three are one rule at three depths — capability, operator, key — and all three exist because the README states the principle for capabilities: *"a harness that silently skips unknown capabilities is the shape the spec forbids"* (§Capability tagging). Nothing is special about capabilities there; a silently-skipped key is the same failure wearing a green tick.

Handler bodies are data: a small DSL (`[:set path value]`, `[:update path [:fn op]]`, `[:get path]`, `[:dispatch event]`, …) realised into host closures — ~50 lines, the complete op table in the README. The CLJS reference's interpreter (`implementation/core/src/re_frame/conformance.cljc`) is one worked example, not a contract.

Wire the harness into the port's CI; every commit should report the score. **The harness is tooling**, so the tooling-security file-path obligation applies to any scratch-dir/env-var write path it accepts ([`phase-2-impl-order.md` §Cross-cutting obligations](phase-2-impl-order.md#cross-cutting-obligations-spec-owned-read-dont-restate)).

## Capability tagging

This section is the skill's single owner of three things: how to **derive** the claimable set, the **family → scope-question map**, and the **corpus/spec divergence rule**. The port profile records only the result.

Three families are **v1-required** and always claimed: `:core/*` (pattern-required basics), `:identity/*` (the `:rf/path` algebra + CEDN-1 canonical identity — `EP-0012` is the `docs/EP/` proposal behind it, `spec/Conventions.md` the normative text), and `:data-classification/*` (the Spec 015 egress/redaction contract). Every other family maps to a profile scope question and is claimed iff that answer is yes:

| Family | Claimed when |
|---|---|
| `:fsm/*`, `:actor/*` | Q1 state machines |
| `:routing/*` | Q2 routing |
| `:ssr/*` | Q3 SSR |
| `:schemas/*` | Q4 schemas ≠ no (see §Static hosts below) |
| `:flow/*` | Q8 flows |
| `:rf.http/managed` | Q9 managed HTTP |
| `:resources/*` | Q10 resources |
| `:derivation/algebra-graph` + `:derivation/algebra-graph-subs-machines` | Q6 Tool-Pair inspection — a subs+machines-only graph host claims the narrow tag and known-skips the broad one |

**Fixtures are authoritative for what RUNS; the README + owning Spec for what EXISTS to be claimed — and the two diverge in both directions.** The common case is corpus-ahead: the fixtures carry tags the prose lists lag, so enumerate each claimed family from `spec/conformance/fixtures/` at the pin (the greps above). But `:actor/*` is corpus-**behind**: the README and Spec 005 declare capabilities the fixtures don't yet back — a real, spec-mandated capability with no fixture goes on `known-skipped` only if you don't implement it, never because a grep missed it. Cross-check each family against the README's table before finalising the claim.

### The two out-of-claim flavours

A fixture whose capabilities aren't a subset of the claim is **not** simply "skipped":

- **Intentional out-of-claim** — the capability is on the explicit `known-skipped` allowlist, with a reason. Reported "skipped (out-of-claim)"; does not fail the suite.
- **Typo / claim-set drift** — the capability is in neither set. The suite **MUST FAIL** naming it. The remedy is to add it to the claim (with runtime backing) or to `known-skipped` (an explicit decision) — a harness that silently skips unknown capabilities is the shape the spec forbids.

### Static hosts and dynamic-host-only fixtures

Some fixtures carry `:fixture/dynamic-host-only? true` (derive the live set with the grep above): they assert a **runtime** validation trace a statically-typed host claiming schemas via its type system cannot produce — the malformed value never compiles. A static-host port filters those fixtures out *before* the capability-subset check, claims the shape-description capability it actually provides, and puts the runtime-trace tags on `known-skipped` with an explicit static-host reason. That is a documented static-host conformance path, not claim-set drift; report it in the port README so the skip reads as a host-mechanism fact.

## Diagnosis — spec gap vs implementation bug

When a fixture fails:

- **Implementation bug.** The spec for the surface is unambiguous; other ports could pass from the spec alone. Fix the port — the corpus is doing its job.
- **Spec gap.** The expectation isn't justified by anything in `spec/`; it seems to reflect a choice the CLJS reference made that isn't normative; an AI armed only with `spec/` + the corpus + this skill couldn't reproduce it without consulting `implementation/`. **Don't patch the port to match** — file it per [`cardinal-rules.md` §§8–9](cardinal-rules.md).

The framing from the corpus README is normative here: *"A fixture an AI cannot reproduce without consulting outside sources is a **spec gap**, not an implementation gap."*

## When the corpus itself is incomplete

The corpus grows with the spec. A spec-mandated surface with no fixture yet is a **corpus gap**: self-test the behaviour from the owning Spec with the port's own unit tests, record the capability as *fixture-less self-tested* in the profile, and file the gap upstream (ideally with a draft fixture in the body) per rules 8–9. A missing fixture is never a reason to skip the behaviour.

A fixture that **exists but observes only a prerequisite** is the same gap wearing a green tick, and one such case is named by the Spec itself: the two `:identity/cedn1` cache-key fixtures assert canonical-identity — the pure mechanism a value-keyed sub-cache rests on — not live cache wiring, and the corpus subscribes each query once, so it cannot see a reference-keyed live cache ([`spec/006-ReactiveSubstrate.md` §Value-keyed cache-key contract](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/#value-keyed-cache-key-contract), conformance-observability note). The port-owned proof for that surface is the required [EP-006 live sub-cache witness](phase-2-impl-order.md#the-ep-006-live-sub-cache-witness-port-owned) — already specified, no upstream filing needed.

## Reporting conformance

The port's README states four things, copied from the profile:

- **Claimed capability tags.**
- **Conformance score** — `passed / claimed-applicable` from the most recent harness run.
- **The corpus commit** the score was measured against — the corpus changes, so an unpinned score is unverifiable.
- **The EP-006 live sub-cache witness result** — its own pass/fail line beside the score (see above), never inside the fraction.

When the score is `N / N`, the port is fixture-conformant against its claim; when it's `N-k / N`, it is k fixtures from conformant. Either way, the consumer knows where they stand. The score stays a **fixture** result: on a host whose cache mechanism does not intrinsically key by `rf=`, `N / N` alone does not make the port v1-complete while the live witness is red or unrun.
