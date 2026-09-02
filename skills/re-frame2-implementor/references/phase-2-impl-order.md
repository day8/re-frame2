# phase-2-impl-order — the EP loop

Phase 2 is one repeatable loop over the EP index below, driven by the **pinned owning Specs** and the **live conformance fixtures** — read the contract, not prose about the contract. Every owner named on this page is read from the profile's verified checkout at the recorded pin — `<path-to-re-frame2>/spec/<file>`, cardinal rule 1; the `day8.github.io` links are citations of live main for browsing, never the reading route. Two standing anchors: [`spec/Ownership.md`](https://day8.github.io/re-frame2/spec/Ownership/) (which spec owns a surface, when unsure) and [`spec/API.md`](https://day8.github.io/re-frame2/spec/API/) (the public signature for anything the port exposes).

## Step 0 — bootstrap the feedback seam

Before, or alongside, the first foundation slice, build the smallest harness seam that can run one fixture end to end. The corpus contract is [`spec/conformance/README.md`](https://day8.github.io/re-frame2/spec/conformance/); the operational walk is [`conformance.md`](conformance.md).

- **Read the corpus at the pin.** An EDN reader is ~200 lines in any host with maps and vectors, or translate the corpus locally as part of bootstrap.
- **Enumerate, don't transcribe.** Fixture ids, capability tags, handler-DSL ops, Mode-B `:call` ops, and the top-level `:fixture/*` key set are all facts of the corpus at the pin — the greps are in [`conformance.md`](conformance.md).
- **Fail loud on the unknown.** An unrecognised `:fixture/spec-version`, a capability in neither the claim nor the `known-skipped` allowlist, an unknown handler-DSL op, an unknown Mode-B `:call` op, a top-level `:fixture/*` key the harness does not implement — each fails the run with a diagnostic naming it, never a silent skip.
- **Grow op-by-op.** Implement handler-DSL and Mode-B operators as live fixtures require them. Do not invent a generated fixture-translation layer or a cross-host compatibility package.

A loop-step-4 slice may report "not run" only while this seam does not yet exist — and the seam lands with the first foundation EP, so successive EPs landed on "no port script yet" is exactly the failure mode this step prevents.

## The loop (every EP)

1. **Read the owner at the pin** — the EP row's links below.
2. **Enumerate what applies** — the EP's fixtures, tags, and operators, from the same pin.
3. **Implement the smallest vertical slice.**
4. **Run the narrowest gate that covers it** — the port's unit-test command for the module, or the fixture subset for the EP's tags. Foreground; capture the exact command and result.
5. **Repair, or diagnose** — implementation bug vs spec gap per [`conformance.md` §Diagnosis](conformance.md#diagnosis--spec-gap-vs-implementation-bug); a spec gap goes through cardinal rules 8–9 (search, draft, ask, file upstream).
6. **Update the port profile** only if a real choice or claim changed.

**Checkpoint** — at whatever granularity fits the work; there is no per-EP-session, per-EP-commit, or report-template mandate. A checkpoint carries: changed profile lines, what was built and what it showed, the exact test command + outcome, the conformance delta, and genuine blockers / spec gaps.

## The EP index — foundation (required, in dependency order)

Read the shared foundation first: one `:rf/path` algebra and one canonical identity (CEDN-1), stated in [`spec/Conventions.md`](https://day8.github.io/re-frame2/spec/Conventions/) and inherited by every subsystem (cardinal rule 11); its fixtures carry the `:identity/*` family. The corpus cites the design record behind it as **`EP-0012`** — a `docs/EP/` Enhancement Proposal, not a numbered Spec (see the Term box in [`SKILL.md`](../SKILL.md)); Conventions is the text you implement against.

| Step | Owner (read at the pin) | Fixture families |
|---|---|---|
| **001 Registration** | [`spec/001-Registration.md`](https://day8.github.io/re-frame2/spec/001-Registration/) + [`spec/Conventions.md`](https://day8.github.io/re-frame2/spec/Conventions/) (the reserved `:rf/*` scheme; the three unqualified fx-ids) | `:core/*` (incl. `:core/image` via the `:assemble-image` op) |
| **002 Frames + events + effects + subs** | [`spec/002-Frames.md`](https://day8.github.io/re-frame2/spec/002-Frames/) (the largest chapter — plan two reads) + [`spec/Spec-Schemas.md` §`:rf/effect-map`](https://day8.github.io/re-frame2/spec/Spec-Schemas/#rfeffect-map) | `:core/*` |
| **006 Reactive substrate** | [`spec/006-ReactiveSubstrate.md`](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/) (the closed ten-entry adapter contract) | `:core/sub`, plus the `:identity/cedn1` cache-key fixtures — canonical-identity only; live cache wiring is proved by the [live sub-cache witness](#the-ep-006-live-sub-cache-witness-port-owned) below |
| **Views** (no numbered Spec) | [`spec/Implementor-Checklist.md` §V1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#v1-render-tree-shape) + [`spec/002-Frames.md` §frame-root](https://day8.github.io/re-frame2/spec/002-Frames/#frame-root--the-ensure-component-cljs-reference) / [§frame-provider](https://day8.github.io/re-frame2/spec/002-Frames/#frame-provider--the-scope-only-component-cljs-reference) | `:core/*` (`view-registration.edn`) |
| **009 Instrumentation** | [`spec/009-Instrumentation.md`](https://day8.github.io/re-frame2/spec/009-Instrumentation/) — two surfaces with opposite production postures: the dev-elided trace surface, and the always-on `:events` / `:errors` substrates that must survive the release build | `:core/trace`, `:core/error` |
| **015 Data Classification** | [`spec/015-Data-Classification.md`](https://day8.github.io/re-frame2/spec/015-Data-Classification/) + the `project-egress` / `register-observability-sink!` rows in [`spec/API.md`](https://day8.github.io/re-frame2/spec/API/) — v1-required; it overlays the 009 emission boundary, so it lands directly after 009 | `:data-classification/*` |

**Acceptance gate 1 — the required-foundation gate.** Run every fixture applicable to the three v1-required families — `:core/*` + `:identity/*` + `:data-classification/*` (tags expanded at the pin per [`conformance.md` §Capability tagging](conformance.md#capability-tagging)). Never `:core/*` alone: that silently skips the separately-tagged path/identity and classification fixtures the foundation just called mandatory. And a green gate 1 is a **fixture** result: it does not prove live sub-cache wiring, so declaring the foundation complete also requires the [live sub-cache witness](#the-ep-006-live-sub-cache-witness-port-owned) below.

### The EP-006 live sub-cache witness (port-owned)

The two `:identity/cedn1` cache-key fixtures (`sub-cache-dedupes-equal-query-v.edn`, `sub-cache-key-map-arg-order.edn`) call the canonical-identity primitive directly: they prove the cache-**key** prerequisite, never the live cache. The owning Spec states the gap ([`spec/006-ReactiveSubstrate.md` §Value-keyed cache-key contract](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/#value-keyed-cache-key-contract), conformance-observability note): the corpus subscribes each query once, so a port whose live sub-cache is keyed by host reference identity — a native reference-keyed map over freshly-allocated query vectors — passes every required fixture while equal queries create separate derived containers forever, ref-counts never converge, and disposal never fires.

So EP-006 carries one **port-owned live test** beside its fixtures, required whenever the selected cache mechanism does not intrinsically key by `rf=` (on the CLJS reference a persistent map keyed by the persistent query vector is intrinsically value-keyed; on a host whose ordinary map keys collections by reference, this witness is the proof the fixtures cannot give). The witness runs through the **public/runtime subscription path** — never the canonical-identity primitive — and observes:

1. **One slot from two allocations.** Build `q1` and `q2` as two distinct host allocations of the same query value, prove `rf=(q1, q2)` first, subscribe both in one frame: exactly **one** cache-slot creation, one derived container / first-run computation, one shared ref-count.
2. **One lifecycle.** Release one reader — the slot stays live; release the last — the slot is removed and disposal fires **exactly once**.
3. **Negative control.** A third query that is *not* `rf=` to `q1` (a different argument value) creates a **second** cache-slot and a second first-run computation, so a port that interns or collapses every query cannot satisfy the witness degenerately.

The witness is **mechanism-agnostic**: an `rf=`-keyed persistent map and an interned canonical key both pass (the Spec blesses either mechanism); what the skill requires is the observable one-slot/one-lifecycle result, never a library. Its result reports at the EP-006 checkpoint and again at `SKILL.md` §Done, **beside** the conformance score, never inside it — the score stays `passed / claimed-applicable` over fixtures, and the witness is its own pass/fail line. A reference-keyed host is not EP-006-complete, foundation-complete, or v1-complete while the witness is red or unrun.

Foundation traps the spec names — one line each, owners linked:

- The `[]` root path focuses the whole value: `put(s, [], x) = x`. Delegating `put` to a raw host `assoc-in` is non-conforming ([`spec/Conventions.md`](https://day8.github.io/re-frame2/spec/Conventions/)).
- Durable writes fold recorded `:rf.cofx` facts; re-reading the host clock (or a UUID/random source) inside the fold breaks replay ([`spec/002-Frames.md`](https://day8.github.io/re-frame2/spec/002-Frames/)).
- The effect-map envelope is policed before routing — malformed shapes produce structured errors, never a silent repair ([`spec/Implementor-Checklist.md` §Required](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#required-not-gated-every-implementation-ships-these)).
- Production elision scopes to the dev trace surface only; the always-on substrates ride a separate ungated path ([`spec/009-Instrumentation.md` §Production builds](https://day8.github.io/re-frame2/spec/009-Instrumentation/#production-builds-zero-overhead-zero-code)).
- Frame boundaries have **two ENSURE lifecycle realizations** — the root door (ENSURE at host preflight, never render; `:committed` set only at the host-commit boundary; `:mount-incomplete` on an aborted attempt — [`spec/004C-Roots-and-Mount.md` §7.1](https://day8.github.io/re-frame2/spec/004C-Roots-and-Mount/)) and the `[REACT-ADAPTERS]` commit-owned two-pass `useLayoutEffect` one (`:rf.error/frame-root-reconfigured` on a mounted reconfig). Pick whichever the host's render model makes honest, and hold its timing.

## The EP index — optional (per the profile's claim)

Each row is isolated; the order minimises rework. Enumerate each claimed family's live tags at the pin.

| Step | Gate | Owner | Family |
|---|---|---|---|
| **010 Schemas** | schema answer ≠ no | [`spec/010-Schemas.md`](https://day8.github.io/re-frame2/spec/010-Schemas/) — §Production builds names the two checks that never elide | `:schemas/*` |
| **008 Testing** | recommended always | [`spec/008-Testing.md`](https://day8.github.io/re-frame2/spec/008-Testing/) | — |
| **005 State machines** | Q1 | [`spec/005-StateMachines.md`](https://day8.github.io/re-frame2/spec/005-StateMachines/) — the spec's largest EP; plan a full read first | `:fsm/*`, `:actor/*` |
| **012 Routing** | Q2 | [`spec/012-Routing.md`](https://day8.github.io/re-frame2/spec/012-Routing/) | `:routing/*` |
| **011 SSR** | Q3 | [`spec/011-SSR.md`](https://day8.github.io/re-frame2/spec/011-SSR/) (incl. §Streaming SSR) | `:ssr/*` |
| **013 Flows** | Q8 | [`spec/013-Flows.md`](https://day8.github.io/re-frame2/spec/013-Flows/) + the partition-qualified inputs rule in [`spec/002-Frames.md`](https://day8.github.io/re-frame2/spec/002-Frames/) | `:flow/*` |
| **014 Managed HTTP** | Q9 | [`spec/014-HTTPRequests.md`](https://day8.github.io/re-frame2/spec/014-HTTPRequests/) + [`spec/Managed-Effects.md`](https://day8.github.io/re-frame2/spec/Managed-Effects/) | `:rf.http/managed` |
| **007 Stories** | Q5 | [`spec/007-Stories.md`](https://day8.github.io/re-frame2/spec/007-Stories/) | — |
| **Tool-Pair attachment** | Q6 | [`spec/Tool-Pair.md`](https://day8.github.io/re-frame2/spec/Tool-Pair/) — mostly a coherent exposure of EP surfaces already built; the genuinely new piece is the time-travel/epoch surface, and direct tool reads fail closed through `project-egress` | the `:derivation/algebra-graph` split pair |
| **016 Resources** | Q10 (presupposes Q9) | [`spec/016-Resources.md`](https://day8.github.io/re-frame2/spec/016-Resources/) | `:resources/*` (the mutation half is corpus-behind — self-test from the spec) |
| **AI-Audit** | Q7 | [`spec/AI-Audit.md`](https://day8.github.io/re-frame2/spec/AI-Audit/) — a discipline tool, not a runtime surface: claiming yes means the port maintains its own AI-first audit doc per the Spec | — |

**Acceptance gate 2.** The full claimed-capability fixture set at the pin; score `claimed-applicable / claimed-applicable`. A failure that is not a spec gap is a port bug; a spec gap is drafted and filed upstream with approval (cardinal rules 8–9).

## Cross-cutting obligations (spec-owned; read, don't restate)

- **The uniform async reply envelope** — [`spec/Managed-Effects.md` §The uniform reply envelope](https://day8.github.io/re-frame2/spec/Managed-Effects/#the-uniform-reply-envelope) binds every async family the port claims (HTTP, resources + mutations, machine async work, route loaders): one reply target, one closed status set, one work identity, mandatory stale suppression, causal `:completed-at`. Do not build per-family reply vocabularies.
- **The composition substrate** — `image → frame → event stream` ([`spec/002-Frames.md` §Resolved decisions](https://day8.github.io/re-frame2/spec/002-Frames/#resolved-decisions)): one registrar, `rf/image` / `rf/make-frame`, frame-derived resolution. There is **no realm / app / module composition layer** to build or conformance-check.
- **Tooling security** — [`spec/Implementor-Checklist.md` §Security obligations](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#security-obligations-for-implementation-tooling) binds any tooling the port ships, the conformance harness included: the env-var write-path allowed-prefix constraint, and `javascript:` / `data:` / `vbscript:` scheme rejection at both registration and click time.

## Who runs the gates

The port's **discovered noninteractive gates are the agent's to run** when it has tool access: the loop's step-4 slice every time, and the full gate-1 / gate-2 conformance passes when the engineer asked for an end-to-end implementation. Report the exact commands, exit codes, and `passed / claimed-applicable`. Hand off only genuinely interactive/visual evidence (a rendered browser surface with no drivable runtime) as a concise programmer handoff — and never call an EP or the port complete while that required evidence is pending. The agent uses the session's normal permissions; there is no skill-local engineer/agent relay policy.
