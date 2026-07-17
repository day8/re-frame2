# re-frame.ui

`re-frame.ui` is the first-party **compiled-view substrate** (Maven coordinate
`day8/re-frame2-ui`). Views are authored as `defview` + hiccup; the compiler lowers
templates to direct React construction (browser) and a versioned structural tree
(JVM). There is no hiccup interpreter in production bundles on the compiled path.

This namespace is **not** re-exported from `re-frame.core`. Require it explicitly and
install its adapter at boot:

```clojure
(:require [re-frame.core :as rf]
          [re-frame.ui :as ui :refer [defview sub]])

(rf/init! ui/adapter)
```

Concept teaching lives in the UI guide (when published under `docs/ui/`) and the
substrate design suite. This page is the contract surface for public symbols.

**Stage honesty.** Front-porch symbols below are the ruled public API. Some
runtime behaviours (committed DOM handlers, full lease semantics, hydration
manifests) land with later substrate stages; the **names and signatures** are the
v1 surface. Where a direct call is only a compile-time resolution symbol, the
description says so.

## Boot

### `adapter`

- **Kind**: Var (adapter map)
- **Signature**: pass to `(rf/init! ui/adapter)`
- **Description**: The `re-frame.ui` substrate adapter. CLJS installs the native
  React observation substrate (no Reagent / UIx / Helix). JVM uses the headless atom
  realisation of the same closed adapter contract. Discriminator
  `:rf.adapter/ui`. Destroying the adapter tears down every public compiled Root,
  then the generic React spine. The JavaScript host must provide `WeakRef`; Root
  admission probes/captures it once before any React/registry/ViewCell ownership
  mutation and otherwise throws `:rf.error/ui-platform-incompatible` with
  `:platform :javascript`, `:capability :js/WeakRef`, and recovery
  `:use-a-weakref-capable-javascript-runtime`. `FinalizationRegistry` is optional;
  synchronous WeakRef compaction covers hosts without it. There is no strong
  fallback, polling, or per-render capability check.
- **Example**:
  ```clojure
  (rf/init! ui/adapter)
  ```

## Views

### `defview`

- **Kind**: macro
- **Signature**:
  ```clojure
  (defview name docstring? opts? [props?] template)
  ```
- **Description**: The one component form. A pure function of **zero or one** props
  map to a hiccup template, compiled to direct React (browser) and the JVM structural
  tree. Header destructuring lowers to per-slot reads. Closed options map:
  - `:props` — Malli schema (dev-checked; compile-time when the call site is literal)
  - `:id` — registry override
  - `:display-name` — React display name
  Every view is memoised on a per-slot `rf=` comparator and registers under the
  registrar's `:view` kind. No Form-2 / Form-3 / positional args.
- **Example**:
  ```clojure
  (ui/defview counter []
    [:div
     [:span "Count: " (sub [:count])]
     [:button {:on-click [:count/inc]} "+"]])
  ```

### `custom-element`

- **Kind**: macro
- **Signature**:
  ```clojure
  (custom-element tag {:properties #{…}})
  ```
- **Description**: Declares a custom-element tag and which kebab-case prop names are
  JS **properties** (camelCased on the client). Undeclared names are attributes;
  undeclared elements need no declaration (all-attributes default). JVM emits
  attributes only; property props apply at hydration.
- **Example**:
  ```clojure
  (ui/custom-element :user-picker {:properties #{:users :selected-id}})
  ```

## Template forms (compiler-owned)

These symbols resolve in templates so the compiler can lower them. **A direct call
from ordinary Clojure code fails loud** (they are not runtime helpers).

### `sub`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(sub query-vector) → value` (inside a compiled view only)
- **Description**: Lexical subscription read site. Returns the current value; the view
  re-renders when it changes. Conditional sites are legal; sites inside `for` are a
  compile error (extract a keyed child view). Not a one-shot helper — use core
  `subscribe` / `subscribe-once` outside views.
- **Errors**: direct call → `:rf.error/ui-tree-malformed`.

