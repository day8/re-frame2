# re-frame.ssr

Render a re-frame2 app to HTML on the server and resume it in the browser. Each request renders in its own frame: its events run to completion, the root view becomes an HTML string, and the resulting state ships in the page as a hydration payload, which `hydrate!` installs on the client before the first render so the browser adopts the server's markup. Most apps serve requests through the Ring handler in [`re-frame.ssr.ring`](re-frame.ssr.ring.md), which calls this namespace for them.

Ships in the `day8/re-frame2-ssr` artefact; require `re-frame.ssr` once at boot. Without it, `rf/reg-head` and `rf/reg-error-projector` throw `:rf.error/ssr-artefact-missing`, naming the artefact and the namespace to require.

```clojure
(:require [re-frame.ssr :as ssr])
```

```clojure
;; Server (JVM): render one request in its own frame.
(rf/init! ssr/adapter)

(rf/with-new-frame [f (rf/make-frame {})]
  (rf/dispatch-sync [:app/server-init] {:frame f})
  (ssr/render-to-string [(rf/view :app/root)] {:doctype? true}))

;; Client (CLJS), after rf/init! with the view adapter: install the server's
;; state before the first render, then check the render matches the server's.
(rf/make-frame {:id :app :platform :client})
(ssr/hydrate! {:frame          :app
               :render-tree-fn (fn [] ((rf/view :app/root)))})
```

Two registrars are on the `re-frame.core` facade: `rf/reg-head` and `rf/reg-error-projector`. Everything else is called on this namespace; there is no facade copy, because requiring `re-frame.ssr` is what installs the SSR runtime. `head-model` and `head-model->html` are defined on [`re-frame.ssr.head`](re-frame.ssr.head.md) and re-exported here. [The SSR model](../ssr/concepts.md) walks through one request from arrival to hydration.

## Rendering

### `render-to-string`

- **Kind**: function
- **Signature**:
  ```clojure
  (render-to-string view-or-hiccup)      → HTML string
  (render-to-string view-or-hiccup opts) → HTML string
  ```
- **Description**: Renders hiccup to an HTML string in one walk of the tree. Pure and JVM-runnable.
    - It resolves callable-headed views (a Var or `(rf/view :id)`), `:tag#id.cls` shorthand and HTML5 void elements, and escapes text and attribute values.
    - Inline `<script>` / `<style>` string content is HTML raw text: emitted verbatim, never entity-escaped and never refused. The only rewrite is React's: an embedded `</script>` / `</style>` is respelled so the parser cannot end the element early. The output is byte-identical across `render-to-string`, the streaming shell walk and `emit-ui-tree`.
    - Put structured data on its own channel: JSON-LD and other head content through `reg-head`, which escapes `<` as `\u003c`, and state through the `__rf_payload` hydration payload.
- **Options** (all optional):
    - `:doctype?` — prefixes `<!DOCTYPE html>`.
    - `:render-hash` — a hash to stamp as `data-rf-render-hash` on the tree's first DOM element, for client-side mismatch detection. Compute it with `render-tree-hash` and use the same value for the payload's `:rf/render-hash`. Without it, no marker is stamped.
