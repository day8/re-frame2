# 8. Snapshot identity and sharing

> **What you'll build.** An understanding of the content-hashed `snapshot-identity` that survives renames; how it keys visual-regression; and how to share a reproduction safely, with honest redaction. This chapter delivers the *share* face.

## The rename that doesn't break your diffs

Anyone who has run visual-regression testing against Storybook knows this specific pain. You have a pixel-diff baseline keyed by story name. You rename a story — `Button/Primary` becomes `Button/Default` because someone tidied the taxonomy — and your diff service decides that *every* baseline under that name is gone and everything is "new." A pure rename, zero pixels changed, and your CI lights up like a Christmas tree. Conversely, the genuine danger: a story keeps its name but its rendered output actually changed, and a name-keyed system that isn't paying attention can miss it.

Story sidesteps the whole problem by keying on a **content hash**, not a name. A rename is free — the hash didn't change, so the baseline carries over. A real change to the variant's content *does* change the hash, so it's caught. The identity tracks *what the variant is*, not *what it's called*.

## Snapshot identity — content hash, not name

Every variant — and every `variant × mode` cell — has a content-hashed `snapshot-identity`. Behind it sits a single primitive, `canonicalize`, which is the one operation responsible for an impressively long list of jobs: determinism checking, semantic diff, snapshot identity, `:plan-hash`, and `:run-hash`. One canonical projection, reused everywhere, so all of those agree by construction.

Two hashes matter, and they answer two different questions:

- **`:plan-hash`** — the identity of the *plan*. Computed over `[:story/id :world :script :expect :required-runner :tags]`. Answers "is this the same variant?" Two variants with the same plan hash are the same variant, whatever they're named.
- **`:run-hash`** — the identity of the *run's behaviour*. Computed over the behavioural run-slice (`:status`, final `:app-db`, the epoch tape, assertions, checks, effects, schema violations, warnings, sub-overrides, fidelity). Answers "did this variant *behave* the same way this time?"

One precision detail with real consequences: the hash computes over the **real values, before any redaction sentinel is substituted.** That's what keeps visual-regression keying stable even for variants carrying sensitive data — the key is computed from the true content. The hash itself never leaves the process unredacted; only the *key* (a hash, not the data) travels.

## Visual-regression keying

Story does not ship a pixel-diff engine, and it's important to be clear about that boundary. What Story ships is the **hook**: a stable `[variant-id content-hash]` key and stable iframes for capture. Downstream services — Chromatic, Percy, Argos, BackstopJS — key their baselines against that hash and skip unchanged variants in O(1) (same hash → same pixels → don't re-shoot). The pixel capture and the diff happen *in those services*; Story is the keying substrate, not the visual-regression service itself.

So the division of labour is: Story guarantees a stable, content-addressed key and an isolated render; your pixel-diff service of choice does the shooting and comparing. The expensive renames-break-everything problem is gone because the key was never the name.

## The determinism gate and semantic diff (briefly)

Two rigour features built on the same `canonicalize` primitive, kept light here:

- **Determinism gate** — run a variant twice; if it canonicalizes to a different `run-hash`, it's flagged non-deterministic. The headline: *the same artifact, run twice, must produce the same hash.* (A bare `[:wait ms]` is the classic determinism breaker, which is exactly why the gate refuses it — see [chapter 5](05-recorder-and-cannot-run.md#the-determinism-gate).)
- **Semantic diff** — when two runs *do* differ, the diff names *which* run-slice slot perturbed the verdict (the final db? an effect? a schema violation?), rather than handing you two giant blobs to compare by eye.

Both exist so that "this variant changed behaviour" is a precise, named statement, not a vibe.

## Sharing a reproduction — honest redaction

<!-- SCREENSHOT S11: a share/copy affordance on a selected variant, with a visible redaction warning ("partial reproduction: 1 sensitive value removed") so the recipient knows the repro is incomplete. NOTE (floor-state): share/export redaction across all egress is BLOCKED on a common egress seam (018 §6); the copy-share-URL command is gated on it. Confirm the affordance renders before leaning this shot on it. -->

Now the *share* face proper. You've got a variant in an interesting state and you want to hand it to a colleague. Story lets you share a selected state as a URL, an inline plan, or a run artifact:

```clojure
(story/variant-share-url variant-id base-url opts)
;; -> a shareable URL encoding the selected variant + state
```

But sharing application state is exactly where privacy bites, so the rule (the spec's T4 tension) is firm: **redaction must be visible and must explain what was removed.** A shared artifact may be only *partially* reproducible — and when redaction has stripped data the reproduction needs, the artifact must *say so*. No silent half-broken repros.

There are two independent gates, and conflating them is a mistake:

- **`show-sensitive?`** — the *on-box* toggle: whether the local devtool reveals a sensitive value to *you*, sitting at your own machine.
- **`include-sensitive?`** — the *off-box* egress gate: whether a sensitive value is allowed to leave the process at all in a shared/exported artifact.

They're separate because "I can see my own secrets locally" and "this secret is allowed to cross the wire" are genuinely different decisions. A `:sensitive?`-marked slot shares as `:rf/redacted`, with the redaction *visible* in the shared artifact — the recipient sees that something was removed and that the repro is partial, rather than receiving a quietly-incomplete artifact that fails mysteriously.

This is the same redaction machinery the recorder used ([chapter 5](05-recorder-and-cannot-run.md#redaction-at-the-recorder-boundary)) and the same boundary an agent reads across ([chapter 9](09-multi-substrate-and-agent-loop.md)) — one egress posture, applied consistently to every face that lets state leave the box.

!!! note "Floor-state, honestly"

    Per the north-star spec ([`018`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/018-Story-UI-North-Star.md)
    §6), *share/export redaction across all egress* is blocked on a common egress
    seam beyond epoch redaction, and the share-URL copy command is gated on it. The
    `snapshot-identity` content-hash and the `canonicalize` primitive ship today;
    the full safe-sharing affordance is converging. Treat the share-URL flow here as
    the target the redaction posture is building toward — the keying and the
    redaction *contract* are real now; the polished egress UI is landing.

## Where we go next

The same plan that renders, tests, diagnoses, and shares can also flip *substrates* — render under Reagent, UIx, or Helix from one body — and be driven by an *agent* over MCP. That's the last two faces, and the chapter that walks back through all six. [Chapter 9](09-multi-substrate-and-agent-loop.md).
