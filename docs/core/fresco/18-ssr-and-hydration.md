# SSR and hydration

With server-side rendering, a Node process renders your Fresco views to HTML,
and the browser then adopts that HTML instead of mounting a fresh page. The
server runs the same views as the client, so there is no second template
layer to keep in sync.

Your views need no SSR-specific code. What you do declare is a server policy
for each foreign React component (a `defhost`):

- **Render**: run it on the server and produce deterministic HTML.
- **Client-only**: skip it on the server and emit a declared fallback, or
  nothing, until the browser takes over.

## Render a page from a snapshot

The example page renders the todo list and a browser-only progress chart:

```clojure
(ns app.views
  (:require [re-frame.fresco :as h]
            ["progress-chart" :refer [ProgressChart]]))

(h/defhost progress-chart ProgressChart
  {:server   :client-only
   :fallback [:div {:class "chart chart--pending"} "Chart loads in the browser"]})

(h/defview todo-item [{:keys [id]}]
  (let [todo (h/sub [:todo/by-id id])]
    [:li {:class (when (:done? todo) "done")} (:title todo)]))

(h/defview page [_]
  [:main
   [:h1 "Todos"]
   [:ul
    (for [{:keys [id]} (h/sub [:todo/visible])]
      [todo-item {:id id :key id}])]
   [progress-chart {:remaining (h/sub [:todo/remaining-count])}]])
```

The server renders one request with `re-frame.fresco.server`. The Node process
needs its own adapter: install the headless SSR adapter once at startup, or
`server/render` raises `:rf.error/no-adapter-installed` when it builds the
request frame.

```clojure
(ns app.server
  (:require [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.fresco.server :as server]
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
     :payload           [:todos :showing]
     :client-frame-id   :app
     :identifier-prefix "main"
     :app-element-id    "app"
     :script-src        "/js/app.js"
     :title             "Todos"})))
```

`server/render` does this for each call:

1. Creates a fresh private frame for the request.
2. Sets its app-db to `:snapshot` (through `:rf/set-db`), then runs any
   `:initial-events` in order.
3. Renders the tree to HTML with `react-dom/server`, applying each host's
   server policy.
4. Embeds the allowlisted app-db keys in the page as `__rf_payload`, the
   hydration payload.
5. Destroys the request frame, even when rendering throws.

Because each request has its own frame, concurrent requests cannot read each
other's app-db.

If the runtime records any error during the render, even one it recovered
from (a subscription that threw, say), `server/render` throws
`:rf.error/ssr-render-failed` instead of returning a page. The request fails
rather than shipping a wrong page with a 200.

### The payload allowlist

`:payload` is required, and must be either:

- a non-empty vector of top-level app-db keys; or
- `:rf.ssr.payload/whole-app-db` as an explicit opt-in.

Omitting it makes `server/render` raise `:rf.error/ssr-missing-payload-policy`.
Allowlist every top-level key the rendered page reads. If the server rendered
a value the client did not receive, the first client render sees different
state and hydration reports a mismatch.

