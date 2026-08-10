# SSR and hydration

You want real HTML in the first response, and you want the same app to take
over in the browser. You do not want to write the UI twice, and you do not
want a second HTML emitter that drifts from what the client renders.
[Hicasso](glossary.md#hicasso)'s answer is a contract, not a mode. Every public surface either
renders deterministic React server bytes or refuses at its source with a
recovery. The browser adopts the server's DOM; it does not repaint over it.

> **One renderer runs in two places, and React's own adoption judges the
> result.**

## Every surface has a server answer

Semantics live in React, so the server renders with React: `react-dom/server`
under Node, which walks the same components the browser mounts. There is no
parallel string emitter and no JVM twin of the view layer. With one
renderer, hydration parity holds by construction. Parity that is only a
claim admits a whole class of bug.

One renderer serves every surface. Two policies govern each surface:

- **Render** — the surface produces deterministic React server bytes from an
  immutable request snapshot. Hydration adopts those bytes.
- **Client-only** — the surface refuses server use at its declaration and
  names its recovery: a deterministic fallback in the server bytes, then the
  live component in the browser after adoption completes. A silent `nil`
  return is not a policy.

| Surface | Policy |
|---|---|
| Hiccup elements, fragments, text | Render |
| [`h/defview`](glossary.md#defview) bodies and [`h/sub`](glossary.md#hsub) reads | Render — reads probe the request snapshot |
| Controlled fields | Render — value/checked attributes come from the model |
| [`h/error-boundary`](glossary.md#error-boundary) | Render — a throw during a server render takes React's server error channel, not the [boundary](glossary.md#boundary)'s fallback |
| Roots and the [outward bridge](glossary.md#outward-bridge) | Render — request-isolated, prefix-matched |
| [`h/defhost`](glossary.md#defhost), [ReactNode slots](glossary.md#reactnode-slot), render props, [`h/as-element`](glossary.md#as-element) | Client-only until the declaration selects Render |
| Portals, raw React elements, opaque foreign components | Client-only — no hydration claim is made for bytes that were never sent |
| Intrinsic [`n/$`](glossary.md#n-dollar) forms | Render |
| [`n/defcomponent`](glossary.md#ndefcomponent) and component-headed [`n/$`](glossary.md#n-dollar) | Client-only until the declaration selects Render |
| Resource and demand boundaries | Client-only until their module contract selects Render |

Two consequences deserve a plain statement. First, a server render runs no
effects. Every [`h/sub`](glossary.md#hsub) read is a cold probe against one coherent snapshot:
no subscription registered, no commit, no disposal owed. A request
therefore leaves nothing behind, by design. Second, determinism is the part
you own. Same bundle, same snapshot, same bytes — that is the Render
obligation, and it holds exactly when bodies are functions of their props
and reads. A clock, a `js/window` check, or a random value in a body paints
one thing on the server and another in the browser, and React reports the
divergence as a hydration mismatch. Platform work belongs in effects:
`:platforms #{:client}` handlers are skipped server-side. At host edges,
the rule is the same purity that [Views and reads](02-views-and-reads.md)
already demands. Events as data help at no cost: `[:article/delete id]` on
`:on-click` has no closure to ship, and each side builds the callback from
the vector.

## Serve a page from a snapshot

The example page: a feed read from app-db, plus a foreign chart that works
only in a browser.

```clojure
(ns app.views
  (:require [re-frame.hicasso :as h]
            ["trend-charts" :refer [TrendChart]]))

(h/defhost trend-chart TrendChart
  {:server   :client-only
   :fallback [:div {:class "chart chart--pending"} "Chart loads in the browser"]})

(h/defview article-row [{:keys [id]}]
  [:li {:class "article"}
   [:a {:href (h/sub [:article/url id])} (h/sub [:article/title id])]])

(h/defview page [_]
  [:main
   [:h1 (h/sub [:feed/heading])]
   [:ul (for [id (h/sub [:feed/article-ids])]
          [article-row {:id id :key id}])]
   [trend-chart {:points (clj->js (h/sub [:feed/trend]))}]])
```

Nothing here is SSR-specific. That is the point: idiomatic [Hicasso](glossary.md#hicasso) is
already the app that serves.

The server side is the optional server module: the deployable Node
service's render entry. One request goes in; one document comes out:

```clojure
(ns app.server
  (:require [re-frame.hicasso.server :as server]
            [app.views :as views]
            [app.subs]
            [app.events]))

(defn page-response
  "Render one request from a db snapshot."
  [db-snapshot]
  (:document
   (server/render
    {:hiccup            [views/page {}]
     :snapshot          db-snapshot            ;; seeded whole via :rf/set-db
     :payload           [:feed :session]       ;; allowlist — what rides the wire
     :client-frame-id   :app/main              ;; the stable wire frame id
     :identifier-prefix "main"                 ;; must match the hydrating root
     :app-element-id    "app"
     :script-src        "/js/app.js"
     :title             "The feed"})))
```

`server/render` is the whole request lifecycle. It runs five steps in a
fixed order:

1. It creates a fresh frame under a private id. Two concurrent requests
   cannot read each other's app-db.
2. It seeds state through the framework's own doors: the snapshot via
   `:rf/set-db`, then any `:initial-events` (a resolved route, a completed
   fetch) in order.
3. It renders the view to HTML with `react-dom/server` — the same element
   tree the browser mounts, with host policies honoured by construction.
4. It builds the hydration payload from the allowlisted app-db slice and
   embeds it as the page's `__rf_payload` script.
5. It destroys the frame in a `finally`, so a render that throws leaks no
   more than a render that returns.

The `:payload` policy is fail-closed. It is a non-empty vector of top-level
app-db keys, or the explicit `:rf.ssr.payload/whole-app-db`. If you omit
it, the service throws `:rf.error/ssr-missing-payload-policy` at boot. It
does not ship your server-only keys at first request, and it does not
silently drop the keys your views read. Allowlist every key the page reads.
A key the server rendered from, but did not ship, hydrates the client
against different state — and the mismatch complaints that follow are real.

Hand `:document` to the HTTP host you run: the service's own runner, an
Express route, a lambda. Two renders from the same snapshot produce
byte-identical documents. The service checks exactly that in its own tests,
and your tests can too.

## Hydrate it

Hydration is two steps in a fixed order: state first, adoption second. The
first client render must run against the server's state. Otherwise the two
trees disagree before React compares a single node.

```clojure
(ns app.client
  (:require [re-frame.ssr :as ssr]
            [re-frame.hicasso :as h]
            [app.views :as views]
            [app.subs]
            [app.events]))

(defn ^:export run []
  ;; 1. State: read __rf_payload, dispatch :rf/hydrate, replace frame state.
  (ssr/hydrate! {:frame :app/main})
  ;; 2. Adoption: keep the server's DOM, attach listeners, report divergence.
  (h/hydrate! (js/document.getElementById "app")
              {:frame             :app/main
               :identifier-prefix "main"}
              [views/page {}]))
```

!!! note "Two [`hydrate!`](glossary.md#hydrate)s, two halves"
    `ssr/hydrate!` is the framework's state half. It reads the payload,
    dispatches `:rf/hydrate` against the target frame — the server's slice
    replaces the client's — and validates the wire frame id against
    `:frame`. A conflict raises `:rf.error/hydration-frame-id-mismatch`; an
    absent `:frame` raises `:rf.error/no-frame-context`. [`h/hydrate!`](glossary.md#hydrate) is
    the DOM half: React's `hydrateRoot` on a container that already holds
    server bytes. The two calls share a verb because they are two halves of
    one adoption — and the order between them is fixed.

[`h/hydrate!`](glossary.md#hydrate) stands beside [`h/mount!`](glossary.md#mount) ([Getting started](01-getting-started.md)):
the same association of a container, a frame, and one view, and the same
idempotent handle for [`h/unmount!`](glossary.md#mount). These properties matter when you write
apps:

- **Adoption is root-scoped.** The handle owns its container, its
  `:identifier-prefix`, and its error reporting. Nothing about hydration is
  process-global. That is what makes the two-root example below work.
- **It returns before adoption finishes.** React hydrates without
  `flushSync`. Do not assume that the DOM on the next line is client-owned.
- **`:identifier-prefix` must match the server render's.** React's `useId`
  writes the prefix into every generated id in the bytes. The hydrating
  root verifies them, and a prefix disagreement flags every generated id at
  once.
- **Controlled text is never rewritten afterwards.** The model's value is
  in the server bytes, and it is the one truth from the first paint
  ([Controlled inputs](04-controlled-inputs.md)).
- **Presence children are born present.** Nothing that arrived with the
  page replays an entrance animation over content the user already reads.

`ssr/hydrate!` returns the applied payload, or `nil` when the page carries
none. The same boot therefore serves a client-only load: on `nil`, fall
through to [`h/mount!`](glossary.md#mount) with your ordinary `:initial-events` seed.

## Two roots, two verdicts

A page may carry several server-rendered roots — for example, an app and a
help panel. Each root adopts and complains independently:

```clojure
(h/defview help-panel [_]                        ;; in app.views
  [:aside
   [:h2 "Need a hand?"]
   ;; Don't — deliberate divergence, kept to show the complaint:
   [:p "Generated at " (js/Date.now)]])

(defn ^:export run []
  (ssr/hydrate! {:frame :app/main})            ;; one payload seeds the frame once
  (h/hydrate! (js/document.getElementById "app")
              {:frame :app/main :identifier-prefix "main"}
              [views/page {}])
  (h/hydrate! (js/document.getElementById "help")
              {:frame :app/main :identifier-prefix "help"}
              [views/help-panel {}]))
```

The server rendered one timestamp into the help panel; the client's first
pass computes another. React keeps the client's version, repairs the DOM,
and reports what it recovered from. The report surfaces on the diagnostic
bus as:

```clojure
{:id    :rf.ssr/hydration-mismatch
 :root  "help"                    ;; the root that complained, by prefix/container
 :where app.views/help-panel      ;; view identity and source
 :error recoverable-error}        ;; React's report, component stack included
```

The `#app` root hydrated clean; its stream is empty. That per-root
attribution is the contract. Each [`h/hydrate!`](glossary.md#hydrate) wires its own recoverable,
caught, and uncaught error channels, so one faulty root cannot contaminate
the other's verdict. Xray's hydration lane shows the complaints joined to
view source and host policy ([Diagnostics](15-diagnostics.md)). The fix
here is the usual one: a value that both sides must agree on belongs in
state — written by an event, rendered from the snapshot.

Be clear about the limits of the check. React reports text divergence and
missing, extra, or wrong-type elements. It does not report an
attribute-only divergence: a stale `class` on an element whose tag and text
still match receives a development warning, not a production callback.
Recovery is replacement, not patching. The page ends up correct, and the
disagreement ends up reported.

??? info "Coming from Reagent: where is the structural hash?"
    The Reagent-tier adapters hash their hiccup tree on the server and
    re-hash the client's first render. Views there are pure functions that
    return a data tree, so there is a tree to hash. [Hicasso](glossary.md#hicasso) views reach
    React as elements, and React walks the tree internally, so no data tree
    exists to hash and no hash rides the payload. Verification on this tier
    is React's own adoption, reported per root. An absent hash cannot lie;
    a degenerate hash could.

## Render and Client-only, at the host

Every [`h/defhost`](glossary.md#defhost) declaration carries its [server policy](glossary.md#server-policy). One declaration
covers the server render, hydration's first client pass, and a fresh
client-only mount ([Interop](09-interop.md)):

```clojure
(h/defhost stock-widget StockWidget)               ;; Client-only — the default
(h/defhost trend-chart TrendChart                  ;; the same, with declared markup
  {:server   :client-only
   :fallback [:div {:class "chart chart--pending"} "Chart loads in the browser"]})

(def theme-context (react/createContext "light"))  ;; (:require ["react" :as react])
(h/defhost theme-provider (.-Provider theme-context)
  {:server :render})                               ;; runs on the server, children included
```

**Client-only** is the conservative default, and the choice is deliberate:
a foreign React component is exactly the node whose render may reach for
`window`, and the declaration cannot know. On the server, the crossing
renders its declared fallback, or nothing. In the browser, the live
component mounts after adoption completes. A fresh client mount renders the
component immediately, with no fallback flash. The fallback must be
deterministic, inert markup. It is walked once at the declaration, and it
lands in the server bytes *and* in hydration's first client pass; those two
must agree by construction. The runtime enforces this. A [`defview`](glossary.md#defview) or
[`defhost`](glossary.md#defhost) head written into a fallback is an element whose body runs later
— differently per frame and per write — and the declaration refuses it
with `:rf.error/hicasso-host-fallback-boundary-head`.

**Render is an assertion**: this component is safe to run on a server.
Write it, and the component is the element's own type. Server render, first
client pass, and fresh mount are then one tree with the same props,
context, and children. There is no swap at adoption, and no remount of the
subtree you just hydrated. Render is also the only policy under which a
crossing's **children** reach the server response. Both Client-only shapes
render *instead of* the component, children included. For a leaf widget
that is correct: there are no children. For a transparent wrapper, such as
a context provider, Client-only deletes the subtree from the response —
and nothing complains, because the server bytes and the first client pass
agree by construction. The answer to "my provider vanished server-side" is
`{:server :render}`: the real subtree, the declared context value, rendered
once. The honest limit: a provider whose *value* comes from the browser has
no server story. It stays Client-only, and so does everything beneath it.

If the Render assertion is false, the failure is loud: `window is not
defined`, thrown mid-render, attributed to the request. Render was designed
to fail this way; the silent alternatives were worse. Refusals are equally
loud at the source. A policy value outside the two raises
`:rf.error/hicasso-host-bad-ssr-policy`. So does a `:fallback` under Render
— there is nothing for the fallback to replace. An option that `defhost`
does not know raises `:rf.error/hicasso-host-unknown-option`. A raw React
element escape carries no declaration, so it carries no server-safety
assertion either. It renders nothing server-side, always.

## The native tier under SSR

The same per-surface rule applies, at the same sources. An intrinsic [`n/$`](glossary.md#n-dollar)
form is markup, and it Renders. A named native component declares its
policy, and the default is conservative:

```clojure
(n/defcomponent ticker
  {:server :render}
  [^js props]
  (let [price (n/use-sub [:quote/price (.-symbol props)])]
    (n/$ :span {:class "ticker"} price)))
```

Under a server render, [`n/use-sub`](glossary.md#nuse-sub) reads the request snapshot through the
hook's server path: the same cold-probe discipline, with no registration.
Omit the declaration map, and the component stays Client-only until its
declaration selects Render ([The native tier](10-native-tier.md)).

## The Node service

The server module deploys as a bounded Node service. Running one provides
the operational half of the story:

- **Per-request isolation** — a fresh frame per request, destroyed in a
  `finally`. State never crosses requests, and a long-running process does
  not accumulate frames.
- **The allowlisted payload** — the fail-closed `:payload` contract,
  enforced at boot. What rides the wire is a decision, not an accident.
- **Bounded execution** — one in-flight render per isolate, bounded
  concurrency, and a timeout with hard termination behind it. The service
  kills a render that hangs; it does not queue work behind it.
- **Build identity** — the service knows which client bundle its bytes
  match, because "same bundle" is half of "same bytes".
- **Error attribution** — a throw during a request is a non-200 with a
  source, never a half-page.

The service renders whole pages. Streaming, React Server Components,
islands, and no-JS progressive enhancement are outside the product. The
render contract keeps response framing separate from semantics, so none of
them requires that a second renderer ever exist.

## When not to server-render

A client-only application never ships the service: no Node process, no
payload, no snapshot plumbing. Boot is [`h/mount!`](glossary.md#mount) and an `:initial-events`
seed. None of the service's operational weight exists in your deployment.
Apps behind a login wall usually belong here. First-response HTML of
private, per-user state rarely justifies a render fleet.

You do not opt out of the contract. Server policies are declared and
checked at their sources whether or not a server exists. Every surface
keeps its Render-or-refuse disposition, and hydration behaviour is part of
each surface's definition. That is why turning SSR on later is
configuration — a snapshot, an allowlist, a prefix — and not a rewrite.

## Troubleshooting

| Symptom | What it is | Fix |
|---|---|---|
| `:rf.ssr/hydration-mismatch` naming a root and a view | The two renders disagreed — a body read the platform (clock, random, `js/window`) instead of props and reads | Keep bodies pure; platform work goes to `:platforms #{:client}` effects and host edges |
| Every `useId`-derived id on one root complains at once | That root's `:identifier-prefix` does not match the server render's | Pass the same prefix to `server/render` and that root's [`h/hydrate!`](glossary.md#hydrate); keep prefixes unique per root |
| A Client-only host shows its fallback, then the live widget swaps in | The policy working: the fallback is in the server bytes and the first client pass; the component mounts at adoption | Make the fallback a same-footprint skeleton to kill the layout jump; only `{:server :render}` removes the swap |
| `:rf.error/hicasso-host-fallback-boundary-head` at a declaration | A [`defview`](glossary.md#defview)/[`defhost`](glossary.md#defhost) head in a fallback — a body that runs later cannot be deterministic, and the fallback must render identically on the server and hydration's first pass | Plain hiccup in the fallback, or `{:server :render}` and render the real subtree |
| `window is not defined` during a server render under `{:server :render}` | The Render assertion is false — the component is not server-safe | Drop back to Client-only with a fallback; this failure is loud by design |
| A crossing's children are missing from the server HTML, silently | The crossing is Client-only, and both Client-only shapes render instead of the component — a transparent wrapper takes its subtree with it, and both passes agree so nothing reports | Declare `{:server :render}` on the wrapper if it is server-safe |
| `:rf.error/hicasso-host-bad-ssr-policy` at a declaration | A policy value outside the two, or `:fallback` combined with Render | Two policies: `:render`, or `:client-only` with an optional `:fallback` |
| `:rf.error/ssr-missing-payload-policy` at service boot | No `:payload` policy — the wire contract is fail-closed | Allowlist the top-level app-db keys the page reads, or opt in with `:rf.ssr.payload/whole-app-db` |
| Mismatch complaints although every body is pure | A key the views read is missing from the `:payload` allowlist, so the client hydrated against different state | Add the key to the allowlist |
| `:rf.error/hydration-frame-id-mismatch` at boot | The payload's wire frame id and `ssr/hydrate!`'s `:frame` disagree | One stable id on both sides: `:client-frame-id` at the server render, the same `:frame` at boot |
