# 11 — Server rendering

A compiled `defview` has two outputs today: direct React element construction in the
browser and versioned `re-frame.ui.tree` structural data on the JVM. The shared
conversion contract and parity gates hold those outputs together.

HTML is a separate S5 step: `re-frame.ssr/emit-ui-tree` serialises that structural tree.
Until S5 lands, `render-to-string` remains the frozen Reagent/hiccup compatibility
route; it is not the re-frame.ui tree pipeline.

| Mechanism | Delivery stage |
|---|---|
| Compiled JVM `defview` → versioned `re-frame.ui.tree` data | S1 — ships on main |
| Committed callbacks, `local`/effects, `client-only`, error boundaries, debug evidence | S3 |
| Presence fallback | S4 |
| `emit-ui-tree` → HTML, root manifests/fingerprints, hydration, `ssr-ring`, `render-static` | S5 |

## What renders on the server

| Full semantics | Honest fallbacks |
|---|---|
| Structure, props, branches, lists | `local` → initial value only *(S3)* |
| Subscriptions (pure snapshot path) | effects do not run *(S3)* |
| Event intent (vectors as data) | refs absent |
| `ui/html` | `client-only` → declared fallback *(S3)* |
| | presence → `:present` *(S4)* |
| | error boundaries do not catch — server failure policy *(S3)* |

Views that *are* their host behaviour (a canvas chart) wrap the leaf in `client-only`
and keep the shared markup outside.

## Rendering a page *(re-frame.ui HTML and `ssr-ring` land S5)*

The current compatibility `render-to-string` path is the frozen Reagent/hiccup route.
The S5 re-frame.ui path uses per-request frames: drain `:initial-events`, emit the
versioned structural tree, serialise it with `re-frame.ssr/emit-ui-tree`, respond, and
tear down. Request-to-response glue lands with the `ssr-ring` ecosystem adapter; this
page covers what the view layer itself owns.

## Roots and frames — two different things

A **root** is one React hydration unit (one DOM container). A **frame** is one
re-frame2 state world. A page can have several of each, and they mix freely:

```clojure
[:body
 [:div#shop-root]      ; root :page/shop   → view [shop-app],   frames :shop + :session
 [:div#assist-root]]   ; root :page/assist → view [assistant],  frames :assist + :session
```

*(Hydration contract below lands S5 with SSR roots; the ruled shape is fixed now.)*

Each root ships a small manifest (id, view, props, referenced frame payload ids,
fingerprint). **Frame payloads install idempotently** — if both roots reference
`:session`, whichever hydrates first installs it; the other finds it live. Roots
hydrate independently, in any order.

**Failure is scoped:** if `:page/shop`'s markup fingerprint mismatches, *that root*
fails loudly (client-fresh render, source-located dev diagnostics) — the assistant is
untouched. A bad frame payload affects exactly the roots that reference it. There is
no `suppressHydrationWarning`-style escape.

## Root identity and the host tier

Every root has a **root-id**. You usually write none: a single-root page derives it
from the mounted view's registered id — the [01](01-getting-started.md) counter path,
zero ceremony. Author identity the moment a page outgrows that:

```clojure
(ui/mount [ui/frame-root {:id :shop} [product-panel]] left-el
          {:root-id :panel/left})
(ui/mount [ui/frame-root {:id :shop} [product-panel]] right-el
          {:root-id :panel/right})
```

- The same view mounted twice with neither site authored is a **build error** with the
  fix in the message. Duplicates are also caught at runtime *before any render*.
- Identity opts (`:root-id`, `:disambiguator`, `:identifier-prefix`) are compile-time
  literals.
- The opts map also carries host error callbacks (`:on-uncaught-error`, …) — plain
  fns handed to the React root, outside the commit path.

Hosts needing more control than `mount` use the host tier directly:

```clojure
(def root (ui/create-root el {:root-id :page/shop}))
(ui/render! root [ui/frame-root {:id :shop :initial-events [[:shop/init]]}
                   [shop-app]])
(ui/unmount! root)
```

`mount` is exactly `create-root` + frame preflight + `render!`, one-shot and
idempotent per root — mounting again with the same id and container re-renders in
place (the hot-reload path from [01](01-getting-started.md)).

Hydration is the same tier *(lands S5)*:

```clojure
(ui/hydrate-root (js/document.getElementById "shop-root")
                 [ui/frame-root {:id :shop} [shop-app]])
```

**One rule with no exceptions:** a hydrating root takes its identity from the
server's manifest, never from client opts — passing `:root-id` or
`:identifier-prefix` to `hydrate-root` is an error. The server decided; the client
matches or fails loudly.

Root forms are literal at every entry point — `mount`, `render!`, `hydrate-root`,
`render-static`, and `ui.test/render` alike.

## Static output is a decision, not a guess *(lands S5)*

A root with no client capabilities *can* ship as inert HTML — no payload, no
hydration — but only when you say so:

```clojure
;; guide:no-fixture — illustrative fragment
(ui/render-static [site-footer {…}])   ; host declares it; compiler proves it
```

The compiler verifies the tree needs no client runtime and fails the build if you
have declared static something that is not. Nothing is silently stripped by
inference.

## Browser-only libraries

```clojure
(ui/client-only {:fallback [:div.map-shell "Map loads in the browser"]}
  [MapboxView {:center center}])
```

JVM renders the fallback; the hydrating client renders the *same* fallback (so
hydration matches), then one root-wide flip swaps every `client-only` site to its
client tree. Fallbacks must be plain markup — compiler-checked. A foreign component
on an SSR path without a fallback is a build error.

*(client-only lands S3; hydration phase flip S5.)*

## Event vectors on the server

Handlers-as-data pays off twice: the JVM tree retains event vectors as data, so
headless tests assert intent server-side; and Xray's static interaction surface reads
them without executing anything. On the client they become ordinary React handlers
*(committed client wiring lands S3)*.

## What you do not manage

You do not hand-maintain browser/JVM conversion parity: the versioned conversion
contract and parity gates guard it. At S5 the `emit-ui-tree` contract and serializer
parity gates guard the separate tree-to-HTML boundary and its contextual escaping
(except the explicit `ui/html` door). Manifests, payload scoping, and teardown then
ride the frame and root contracts you already use.
