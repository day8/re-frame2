# 08 — Server rendering

The same compiled views render on the JVM — no React on the server, no second serialiser.
One compiled template, two emitters, one conversion/escaping rule table: client and
server output are **structurally equivalent** (verified by fingerprint and a generative
parity suite), so they cannot drift apart.

*(Stage note: the JVM emitter, structural parity, root identity, and the client host
tier are Stage 1 — shipped, and Tier-1 tests ride them today; headless `sub` resolution
rides the S2 snapshot path, also shipped. The host-fallback rules below are the ruled
contract for features that land later — `local`, `effect`, `client-only`, and error
boundaries at S3; presence at S4. Hydration, root manifests, `render-static`, and the
`client-only` phase flip land S5 — SSR roots.)*

## What renders on the server

Structure, props, subscriptions (via the pure snapshot path), branches, lists, event
intent, and `ui/html` — full semantics. Host-bearing features have honest fallbacks,
each fixed now as the ruled contract and landing with its feature's stage: `local`
contributes its initial value and effects don't run *(both land S3)*; refs are absent;
`client-only` renders its declared fallback *(lands S3; `ui/portal` — wave-2 — will
follow the same rule if it ever ships)*; presence renders as `:present` *(lands S4)*;
and error boundaries don't catch here *(the component lands S3)* — a server-side throw
follows the server failure policy (project the error, or fail the response), because
catching and retrying is client recovery.
Views that *are* their host behavior (a canvas chart) wrap the leaf in `client-only` and
keep the shared markup outside.

## Rendering a page

Server lifecycle is standard re-frame2 SSR: per-request frames, drain `:initial-events`,
render (a tree walk — fast, no JS engines), respond, teardown.

## Roots and frames — two different things

A **root** is one React hydration unit (one DOM container). A **frame** is one re-frame2
state world. A page can have several of each, and they mix freely:

```clojure
[:body
 [:div#shop-root]      ; root :page/shop   → view [shop-app],   frames :shop + :session
 [:div#assist-root]]   ; root :page/assist → view [assistant],  frames :assist + :session
```

*(The rest of this section is the hydration contract — ruled now; it lands S5 with SSR
roots.)*

Each root ships a small manifest (its id, view, props, referenced frame payload ids,
fingerprint). **Frame payloads install idempotently** — here both roots reference
`:session`; whichever hydrates first installs it, the other finds it live. Roots hydrate
independently, in any order.

**Failure is scoped precisely:** if `:page/shop`'s markup fingerprint mismatches, *that
root* fails loudly (client-fresh render, source-located dev diagnostics) — the assistant
is untouched. A bad frame payload affects exactly the roots that reference it. There is
no `suppressHydrationWarning`-style escape; mismatches are errors with the differing
node's location.

## Root identity and the host tier

Every root has a **root-id**. You usually write none: a single-root page derives it
from the mounted view's registered id — the guide-01 counter path, zero ceremony. You
author identity the moment a page outgrows that:

```clojure
(ui/mount [ui/frame-root {:id :shop} [product-panel]] left-el
          {:root-id :panel/left})
(ui/mount [ui/frame-root {:id :shop} [product-panel]] right-el
          {:root-id :panel/right})
```

- The same view mounted twice with neither site authored is a **build error** with the
  fix in the message ("add `:disambiguator` or author `:root-id`"). Duplicates are also
  caught at runtime *before any render* — the already-live root is untouched.
- Identity opts (`:root-id`, `:disambiguator`, `:identifier-prefix`) are compile-time
  literals. `:identifier-prefix` seeds React's generated ids and defaults to
  `rf2-<root-id-slug>-` (`:page/shop` → `"rf2-page_Sshop-"` — the slug escapes the `/`
  as `_S`, so distinct root-ids can never share a prefix) — author it only when two
  independently built roots could collide.
- The opts map also carries the host error callbacks (`:on-uncaught-error`,
  `:on-caught-error`, `:on-recoverable-error`) — plain fns handed to the React root,
  invoked outside the commit path.

Hosts needing more control than `mount` use the host tier directly:

```clojure
(def root (ui/create-root el {:root-id :page/shop}))  ; identity fixed for the Root's lifetime
(ui/render! root [ui/frame-root {:id :shop :initial-events [[:shop/init]]}
                   [shop-app]])
(ui/unmount! root)                                    ; total teardown; the id frees
```

`mount` is exactly `create-root` + frame preflight + `render!`, one-shot and idempotent
per root — mounting again with the same id and container re-renders in place, which is
the hot-reload path from [01](01-getting-started.md).

Hydration is the same tier *(lands S5)*:

```clojure
(ui/hydrate-root (js/document.getElementById "shop-root")
                 [ui/frame-root {:id :shop} [shop-app]])
```

One rule with no exceptions: **a hydrating root takes its identity from the server's
manifest**, never from client opts — passing `:root-id` or `:identifier-prefix` to
`hydrate-root` is an error naming the conflicting key. The server decided; the client
matches or fails loudly.

Root forms are literal at every entry point — `mount`, `render!`, `hydrate-root`,
`render-static`, and `ui.test/render` alike; a runtime-assembled root vector is the
same compile error everywhere.

## Static output is a decision, not a guess *(lands S5)*

A root with no client capabilities *can* ship as inert HTML — no payload, no hydration —
but only when you say so:

```clojure
;; guide:no-fixture — illustrative fragment
(ui/render-static [site-footer {…}])   ; host declares it; compiler proves it
```

The compiler verifies the tree needs no client runtime (no subs, handlers, leases,
local, effects, refs, context, custom-element properties, portals, boundaries, presence,
client-only, foreign components) and fails the build if you've declared static something
that isn't. Nothing is silently stripped by inference.

## Browser-only libraries *(client-only lands S3; its hydration phase flip S5)*

```clojure
(ui/client-only {:fallback [:div.map-shell "Map loads in the browser"]}
  [MapboxView {:center center}])
```

JVM renders the fallback; the hydrating client renders the *same* fallback (so hydration
matches), then one root-wide flip swaps every `client-only` site to its client tree in a
single update. Fallbacks must be plain markup — compiler-checked. A foreign component on
an SSR path without a fallback is a build error.

## Event vectors on the server

Handlers-as-data pays off here twice: the JVM tree retains event vectors as data, so
headless tests assert intent server-side; and Xray's static interaction surface reads
them without executing anything. On the client they become ordinary React handlers
*(the committed client wiring lands S3)*.
(Pre-hydration interaction capture — queueing clicks before the bundle arrives — is a
research track, not a shipped feature; the data property that would enable it is already
here.)

## What you don't manage

No dual-emitter drift, no escaping decisions (everything escapes for its context except
the explicit `ui/html` door), no per-root wiring beyond the mount — manifests, payload
scoping, and teardown ride the frame and root contracts you already use.
