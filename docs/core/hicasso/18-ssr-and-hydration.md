# SSR and hydration

Server-side rendering should produce the same UI that the browser later
adopts. Hicasso does not use a separate string-template implementation: the
server renders the same views and React elements used by the client.

Every foreign or native component has one of two server policies:

- **Render**: run on the server and produce deterministic HTML;
- **Client-only**: do not run on the server; render a declared fallback or
  nothing until the browser takes over.

Hydration adopts the server's DOM and attaches the application. It does not
replace the page with a fresh client mount when the two sides agree.

## Render a page from a snapshot

The example page reads a feed from app-db and includes a browser-only chart:

```clojure
(ns app.views
  (:require [re-frame.hicasso :as h]
            ["trend-charts" :refer [TrendChart]]))

(h/defhost trend-chart TrendChart
  {:server   :client-only
   :fallback [:div
              {:class "chart chart--pending"}
              "Chart loads in the browser"]})

(h/defview article-row [{:keys [id]}]
  [:li {:class "article"}
   [:a {:href (h/sub [:article/url id])}
    (h/sub [:article/title id])]])

(h/defview page [_]
  [:main
   [:h1 (h/sub [:feed/heading])]
   [:ul
    (for [id (h/sub [:feed/article-ids])]
      [article-row {:id id :key id}])]
   [trend-chart
    {:points (clj->js (h/sub [:feed/trend]))}]])
```

No view code is specific to SSR.

Use the optional server module to render one request. The Node renderer is a
separate process from the browser, and nothing installs an adapter for it:
`server/render` mints a frame per request, and building that frame's state
container raises `:rf.error/no-adapter-installed` in a process where
`rf/init!` never ran — before any HTML is produced. Install the headless SSR
adapter once at process startup. It is boot work, not request work.

```clojure
(ns app.server
  (:require [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.hicasso.server :as server]
            [app.views :as views]
            [app.subs]
            [app.events]))

(rf/init! ssr/adapter)   ;; once, at process startup — never per request

(defn page-response
  "Render one request from an app-db snapshot."
  [db-snapshot]
  (:document
   (server/render
    {:hiccup            [views/page {}]
     :snapshot          db-snapshot
     :payload           [:feed :session]
     :client-frame-id   :app/main
     :identifier-prefix "main"
     :app-element-id    "app"
     :script-src        "/js/app.js"
     :title             "The feed"})))
```

`server/render` performs a fixed sequence:

1. Create a fresh private frame for the request.
2. Seed it through the normal framework doors: `:rf/set-db` for `:snapshot`,
   followed by any `:initial-events` in order.
3. Render the React tree to HTML with `react-dom/server`, applying each host
   and native component's server policy.
4. Build the hydration payload from the allowlisted app-db keys and embed it
   as `__rf_payload`.
5. Destroy the request frame in a `finally` block, including when rendering
   throws.

Concurrent requests cannot read each other's app-db.

### The payload is fail-closed

`:payload` must be either:

- a non-empty vector of top-level app-db keys; or
- `:rf.ssr.payload/whole-app-db` as an explicit opt-in.

Omitting it raises `:rf.error/ssr-missing-payload-policy` at service boot.
Allowlist every top-level key the rendered page reads. If the server rendered a
value that the client did not receive, the first client render uses different
state and the resulting hydration mismatch is real.

The HTTP service returns `:document`.

### Server render rules

A server render performs cold subscription reads against one immutable
snapshot. It does not register live subscriptions, commit React work, or run
client effects.

Two renders from the same code and snapshot should produce the same document.
Do not read clocks, randomness, `window`, or other ambient platform state from
a view body. Put browser work in client-only effects such as
`:platforms #{:client}` or behind a declared host.

## Create the frame, hydrate state, then adopt the DOM

