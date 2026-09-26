# Build RealWorld — what you'll make, and setup

The [Core introduction](../../core/introduction.md) taught the pure pipeline in a
browser cell. This tutorial grows it into **Conduit** — a Medium-style app with
feeds, auth, favoriting, and a production build — on the real toolchain. This page
scaffolds the project; budget five minutes from `npm install` to pixels.

Conduit follows the [RealWorld spec](https://github.com/gothinkster/realworld), so the same app already exists in React, Vue, Svelte, Solid, and Elm, and every pattern here has a counterpart in a stack you know. Each part follows the same rhythm: *do* a thing, *observe* what the app did in [Xray](../../core/glossary.md#xray) (the inspector you set up below), then *explain* why.

## One app, six parts

Each part adds one slice of the app and the machinery that slice needs:

| Part | You add | You learn |
|---|---|---|
| [Part 1](01-pages-and-state.md) | Pages, navigation, and the first feed | app-db, events, subs, views, routing |
| [Part 2](02-server-data.md) | Real data from a Conduit API | resources, and handling every state a page's data can be in |
| [Part 3](03-auth-and-forms.md) | Login, register, and a session that survives reload | forms, the session |
| [Part 4](04-scopes-and-guards.md) | A cache per reader, and pages that need a signed-in user | scopes, guarding navigation |
| [Part 5](05-mutations-and-invalidation.md) | Favoriting, publishing, and an unsaved-draft guard | mutations, and invalidating the reads they make stale |
| [Part 6](06-test-and-ship.md) | Tests and a production build | testing the pieces, shipping the app |

From Part 2 onward the app talks to a Conduit API — the hosted RealWorld API, or the upstream reference backend running on your own machine. Part 2 sets that up. The finished reference lives at [`examples/real-apps/realworld_resources/`](../../../examples/real-apps/realworld_resources) — the same app on resources and mutations — so you can peek when you're stuck. (A sibling, [`realworld_http/`](../../../examples/real-apps/realworld_http), builds the same app on the raw HTTP transport with no resource layer — useful later, as the before-picture.)

## What you need

- **Node.js** (18+), **a JDK** (11+), and the **Clojure CLI**. npm runs the build tool's launcher and supplies React; the ClojureScript compiler runs on the JVM; and `clojure` resolves the JVM-side dependencies declared in `deps.edn`.
- **A checkout of re-frame2.** re-frame2 is pre-alpha and not on a Maven repository yet, so you depend on a local checkout cloned next to your project. Once it ships, the `:local/root` entries below become ordinary `:mvn/version` coordinates.

```bash
git clone https://github.com/day8/re-frame2.git
```

??? info "For JavaScript developers"

    shadow-cljs is your Vite — dev server, hot reload, and bundler in one. `deps.edn` is `package.json` for the JVM-side (ClojureScript) libraries, and `package.json` still handles the npm side. Two manifests instead of one, because two language ecosystems meet here.

## Scaffold: four files

Create a project directory next to your re-frame2 clone:

```text
conduit/
  deps.edn               ;; ClojureScript dependencies
  package.json           ;; npm dependencies
  shadow-cljs.edn        ;; the build
  public/index.html      ;; the host page
  src/conduit/core.cljs  ;; the app (next section)
```

**`deps.edn`** — the compiler, the core artefact, the Reagent [adapter](../../core/glossary.md#adapter), and (dev-only) Xray:

```clojure
{:deps {thheller/shadow-cljs   {:mvn/version "3.4.10"}  ;; the build tool's JVM half
        day8/re-frame2         {:local/root "../re-frame2/implementation/core"}
        day8/re-frame2-reagent {:local/root "../re-frame2/implementation/adapters/reagent"}}
 :aliases
 {:dev {:extra-deps {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

`thheller/shadow-cljs` here is the compiler itself; the npm package below is only its launcher, and the two versions must match or the build won't start. Xray, the inspector you'll keep open for the whole tutorial, sits under `:aliases {:dev …}`. An **alias** in `deps.edn` is a named bundle of extra dependencies you opt into, much like an npm `devDependency`; a release build never activates `:dev`, so Xray stays out of your shipped bundle.

??? info "For JavaScript developers"

    Xray is your React DevTools — except instead of a component tree, it shows you the framework's own record of every event, every state change, and every subscription read. (That record is the [trace stream](../../core/glossary.md#trace-stream); Xray is just the prettiest reader of it.) You'll lean on it constantly. Like DevTools, it ships only in dev builds; the `:dev` alias is what makes that automatic.

**`package.json`**:

```json
{"name": "conduit",
 "private": true,
 "scripts": {"dev": "shadow-cljs watch app"},
 "dependencies": {"react": "19.3.0", "react-dom": "19.3.0"},
 "devDependencies": {"shadow-cljs": "3.4.10",
                     "@xyflow/react": "12.4.2",
                     "elkjs": "^0.11.1"}}
```

`@xyflow/react` and `elkjs` belong to Xray, not your app — its machine-topology canvas renders with them — so they sit in `devDependencies` beside `shadow-cljs`.

**`shadow-cljs.edn`**:

```clojure
{:deps {:aliases [:dev]}          ;; classpath comes from deps.edn, plus the :dev alias
 :dev-http {8020 "public"}
 :builds
 {:app {:target     :browser
        :output-dir "public/js"
        :asset-path "/js"
        :modules    {:main {:init-fn conduit.core/run}}
        :devtools   {:preloads [day8.re-frame2-xray.preload]}}}}
```

Two lines matter beyond the boilerplate. `:init-fn` names your boot function, which you'll write in a moment. `:preloads` injects Xray into **dev builds only** and opens its panel once the app boots; release builds skip `:devtools` entirely. [Configure dev and production builds](../../core/how-to/configure-dev-and-prod.md) covers the full split.

**`public/index.html`** — the official Conduit theme, a mount node, and a right-hand rail reserved for Xray:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Conduit</title>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="https://demo.productionready.io/main.css">
  <style>
    .app-shell { display: flex; min-height: 100vh; }
    #app { flex: 1; min-width: 0; }
    [data-rf-xray-host] { flex: 0 0 var(--rf-xray-inline-width, 560px); min-width: 320px; }
  </style>
</head>
<body>
  <div class="app-shell">
    <main id="app"></main>
    <aside data-rf-xray-host></aside>  <!-- Xray renders here, in dev builds only -->
  </div>
  <script src="/js/main.js"></script>
</body>
</html>
```

Your page owns the layout; Xray owns only the content inside `[data-rf-xray-host]`. In a release build that rail stays empty, so the same HTML works for both.

## The app's first file

`src/conduit/core.cljs` is the signed-out Conduit shell: a navbar, the banner, and the boot. Every later part edits or extends this file.

```clojure
;; Adapted from examples/core/counter and examples/real-apps/realworld_http
;; in the re-frame2 repo.
(ns conduit.core
  (:require [re-frame.core            :as rf]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; --- The boot event: seeds the initial app-db value ---
(rf/reg-event :app/initialise
  (fn [_cofx _event]
    {:db {:session {:user nil}}}))    ;; nobody is signed in yet

;; --- Subscription: who is signed in? ---
(rf/reg-sub :session/user
  (fn [db _query]
    (get-in db [:session :user])))

;; --- Views ---
(reg-view header []
  (let [user @(subscribe [:session/user])]
    [:nav.navbar.navbar-light
     [:div.container
      [:a.navbar-brand {:href "/"} "conduit"]
      [:ul.nav.navbar-nav.pull-xs-right
       (if user
         [:li.nav-item [:a.nav-link {:href "/"} (:username user)]]
         [:<>
          ;; Placeholder anchors — Part 1 replaces these with real routes.
          [:li.nav-item [:a.nav-link {:href "#"} "Sign in"]]
          [:li.nav-item [:a.nav-link {:href "#"} "Sign up"]]])]]]))

(reg-view banner []
  [:div.home-page
   [:div.banner
    [:div.container
     [:h1.logo-font "conduit"]
     [:p "A place to share your knowledge."]]]])

(reg-view shell []
  [:div
   [header]
   [banner]])

;; --- Mount: the only impure corner of the file ---
(defonce app-root (reagent-adapter/client-root))

(defn run []
  (rf/init! reagent-adapter/adapter)        ;; 1. install the Reagent substrate
  (rf/make-frame {:id :rf/default})             ;; 2. establish this app's one frame
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:app/initialise]))   ;; 3. seed app-db before first render
  (reagent-adapter/render! app-root
    [rf/frame-provider {:frame :rf/default} ;; 4. the whole tree runs in this frame
     [shell]]
    (js/document.getElementById "app")))
```

The events, subs, and views here are the quickstart's pipeline again — an [event](../../core/glossary.md#event) updates [app-db](../../core/glossary.md#app-db) (your app's single state map), a [subscription](../../core/glossary.md#subscription) reads from it, and a [view](../../core/glossary.md#view) renders that read. Two bits of syntax are new because the browser cells hid them:

- **`reg-view`** is a macro, which is why it's `:require-macros`'d rather than `:require`'d. It defines a view *and* wires its body to the current frame, so inside `header` a bare `subscribe` / `dispatch` finds the right app-db with no frame argument to pass around. The browser cells couldn't run macros, so the quickstart used plain `defn` views with `rf/subscribe`; on the real toolchain `reg-view` is the normal shape.
- **`@(subscribe …)`** — a subscription returns a *reactive reference*, and the view re-renders whenever its value changes. The leading `@` (Clojure's deref) reads the current value. Read `@(subscribe [:session/user])` as "the live value of who's signed in."

What's new beyond syntax is the **boot** in `run` — the part the browser cells did for you. It's four steps, in order:

1. **`(rf/init! reagent-adapter/adapter)` installs the substrate.** The [substrate](../../core/glossary.md#substrate) is the view library's reactivity; the [adapter](../../core/glossary.md#adapter) binds re-frame2 to it. Calling `init!` again with the same adapter does nothing, so hot reload is safe; handing it a *different* adapter is an error. To swap substrates later you change one require and this one Var ([Use UIx or reagent-slim](../../core/how-to/use-uix-or-slim.md)).
2. **`(rf/make-frame {:id :rf/default})` creates the frame.** Every dispatch and subscription runs against a [**frame**](../../core/glossary.md#frame) — an isolated instance of the app holding its own app-db. The runtime never invents one for you. `make-frame` creates the frame and registers it under `:id` in one call. A fresh frame always starts with `app-db = {}`, which is why the next step seeds it with an event. The config map grows in later parts.
3. **`with-frame` + `dispatch-sync` seeds state.** Outside the rendered tree there's no provider in scope, so `with-frame` names the frame for the dispatch. [`dispatch-sync`](../../core/glossary.md#dispatch-sync) runs the [event pipeline](../../core/glossary.md#event-pipeline) immediately instead of queuing it, so the first render can't paint an empty app-db. Boot, tests and the REPL are where `dispatch-sync` belongs; calling it from inside a running handler raises `:rf.error/dispatch-sync-in-handler`.
4. **`frame-provider` wraps the tree.** The [provider](../../core/glossary.md#frame-provider) passes the `:rf/default` frame down through React context, so every bare `dispatch` / `subscribe` inside a `reg-view` body resolves to it. Given a `:frame` key, it scopes the tree to a frame that already exists (step 2) — it creates and destroys nothing. It's the React-side counterpart of `with-frame`, which can't reach children that render after it returns. (`defonce` keeps the same client-root handle across hot reloads: the first `render!` creates the React root, later ones update it.)

??? info "For JavaScript developers"

    Step 4 is a context provider — the same pattern as wrapping your React tree in a `<Provider>` so hooks deep in the tree can reach shared state. The frame is what's carried down the context; `subscribe` and `dispatch` are the hooks that read it.

??? info "Coming from Redux?"

    Step 1 (`init!`) is roughly `applyMiddleware` — it wires the runtime to a substrate. Step 2 (`make-frame`) is `createStore`. Step 3 is your initial-state argument to `createStore`, expressed as an event. Step 4 is `<Provider store={...}>`. The difference: re-frame2 makes you name the store (the frame), because an app can run several isolated frames side by side.

`frame-provider` has a sibling, `frame-root {:id …}`, which *creates* its frame on first mount — for a view that brings its own frame, such as an embedded widget. This tutorial never needs it; [Frames: isolated worlds](../../core/frames.md) covers both.

### Troubleshooting

Each boot step has a named way of failing. Every failure arrives as a structured [error record](../../core/glossary.md#error-record) — in the console and as a row in Xray — under a stable `:rf.error/*` category. If you hit an error on first run, match its category here:

| `:rf.error/*` category | What happened | The fix |
|---|---|---|
| `:rf.error/no-adapter-installed` | Something rendered or subscribed before any `init!` ran — usually a refactor that moved the boot and dropped step 1. | Install the adapter first; everything else comes after. |
| `:rf.error/no-frame-context` (at a dispatch) | An event was dispatched with no frame in scope. The classic case is a top-of-namespace `dispatch`, which runs at *load* time — before any frame exists. | Boot-time events belong inside `run`, under `with-frame`, after `make-frame`. |
| `:rf.error/no-frame-context` (at a subscribe) | The tree rendered *without* the provider, so the first `subscribe` in a view has no frame to read. No fallback exists underneath. | Wrap the root in `frame-provider {:frame …}` (step 4). |
| `:rf.error/frame-provider-frame-absent` | `frame-provider` was handed a `:frame` that was never created (or has been destroyed). | Create the frame with `make-frame` before rendering (step 2). |
| `:rf.error/no-such-handler` | A dispatch reached the runtime but nothing is registered under that id. Once the app spans files (Part 1 on), the usual cause isn't a typo — it's a feature namespace never `:require`d from `core`, so its registrations never ran. | `:require` the feature namespace from `core` so its registrations run at load. |
| `:rf.error/no-such-fx` | The same story for an [effect](../../core/glossary.md#effect) — a side-effect the framework performs for you — whose [effect handler](../../core/glossary.md#effect-handler) lives in a namespace that never loaded. | This is why Part 2 requires the HTTP artefact at boot, so its effects register. |

## `npm install` to pixels

```bash
cd conduit
npm install          # shadow-cljs + React           (~30s)
npm run dev          # first compile                 (~60–90s)
```

When the build reports `Build completed`, open **<http://localhost:8020>**. You should see the green Conduit banner, the navbar with **Sign in** / **Sign up** — and Xray already open in the right rail. If you got an error instead, match its `:rf.error/*` category against the table above.

## Minute one: open Xray

Xray opened with the app, and `Ctrl+Shift+C` toggles it. Before you've written any feature code, it already shows:

- **The event timeline** has one row: `:app/initialise` — the runtime's own record of the only thing that has happened so far.
- **app-db** shows `{:session {:user nil}}` — exactly the value the boot event returned.

**Keep Xray open for the whole tutorial.** When something misbehaves later, read what the app actually did instead of adding print statements. (The framework keeps its own state in a separate partition, [runtime-db](../../core/glossary.md#runtime-db), which Xray shows once routing and resources start using it.) [Debug with Xray](../../xray/index.md) is the full tour.
