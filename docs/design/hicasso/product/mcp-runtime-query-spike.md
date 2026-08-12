# The MCP-queryable runtime — criteria, then verdict (rf2-hic-059)

`rf2-hic-059` asks whether an AI pair should be able to interrogate a *running* Hicasso application through MCP, and what that surface should be. This page carries the criteria that decide it, frozen before anything was measured, and — below them, appended later — the measurement and the verdict.

**The spike's completion is the verdict, not adoption.** The operator ruling of 2026-08-12 17:36 AUSEST (`rf2-xpq9`) puts every Phase-5 decision-shaped item in v0 scope on exactly those terms: *the spike RUNS and its pre-registered verdict is MADE within v0; adoption follows its own criteria. A ruled STOP still completes the item.* A STOP here is therefore a completed bead, not a deferred one.

## Pre-registration record

| Field | Value |
|---|---|
| Subject | An MCP-queryable runtime for the AI pair: which reads of a running Hicasso application an agent may call over MCP |
| Registered by | `rf2-hic-059`, on the specification, the tool catalogue and the shipped source alone |
| Frozen | 2026-08-12 20:06 AUSEST, before any read was driven and before any source was read for an answer rather than for the question |
| Evidence source | The `rf2-hic-025` slice application, driven through the real commit seam, and the shipped `tools/re-frame2-pair-mcp` descriptor set |
| Default verdict | STOP. ADOPT requires every criterion met |
| Amendment | Prospective only. A criterion changed after a number exists is not a criterion |