### `lease`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(lease descriptor)`
- **Description**: Declares a mounted/visible view's interest in a resource, as a
  declaration in the `defview` body (a `nil` descriptor is an inactive declaration).
  Returns **no data** and does **no render-time work** — the render site only records
  the desired ownership in the immutable render capture; it never mints an owner,
  fetches, or dispatches during render. After the view's **connected, visible
  commit**, the passive resource reconciler queues `:rf.resource/ensure` under an
  app-minted `[:lease …]` owner, which **loads when the cache requires it** (a fetch
  is a downstream consequence of that ensure, not render-time work). It **releases**
  on disconnect / hide / unmount by dispatching `:rf.resource/release-owner`. Read the
  resource's status and data passively with `sub` — `(sub [:rf/resource …])`. See the
  [Resource lifecycle](../resources/concepts.md). Direct calls fail loud.

### `frame`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(frame) → {:frame :dispatch :dispatch-sync :subscribe}`
- **Description**: Inside a compiled view, returns the capture-frame-shaped ops bundle
  bound to the **committed** frame. Ops fail loud after the frame incarnation dies
  (`:rf.error/frame-destroyed`). Outside views use `rf/capture-frame`.

### `local`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(local init) → [value set! update!]` (bound in a `defview` top-region `let`)
- **Description**: Host component-local ephemera, deliberately **outside re-frame2
  epochs** — not observed by subs, not revertible by epoch restore, re-renders this
  view only. The **P0-1** three-tuple: `set!` stores its argument **exactly** (a stored
  function is a value, never an updater — no `useState` fn-overload); `update!` applies
  `(f current & args)` to the **latest host state** so several same-turn writers
  (key + pointer + timer + observer) compose instead of last-write-wins. Setter/updater
  are **host-only** — a mutation during render fails loud; committed same-view handlers
  and `effect` callbacks may read/update. On the JVM structural render `local` exposes
  the initial value; `set!`/`update!` raise `:rf.error/jvm-host-op`.
- **Placement**: legal only in a `defview`'s unconditional top region (not a loop,
  branch, deferred callback, or render-fn slot → `:rf.ui.compile/hook-misplaced`).

### `effect`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(effect [deps…] body…)` / `(effect :connect body…)` (a leading statement)
- **Description**: Passive host effect for synchronising with the world outside the tree
  (measurement, chart/animation libraries via a ref). The value-deps form re-runs its
  body after commit whenever the literal deps change, compared by **`rf=`**; a returned
  function is the cleanup honoured on dep-change, disconnect, and unmount. `:connect`
  runs at each connect (mount / Activity reveal) with cleanup at each disconnect — there
  is deliberately no "once"/"mount" name. StrictMode dev replay is expected and must be
  idempotent-safe (that is what cleanup is for). `sub`/`lease`/`frame` inside an effect
  body are compile errors. On the JVM effects do not run (capability metadata only).
- **Placement**: a leading statement in a `defview` top region (or a top-region `let`/
  `do` body) before the template (`:rf.ui.compile/hook-misplaced` otherwise).

### `dispatch-fn`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(dispatch-fn) → (fn ([event] [event opts]))`
- **Description**: The per-view **stable** committed-frame dispatcher for imperative /
  foreign callbacks. Its identity is stable across renders (attach it as a listener
  once); it reads the **committed** frame at call time and retargets only on commit; and
  it **fails loud in every non-connected state** (`:rf.error/dispatch-disconnected`) —
  the leaked-listener detector for a callback that outlived its view. Capture it in the
  view body and use it from an `effect` callback or a foreign event bridge. On the JVM a
  dispatch invocation raises `:rf.error/jvm-host-op`.

### `raw`

- **Kind**: function (template interop form)
- **Signature**: `(raw react-element)`
- **Description**: Embed an existing React element in child position. On the JVM a
  rendered `raw` child is a host-op error.

### `html`

- **Kind**: function (template interop form)
- **Signature**: `(html string)`
- **Description**: Trusted markup bypass — the sole child of a DOM element
  (`[:div (ui/html s)]`). Visible at the call site; both emitters treat it
  identically.

### `raw-fn`

- **Kind**: function
- **Signature**: `(raw-fn f) → f`
- **Description**: Identity-as-protocol callback marker (also the explicit callback-ref
  form). Passes the function through to the host untouched.

### `spread`

