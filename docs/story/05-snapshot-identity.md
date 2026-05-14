# 5. Snapshot identity + QR sharing

Every variant cell — the `(variant × mode × per-cell args)` tuple — has a **snapshot identity**: a content hash of everything that determines what the canvas will render. The hash is the join key visual-regression services use to bucket screenshots; it's how Story tells Chromatic, Argos, Percy, Lost Pixel, or your in-house diff tool "this screenshot is the same scenario as last week's, even though `:label` is different — diff them."

## Why content-hash rather than slug

A naive identity would be `:story.counter/loaded@dark`. That works until you tweak `:label "Total"` → `"Count"` and the visual-regression service flags the change as a delta of an entirely new variant. Content-hashing the resolved-args fingerprint makes the identity track the *picked state*, not the *name path*.

So:

- Renaming `:story.counter/loaded` → `:story.counter/with-load` doesn't change the identity.
- Renaming a top-level `:Mode.app/dark` → `:Mode.dark` doesn't change the identity.
- Changing `:label "Count"` → `"Total"` *does* change the identity (because the rendered content does).

The contract: **identity tracks content; name tracks lineage**. Both are stable, separately.

The hash is a SHA-256 of a transit-printed `{:variant-id :resolved-args :mode-args :decorators-fingerprint}` tuple. The hash is deterministic across machines, build tags, and load orders — the contract on `resolved-args` is sorted-keys, canonical types.

## QR sharing — local-vendored encoder

Story's *share via QR* button renders the snapshot-identity (plus the picked workspace + mode + cell-overrides) into a QR code, displayed inline. Scan with a phone; the phone opens a URL into your locally-served Story instance at that exact picked state.

The QR encoder is **vendored locally** (per [rf2-20w5i](https://github.com/day8/re-frame2)). No CDN hit, no external dependency at render time. The vendored encoder is ~3kB after `:advanced` (DCE handles dead modes); production builds short-circuit before the encoder code is reachable.

Two affordances on the QR:

- *Copy as image* — for embedding in design docs.
- *Copy as URL* — same content, text-shaped.

## Snapshot artefacts in the static build

Story's `story-static` artefact (built via `npm run story:build` from `implementation/`) materialises one HTML page per `(variant × mode)` cell, named by snapshot identity. Visual-regression services consume the static build directly — each PNG comes with a stable identity, and diffs are by identity.

A few snapshot-identity hygiene rules:

- **Don't read non-deterministic values during render.** `(js/Date.)`, `(rand)`, `(.now js/performance)` — any of these inside a view body inflates the identity for the same picked args. Push them out (cofx, decorator-driven mocks). Story will emit a warning trace for non-determinism if it can detect it.
- **`:large?`-tagged slots are elided from the fingerprint.** A 2 MB image payload in `:cart/preview-image` doesn't change the identity if only its byte content changes. Useful for variants that exercise large-payload behaviours without dirtying every diff.
- **`:sensitive?`-tagged values participate in the fingerprint as a hash of the value, not the value itself.** Story honours the privacy contract — secrets don't leak into snapshot identities the diff service receives.

## Why this is unusual

Storybook's identity is path-based — slugs, story titles, mode names. Visual-regression buckets follow the slug. Rename anything and you've broken bucket continuity.

Story's identity is *content-based*. Stable identity follows the content. Renames are free. The trade is that an args-tweak is a new identity — which is the point: you want the diff service to flag that the picked state changed.

A second trade: the agent self-healing loop relies on this. When an agent registers a new variant body via MCP, the bucket continuity for an existing snapshot is determined by the content hash, not the agent's chosen name. So an agent can generate-then-name without worrying about colliding bucket keys.

Next: [time-travel in Story](06-time-travel.md).
