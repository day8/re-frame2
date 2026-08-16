# Installation

Chapter `00` of the Hicasso draft guide. This page adds Hicasso to a browser
build and mounts a small counter. The example establishes the three forms used
throughout the guide: [`h/defview`](glossary.md#defview) for a view,
[`h/sub`](glossary.md#hsub) for a subscription read, and event vectors for
ordinary handlers.

## Add the dependencies

!!! warning "Pre-alpha: no Clojars coordinate"

    `day8/re-frame2-hicasso` is **not published**, and there is no date at
    which it will be. It lives in the re-frame2 monorepo, so today you resolve
    it — and `day8/re-frame2` with it — from a checkout using `:local/root`.
    The snippet below resolves against a clone on your own disk, and becomes
    an ordinary Maven coordinate the day Hicasso publishes.

`:local/root` is relative to *your* `deps.edn`, so clone the monorepo **beside**
your project directory — the convention the rest of the docs use:

```bash
cd ..                                             # the folder holding your project
git clone https://github.com/day8/re-frame2.git
cd my-app
```

Then add the Hicasso artifact and a [substrate
adapter](#hicasso-needs-a-substrate-adapter) to `deps.edn`:

```clojure
;; deps.edn — resolved from a re-frame2 checkout beside your project
{:paths ["src"]
 :deps  {day8/re-frame2-hicasso {:local/root "../re-frame2/implementation/hicasso"}
         day8/re-frame2-uix     {:local/root "../re-frame2/implementation/adapters/uix"}}

 ;; shadow-cljs reads its classpath from this file, so the compiler is a
 ;; dependency here as well as an npm package below.
 :aliases
 {:shadow {:extra-deps {thheller/shadow-cljs {:mvn/version "3.4.10"}}}}}
```

The Hicasso artifact brings `day8/re-frame2` with it. React and the shadow-cljs
launcher come from npm:

```json
{
  "dependencies":    {"react": "19.2.0", "react-dom": "19.2.0"},
  "devDependencies": {"shadow-cljs": "3.4.10"}
}
```

```bash
npm install
```

Both npm lines earn their place. **Pin React**: Hicasso needs 18 or newer — it
mounts through `createRoot`, and `:identifier-prefix` sets what `useId` answers
— and 19.2 is what the reference implementation runs and what this chapter is
checked against, whereas a bare `npm install react react-dom` resolves to
whatever is current that day. **And keep `shadow-cljs` in `devDependencies`**
even though the JVM dependency above is what compiles: the npm package is where
the `process` shim React's CommonJS build asks for comes from, and without it
the build stops at `The required JS dependency "process" is not available`.

Hicasso interprets Hiccup at runtime, so it needs no compiler hook, macro
allow-list, or build flag. A normal shadow-cljs browser build is enough:

```clojure
;; shadow-cljs.edn
{:deps     {:aliases [:shadow]}
 :dev-http {8080 "public"}
 :builds   {:app {:target     :browser
                  :output-dir "public/js"
                  :asset-path "/js"
                  :modules    {:main {:init-fn counter.core/init}}}}}
```

`{:deps {:aliases [:shadow]}}` is what puts the compiler on the classpath. A
bare `{:deps true}` reads `deps.edn` without the alias, finds no
`thheller/shadow-cljs` there, and dies before it compiles anything:
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

The main namespace is `re-frame.hicasso`, conventionally required as `h`.
Forms, overlays, motion, routing, the native tier, and the test kit use separate
namespaces. A build that does not require an optional module does not include
its code.

## Hicasso needs a substrate adapter

Hicasso is a view layer, not a [substrate](../glossary.md#substrate). It owns
Hiccup interpretation and the render boundary; the reactive container app-db
lives in comes from an [adapter](../glossary.md#adapter), and re-frame2 installs
none for you. **So every Hicasso application calls
[`rf/init!`](../glossary.md#init) with an adapter before it mounts anything** —
one line, on the first line of boot:

```clojure
(rf/init! uix-adapter/adapter)
```

It is not optional and it does not fail quietly. `h/mount!` ensures its frame,
creating a frame asks the adapter for a state container, and a container asked
for before `init!` throws:

```text
rf/make-state-container was called before (rf/init! ...); require an adapter
ns and pass its `adapter` Var, e.g. (rf/init! reagent/adapter).
[:rf.error/no-adapter-installed]
```

*Which* adapter is your choice, and it is the only line that changes between
substrates — see [Use UIx or reagent-slim](../how-to/use-uix-or-slim.md) for the
three and their coordinates. This guide's recipes pass `uix-adapter/adapter`,
because that is what Hicasso's own test lane and witnesses run on. It buys
plumbing, not notation: you write Hicasso views either way and never call the
adapter yourself. The headless plain-atom adapter is not a substitute for a
browser app — its derived values are not `IWatchable`, so a subscription under
it notifies nothing.

## Mount a first screen

A production application normally separates registrations and views into
several namespaces. This complete example keeps them together so the boot
sequence is visible — adapter first, then the root, inside the one `init` the
build calls:

```clojure
(ns counter.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso :as h]))

(rf/reg-event :counter/initialise
  (fn [_cofx _event]
    {:db {:count 0}}))

(rf/reg-event :counter/increment
  (fn [{:keys [db]} _event]
    {:db (update db :count inc)}))

(rf/reg-sub :counter/count
  (fn [db _query]
    (:count db)))

(h/defview counter [_]
  [:main
   [:h1 "Clicked " (h/sub [:counter/count]) " times"]
   [:button {:on-click [:counter/increment]} "Click me"]])

(defonce !root (atom nil))

(defn ^:dev/after-load rerender! []
  (when-some [root @!root]
    (h/render! root [counter])))

(defn ^:export init []
  (rf/init! uix-adapter/adapter)
  (reset! !root
          (h/mount! (js/document.getElementById "app")
                    {:frame          :rf/default
                     :initial-events [[:counter/initialise]]}
                    [counter]))
  nil)
```

`init` is the build's `:init-fn`, wired in `shadow-cljs.edn` above. Namespace
load registers handlers and defines views and touches no DOM, so a test host, a
Story tool, or another namespace can require this one for its registrations
alone — the rule [Boot and mount an
app](../how-to/boot-and-mount-an-app.md#no-dom-work-at-namespace-load) states
for every substrate.

Start the build and open the page:

```bash
npx shadow-cljs watch app
# http://localhost:8080
```

The example uses three Hicasso rules:

- `h/defview` creates a Hiccup head. Render it as `[counter]` or
  `[counter {}]`; do not call it as `(counter {})`. Use a plain `defn` for
  markup that should inline into its caller.
- `h/sub` reads during the synchronous view body. It is legal inside a `let`,
  conditional, loop, or ordinary helper called by that body.
- `{:on-click [:counter/increment]}` is an
  [intent](glossary.md#intent). The runtime creates the callback and dispatches
  the event vector to this root's frame.

## What `h/mount!` creates

[`h/mount!`](glossary.md#mount) receives a DOM node, a root configuration, and
one view form. Its `:frame` identifies the re-frame2 frame used by that root.
A frame has its own app-db, event queue, and subscription cache.

Mounting **ensures** the named frame: it creates the frame if it does not exist,
or joins the existing frame if another root already uses it.
`:initial-events` run once, in order, when that mount creates the frame. They
complete before the first paint, which prevents an empty initial render.
Initial state still arrives through events; Hicasso does not add a separate
`:db` seed option.

`[counter]` and `[counter {}]` are equivalent. The body receives an empty props
map in either case.

Keep the returned handle for later renders and teardown:

```clojure
(h/render! root [counter])
(h/unmount! root)
```

`h/unmount!` is idempotent. A second call is a no-op because teardown can be
reached independently by fixtures, reload hooks, and `finally` blocks. The
unmount releases the root's subscriptions as their reference counts reach
zero and makes the DOM node available for another root.

## More than one root

A page can mount several Hicasso roots. The frame id determines whether those
roots share an application.

### Two roots sharing one frame

The first root creates and seeds the frame. A later root that names the same
frame joins its current state and does not replay `:initial-events`:

```clojure
(defonce !app-root (atom nil))
(defonce !status-root (atom nil))

(defn ^:export init []
  (rf/init! uix-adapter/adapter)
  (reset! !app-root
          (h/mount! (js/document.getElementById "app")
                    {:frame          :app/main
                     :initial-events [[:app/initialise]]}
                    [main-screen]))
  (reset! !status-root
          (h/mount! (js/document.getElementById "status")
                    {:frame :app/main}
                    [connection-badge]))
  nil)
```

Both roots read the same app-db and dispatch into the same queue. Seed from the
root that creates the frame; joining roots should omit `:initial-events`.

Unmounting one root does not destroy state still used by another root. Teardown
remains per root.

### Two roots using different frames

Give each root a different frame id when they must be isolated. Each frame then
has its own app-db, queue, and subscription cache. A view reads under the frame
of the root that renders it, and subscriptions never cross frames.

This is suitable for cases such as an editor and a live preview that use the
same view code but must not share state.

## Hot reload

The `^:dev/after-load` hook calls `h/render!` with the redefined view. The root,
frame, app-db, and subscriptions survive, so changing the view does not reset
the counter or leak registrations.

Boot and re-render are two functions rather than one because shadow calls
`:init-fn` **once**, when the module loads, and not again after a reload — a
build whose only entry point is `:init-fn` logs `reloading code but no
:after-load hooks are configured!` and leaves the page showing the old view.
Mount in `init`; re-render in the hook.

Hot reload also means a changed initialisation handler does not re-seed an
already live frame. Reload the page, destroy the frame, or dispatch an explicit
reset event when you need the new initial state.

## Production builds

Build the release normally:

```bash
npx shadow-cljs release app
```

Advanced compilation removes development-only warnings, warning strings, and
Xray instrumentation hooks. Optional modules that were never required add no
code. The Hiccup interpretation itself has the same meaning in development and
production; there is no production-only view mode.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A mount throws `:rf.error/no-adapter-installed`, naming `rf/make-state-container` | No [adapter](#hicasso-needs-a-substrate-adapter) is installed: `rf/init!` never ran, or ran after the mount | Make `(rf/init! uix-adapter/adapter)` the first line of boot |
| `(counter {})` throws and names the view | A `defview` is a Hiccup head, not a directly callable helper | Render `[counter {}]`. Use a plain `defn` for inline markup |
| `h/sub` in a callback, timer, or promise throws | `:rf.error/hicasso-sub-outside-render`: the read happened outside a synchronous view body | Read during the body and close over the value. Async work should read state through events and coeffects |
| The first paint is empty and then fills in | Initial state was dispatched after mounting | Put the seed events in `:initial-events` so they finish before the first paint |
| A second `h/mount!` fails on the same DOM node | A live root already owns the node | Keep the handle with `defonce`; unmount that root before mounting another |
| A joining root's `:initial-events` never run | The named frame already exists; joining does not replay setup | Seed only from the mount that creates the frame |
| A changed initialisation handler has no effect after hot reload | The live frame kept its existing app-db | Reload, recreate the frame, or dispatch an explicit reset event |
| A view body runs twice when first mounted in development | React StrictMode probes bodies twice | Expected. Keep view bodies pure and safe to re-run |