- **Kind**: function (template interop form)
- **Signature**: `(spread base overrides)`
- **Description**: Runtime prop-map merge through the **same conversion rule table**
  as the compiler. Legal in a DOM element's props position only:
  `[:div (ui/spread base attrs)]`. Direct call → `:rf.error/ui-spread-outside-template`.

### `spread-safe`

- **Kind**: function (template interop form)
- **Signature**: `(spread-safe owned caller)`
- **Description**: The **literal safe-spread policy** — the one attr passthrough a
  component library can forward onto an internal element without clobbering owned props
  or forfeiting the controlled guarantee. `owned` is a **literal** map of the
  component's own props (analysed like an element's props map, so a controlled owned
  site — a literal `:value` / `:checked` co-present with a handler — retains the sync
  door); `caller` is the runtime attr map forwarded from the consumer. The
  compiler-visible deny law, enforced in **every** build (not dev-only): the
  structural / controlled / identity keys `:key` `:ref` `:value` `:checked` and the
  component's owned `:on-*` handlers may not appear in `caller` — a literal offender is
  the compile error `:rf.ui.compile/spread-safe-owned-key`, a runtime offender throws
  `:rf.error/ui-tree-malformed`. Everything else passes and converts per the 004B rule
  table; owned props win any collision and `:class` composes (owned classes first).
  General `spread` remains the visible-cost escape and still forfeits the sync door.
  Legal only in a DOM / custom-element props position.
- **Errors**: direct call → `:rf.error/ui-spread-outside-template`.

### `event`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(event [e] body…)`
- **Description**: Committed `:on-*` handler form for a DOM / custom-element site whose
  body needs the live native event. The body runs when the callback fires; its result
  is the **event vector to dispatch**, or `nil` to dispatch nothing (a filter). The
  callback sees the view's **committed** values and dispatches to the **committed**
  frame — the same per-site-stable, retarget-safe machinery a literal event vector
  rides. At a compiler-proven controlled site (a literal `:value` / `:checked`
  co-present with the handler on `:on-input` / `:on-change` / `:on-before-input`) a
  synchronous `event` whose result is a vector rides the **one synchronous door**
  alongside a literal-vector handler. Any synchronous result other than a vector or
  `nil` is a loud runtime diagnostic.
- **Errors**: direct call → `:rf.error/ui-tree-malformed`.
- **Example**:
  ```clojure
  [:input {:value (sub [:draft])
           :on-input (ui/event [e] [:draft/set (.. e -target -value)])}]
  ```

### `handler`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(handler [x] body…)`
- **Description**: The explicit **imperative** committed callback — the imperative
  sibling of `event`. The body runs after commit and its return is **ignored** (no
  dispatch of a returned vector — that is `event`); it does imperative work. Like every
  committed callback it is **per-site stable** and reads the **committed** values (the
  stale-closure boundary law). Legal at a DOM / custom-element `:on-*` site (the
  explicit spelling of the bare-fn shorthand; the native event binds its parameter) and
  at a **foreign-component** or **internal-view** prop (the invoker's arguments bind
  through). At an internal-view seam it gives a bare fn prop the per-site stable identity
  a fresh closure lacks (C-13a).
- **Errors**: direct call → `:rf.error/ui-tree-malformed`.
- **Example**:
  ```clojure
  (let [d (ui/dispatch-fn)]
    [foreign-map {:on-move (ui/handler [pt] (d [:map/moved pt]))}])
  ```

### `render-fn`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(render-fn [args…] template)`
- **Description**: A **pure render callback** for an internal library seam. The body is
  a template lexically visible at the consumer call site, so both emitters compile it
  (closed grammar, no runtime hiccup); it renders from its arguments alone. A render-fn
  value is legal in exactly two positions: a component call-site prop value
  (`[list {:row (ui/render-fn [i x] …)}]`) and a `slot` argument; the library invokes
  it through `slot`. A render-fn body is **pure render phase** — `sub` / `lease` /
  `frame` are allowed, but `dispatch` / hooks / local state / effects inside are
  compile errors (a stateful replacement part is a pure slot body that mounts a static
  `defview`, which owns its state).
- **Errors**: direct call → `:rf.error/ui-tree-malformed`.

