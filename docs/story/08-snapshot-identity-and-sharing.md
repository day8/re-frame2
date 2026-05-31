# 8. Snapshot identity and sharing

> **What you'll build.** An understanding of the content-hashed `snapshot-identity` that survives renames; how it keys visual-regression; and how to share a reproduction with honest *reproducibility* labelling — every shared artifact says whether the recipient can fully reproduce it. This chapter delivers the *share* face.

## The rename that doesn't break your diffs

Anyone who has run visual-regression testing against Storybook knows this specific pain. You have a pixel-diff baseline keyed by story name. You rename a story — `Button/Primary` becomes `Button/Default` because someone tidied the taxonomy — and your diff service decides that *every* baseline under that name is gone and everything is "new." A pure rename, zero pixels changed, and your CI lights up like a Christmas tree. Conversely, the genuine danger: a story keeps its name but its rendered output actually changed, and a name-keyed system that isn't paying attention can miss it.

Story sidesteps the whole problem by keying on a **content hash**, not a name. A rename is free — the hash didn't change, so the baseline carries over. A real change to the variant's content *does* change the hash, so it's caught. The identity tracks *what the variant is*, not *what it's called*.

## Snapshot identity — content hash, not name

Every variant — and every `variant × mode` cell — has a content-hashed `snapshot-identity`. Behind it sits a single primitive, `canonicalize`, which is the one operation responsible for an impressively long list of jobs: determinism checking, semantic diff, snapshot identity, `:plan-hash`, and `:run-hash`. One canonical projection, reused everywhere, so all of those agree by construction.

Two hashes matter, and they answer two different questions:

- **`:plan-hash`** — the identity of the *plan*. Computed over `[:story/id :world :script :expect :required-runner :tags]`. Answers "is this the same variant?" Two variants with the same plan hash are the same variant, whatever they're named.
- **`:run-hash`** — the identity of the *run's behaviour*. Computed over the behavioural run-slice (`:status`, final `:app-db`, the epoch tape, assertions, checks, effects, schema violations, warnings, sub-overrides, fidelity). Answers "did this variant *behave* the same way this time?"

One precision detail with real consequences: the hash computes over the variant's **real values**. That's what keeps visual-regression keying stable — the key is a content-address of the true content, so the same content always lands on the same key. And because the key *is* a hash, not the data, only the digest travels into a downstream baseline service — never the underlying state.

## Visual-regression keying

Story does not ship a pixel-diff engine, and it's important to be clear about that boundary. What Story ships is the **hook**: a stable `[variant-id content-hash]` key and stable iframes for capture. Downstream services — Chromatic, Percy, Argos, BackstopJS — key their baselines against that hash and skip unchanged variants in O(1) (same hash → same pixels → don't re-shoot). The pixel capture and the diff happen *in those services*; Story is the keying substrate, not the visual-regression service itself.

So the division of labour is: Story guarantees a stable, content-addressed key and an isolated render; your pixel-diff service of choice does the shooting and comparing. The expensive renames-break-everything problem is gone because the key was never the name.

## The determinism gate and semantic diff (briefly)

Two rigour features built on the same `canonicalize` primitive, kept light here:

- **Determinism gate** — run a variant twice; if it canonicalizes to a different `run-hash`, it's flagged non-deterministic. The headline: *the same artifact, run twice, must produce the same hash.* (A bare `[:wait ms]` is the classic determinism breaker, which is exactly why the gate refuses it — see [chapter 5](05-recorder-and-cannot-run.md#the-determinism-gate).)
- **Semantic diff** — when two runs *do* differ, the diff names *which* run-slice slot perturbed the verdict (the final db? an effect? a schema violation?), rather than handing you two giant blobs to compare by eye.

Both exist so that "this variant changed behaviour" is a precise, named statement, not a vibe.

## Sharing a reproduction — honest reproducibility

<!-- SCREENSHOT S11: the "Share & export" dialog open on a selected variant — the four egress commands (Share URL · Copy EDN · Screenshot · Static build), each carrying a reproducibility badge ("fully reproducible" / "partially reproducible" / "view-only"). For a partial/view-only cell the badge lists the reason (e.g. "the `:on-click` override pins a function value that cannot be serialised"). -->

Now the *share* face proper. You've got a variant in an interesting state and you want to hand it to a colleague. Open **Share & export** from the toolbar and Story gives you four ways to hand the cell off, each labelled with how reproducible it is:

- **Share URL** — the live address-bar URL. The browser's own URL *is* the share URL (`Cmd-L`, `Cmd-C`); the dialog is a one-click copy of it, not a second artifact. Paste it to land on the exact same cell.
- **Copy EDN** — a `(story/reg-variant …)` snippet of the cell's effective state, ready to paste into your stories namespace.
- **Screenshot** — a PNG of the variant canvas copied to the clipboard.
- **Static build** — the `npm run story:build` command that emits a self-contained static site carrying the registered variants as data.

The same share URL is available programmatically — pure data → data, encoding the active modes, cell-overrides, and substrate so the cell reproduces:

```clojure
(story/variant-share-url variant-id base-url opts)
;; -> a shareable URL encoding the variant + its active modes, overrides, and substrate
```

The honest part is **reproducibility labelling**, not redaction. A shared artifact may be only *partially* reproducible — and when something the reproduction needs cannot survive the round-trip, the artifact *says so*. No silent half-broken repros. Every command carries a badge with one of three statuses:

- **fully reproducible** — every input that drives the cell survives the URL / EDN round-trip; paste it and you land exactly here.
- **partially reproducible** — most state carries, but something is dropped or does not serialise (a cell-override value that is not readable EDN, a network stub that replies via a function, share-URL overrides that no longer apply after the variant's args were refactored). The recipient reproduces a degraded-but-usable approximation.
- **view-only** — the cell fundamentally cannot be replayed from the artifact (a function pinned as an arg value the view calls; a screenshot is *always* view-only — a static image is a view, not a replay). The recipient gets a view-only snapshot.

When the status is downgraded the badge lists *which* slot caused it — "the `:on-click` override pins a function value that cannot be serialised", "2 overrides from this URL no longer apply to the variant" — so the recipient reads exactly what makes the artifact less than fully replayable. This is *"can the recipient reproduce this?"*, never *"is this sensitive?"*.

!!! note "Why there is no redaction here"

    Sharing the URL, EDN, a screenshot, or a static build of **your own running
    app** is not a privacy concern: you, the local developer, already have
    programmatic access to your own state and secrets, so redacting the artifacts
    you emit of your own app would be futile. So these human-egress commands ship
    freely — enabled, not gated. The genuine egress-privacy boundaries live
    elsewhere, where state leaves *your* box for someone — or something — else: the
    AI / MCP surface an agent reads across ([chapter 9](09-multi-substrate-and-agent-loop.md))
    and the logs. The recorder's own boundary is covered in
    [chapter 5](05-recorder-and-cannot-run.md#redaction-at-the-recorder-boundary).

## Where we go next

The same plan that renders, tests, diagnoses, and shares can also flip *substrates* — render under Reagent, UIx, or Helix from one body — and be driven by an *agent* over MCP. That's the last two faces, and the chapter that walks back through all six. [Chapter 9](09-multi-substrate-and-agent-loop.md).
