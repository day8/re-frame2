# 5. Snapshot identity + QR sharing

You're three weeks into building the login form. Five variants are pinned to Chromatic: `:story.login/idle`, `/submitting`, `/error`, `/submitting-retry`, `/authenticated`. Two weeks of baselines. The design team has signed off on each one. The visual-regression service is happy.

Then a teammate looks at the names and pushes back. *Submitting-retry* is awkward; what's wrong with calling it `:story.login/retrying`? You agree; it's a five-second edit; you push. CI runs Chromatic against the new build. Forty-eight new buckets land in the dashboard, forty-eight old buckets get archived. The diff service shows every "new" screenshot as a 100%-novel image because the bucket key is the variant slug and the slug just changed. Two weeks of baselines, gone — not because the pixels changed, but because the *name* did.

This is the trade-off Storybook 9 ships and most playground tools after it: identity-by-slug. Rename anything and the diff service forgets. It's also the trade-off Story is built to avoid. Story's snapshot identity is **content-based** — a hash of what's rendered, not what it's called. Renames are free. The bucket key tracks the *picked state*, not the *name path*. Same screenshot, same hash, same bucket.

## What identity tracks

Every variant cell — the `(variant × mode × per-cell args)` tuple — has a **snapshot identity**: a content hash of everything that determines what the canvas will render. The hash is the join key visual-regression services use to bucket screenshots; it's how Story tells Chromatic, Argos, Percy, Lost Pixel, or your in-house diff tool "this screenshot is the same scenario as last week's — diff them."

So, concretely, on the five-state login form:

- Renaming `:story.login/submitting-retry` → `:story.login/retrying` doesn't change the identity. **Same bucket; baselines preserved.**
- Renaming a top-level `:Mode.login/dark` → `:Mode.login/midnight` doesn't change the identity either. **Same bucket; baselines preserved.**
- Changing the `:heading` arg from `"Sign in"` to `"Welcome back"` *does* change the identity. **New bucket; new baseline required.**

The contract: **identity tracks content; name tracks lineage**. Both are stable, separately.

The hash is a SHA-256 of a transit-printed tuple — `:resolved-args`, `:mode-args`, an `:events-fingerprint`, and a `:decorators-fingerprint`. Crucially, the *variant slug itself is not part of the input*. The fingerprint hashes what the variant produces, not what it's called. The hash is deterministic across machines, build tags, and load orders — the contract on `resolved-args` is sorted-keys, canonical types.

## A worked rename — `:submitting-retry` → `:retrying`

Walk through the rename on the login-form testbed.

**Before** (the variant body in `tools/story/testbeds/login_form/stories.cljs:175`):

```clojure
(story/reg-variant :story.login/submitting-retry
  {:doc    "The user corrected the typo and re-submitted."
   :events [[:login/flow [:login/submit {:email "ada@example.com" :password "wrong"}]]
            [:login/flow [:login/failure {:failure {:status 401}}]]
            [:login/flow [:login/retry  {:email "ada@example.com" :password "correct-horse"}]]]
   :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
   :play   [[:rf.assert/state-is :login/flow :submitting-retry]]
   :tags   #{:dev :docs :test}
   :substrates #{:reagent}})
```

The fingerprint Story computes for this variant under `:Mode.login/dark` (canonical, sorted, transit-printed) is roughly:

```clojure
{:resolved-args          {:heading "Sign in", :theme :dark}
 :mode-args              {:theme :dark}
 :events-fingerprint     "sha256:f3a1…"
 :decorators-fingerprint "sha256:9c40…"}
```

Notice the slug `:story.login/submitting-retry` is nowhere in that tuple. The SHA-256 of the whole thing is, say, `e2b7f4…a1`. That's the bucket key. Chromatic has two weeks of baselines pinned to `e2b7f4…a1`.

**After** the rename, the variant body is identical except for the slug:

```clojure
(story/reg-variant :story.login/retrying
  {:doc    "The user corrected the typo and re-submitted."
   :events [...]   ; unchanged
   :decorators [...]   ; unchanged
   :play   [[:rf.assert/state-is :login/flow :submitting-retry]]   ; <-- arguably should rename too
   :tags   #{:dev :docs :test}
   :substrates #{:reagent}})
```

Re-run the fingerprint computation on the new variant. The `:resolved-args` are the same `{:heading "Sign in", :theme :dark}`. The `:events-fingerprint` is the same `sha256:f3a1…` because the events sequence is identical. The `:decorators-fingerprint` is the same. The SHA-256 is *still* `e2b7f4…a1`.

Chromatic, Argos, Percy, Lost Pixel — every one of them keys on the hash. The bucket continuity holds. Your two weeks of baselines are intact.

(One nit: if your assertions still pin the *machine* state name `:submitting-retry`, you'd want to rename the machine state separately. The variant identity is decoupled from internal naming — but your tests aren't.)

## QR sharing — local-vendored encoder

Story's *share via QR* button renders the snapshot identity (plus the picked workspace + mode + cell-overrides) into a QR code, displayed inline. Scan with a phone; the phone opens a URL into your locally-served Story instance at that exact picked state.

![QR sharing — scan to open the picked variant state on another device](../images/story/05-qr-share.png)

The use case: design review against a real device. You've built the login form's `:error` variant; the design lead wants to see it on the actual phone they were thinking about, not the Chrome device emulator. Click the QR, scan with the phone, the phone opens the variant on your dev server — same picked state, same args, same mode, same workspace overrides. Two seconds vs the usual "let me get my laptop on the same WiFi as your phone."

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

A second trade: the agent self-healing loop relies on this. When an agent generates a new variant via the MCP write surface — say, prompted with "give me an `:error` variant where the server returns 503 instead of 401" — the bucket continuity for the *existing* `:story.login/error` snapshot is determined by the content hash, not the agent's chosen name. The agent can generate-then-name without worrying about colliding bucket keys, and a human can rename what the agent produced without breaking the diff service's history.

The pattern composes: humans rename freely, agents generate freely, the diff service stays anchored to what's actually rendered. That's the whole point.

Next: [time-travel in Story](06-time-travel.md).