### `slot`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(slot render-fn-value arg…)`
- **Description**: The compiler-owned **invocation** of a `render-fn` value at a
  library seam. `render-fn-value` is a `render-fn` (inline or carried through a prop)
  or `nil`; any other value is a loud didactic error (`:rf.error/ui-tree-malformed`).
  `nil` renders nothing; a render-fn renders with the supplied args, and its output
  participates in the surrounding children exactly like any other child. Legal only in
  a child position.
- **Errors**: direct call → `:rf.error/ui-tree-malformed`.

### `error-boundary`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(error-boundary {:fallback view :reset-key val :on-error [:ev …]} child)`
- **Description**: The explicit **error component**. Catches render / lifecycle throws
  **below** it (React does not catch event-handler or async errors — those keep their
  own typed paths). On catch the `:fallback` **view** renders with `:error` + its
  declared props and cannot recursively dispatch. `:on-error` (optional) dispatches
  **after** the failing commit through a live frame captured at the owning view's render
  — never during render — with the caught error appended to the authored event vector.
  Changing `:reset-key` (compared `rf=`) clears the caught error (retry = a state change
  that changes the key). The JVM / SSR renders the **child** under the server failure
  policy — boundaries are a **client** recovery mechanism.
- **Errors**: bad opts / non-view fallback → `:rf.ui.compile/bad-error-boundary`;
  direct call → `:rf.error/ui-tree-malformed`.

### `client-only`

- **Kind**: function (compile-time authoring form)
- **Signature**: `(client-only {:fallback tpl} client-tpl)`
- **Description**: A **browser-only** subtree with a **mandatory, capability-free**
  fallback (compiler-checked: the fallback carries no reactive read / host state /
  effect / event handler, so the JVM / SSR and first hydration render it
  deterministically). The JVM / SSR renders the fallback (a `:rf.ui/boundary
  :client-only` node); the browser renders the client subtree (the S3 activation). The
  one root phase-flip that swaps all sites in a single update completes S5.
- **Errors**: bad opts / missing fallback → `:rf.ui.compile/bad-client-only`; a
  capability in the fallback → `:rf.ui.compile/capability-in-fallback`; direct call →
  `:rf.error/ui-tree-malformed`.

### `route-link`

- **Kind**: compiled view (an ordinary `defview`)
- **Signature**: `[route-link {:to :route-id …html-attrs} & children]`
- **Description**: A navigation anchor — the compiled counterpart of the stock-Reagent
  `rf/route-link`. It renders a **real** `<a href=…>` with the route's strategy-encoded
  href (so copy-link / open-in-new-tab / keyboard activation / no-JS navigation all
  work) and, on a plain in-app left click, dispatches `:rf.route/url-requested` to the
  **committed frame** (tagged `:source :router`) instead of letting the browser reload.
  `:to` is required (a registered route id); `:params` / `:query` / `:fragment` feed both
  the href and the dispatch payload; every other key — `:class`, `:title`, `:id`,
  `:aria-label`, `:target`, `:download`, `:on-click`, and any further HTML attribute —
  passes through to the `<a>`. A caller `:on-click` runs **first** and may veto (prevent
  default); modifier / middle-click and native anchors (`:target` other than `_self`, or
  `:download`) defer to the browser so its native affordances stand.
- **Architecture**: `route-link` is an **ordinary compiled `defview`** — it gets JSX
  emission, the generated prop comparator, JVM-tree parity, and dev identity for free;
  there is **no route-link compiler intrinsic**. The routing calculation (href encode,
  dispatch payload, native-attr detection) and the click law live in the **optional**
  `re-frame.routing` artefact behind the substrate-neutral `:routing/link-model` /
  `:routing/activate-link!` late-bound seam, consumed through core's late-bind registry —
  so `re-frame.ui` never statically requires routing (`ui -> core late-bind <- routing`).
  Migrating a routed Reagent app is a mechanical `rf/route-link` → `ui/route-link`
  head-rename.
- **JVM / SSR**: renders the handler-free path-form `<a href>` shell (no raw host event
  enters the server tree); the hydrated client re-encodes through the frame's URL
  strategy. The behavioural contract + full conformance matrix live in Spec 012 — routing
  owns the law.
- **Errors**: rendered without `day8/re-frame2-routing` on the classpath →
  `:rf.error/routing-artefact-missing` (naming the artefact, its Maven coord, and the
  link site). A plain `[:a]` stays available for intentional browser-native navigation.

