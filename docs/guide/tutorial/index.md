# Build RealWorld — what you'll make, and setup

You're going to build **Conduit** — a working Medium-style blogging app. It follows the [RealWorld spec](https://github.com/gothinkster/realworld), the ecosystem's shared benchmark, which means the same app already exists in React, Vue, Svelte, Solid, and Elm. Every pattern you write here has a direct counterpart in a stack you already know. By the end of Part 5 you'll have feeds, tags, auth, favoriting, posting, tests, and a production build — a real app, not a toy.

**One real app, grown a part at a time — the same loop you ran in the [quickstart](../quickstart.md), now with a server on the other end.**

This page does two things. First it orients you, so you know where the five parts go. Then it scaffolds the project on the real toolchain — the part the quickstart hid so you could focus on the loop. Budget five minutes from `npm install` to pixels. If a step fails, don't worry: the four failure modes near the end of this page are the known ways it goes wrong, each with a fix.

!!! note "Haven't done the quickstart?"

    [Do that first](../quickstart.md). It teaches the loop — events → app-db → subs → views — right in your browser, with nothing installed. This page assumes you've seen that rhythm at least once.

## One app, five parts

Each part adds one slice of the real app and teaches the machinery that slice needs:

| Part | You add | You learn |
|---|---|---|
| [Part 1](01-pages-and-state.md) | Pages, navigation, and the first feed | app-db, events, subs, views, routing |
| [Part 2](02-server-data.md) | Real data from a Conduit API | resources, and the nine states server data can be in |
| [Part 3](03-auth-and-forms.md) | Login, register, and the route guard | forms, auth state, guarding navigation |
| [Part 4](04-mutations-and-invalidation.md) | Favoriting, posting, commenting | writes, and invalidating what they stale |
| [Part 5](05-test-and-ship.md) | Tests and a production build | testing the pieces, shipping the app |

From Part 2 onward the app talks to a Conduit API — either a hosted demo or an offline stub, whichever you prefer. Part 2 sets that up. The finished reference lives at [`examples/reagent/realworld/`](../../../examples/reagent/realworld/), so you can peek when you're stuck — but try not to read ahead, since building it yourself is where the learning happens.

## What you need

- **Node.js** (18+), **a JDK** (11+), and the **Clojure CLI**. Here's why all three: npm runs the build tool's launcher and supplies React; the ClojureScript compiler runs on the JVM, which is why you need a JDK; and `clojure` resolves the JVM-side dependencies in `deps.edn`.
- **A checkout of re-frame2.** A bit of pre-alpha honesty here — re-frame2 isn't on a Maven repository yet, so you depend on a local checkout cloned next to your project rather than a published version. Once it ships, the `:local/root` entries below become ordinary `:mvn/version` coordinates and this step goes away.

```bash
git clone https://github.com/day8/re-frame2.git
```

> **Coming from React?** shadow-cljs is your Vite — dev server, hot reload, and bundler in one; `deps.edn` is `package.json` for the JVM-side (ClojureScript) libraries, and `package.json` still handles the npm side.

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

**`deps.edn`** — the compiler, the core artefact, the Reagent adapter, and (dev-only) Xray:

```clojure
{:deps {thheller/shadow-cljs   {:mvn/version "3.4.10"}  ;; the build tool's JVM half
        day8/re-frame2         {:local/root "../re-frame2/implementation/core"}
        day8/re-frame2-reagent {:local/root "../re-frame2/implementation/adapters/reagent"}}
 :aliases
 {:dev {:extra-deps {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

`thheller/shadow-cljs` here is the compiler itself; the npm package below is only its launcher, and the two versions must match or the build won't start. Xray is the inspector you'll keep open for the whole tutorial — think of it as a live window into what your app is doing. It lives in a `:dev` alias because it's a tool, not application code, which keeps it out of your shipped bundle.

**`package.json`**:

```json
{"name": "conduit",
 "private": true,
 "scripts": {"dev": "shadow-cljs watch app"},
 "dependencies": {"react": "19.2.0", "react-dom": "19.2.0"},
 "devDependencies": {"shadow-cljs": "3.4.10",
                     "@xyflow/react": "12.4.2",
                     "elkjs": "^0.11.1"}}
```

`@xyflow/react` and `elkjs` belong to Xray, not your app — its machine-topology canvas renders with them, and the dev build resolves them from `node_modules` like any other JS dependency. Because they're dev-only, they sit alongside `shadow-cljs` in `devDependencies` rather than your app's real `dependencies`.

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

Two lines matter beyond the boilerplate. `:init-fn` names your boot function, which you'll write below. `:preloads` injects Xray into **dev builds only** — the preload registers its collectors and auto-opens the panel once the app boots. Release builds skip `:devtools` entirely, so Xray never reaches your production bundle, and you don't have to remember to strip it out. [Configure dev and production builds](../how-to/configure-dev-and-prod.md) covers the full split.

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

Your page owns the layout; Xray owns only the content inside `[data-rf-xray-host]`. In a release build that rail simply stays empty, so the same HTML works for both.

## The app's first file

`src/conduit/core.cljs` is the signed-out Conduit shell: a navbar, the banner, and the boot. This is the file the whole tutorial grows from, so it's worth reading slowly.

```clojure
;; Adapted from examples/reagent/counter and examples/reagent/realworld
;; in the re-frame2 repo.
(ns conduit.core
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
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
(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn run []
  (rf/init! reagent-adapter/adapter)        ;; 1. install the Reagent substrate
  (rf/reg-frame :rf/default {})             ;; 2. establish this app's one frame
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:app/initialise]))   ;; 3. seed app-db before first render
  (rdc/render root
    [rf/frame-provider-existing {:frame :rf/default} ;; 4. the whole tree runs in this frame
     [shell]]))