`server/render` also returns `:html` (the app root's inner markup) and
`:payload` beside `:document`; the HTTP handler normally sends `:document`.

### Server render rules

A server render performs cold subscription reads against one immutable
snapshot. It does not register live subscriptions, commit React work, or run
client effects.

Two renders from the same code and snapshot should produce the same document,
so do not read clocks, randomness, `window`, or other browser state in a view
body. Put browser work in effects registered with `:platforms #{:client}`, or
behind a declared host.

## Create the frame, hydrate state, then adopt the DOM

The first client render must see the same state used by the server, and the
frame that state lands in must already exist. After the usual
[adapter install](00-installation.md#fresco-needs-a-substrate-adapter), a
hydrating boot has three ordered steps:

```clojure
(ns app.client
  (:require [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.fresco :as h]
            [re-frame.fresco.substrate :as substrate]
            [app.views :as views]
            [app.subs]
            [app.events]))

(defonce app-root (h/client-root))

(defn ^:export run []
  (rf/init! substrate/adapter)

  ;; 1. Create the client frame both hydration steps name.
  (rf/make-frame {:id :app :platform :client})

  ;; 2. Read __rf_payload and replace that frame's state.
  (ssr/hydrate! {:frame :app})

  ;; 3. Adopt the existing server DOM.
  (h/render! app-root
             [h/frame-provider {:frame :app}
              [views/page {}]]
             (js/document.getElementById "app")
             {:hydrate? true :identifier-prefix "main"}))
```

The three calls have different jobs:

- `rf/make-frame` creates the frame. Neither hydration step does it for you:
  `ssr/hydrate!` seeds a frame that already exists, and the adopting tree uses
  `h/frame-provider`, which scopes an existing frame rather than creating one.
  Do not use `h/frame-root` here. Its first render emits no children (they
  arrive on a second pass, after the frame is ensured), so React would be
  handed an empty tree where the server's markup is and report a mismatch.
- `ssr/hydrate!` applies the state payload through `:rf/hydrate`. It validates
  the wire frame id against the requested frame. A mismatch raises
  `:rf.error/hydration-frame-id-mismatch`; omitting `:frame` raises
  `:rf.error/no-frame-context`, because the target is supplied rather than
  inferred.
- `h/render!` with `{:hydrate? true}` calls React's `hydrateRoot` on a
  container that already has server markup. It applies to the first call
  only: every later render through `app-root` updates the root it adopted, and
  the key is ignored.

Skipping step 1 does not throw at step 2: `ssr/hydrate!` applies nothing,
emits `:rf.error/frame-destroyed` and returns `nil`. It fails at step 3, where
`h/frame-provider` raises `:rf.error/frame-provider-frame-absent`.

An adopting root is otherwise an ordinary root: its opts carry React-root
options only, and `h/unmount!` takes it down.

A few more rules apply to an adopting root:

- Hydration is per root. Each root has its own container, identifier prefix,
  and mismatch reporting.
- `h/render!` returns before adoption finishes, because React hydrates
  asynchronously. The next line must not assume the browser owns the DOM yet.
- `:identifier-prefix` must match the server's. React includes the prefix in
  `useId` output, so a mismatch can flag every generated id in the root.
- Controlled inputs keep the value the server rendered. The model value must
  already be in the server HTML.
- Children under presence management start as `:present`, so existing page
  content does not replay an entry animation.

`ssr/hydrate!` returns the applied payload, or `nil` when the page carries
none or the frame is not live. A boot path shared by server-rendered and
client-only pages can ask first with `ssr/read-server-payload`, which returns
the payload map or `nil` without applying anything:

```clojure
;; app.client, replacing run above
(defn ^:export run []
  (rf/init! substrate/adapter)
  (let [el (js/document.getElementById "app")]
    (if (ssr/read-server-payload)
      (do (rf/make-frame {:id :app :platform :client})
          (ssr/hydrate! {:frame :app})
          (h/render! app-root
                     [h/frame-provider {:frame :app} [views/page {}]]
                     el
                     {:hydrate? true :identifier-prefix "main"}))
      (h/render! app-root
                 [h/frame-root {:id :app :initial-events [[:todo/initialise]]}
                  [views/page {}]]
                 el))))
```

The client-only branch needs no `rf/make-frame`: `h/frame-root` creates the
frame and runs its `:initial-events`.

## Client-only components and fallbacks

Client-only is the default for foreign hosts:

```clojure
(h/defhost date-picker DatePicker)   ; Client-only, renders nothing on the server
```

The `progress-chart` host at the top of the page shows the other shape, a
Client-only host with a fallback.

On the server, the host renders its fallback or nothing. The first client
pass produces the same fallback. After adoption, the live component mounts. A
fresh client-only application with no SSR mounts the live component directly
and does not show the server fallback.

A fallback must be plain, deterministic Hiccup, and it is checked when the
host is declared. A `defview` or `defhost` inside it raises
`:rf.error/fresco-host-fallback-boundary-head`.

Give the fallback the same size as the live component to avoid layout shift.

### One browser-only leaf inside a server-rendered region

When a region is server-safe except for one leaf, declare the region Render
and the leaf Client-only. Only the leaf is skipped on the server.

```clojure
(h/defhost card Card
  {:server :render})

(h/defhost viewport-badge ViewportBadge
  {:server   :client-only
   :fallback [:span {:class "badge badge--pending"} "Measures in the browser"]})

[card {}
 [:p "Server-rendered copy."]
 [viewport-badge {}]
 [:p "More server-rendered copy."]]
```

The response carries the card, both paragraphs and the badge's fallback; the
badge's own component never runs on the server. It hydrates against the
fallback it emitted, and the live component mounts after adoption.

A Client-only host replaces itself and its children, so moving the region's
server-safe content inside the leaf would delete that content from the
response. Keep the leaf as small as the browser dependency.

## Render-safe hosts

Declare `{:server :render}` when a component is deterministic and safe to run
on the server:

```clojure
;; (:require ["react" :as react])
(def theme-context
  (react/createContext "light"))

(h/defhost theme-provider
  (.-Provider theme-context)
  {:server :render})
```

Under Render, the real component is used for:

- server rendering;
- the first client hydration pass;
- fresh client mounts.

There is no component swap after adoption.

Render is also the only policy that renders a host's children on the server.
A Client-only host emits its fallback, or nothing, in place of the whole host,
children included. So a wrapper such as a context provider removes its whole
subtree from the server response unless it is declared Render.

A provider whose value depends on browser-only state has no deterministic
server contract and remains Client-only, along with its subtree.

A false Render assertion fails loudly, often as `window is not defined` during
the server render. Other declaration failures include:

- `:rf.error/fresco-host-bad-ssr-policy` for an unsupported policy or a
  `:fallback` combined with Render;
- `:rf.error/fresco-bad-host-declaration` for an unknown host option.

## Multiple roots report independently

A page can hydrate several roots against one frame and payload. `server/render`
returns markup for one root (its `:html`), so each extra root needs its own
server markup in the page shell, rendered with the same `:identifier-prefix`
its client root will use. The client side looks like this:

```clojure
;; app.views
(h/defview help-panel [_]
  [:aside
   [:h2 "Need a hand?"]
   ;; Don't do this: the server and client render different text.
   [:p "Generated at " (js/Date.now)]])

;; app.client
(defonce app-root (h/client-root))
(defonce help-root (h/client-root))

(defn ^:export run []
  (rf/init! substrate/adapter)
  (rf/make-frame {:id :app :platform :client})
  (ssr/hydrate! {:frame :app})

  ;; One handle per root.
  (h/render! app-root
             [h/frame-provider {:frame :app}
              [views/page {}]]
             (js/document.getElementById "app")
             {:hydrate? true :identifier-prefix "main"})

  (h/render! help-root
             [h/frame-provider {:frame :app}
              [views/help-panel {}]]
             (js/document.getElementById "help")
             {:hydrate? true :identifier-prefix "help"}))
```

The timestamp differs between server and client. React patches the help
root's DOM and, in development builds, Fresco emits a
`:rf.ssr/hydration-mismatch` warning trace from that root's recoverable-error
callback:

```clojure
{:error    "Hydration failed because ..."   ;; React's message
 :where    re-frame.fresco.impl.mount/hydrate-root!
 :recovery :warned-and-replaced}
```

The app root still hydrates cleanly: each root reports only its own
mismatches. Xray lists these warnings with its other issues
([Diagnostics](16-diagnostics.md)).

React reports text differences and missing, extra, or wrong-type elements.
An attribute-only difference may produce only a development warning. Either
way, the fix is to put values both sides need in the snapshot or payload.

??? info "Coming from a Hiccup-tree hash"
    Some adapters can hash an authored Hiccup data tree before React sees it.
    Fresco views produce React elements and React performs the traversal, so
    there is no separate complete Hiccup tree to hash. Verification uses
    React's own root-scoped adoption reports.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A `:rf.ssr/hydration-mismatch` warning appears in development | Server and first client render differed, often because a body read a clock, random value, or browser global | Keep bodies deterministic; move platform work to client effects or host edges |
| Hydration reports a mismatch and the server markup is replaced | The adopting tree uses `h/frame-root`, whose first render is empty | Make the frame with `rf/make-frame`, then adopt under `[h/frame-provider {:frame …}]` |
| Every `useId` id in one root reports a mismatch | The root's `:identifier-prefix` differs from the server prefix | Use the same unique prefix in `server/render` and that root's adopting `h/render!` |
| An adopting `h/render!` throws `:rf.error/frame-provider-frame-absent` | `rf/make-frame` was skipped, so `h/frame-provider` names a frame that does not exist | Create the frame, then install the payload, then adopt the DOM |
| Client-only widget shows a skeleton, then swaps to the live widget | The Client-only policy is working | Use a same-size fallback, or select Render only when the component is truly server-safe |
| Declaration raises `:rf.error/fresco-host-fallback-boundary-head` | The fallback contains a view or host head | Use plain deterministic Hiccup, or render the real component with `{:server :render}` |
| Server render throws `window is not defined` under Render | The component is not server-safe | Return it to Client-only and provide a fallback |
| A host's children are absent from server HTML | The host is Client-only, so the fallback replaces the whole host | Mark a server-safe transparent wrapper `{:server :render}` |
| Declaration raises `:rf.error/fresco-host-bad-ssr-policy` | Unsupported policy, or `:fallback` used with Render | Use `:render`, or `:client-only` with an optional fallback |
| `server/render` raises `:rf.error/ssr-missing-payload-policy` | No fail-closed payload policy was supplied | Allowlist every top-level app-db key the page reads, or explicitly select whole app-db |
| Pure views still mismatch | A rendered app-db key was omitted from the payload | Add the key to the allowlist |
| Boot raises `:rf.error/hydration-frame-id-mismatch` | Server `:client-frame-id` and client `:frame` differ | Use one stable wire frame id on both sides |
| Nothing throws, `ssr/hydrate!` returns a payload, and the page still renders empty (an `:rf.error/frame-destroyed` record names `:rf/hydrate`) | `rf/make-frame` ran after `ssr/hydrate!`, so the `:rf/hydrate` dispatch had no frame to land in | Call `rf/make-frame` before `ssr/hydrate!` |
| `server/render` raises `:rf.error/ssr-render-failed` | The runtime recorded an error during the render, such as a subscription that threw, even though rendering continued | Fix the failure the attached record names; the renderer refuses to return a page built over it |

## When not to use SSR

A client-only application does not need the Node rendering service, payload
allowlist, or snapshot plumbing. Boot it with `h/render!` and no `:hydrate?`,
under an `[h/frame-root {:id … :initial-events …}]`.

Applications behind a login wall often gain little from rendering private,
per-user HTML on a server.

Even in a client-only deployment, keep host server policies accurate, so that
adding SSR later needs no view changes.

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
| Portals, `[:>]` crossings, and opaque foreign components reached through them | Client-only |
| A React element returned from a `defview` or placed as a child | Render, as React renders it; a component inside it has no Fresco gate, so it must be server-safe itself |
| React islands, through `h/defhost` | Client-only until the declaration selects Render |
| Resource boundaries | Follow their module's server contract; a passive read causes nothing, so no client `[:rf.resource/ensure …]` runs during server rendering |

Event intents require no wire serialisation. Each side turns the same vector
into its own callback.

### Islands under SSR

An island is Client-only unless its host declares Render:

```clojure
;; (:require ["react" :as react]
;;           [re-frame.fresco.native :as n])
(defn remaining-badge [^js _props]
  (let [n (n/use-sub [:todo/remaining-count])]
    (react/createElement "span" #js {:className "badge"} n)))

(h/defhost remaining-badge-host remaining-badge
  {:server :render})
```

During server rendering, `n/use-sub` performs the same cold snapshot read as
`h/sub`. It does not install a live subscription.

### Ring-hosted: rendering on a Node sidecar

Everything above renders in a Node process you drive yourself. If the rest of
your application runs on the JVM, a Ring handler can own the request instead
and call a Node sidecar for the body markup.

The JVM keeps the request frame, the boot-event drain,
the blocking-resource settle, the `<head>`, `__rf_payload`, the shell, the
status, headers, cookies, redirects, error projection and frame teardown. Node
returns a string and nothing else, so the sidecar's own HTTP status never
reaches the browser and no partial page is possible.

Three pieces make it work:

- **`re-frame.fresco.server/render-body`** — the body-only sibling of
  [`server/render`](#render-a-page-from-a-snapshot). It takes `:hiccup`, a
  `:render-state` envelope and an `:identifier-prefix`, installs both state
  partitions into a fresh per-request frame in one write, and returns inner
  markup. It builds no payload, no document and no head, because the JVM
  already owns all three. It replays no boot events either, because the JVM
  already ran them and the state it sends is the settled result.
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
[`substrates/fresco/login`](../../../examples/substrates/fresco/login):
`server.cljs` is the render module, and `host.clj` is a Ring server you can
start, publishing `init!`, `make-handler` and `make-app`. The shared
`login.model` is `.cljc`, so the JVM loads the application's own
registrations. The example's README has the commands.

Whichever host you use, Fresco renders whole pages. It does not support
streaming, React Server Components, partial hydration, or no-JavaScript
progressive enhancement.
