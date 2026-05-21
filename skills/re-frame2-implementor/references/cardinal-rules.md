# Cardinal rules — re-frame2 implementor

These hold across every phase of building a new re-frame2 implementation. Each rule expands the one-line form carried in `SKILL.md`.

---

## 1. The spec is the contract

[`spec/`](../../../spec/) is the source of truth. The CLJS implementation under `implementation/` is one worked example of how to realise the contract — not the contract itself. When the reference impl and the spec disagree, the spec wins; draft a GitHub issue against `day8/re-frame2` (against the spec or the reference impl as appropriate) and ask the engineer to OK it before filing — see rule 9.

**Pin the spec before reading it.** Phase 1 names a specific re-frame2 commit/tag in `DECISIONS.md` (see [`decision-record.md`](decision-record.md) D1 preamble). Verify `git -C <path-to-re-frame2> rev-parse HEAD` matches the pin and that the origin is `https://github.com/day8/re-frame2` before reading the spec corpus. An unpinned or unverified checkout is not a contract — it's whatever happens to be on the filesystem.

## 2. Phase 1 before Phase 2

Decisions made under Phase 1 (host language, substrate, scope, identity primitive, concurrency model) are load-bearing for every line of code written in Phase 2. Lock them in writing before opening an editor. If a Phase 2 step forces a Phase 1 decision to change, **stop**, revise the decision record, and restart that Phase 2 step.

## 3. Implement in dependency order

EP 001 (Registration) → 002 (Frames + events + effects + subs) → 006 (Reactive substrate) → 004 (Views) → 009 (Instrumentation) are the foundation. Acceptance gate 1 — running the `:core/*` conformance fixtures — sits at the end of this cluster; 009 is in the foundation because `:core/trace` and `:core/error` fixtures exercise it. The optional EPs (005, 007–008, 010–014) sit downstream and can be deferred or skipped per Phase 1 scope.

## 4. Substrate-agnostic phrasing in code and docs

The reference impl is a CLJS+Reagent realisation — keywords as ids, hiccup as render-tree, Reagent ratoms as the reactive container. These are *choices the reference made*. Your port chooses differently and re-frame2 is still re-frame2. Do not write code that assumes "hiccup" or "Reagent" or "keyword" universally; write code that assumes "the identity primitive", "the render-tree", "the reactive container".

## 5. No core.async equivalents

The CLJS reference does not use core.async, and ports inherit this directive. Async effects schedule via host primitives (Promise / setTimeout / setImmediate / asyncio / tokio); cross-frame work is serialised per frame via the run-to-completion drain. If your host's idiomatic concurrency model is channel-shaped, you may use channels *internally* — but the public dispatch contract is still the run-to-completion drain.

## 6. JVM-runnability for the testing surface

Where the CLJS reference makes `compute-sub` and `machine-transition` pure-function-callable from JVM, your port's analogue must be callable from a non-substrate test harness. Pure transitions and pure sub computations are the bedrock of the test story.

## 7. Conformance corpus is the acceptance test

[`spec/conformance/`](../../../spec/conformance/) is the verification mechanism. Your port runs the fixtures whose capabilities are a subset of what you've claimed; the score is `passed / claimed-applicable`. A fixture you cannot make pass without consulting outside sources is a **spec gap**, not an implementation gap — draft a GitHub issue against `day8/re-frame2` against the spec corpus, then file with engineer approval (rule 9).

## 8. If you find a spec gap, draft a GitHub issue and ask before filing. Do not paper.

When implementing surfaces a missing surface, an inconsistency, or an undocumented decision, that's a spec finding. **Draft** a GitHub issue against `day8/re-frame2`, show the engineer the title + body, and **ask for explicit OK before running `gh issue create`** (rule 9). Do not silently invent an answer; do not extrapolate from "what the reference impl does" if the spec is silent.

`bd` (beads) is the re-frame2 monorepo's internal tracker and is **never invoked from a published skill**. The implementor skill runs in the engineer's port repo; spec gaps reach the framework maintainers via the upstream repo's GitHub issues — and only after the engineer has OK'd the specific issue body.

**Body contents — public evidence only.** The issue body quotes `spec/` and names the EP / fixture / capability. It does NOT paste private port source, the engineer's commits, transcripts, repo-local paths, or any text the engineer hasn't seen. Re-use spec text; describe the gap; show the minimal reproduction shape; stop.

**Shell safety for `gh issue create`.** Spec-gap bodies often include error traces, log output, or excerpts from the engineer's port — text the user never inspects character by character. Always pass the body via a file:

```bash
cat > /tmp/spec-gap.md <<'EOF'
## Context
…port and EP being implemented…

## What the spec is silent on
…concrete surface, with quotes from spec/<EP>.md…

## Why this is a spec gap, not a port bug
…cannot be resolved without consulting outside sources…
EOF

gh issue create \
  --repo day8/re-frame2 \
  --title "spec-gap(EP-NNN): <one-line>" \
  --body "$(cat /tmp/spec-gap.md)" \
  --label spec-gap,from-implementor
```

