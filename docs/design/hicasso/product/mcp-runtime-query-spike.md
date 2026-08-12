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

<!-- VERDICT-APPENDED-BELOW -->
