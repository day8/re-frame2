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
| `spec/MIGRATION.md` | v1 → v2 migrants are a distinct audience; ch.18 is their on-ramp |
| `spec/Pattern-AsyncEffect.md` | Runnable convention; cross-cutting across ch.04 / ch.08 / ch.16 |
| `spec/Pattern-RemoteData.md` | Runnable convention; cross-cutting across ch.02 / ch.09 / ch.10 |
| `spec/Pattern-Forms.md` | Runnable convention; the 7-event lifecycle is reused |

**Chapter 20 (Where to go next) is exempt** from this restriction. It IS the
spec portal — curated lists of every spec doc the curious reader can dive into
are appropriate there.

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

This policy was applied retrospectively via beads rf2-4470, rf2-09ew3,
rf2-6e6yu, rf2-zfqgl, rf2-oi6mw, rf2-jdzjf, rf2-7wkwl, rf2-uvuyd (the 8
chapter-surgery beads from audit rf2-7uz9). Future chapter authors who skip
this policy will reintroduce the same drift.
