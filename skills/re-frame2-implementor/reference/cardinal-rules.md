# Cardinal rules — re-frame2 implementor

These hold across every phase of building a new re-frame2 implementation. Each rule expands the one-line form carried in `SKILL.md`.

---

## 1. The spec is the contract

[`spec/`](../../../spec/) is the source of truth. The CLJS implementation under `implementation/` is one worked example of how to realise the contract — not the contract itself. When the reference impl and the spec disagree, the spec wins; file a GitHub issue against `day8/re-frame2` against the spec or the reference impl as appropriate.

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

[`spec/conformance/`](../../../spec/conformance/) is the verification mechanism. Your port runs the fixtures whose capabilities are a subset of what you've claimed; the score is `passed / claimed-applicable`. A fixture you cannot make pass without consulting outside sources is a **spec gap**, not an implementation gap — file a GitHub issue against `day8/re-frame2` against the spec corpus.

## 8. If you find a spec gap, file a GitHub issue. Do not paper.

When implementing surfaces a missing surface, an inconsistency, or an undocumented decision, that's a spec finding. Surface it as a GitHub issue against `day8/re-frame2`. Do not silently invent an answer; do not extrapolate from "what the reference impl does" if the spec is silent.

`bd` (beads) is the re-frame2 monorepo's internal tracker and is **never invoked from a published skill**. The implementor skill runs in the engineer's port repo; spec gaps reach the framework maintainers via the upstream repo's GitHub issues.

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

## 9. Announce before any cross-repo side effect

Filing a GitHub issue against `day8/re-frame2` from inside the engineer's port repo is a cross-repo side effect. Before the call, post a one-line announcement — title, target repo, body summary — so the engineer can Ctrl-C or steer. Per-issue approval isn't required (they've invoked the skill); the gate is that they always know an issue is about to land somewhere other than where they're standing.

---

## Anti-patterns (rule corollaries)

- **Don't copy the reference impl line-by-line and translate.** The reference uses macros for source-coord capture; your host may not have macros. The reference uses Reagent's automatic dependency tracking; your host may need explicit subscriptions. The reference uses keywords; your host may use branded strings. *Copy the contract, not the realisation.*
- **Don't skip Phase 1.** Decisions made under Phase 1 (especially F2 persistent data structures and F5 concurrency model) propagate through every line of Phase 2 code. Engineers who skip Phase 1 to "just start coding" end up rewriting the foundation halfway through.
- **Don't declare Q1 (state machines) `yes` unless the FSM substrate is genuinely required.** EP 005 is substantial work and gates a large block of code. Smaller ports ship `Q1=no` and add the FSM substrate later — the events/subs/fx/views triad is self-sufficient for many use cases.
- **Don't ship without conformance.** The corpus is the only objective measure of "is this re-frame2?" Without the corpus passing, the port is "inspired by re-frame2" but not a re-frame2 implementation. Run the harness early; the corpus is also useful as your test suite during development.
- **Don't invent surfaces the spec is silent on.** If the spec says nothing about hierarchical FSM exit-order semantics in a specific edge case, *file a GitHub issue against `day8/re-frame2`* — don't extrapolate from the CLJS reference. The reference's choice is one realisation; the spec's silence is a gap.
- **Don't file cross-repo issues silently.** Every `gh issue create` against `day8/re-frame2` gets a one-line announcement first (title, repo, body summary). The engineer always knows when the skill is about to touch a repo other than the one they're standing in. See rule 9.
- **Don't interpolate transcript-derived text into a `gh issue create` command.** Use the here-doc + `--body "$(cat /tmp/file)"` pattern under rule 8.
- **Don't promise the engineer "this skill will write the port for you".** The skill walks the workflow and surfaces the decisions; the engineer (or their Claude session) writes the code. Phase 2 is multi-week work in any host; the skill is the map, not the vehicle.
