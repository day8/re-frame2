# re-frame.ssr

Server-side rendering and hydration. `re-frame.ssr` runs the same framework as the client: the same registrations, cascade, `app-db`, and subs. Four things differ on the server:

- each request creates a per-request frame;
- the cascade runs to completion before the response is built;
- the resulting hiccup is emitted as an HTML string;
- a hydration payload ships alongside, so the client resumes without re-rendering.

Ships in a separate artefact (`day8/re-frame2-ssr`); add it to your deps and require the namespace. The Ring host-adapter lives in [`re-frame.ssr.ring`](re-frame.ssr.ring.md).

```clojure
(:require [re-frame.ssr :as ssr])
```

The `re-frame.core` facade re-exports a curated set of render and head primitives as late-bound wrappers: `rf/render-to-string`, `rf/render-tree-hash`, `rf/project-error`, the registration macros `rf/reg-head` / `rf/reg-error-projector`, and the head accessors `rf/render-head` / `rf/active-head` / `rf/head-model->html` / `rf/head-snapshot` (accessors documented in [`re-frame.core`](re-frame.core.md)). When the artefact is on the classpath, these wrappers resolve to this namespace at call time. When it is not, they throw a clear "SSR not loaded" error. Examples below use `rf/` at idiomatic call sites and `ssr/` for the host-adapter surface.

## Rendering primitives

### `render-to-string`

- **Kind**: function
- **Signature**:
  ```clojure
  (render-to-string view-or-hiccup opts) → HTML string
  ```
- **Description**: The canonical server-side render. Walks the hiccup tree once and emits a string. Pure and JVM-runnable.
  - It resolves callable-headed views (Var / `(rf/view :id)`), `:tag#id.cls` shorthand, and HTML5 void elements. It escapes text and attribute values.
  - Ordinary inline `<script>` / `<style>` string content is HTML **raw text**: emitted verbatim with only React's context-safe closing-sequence rewrite (an embedded `</script>` / `</style>` breakout is respelled so the parser cannot terminate the element early), never entity-escaped and never refused. This is byte-identical across `render-to-string`, the streaming shell walk, and `emit-ui-tree`. Structured data belongs on its own channel: JSON-LD / head content via `reg-head` (which applies the stricter `<`→`<` data escape), the hydration payload via the `__rf_payload` wire.
  - `opts` keys (all optional):
    - `:doctype?` — prefixes `<!DOCTYPE html>`.
    - `:emit-hash?` — injects `data-rf-render-hash` on the tree's first DOM-tag element, for client-side mismatch detection.
    - `:render-hash` — supplies a precomputed hash to stamp instead, avoiding a second canonical-EDN walk.
  - Raises:
    - `:rf.error/invalid-tag-name` — malformed tag name.
    - `:rf.error/ssr-invalid-attribute-name` — malformed attribute key.
    - `:rf.error/ssr-reagent-native-head` — a `:>` interop head.
    - `:rf.error/ssr-suspense-boundary-outside-stream` — a `:rf/suspense-boundary` marker reached this non-streaming emitter.
- **Example**:
  ```clojure
  (rf/with-new-frame [f (rf/make-frame {:images [app-image]})]
    (rf/dispatch-sync [:app/server-init] {:frame f})   ;; setup dispatch, not :initial-events
    (ssr/render-to-string [app-root] {:doctype? true}))
  ```

### `emit-ui-tree`

- **Kind**: function
- **Signature**:
  ```clojure
  (emit-ui-tree tree)
  (emit-ui-tree tree opts) → HTML string
  ```
- **Description**: Serialises an **already-rendered** version-1 structural tree to a string. Where `render-to-string` consumes hiccup and renders it, this seam consumes the value `re-frame.ui.tree/render` already produced, and calls nothing: no view is invoked, no subscription is resolved, no frame is bound. Every dynamic value is already a literal in the tree. Pure, JVM-runnable, and deterministic to the byte.
  - It emits the markup for one root's tree only. Manifests, payloads, root identity, and the HTTP response belong to the SSR artefact's other surfaces.
  - `opts` keys (all optional):
    - `:doctype?` — prefixes `<!DOCTYPE html>`.
  - Raises:
    - `:rf.error/ssr-ui-tree-version-unsupported` — the root `:rf.ui/tree-version` is missing, non-integer, or unsupported. Checked **first**, before any emission, and carries `{:got <received> :supported #{1}}`. This is a deploy-skew condition: the server is older than the tree it was handed.
    - `:rf.error/ui-tree-malformed` — a structurally invalid node past the version gate. The shared tree-consumer id, signalling a code bug rather than skew.
- **Example**:
  ```clojure
  ;; The tree arrives already rendered; this call only folds it to markup.
  (ssr/emit-ui-tree tree {:doctype? true})
  ```

### `render-tree-hash`

- **Kind**: function
- **Signature**:
  ```clojure
  (render-tree-hash render-tree) → 32-bit FNV-1a structural hash (lowercase hex)
  ```
- **Description**: A deterministic structural fingerprint of a render tree. The same canonical-EDN representation produces the same hash on JVM and CLJS. This hash drives the hydration compatibility check: a server/client hash mismatch means hydration is unsafe.
- **Example**:
  ```clojure
  ;; Capture the hash at render time; it rides the hydration payload as
  ;; :rf/render-hash and is re-checked client-side after the first render.
  (let [hiccup      ((rf/view :app/root))
        render-hash (rf/render-tree-hash hiccup)]
    {:rf/render-hash render-hash})
  ```

### `install-render-to-string!`