**No commit can contain its own hash**, so the freezing revision is named in the verdict section below and is the hash to cite. The criteria text between here and [The measurement](#the-measurement) is byte-for-byte what that revision carries; anything after it was written afterwards and says so.

## What is being decided

The bead asks for "the smallest read-only surface (mounted views + recent intents + complaints) reusing Xray/Pair's one privacy-projected schema — no new retention, no parallel graph/history".

Three candidates are therefore live, and they are decided separately because they have different answers available to them:

| Id | Candidate | Why it is a question |
|---|---|---|
| Q0 | The runtime reads that **already ship** as MCP tools | The bead's acceptance demands the surface be shown to work, and a read that only answers what a fixture told it has shown nothing |
| Q1 | `re-frame.hicasso.tool/read-intents` as a **fourth** MCP tool | The door has this read; the wire deliberately does not ship it |
| Q2 | A runtime **complaint** query as a fifth | The complaint catalogue is a static document; nothing asks a running application what it has refused |

Out of scope, and not evidence for or against: the Xray Hicasso panels, which consume the same door and are decided elsewhere; the evidence projection's own shape (`rf2-hic-023`); the production-erasure law (`rf2-hic-024`), which is a standing gate this spike may not move; any latency or token figure, which this spike does not measure and does not need.

## The criteria

The default is STOP. A candidate is ADOPTED only if **every** one of A1–A4 holds for it, and is STOPPED the moment any of S1–S3 holds.

### A1 — A question of its own

The candidate answers at least one question that no already-shipped tool answers, stated as a question an agent would actually ask, with the shipped tool that comes closest named and the difference stated.

This is the tool catalogue's own standard rather than a new one: a read shipped under a new name that folds a window another tool already folds is *surface without a question of its own*. Meeting A1 requires naming the closest shipped tool and showing it cannot answer — not merely that it answers differently.

### A2 — Non-empty on a population it did not author

Driven against the `rf2-hic-025` slice application — an application written for another bead, whose subscriptions, events and views this spike does not get to choose — the read answers a **non-empty** result whose contents are that application's own registered ids.

A fixture minted for the query is inadmissible. The whole failure mode this criterion exists against is a census that can only report what its own harness just planted, and this programme has already produced six of those.

### A2c — And it can answer empty

The **same** read, with the population absent, answers empty (or states an explicit absence). A read that answers non-empty whether or not the application is there is not reading the application, and its non-empty answer is not evidence.

A2 and A2c are one criterion in two halves and neither counts alone.

### A3 — Divergence under population change

The same read, driven twice against **different** populations of the same application, answers **differently**, and the difference is the change that was made. The diverging values are published verbatim, not summarised, so a reader can check that the answer moved for the stated reason.

A number, roster or table that cannot be made to move is not a measurement of the runtime; it is a constant.

### A4 — Free at the boundaries this programme has already fixed

The candidate needs **none** of:

- new retention, or any parallel graph / history subsystem — the bead's own fence;
- an `implementation/` → `tools/` `:require` edge — bundle isolation;
- a new hook on the boundary shell — invariant I9, and the `substrate-decision.md` two-hook ceiling;
- an npm dependency;
- a hot-zone edit (`implementation/shadow-cljs.edn`, `implementation/deps.edn`, `spec/**`, `.github/workflows/**`);
- a change to what a production build erases (`rf2-hic-024`).

A candidate that needs any of them is not stopped *because* it is expensive — it is stopped because these are settled boundaries and a spike does not get to move one.

### S1 — Already answered

A shipped tool answers the candidate's question. STOP: shipping it again under a new name adds surface and a second thing to keep correct, and buys an agent nothing.

### S2 — Structurally empty

The candidate cannot answer non-empty on a real population, or cannot be made to diverge. STOP, and say which — "the runtime does not retain that" and "the read is broken" are different findings.

### S3 — Needs retention the bead forbids

Answering requires the runtime to retain something it does not retain today. STOP: the bead forbids new retention in terms, so a candidate needing it is refused by its own specification rather than by a judgement call.

### Amendment rule

A criterion may be changed only **before** the measurement it governs exists, and the change is recorded here with its reason. After that the criteria are fixed and the verdict follows them mechanically. A criterion relaxed to fit a number is a rationalisation with a table.

## The measurement

*Appended after the criteria above were frozen. Nothing below governs anything above.*

The criteria were frozen at authored head `d699c430f1`, the first commit on this branch, which contains the text above and none of the text below; the branch's base is the landed commit `8f12343115`. That pair is the pre-registration proof: a verdict that cannot name a criteria commit predating its own evidence has not pre-registered anything, whatever its dates claim.

This repository rebase-merges, so the authored head is re-minted the moment the PR lands and is then reachable from no ref — which is why the landed base is cited beside it, and why the landed hash of the freeze is back-filled here afterwards, exactly as [`resource-demand-criteria.md`](resource-demand-criteria.md) records its own.

The evidence is `implementation/hicasso/test/re_frame/hicasso/mcp_runtime_query_spike_cljs_test.cljs`, which drives the four `re-frame.hicasso.tool` reads against the `rf2-hic-025` slice application — booted through the application's own `make-frame!`, its six bodies reached with `codec/retained-body` and run through the real commit seam. The suite registers no subscription, no event and no view.

### The premise this bead was written on did not hold

**The MCP-queryable runtime already ships.** `tools/re-frame2-pair-mcp` exposes three of the four door reads as wire tools today — `read-mounted-boundaries`, `read-read-attribution` and `explain-render` — each an eval of `re-frame.hicasso.tool` that resolves the door at runtime — `cljs.core/find-ns-obj` on the namespace, since `rf2-t2ec` replaced the original `exists?` guard, whose `:evidence-tier-unavailable` rung was unreachable in any app that had never loaded the door — gated against a consumer-owned evidence-schema literal, with `hicasso_wire_test.cljs` holding the wire names and the provider's fn names in agreement. The bead asks for a surface that was built while it sat in the queue.

So the spike's live question is not *should there be one* but *what is missing from the one there is*, and the answer decides three candidates rather than one.

### Non-empty, and able to answer empty

Each read was called first against a **booted and seeded** application with **nothing mounted**, and then against the mounted feed route. The control runs first in the file, deliberately: it is what licenses reading the second column as evidence about the application rather than about the reader.

| Read | Population absent | Population present (feed route) |
|---|---|---|
| `read-mounted-boundaries` | `:boundaries []`, `:complete? true` | non-empty; names `::subs/feed`, `::subs/t`, `::subs/token`, `::subs/tags-open?`, `:rf.route/id` |
| `read-read-attribution` | `:edges []` | non-empty; `::subs/t` held with positive fan-out; `::subs/save-state` correctly ABSENT (the editor is not on this route) |
| `explain-render` | `:explanations []` | non-empty, one explanation per distinct edge set |
| `read-intents` | — | non-empty; names `::events/set-theme`, whose run recomputed `[::subs/feed ::subs/locale ::subs/tags-open? ::subs/theme ::subs/token]` |

Every id in the right-hand column was registered by `rf2-hic-025` for another purpose. Nothing in the witness registers one.

### Divergence — the same read, two populations, two answers

| Read | Change made | Answer before | Answer after |
|---|---|---|---|
| `read-mounted-boundaries` | feed route → article route | holds `::subs/feed`; no `::subs/draft` | holds `::subs/draft`, `::subs/revision`, `::subs/dirty?`, `::subs/save-state`; no `::subs/feed` |
| `read-read-attribution` | feed route → article route | edge set differs from the article's | edge set differs from the feed's |
| `explain-render` (chrome) | `::events/set-theme` vs `::events/set-locale` | `:latest-reads` = `#{::subs/theme}` | `:latest-reads` = `#{::subs/t}` |
| `explain-render` (root) | `::events/set-theme` vs `::events/set-locale` | `:latest-reads` = `#{::subs/token}` | `:latest-reads` = `#{::subs/t}` |

The shell's reads (`::subs/token`) stay held across the route change, which is what makes the roster row a *difference* rather than a reset.

### What the run found that reading the source does not give

The slice's root boundary **holds a string-table edge**. `views/app` reads two theme tokens and the route id; it also reads `[::subs/t :app/pane-error]`, because the `h/error-boundary` fallback is markup written in the root's own body and is therefore evaluated when the root runs, not when a pane throws. So the root re-stamps on every locale change, for a sentence that reaches the screen only after a failure.

Nothing is wrong with the slice — the fallback has to be built somewhere and its own docstring explains why it is built there. The point is that the edge is a **runtime fact the read states outright and a reading of the body does not**, which is exactly the question the bead set.

Two expectations written into the witness before it ran did not survive it, and both are kept in the file rather than edited away: that a locale switch would leave the root's `:peak-epoch` standing (it rose), and that the chrome's locale answer would be `#{::subs/locale ::subs/t}` (it is `#{::subs/t}` alone — the layer-2 string table is re-stamped after the read it derives from, so it alone stands at the maximum).

### Two qualifiers a consumer of these reads has to know

- **`:sub-ids` egresses as an ordered vector, not a set.** `intent-row` builds a set and the determinism ordering turns it into one total order before it ships. A consumer reaching for `contains?` is asking about indices and gets a quiet `false`. This witness made that mistake first.
- **`:latest-reads` is the boundary's OWN maximum epoch.** It is not *what the last dispatch moved*. For a boundary the last dispatch did not touch, the row still names reads and they are historic.

### The verdict

| Candidate | Verdict | Criterion |
|---|---|---|
| Q0 — the three shipped runtime reads | **ALREADY ADOPTED, and now witnessed on a real application** | A1, A2, A2c, A3, A4 all met |
| Q1 — `read-intents` as a fourth MCP tool | **DO NOT ADOPT** | S1 |
| Q2 — a runtime complaint query | **DO NOT ADOPT** | S3, and S1 for the retained half |

**Q0.** A1 is met and the closest shipped tool is `list-subscriptions`, which reads the frame's live sub-cache: it has no boundary side at all — no readers, no fan-out, no per-boundary edge set — so it cannot answer *which boundaries read this*, and neither can anything else that ships. A2, A2c and A3 are the two tables above. A4 is met because nothing was added. **There is no further work here**; the surface exists, and this spike's contribution is that it has now been driven against an application it did not write.

**Q1 — `read-intents` on the wire: STOP on S1.** *What was dispatched, in what order, and what did each run recompute* is answered by two shipped tools. `trace-window` pulls the per-frame epoch ring and egresses `:trace-events`, whose cascade tags carry `:subs-recomputed` as `{:sub-id :query-v}` pairs; `subscribe` streams event bundles keyed `[frame dispatch-id]` — the same bundles `read-intents` folds — carrying `:event`, `:subs` and `:renders`, and its all-frame stream already delivers bundles from several frames in one tick. The one thing `read-intents` does that neither does is merge two ring fragments of one dispatch into a single row, which is a grouping an agent performs on `[frame dispatch-id]` itself. A1 requires showing the shipped tool *cannot* answer, not that it answers differently.

This confirms rather than re-decides: the tool catalogue already ruled `read-intents` off the wire as "surface without a question of its own" when the family was re-authored. The spike's job was to check that ruling against a real application, and it holds.

**The one live argument for revisiting is recorded rather than dismissed.** `read-intents` carries an *absolute* promise — an id and an arity, never the vector, under any classification — while `trace-window`'s event-vector redaction is keyed to what a registration declared, and EP-0025's model is fail-open, so an undeclared event ships its arguments. That is a difference in kind, not degree. It is not adopted here because it is a difference in **posture**, not a different question, and no evidence exists that a pairing session ever needed the absolute form. What would change the verdict: a session where the intent ORDER was the needed fact and the sensitive-reads posture refused the tools that carry it.

**Q2 — a runtime complaint query: STOP on S3.** Hicasso refusals are thrown by `impl.error/fail!` and **retained by nobody** — the constructor mints the `ex-info` and throws it. A census of *what has this application refused* therefore requires the runtime to start retaining refusals, and this bead forbids new retention in terms, so the candidate is refused by its own specification rather than by a judgement call.

The two halves that already have answers are worth naming so the STOP is not read as a gap:

- a refusal raised **inside an event or effect cascade** is a `:rf.error/*` trace op and rides `trace-window`'s `:errors` slot already — S1;
- a refusal raised **during render or mint** is outside any cascade and is retained nowhere, which is S3 in its sharpest form: the finding is *the runtime does not retain that*, not *the read is broken*;
- the **static** half — id, meaning, payload shape, recovery ladder — is [`complaints.md`](complaints.md), round-tripped against the runtime by `check_complaint_catalogue.py`, and an agent reads it directly.

### What this spike did not need, and did not touch

No new public export. No `implementation/` → `tools/` `:require` edge. No npm dependency. No hot-zone file. No new retention, no parallel graph or history. No hook added to the boundary shell, so I9 and the two-hook ceiling are untouched. No change to what a production build erases: the witness is a test namespace, and `re-frame.hicasso.tool`'s dev-only gating is `rf2-hic-024`'s and was not moved.

The bead's own acceptance also asks that the privacy projection be verified. It is, and by the suite that owns it rather than a second copy here: `tool-reads-cljs-test`'s seeded-value rows prove the cells demonstrably hold a secret and that none of the four envelopes carries it. This spike adds the population that suite could not have — a real application — and repeats none of its assertions.