The single-quoted here-doc delimiter (`<<'EOF'`) keeps `$`, `` ` ``, and `\` inside the body literal. Pattern is documented in `skills/README.md` §Published-skill `allowed-tools` baseline.

## 9. Approval gate before any cross-repo side effect

Filing a GitHub issue against `day8/re-frame2` from inside the engineer's port repo is a cross-repo side effect. **Per-issue approval IS required.** Before the call, show the engineer the full draft — title, target repo (`day8/re-frame2`), label set, body — and wait for an explicit OK. "Continuing in a moment" is not enough; the engineer types "go" / "yes" / "file it" or the skill does not run `gh issue create`. The cost of a delayed file is minutes; the cost of an unwanted cross-repo issue is permanent and visible.

Invoking the skill is consent to the *workflow*, not consent to each *cross-repo write*. Treat the two as separate gates.

## 10. Pin the spec corpus before reading it

The kickoff prompt names a specific `day8/re-frame2` commit/tag. Verify the checkout's HEAD and origin before reading the spec, and record the pinned hash in `DECISIONS.md` (the preamble before D1). Confirm `git -C <path-to-re-frame2> rev-parse HEAD` matches the pin and that the origin is `https://github.com/day8/re-frame2`. An unpinned or unverified checkout is not the contract — it is whatever happens to be on the filesystem. (This restates, as a standalone rule, the pinning discipline also threaded through rule 1.)

## 11. Honour the reserved `:rf/*` namespace scheme

re-frame2 reserves **one root keyword namespace** for framework-owned ids: `:rf/*` (and its sub-namespaces — `:rf.fx/*`, `:rf.machine/*`, `:rf.error/*`, `:rf.registry/*`, …). Every framework runtime id — events, fx, cofx, app-db keys (`:rf/machines`, `:rf/route`), trace operations, error categories, warnings, registrar mutations, the default frame id (`:rf/default`) — lives under that root. **User code MUST NOT register handlers, fx, subs, or frames under `:rf/*`.** Your port must (a) emit framework ids under the reserved scheme and (b) leave the scheme free for the framework, never user code.

This is a conformance surface, not a style preference. Fixtures assert `:rf.*` operation ids on the trace stream and reserved app-db keys at known paths; a port that invents its own framework-id namespace, or lets app code squat `:rf/*`, fails them. The reserved set, reserved fx-ids, and reserved app-db keys are catalogued in [`spec/Conventions.md`](../../../spec/Conventions.md). The "which spec owns this surface" map — the single most useful index for a port author asking "where does X live?" — is [`spec/Ownership.md`](../../../spec/Ownership.md). Consult [`spec/API.md`](../../../spec/API.md) for the consolidated public signatures when wiring each EP's surface.

---

## Anti-patterns (rule corollaries)

- **Don't copy the reference impl line-by-line and translate.** The reference uses macros for source-coord capture; your host may not have macros. The reference uses Reagent's automatic dependency tracking; your host may need explicit subscriptions. The reference uses keywords; your host may use branded strings. *Copy the contract, not the realisation.*
- **Don't skip Phase 1.** Decisions made under Phase 1 (especially F2 persistent data structures and F5 concurrency model) propagate through every line of Phase 2 code. Engineers who skip Phase 1 to "just start coding" end up rewriting the foundation halfway through.
- **Don't declare Q1 (state machines) `yes` unless the FSM substrate is genuinely required.** EP 005 is substantial work and gates a large block of code. Smaller ports ship `Q1=no` and add the FSM substrate later — the events/subs/fx/views triad is self-sufficient for many use cases.
- **Don't ship without conformance.** The corpus is the only objective measure of "is this re-frame2?" Without the corpus passing, the port is "inspired by re-frame2" but not a re-frame2 implementation. Run the harness early; the corpus is also useful as your test suite during development.
- **Don't invent surfaces the spec is silent on.** If the spec says nothing about hierarchical FSM exit-order semantics in a specific edge case, *draft a GitHub issue against `day8/re-frame2`* (and ask the engineer to OK before filing — rule 9) — don't extrapolate from the CLJS reference. The reference's choice is one realisation; the spec's silence is a gap.
- **Don't file cross-repo issues without explicit OK.** Every `gh issue create` against `day8/re-frame2` shows the engineer the full drafted title + body + labels and waits for "yes" / "go" / "file it" before running. The engineer always confirms each issue, not just the workflow. See rule 9.
- **Don't paste private port content into a cross-repo issue body.** Issue bodies cite `spec/` and the fixture / EP. They do not include the engineer's source, commits, transcript, or repo-local paths. See rule 8.
- **Don't interpolate transcript-derived text into a `gh issue create` command.** Use the here-doc + `--body "$(cat /tmp/file)"` pattern under rule 8.
- **Don't read the spec corpus without verifying the pin.** Phase 1 records a `day8/re-frame2` commit/tag in `DECISIONS.md`. Confirm `git -C <path-to-re-frame2> rev-parse HEAD` matches and the origin is `day8/re-frame2` before reading. An unverified or unpinned checkout is not the contract.
- **Don't promise the engineer "this skill will write the port for you".** The skill walks the workflow and surfaces the decisions; the engineer (or their Claude session) writes the code. Phase 2 is multi-week work in any host; the skill is the map, not the vehicle.
