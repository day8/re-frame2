# re-frame2 UI guide

This is the proposed user manual for the blank-slate `day8/re-frame2-ui` design. The library does not exist yet; examples are normative API sketches and should become executable documentation during implementation.

## The model in sixty seconds

- Define application state transitions and projections with ordinary re-frame2 events and subscriptions.
- Define React views with `ui/defview` and literal Clojure markup.
- Read a subscription value with `ui/sub`; do not dereference a reaction and do not call a subscription Hook.
- Put an event vector in a DOM event attr; the compiler creates a stable, frame-correct callback.
- Keep render pure. External systems belong in effects; live resources use explicit route/event ownership or `ui/lease`.
- Props are one named map. Lists require semantic keys.
- The compiler emits direct React calls in the browser and a serializable render tree on JVM.
- Xray can explain a committed render from its event and subscription causes. Development evidence is absent from production.

```clojure
(ui/defview counter [{:keys [label]}]
  (let [n (ui/sub [::count])]
    [:button.counter
     {:on-click [::increment]}
     label ": " n]))
```

## Chapters

1. [Quickstart](01-quickstart.md)
2. [Views and templates](02-views-and-templates.md)
3. [State reads and events](03-state-and-events.md)
4. [Props, children, and lists](04-props-children-and-lists.md)
5. [Frames and reusable UI](05-frames-and-reuse.md)
6. [Local state and forms](06-local-state-and-forms.md)
7. [Effects and React interop](07-effects-and-interop.md)
8. [Resources and asynchronous UI](08-resources-and-async-ui.md)
9. [Debugging](09-debugging.md)
10. [Production performance](10-performance.md)
11. [SSR, hydration, and hot reload](11-ssr-hydration-and-hmr.md)
12. [Testing](12-testing.md)

## Conventions

Examples use these aliases:

```clojure
(ns app.feature
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui]
            [re-frame.ui.react :as react]))
```

Event and subscription IDs are namespaced keywords. `::name` means the current namespace's keyword.

The guide uses `app-db` as shorthand for a frame's durable application partition. re-frame2 frames remain explicit; there is no ambient process-global default created by the UI library.

## The strict boundary

Literal markup is compiled. Arbitrary vectors assembled at runtime are not UI elements:

```clojure
;; Compiled and fast
[:div [:span text]]

;; Not supported as dynamic markup
(let [node (if active? [:strong text] [:em text])]
  [:div node])
```

Write the branch in template position instead:

```clojure
[:div
 (if active?
   [:strong text]
   [:em text])]
```

For genuinely dynamic React types, props, or existing elements, use the explicit interop forms described later. There is no silent runtime-Hiccup fallback.

## Where to look when something feels surprising

- A build error normally includes the invariant and rewrite. Start there.
- Use Xray's view instance to see the committed frame, props, subscription sites, and exact render cause.
- Use React DevTools for the foreign/React component tree.
- If a view renders too often, inspect the changed subscription or prop; do not add `useMemo` blindly.
- If loading or async work is hard to inspect, make sure its status lives in re-frame2 state rather than a hidden component promise.

The [design suite](../README.md) explains the compiler, ViewCell algorithm, integration contracts, and performance gates behind this manual.
