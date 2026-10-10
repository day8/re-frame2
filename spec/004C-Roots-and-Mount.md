# Spec 004C — Root identity and mount

> Status: v1-required. Root identity, the two rules a root keeps at mount, idempotent
> payload install across a page's roots, and fail-loud payload conflict. The identity
> model is exact — a **root** is one React DOM render/hydration unit, a **frame** is one
> re-frame2 state world, roots ↔ frames are many-to-many, and mount position is never
> identity.

> **What realises this contract.** `re-frame.ssr.install` keeps the payload-install
> ledger (§6, §7), and `re-frame.ssr/hydrate!` runs a hydrating root's preflight and
> seed in the order of §10. This Spec owns no mount verb: the client-root grammar is
> [006 §The client root](006-ReactiveSubstrate.md#the-client-root-adapter-owned-reusable),
> which every React view adapter publishes, `re-frame.fresco` included.

## 1. Root identity — required, host-authored, derivable

**Every root has a `root-id`, and it is the root's identity.** A hydrating root's
root-id is the `:root-id` its host passes (§3), and the payload-install ledger
attributes each claim to it (§6). A root-id is a qualified keyword (canonical:
`:page/shop`) or a vector of a qualified keyword plus scalar disambiguators — keyword,
string, or integer (`[:shop/product-panel :left]`).

## 3. The mount grammar and the host signature set

This Spec owns no mount verb. The client-root grammar — `client-root`, `render!`,
`unmount!` — is [006 §The client root](006-ReactiveSubstrate.md#the-client-root-adapter-owned-reusable),
and `re-frame.fresco` realises it as `h/client-root`, `h/render!` and `h/unmount!`:
the first `render!` through a handle creates its root, or hydrates one under
`{:hydrate? true}`. What this section owns is the two rules a root keeps about identity
and ordering.

- **A hydrating root hydrates as the server rendered it.** Its root-id is the
  `:root-id` its host passes to `re-frame.ssr/hydrate!` (or on its `hydrate-page!`
  entry), which records it as the payload's installer and names it in a conflict. Its
  `identifierPrefix` must be the one the server rendered under — React's empty prefix
  `""` when the server set none — or every `useId` resolves differently from the
  server's bytes. The host passes it as the `:identifier-prefix` `render!` opt, which
  Fresco's door and the Reagent, reagent-slim and UIx client roots all take.
- **Frame preflight runs before React.** A root door that ensures its frame does so
  before `createRoot`: ENSURE creates the frame if absent and drains its
  `:initial-events` synchronously, so the first paint is the seeded one, and a frame
  already live is joined as it stands — no re-seed, no config refresh.
  `re-frame.fresco.impl.mount/root!` keeps this order through `ensure-frame!`; Fresco's
  public door names no frame, because its tree does, on `h/frame-root` or
  `h/frame-provider`. A hydrating root's frame state arrives through
  `re-frame.ssr/hydrate!`, which seeds it before the host mounts (§10). What
  [`make-frame` construction](002-Frames.md#make-frame--atomic-create-and-register-and-the-canonical-config-grammar)
  and the synchronous, ordered `:initial-events` drain *do* is Spec 002's. The
  [`frame-root` two-pass contract](002-Frames.md#frame-root--the-ensure-component-cljs-reference)
  (empty first render → commit-phase `useLayoutEffect` ENSURE → populated second render)
  runs the *opposite* order and scopes a component subtree, not a host root.

## 6. Payload references and idempotent install

- A **payload id is a frame id**: the payload a root references is the one for the frame
  it hydrates into, so the two are one identifier, not two.
- Payload install is **idempotent and order-independent**: the first
  hydrating root referencing a payload installs it; later roots find it live and do
  not re-seed. Conflict is the exception, and it is fail-loud (§7). Install is the
  **state-boot** call's work, not the DOM-adoption call's: it is `re-frame.ssr/hydrate!`
  that reads the payload and installs it, and a view layer's hydrating root never does
  ([011 §Client-side hydration boot helper](011-SSR.md#client-side-hydration-boot-helper)).
  "The first hydrating root installs it" therefore describes the ORDER the two-call boot
  runs in across N roots on a page, not a step hidden inside the hydrating root.

## 7. Conflict detection — fail-loud

All ids below follow the one-catalogue `:rf.error/*` scheme; each carries a data map
naming both parties.

| Id | When |
|---|---|
| `:rf.error/frame-payload-conflict` | below |
| `:rf.ssr/hydration-mismatch` | server↔client render-tree fingerprint/digest disagreement at hydration (a Spec 009 catalogue row this Spec does not own) |

**Payload conflict — fail-loud at hydration preflight.** At a hydrating root's
preflight, before any install, a referenced **payload id already installed with a
different content digest** fails **that root** with `:rf.error/frame-payload-conflict`
(`re-frame.ssr.install/payload-install-decision!`). Its data names both parties — the
`:payload-id`, the `:installed` record (`:digest`, `:installed-by`), and the
`:arriving` `{:digest … :root-id …}` — with recovery
`:render-the-page-from-one-response`. The installed payload and the roots already using
it are untouched — failure scoping is precise: **a bad frame payload affects exactly the
roots referencing it.** There is no first-wins silent merge and no last-wins
overwrite; an **equal** digest is the idempotent no-op of §6.

## 10. Stage placement

| Surface | Stage |
|---|---|
| The hydrating-root boot sequence (payload frame-id check → install decision → hydrate) — `re-frame.ssr/hydrate!` validates the payload's `:rf/frame-id`, runs `re-frame.ssr.install/preflight!` (the install decision) and then `:rf/hydrate`, before the view layer's hydrating root adopts the DOM — and the payload-**content-digest** conflict preflight (`:rf.error/frame-payload-conflict`) | **S5** (stage roster per [EP-0030 §Stages S1–S7](../docs/EP/EP-0030-the-compiled-view-substrate-program.md#stages-s1s7)) |

## Q24–Q28 coverage

- **Q24** (render root-or-view forms; props/frames/registrations/overrides) → no Spec:
  there is no `ui.test/render`
  ([008 §The `ui.test` contract](008-Testing.md#the-uitest-contract--headless-testing-for-compiled-views)).
- **Q25** (where root-id is authored/derived; the full signature set) → §1, §3.
- **Q26–Q28** (a static root descriptor and its evolution, locator generation and
  duplicate detection across page fragments, frame-plan extraction) → no Spec: a root's
  identity is the `:root-id` its host passes (§1), its container is the DOM node the host
  hands `render!` (§3), and a payload reference is a frame id (§6).
