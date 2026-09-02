# Hicasso server policy, per public surface

Every public surface carries a permanent inventory id (`HS-nn`, minted once, never
reused or renumbered) and one of two server policies:

- **Render** — deterministic React server output from an immutable request
  snapshot, with matching hydration.
- **Client-only** — refuse server use at the declaration source. A declared
  `:fallback` stands in the bytes, otherwise nothing, and the live surface arrives
  on the client after adoption.

A row moves from Client-only to Render only on a witness proving, for that surface:
deterministic bytes from an immutable snapshot, matching hydration, a deliberate
mismatch attributed to source with its recovery, two simultaneous hydrating roots
under stable and distinct `identifierPrefix`es, and exact cleanup on unmount; a row
that reads or demands resources also proves no duplicate acquisition. A row whose
witness later fails returns to Client-only in the same edit. Surfaces that are not
nodes of the rendered tree — lifecycle commands, registrations, developer products —
carry no policy and say so.

This is the table and nothing gates it. The reasoning, every witness and every dated
amendment behind each cell is design history at
[`docs/design/hicasso/product/dispositions.md`](../../../docs/design/hicasso/product/dispositions.md)
§2 (demoted 2026-08-30, `rf2-6c12m.8`), and the two-policy matrix itself is
[`lanes/react-compatibility-notes.md`](../../../docs/design/hicasso/product/lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix).

| Id | Surface | Server policy |
|---|---|---|
| HS-01 | `h/defview` boundary | Render |
| HS-02 | `h/sub` read during a server render | Render |
| HS-03 | Literal intent vectors and `h/event` | Render — witnessed by absence: no event attribute or reserved keyword reaches the bytes |
| HS-04 | Intrinsic element head, including SVG and custom elements | Render |
| HS-05 | Fragment head | Render |
| HS-06 | Props map and canonical slot naming | Render |
| HS-07 | Reserved data vocabulary: `::h/value`, `::h/checked`, `::h/prevent`, `::h/revision` | Render — the `::h/revision`-on-an-uncontrolled-field refusal fires during the server render too |
| HS-08 | Controlled DOM fields as a class | Render for the server half; per-control support is a client axis |
| HS-09 | `h/error-boundary` | Render on the succeeding arm; a throwing child is not caught server-side and reaches the caller's `renderToString` |
| HS-10 | `h/mount!` | No policy — a client lifecycle command, not a node of the tree |
| HS-11 | `h/hydrate!` | Client-only — the adoption half of every Render row; adopts `re-frame.hicasso.server/render`'s bytes under a matching `:identifier-prefix` (`server-render-ssr-dom-cljs-test` §4, §4b) |
| HS-12 | `h/render!` | No policy — a lifecycle command |
| HS-13 | `h/unmount!` | No policy — a lifecycle command; idempotent, silent on a rootless handle |
| HS-14 | Root and frame-provider element, including `identifierPrefix` | Render on bytes from `re-frame.hicasso.server/render` — `server-render-ssr-dom-cljs-test` §1, §2, §4, §4b and `identifier-prefix-ssr-dom-cljs-test`; hand-rolled `renderToString` bytes are outside the claim (§4b-3) |
| HS-15 | Attribute-merge helper | Render — no separate public spelling |
| HS-16 | `h/defhost` declaration | Render on `:server :render`; Client-only by default, with an optional `:fallback` |
| HS-17 | Declared ReactNode positions and named `:slots` | Client-only — named positions unwitnessed on the server |
| HS-18 | Render-prop callback lowered through `h/as-element` | Client-only — unmeasured on the server |
| HS-19 | Raw React element head (`[:>]`) | Client-only — carries no declaration, so the enclosing boundary decides; renders nothing server-side |
| HS-20 | Portal helper | Client-only — the portalled subtree is absent from the bytes; a `:fallback` stands at the position |
| HS-21 | Outward bridge: a Hicasso view under a native React parent | Client-only — mismatch attribution is scoped to roots the package itself adopts (Spec 011) |
| HS-22 | `React.lazy` bridge and Hiccup-aware Suspense host | Client-only — a bare lazy head writes nothing and never calls its loader; a declared fallback writes the skeleton |
| HS-23 | Activity-hosted subtree | Client-only at the declaration; through a raw React element there is no Hicasso policy and React's own server semantics govern |
| HS-24 | ~~native intrinsic element form~~ | Struck 2026-08-29 (`rf2-6c12m.3`) — the native grammar is deleted |
| HS-25 | ~~native component-headed element form~~ | Struck 2026-08-29 (`rf2-6c12m.3`) |
| HS-26 | ~~native dynamic-props marker~~ | Struck 2026-08-29 (`rf2-6c12m.3`) |
| HS-27 | ~~native component declaration door~~ | Struck 2026-08-29 (`rf2-6c12m.3`) — an island is declared through `h/defhost` |
| HS-28 | `n/use-sub` | Render on the host's declaration |
| HS-29 | `n/use-frame` | Render on the host's declaration |
| HS-30 | ~~native ABI helpers: memo, lazy, ref, both embedding directions~~ | Struck 2026-08-29 (`rf2-6c12m.3`) — `React.memo` and `React.lazy` are used directly |
| HS-31 | Optional forms module | Client-only — nothing refuses; every door beneath it is Render, and the five-clause upgrade was never taken |
| HS-32 | Optional overlay module (popover and modal) | Client-only — the panel markup is in the bytes; the top-layer entry is a client ref callback |
| HS-33 | Optional motion and presence module | Neither policy holds today — the server emits `:mounting` children and hydration discards the adoption; the Render repair is a server-scoped adoption window |
| HS-34 | Optional routing-integration module | Client-only — no module exists to refuse in |
| HS-35 | Committed-read resource-demand boundary | No surface — the graduating verdict was STOP |
| HS-36 | Supported test namespace | Development-only; absent from production and server bundles |
| HS-37 | Xray and Pair evidence projection | Development-only; no production sentinels |
| HS-38 | clj-kondo exports and optional dev schemas | Development-only; no production cost |
| HS-39 | Bounded Node/React SSR service | A deployable service, not a view surface; its contract is separate from every row above |
| HS-40 | `h/route-link` | Render — the declined-`:prefetch` refusal fires during the server render |
| HS-42 | `h/reg-state` | No policy — a load-time registration; its reads are HS-02's |
| HS-43 | ~~`h/hframe`~~ | **Retired 2026-08-30 (`rf2-t32wg` ruling, executed as `rf2-6c12m.13`)** — the verb is deleted with no alias; the frame doors are core's `rf/current-frame-id` and zero-arity `rf/capture-frame`, legal inside a body. The policy the row carried holds for those doors unchanged: no policy — an ambient frame-id read; rendering the id into markup makes the document non-deterministic |