The first client render must see the same state used by the server, and the
frame that state lands in must already exist. A hydrating boot therefore has
three ordered steps. They share the adapter precondition every browser boot
has — install one with `rf/init!` before the first frame, per
[Installation](00-installation.md#hicasso-needs-a-substrate-adapter).

```clojure
(ns app.client
  (:require [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.hicasso :as h]
            [app.views :as views]
            [app.subs]
            [app.events]))

(defn ^:export run []
  ;; 1. Create the client frame both hydration steps name.
  (rf/make-frame {:id :app/main :platform :client})

  ;; 2. Read __rf_payload and replace that frame's state.
  (ssr/hydrate! {:frame :app/main})

  ;; 3. Adopt the existing server DOM.
  (h/hydrate!
   (js/document.getElementById "app")
   {:frame             :app/main
    :identifier-prefix "main"}
   [views/page {}]))
```

The three calls have different jobs:

- `rf/make-frame` creates the frame. Neither hydration step does it for you:
  `ssr/hydrate!` seeds a frame that already exists, and `h/hydrate!` names the
  frame its root renders under.
- `ssr/hydrate!` applies the state payload through `:rf/hydrate`. It validates
  the wire frame id against the requested frame. A mismatch raises
  `:rf.error/hydration-frame-id-mismatch`; omitting `:frame` raises
  `:rf.error/no-frame-context`, because the target is supplied rather than
  inferred.
- `h/hydrate!` calls React's `hydrateRoot` on a container that already has
  server markup.

!!! warning "Seeding an absent frame is silent"

    `ssr/hydrate!` installs the payload by dispatching `:rf/hydrate`, and a
    dispatch into a frame that does not exist is a **no-op, not an error**. The
    call still reads the payload and still returns it, so a boot that skips
    step 1 looks like it worked: nothing throws, and the page adopts the
    server's DOM against a frame that never received the server's state.

The frame comes first, then state, then the DOM.

`h/hydrate!` has the same root lifecycle as `h/mount!`: it associates one
container, one frame, and one view, and returns a handle accepted by
`h/unmount!`.

Important root rules:

- **Hydration is root-scoped.** Each root owns its container, identifier
  prefix, and recoverable-error stream.
- **The call returns before adoption finishes.** React hydrates
  asynchronously. The next line must not assume the DOM is fully client-owned.
- **`:identifier-prefix` must match.** React includes the prefix in `useId`
  output. A mismatch can flag every generated id in the root.
- **Controlled text is adopted, not rewritten later.** The model value must
  already be present in the server bytes.
- **Presence-managed children start as `:present`.** Existing page content
  does not replay an entry animation.

`ssr/hydrate!` returns the applied payload, or `nil` when the page carries no
payload. A shared boot path branches on that: `nil` means nobody
server-rendered this page, so seed the frame with ordinary events and `h/mount!`
a fresh root instead of adopting one. Step 1 is the same either way — both
branches need the frame.

## Client-only components and fallbacks

Client-only is the default for foreign hosts:

```clojure
(h/defhost stock-widget StockWidget)

(h/defhost trend-chart TrendChart
  {:server   :client-only
   :fallback [:div
              {:class "chart chart--pending"}
              "Chart loads in the browser"]})
```

On the server, the crossing renders its fallback or nothing. The first client
pass produces the same fallback. After adoption, the live component mounts. A
fresh client-only application with no SSR mounts the live component directly
and does not show the server fallback.

A fallback must be deterministic, inert Hiccup. It is inspected at declaration
time. A `defview` or `defhost` head inside it raises
`:rf.error/hicasso-host-fallback-boundary-head`, because a later-running body
cannot be part of the fixed fallback contract.

Use a same-footprint skeleton to reduce layout shift.

## Render-safe hosts

Declare `{:server :render}` when a component is deterministic and safe to run
on the server:

```clojure
(h/defhost theme-provider ThemeProvider
  {:server :render})
```

Under Render, the real component is used for:

- server rendering;
- the first client hydration pass;
- fresh client mounts.

There is no component swap after adoption.

Render is also the only policy that sends a crossing's **children** to the
server. A Client-only component renders its fallback or nothing **instead of
the whole crossing**, including its children. A transparent wrapper such as a
context provider therefore deletes its subtree from the server response unless
it is declared Render.

A false Render assertion fails loudly, often as `window is not defined` during
the server render. Other declaration failures include:

- `:rf.error/hicasso-host-bad-ssr-policy` for an unsupported policy or a
  `:fallback` combined with Render;
- `:rf.error/hicasso-bad-host-declaration` for an unknown host option.

## Multiple roots report independently

A page can hydrate several roots against one frame and payload:

```clojure
(h/defview help-panel [_]
  [:aside
   [:h2 "Need a hand?"]
   ;; Don't: deliberate server/client divergence.
   [:p "Generated at " (js/Date.now)]])

(defn ^:export run []
  (rf/make-frame {:id :app/main :platform :client})

  (ssr/hydrate! {:frame :app/main})

  (h/hydrate!
   (js/document.getElementById "app")
   {:frame :app/main
    :identifier-prefix "main"}
   [views/page {}])

  (h/hydrate!
   (js/document.getElementById "help")
   {:frame :app/main
    :identifier-prefix "help"}
   [views/help-panel {}]))
```

The timestamp differs between server and client. React repairs the help root
and reports a root-scoped mismatch:

```clojure
{:id    :rf.ssr/hydration-mismatch
 :root  "help"
 :where app.views/help-panel
 :error recoverable-error}
```

The app root can still hydrate cleanly. Each `h/hydrate!` has its own
recoverable, caught, and uncaught error channels.

Xray associates hydration complaints with the root, view source, and host
policy ([Diagnostics](16-diagnostics.md)).

React reports text differences and missing, extra, or wrong-type elements.
Attribute-only divergence may produce only a development warning and can be
harder to observe. The reliable fix is the same: values that both sides must
share belong in the snapshot or hydration payload.

??? info "Coming from a Hiccup-tree hash"
    Some adapters can hash an authored Hiccup data tree before React sees it.
    Hicasso views produce React elements and React performs the traversal, so
    there is no separate complete Hiccup tree to hash. Verification uses
    React's own root-scoped adoption reports.

## When not to use SSR

A client-only application does not need the Node rendering service, payload
allowlist, or snapshot plumbing. Boot it with `h/mount!` and
`:initial-events`.

Applications behind a login wall often gain little from rendering private,
per-user HTML on a server fleet.

Even in a client-only deployment, keep host and native server policies
accurate. That makes later SSR adoption a configuration task rather than a
view rewrite.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `:rf.ssr/hydration-mismatch` names a root and view | Server and first client render differed, often because a body read a clock, random value, or browser global | Keep bodies deterministic; move platform work to client effects or host edges |
| Every `useId` id in one root reports a mismatch | The root's `:identifier-prefix` differs from the server prefix | Use the same unique prefix in `server/render` and that root's `h/hydrate!` |
| Client-only widget shows a skeleton, then swaps to the live widget | The Client-only policy is working | Use a same-size fallback, or select Render only when the component is truly server-safe |
| Declaration raises `:rf.error/hicasso-host-fallback-boundary-head` | The fallback contains a view or host head | Use plain deterministic Hiccup, or render the real component with `{:server :render}` |
| Server render throws `window is not defined` under Render | The component is not server-safe | Return it to Client-only and provide a fallback |
| A host's children are absent from server HTML | The host is Client-only, so the fallback replaces the whole crossing | Mark a server-safe transparent wrapper `{:server :render}` |
| Declaration raises `:rf.error/hicasso-host-bad-ssr-policy` | Unsupported policy, or `:fallback` used with Render | Use `:render`, or `:client-only` with an optional fallback |
| Service boot raises `:rf.error/ssr-missing-payload-policy` | No fail-closed payload policy was supplied | Allowlist every top-level app-db key the page reads, or explicitly select whole app-db |
| Pure views still mismatch | A rendered app-db key was omitted from the payload | Add the key to the allowlist |
| Boot raises `:rf.error/hydration-frame-id-mismatch` | Server `:client-frame-id` and client `:frame` differ | Use one stable wire frame id on both sides |
| Nothing throws, `ssr/hydrate!` returns a payload, and the page still renders empty | The client frame did not exist yet, so the `:rf/hydrate` dispatch was a silent no-op | `rf/make-frame` the client frame before `ssr/hydrate!` |

## Advanced

### Server policy by surface

React renders the server output; there is no parallel JVM string emitter.

| Surface | Server policy |
| --- | --- |
| Native Hiccup elements, fragments, and text | Render |
| `h/defview` bodies and `h/sub` reads | Render against the request snapshot |
| Controlled fields | Render their model `value` and `checked` attributes |
| `h/error-boundary` | The component renders, but a server throw uses React's server error channel rather than the client fallback |
| Roots and `h/as-component` | Render, with request isolation and prefix matching |
| `h/defhost`, slots, render props, and `h/as-element` | Client-only until the declaration selects Render |
| Portals, raw React elements, and opaque foreign components | Client-only |
| A React element returned directly from a `defview` | Render, as React renders it; a component inside it has no Hicasso gate, so it must be server-safe itself |
| React islands, through `h/defhost` | Client-only until the declaration selects Render |
| Resource boundaries | Follow their module's server contract; a passive read causes nothing, so no client `[:rf.resource/ensure …]` runs during server rendering |

Event intents require no wire serialisation. Each side turns the same vector
into its own callback.

### Context providers

A server-safe context provider must be declared Render so its children remain
in the response:

```clojure
(def theme-context
  (react/createContext "light"))

(h/defhost theme-provider
  (.-Provider theme-context)
  {:server :render})
```

A provider whose value depends on browser-only state has no deterministic
server contract and remains Client-only, along with its subtree.

### Islands under SSR

An island is Client-only unless its host declares Render:

```clojure
(defn ticker [^js props]
  (let [price (n/use-sub [:quote/price (.-symbol props)])]
    (react/createElement "span" #js {:className "ticker"} price)))

(h/defhost ticker-host ticker
  {:server :render})
```

During server rendering, `n/use-sub` performs the same cold snapshot read as
`h/sub`. It does not install a live subscription.

### Ring-hosted: rendering on a Node sidecar

Everything above renders in a Node process you drive yourself. There is also a
supported path where a **JVM Ring host** owns the request and calls out to a
Node sidecar for the body markup — which is what you want when the rest of the
application already runs on the JVM.

The division is strict. The JVM keeps the request frame, the boot-event drain,
the blocking-resource settle, the `<head>`, `__rf_payload`, the shell, the
status, headers, cookies, redirects, error projection and frame teardown. Node
returns a string and nothing else, so the sidecar's own HTTP status never
reaches the browser and no partial page is possible.

Three pieces make it work, and only the middle one is new to this chapter:

- **`re-frame.hicasso.server/render-body`** — the body-only sibling of
  [`server/render`](#render-a-page-from-a-snapshot). It takes `:hiccup`, a
  `:render-state` envelope and an `:identifier-prefix`, installs both state
  partitions into a fresh per-request frame in one write, and returns inner
  markup. It builds no payload, no document and no head, because the JVM
  already owns all three. It replays no boot events either — the JVM drained
  them, and the projection *is* the settled result.
- **A render module** in a `:node-library` build, publishing a build id and an
  entry table whose per-entry allowlists are the render-visibility policy.
- **`:renderer`** on the Ring handler, pointing at
  `re-frame.ssr.ring.node/renderer`.

The `:identifier-prefix` rule from
[Create the frame, hydrate state, then adopt the DOM](#create-the-frame-hydrate-state-then-adopt-the-dom)
applies unchanged and matters more here, because two processes now have to
agree on the string rather than one.

The full recipe — both builds, the module, the two state policies and why they
differ, the serve command, build-id skew and the deployment posture — is
[Render on Node](../../ssr/concepts.md#render-on-node). The worked example is
[`substrates/hicasso/login`](../../../examples/substrates/hicasso/login), whose
`server.cljs` is a real render module driven by the test suite against the real
views; its `host.clj` is annotated wiring rather than a server you can start,
because that example's model is ClojureScript-only today.

Whichever host you use, the service renders whole pages. Streaming, React
Server Components, islands, and no-JavaScript progressive enhancement are
outside this product's scope.
