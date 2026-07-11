# Programming model and compiler

## Design taste

The surface should feel like Clojure, compile like JSX, and behave like re-frame2.

```clojure
(ui/defview article-row [{:keys [article selected?]}]
  [:article.row
   {:class    (when selected? "is-selected")
    :on-click [::article-selected (:article/id article)]}
   [:h2 (:article/title article)]
   [:p  (:article/summary article)]])
```

There is no `$` around every element, no subscription handle to dereference, no `useCallback`, no frame capture at a click site, and no runtime tree conversion. The restrictions that make this possible are visible and teachable.

## `defview`

### Shape

```clojure
(ui/defview name
  "optional docstring"
  optional-options-map
  [props-destructure]
  body)
```

Examples:

```clojure
(ui/defview spinner []
  [:span.spinner {:aria-label "Loading"}])

(ui/defview greeting [{:keys [name] :or {name "friend"}}]
  [:p "Hello, " name])

(ui/defview user-card
  {:props [:map
           [:user/id uuid?]
           [:compact? {:optional true} boolean?]]}
  [{:user/keys [id] :keys [compact?]}]
  ...)
```

The component always has one logical props map. `[]` means no application props. Positional component arguments are deliberately absent: a single named shape makes component calls readable, enables literal call-site validation, gives Xray stable prop names, and lets the compiler generate a direct comparator.

The normal destructuring subset is `:keys`, namespaced `:x/keys`, `:or`, and explicit key bindings. The compiler translates each binding to a direct constant-key read from the JavaScript props object. It does not construct a shallow CLJS map first.

Using `:as` is permitted but marked as a whole-props capability. It materializes a CLJS map and selects the generic prop-comparison path. The compiler emits a development performance note at the definition. This keeps generic forwarding components possible while ordinary views remain allocation-free at entry.

### Generated identity

`defview` derives a globally unique view ID from namespace and symbol unless `:id` is supplied. It emits:

- a named React function component;
- a generated `React.memo` comparator;
- a stable hot-reload shell;
- a re-frame2 `:view` registry entry;
- a compile manifest in development;
- a plain JVM function from the same normalized template;
- a template fingerprint shared by both hosts.

The component Var carries compiler metadata, so another `defview` call site can distinguish an internal view from a foreign React component without a runtime registry probe.

## Template grammar

### Native nodes

```clojure
[:button.primary#save
 {:type       :button
  :disabled   disabled?
  :class      ["wide" (when dirty? "dirty")]
  :style      {:inline-size width}
  :aria-label "Save"}
 "Save"]
```

At macro expansion:

- `button`, `primary`, and `save` are parsed once;
- DOM prop names become their React spellings;
- literal named DOM values such as `:button` become strings;
- the literal style keys become JavaScript keys;
- the static class prefix is folded;
- `nil` attrs are omitted where React semantics permit;
- production output calls `jsx` or `jsxs` from `react/jsx-runtime`;
- development output calls the development JSX runtime with source coordinates.

There is no tag parser or generic prop converter in the browser runtime.

### Internal views

```clojure
[user-card {:user/id user-id
            :compact? true}]
```

The compiler resolves `user-card` as a `defview`, encodes its prop keys directly, checks literal required props, places `:key` in the JSX key slot, and leaves Clojure values unchanged.

Children are ordinary trailing forms and arrive under `:children`:

```clojure
[panel {:tone :quiet}
 [:h2 "Details"]
 [:p body]]
```

### Fragments and branches

`[:<> ...]` is the literal Fragment form. The compiler understands template positions through these control forms:

- `let` and `letfn` for value computation;
- `if`, `if-not`, `when`, `when-not`, `cond`, and `case`;
- `do` when all non-final expressions are statically pure;
- `for` for element lists;
- threading and ordinary functions for values, not for dynamically manufactured markup.

Every branch is normalized into the template AST. A form that might produce arbitrary Hiccup at runtime is a compile error with an escape-hatch suggestion.

### Lists

The compiler lowers a template-position `for` into a direct JavaScript array builder:

```clojure
[:ul
 (for [todo todos]
   [todo-row {:key  (:todo/id todo)
              :todo todo}])]
```

The element must have a key the compiler can see. Missing keys are build failures, not runtime warnings. The initial core supports one generator plus Clojure's `:let`, `:when`, and `:while` modifiers. Nested iteration uses nested `for` forms. This bounded grammar covers the normal case and compiles predictably.

`map` is still useful for computing values. A `map` that returns markup is rejected because it would either retain a lazy sequence and its capture hazards or require a runtime element interpreter. Extract a child view and use `for`.

Reactive reads and resource lease declarations inside a loop are rejected. Each item should be a keyed child view that owns its own fixed read sites, or the parent should read one aggregate subscription. This gives every site stable identity and prevents unbounded dependency bookkeeping inside one cell.

