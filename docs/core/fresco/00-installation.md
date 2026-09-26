# Installation

This page adds Fresco to a browser build and mounts a small counter. The
example establishes the three forms used
throughout the guide: [`h/defview`](glossary.md#defview) for a view,
[`h/sub`](glossary.md#hsub) for a subscription read, and event vectors for
ordinary handlers.

There is no Fresco variant of the re-frame2 app template — it scaffolds
`:reagent` and `:uix`, and neither emits Fresco views. Build by hand from this
chapter instead: it names every file a Fresco project needs, and the result
boots.

## Add the dependencies

!!! note "Fresco ships in the re-frame2 release set"

    `day8/re-frame2-fresco` is one of the coordinates every re-frame2 release
    publishes, at the same version as `day8/re-frame2` itself, so pin them as
    a set. Until a release, every re-frame2 artefact resolves from source, and
    this page resolves Fresco — and `day8/re-frame2` with it — from a checkout
    using `:local/root`. Once you are on a release, the one `:local/root` entry
    below becomes an ordinary `{:mvn/version …}` coordinate and nothing else
    on this page changes.

`:local/root` is relative to *your* `deps.edn`, so clone the monorepo **beside**
your project directory — the convention the rest of the docs use:

```bash
cd ..                                             # the folder holding your project
git clone https://github.com/day8/re-frame2.git
cd my-app
```

Then add the Fresco artifact to `deps.edn`. One coordinate is the whole of it:
Fresco ships its own [substrate adapter](#fresco-needs-a-substrate-adapter),
so there is no second dependency to add for the reactive plumbing.

```clojure
;; deps.edn — resolved from a re-frame2 checkout beside your project
{:paths ["src"]
 :deps  {day8/re-frame2-fresco {:local/root "../re-frame2/implementation/fresco"}}

 ;; shadow-cljs reads its classpath from this file, so the compiler is a
 ;; dependency here as well as an npm package below.
 :aliases
 {:shadow {:extra-deps {thheller/shadow-cljs {:mvn/version "3.4.10"}}}}}
```

The Fresco artifact brings `day8/re-frame2` with it. React and the shadow-cljs
launcher come from npm:

```json
{
  "dependencies":    {"react": "19.3.0", "react-dom": "19.3.0"},
  "devDependencies": {"shadow-cljs": "3.4.10"}
}
```

```bash
npm install
```

Pin React at 19.2 or newer. Fresco's lifecycle contract is written against
[`<Activity>`](https://react.dev/reference/react/Activity), which shipped in
19.2, and the reference implementation runs and tests against 19.3.0. React 18
is not supported: nothing tests Fresco there, and `<Activity>` does not exist.
Write the version out, because a bare `npm install react react-dom` resolves to
whatever is current that day.

Keep `shadow-cljs` in `devDependencies` even though the JVM dependency above is
what compiles. The npm package supplies the `process` shim React's CommonJS
build asks for; without it the build stops at
`The required JS dependency "process" is not available`.

Fresco interprets Hiccup at runtime, so it needs no compiler hook, macro
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

The main namespace is `re-frame.fresco`, conventionally required as `h`.
Forms, overlays, motion, the native-React hooks, server rendering, and the test
kit live in separate namespaces (`re-frame.fresco.forms`,
`re-frame.fresco.overlay`, `re-frame.fresco.motion`, `re-frame.fresco.native`,
`re-frame.fresco.server`, `re-frame.fresco.test.*`). A build that does not
require an optional namespace does not include its code.

## Supported versions

**Tested** means a gate in the re-frame2 repository runs Fresco against that
combination. **Expected** means the code has no version-conditional branch that
would make it fail there, but nothing measures it.

| Combination | Tested | Expected, but unmeasured |
| --- | --- | --- |
| React and react-dom | 19.3.0, on every browser and Node lane Fresco runs | Later 19.x. 19.2 is the minimum, because `<Activity>` shipped there; 18 and earlier are not supported |
| Browser engine | Chromium, on the headless DOM lane. Firefox and WebKit, whenever a change touches Fresco | Any other engine or version, since Fresco targets React's DOM behaviour rather than any one browser |
| ClojureScript and shadow-cljs | 1.12.145 and 3.4.10 | Nothing else is measured |
| re-frame2 core and `re-frame2-ssr` | The same checkout as Fresco, which `:local/root` guarantees | No mixed versions: every release ships all three at one version, so pin them as a set |

The full matrix — every row above, plus the platform axis and the named CI job
or explicit untested-but-expected label behind each one — is maintained in the
repository as the Fresco release policy, under
`docs/design/fresco/product/release-policy.md`. That page is a working design
record rather than part of this site, so it is read from a checkout.

Before 1.0, an upgrade may rename a public function. Every published artifact
ships at the same version, and there are no back-compatibility shims: a renamed
or removed name is a compile error at your call site rather than a deprecation
warning, so the compiler lists every site that has to change. Error and warning
ids are stable: an id never changes meaning or spelling, and a retired one is
never reused, because stored errors and monitoring rules outlive the code that
raised them. The [complaint index](troubleshooting.md#the-complaint-index)
lists them.

## Fresco needs a substrate adapter

Fresco is a view layer, not a [substrate](../glossary.md#substrate). It owns
Hiccup interpretation and the render boundary; the reactive container app-db
lives in comes from an [adapter](../glossary.md#adapter), and re-frame2 installs
none for you. So every Fresco application calls
[`rf/init!`](../glossary.md#init) with an adapter as the first line of boot,
before it mounts anything:

```clojure
(rf/init! substrate/adapter)
```

`re-frame.fresco.substrate` ships inside `day8/re-frame2-fresco`, so that
line needs no extra dependency. It is a separate namespace rather than a name
on `h` so that an application installing a different adapter never loads it.

Skipping it fails loudly. `h/frame-root` ensures its frame, creating a frame
asks the adapter for a state container, and a container requested before
`init!` throws:

```text
rf/make-state-container was called before (rf/init! ...); require an adapter
ns and pass its `adapter` Var, e.g. (rf/init! reagent/adapter).
[:rf.error/no-adapter-installed]
```

The adapter is the only line that changes between substrates; you write
Fresco views either way and never call the adapter yourself. Use the Reagent or
UIx adapter instead when the page also renders that library's own components —
see [Use UIx or reagent-slim](../how-to/use-uix-or-slim.md) for their
coordinates. Every React-based adapter provides the same frame context, so a
Fresco subtree and a Reagent or UIx subtree on one page resolve the same frame.
Do not use the headless plain-atom adapter for a browser app: its
subscriptions never notify, so views never re-render.

Sharing a frame does not let you mix notations inside one tree. A Reagent view
is not a legal Hiccup head, so `[reagent-footer]` inside a Fresco body raises
`:rf.error/fresco-bad-head`. Mix the two layers at a root instead: give each
its own root naming the same frame, as in [More than one
root](#more-than-one-root). Embedding a Fresco view inside a Reagent, UIx or
React parent is covered in
[Interop](09-interop.md#render-a-fresco-view-from-native-react).

## Mount a first screen

A production application normally separates registrations and views into
several namespaces. This complete example keeps them together so the boot
sequence is visible — adapter first, then the root, inside the one `init` the
build calls:

```clojure
(ns counter.core
  (:require [re-frame.core :as rf]
            [re-frame.fresco.substrate :as substrate]
            [re-frame.fresco :as h]))

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

(defonce app-root (h/client-root))

(defn ^:dev/after-load mount! []
  ;; Render the whole tree, frame-root included. On a hot reload the
  ;; frame-root finds its frame already live and reuses it.
  (h/render! app-root
             [h/frame-root {:id             :rf/default
                            :initial-events [[:counter/initialise]]}
              [counter]]
             (js/document.getElementById "app")))

(defn ^:export init []
  (rf/init! substrate/adapter)
  (mount!)
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

The example uses three Fresco rules:

- `h/defview` creates a Hiccup head. Render it as `[counter]` or
  `[counter {}]`; do not call it as `(counter {})`. Use a plain `defn` for
  markup that should inline into its caller.
- `h/sub` reads during the synchronous view body. It is legal inside a `let`,
  conditional, loop, or ordinary helper called by that body.
- `{:on-click [:counter/increment]}` is an
  [intent](glossary.md#intent). The runtime creates the callback and dispatches
  the event vector to this root's frame.

## What the boot creates

Two things. [`h/render!`](glossary.md#mount) makes the React root: the first
call through a handle takes the tree and a DOM node (plus optional root
options) and creates the root. `[h/frame-root {:id ...}]` makes the **frame**
— its own app-db, event queue and subscription cache — and scopes it to
everything beneath it in the tree.

`h/render!` is also the hot-reload hook, which is why `mount!` above carries
`^:dev/after-load` and `init` just calls it. Every later `h/render!` through
`app-root` updates the root it already owns instead of creating another, so
the frame, its app-db and its subscriptions carry on. There is no separate
"create" call that could build a second root by mistake.

`h/frame-root` **ensures** the frame it names: it creates it if it does not
exist, or reuses the live one as it stands. `:initial-events` run once, in
order, when the frame is created. They complete before the first paint, so
there is no empty initial render: the frame is created in a layout effect and
`h/render!` renders inside `flushSync`, so the call returns with the seeded
markup already on the page. Initial state arrives through events; there is no
separate `:db` seed option.

Reagent and UIx boots use the same `frame-root` / `frame-provider` pair, so a
Fresco boot reads like theirs.

`[counter]` and `[counter {}]` are equivalent. The body receives an empty props
map in either case.

Keep the handle for later renders and teardown. Every `h/render!` takes the
whole tree, `frame-root` included, because the frame lives in the tree: a
re-render that drops the head renders a root with no frame under it. Pass the
head the same options each time. A mounted `frame-root` scopes one frame for
its lifetime, so re-rendering it with a changed option map — for example,
dropping `:initial-events` because they have already run — raises
`:rf.error/frame-root-reconfigured`. That is why `mount!` above renders the same
form on every call.

```clojure
(h/render! app-root
           [h/frame-root {:id             :rf/default
                          :initial-events [[:counter/initialise]]}
            [counter]]
           (js/document.getElementById "app"))
(h/unmount! app-root)
```

The DOM node and root options are read on the first call only; later calls
accept them and ignore them. `h/unmount!` is idempotent, because teardown can
be reached independently by fixtures, reload hooks, and `finally` blocks. It releases the root's
subscriptions and leaves the DOM node empty, ready for another root. It does
not destroy the frame; `rf/destroy-frame!` does that.

Fresco reports a mistake by throwing, and React unmounts a root whose tree
throws with no error boundary above it — the whole page, not the offending
region, with the error only in the console. Put
`h/error-boundary` around the regions a user can carry on without, which for
most applications means a route's main content rather than the root itself;
[Errors](17-errors.md#place-boundaries-at-useful-recovery-regions) is the whole
rule and [Routing and navigation](07-routing-and-navigation.md#move-focus-after-a-page-change)
shows it in a routed root.

### A frame that needs more than a seed

`h/frame-root` takes the whole `rf/make-frame` option map: `:url-bound? true`
for an application that owns the browser URL, `:fx-overrides` for a stubbed
backend, `:images`, and the rest. All of them go on the `frame-root` head:

```clojure
(defn ^:export init []
  (rf/init! substrate/adapter)
  (h/render! app-root
             [h/frame-root {:id             :app/main
                            :url-bound?     true
                            :initial-events [[:app/initialise]]}
              [main-screen]]
             (js/document.getElementById "app"))
  nil)
```

The frame is named and configured in one place. `h/render!`'s own options are
for the React root only; handing it a frame option throws rather than silently
dropping it (see [Troubleshooting](#troubleshooting)).

[Routing and navigation](07-routing-and-navigation.md#boot-a-routed-application)
walks the routed case, which is the common one — a frame owns the browser URL
only by carrying `:url-bound? true`, and nothing supplies it by default.

## More than one root

A page can mount several Fresco roots. The frame id determines whether those
roots share an application.

### Two roots sharing one frame

The first root's `h/frame-root` creates and seeds the frame. A later root joins
it with `h/frame-provider`, which scopes an existing frame and creates,
reconfigures and destroys nothing:

```clojure
;; Two roots, so two handles: one handle owns at most one root at a time.
(defonce app-root (h/client-root))
(defonce status-root (h/client-root))

(defn ^:export init []
  (rf/init! substrate/adapter)
  (h/render! app-root
             [h/frame-root {:id             :app/main
                            :initial-events [[:app/initialise]]}
              [main-screen]]
             (js/document.getElementById "app")
             {:identifier-prefix "main"})
  (h/render! status-root
             [h/frame-provider {:frame :app/main}
              [connection-badge]]
             (js/document.getElementById "status")
             {:identifier-prefix "status"})
  nil)
```

Both roots read the same app-db and dispatch into the same queue. Give each
root a distinct `:identifier-prefix`: React numbers `useId` values per root from
the same start, so two unprefixed roots generate colliding ids. A page with one
root needs no prefix.

Use `frame-provider` rather than a second `frame-root` to join. A second
`frame-root` naming a live `:id` does not replay `:initial-events`, but it
does replace the frame's configuration with its own option map, silently
dropping whatever the first one configured. `frame-provider` also throws
`:rf.error/frame-provider-frame-absent` if the frame does not exist yet, which
catches a root booted before the one that creates its frame.

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

The DOM of reloaded views does not survive. A reload re-evaluates the
namespace, so every `h/defview` in it becomes a new component type, as does
each `h/as-component` result. React remounts a component whose type changed,
so the re-render rebuilds those nodes instead of updating them. State held in
app-db comes back untouched; state the DOM itself owns does not. Focus and the
caret are the two you notice: edit a form's markup while typing in that form
and the draft survives in app-db while the cursor leaves the field. Nothing is wrong when that happens, and it is not a reason to reach for
`defonce` on a view.

Boot and re-render are two functions because shadow-cljs calls `:init-fn`
once, when the module loads, and not again after a reload. A build whose only
entry point is `:init-fn` logs `reloading code but no
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
| A mount throws `:rf.error/no-adapter-installed`, naming `rf/make-state-container` | No [adapter](#fresco-needs-a-substrate-adapter) is installed: `rf/init!` never ran, or ran after the mount | Make `(rf/init! substrate/adapter)` the first line of boot |
| `(counter {})` throws | A `defview` is a React component used as a Hiccup head, not a directly callable helper | Render `[counter {}]`. Use a plain `defn` for inline markup |
| `h/sub` in a callback, timer, or promise throws | `:rf.error/fresco-sub-outside-render`: the read happened outside a synchronous view body | Read during the body and close over the value. Async work should read state through events and coeffects |
| The first paint is empty and then fills in | Initial state was dispatched after mounting | Put the seed events in `h/frame-root`'s `:initial-events` so they finish before the first paint |
| A hot reload replaced the whole tree instead of updating it | A new handle was allocated on reload, so its first `h/render!` created a second root | Allocate the handle with `defonce`, so a reload re-evaluates the namespace without replacing the handle |
| A second root's `:initial-events` never run | The named frame already exists; `frame-root` reuses it without re-seeding | Seed only from the root that creates the frame, and join it with `h/frame-provider` |
| `h/render!` throws `:rf.error/fresco-frame-config-misplaced` | `:frame` or `:initial-events` was passed in `h/render!`'s options; frame configuration belongs on `h/frame-root` | Move it to `[h/frame-root {:id … :initial-events …}]` ([above](#a-frame-that-needs-more-than-a-seed)) |
| `h/render!` throws `:rf.error/fresco-unknown-root-option` | `h/render!`'s options accept only `:hydrate?` and `:identifier-prefix`, and throw on any other key | Put every `rf/make-frame` option on `h/frame-root` |
| One failing view blanks the whole page | Fresco reports mistakes by throwing, and React unmounts a root that throws with no boundary above it | Wrap independently recoverable regions with `h/error-boundary` ([Errors](17-errors.md)) |
| A changed initialisation handler has no effect after hot reload | The live frame kept its existing app-db | Reload, recreate the frame, or dispatch an explicit reset event |
| A view body runs twice when first mounted in development | The tree is under React `StrictMode`, which calls bodies twice in development | Expected. Keep view bodies pure and safe to re-run |