## Roots and mounting

### `mount`

- **Kind**: macro
- **Signature**:
  ```clojure
  (mount root-form dom-node)
  (mount root-form dom-node opts)
  ```
- **Description**: One-shot client mount: create-root + frame preflight + `render!`.
  **Idempotent per root** (same root-id + container re-renders; live frames are not
  re-seeded). `root-form` must be a **literal** vector. Identity opts
  (`:root-id`, `:disambiguator`, `:identifier-prefix`) are compile-time literals.
  Host error callbacks (`:on-uncaught-error`, …) are plain fns. Returns the Root.
  If a first render throws after allocation, the exact id/container/prefix claim is
  marked `:tearing-down` before cleanup. A normal cleanup releases at the next FIFO
  microtask; a cleanup throw leaves the claim quarantined, force-deads framework
  ownership for that incarnation, and rides the original mount error as
  `rfUiRollbackCleanupError` secondary evidence (the mount error remains primary).
- **Example**:
  ```clojure
  (ui/mount [ui/frame-root {:id :app :initial-events [[:app/init]]}
              [counter]]
            (js/document.getElementById "root"))
  ```

### `create-root`

- **Kind**: macro
- **Signature**: `(create-root dom-node opts) → Root`
- **Description**: Fix root identity for the Root's lifetime without rendering.
  Authored `:root-id` is **required** (no root form to derive from). Prefer `mount`
  for the one-liner path.

### `render!`

- **Kind**: macro
- **Signature**: `(render! root root-form)`
- **Description**: Render / re-render a **literal** root form into an existing Root.
  Frame plans in the top region preflight before render. Identity is unchanged.

### `hydrate-root`

- **Kind**: macro
- **Signature**:
  ```clojure
  (hydrate-root dom-node root-form)
  (hydrate-root dom-node root-form opts)
  ```
- **Description**: Hydrating mount. Identity comes from the **server manifest**, not
  client identity opts (passing `:root-id` / `:identifier-prefix` is an error). Full
  hydration lands with SSR roots; earlier stages fail loud with
  `:rf.error/root-manifest-invalid` when no valid manifest is present.

### `unmount!`

- **Kind**: function
- **Signature**: `(unmount! root) → nil`
- **Description**: Total, exact-incarnation teardown. The root's complete
  id/container/identifier-prefix claim moves `:live → :tearing-down → :released`;
  release occurs at host settlement, not merely when `.unmount` returns. Synchronous
  cleanup releases inline. A deferred React teardown retains the claim until its FIFO
  settlement microtask, rejecting same-id/container reuse in the interim. A throwing
  host cleanup force-deads that incarnation's framework owners but leaves the host
  claim quarantined `:tearing-down` because the container is unproven; a second call
  is a no-op and recovery uses a fresh container. Idempotent. Client-only; JVM
  host-op error.

### `frame-root`

- **Kind**: function (root-form template symbol)
- **Signature**: `(frame-root {:id … :initial-events …} & children)`
- **Description**: Static **ENSURE** plan wrapper, legal only in the **top region** of
  a root form passed to `mount` / `render!` / `hydrate-root`. `:id` must be a
  compile-time keyword. Creates the frame if absent, seeds `:initial-events` once,
  never destroys on unmount. Compiles away — direct call fails with
  `:rf.error/ui-frame-root-outside-root-form`. To **scope** an already-live frame
  inside a view tree, use `frame-provider`.

### `frame-provider`

- **Kind**: function (template symbol)
- **Signature**: `(frame-provider {:frame f} & children)`
- **Description**: Scope a subtree to an **already-live** frame through React
  context. Creates nothing, seeds nothing, destroys nothing. `:frame` is a runtime
  frame id or live frame value. Absent frame →
  `:rf.error/frame-provider-frame-absent`. Direct call fails with
  `:rf.error/ui-frame-provider-outside-template`.

## See also

- [`re-frame.core`](re-frame.core.md) — events, subs, frames (`make-frame`,
  `capture-frame`), boot (`init!`)
- [Introduction](../core/introduction.md) — the event pipeline this substrate
  plugs into
- Substrate design / UI guide under the synthesis suite (force-tracked findings) when
  reader-facing `docs/ui/` is not yet landed