### Text and empty values

Strings and numbers render as text. `nil` and `false` render nothing. Keywords and symbols in child position are development errors unless explicitly converted; silent keyword text is usually a mistake. Collections are not flattened generically. Only compiler-produced element arrays are accepted as multiple children.

## Reactive reads

```clojure
(ui/defview inbox [{:keys [user-id]}]
  (let [mode  (ui/sub [::display-mode])
        items (if (= mode :mine)
                (ui/sub [::items-for user-id])
                (ui/sub [::all-items]))]
    [inbox-list {:items items}]))
```

`ui/sub` returns the current value. It is a compiler-recognized render read, not a React Hook, so conditional reads are legal. Each lexical call receives an integer site index.

For a literal query:

- the literal prefix is hoisted;
- static queries are hoisted in full;
- dynamic arguments are stored in the render capture;
- the prior query object is reused while arguments remain `rf=`;
- development metadata records the query expression and source coordinate.

The result published at a site is reference-stable: if the newly read value is `rf=` to that site's committed value, the committed value object is returned. Persistent data's structural sharing therefore reaches React prop comparison even when a subscription body rebuilt an equal collection.

An explicit frame pin is available for infrastructure views:

```clojure
(ui/sub :preview/frame [::document doc-id])
```

Application views should normally use a nested frame scope instead. The one-argument form follows re-frame2's carried frame invariant and fails loudly when no frame is available.

`ui/sub` outside `defview`, inside an async callback, or inside an arbitrary helper is a macro error. Pure helpers take the values they need. One-shot/test reads continue to use re-frame2's existing compute and introspection APIs.

## Events as data

### Direct event intent

A vector in a native DOM `:on-*` attr is a re-frame2 event intent:

```clojure
[:button {:on-click [::remove item-id]} "Remove"]
```

The vector is not passed to React. The compiler assigns an event site and generates one stable callback for the mounted view instance. Each committed render publishes the current values used by that event expression into the site. When invoked, the callback reads those committed values, uses the committed frame, and dispatches with the view ID, instance token, template path, and source coordinate in development tracing.

The JVM emitter retains the event vector as data. Headless view tests can assert intent without synthesizing a browser event.

### Reading the DOM event

Use `ui/event` when the dispatched value depends on the React event:

```clojure
[:input
 {:value    draft
  :on-input (ui/event [e]
              [::draft-changed (.-value (.-currentTarget e))])}]
```

The last form yields the event vector. Preceding forms may perform event-local mechanics such as `preventDefault`:

```clojure
[:form
 {:on-submit (ui/event [e]
               (.preventDefault e)
               [::submitted form-id])}
 ...]
```

`ui/event` can also forward data received as a prop:

```clojure
(ui/defview action-button [{:keys [event children]}]
  [:button {:on-click (ui/event event)} children])
```

Event intent is evaluated at invocation, but free render locals are loaded from the committed site slots. The compiler rewrites those locals explicitly; it does not retain a fresh render closure.

### Imperative callback

Foreign components and rare DOM callbacks sometimes need an arbitrary function rather than a dispatched event:

```clojure
[:canvas {:on-pointer-move
          (ui/handler [e]
            (paint-preview! canvas-ref e brush))}]
```

`ui/handler` has the same stable identity and committed-value semantics but executes its body directly. It is a side-effect boundary and is marked as such in the manifest. A plain function in an event prop is rejected so the call site must choose data dispatch or imperative handling intentionally.

`ui/raw-handler` passes a function identity through unchanged for a foreign API that deliberately treats callback identity as data. It is a rare, explicit escape hatch and disables handler-site optimization and provenance for that prop.

### Render callback

Some foreign props are called synchronously while the foreign component renders: item-key, comparator, formatter, and render-prop functions are common examples. They must observe the current render, not the previously committed DOM:

```clojure
[VirtualList
 {:items items
  :item-key (ui/render-fn [item]
              (.-id item))}]
```

`ui/render-fn` emits a render-scoped pure closure. It may return a value or compiled template, but it may not dispatch, perform I/O, call Hooks, read `ui/sub`, or declare `ui/lease`. It has no stable-identity guarantee; a capture-free closure may be hoisted, but code must not depend on that optimization. If a returned subtree needs its own state or subscriptions, the render function returns a named child `defview`.

Its argument vector is the foreign callback's positional JS call shape. A literal map destructure compiles to direct property reads from a JS object; use a plain binding and ordinary Clojure lookup when the foreign protocol deliberately passes a persistent Clojure value. No automatic deep conversion occurs.

This distinction is concurrency-critical. Publishing speculative values into a stable `ui/handler` so a foreign render could see them would also retarget callbacks on the still-visible old DOM. One callback cannot truthfully represent both phases.