- **Kind**: function
- **Signature**:
  ```clojure
  (install-render-to-string! set-hiccup-emitter!-fn)
  ```
- **Description**: Install this namespace's `render-to-string` into a substrate adapter's `:render-to-string` slot. Use it when wiring a custom (non-bundled) adapter directly. The bundled Reagent adapter wires itself via the `:reagent/set-hiccup-emitter!` late-bind hook, so app code rarely calls this.

### `adapter`

- **Kind**: var
- **Signature**:
  ```clojure
  ssr/adapter   ;; the SSR substrate adapter map
  ```
- **Description**: The SSR substrate adapter map: the server-side / headless (JVM) substrate. Pass it to `rf/init!` to install it.
  - It carries this namespace's `render-to-string` directly in its `:render-to-string` slot, so no late-bind wiring is needed at the call site.
  - Its `:render` slot throws `:rf.error/render-on-headless-adapter`. SSR renders exclusively via `render-to-string`.
- **Example**:
  ```clojure
  (rf/init! ssr/adapter)
  ```

## Streaming render

Streaming emits the shell HTML first, then continues rendering boundary subtrees as their data settles. Authors mark a streamed region with the `boundary` component; on the server it expands to the internal `:rf/suspense-boundary` marker, and each marker becomes a continuation.

### `boundary`

- **Kind**: function (component)
- **Signature**:
  ```clojure
  (boundary attrs & body) → hiccup
  ```
- **Description**: Declare a streaming suspense boundary around `body`. This is the authoring surface for a streamed region — one form that works on every host. `attrs` requires two keys:
  - `:id` — the boundary's identity, unique per page. It is how an arriving chunk is paired with its placeholder. A keyword or a string.
  - `:fallback` — hiccup rendered inline in the shell while the body is still resolving, and re-rendered by this component when the boundary is reported failed.

  Per host:
  - **Server**: expands to the internal `:rf/suspense-boundary` marker the shell walker consumes. That marker is wire syntax, never authored directly; outside a stream the non-streaming emitter still rejects it with `:rf.error/ssr-suspense-boundary-outside-stream`.
  - **Client**: renders `body`, or `:fallback` when `:id` is in the page's failed-boundary record written at stream finalization. No recorded outcome — a plain client mount, a non-streamed page — renders `body`, so it fails soft by construction.

  Malformed `attrs` raise `:rf.error/suspense-boundary-invalid-attrs`.

  This is not React Suspense: no promises, no thrown thenables, no selective hydration. The server decides what defers; the client paints the fallback and swaps content as chunks land.
- **Example**:
  ```clojure
  (require '[re-frame.ssr :as ssr])

  [ssr/boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
   [card-view :revenue]]
  ```

### `streaming-render-shell`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-render-shell root-hiccup)
    → {:shell-html "..." :continuations [{:id :subtree} ...]}
  ```
- **Description**: Walk the tree once. At each `:rf/suspense-boundary`, it emits a `<template …suspense-fallback>` placeholder and records a continuation. Returns the shell HTML (ready to flush) and the continuations to drain.
- **Example**:
  ```clojure
  ;; Host adapter: render the shell to flush immediately, keep the continuations.
  (let [{:keys [shell-html continuations]}
        (rf/with-frame fid (ssr/streaming-render-shell hiccup))]
    ;; flush shell-html now; drain `continuations` as each subtree settles
    shell-html)
  ```

### `streaming-render-continuation`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-render-continuation frame-id entry)
    → {:id :html :delta :failed? :continuations}
  ```
- **Description**: Drain one continuation against `frame-id`'s app-db.
  - It snapshots before-db / after-db and computes the per-subtree delta.
  - A nested `:rf/suspense-boundary` inside the subtree registers a new continuation. New continuations come back under `:continuations`, for the host to append at the tail of its FIFO drain queue (`[]` when the subtree carries none).
  - On a throw, it emits `:rf.ssr/suspense-boundary-failed`, surfaces the original fallback HTML inline with `:failed? true`, omits `:delta`, and returns no nested continuations.
- **Example**:
  ```clojure
  ;; Drain the FIFO queue against fid's app-db, emitting each chunk;
  ;; nested boundaries discovered mid-drain append at the tail.
  (loop [queue continuations]
    (when-let [entry (first queue)]
      (let [{:keys [id html delta failed? continuations]}
            (rf/with-frame fid (ssr/streaming-render-continuation fid entry))]
        ;; flush this subtree's resolved HTML + hydrate-delta as the next chunk
        (recur (into (vec (rest queue)) continuations)))))
  ```

### `streaming-build-final-payload`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-build-final-payload frame-id render-hash opts)
    → canonical :rf/hydration-payload
  ```
- **Description**: Build the `__rf_payload` final chunk. Call it after all continuations drain.
  - `opts` **MUST** carry the fail-closed `:payload` policy: a vector allowlist of top-level app-db keys, or `:rf.ssr.payload/whole-app-db` to ship the whole app-db. Omitting it throws `:rf.error/ssr-missing-payload-policy`.
  - Optional `:version` overrides the SSR artefact's compiled-in pattern-protocol constant as the payload's `:rf/version` source.
- **Example**:
  ```clojure
  ;; After every continuation drains, build the canonical __rf_payload chunk.
  ;; No :version opt — the builder sources :rf/version from the SSR artefact's
  ;; compiled-in pattern-protocol constant. Pass :version only to force skew.
  (rf/with-frame fid
    (ssr/streaming-build-final-payload
      fid render-hash {:payload :rf.ssr.payload/whole-app-db}))
  ```

The next four functions are the lower-level chunk-template builders the streaming host emits per boundary. They are host-adapter territory; app code rarely calls them.

### `streaming-fallback-template`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-fallback-template id fallback-html) → HTML string
  ```