- **Errors**:
    - `:rf.error/invalid-tag-name` — a malformed tag name.
    - `:rf.error/invalid-hiccup-head` — a vector head that is neither a keyword nor a callable (a string, `nil`, a number, a collection), or an unrecognised `:rf/*` keyword head.
    - `:rf.error/ssr-invalid-attribute-name` — a malformed attribute key.
    - `:rf.error/ssr-reagent-native-head` — a `:>` interop head.
    - `:rf.error/ssr-suspense-boundary-outside-stream` — a streaming [`boundary`](#boundary) reached this non-streaming emitter.
- **Example**:
  ```clojure
  (rf/with-new-frame [f (rf/make-frame {:images [app-image]})]
    (rf/dispatch-sync [:app/server-init] {:frame f})   ;; setup dispatch, not :initial-events
    (ssr/render-to-string [app-root] {:doctype? true}))
  ```

### `render-tree-hash`

- **Kind**: function
- **Signature**:
  ```clojure
  (render-tree-hash render-tree) → 32-bit FNV-1a structural hash (lowercase hex)
  ```
- **Description**: Returns a structural hash of a render tree. The hash is computed over the tree's canonical EDN, so the same tree hashes the same on JVM and CLJS. Hydration compares the server's hash with the client's first render; a mismatch means hydration is unsafe.
- **Example**:
  ```clojure
  ;; Capture the hash at render time; it rides the hydration payload as
  ;; :rf/render-hash and is re-checked client-side after the first render.
  (let [hiccup      ((rf/view :app/root))
        render-hash (ssr/render-tree-hash hiccup)]
    {:rf/render-hash render-hash})
  ```

### `adapter`

- **Kind**: var
- **Signature**:
  ```clojure
  ssr/adapter   ;; the SSR substrate adapter map
  ```
- **Description**: The headless substrate adapter for the server (JVM). Pass it to `rf/init!`.
    - Its `:render-to-string` slot is this namespace's `render-to-string`.
    - Its `:render` slot throws `:rf.error/render-on-headless-adapter`; on the server, render with `render-to-string`.
- **Example**:
  ```clojure
  (rf/init! ssr/adapter)
  ```

## The head model

A head model is data describing the page's `<head>`: `:title`, `:meta`, `:link`, `:script`, `:json-ld`, `:html-attrs` and `:body-attrs`. Register a head function with `rf/reg-head` and select it from a route's `:head` metadata. Read the model with `head-model` and emit it with `head-model->html`; both are defined on [`re-frame.ssr.head`](re-frame.ssr.head.md) and re-exported here under the same names. `head-model` returns the model and records it nowhere, and there is no `:rf/head` subscription ([below](#subscriptions--there-are-none)). [Head metadata](../ssr/head.md) shows a complete head and route.

### `reg-head`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-head id ?metadata head-fn) → id
  ```
- **Description**: Registers a head function under `id`; a route selects it with `:head id` in its metadata. Returns `id`.
    - `head-fn` is `(fn [db route] head-model)`: pure, like a subscription.
    - Called as `rf/reg-head` on the `re-frame.core` facade. There is no `re-frame.ssr/reg-head`; the function form is `re-frame.ssr.head/reg-head`.
- **Example**:
  ```clojure
  (rf/reg-head :app/head
    (fn [db _route]
      {:title (str "MyApp — " (:page-title db))
       :meta  [{:name "description" :content (:summary db)}]}))
  ```

### `head-model`

- **Kind**: function
- **Signature**:
  ```clojure
  (head-model frame-id)
  (head-model frame-id {:head-id id :route route})
  ```
- **Description**: Returns `frame-id`'s head model: the head selected by `:head-id`, else by the route's `:head` metadata, else the default head, evaluated against the frame's `app-db` and route. Pure and JVM-runnable. The same function as [`re-frame.ssr.head/head-model`](re-frame.ssr.head.md#head-model), which documents route and head selection and its errors in full.
- **Example**:
  ```clojure
  (ssr/head-model :app/request-17)
  ;; => {:title "Hello — Example" :meta [{:name "description" :content "…"}]}

  (ssr/head-model :app/request-17 {:head-id :head/article})
  ```

### `head-model->html`

- **Kind**: function
- **Signature**:
  ```clojure
  (head-model->html head-model)
  (head-model->html head-model {:wrap? bool})
  ```
- **Description**: Renders a head model to its inner-`<head>` HTML fragment; `{:wrap? true}` wraps it in `<head>…</head>`. The same function as [`re-frame.ssr.head/head-model->html`](re-frame.ssr.head.md#head-model-html), which documents the output order.
- **Example**:
  ```clojure
  (ssr/head-model->html (ssr/head-model frame-id) {:wrap? true})
  ```

## Hydration

The page carries the hydration payload in a `__rf_payload` `<script>`. Three checks guard hydration, each with its own trace:

- `:rf.ssr/hydration-mismatch` — the server's render hash differs from the client's. In `:hard-error` mode it throws; otherwise it warns and re-renders client-side rather than mounting a broken DOM.
- `:rf.ssr/version-mismatch` — the payload came from a different framework version.
- `:rf.ssr/schema-digest-mismatch` — the app's schema set has changed since the payload was built.

The reference `:rf/hydrate` handler runs the last two, which check where the payload came from ([client-only fx](#client-only-fx)). [The client side: hydrate, then verify](../ssr/concepts.md#the-client-side-hydrate-then-verify) explains the boot sequence.

### `hydrate!`

- **Kind**: function
- **Signature**:
  ```clojure
  (hydrate! opts) → applied-payload | nil
  ```
- **Description**: Boots the client from the server's payload. Returns the payload it applied, or `nil` on a client-only first load. It runs three steps in order:
    1. **Read** the payload: `:payload` if supplied, else (CLJS) the DOM's `__rf_payload` `<script>`, via [`read-server-payload`](#read-server-payload).
    2. **Hydrate**: `dispatch-sync [:rf/hydrate payload]` against `:frame` before the first render. `:rf/hydrate` replaces the frame's state with the payload's.
    3. **Verify**: call `:render-tree-fn` and compare its hash with the server's (omit `:render-tree-fn` to skip).
    - `hydrate!` installs state only. Adopting the server's DOM is a separate call to the view adapter, such as the Reagent adapter's `render!` with `{:hydrate? true}` ([`re-frame.adapter.reagent`](re-frame.adapter.reagent.md)).
    - Installation is idempotent per payload id: a second `hydrate!` with the same payload finds it live and does not re-seed.
- **Options**:
    - `:frame` — required; the frame to hydrate.
    - `:payload` — the payload map. Required on the JVM; on CLJS, omit it to read the payload from the DOM.
    - `:element-id` (CLJS) — the payload `<script>` id to read when `:payload` is omitted; default `"__rf_payload"`.
    - `:render-tree-fn` — a 0-arity fn returning the client's render tree, for the verify step.
    - `:container` (CLJS) — discover and validate the root manifest beside this root's container, for a page with several roots.
    - `:manifest` — an explicit root manifest, validated in place of discovery.
    - `:root-id` — this root's id, recorded as the payload's installer and named in a conflict. Defaults to the manifest's `:root-id` when a manifest was resolved.
- **Errors**:
    - `:rf.error/no-frame-context` — no `:frame`. Emitted, then thrown.
    - `:rf.error/hydration-frame-id-mismatch` — the payload's `:rf/frame-id` names a different frame than `:frame`. Emitted, then thrown.
    - `:rf.error/frame-payload-conflict` — a different payload is already installed under the same payload id. Thrown before anything is installed.
- **Example**:
  ```clojure
  ;; Client boot: read payload, dispatch :rf/hydrate, then verify —
  ;; synchronously, before the host mounts. The hydration target is
  ;; carried (supplied via :frame), not synthesised.
  (ssr/hydrate! {:frame          :app/main
                 :render-tree-fn #((rf/view :app/root))})
  ```

### `hydrate-page!`

- **Kind**: function
- **Signature**:
  ```clojure
  (hydrate-page! roots) → [{:root-id … :status :hydrated :payload …}
                           | {:root-id … :status :failed :error throwable} …]
  ```
- **Description**: Boots a page with several roots, isolating each root's failure from the others. `roots` is a collection of per-root opts maps: each is the map `hydrate!` takes, plus an optional 0-arity `:mount-fn` that runs right after that root's hydrate, inside the same failure boundary.
    - A root whose hydrate or mount throws is reported with an always-on `:rf.error/root-boot-failed` record, and the remaining roots keep booting.
    - Outcomes come back in input order.
    - A failed root is not retried.

### `read-server-payload`

- **Kind**: function (ClojureScript only)
- **Signature**:
  ```clojure
  (read-server-payload)            → payload-map | nil
  (read-server-payload element-id) → payload-map | nil
  ```
- **Description**: Reads the EDN hydration payload from the DOM's `__rf_payload` `<script>`, or from `element-id` when the host uses a different id. `hydrate!` calls this when `:payload` is omitted.
    - Returns the parsed payload map, or `nil` when the page was not server-rendered (no payload script).
    - A malformed payload script surfaces `:rf.error/malformed-hydration-payload` and returns `nil`, so the host falls back to a client-only first render.
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
- **Description**: Compares the client's post-render hash with the server hash stored by `:rf/hydrate`, and emits `:rf.ssr/hydration-mismatch` when they differ. `hydrate!` calls it for you when given `:render-tree-fn`; call it directly when the host mounts first and must verify the tree it actually mounted.
    - The second argument is a render tree (it is hashed) or a precomputed hash string.
    - `opts` may carry `:first-diff-path`, `:failing-id` and `:server-hash`; `:server-hash` overrides the stored server hash.
    - Two per-frame `:ssr` settings control it. `{:detect-mismatch? false}` skips the comparison. `{:on-mismatch :hard-error}` turns a detected mismatch into a thrown structured exception; the default, `:warn`, records `:recovery :warned-and-replaced`.
- **Example**:
  ```clojure
  ;; Host that mounts first, then verifies the mounted tree explicitly.
  (ssr/verify-hydration! :app/main ((rf/view :app/root)))
  ```

## Streaming

Streaming sends the page shell first, then each marked region as its data settles. Mark a region with `boundary`; in the browser, `streaming-install!` applies the chunks as they arrive. On the server, [`re-frame.ssr.ring/stream-handler`](re-frame.ssr.ring.md#stream-handler) runs the sequence under [Streaming render](#streaming-render). [Streaming](../ssr/streaming.md) walks through the wiring.

### `boundary`

- **Kind**: function (component)
- **Signature**:
  ```clojure
  (boundary attrs & body) → hiccup
  ```
- **Description**: Marks `body` as a streamed region; the same form works on every host. `attrs` requires two keys:
    - `:id` — the boundary's identity, unique on the page; it pairs an arriving chunk with its placeholder. A keyword or a string.
    - `:fallback` — hiccup rendered in the shell while `body` is still resolving, and rendered again by this component when the boundary is reported failed.

    On each host:

    - Server: expands to the `:rf/suspense-boundary` marker the shell walker defers on. That marker is wire syntax; do not write it yourself. Outside a stream, `render-to-string` rejects it with `:rf.error/ssr-suspense-boundary-outside-stream`.
    - Client: renders `body`, or `:fallback` when `:id` is in the page's failed-boundary record, written when the stream finalises. With no record (a client-only mount, a page that did not stream) it renders `body`.

    Malformed `attrs` raise `:rf.error/suspense-boundary-invalid-attrs`.

    This is not React Suspense: there are no promises, thrown thenables or selective hydration. The server decides what defers; the client shows the fallback and swaps in content as chunks arrive.

- **Example**:
  ```clojure
  (require '[re-frame.ssr :as ssr])

  [ssr/boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
   [card-view :revenue]]
  ```

### `streaming-install!`

- **Kind**: function (ClojureScript only)
- **Signature**:
  ```clojure
  (streaming-install! opts) → stop! (0-arity fn)
  ```
- **Description**: Installs the client-side streaming runtime, which applies streamed chunks to the page as they arrive. Install it before the first chunks can land; its initial sweep also picks up chunks that arrived earlier. Idempotent per chunk.
    - It watches the document for chunks. It turns each inert fallback `<template>` into a visible mount, swaps in resolved subtrees, and merges each subtree's hydration delta into the `:frame`'s `app-db`.
    - It disconnects itself once the final `__rf_payload` node lands; from there, the bootstrap's `:rf/hydrate` is the canonical reconciliation.
    - The returned `stop!` disconnects it early. Stopping early abandons the stream: finalisation does not run and `:on-ready` never fires.
- **Options**:
    - `:frame` — required. Without it, `streaming-install!` emits and throws `:rf.error/no-frame-context`.
    - `:root` — the DOM root to watch; default `js/document`.
    - `:payload-id` — the final-payload `<script>` id; default `"__rf_payload"`.
    - `:on-ready` — a 1-arity fn called exactly once when the stream has finalised, with `{:resolved #{ids} :failed #{ids}}`. Call `hydrate!` and the adapter's hydrate from here. It runs synchronously inside `streaming-install!` if the payload had already landed.
- **Example**:
  ```clojure
  ;; Streaming-aware bootstrap: install BEFORE the first chunks can land
  ;; (the initial sweep also covers chunks that arrived earlier).
  (ssr/streaming-install! {:frame :app/main})
  ```

## Error projection

When a server-side handler, effect, subscription or view throws, the error is mapped through an error projector to a client-safe `:rf/public-error` (`:status`, `:code`, `:message`, `:retryable?`), and only that shape reaches the response. A frame names its projector in its `:ssr` config, `{:public-error-id … :dev-error-detail? …}` on `make-frame` / `frame-root`. The config is per frame, not a `configure` key, so frames in one process can use different projectors and detail settings. [When the server throws](../ssr/concepts.md#when-the-server-throws) explains the model.

### `reg-error-projector`

- **Kind**: macro (`rf/reg-error-projector`); also a function, `ssr/reg-error-projector`
- **Signature**:
  ```clojure
  (reg-error-projector id ?metadata projector-fn) → id
  ```
- **Description**: Registers an error projector under `id`; a frame selects it with `:ssr {:public-error-id id}`. `projector-fn` is `(fn [trace-event] :rf/public-error)`. Returns `id`.
    - The return shape is closed. It must carry exactly the four [`public-error-keys`](#public-error-keys): `:status` (an integer in `400`–`599`), `:code` (a keyword), `:message` (a string) and `:retryable?` (a boolean). Nothing may be missing and nothing added, including a `:details` of your own; only the runtime adds `:details`, after validation, under `:ssr {:dev-error-detail? true}`.
    - A non-conforming return makes [`project-error`](#project-error) emit `:rf.error/sanitised-on-projection` and serve [`fallback-public-error`](#fallback-public-error) instead, on every error, for as long as that projector is registered.
    - A custom projector does not inherit the default's tag checks. `:rf.error/no-such-handler` is a `404` only when `[:tags :kind]` is `:route`, and `:rf.error/schema-validation-failure` is a `400` only when `[:tags :where]` is `:event`. Check the tag as the example does. Otherwise an unregistered event id answers `404`, and a server-side `:where :fx-args` failure is reported as the client's fault ([`default-error-projector-fn`](#default-error-projector-fn) explains why).
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

### `default-error-projector-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-error-projector-fn trace-event) → :rf/public-error
  ```
- **Description**: The built-in projector, registered as `:rf.ssr/default-error-projector`. A frame whose `:ssr` config names no `:public-error-id` uses it. It maps:
    - `:rf.error/no-such-handler` → `404 :not-found`, only when the miss's `:kind` tag is `:route` (a URL that matched no route). The category also covers an unregistered event id (`:kind :event`) and a Tool-Pair call naming an unknown frame-id (`:kind :frame`). Those are server defects and get the generic `500`, as does a miss with no `:kind`, because a `404` would tell the client, and any crawler, that its URL was wrong. The category is always-on, so the route miss reaches the projector in release builds too.
    - `:rf.error/no-such-route` → `404`, unconditionally. This category is caller misuse of `route-url` and is reported on the dev-only trace stream, so a release build never reaches this arm.
    - `:rf.error/cofx-value-invalid` (a client-supplied coeffect rejected at dispatch) → `400 :bad-request`.
    - `:rf.error/schema-validation-failure` → `400 :bad-request`, only when the `:where` tag is `:event` (a client-supplied event payload). Server-side surfaces such as `:where :fx-args`, and a record with no `:where`, get the `500`.
    - Everything else → `500 :internal-error` ([`fallback-public-error`](#fallback-public-error)).

    In a release build (`:advanced` with `goog.DEBUG=false`, or the JVM `-Dre-frame.debug=false` an SSR service must set), most schema validation is elided: the `validate-*!` functions return `true`. The `400` schema arm then fires only for handlers registered with `{:boundary? true}`:

    - A boundary handler's own `:schema` is checked in every build. A malformed request body skips the handler, the payload never reaches `app-db`, and one always-on `:rf.error/schema-validation-failure` record (`:source :boundary`, `:where :event`) reaches the projector, so the endpoint answers `400`.
    - That production record carries identifiers only (no event vector, value or explanation), because a boundary payload is attacker-controlled. [Validate with schemas](../core/how-to/validate-with-schemas.md#in-production-what-goes-what-stays) covers `:boundary? true`.
    - The arm sets the status only. To shape the response (field-level errors, submitted values kept), validate in the handler body and emit `[:rf.server/set-status 400]`.
    - The `:rf.error/safe-redirect-*` errors are not projected at all. A refused redirect is the mitigation working, and turning `?next=javascript:alert(1)` into a `500` would be a denial of service.

### `project-error`

- **Kind**: function
- **Signature**:
  ```clojure
  (project-error frame-id trace-event) → :rf/public-error
  ```
- **Description**: Applies `frame-id`'s error projector to a trace event and returns the client-safe public error. The frame's `:ssr {:public-error-id …}` selects the projector; with none named, the default projector applies.
    - Under `:ssr {:dev-error-detail? true}`, the result carries an extra `:details` key holding the raw trace event. It is absent by default.
    - If the projector throws or returns a non-conforming shape, `project-error` emits `:rf.error/sanitised-on-projection` and returns [`fallback-public-error`](#fallback-public-error), so a projector bug cannot bypass the projection.
    - A configured `:public-error-id` that names no registered projector gets the same fallback, and the `:rf.error/sanitised-on-projection` record carries `:projection-failure-reason :missing-projector`.
- **Example**:
  ```clojure
  ;; Turn an internal error-trace event into the frame's client-safe
  ;; public-error projection (host adapter / error-page render path).
  (ssr/project-error :rf/default trace-event)
  ;; => {:status 404 :code :not-found :message "Page not found" :retryable? false}
  ```

### `public-error-keys`

- **Kind**: var
- **Signature**:
  ```clojure
  public-error-keys   ;; => #{:status :code :message :retryable?}
  ```
- **Description**: The four keys of the `:rf/public-error` shape. Conforming projector output carries exactly these; the runtime adds `:details` only under `:dev-error-detail?`.

### `fallback-public-error`

- **Kind**: var
- **Signature**:
  ```clojure
  fallback-public-error
  ;; => {:status 500 :code :internal-error :message "Something went wrong" :retryable? false}
  ```
- **Description**: The generic `500` public error. The runtime returns it whenever the active projector throws or returns a non-conforming shape.

## Keyword surfaces

Everything in this section is addressed by keyword rather than imported as a var.

### Events

| Event | What it does |
|---|---|
| `:rf/server-init` | The conventional per-request setup event; the app registers it. It reads request cofx and dispatches setup events. `:platforms #{:server}`. |
| `:rf/hydrate` | Seeds the client frame from the server's payload before the first render, replacing the frame's state (`app-db` and the serialisable `runtime-db` slice). Framework-owned; runs once on client boot. A non-map payload, or a present but non-map `:rf/app-db` / `:rf/runtime-db` slice, is rejected with `:rf.error/malformed-hydration-payload`. A payload `:rf/frame-id` naming a different frame than the dispatch target is rejected with `:rf.error/hydration-frame-id-mismatch`. Either way the frame's state is left unchanged. |

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

All seven are server-only (`:platforms #{:server}`). They write the response accumulator, which the host adapter turns into the HTTP response. [Controlling the response](../ssr/response.md) shows them in use.

| Fx | Args |
|---|---|
| `[:rf.server/set-status int]` | per `:rf.fx.server/set-status-args` |
| `[:rf.server/set-header {:name :value}]` | per `:rf.fx.server/set-header-args` |
| `[:rf.server/append-header {:name :value}]` | per `:rf.fx.server/append-header-args` |
| `[:rf.server/set-cookie :rf.server/cookie]` | a structured cookie map |
| `[:rf.server/delete-cookie {:name ?:path ?:domain}]` | — |
| `[:rf.server/redirect {:location ?:status}]` | Default `:status 302`; the HTML body is dropped. Use it for a `:location` you trust. |
| `[:rf.server/safe-redirect {:location ?:relative-only? ?:allow ?:status}]` | For an untrusted `:location`, such as a `?next=` parameter. Before setting `:redirect` it parses `:location` (`:rf.error/safe-redirect-invalid-url`), rejects `javascript:`, `data:` and `vbscript:` schemes (`:rf.error/safe-redirect-scheme-rejected`), and enforces the `:relative-only?` / `:allow` allowlist (`:rf.error/safe-redirect-host-disallowed`). |

All seven validate their arguments:

- A header name outside the RFC 7230 token grammar throws `:rf.error/header-invalid-name`. A header value containing CR, LF or NUL throws `:rf.error/header-invalid-value`.
- A cookie `:name` outside the RFC 6265 token grammar, or of an unsupported type, throws `:rf.error/cookie-invalid-name`. Any other cookie attribute (`:value`, `:path`, `:domain`, `:max-age`, `:same-site`, `:expires`) containing CR, LF or NUL throws `:rf.error/cookie-invalid-attribute`, as does a raw `;` in any attribute except the percent-encoded `:value`. The error's `:attribute` slot names the attribute.
- A redirect `:location` containing CR, LF or NUL throws `:rf.error/redirect-invalid-location`. There are no `:url` or `:to` target keys; passing either throws `:rf.error/redirect-retired-target-key`. Use `:location`.
- An argument of the wrong type (a non-map args map, a `:status` that is not an integer in `100`–`599`, a non-string header `:value` or redirect `:location`, a wrongly typed cookie attribute) throws `:rf.error/server-fx-args-invalid` in every build, naming the offending `:key`.
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

Both are client-only (`:platforms #{:client}`). The reference `:rf/hydrate` handler dispatches them after installing the server's state, to check where the payload came from. They are best-effort: a mismatch emits a warning trace and hydration continues.

| Fx | Args |
|---|---|
| `[:rf.ssr/check-version server-value]` | A scalar (the payload's `:rf/version`) or `{:expected ?:actual}`. Without `:actual`, the client value is the SSR artefact's compiled-in pattern-protocol constant, the same value the server stamped, so matching builds compare equal. A mismatch emits `:rf.ssr/version-mismatch`. |
| `[:rf.ssr/check-schema-digest server-value]` | A scalar (the payload's `:rf/schema-digest`) or `{:expected ?:actual}`. Without `:actual`, the client value is the digest of the app's registered schemas, from the schemas artefact; without that artefact the check emits `:rf.ssr/compatibility-check-skipped`. A mismatch emits `:rf.ssr/schema-digest-mismatch`. |

### Subscriptions — there are none

`re-frame.ssr` and `re-frame.ssr.ring` register no subscriptions. Their only registrations are the `:rf/hydrate` event, the server-only and client-only fx above, the `:rf.server/request` coeffect below and the built-in `:rf.ssr/default-error-projector`. There is no `:rf/head` sub and no `:rf/public-error` sub, so `@(rf/subscribe [:rf/head])` cannot resolve. Both keywords name a data shape (`:rf/head-model` and `:rf/public-error`), not a registry entry.

Read them through functions instead:

| What you want | How to read it |
|---|---|
| The active route's head model | [`ssr/head-model`](#head-model), and [`ssr/head-model->html`](#head-model-html) to emit it (see [The head model](#the-head-model)) |
| The client-safe public error | [`project-error`](#project-error), or [`apply-error-projection!`](#apply-error-projection), which also stamps the response `:status` |

The request's response accumulator (status, headers, cookies, redirect) is not a subscription either. It lives outside `app-db`, keyed by frame-id, and is read with [`get-response`](#get-response).

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

`reg-fx` accepts a `:platforms` metadata key: a set containing `:server`, `:client` or both. The effect runs only on the listed platforms. The default is `#{:server :client}`.

```clojure
(rf/reg-fx :my/fx
  {:platforms #{:server}}
  (fn [ctx args] ...))
```

A skipped effect emits a `:rf.fx/skipped-on-platform` trace event, so debug tools can see the gate; a skipped coeffect emits `:rf.cofx/skipped-on-platform`. [`:platforms`: one handler, gated per runtime](../ssr/concepts.md#platforms--one-handler-gated-per-runtime) shows it in an app.

## Framework integration

Not for application code — used by adapters, tools and the test harness.

### `emit-ui-tree`

- **Kind**: function
- **Signature**:
  ```clojure
  (emit-ui-tree tree)
  (emit-ui-tree tree opts) → HTML string
  ```
- **Description**: Serialises an already-rendered version-1 structural tree to an HTML string. Where `render-to-string` takes hiccup and renders it, `emit-ui-tree` takes a tree another renderer has already produced, and calls nothing: no view, no subscription, no frame binding. Every dynamic value is already a literal in the tree. Pure, JVM-runnable and deterministic to the byte.
    - It emits the markup for one root's tree. Manifests, payloads, root identity and the HTTP response come from the SSR artefact's other functions.
    - `opts` takes one key, `:doctype?`, which prefixes `<!DOCTYPE html>`. Other keys are ignored; `render-to-string`'s options do not apply.
- **Errors**:
    - `:rf.error/ssr-ui-tree-version-unsupported` — the root's `:rf.ui/tree-version` is missing, not an integer, or unsupported. Checked first, before any emission, with `{:got <received> :supported #{1}}`. This signals deploy skew: the server is older than the tree it was handed.
    - `:rf.error/ui-tree-malformed` — a structurally invalid node past the version check. This is the shared tree-consumer id and signals a code bug.
    - `:rf.error/invalid-tag-name` — an element's `:tag` is outside the HTML5 / SVG / MathML element-name grammar. The tag is read as an element name exactly as written, never as `.class#id` shorthand, and is rejected before any markup is composed.
    - `:rf.error/ssr-invalid-attribute-name` — an emitted attribute name is outside the HTML5 attribute-name grammar. Names are checked after the conversion table is applied, because a name is written into the tag unescaped.
- **Example**:
  ```clojure
  ;; The tree arrives already rendered; this call only folds it to markup.
  (ssr/emit-ui-tree tree {:doctype? true})
  ```

### Streaming render

The server half of streaming, in the order a host calls it: `streaming-render-shell` renders the shell and collects continuations, `streaming-render-continuation` renders each deferred subtree, and `streaming-build-final-payload` builds the final `__rf_payload` chunk. The four template builders produce the chunk markup for each boundary. [`re-frame.ssr.ring/stream-handler`](re-frame.ssr.ring.md#stream-handler) runs this sequence for you.

#### `streaming-render-shell`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-render-shell root-hiccup)
    → {:shell-html "..." :continuations [{:id :subtree :fallback} ...]}
  ```
- **Description**: Renders the shell in one walk of the tree. At each `:rf/suspense-boundary` it emits a `<template …suspense-fallback>` placeholder and records a continuation. Returns the shell HTML, ready to flush, and the continuations to drain.
- **Example**:
  ```clojure
  ;; Host adapter: render the shell to flush immediately, keep the continuations.
  (let [{:keys [shell-html continuations]}
        (rf/with-frame fid (ssr/streaming-render-shell hiccup))]
    ;; flush shell-html now; drain `continuations` as each subtree settles
    shell-html)
  ```

#### `streaming-render-continuation`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-render-continuation frame-id entry)
    → {:id :html :delta :failed? :continuations}
  ```
- **Description**: Renders one continuation against `frame-id`'s `app-db`.
    - It snapshots `app-db` before and after, and returns the subtree's change as `:delta`.
    - A nested `:rf/suspense-boundary` inside the subtree becomes a new continuation, returned under `:continuations` for the host to append to the tail of its FIFO drain queue (`[]` when there are none).
    - On a throw, it emits `:rf.ssr/suspense-boundary-failed` and returns the original fallback HTML with `:failed? true`, no `:delta` and no nested continuations.
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

#### `streaming-build-final-payload`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-build-final-payload frame-id render-hash opts)
    → canonical :rf/hydration-payload
  ```
- **Description**: Builds the final `__rf_payload` chunk. Call it after every continuation has drained.
- **Options**:
    - `:payload` — required; the fail-closed payload policy. A vector allowlist of top-level `app-db` keys, or `:rf.ssr.payload/whole-app-db` to ship the whole `app-db`. Omitting it throws `:rf.error/ssr-missing-payload-policy`.
    - `:version` — overrides the payload's `:rf/version`, which otherwise comes from the SSR artefact's compiled-in pattern-protocol constant.
    - `:client-frame-id` — the stable wire `:rf/frame-id`. Absent, the payload omits the key.
    - `:failed-boundaries` — the set of boundary ids whose continuation returned `:failed? true`. It is carried into the `runtime-db` slice the client `boundary` reads.
    - `:head-hash`, `:schema-digest`, `:payload-include-sensitive`.
- **Example**:
  ```clojure
  ;; After every continuation drains, build the canonical __rf_payload chunk.
  ;; No :version opt — the builder sources :rf/version from the SSR artefact's
  ;; compiled-in pattern-protocol constant. Pass :version only to force skew.
  (rf/with-frame fid
    (ssr/streaming-build-final-payload
      fid render-hash {:payload :rf.ssr.payload/whole-app-db}))
  ```

#### `streaming-fallback-template`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-fallback-template id fallback-html) → HTML string
  ```
- **Description**: Wraps a boundary's fallback markup in the inline `<template data-rf2-suspense-fallback>` placeholder that goes in the shell. A `<template>`'s content is inert and never painted; the client streaming runtime turns each one into a visible mount.

#### `streaming-resolved-template`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-resolved-template id resolved-html) → HTML string
  ```
- **Description**: Builds the chunk flushed when a continuation drains successfully. The client streaming runtime swaps the matching fallback placeholder for this content.

#### `streaming-failed-template`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-failed-template id fallback-html) → HTML string
  ```
- **Description**: Builds the chunk for a failed continuation: the same shape as `streaming-resolved-template`, plus a `data-rf2-suspense-failed` marker. The fallback stays inline and the client runtime reports the failure without turning the page into a `500`.

#### `streaming-hydrate-delta-script`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-hydrate-delta-script id delta-edn) → HTML string
  ```
- **Description**: Builds a subtree's hydration delta chunk (`application/edn`). The client reads the EDN and merges the delta into `app-db` as the subtree streams in.

### The response accumulator

Each per-request frame collects its HTTP response (status, headers, cookies, redirect) in a framework-private store keyed by frame-id. It lives outside `app-db`, so it never reaches the client in the hydration payload. The [server-only fx](#server-only-fx) write it; hosts read the resolved response with `get-response`, which first drains pending error projections. `peek-response` reads without draining.

#### `default-response`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-response) → response-map
  ```
- **Description**: Returns the starting response: status `200`, a `content-type: text/html; charset=utf-8` header, no cookies, no redirect.
- **Example**:
  ```clojure
  (ssr/default-response)
  ;; => {:status   200
  ;;     :headers  [["content-type" "text/html; charset=utf-8"]]
  ;;     :cookies  []
  ;;     :redirect nil}
  ```

#### `get-response`

- **Kind**: function
- **Signature**:
  ```clojure
  (get-response frame-id) → response-map
  ```
- **Description**: Returns the resolved response for a frame; the read host adapters use. It drains any pending error projection first, so `:status` reflects the active projector's output, then strips internal bookkeeping keys. The same drain as `flush-response!`.
- **Example**:
  ```clojure
  ;; Host adapter: after the drain settles, read the response to build the wire reply.
  (ssr/get-response :app/request-frame)
  ;; => {:status 200 :headers [["content-type" "text/html; charset=utf-8"]] :cookies [] :redirect nil}
  ```

#### `peek-response`

- **Kind**: function
- **Signature**:
  ```clojure
  (peek-response frame-id) → response-map
  ```
- **Description**: Returns the resolved response without draining pending error projections. Use it for debugging or mid-request inspection, where `get-response`'s drain would consume a trace the host has not yet handled.

#### `flush-response!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-response! frame-id) → response-map
  ```
- **Description**: Drains any pending error projection for `frame-id`, then returns the resolved response. Every call clears the projector buffer, so only the first call after an error trace projects it; when several traces are pending, the last one wins. `get-response` does the same drain.

#### `flush-response-result!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-response-result! frame-id) → {:response response-map :public-error public-error-or-nil}
  ```
- **Description**: The same drain as `flush-response!`, returning the projected `:rf/public-error` beside the response (`nil` when no projection fired). `flush-response!` and `get-response` return this map's `:response`.
    - Host adapters branch on `:public-error` to classify the outcome: a projected 4xx keeps the app body, a projected 5xx switches to the error page. They cannot infer this from `:status`, because an app can set a `500` itself with nothing projected.
    - A second call returns `:public-error nil`, since the projection has been consumed.

#### `apply-error-projection!`

- **Kind**: function
- **Signature**:
  ```clojure
  (apply-error-projection! frame-id)
  (apply-error-projection! frame-id trace-event)
  ```
- **Description**: Projects an error trace event through `frame-id`'s projector, writes the public error's `:status` to the response, and returns the public-error map. Returns `nil` when there is nothing to do: the frame is missing, is not a server frame, or has no pending trace.
    - 1-arity: drains the frame's error-trace buffer and projects the last trace.
    - 2-arity: projects the given trace, for hosts that catch errors outside the trace stream.
    - When the response already holds a `:redirect`, its status is kept. The public-error map is still returned, but `:status` is not overwritten.

#### `project-render-exception!`

- **Kind**: function
- **Signature**:
  ```clojure
  (project-render-exception! frame-id throwable) → :rf/public-error | nil
  ```
- **Description**: Projects a render-time `Throwable` through `frame-id`'s error projector; host adapters wrap their `render-to-string` call with it. Returns the public-error map, which the host renders as the error body, or `nil` when projection does not apply.
    - It builds a `:rf.error/ssr-render-failed` trace carrying the exception and projects it with `apply-error-projection!`, which writes the response `:status`.
    - It also emits the trace, so monitoring listeners see the full internal detail.
    - Under the per-frame dev setting `:ssr {:on-view-exception :throw}`, it re-throws the exception unchanged instead of projecting.

### Request context

The host adapter stores each request in a per-frame slot before the drain; the [`:rf.server/request`](#coeffects) cofx gives server-side handlers that value. Frame teardown clears the slot.

#### `set-request!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-request! frame-id request) → frame-id
  ```
- **Description**: Stores `request` in the frame's request slot. A host adapter calls it once per request, before starting the drain. The shape is the host's: the Ring adapter passes the Ring request map, other adapters their native shape. The runtime never inspects it.
- **Example**:
  ```clojure
  ;; Host adapter: stash the active request before driving the drain.
  (ssr/set-request! :app/request-frame ring-request)
  ```

#### `get-request`

- **Kind**: function
- **Signature**:
  ```clojure
  (get-request frame-id) → request | nil
  ```
- **Description**: Returns the active request for `frame-id`, or `nil` when no host adapter has stored one. Host adapters and tools may call it.

#### `clear-request!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-request! frame-id) → frame-id
  ```
- **Description**: Clears the frame's request slot. Host adapters call it after building the response, usually as part of frame teardown. Safe to call when the slot is empty.

#### `on-frame-destroyed!`

- **Kind**: function
- **Signature**:
  ```clojure
  (on-frame-destroyed! frame-id)
  ```
- **Description**: Drops the frame's SSR state: its pending error traces, request slot and response slot, and its claim on a hydration payload. Frame teardown calls it for you. Idempotent: a second call for the same frame-id does nothing.

### `drain-blocking-resources!`

- **Kind**: function
- **Signature**:
  ```clojure
  (drain-blocking-resources! frame-id)
  (drain-blocking-resources! frame-id opts)
  ```
- **Description**: Waits for the current navigation's blocking resources on `frame-id` to settle, or for the render deadline, so the render sees settled data rather than a hung `:loading`. The host render path calls it after frame setup and route resolution, before rendering. Returns `{:settled? :timed-out :route-blocking-failure}`.
    - Without the resources artefact it does nothing and returns `{:settled? true}`.
- **Options** (all optional):
    - `:ssr-blocking-timeout-ms` — the wall-clock budget; default `5000`.
    - `:pump!` — a 1-arity `(fn [tick-ms] …)` that pumps pending events. The default yields to the host platform, so an in-flight async reply can land between checks.
    - `:tick-ms` — the polling interval hint; default `5`.
- **Example**:
  ```clojure
  ;; Host render path: settle blocking resources before walking the tree.
  (ssr/drain-blocking-resources! :app/request-frame {:ssr-blocking-timeout-ms 5000})
  ;; => {:settled? true :timed-out [] :route-blocking-failure nil}
  ```

## See also

- [`re-frame.ssr.ring`](re-frame.ssr.ring.md) — the Ring handler that runs a request through this namespace and writes the response.
- [`re-frame.ssr.head`](re-frame.ssr.head.md) — where `head-model` and `head-model->html` are defined.
- [`re-frame.core`](re-frame.core.md) — `init!`, `make-frame`, and the `rf/reg-head` / `rf/reg-error-projector` facade entries.
- [`re-frame.routing`](re-frame.routing.md) — routes select a head with `:head` metadata.
- [Server-side rendering](../ssr/index.md) — the guide.