## Resource lifetime

```clojure
(ui/defview article [{:keys [slug]}]
  (let [descriptor {:resource :article/by-slug
                    :scope    {:from-db :session/current}
                    :params   {:slug slug}}]
    (ui/lease descriptor)
    (let [{:keys [status data error]}
          (ui/sub [:rf/resource descriptor])]
      ...)))
```

`ui/lease` declares effect-connected liveness. It returns no data and starts no work during render. The render capture records the descriptor at a stable site; the aggregated passive effect ensures it after commit and releases it when the site disappears, changes target, unmounts, or disconnects under a hidden React Activity.

Keeping lease and read as two forms preserves re-frame2's causal/passive distinction. Route and event resource plans remain preferable when loading is part of navigation or workflow rather than view liveness.

## Local React facilities

`re-frame.ui.react` exposes a deliberately thin Clojure spelling of current React Hooks:

```clojure
(ns example.chart
  (:require [re-frame.ui :as ui]
            [re-frame.ui.react :as react]))

(ui/defview chart [{:keys [series]}]
  (let [node (react/use-ref nil)]
    (react/use-effect [series]
      (fn []
        (let [instance (mount-chart! (.-current node) series)]
          #(destroy-chart! instance))))
    [:div {:ref node}]))
```

The `defview` analyzer enforces top-level Hook ordering and exact dependency vectors, following UIx's proven approach. These hooks are for actual React-local or imperative host concerns. They do not replace app-db, subscriptions, events, resources, or machines.

For callbacks created inside effects that need the latest committed values without reconnecting the effect, the namespace exposes React 19.2's effect-event semantics. DOM event handlers still use `ui/event` or `ui/handler`; React explicitly limits `useEffectEvent` to effects.

## Props ABI and memoization

### Internal props

Each Clojure keyword maps to a deterministic quoted JS property name. The encoding preserves the full namespace and name and cannot collide with React's `key`, `ref`, or `children` slots. The manifest maps compact production slot indexes back to keywords.

A generated component reads only declared slots. Literal calls with missing required props or unknown props fail at compile time. A `:props` Malli schema adds value validation in development and required-key validation at literal call sites; production assertions elide.

### Generated comparator

Every internal view is memoized by default with a generated straight-line comparator over its declared prop slots. It uses re-frame2's host-portable `rf=` relation, which short-circuits identical persistent values and handles keywords by value. It does not enumerate JS keys or allocate a result object.

`children` is compared as one slot. Compiler-hoisted static children and stable event handlers preserve its identity naturally. A view using whole-props materialization or a dynamic spread uses a generic shallow comparator and receives a development cost note.

Context and ViewCell invalidation still render through `React.memo`; memo only suppresses unchanged parent props.

### Foreign props

A symbol that is not a `defview` is treated as a foreign React component when used in template head position:

```clojure
[DatePicker {:selected date
             :on-change (ui/handler [value]
                          (dispatch-date! value))}]
```

Literal prop names compile to their React JS spellings and values pass through without deep conversion. `children`, `key`, and `ref` follow React 19 semantics. Foreign component props are open by definition.

## Explicit dynamic boundaries

### Dynamic component

```clojure
(ui/element component-type {:value value} child)
```

`ui/element` accepts a runtime React type and always uses the public/foreign JS props ABI; it never performs runtime component-shape detection. Its literal props still compile directly. If the props themselves are dynamic, combine it with `ui/spread`.

A dynamic internal view is obtained with `ui/view`, which returns that view's public wrapper. The wrapper translates public prop names to the direct internal slots once. Literal internal calls bypass it entirely.

### Dynamic props

```clojure
[:button (ui/spread base-dom-props {:disabled disabled?}) "Run"]
```

`ui/spread` is the only generic prop-map conversion path. It performs target-aware key conversion and merge at runtime. The explicit spelling lets bundle and profiler tooling attribute its cost. It never enables dynamic element vectors.

Literal override maps passed to `ui/spread` are still compiled target-aware: a native DOM `:on-*` vector gets normal event-site lowering, while a foreign callback prop must explicitly use `ui/event`, `ui/handler`, or `ui/render-fn`. An event vector hidden inside the opaque runtime map is never inferred; that map must already contain values valid for the foreign/React boundary.

### Existing React element

```clojure
(ui/raw element-returned-by-js)
```

The compiler verifies the form is in child/template position and passes it through. JVM rendering requires a sibling `ui/client-only` fallback.

### Client-only foreign UI

```clojure
(ui/client-only
  {:fallback [:div.map-placeholder "Map unavailable in server render"]}
  [MapboxView {:center center}])
```