- **Description**: The fallback chunk shape: the boundary's fallback markup, wrapped in an inline `<template data-rf2-suspense-fallback>` placeholder in the shell HTML. A `<template>`'s content is inert (never painted), which makes it the wire-carrier for the fallback markup. The client-side streaming runtime materialises each inert fallback into a live, visible mount.

### `streaming-resolved-template`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-resolved-template id resolved-html) → HTML string
  ```
- **Description**: The resolved-subtree chunk shape, flushed when a continuation drains successfully. The client-side streaming runtime swaps the matching fallback placeholder for this resolved content in the DOM.

### `streaming-failed-template`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-failed-template id fallback-html) → HTML string
  ```
- **Description**: The failed-continuation chunk shape: the same wire shape as `streaming-resolved-template`, plus a `data-rf2-suspense-failed` marker. The failure semantics are inline-fallback: the client-side runtime surfaces the failure observably, without surfacing a 500.

### `streaming-hydrate-delta-script`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-hydrate-delta-script id delta-edn) → HTML string
  ```
- **Description**: The per-subtree hydration delta chunk (`application/edn`). The client reads the EDN and merges the delta into `app-db` as the subtree streams in.

The streaming surface is host-adapter territory. The SSR-aware host ([`re-frame.ssr.ring`](re-frame.ssr.ring.md) or equivalent) wires it, so most app code never touches `streaming-render-*` directly. The one app-facing piece is the client-side runtime, below.

### `streaming-install!`

- **Kind**: function (ClojureScript only)
- **Signature**:
  ```clojure
  (streaming-install! opts) → stop! (0-arity fn)
  ```
- **Description**: Install the client-side streaming runtime. Returns a 0-arity `stop!` fn that disconnects the observer early. Idempotent per chunk.
  - It observes the document for arriving chunks. As they land, it materialises each inert fallback `<template>` into a visible mount, swaps in resolved subtrees, and merges each per-subtree hydration delta into the `:frame`'s app-db.
  - It disconnects itself once the final `__rf_payload` node lands. From there, the bootstrap's `:rf/hydrate` is the canonical reconciliation.
  - `opts`:
    - `:frame` — **REQUIRED**. An absent frame emits + throws `:rf.error/no-frame-context`.
    - `:root` — the DOM root to observe; default `js/document`.
    - `:payload-id` — the final-payload `<script>` id; default `"__rf_payload"`.
- **Example**:
  ```clojure
  ;; Streaming-aware bootstrap: install BEFORE the first chunks can land
  ;; (the initial sweep also covers chunks that arrived earlier).
  (ssr/streaming-install! {:frame :app/main})
  ```

## The head model

The `<head>` is modelled separately from the body as a head-model: a data structure carrying `:title`, `:meta`, `:link`, `:json-ld`, `:html-attrs`, and `:body-attrs`. Head-models are registered per-route with `reg-head`. A registered head-fn is evaluated and rendered through the `re-frame.core` facade accessors `render-head` / `active-head` / `head-model->html` / `head-snapshot` (documented in [`re-frame.core`](re-frame.core.md)). Those four fns are the whole read surface: `active-head` returns the active route's head-model. There is **no `:rf/head` subscription** — see [§Subscriptions](#subscriptions--there-are-none).

### `reg-head`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-head id ?metadata head-fn)
  ```
- **Description**: Register a head-fn keyed by id. The head-fn signature is `(fn [db route] head-model)`. Routes opt in via `:head` route metadata.
- **Example**:
  ```clojure
  (rf/reg-head :app/head
    (fn [db _route]
      {:title (str "MyApp — " (:page-title db))
       :meta  [{:name "description" :content (:summary db)}]}))
  ```

*Exposed on the `re-frame.core` facade as `rf/reg-head`; there is no `re-frame.ssr/reg-head` alias. The brief facade entry in [`re-frame.core`](re-frame.core.md) points here for the full contract.*

## Hydration

The server-rendered HTML carries a `__rf_payload` chunk that the client deserialises into `app-db` on bootstrap. The `render-tree-hash` structural hash is captured at render time and re-checked at hydration.

Three checks guard hydration, each with its own trace:

- **`:rf.ssr/hydration-mismatch`** — the server-render hash disagrees with the client-render hash. In `:hard-error` mode it also throws; otherwise it warns and re-renders client-side, rather than mounting a broken DOM.
- `:rf.ssr/version-mismatch` — the payload was produced by a different framework version.
- `:rf.ssr/schema-digest-mismatch` — the app's schema set drifted since the payload was built.

