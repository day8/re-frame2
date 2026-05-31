# Guide Outline

This is the authoring map for `docs/guide/`. The guide is the human tutorial track: it teaches the runtime model by building and modifying programs, then hands off to the API reference, spec, Story docs, and Xray docs when the reader needs those surfaces.

The guide is not the spec and should not read like the spec. It should teach enough detail that a motivated programmer can build a real re-frame2 app without guessing.

## Structure Rules

1. Chapter files use straight `NN-name.md` ordering. Do not introduce `01a`, `01b`, or sidecar chapter numbers.
2. Visible chapter titles use `NN - Title`.
3. Every numbered chapter opens with a short reader-problem summary: what pain or curiosity brought the reader here, and what they will be able to do afterward.
4. Do not add a generic "What's next" section to every page. Cross-link when useful; let the navigation do the rest.
5. Prefer worked examples over abstract prose. Prefer runnable cells only when editing the code teaches more than reading it.
6. Keep Story and Xray as linked tool-doc handoffs. The guide may explain the runtime evidence they read, but their APIs and full workflows belong in their own docs.
7. Preserve the example spine. The counter carries the core architecture; login/forms/auth carry real app workflow; runtime/tooling examples carry operations. New examples should earn their place by teaching a concept those spines cannot teach cleanly.

## Chapter Set

| # | File | Job |
|---|---|---|
| 01 | `01-introduction.md` | Give the reader the mental model and a live first program. |
| 02 | `02-app-db.md` | Teach state as one inspectable value. |
| 03 | `03-first-app.md` | Build the counter for real and meet the core primitives. |
| 04 | `04-events-and-the-cascade.md` | Walk one event through the six-domino cascade. |
| 05 | `05-subscriptions.md` | Teach derived reads, query vectors, chaining, and cache behavior. |
| 06 | `06-views.md` | Teach views as derivative UI, not causal state owners. |
| 07 | `07-effects-and-coeffects.md` | Teach controlled inputs and outputs at the boundary of purity. |
| 08 | `08-schemas.md` | Teach boundary validation and schema-backed confidence. |
| 09 | `09-interceptors.md` | Teach reusable event-pipeline behavior. |
| 10 | `10-http.md` | Teach managed remote data without callback soup. |
| 11 | `11-forms.md` | Teach draft state, validation, submission, and field errors. |
| 12 | `12-machines.md` | Teach dynamic processes without boolean soup. |
| 13 | `13-testing.md` | Teach cheap tests: events, subs, views, effects, and integration scripts. |
| 14 | `14-errors.md` | Teach how failures are emitted, surfaced, and recovered from. |
| 15 | `15-performance.md` | Teach subscription/render performance and how to see it. |
| 16 | `16-observability.md` | Teach trace/epoch evidence and production-safe observation. |
| 17 | `17-tooling.md` | Orient the reader to Story, Xray, and MCP without duplicating their docs. |
| 18 | `18-frames.md` | Teach isolated runtime instances. |
| 19 | `19-routing.md` | Teach URL state, links, route params, query params, and guarded navigation. |
| 20 | `20-server-side.md` | Teach SSR as another frame use, not a second app architecture. |
| 21 | `21-dynamic-model.md` | Teach runtime registration and dynamic model concerns. |
| 22 | `22-adapters.md` | Teach Reagent, UIx, Helix, SSR, and headless adapter boundaries. |
| 23 | `23-privacy-and-large-things.md` | Teach redaction, elision, and large values. |
| 24 | `24-config-and-safety.md` | Teach configuration, safety knobs, and production posture. |
| 25 | `25-from-re-frame-v1.md` | Map v1 habits to v2 and explain why migration is worth it. |
| 26 | `26-operating-well.md` | Provide the operating map: examples, API, spec, patterns, tools, skills. |

## Live Cell Policy

Use `cljs-rf2` cells for ideas the reader should edit and rerun:

- First counter.
- Event/cascade tracing.
- Subscription derivations.
- Small view-state examples.

Do not turn the whole guide into an embedded IDE. Most chapters should use static listings plus a clear checkpoint.

## Quality Bar

A foundational chapter should usually include:

- A concrete reader problem.
- A complete or nearly complete code listing.
- A walkthrough of the listing.
- A common mistake.
- A checkpoint the reader can run or reason through.
- Links to API/spec/tool docs only after the chapter has taught the usable idea.

If a chapter is under 100 lines and covers a foundational concept, assume it is probably too thin.

## Flow Check

Every chapter should answer three questions before it ships:

- What problem did the previous chapters make visible?
- What is the smallest example that teaches this chapter's new idea?
- What curiosity or practical problem should now feel unlocked?

Do not turn the third answer into a boilerplate "What's next" section. A good final paragraph is enough.