The CLJS emitter produces the foreign element. The JVM emitter produces the fallback. A missing fallback is a compile error in `.cljc` code used by SSR.

During hydration CLJS first emits the same fallback as JVM. The compiler emits a small `ClientOnlyBoundary` component at each site; it reads one root hydration-phase context but owns no effect. One root effect flips that context to client mode after the hydration commit, then every boundary renders its client template. A normal `createRoot` starts in client mode immediately. This makes the boundary a deliberate post-hydration replacement rather than a suppressed mismatch or a conditional Hook in the authoring view.

The fallback must be capability-free render output: deterministic DOM/internal props-only views, with no Hooks, `ui/sub`, `ui/lease`, event handlers, refs, or foreign/client-only descendants. This prevents a one-commit placeholder from acquiring work just before replacement. Put shared reactive data/status outside the boundary and wrap only the browser-only leaf. The compiler checks reachable literal fallback views and rejects a fallback whose safety it cannot prove.

There is intentionally no `ui/hiccup` escape hatch in the core artefact. An optional development/tooling package may render data-authored UI through a separate, visibly imported interpreter if a real consumer requires it.

## Compiler pipeline

### 1. Analyze

The macro resolves Vars, macroexpands supported control forms, reads props destructuring, and obtains analyzer information for locals, Hooks, purity checks, and foreign references.

### 2. Normalize

It produces a small host-neutral AST:

```clojure
{:node :dom
 :tag "button"
 :props [{:name "className" :value ...}
         {:name "onClick" :event-site 0}]
 :children [{:node :value :expr ...}]
 :source {:line 12 :column 5}
 :path [0 1]}
```

The AST is data. It is the sole input to both code generators and to the manifest fingerprint.

### 3. Validate and classify

The compiler:

- validates element and component props;
- assigns subscription, event, render-callback, lease, Hook, and DOM site IDs;
- rejects render-time dispatch and known side effects;
- enforces list keys;
- finds static subtrees and literal queries;
- computes capability bits;
- records the Hook signature for hot reload;
- marks dynamic cost boundaries.

### 4. Emit CLJS

The CLJS emitter generates direct JSX-runtime calls, direct prop reads, a straight-line comparator, and only the ViewCell features selected by capability bits. Static queries, props, and subtrees become module constants.

### 5. Emit JVM

The JVM emitter generates a plain function returning the canonical serializable render tree expected by `day8/re-frame2-ssr`. Subscription reads call the bound SSR snapshot computation. Event vectors stay data; imperative handlers disappear; lease declarations are no-ops.

### 6. Emit development manifest

The manifest contains stable IDs and source facts, not executable application logic. It is registered under the existing re-frame2 view kind in development and removed by Closure advanced compilation in production.

## Static hoisting

The production emitter hoists a subtree only when it proves all of these:

- no local, prop, subscription, context, or Hook value is read;
- no event, handler, ref, or resource site exists;
- no key depends on list position;
- no development annotation is required in the production branch.

It may separately hoist static prop objects and child arrays around dynamic values. Reusing an immutable React element description is safe; refs and owner-sensitive facts are never hoisted.

The compiler does not attempt whole-program ClojureScript memoization. It performs local template transformations with mechanically checkable proofs.

## Build-time diagnostics

The following are errors by default:

- arbitrary runtime Hiccup in template position;
- `ui/sub` or `ui/lease` outside a view capture or inside a loop;
- dispatch, timer creation, DOM mutation, or known I/O in render;
- a plain function in an event/callback prop that has not chosen `ui/event`, `ui/handler`, `ui/render-fn`, or `ui/raw-handler` semantics;
- a Hook in a condition or loop;
- missing or nonliteral Hook dependency vectors;
- a missing list key;
- a literal unknown DOM prop;
- missing required internal props;
- a foreign/client-only node with no JVM fallback on an SSR path;
- an effectful/reactive/interactive `ui/client-only` fallback;
- a raw lazy sequence as children.

Warnings, configurable as errors, cover:

- whole-props materialization;
- `ui/spread` in a hot list;
- broad whole-db or whole-resource reads that have high invalidation frequency;
- index keys on a non-append-only list;
- an opaque handler where a data event would suffice;
- a view whose compiler-estimated dynamic node count exceeds a configured budget.

Diagnostics include the offending source form, why the invariant matters, and the smallest valid rewrite. The compiler should teach the model, not merely say “unsupported.”

## Why strictness improves ergonomics

Silent fallback is friendly at the first call site and hostile in a large application: two visually identical forms acquire very different performance, SSR, and debugging behavior. A strict compiler gives every normal view one cost model. When the model cannot apply, the author names the boundary once and tools can find it.

This is the same discipline reagent-slim applies to absent legacy APIs and UIx applies to Hook-rule violations, carried through the whole template pipeline.
