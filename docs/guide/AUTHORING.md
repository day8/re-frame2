# Guide Authoring Notes

These notes are for authors of `docs/guide/` chapters. Spec authors should read
`spec/SPEC-AUTHORING.md` (if it exists) — these rules are about the human
tutorial track, not the normative spec track.

## Linking from the guide to the spec

The guide is for **human readers**; the spec is for **AI agents and
implementors**. The spec is dry, normative, and exhaustive by design — not
where a tutorial reader should land mid-chapter.

When you want to send the reader to spec material, **paraphrase the load-bearing
detail inline** rather than linking out, except for these explicitly permitted
spec docs:

| Doc | Why permitted |
|---|---|
| `spec/Principles.md` | Framework philosophy; load-bearing for a curious reader |
| `spec/000-Vision.md` | Human-readable philosophy track; the 'why re-frame2 exists' doc. Permitted but most chapters won't need it; ch.26 (operating map) is its natural home. |
| `migration/from-re-frame-v1/README.md` | v1 -> v2 migrants are a distinct audience; ch.25 is their on-ramp |
| `spec/Pattern-AsyncEffect.md` | Runnable convention; cross-cutting across ch.04 / ch.09 / ch.16 |
| `spec/Pattern-RemoteData.md` | Runnable convention; cross-cutting across ch.02 / ch.08 / ch.10 |
| `spec/Pattern-Forms.md` | Runnable convention; the 7-event lifecycle is reused |

**Chapter 26 (Operating well) is exempt** from this restriction. It is the
reference/tooling portal: curated lists of spec docs, API pages, examples,
patterns, Story/Xray docs, and skills are appropriate there.

## What "paraphrase inline" means

When the chapter prose hits the boundary of "user needs to know X about the
spec", state X in chapter-flavoured prose. Don't write "See Spec NNN for the
full table" — give the reader the table or the relevant rows directly.

The spec link is correct when:
- The link target is in the permitted set above, AND
- The user genuinely benefits from following it (not a defensive citation).

The spec link is wrong when:
- It's a "see spec for the full story" sentence used to dodge writing the
  chapter explanation.
- It's a parenthetical citation (e.g. "(per Spec 010)") — citations belong in
  the spec, not the guide.
- It's a chapter-end "Further reading" pointing at a numbered Spec doc the
  tutorial reader has no business reading.

## Cross-chapter linking

Linking guide chapter → guide chapter is fine and encouraged (e.g. "covered in
ch.13"). The reader stays in the tutorial track.

## When you discover a gap

If you find yourself wanting to link to a spec doc that's NOT in the permitted
set, that's a signal the chapter prose is incomplete. Add the missing prose;
don't reach for the link.

## Drift watch

The guide's spec-link policy was tightened retrospectively after chapter
authors had started reaching for spec URLs as a substitute for writing
the explanation in chapter prose. Future chapter authors who skip this
policy will reintroduce the same drift — keep the tutorial track
self-sufficient.

## The "for the categorically curious" callout

re-frame2's design is grounded in functional and category-theoretic ideas
(folds, lattices, lenses, derivation algebras). Those ideas are real and they
earn the framework its shape — but they are *design tools*, not *teaching
vocabulary*. The guide's rule is **translate, don't transplant**: the chapter
body explains an idea in plain language, framed as a payoff for the reader; the
category-theory vocabulary appears *only* inside an optional, collapsible
callout for the reader who enjoys the deeper unifying frame.

The device is an mkdocs-material collapsible admonition (the `pymdownx.details`
extension, already enabled), titled **"For the categorically curious"**:

```markdown
<details markdown="1">
<summary>For the categorically curious</summary>

All four homes are the same thing seen four ways: a node in one dependency
graph, distinguished by its storage policy and its evaluation policy. ...
</details>
```

The rules — non-negotiable, so the device stays a treat and never a tax:

- **Always collapsible and always skippable.** Use the `<details>`/`<summary>`
  form (collapsed by default), never a plain `> **Note:**` block or an
  always-expanded admonition. A reader who never opens it must lose nothing.
- **The surrounding prose must stand alone.** The chapter has to make complete
  sense to a reader who skips every callout. The callout *adds* a frame; it
  never *carries* a load-bearing fact. If the body can't be understood without
  it, the body is incomplete — fix the body, don't lean on the box.
- **At most one per concept.** One callout illuminating one idea. A page with
  three of them has turned the treat into the meal.
- **Max ~6 lines.** It's a glimpse, not a lecture. No proofs, no derivations,
  no chains of definitions.
- **Every symbol gets a plain-English gloss.** If you write "catamorphism" or
  "lens," say in the same breath what it means here. A term the reader can't
  decode is noise, not insight.

First user: [Where Should This Value Live?](where-state-lives.md), whose body
teaches the sub/flow/resource/machine decision in plain language and offers one
callout framing the four as storage-and-evaluation policies over one graph.

## No bead references in chapter prose

User-facing docs state the **current truth** of the framework, not the
historical trace of which decisions produced it. Do not introduce
`(rf2-xxx)` citations, `Per rf2-xxx ...` constructions, or
`as decided in rf2-xxx` style references into chapter text. Substantive
content goes inline; the bead history lives in the bead tracker.

Migration-rule ids (`M-NN`), spec section anchors (`#section-name`), and
cross-doc filename links are all fine — those are normative anchors, not
historical decision-trace references.