```

The events, subs, and views here are just the quickstart's loop again — an event updates app-db (your app's single state map), a subscription reads from it, and a view renders that read. What's genuinely new is the **boot**, the part the quickstart's browser cells quietly did for you. It's four moves, and each one has a named way of going wrong. The nice thing is that every failure arrives as a structured error — in the console *and* as a row in Xray, under a stable `:rf.error/*` id — so you're never left guessing.

**Move 1 — `(rf/init! reagent-adapter/adapter)` installs the substrate.** The substrate is the view library's reactivity that your subscriptions wire into, and this line tells the runtime which one you're using. It's idempotent, so hot reload is safe — calling it twice does nothing. It creates *no* frame; that's the next move's job. To swap substrates later you change one require and this one Var ([Use UIx, Helix, or reagent-slim](../how-to/use-uix-helix-or-slim.md)).

!!! warning "Failure mode 1 — `:rf.error/no-adapter-installed`"

    Something rendered or subscribed before any `init!` ran — usually a refactor that moved the boot and dropped the line. Install the adapter first; everything else comes after.

**Move 2 — `(rf/reg-frame :rf/default {})` establishes the frame.** Every dispatch and subscription runs against a **frame** — an isolated instance of the app holding its own app-db. The runtime *never* invents one for you: there's no ambient global and no silent default. A single-page app has exactly one frame, registered once at the root. The empty config map grows in later parts, so don't worry that it looks bare now. The full story is in [Frames: isolated worlds](../concepts/frames.md).

**Move 3 — `with-frame` + `dispatch-sync` seeds state.** Out here, outside the rendered tree, there's no provider in scope, so `with-frame` scopes the dispatch lexically to `:rf/default`. And it's `dispatch-sync` — a dispatch that runs the event immediately rather than queuing it — because plain `dispatch` would let the first render race it and paint an empty app-db. Seeding synchronously at the boot boundary is one of only two legitimate uses of `dispatch-sync`; the other is tests.

!!! warning "Failure mode 2 — `:rf.error/no-frame-context` (at a dispatch)"

    An event was dispatched with no frame in scope. The classic case is a top-of-namespace `dispatch`, which runs at *load* time — before any frame exists. Boot-time events belong inside `run`, under `with-frame`, after `reg-frame`.

**Move 4 — `frame-provider-existing` wraps the tree.** The provider carries the already-registered `:rf/default` frame down through React context, so every bare `dispatch` / `subscribe` inside a `reg-view` body resolves to it without naming it — much like a React context provider you've used before. (`defonce` guards the root because a hot reload must not call `create-root` twice on the same element.)

!!! warning "Failure mode 3 — `:rf.error/no-frame-context` (at a subscribe)"

    Same id, different site: the tree rendered *without* the provider, so the first `subscribe` in a view has no frame to read. No fallback exists underneath — the fix is to wrap the root.

!!! warning "Failure mode 4 — `:rf.error/no-such-handler`"

    A dispatch reached the runtime but nothing is registered under that id. Once the app spans files (Part 1 on), the usual cause isn't a typo — it's a feature namespace never `:require`d from `core`, so its registrations never ran. (Sibling `:rf.error/no-such-fx`: the same story for an effect — a side-effect the framework performs for you — which is why Part 2 requires the HTTP artefact at boot, so its effects register.)

> **Coming from re-frame v1?** Moves 2–4 are the new part: there is no implicit global frame anymore, so the app says — once, at the root — which frame it runs in. Everything else in this file should look familiar.

## `npm install` to pixels

```bash
cd conduit
npm install          # shadow-cljs + React           (~30s)
npm run dev          # first compile                 (~60–90s)
```

When the build reports `Build completed`, open **<http://localhost:8020>**. You should see the green Conduit banner, the navbar with **Sign in** / **Sign up** — and Xray already open in the right rail. That's the gate: pixels, inspector attached, inside five minutes. If you got an error instead, match its `:rf.error/*` id against the four failure modes above and you'll find the fix.

## Minute one: open Xray

Xray auto-opened with the app, and `Ctrl+Shift+C` toggles it. Take a moment to look at what minute one already gives you:

- **The event spine** shows one row: `:app/initialise`. That's not a log line you wrote — it's the runtime's own record of the only thing that has happened so far.
- **app-db** shows `{:session {:user nil}}` — exactly the value the boot event returned.

One event, one state, nothing else. **Keep Xray open for the whole tutorial.** Every part runs the rhythm *do → observe → explain*, and Xray is the observe step — so when something misbehaves, you won't reach for print statements, you'll just read what the app actually did. [Debug with Xray](../how-to/debug-with-xray.md) is the deeper tour when you want it.

**You can now:**

- Scaffold a re-frame2 project on the real toolchain: `deps.edn`, `package.json`, `shadow-cljs.edn`, a host page.
- Boot an app honestly, and say what each move does: `init!` (substrate), `reg-frame` (the app's frame), `with-frame` + `dispatch-sync` (seed before first render), `frame-provider-existing` (carry the frame down the tree).
- Diagnose the four first-render failures by id: `:rf.error/no-adapter-installed`, `:rf.error/no-frame-context` (dispatch- and subscribe-side), `:rf.error/no-such-handler`, `:rf.error/no-such-fx`.
- Run the app with Xray attached and read your first event row.
