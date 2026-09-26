# Installation

This page adds Fresco to a browser build and mounts the counter from the
[Core introduction](../introduction.md) as a Fresco view. It uses the three
forms the rest of the guide builds on: [`h/defview`](glossary.md#defview) for a
view, [`h/sub`](glossary.md#hsub) for a subscription read, and event vectors
for ordinary handlers.

The re-frame2 app template scaffolds `:reagent` and `:uix` projects only, so a
Fresco project is set up by hand. This page names every file it needs.

## Add the dependencies

!!! note "Fresco ships in the re-frame2 release set"

    `day8/re-frame2-fresco` is published with every re-frame2 release, at the
    same version as `day8/re-frame2`, so pin them as a set. Until a release,
    every re-frame2 artefact resolves from source, and this page uses
    `:local/root` against a checkout. On a release, that one entry becomes an
    ordinary `{:mvn/version …}` coordinate and nothing else on this page
    changes.

`:local/root` is relative to *your* `deps.edn`, so clone the monorepo beside
your project directory:

```bash
cd ..                                             # the folder holding your project
git clone https://github.com/day8/re-frame2.git
cd my-app
```

Then add Fresco to `deps.edn`. It brings `day8/re-frame2` with it and ships its
own [substrate adapter](#fresco-needs-a-substrate-adapter), so this is the only
re-frame2 coordinate you need:

```clojure
;; deps.edn — resolved from a re-frame2 checkout beside your project
{:paths ["src"]
 :deps  {day8/re-frame2-fresco {:local/root "../re-frame2/implementation/fresco"}}

 ;; shadow-cljs reads its classpath from this file, so the compiler is a
 ;; dependency here as well as an npm package below.
 :aliases
 {:shadow {:extra-deps {thheller/shadow-cljs {:mvn/version "3.4.10"}}}}}
```

React and the shadow-cljs launcher come from npm:

```json
{
  "dependencies":    {"react": "19.3.0", "react-dom": "19.3.0"},
  "devDependencies": {"shadow-cljs": "3.4.10"}
}
```

```bash
npm install
```

Pin React at 19.2 or newer and write the version out, because a bare
`npm install react react-dom` resolves to whatever is current that day.
Fresco's lifecycle relies on
[`<Activity>`](https://react.dev/reference/react/Activity), which shipped in
19.2; React 18 is not supported.

Keep `shadow-cljs` in `devDependencies` even though the JVM dependency above
does the compiling. The npm package supplies the `process` shim React's
CommonJS build needs; without it the build stops at
`The required JS dependency "process" is not available`.

Fresco interprets Hiccup at runtime, so it needs no compiler hook or build
flag. A normal shadow-cljs browser build is enough:

```clojure
;; shadow-cljs.edn
{:deps     {:aliases [:shadow]}
 :dev-http {8080 "public"}
 :builds   {:app {:target     :browser
                  :output-dir "public/js"
                  :asset-path "/js"
                  :modules    {:main {:init-fn counter.core/init}}}}}
```

`{:deps {:aliases [:shadow]}}` puts the compiler on the classpath. A bare
`{:deps true}` reads `deps.edn` without the alias and fails with
`Could not locate shadow/cljs/devtools/cli`.

```html
<!-- public/index.html -->
<!doctype html>
<html>
  <body>
    <div id="app"></div>
    <script src="/js/main.js"></script>
  </body>
</html>
```

## Mount a first screen

A real application splits registrations and views across namespaces. This
example keeps them together so the whole boot sequence is visible:

```clojure
(ns counter.core
  (:require [re-frame.core :as rf]
            [re-frame.fresco.substrate :as substrate]
            [re-frame.fresco :as h]))

(rf/reg-event :initialise (fn [_ [_ v]] {:db {:value v}}))
(rf/reg-event :inc        (fn [{:keys [db]} _] {:db (update db :value inc)}))
(rf/reg-sub   :value      (fn [db _] (:value db 0)))

(h/defview counter [_]
  [:div
   [:span "Count: " (h/sub [:value]) " "]
   [:button {:on-click [:inc]} "+"]])

(defonce app-root (h/client-root))

(defn ^:dev/after-load mount! []
  ;; Render the whole tree, frame-root included. On a hot reload the
  ;; frame-root finds its frame already live and reuses it.
  (h/render! app-root
             [h/frame-root {:id :app :initial-events [[:initialise 3]]}
              [counter]]
             (js/document.getElementById "app")))

(defn ^:export init []
  (rf/init! substrate/adapter)
  (mount!)
  nil)
```

Start the build and open the page:

```bash
npx shadow-cljs watch app
# http://localhost:8080
```

The main namespace is `re-frame.fresco`, conventionally required as `h`.
Compared with the Core counter, three things differ:

- `h/defview` defines the view. Render it as `[counter]` or `[counter {}]`;
  do not call it as `(counter {})`. Use a plain `defn` for markup that should
  inline into its caller.
- `h/sub` reads a subscription and returns the value directly, with no `@`.
  It works anywhere in the synchronous view body: in a `let`, a conditional, a
  loop, or a helper the body calls.
- `{:on-click [:inc]}` is an [intent](glossary.md#intent): an event vector in
  place of a function. Fresco creates the callback and dispatches `[:inc]` to
  the view's frame.

`init` is the build's `:init-fn`. It installs the adapter, then mounts.
Loading the namespace only registers handlers and defines views, so a test or
another namespace can require it without touching the DOM; [Boot and mount an
app](../how-to/boot-and-mount-an-app.md#the-small-shape) gives
that rule for every substrate.

## Fresco needs a substrate adapter

Fresco renders views; the reactive container app-db lives in comes from an
[adapter](../glossary.md#adapter), and re-frame2 installs none for you. So a
Fresco application calls [`rf/init!`](../glossary.md#init) with an adapter
before it mounts anything:

```clojure
(rf/init! substrate/adapter)
```

`re-frame.fresco.substrate` ships inside `day8/re-frame2-fresco`, so it needs
no extra dependency. Skipping the call fails on the first mount with
`:rf.error/no-adapter-installed`, because `h/frame-root` creates a frame and
the frame asks the adapter for a state container.

A page that also renders Reagent or UIx components can install that library's
adapter instead; see [Use another adapter](#use-another-adapter).

## What the boot creates

`h/render!` creates the React root, and `[h/frame-root {:id :app …}]` creates
the **frame**: its own app-db, event queue and subscription cache, scoped to
everything beneath it in the tree.

`h/frame-root` ensures the frame it names: it creates the frame if it does not
exist and reuses it if it does. `:initial-events` run once, in order, when the
frame is created, and finish before the first paint, so the page never shows
an empty first render. Initial state arrives through events; there is no
separate `:db` option. Reagent and UIx boots use the same
`frame-root` / `frame-provider` pair.

The first `h/render!` through a handle takes the tree and a DOM node (plus
optional root options) and creates the root. Every later call through the same
handle updates that root, so the frame, its app-db and its subscriptions carry
on. That is why `mount!` doubles as the hot-reload hook.

Pass the same tree, including the `frame-root` head and its options, on every
call. The frame lives in the tree, and `frame-root` checks its options against
the live frame ([Troubleshooting](#troubleshooting)).

```clojure
(h/unmount! app-root)
```

The DOM node and root options are read on the first call only. `h/unmount!`
releases the root's subscriptions and empties the DOM node. It is idempotent,
so fixtures, reload hooks and `finally` blocks can all call it. It does not
destroy the frame; `rf/destroy-frame!` does that.

Wrap regions a user can carry on without in `h/error-boundary`, usually a
route's main content. A throw with no boundary above it unmounts the whole
root. [Errors](17-errors.md#place-boundaries-at-useful-recovery-regions) has
the rule, and [Routing and
navigation](07-routing-and-navigation.md#move-focus-after-a-page-change) shows
it in a routed root.

### A frame that needs more than a seed

`h/frame-root` takes the whole `rf/make-frame` option map: `:url-bound? true`
for an application that owns the browser URL, `:fx-overrides` for a stubbed
backend, `:images`, and the rest. They all go on the `frame-root` head:

```clojure
(defn ^:dev/after-load mount! []
  (h/render! app-root
             [h/frame-root {:id             :app
                            :url-bound?     true
                            :initial-events [[:initialise 3]]}
              [counter]]
             (js/document.getElementById "app")))
```

`h/render!`'s own options are for the React root only, and it throws if handed
a frame option (see [Troubleshooting](#troubleshooting)).
[Routing and navigation](07-routing-and-navigation.md#boot-a-routed-application)
walks through the routed case.

## Hot reload

The `^:dev/after-load` hook calls `h/render!` with the redefined views. The
root, frame, app-db and subscriptions survive, so editing a view does not reset
the counter.

The DOM of reloaded views does not survive. Reloading a namespace makes every
`h/defview` in it a new component type, and React remounts a component whose
type changed. State in app-db comes back untouched; state the DOM owns, such
as focus and the caret, does not. Edit a form's markup while typing in it and
the draft survives while the cursor leaves the field. That is expected.

Boot and re-render are separate functions because shadow-cljs calls `:init-fn`
once, when the module loads. A build whose only entry point is `:init-fn` logs
`reloading code but no :after-load hooks are configured!` and keeps showing the
old view.

A changed initialisation handler does not re-seed a frame that is already
live. Reload the page, destroy the frame, or dispatch a reset event when you
need the new initial state.

## Production builds

```bash
npx shadow-cljs release app
```

Advanced compilation removes development-only warnings and Xray
instrumentation hooks. Optional namespaces that were never required add no
code. Hiccup interpretation behaves the same in development and production.

Forms, overlays, motion, native-React hooks, server rendering and the test kit
live in separate namespaces (`re-frame.fresco.forms`,
`re-frame.fresco.overlay`, `re-frame.fresco.motion`, `re-frame.fresco.native`,
`re-frame.fresco.server`, `re-frame.fresco.test.*`). A build includes only the
ones it requires.

## Supported versions

**Tested** means a gate in the re-frame2 repository runs Fresco against that
combination. **Expected** means no code path would make it fail there, but
nothing measures it.

| Combination | Tested | Expected, but unmeasured |
| --- | --- | --- |
| React and react-dom | 19.3.0, on every browser and Node lane Fresco runs | Later 19.x. 19.2 is the minimum; 18 and earlier are not supported |
| Browser engine | Chromium, on the headless DOM lane. Firefox and WebKit, whenever a change touches Fresco | Other engines and versions, since Fresco targets React's DOM behaviour |
| ClojureScript and shadow-cljs | 1.12.145 and 3.4.10 | Nothing else is measured |
| re-frame2 core and `re-frame2-ssr` | The same checkout as Fresco, which `:local/root` guarantees | No mixed versions: pin all three at one version |

The full matrix, with the CI job behind each row, is the Fresco release policy
at `docs/design/fresco/product/release-policy.md` in a checkout (it is a design
record, not part of this site).

Before 1.0, an upgrade may rename a public function. There are no
back-compatibility shims, so a renamed or removed name is a compile error at
each call site that has to change. Error and warning ids are stable: an id
never changes meaning or spelling, and a retired id is never reused. The
[complaint index](troubleshooting.md#the-complaint-index) lists them.

## Troubleshooting

`h/render!` returns normally when rendering fails: React 19 reports an error
raised while rendering or committing to the page's global error handler rather
than re-throwing it, so these show up in the console.

| Symptom | Cause | Fix |
| --- | --- | --- |
| The page stays empty and the console reports `:rf.error/no-adapter-installed`, naming `rf/make-state-container` | No [adapter](#fresco-needs-a-substrate-adapter) is installed: `rf/init!` never ran, or ran after the mount | Make `(rf/init! substrate/adapter)` the first line of boot |
| The page stays empty and the console reports `:rf.error/no-frame-context` | The tree has no `h/frame-root` or `h/frame-provider` head, for example a reload that renders `[counter]` without the head | Wrap it: `[h/frame-root {:id :app …} [counter {}]]` |
| A hot reload raises `:rf.error/frame-root-reconfigured` | The `h/frame-root` options changed between `h/render!` calls, for example `:initial-events` dropped after the first mount | Pass the identical `frame-root` head on every call; `:initial-events` do not re-run on a live frame |
| The page stays empty and the console reports `:rf.error/initial-events-step-failed` | An `:initial-events` handler threw, or a coeffect it requires is missing. Creating a frame is strict, so the half-built frame is torn down and nothing renders | Fix the event the error names (`:step-index`, `:event`) |
| `(counter {})` ignores its props, or fails with React's invalid-hook error | A `defview` is a React component used as a Hiccup head, not a function to call | Render `[counter {}]`. Use a plain `defn` for inline markup |
| `h/sub` in a callback, timer or promise throws `:rf.error/fresco-sub-outside-render` | The read happened outside a synchronous view body | Read during the body and close over the value. Async work reads state through events and coeffects |
| The first paint is empty and then fills in | Initial state was dispatched after mounting | Put the seed events in `h/frame-root`'s `:initial-events` |
| A hot reload replaced the whole tree instead of updating it | A new handle was allocated on reload, so its first `h/render!` created a second root | Allocate the handle with `defonce` |
| A second root's `:initial-events` never run | The frame already exists; `frame-root` reuses it without re-seeding | Seed from the root that creates the frame; join it with `h/frame-provider` |
| `h/render!` throws `:rf.error/fresco-frame-config-misplaced` | `:frame` or `:initial-events` was passed in `h/render!`'s options | Move it to `[h/frame-root {:id … :initial-events …}]` ([above](#a-frame-that-needs-more-than-a-seed)) |
| `h/render!` throws `:rf.error/fresco-unknown-root-option` | `h/render!` accepts only `:hydrate?` and `:identifier-prefix` | Put every `rf/make-frame` option on `h/frame-root` |
| One failing view blanks the whole page | React unmounts a root that throws with no boundary above it | Wrap recoverable regions with `h/error-boundary` ([Errors](17-errors.md)) |
| A changed initialisation handler has no effect after hot reload | The live frame kept its app-db | Reload, recreate the frame, or dispatch a reset event |
| A view body runs twice on first mount in development | React `StrictMode` calls bodies twice in development | Expected. Keep view bodies pure |

## Advanced

### More than one root

A page can mount several Fresco roots. The frame id decides whether they share
an application.

To share one frame, let the first root's `h/frame-root` create and seed it, and
join it from the others with `h/frame-provider`. `frame-provider` scopes an
existing frame and never creates, reconfigures or destroys one:

```clojure
;; Two roots, so two handles: a handle owns at most one root.
(defonce app-root (h/client-root))
(defonce status-root (h/client-root))

(h/defview status-badge [_]
  [:span "Count: " (h/sub [:value])])

(defn ^:dev/after-load mount! []
  (h/render! app-root
             [h/frame-root {:id :app :initial-events [[:initialise 3]]}
              [counter]]
             (js/document.getElementById "app")
             {:identifier-prefix "main"})
  (h/render! status-root
             [h/frame-provider {:frame :app}
              [status-badge]]
             (js/document.getElementById "status")
             {:identifier-prefix "status"}))

(defn ^:export init []
  (rf/init! substrate/adapter)
  (mount!)
  nil)
```

Both roots read the same app-db and dispatch into the same queue. Give each a
distinct `:identifier-prefix`, because React numbers `useId` values per root
from the same start. A page with one root needs no prefix.

Join with `frame-provider` rather than a second `frame-root`. A second
`frame-root` naming a live `:id` does not replay `:initial-events`, but it does
replace the frame's configuration with its own, dropping whatever the first
configured. `frame-provider` throws `:rf.error/frame-provider-frame-absent` if
the frame does not exist yet, which catches a root booted too early.
Unmounting one root does not destroy state another root still uses.

To isolate roots, give each its own frame id, for example `:todos/work` and
`:todos/home` for two todo lists that share view code but not state. Each frame
has its own app-db, queue and subscription cache, and a view reads from the
frame of the root that renders it.

### Use another adapter

Every React-based adapter provides the same frame context, so a page that also
renders Reagent or UIx components can install that library's adapter in place
of `substrate/adapter` and keep writing Fresco views; [Use UIx or
reagent-slim](../how-to/use-uix-or-slim.md) has their coordinates. Do not use
the headless plain-atom adapter in a browser: its subscriptions never notify,
so views never re-render.

A shared frame does not let you mix notations in one tree. A Reagent view is
not a legal Hiccup head, so `[reagent-footer]` inside a Fresco body raises
`:rf.error/fresco-bad-head`. Give each layer its own root naming the same
frame, as in [More than one root](#more-than-one-root). To embed a Fresco view
in a Reagent, UIx or React parent, see
[Interop](09-interop.md#render-a-fresco-view-from-native-react).
