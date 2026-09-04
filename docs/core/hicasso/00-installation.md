# Installation

Chapter `00` of the Hicasso draft guide. This page adds Hicasso to a browser
build and mounts a small counter. The example establishes the three forms used
throughout the guide: [`h/defview`](glossary.md#defview) for a view,
[`h/sub`](glossary.md#hsub) for a subscription read, and event vectors for
ordinary handlers.

There is no Hicasso variant of the re-frame2 app template — it scaffolds
`:reagent` and `:uix`, and neither emits Hicasso views. Build by hand from this
chapter instead: it names every file a Hicasso project needs, and the result
boots.

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

Then add the Hicasso artifact to `deps.edn`. One coordinate is the whole of it:
Hicasso ships its own [substrate adapter](#hicasso-needs-a-substrate-adapter),
so there is no second dependency to add for the reactive plumbing.

```clojure
;; deps.edn — resolved from a re-frame2 checkout beside your project
{:paths ["src"]
 :deps  {day8/re-frame2-hicasso {:local/root "../re-frame2/implementation/hicasso"}}

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

Both npm lines earn their place. **Pin React, and pin it at 19.2 or newer.**
Hicasso mounts through `createRoot` and lets `:identifier-prefix` decide what
`useId` answers, both of which React has offered since 18 — but the lifecycle
contract Hicasso holds itself to is written against
[`<Activity>`](https://react.dev/reference/react/Activity), which shipped in
19.2, and 19.2 is the pair the reference implementation runs and this chapter is
checked against. **React 18 is not supported**: nothing tests Hicasso there, and
the Activity half of the contract has nothing to run on. A bare
`npm install react react-dom` resolves to whatever is current that day, which is
the other half of why the pin is written out. **And keep `shadow-cljs` in
`devDependencies`** even though the JVM dependency above is what compiles: the
npm package is where the `process` shim React's CommonJS build asks for comes
from, and without it the build stops at
`The required JS dependency "process" is not available`.

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
Forms, overlays, motion, routing, the island hooks, and the test kit use separate
namespaces. A build that does not require an optional module does not include
its code.

## Supported versions

The pins above are not arbitrary, and this table says what stands behind each of
them. **Tested** means a gate in the re-frame2 repository runs Hicasso against
that combination. **Expected** means the code has no version-conditional branch
that would make it fail there, but nothing measures it — a reasonable bet rather
than a promise. Nothing here is forbidden; the distinction is only between what
is checked and what is not.

| Combination | Tested | Expected, but unmeasured |
| --- | --- | --- |
| React and react-dom | 19.2.0, on every browser and Node lane Hicasso runs | Later 19.x, for the render boundary and the two-hook contract. Below 19.2 the `<Activity>` lifecycle rows have nothing to run on, and 18 and earlier is not supported at all |
| Browser engine | Chromium, on the headless DOM lane. Firefox and WebKit, whenever a change touches the Hicasso surface | Any other engine or version — the substrate targets React's DOM contract rather than any one browser's |
| ClojureScript and shadow-cljs | 1.12.145 and 3.4.10 | Nothing else is measured |
| re-frame2 core and `re-frame2-ssr` | the same checkout as Hicasso, which `:local/root` is what guarantees | There is no released coordinate yet, so no released-version pair exists to be compatible with |

The full matrix — every row above, plus the platform axis and the named CI job
or explicit untested-but-expected label behind each one — is maintained in the
repository as the Hicasso release policy, under
`docs/design/hicasso/product/release-policy.md`. That page is a working design
record rather than part of this site, so it is read from a checkout.

**Upgrades before 1.0 may cost you a rename, and should not cost you a
rethink.** Every published artifact ships at the same version through 1.0, and
the project ships no back-compatibility shims: a renamed or removed door is a
compile error at your own call site rather than a deprecation warning, so the
compiler enumerates every site that has to move. What is genuinely promised in
the meantime is the complaint ids — an id never changes meaning or spelling, and
a retired one is never reused — because stored errors and monitoring rules
outlive the code that raised them. The
[complaint index](troubleshooting.md#the-complaint-index) is the list those
promises are about.

## Hicasso needs a substrate adapter

Hicasso is a view layer, not a [substrate](../glossary.md#substrate). It owns
Hiccup interpretation and the render boundary; the reactive container app-db
lives in comes from an [adapter](../glossary.md#adapter), and re-frame2 installs
none for you. **So every Hicasso application calls
[`rf/init!`](../glossary.md#init) with an adapter before it mounts anything** —
one line, on the first line of boot:

```clojure
(rf/init! substrate/adapter)
```

`re-frame.hicasso.substrate` ships inside `day8/re-frame2-hicasso`, so that
line costs no coordinate. It is a separate namespace rather than a name on the
`h` door for the reason every optional Hicasso module is: an application that
installs somebody else's adapter never requires it and never carries it.

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
shipped alternatives and their coordinates. A Reagent or UIx adapter under a
Hicasso tree keeps working exactly as it did, and is what you want when the page
also renders that substrate's own components: every React-shaped adapter writes
the same frame context, so a Hicasso subtree and that substrate's own subtree
resolve one frame. What it costs is a second dependency whose notation you never
write, which is why Hicasso's own is the default here.

**One frame, but not one markup dialect.** That shared context is what a
*component* crossing reads; it is not permission to interleave the two notations.
A Reagent view is not a legal Hiccup head, so `[reagent-footer]` written inside a
Hicasso body raises `:rf.error/hicasso-bad-head` at the first paint — and because
a Reagent view is an anonymous meta-carrying function, the refusal can name the
enclosing view but not the offender. The two directions are not symmetric:

- **Hicasso inside a foreign parent has a named door.** `h/as-component` mints a
  real React component from a Hicasso head, and `h/as-element` converts one
  subtree; a Reagent, UIx, React or plain-JavaScript parent then mounts either
  under the frame it is already in. See [Render a Hicasso view from native
  React](09-interop.md#render-a-hicasso-view-from-native-react).
- **Reagent inside a Hicasso tree has no Hicasso door.** `[:>]` and `h/defhost`
  take real React components, which a Reagent view is not. Lifting it with
  `reagent.core/reactify-component` and crossing at the [raw
  escape](09-interop.md#raw--escape) does work, but that is Reagent's own bridge
  plus a general escape rather than something this package offers, and it puts
  two renderers in one tree.

So the practical boundary between the two layers is a **root**, not a tag. Mix or
migrate a screen at a time, and where one page must genuinely show both, give
each layer its own root naming the same `:frame` — see [More than one
root](#more-than-one-root).

An adapter buys plumbing, not notation: you write Hicasso views either way and
never call the adapter yourself. The headless plain-atom adapter is not a
substitute for a browser app — its derived value registers no watch, so a
subscription under it notifies nothing.

## Mount a first screen

A production application normally separates registrations and views into
several namespaces. This complete example keeps them together so the boot
sequence is visible — adapter first, then the root, inside the one `init` the
build calls:

```clojure
(ns counter.core
  (:require [re-frame.core :as rf]
            [re-frame.hicasso.substrate :as substrate]
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
  (rf/init! substrate/adapter)
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

**A bare mount catches nothing.** Every Hicasso refusal is a throw, and React
unmounts a root whose tree throws with no error boundary above it — the whole
page, not the offending region, with the error only in the console. Put
`h/error-boundary` around the regions a user can carry on without, which for
most applications means a route's main content rather than the root itself;
[Errors](17-errors.md#place-boundaries-at-useful-recovery-regions) is the whole
rule and [Routing and navigation](07-routing-and-navigation.md#move-focus-after-a-page-change)
shows it in a routed root.

### A frame that needs more than a seed

Those three keys are the whole of what `h/mount!` reads, and the list is closed:
any other key in `config` is ignored, silently and without complaint. A frame
that needs an `rf/make-frame` option — `:url-bound? true` for an application that
owns the browser URL, `:fx-overrides` for a stubbed backend, `:images`,
`:platform` — is therefore **created first, and the mount joins it**:

```clojure
(defn ^:export init []
  (rf/init! substrate/adapter)
  (rf/make-frame {:id             :app/main
                  :url-bound?     true
                  :initial-events [[:app/initialise]]})
  (reset! !root
          (h/mount! (js/document.getElementById "app")
                    {:frame :app/main}
                    [app-root]))
  nil)
```

Mounting ensures rather than creates, so the mount finds the frame already live
and joins it untouched. That is the same join a second root takes, and it carries
the same consequence: **seed from `rf/make-frame` in this shape, not from the
mount**, because `:initial-events` handed to a mount that joins never run.

[Routing and navigation](07-routing-and-navigation.md#boot-a-routed-application)
walks the routed case, which is the common one — a frame owns the browser URL
only by carrying `:url-bound? true`, and nothing supplies it by default.

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
  (rf/init! substrate/adapter)
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

**The DOM does not survive with them.** A reload re-evaluates the namespace, so
every `h/defview` in it is a new component type, and `h/as-component`'s `def`
mints a new one too. A changed type is a remount rather than an update by
React's own rule, so the re-render rebuilds those nodes instead of updating
them. State held in app-db comes back untouched; state the DOM itself owns does
not, and focus and the caret are the two you notice — edit a form's markup while
typing in that form and the draft survives in app-db while the cursor leaves the
field. Nothing is wrong when that happens, and it is not a reason to reach for
`defonce` on a view.

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
| A mount throws `:rf.error/no-adapter-installed`, naming `rf/make-state-container` | No [adapter](#hicasso-needs-a-substrate-adapter) is installed: `rf/init!` never ran, or ran after the mount | Make `(rf/init! substrate/adapter)` the first line of boot |
| `(counter {})` throws and names the view | A `defview` is a Hiccup head, not a directly callable helper | Render `[counter {}]`. Use a plain `defn` for inline markup |
| `h/sub` in a callback, timer, or promise throws | `:rf.error/hicasso-sub-outside-render`: the read happened outside a synchronous view body | Read during the body and close over the value. Async work should read state through events and coeffects |
| The first paint is empty and then fills in | Initial state was dispatched after mounting | Put the seed events in `:initial-events` so they finish before the first paint |
| A second `h/mount!` fails on the same DOM node | A live root already owns the node | Keep the handle with `defonce`; unmount that root before mounting another |
| A joining root's `:initial-events` never run | The named frame already exists; joining does not replay setup | Seed only from the mount that creates the frame |
| A key added to `h/mount!`'s `config` has no effect and raises nothing | The config is closed at `:frame`, `:initial-events` and `:identifier-prefix`; every other key is ignored | Put frame options on `rf/make-frame` and let the mount join ([above](#a-frame-that-needs-more-than-a-seed)) |
| One refused head blanks the whole page | A Hicasso refusal is a throw, and React unmounts a root that throws with no boundary above it | Wrap independently recoverable regions with `h/error-boundary` ([Errors](17-errors.md)) |
| A changed initialisation handler has no effect after hot reload | The live frame kept its existing app-db | Reload, recreate the frame, or dispatch an explicit reset event |
| A view body runs twice when first mounted in development | React StrictMode probes bodies twice | Expected. Keep view bodies pure and safe to re-run |