The latter two are payload-provenance checks; the reference `:rf/hydrate` handler runs them. `hydration-mismatch` guards the render-tree structural hash. See [the SSR tutorial §The client side](../ssr/concepts.md#the-client-side-hydrate-then-verify).

### `hydrate!`

- **Kind**: function
- **Signature**:
  ```clojure
  (hydrate! opts) → applied-payload | nil
  ```
- **Description**: The client-side boot helper, and the symmetric counterpart of the server's `re-frame.ssr.ring/ssr-handler`. Returns the payload that was applied, or `nil` on a client-only first load. It fuses the three mandated client-flow steps:
  1. **read** the payload — supplied via `:payload`, or on CLJS read from the DOM's `__rf_payload` `<script>` via [`read-server-payload`](#read-server-payload).
  2. **hydrate** via `dispatch-sync [:rf/hydrate payload]` against the target `:frame` before the first render (locked `:replace-frame-state` semantics).
  3. **verify** by calling the supplied `:render-tree-fn` and comparing its hash against the server hash (omit `:render-tree-fn` to skip).
  - `:frame` is **REQUIRED** — an absent frame emits + throws `:rf.error/no-frame-context`.
  - A payload whose `:rf/frame-id` names a different frame than `:frame` emits + throws `:rf.error/hydration-frame-id-mismatch`.
  - `:payload` is required on the JVM and optional on CLJS (read from the DOM when omitted). `:element-id` overrides the payload `<script>` id.
- **Example**:
  ```clojure
  ;; Client boot: read payload, dispatch :rf/hydrate, then verify —
  ;; synchronously, before the host mounts. The hydration target is
  ;; carried (supplied via :frame), not synthesised.
  (ssr/hydrate! {:frame          :app/main
                 :render-tree-fn #((rf/view :app/root))})
  ```

### `read-server-payload`

- **Kind**: function (ClojureScript only)
- **Signature**:
  ```clojure
  (read-server-payload)            → payload-map | nil
  (read-server-payload element-id) → payload-map | nil
  ```
- **Description**: Read the EDN hydration payload from the DOM's `__rf_payload` `<script>`. That id is the pinned default; pass `element-id` to read a host-overridden slot. `hydrate!` calls this when `:payload` is omitted.
  - Returns the parsed payload map, or `nil` when the page was not server-rendered (no payload script present).
  - Fails closed: a malformed payload script surfaces `:rf.error/malformed-hydration-payload` and returns `nil`, so the host falls back to a client-only first render.
- **Example**:
  ```clojure
  ;; Branch on "was this page server-rendered?" without booting.
  (when-let [payload (ssr/read-server-payload)]
    (:rf/render-hash payload))
  ```

### `verify-hydration!`

- **Kind**: function
- **Signature**:
  ```clojure
  (verify-hydration! frame-id render-tree)
  (verify-hydration! frame-id render-tree opts)
  ```
- **Description**: Called by client code after the first render. It compares the post-render hash to the server hash stashed during `:rf/hydrate`, and emits `:rf.ssr/hydration-mismatch` on disagreement. `hydrate!` calls this for you when given a `:render-tree-fn`. Call it directly when the host must verify the actually-mounted tree.
  - The second argument may be a render tree (it is hashed) or a pre-computed hash string.
  - `opts` may carry `:first-diff-path`, `:failing-id`, and `:server-hash` (the last overrides the stashed slot).
  - Two per-frame `:ssr` knobs govern behaviour. `{:detect-mismatch? false}` skips the comparison. `{:on-mismatch :hard-error}` escalates a detected mismatch to a thrown structured exception; the default `:warn` resolves to `:warned-and-replaced`.
- **Example**:
  ```clojure
  ;; Host that mounts first, then verifies the mounted tree explicitly.
  (ssr/verify-hydration! :app/main ((rf/view :app/root)))
  ```

## Error projection

A frame opts into SSR error projection via the `:ssr {:public-error-id ... :dev-error-detail? ...}` map on its `make-frame` / `frame-root` config. This is **per-frame metadata**, not a `configure` key: different frames in the same process can carry different projector / dev-detail settings.

### `reg-error-projector`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-error-projector id ?metadata projector-fn) → id
  ```
- **Description**: Register a projector keyed by id. The projector signature is `(fn [trace-event] :rf/public-error)`. Named per-frame via the frame's `:ssr {:public-error-id ...}` metadata. Returns `id`.
  - **The return shape is closed — get it wrong and your projector is discarded silently.** Conformant output carries **exactly** the four [`public-error-keys`](#public-error-keys): `:status` (an integer in `400`–`599`), `:code` (a keyword), `:message` (a string), `:retryable?` (a boolean). None missing, none extra — including a `:details` of your own, which only the runtime may append, *after* validation, under `:ssr {:dev-error-detail? true}`. A non-conforming return makes [`project-error`](#project-error) emit `:rf.error/sanitised-on-projection` and serve the locked generic-500 [`fallback-public-error`](#fallback-public-error) instead, on **every** error, for the life of the registration.
  - **A custom projector inherits none of the default's condition-gating.** Two `:rf.error/*` categories carry a discriminator tag that decides the status, and a projector that branches on `:operation` alone gets both wrong: `:rf.error/no-such-handler` is a `404` only under `[:tags :kind]` `:route`, and `:rf.error/schema-validation-failure` a `400` only under `[:tags :where]` `:event`. Gate on the tag the way the example below does, or an unregistered event id answers `404` and a server-side `:where :fx-args` failure blames the client for a server bug — see [`default-error-projector-fn`](#default-error-projector-fn) for the reasoning.
- **Example**:
  ```clojure
  (rf/reg-error-projector :app/public-error
    (fn [trace-event]
      ;; Gate the 404 on `:kind :route` — only a URL that matched no route
      ;; is a missing PAGE. An unregistered event id or frame-id arrives
      ;; under the same category and is a SERVER defect: 500, not 404.
      (if (and (= :rf.error/no-such-handler (:operation trace-event))
               (= :route (get-in trace-event [:tags :kind])))
        {:status     404
         :code       :not-found
         :message    "We couldn't find that page."
         :retryable? false}
        {:status     500
         :code       :internal-error
         :message    "Something went wrong."
         :retryable? false})))
  ```

*Exposed both as the `re-frame.core` facade macro `rf/reg-error-projector` and as the `re-frame.ssr/reg-error-projector` function; the brief facade entry in [`re-frame.core`](re-frame.core.md) points here for the full contract.*

### `project-error`

- **Kind**: function
- **Signature**:
  ```clojure
  (project-error frame-id trace-event) → :rf/public-error
  ```
- **Description**: Apply the named frame's active error-projector to a trace event. The projector is selected by the frame's `:ssr {:public-error-id ...}` metadata. This is the seam between an internal error trace event (full diagnostic detail) and a client-safe public-error projection.
  - When the frame's `:ssr {:dev-error-detail? true}` metadata is set, the projection carries an extra `:details` key with the raw trace event (absent by default).
  - If the projector throws or returns a non-conforming shape, this emits `:rf.error/sanitised-on-projection` and returns the locked generic-500 [`fallback-public-error`](#fallback-public-error). A bug in the projector cannot bypass the boundary.
  - `reg-error-projector` (above) registers the projector this applies.
- **Example**:
  ```clojure
  ;; Turn an internal error-trace event into the frame's client-safe
  ;; public-error projection (host adapter / error-page render path).
  (ssr/project-error :rf/default trace-event)
  ;; => {:status 404 :code :not-found :message "Page not found" :retryable? false}
  ```

### `default-error-projector-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-error-projector-fn trace-event) → :rf/public-error
  ```
- **Description**: The runtime's built-in default projector, registered under `:rf.ssr/default-error-projector`. Maps trace events to public errors:
  - `:rf.error/no-such-handler` → `404 :not-found`, but **only when the miss's `:kind` tag is `:route`**. Spec 009 catalogues three misses under this one category, discriminated by a mandatory `:kind`: a URL that matched no route (`:kind :route`), a dispatch to an unregistered event id (`:kind :event`), and a Tool-Pair surface addressing an unknown frame-id (`:kind :frame`). Only the first is a missing-*page* condition. The other two are server defects — answering `404` would tell the client its URL was wrong when the server forgot a registration, and a crawler will act on that — so they fall through to the generic `500`, as does a miss carrying no `:kind` at all. The arm is opt-in on the discriminator, fail-safe, exactly like the `:where`-gated arm below. Gated so it stays that way once the URL-driven miss was promoted onto the always-on error axis: before that promotion no `:rf.error/no-such-handler` reached this projector in production at all, and an ungated arm would now answer `404` for an unregistered event id. The `:kind :route` arm is the one that answers an unroutable URL, and it survives production hardening. Its sibling `:rf.error/no-such-route` maps to `404` unconditionally — one failure mode, no `:kind` discriminator — but that category is caller misuse of `route-url` and rides the dev-gated trace stream, so a release build never reaches this arm through it.
  - `:rf.error/cofx-value-invalid` (a client-supplied coeffect rejected at the dispatch boundary) → `400 :bad-request`.
  - `:rf.error/schema-validation-failure` → `400 :bad-request`, but only when the failure's `:where` tag is `:event` (a client-supplied event payload). Server-side surfaces such as `:where :fx-args` fall through, and so does a record carrying no `:where` at all — the arm is opt-in on the discriminator, fail-safe. **This arm fires under production hardening as well as in dev** — see below.
  - Everything else → the locked generic `500 :internal-error` ([`fallback-public-error`](#fallback-public-error)).
- **The `400`-for-schema-validation-failure arm reaches this projector under production hardening, and the route it takes is worth knowing.** Most of schema validation really is a development-build assertion ([Spec 010 §Production builds](../../spec/010-Schemas.md#production-builds)): under `:advanced` + `goog.DEBUG=false`, or the JVM `-Dre-frame.debug=false` hardening an SSR service is required to set, the `validate-*!` family returns `true` unconditionally and there is nothing left to reject. **The `:rf.schema/at-boundary` interceptor is not part of that family, and it is the arm an SSR endpoint is most likely to be leaning on.** Its check is ungated and runs on every build, so a handler registered with `{:interceptors [:rf.schema/at-boundary]}` rejects a malformed request body under production hardening: the handler is skipped and the payload never reaches `app-db`. The rejection also fans one **always-on** `:rf.error/schema-validation-failure` record carrying `:source :boundary` and `:where :event`, which the SSR projection listener buffers exactly as it buffers the route miss — so the projector *is* handed something to map, and the endpoint answers `400` rather than the silent `200` it once did. That is the status [RFC 9110 §15.5.1](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.1) asks of a refused request payload, and `re-frame.ssr-boundary-rejection-400-production-test` pins it under the real gate. What still elides is the *rich* `emit-error!` above the rejection: the production record carries identifiers only — no event vector, no offending value, no explanation — because a boundary payload is attacker-controlled by definition. Note the deliberate contrast with the `:rf.error/safe-redirect-*` categories, which are *non*-projection-eligible: a refused redirect is a working mitigation, and turning `?next=javascript:alert(1)` into a `500` would be a denial of service. A refused request payload is the opposite case — a client fault, with a status of its own. **What the arm gives you is the status, not the page.** A rejection that must shape the *response* — field-level errors, submitted values preserved — still validates in the handler body and emits `[:rf.server/set-status 400]`; see [Pattern-FormAction §Validation is the handler's job](../../spec/Pattern-FormAction.md#validation-is-the-handlers-job) for the worked shape, and [Spec 011 §Substrate](../../spec/011-SSR.md#server-error-projection) for the full set of categories that reach the projector under production hardening.

### `apply-error-projection!`

- **Kind**: function
- **Signature**:
  ```clojure
  (apply-error-projection! frame-id)
  (apply-error-projection! frame-id trace-event)
  ```
- **Description**: Project an error trace event via `frame-id`'s active projector, and stamp the resulting public-error's `:status` onto the response accumulator. Returns the public-error map. Returns `nil` on a no-op: the frame is missing, is not a server frame, or has no pending trace.
  - 1-arity: drains the frame's error-trace buffer and projects the LAST trace (last-write-wins).
  - 2-arity: projects the supplied trace directly (for hosts that catch errors outside the trace stream).
  - When the response already carries a `:redirect`, its status is locked through. The projection still returns the public-error map, but does not overwrite `:status`.

### `project-render-exception!`

- **Kind**: function
- **Signature**:
  ```clojure
  (project-render-exception! frame-id throwable) → :rf/public-error | nil
  ```
- **Description**: Route a render-time `Throwable` through `frame-id`'s SSR error projector. Host adapters wrap their `render-to-string` call with this. Returns the public-error map (the host's contract for rendering the wire error body), or `nil` when projection is not applicable.
  - It synthesises a `:rf.error/ssr-render-failed` trace carrying the exception, then drives the projector via `apply-error-projection!`. The projector's output stamps the response accumulator's `:status`.
  - It also emits the trace, so monitoring listeners see the rich internal detail.
  - The per-frame dev escape-hatch `:ssr {:on-view-exception :throw}` re-throws unchanged instead of projecting.

### `public-error-keys`

- **Kind**: var
- **Signature**:
  ```clojure
  public-error-keys   ;; => #{:status :code :message :retryable?}
  ```
- **Description**: The four locked keys on the `:rf/public-error` shape. Conformant projector output carries exactly these, plus an optional `:details` in dev mode.

### `fallback-public-error`

- **Kind**: var
- **Signature**:
  ```clojure
  fallback-public-error
  ;; => {:status 500 :code :internal-error :message "Something went wrong" :retryable? false}
  ```
- **Description**: The locked generic-500 public-error shape. The runtime falls back to this whenever the active projector throws or returns a non-conforming shape. A bug in the projector cannot bypass the boundary.

Full rationale: [the SSR tutorial §When the server throws](../ssr/concepts.md#when-the-server-throws).

## The response accumulator

Each per-request frame accumulates its HTTP response (status, headers, cookies, redirect) in a framework-private side-channel keyed by frame-id. The accumulator lives outside `app-db`, so it never rides the hydration payload to the client. The server-only [`:rf.server/*` fx](#server-only-fx) write into it, and the functions below read the resolved value. `get-response` is the canonical host-adapter alias. `peek-response` and `flush-response!` split the pure read from the side-effecting drain.

### `default-response`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-response) → response-map
  ```
- **Description**: The default response accumulator: status `200`, a default `content-type: text/html; charset=utf-8` header, no cookies, no redirect.
- **Example**:
  ```clojure
  (ssr/default-response)
  ;; => {:status   200
  ;;     :headers  [["content-type" "text/html; charset=utf-8"]]
  ;;     :cookies  []
  ;;     :redirect nil}
  ```

### `get-response`

- **Kind**: function
- **Signature**:
  ```clojure
  (get-response frame-id) → response-map
  ```
- **Description**: Read the resolved response accumulator for a frame. This is the canonical host-adapter alias for the drain-then-read sequence: it flushes any pending error projections before reading, so `:status` reflects the active projector's output, and then strips the internal bookkeeping keys. Use `peek-response` for a pure read (no drain) and `flush-response!` for the explicit-side-effect spelling.
- **Example**:
  ```clojure
  ;; Host adapter: after the drain settles, read the response to build the wire reply.
  (ssr/get-response :app/request-frame)
  ;; => {:status 200 :headers [["content-type" "text/html; charset=utf-8"]] :cookies [] :redirect nil}
  ```

### `peek-response`

- **Kind**: function
- **Signature**:
  ```clojure
  (peek-response frame-id) → response-map
  ```
- **Description**: A **pure** read of the resolved response accumulator. It does NOT drain pending error projections. Use it from debug paths or midpoint inspections, where the drain baked into `get-response` would consume a trace the host had not yet observed.

### `flush-response!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-response! frame-id) → response-map
  ```
- **Description**: Drain any pending error projection for `frame-id`, then return the resolved response. This is side-effecting: every call clears the projector buffer, and the first call after an error trace wins (last-write-wins). This is the explicit-side-effect spelling. `get-response` is the canonical host-adapter alias; `peek-response` is the pure-read counterpart.

## Request context

An SSR host adapter populates a per-frame request slot once per request, before the drain. The [`:rf.server/request`](#coeffects) cofx surfaces it to server-side handlers. The slot is cleared as part of per-request frame teardown.

### `set-request!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-request! frame-id request) → frame-id
  ```
- **Description**: Populate the per-frame request slot. An SSR host adapter calls this once per request, before kicking off the drain. The shape of `request` is host-defined: the Ring adapter passes the Ring request map, and other adapters pass their native shape. The runtime never inspects it.
- **Example**:
  ```clojure
  ;; Host adapter: stash the active request before driving the drain.
  (ssr/set-request! :app/request-frame ring-request)
  ```

### `get-request`

- **Kind**: function
- **Signature**:
  ```clojure
  (get-request frame-id) → request | nil
  ```
- **Description**: Read the active request for `frame-id`. Returns `nil` when no host adapter has populated the slot. This is a public read surface: host adapters and tools may inspect the active request via this fn.

### `clear-request!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-request! frame-id) → frame-id
  ```
- **Description**: Clear the per-frame request slot. Host adapters call this after building the wire response (typically as part of per-request frame teardown). Safe to call when no slot is populated.

### `on-frame-destroyed!`

- **Kind**: function
- **Signature**:
  ```clojure
  (on-frame-destroyed! frame-id)
  ```
- **Description**: The per-request frame teardown hook. It drops the frame's entries in the pending-error-trace buffer, the request slot, and the response slot, and it invokes the head-snapshot cleanup. It runs during per-request frame teardown via the `:ssr/on-frame-destroyed` late-bind hook. Idempotent: a second call against the same frame-id is a no-op.

## Blocking-resource drain

### `drain-blocking-resources!`

- **Kind**: function
- **Signature**:
  ```clojure
  (drain-blocking-resources! frame-id)
  (drain-blocking-resources! frame-id opts)
  ```
- **Description**: Drain the current nav-token's BLOCKING resources for SSR `frame-id` until they settle or the render deadline fires. This way the render walk sees a settled resource state, never a hung `:loading`. The host render path calls this after frame setup and route resolution, before the render walk. Returns the drain result map `{:settled? :timed-out :route-blocking-failure}`.
  - When the resources artefact is absent, this is a no-op returning `{:settled? true}`. An SSR app without resources never blocks on them.
  - `opts` keys (all optional):
    - `:ssr-blocking-timeout-ms` — wall-clock budget; default `5000`.
    - `:pump!` — a 1-arity `(fn [tick-ms] …)` event-pump thunk. Defaults to a host-platform yield, so an in-flight async reply lands between re-checks.
    - `:tick-ms` — poll-granularity hint; default `5`.
- **Example**:
  ```clojure
  ;; Host render path: settle blocking resources before walking the tree.
  (ssr/drain-blocking-resources! :app/request-frame {:ssr-blocking-timeout-ms 5000})
  ;; => {:settled? true :timed-out [] :route-blocking-failure nil}
  ```

## Keyword surfaces

The SSR runtime owns a set of keyword-addressed surfaces: events, server-only and client-only fx, subscriptions, one coeffect, and the `:platforms` registration-metadata key. These are addressed by keyword, not imported as vars.

### Events

| Event | What it does |
|---|---|
| `:rf/server-init` | Per-request server-side initialisation. Reads request cofx; dispatches setup events. `:platforms #{:server}`. |
| `:rf/hydrate` | Seed the client-side `app-db` from the server-supplied payload (locked `:replace-frame-state` semantics). Runs once on client bootstrap. Fails closed. A non-map payload, or a present-but-non-map `:rf/app-db` / `:rf/runtime-db` slice, is rejected with `:rf.error/malformed-hydration-payload`. A payload `:rf/frame-id` naming a different frame than the dispatch target is rejected with `:rf.error/hydration-frame-id-mismatch`. In both cases the frame-state is left unchanged. |

- **Example**:
  ```clojure
  ;; :rf/server-init — registered by the app; fired from the per-request frame's
  ;; :initial-events as it boots, dispatching the setup work the page needs.
  (rf/reg-event :rf/server-init
    {:platforms #{:server}}
    (fn [{:keys [db]} _]
      {:db db
       :fx [[:rf.http/managed {:request    {:method :get :url "/api/articles"}
                               :decode     :json
                               :on-success [:articles/loaded]}]]}))

  ;; :rf/hydrate — framework-owned; dispatched on the client (usually via
  ;; ssr/hydrate!) to seed app-db from the payload before the first render.
  (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
  ```

### Server-only fx

All seven fx are server-only (`:platforms #{:server}`). They build the response accumulator that the host adapter turns into the HTTP response.

| Fx | Args |
|---|---|
| `[:rf.server/set-status int]` | per `:rf.fx.server/set-status-args` |
| `[:rf.server/set-header {:name :value}]` | per `:rf.fx.server/set-header-args` |
| `[:rf.server/append-header {:name :value}]` | per `:rf.fx.server/append-header-args` |
| `[:rf.server/set-cookie :rf.server/cookie]` | structured cookie map |
| `[:rf.server/delete-cookie {:name ?:path ?:domain}]` | — |
| `[:rf.server/redirect {:location ?:status}]` | default `:status 302`; truncates HTML. **Caller-trusted** `:location`. |
| `[:rf.server/safe-redirect {:location ?:relative-only? ?:allow ?:status}]` | The caller-untrusted variant: open-redirect mitigation for attacker-controlled `?next=` strings. Before setting `:redirect`, it parses `:location` (`:rf.error/safe-redirect-invalid-url`), rejects `javascript:` / `data:` / `vbscript:` schemes (`:rf.error/safe-redirect-scheme-rejected`), and enforces the `:relative-only?` / `:allow` allowlist (`:rf.error/safe-redirect-host-disallowed`). |

Boundary validation (all seven):

- A header name violating the RFC 7230 token grammar throws `:rf.error/header-invalid-name`. A header value carrying CR/LF/NUL throws `:rf.error/header-invalid-value`.
- A cookie `:name` violating the RFC 6265 token grammar, or of an unsupported type, throws `:rf.error/cookie-invalid-name`. Any other cookie attribute (`:value` / `:path` / `:domain` / `:max-age` / `:same-site` / `:expires`) carrying CR/LF/NUL throws the single `:rf.error/cookie-invalid-attribute`, which names the offending attribute in its `:attribute` payload slot.
- A redirect `:location` carrying CR/LF/NUL throws `:rf.error/redirect-invalid-location`. The retired `:url` / `:to` target keys throw `:rf.error/redirect-retired-target-key`.
- `:status` and `:redirect` are last-write-wins. A second write in the same drain emits `:rf.warning/multiple-status-set` / `:rf.warning/multiple-redirects`.

- **Example**:
  ```clojure
  ;; Shape the HTTP response from a server-side handler. Every :rf.server/* fx
  ;; is :platforms #{:server}, so the client render skips them.
  (rf/reg-event :app/respond
    {:platforms #{:server}}
    (fn [{:keys [db]} _]
      {:db db
       :fx [[:rf.server/set-status    200]
            [:rf.server/set-header    {:name "X-Foo" :value "first"}]
            [:rf.server/append-header {:name "Set-Cookie" :value "a=1"}]
            [:rf.server/set-cookie    {:name      "session"
                                       :value     "abc123"
                                       :max-age   3600
                                       :http-only true
                                       :same-site :lax
                                       :path      "/"}]
            [:rf.server/delete-cookie {:name "stale-session" :path "/"}]]}))

  ;; Redirects — caller-trusted vs caller-untrusted (e.g. an attacker-supplied
  ;; ?next= param). safe-redirect parses + allowlists before setting :redirect.
  (rf/reg-event :auth/bounce
    {:platforms #{:server}}
    (fn [{:keys [db]} _]
      {:db db
       :fx [[:rf.server/redirect      {:status 302 :location "/login"}]
            [:rf.server/safe-redirect {:location "/dashboard" :relative-only? true}]]}))
  ```

### Client-only fx

Both fx are client-only (`:platforms #{:client}`). They are the payload-provenance compatibility checks that the reference `:rf/hydrate` handler dispatches after installing the server slice. They are best-effort: a mismatch emits a structured warning trace, and hydration proceeds.

| Fx | Args |
|---|---|
| `[:rf.ssr/check-version server-value]` | A scalar (the payload's `:rf/version`) or `{:expected ?:actual}`. When `:actual` is absent, the client value resolves from the SSR artefact's compiled-in pattern-protocol constant (the same value the server stamped, so a matching build compares equal). A mismatch emits `:rf.ssr/version-mismatch`. |
| `[:rf.ssr/check-schema-digest server-value]` | A scalar (the payload's `:rf/schema-digest`) or `{:expected ?:actual}`. When `:actual` is absent, the client value resolves via the `:schemas/app-schemas-digest` late-bind hook (schemas artefact). A mismatch emits `:rf.ssr/schema-digest-mismatch`. An absent hook emits `:rf.ssr/compatibility-check-skipped`. |

### Subscriptions — there are none

**`re-frame.ssr` and `re-frame.ssr.ring` register no subscriptions at all.** Their only
registrations are the `:rf/hydrate` event, the server-only and client-only fx above, and
the `:rf.server/request` coeffect below. In particular there is **no `:rf/head` sub and
no `:rf/public-error` sub** — `@(rf/subscribe [:rf/head])` cannot resolve. Both keywords
name a *data shape* registered in [Spec-Schemas](../../spec/Spec-Schemas.md)
(`:rf/head-model` and `:rf/public-error`), not a registry entry.

Read them through functions instead:

| What you want | How to read it |
|---|---|
| The active route's head model | The `re-frame.core` facade accessors `render-head` / `active-head` / `head-model->html` / `head-snapshot` — see [§The head model](#the-head-model) |
| The sanitised public-error projection | [`project-error`](#project-error), or [`apply-error-projection!`](#apply-error-projection) which projects *and* stamps the response `:status` |

The current request's **response accumulator** (status / headers / cookies / redirect) is *not* a registered subscription. It lives in a framework-private side-channel atom keyed by frame-id, and the runtime reads it exclusively via `re-frame.ssr/get-response`. The host adapter consumes the resolved value to build the wire response.

### Coeffects

| Cofx | Returns |
|---|---|
| `:rf.server/request` | The active HTTP request map. |

- **Example**:
  ```clojure
  ;; A server handler declares the requirement, then reads the request FLAT
  ;; under :rf.server/request (Ring-shaped under the bundled adapter).
  (rf/reg-event :app/server-init
    {:platforms        #{:server}
     :rf.cofx/requires [:rf.server/request]}
    (fn [{:rf.server/keys [request] :keys [db]} _]
      {:db (assoc db :method (:request-method request))}))
  ```

### `:platforms` fx-gating metadata

`reg-fx` accepts a `:platforms` metadata key: a set containing `:server` and / or `:client`. It gates fx execution by active platform. When the key is absent, the default is `#{:server :client}` (universal).

```clojure
(rf/reg-fx :my/fx
  {:platforms #{:server}}
  (fn [ctx args] ...))
```

Skipped fx emit a `:rf.fx/skipped-on-platform` trace event so debug tools see the gate firing. The cofx side has a mirror trace event, `:rf.cofx/skipped-on-platform`.

Detail in [the SSR tutorial §`:platforms`](../ssr/concepts.md).

## See also

- [`re-frame.core`](re-frame.core.md) — the `reg-head` / `reg-error-projector` facade entries and the head accessors (`render-head` / `active-head` / `head-model->html` / `head-snapshot`), plus the instrumentation / error-catalogue surface where the SSR trace events are defined.
- [`re-frame.ssr.ring`](re-frame.ssr.ring.md) — the Ring host adapter that drives the render pipeline and materialises the response accumulator onto the wire.
- [`re-frame.routing`](re-frame.routing.md) — routes opt into head models via `:head` metadata.
- [Server-side rendering — the tutorial](../ssr/concepts.md) — the conceptual walkthrough.
