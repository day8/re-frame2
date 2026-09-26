# re-frame.ssr

Render a re-frame2 app to HTML on the server and resume it in the browser. Each request renders in its own frame: its events run to completion, the root view becomes an HTML string, and the resulting state ships in the page as a hydration payload. On the client, `hydrate!` installs that state before the first render, and the view adapter then adopts the server's markup. Most apps serve requests through the Ring handler in [`re-frame.ssr.ring`](re-frame.ssr.ring.md), which calls this namespace for them.

Ships in the `day8/re-frame2-ssr` artefact; require `re-frame.ssr` once at boot. Without it, `rf/reg-head` and `rf/reg-error-projector` throw `:rf.error/ssr-artefact-missing`, naming the artefact and the namespace to require.

```clojure
(:require [re-frame.ssr :as ssr])
```

```clojure
;; Server (JVM): render one request in its own frame. In production,
;; re-frame.ssr.ring/ssr-handler does this for every request and writes the
;; hydration payload into the page beside the HTML.
(rf/init! ssr/adapter)

(rf/with-new-frame [f (rf/make-frame {})]
  (rf/dispatch-sync [:app/server-init] {:frame f})
  (ssr/render-to-string [(rf/view :app/root)] {:doctype? true}))

;; Client (CLJS), with [re-frame.adapter.reagent :as reagent-adapter] required:
;; install the server's state before the first render and check it renders
;; the same tree, then let the adapter adopt the server's DOM.
(defonce app-root (reagent-adapter/client-root))

(rf/init! reagent-adapter/adapter)
(rf/make-frame {:id :app :platform :client})
(let [payload (ssr/hydrate! {:frame          :app
                             :render-tree-fn (fn [] ((rf/view :app/root)))})]
  (reagent-adapter/render! app-root
                           [rf/frame-provider {:frame :app} [(rf/view :app/root)]]
                           (js/document.getElementById "app")
                           {:hydrate? (some? payload)}))   ;; nil payload: client-only load
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
    - It drops props that have no HTML form, as react-dom does: `on*` handlers and other fn-valued props, `:key` and `:ref`, and the `:children` and `:dangerouslySetInnerHTML` props. Markup passed through `:dangerouslySetInnerHTML` is therefore not rendered on the server; the element is sent empty. A `javascript:` URL in `:href`, `:src`, `:action`, `:form-action` or `:xlink-href` is replaced by react-dom's blocked-URL placeholder.
- **Options** (all optional):
    - `:doctype?` — prefixes `<!DOCTYPE html>`.
    - `:render-hash` — a hash to stamp as `data-rf-render-hash` on the tree's first DOM element, for client-side mismatch detection. Compute it with `render-tree-hash` over the tree the root view returns, `((rf/view :app/root))`, and use the same value for the payload's `:rf/render-hash`. Without it, no marker is stamped.
- **Errors**:
    - `:rf.error/invalid-tag-name` — a malformed tag name.
    - `:rf.error/invalid-hiccup-head` — a vector head that is neither a keyword nor a callable (a string, `nil`, a number, a collection), or an unrecognised `:rf/*` keyword head.
    - `:rf.error/ssr-invalid-attribute-name` — a malformed attribute key.
    - `:rf.error/ssr-reagent-native-head` — a `:>` interop head.
    - `:rf.error/ssr-suspense-boundary-outside-stream` — a streaming [`boundary`](#boundary) reached this non-streaming emitter.
    - `:rf.error/ssr-nonrenderable-component` — a component still returned a fn after its Form-2 render fn was called; return hiccup from the render fn.
- **Example**:
  ```clojure
  ;; Render a settled frame, stamping the hash the client will check against.
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:app/server-init] {:frame f})
    (let [tree ((rf/view :app/root))]               ;; call the view: a hashable tree
      (ssr/render-to-string tree {:doctype?    true
                                  :render-hash (ssr/render-tree-hash tree)})))
  ```

### `render-tree-hash`

- **Kind**: function
- **Signature**:
  ```clojure
  (render-tree-hash render-tree) → 32-bit FNV-1a structural hash (lowercase hex)
  ```
- **Description**: Returns a structural hash of a render tree. The hash is computed over the tree's canonical EDN, so the same tree hashes the same on JVM and CLJS. Hydration compares the server's hash with the client's first render; a mismatch means the client's first render is not the tree the server rendered.
    - Attribute order does not matter and `nil` values are dropped: `[:div {:class nil}]` and `[:div {}]` hash the same.
    - It does not call views. A view in the tree (a function head) hashes as one fixed token followed by its arguments, so the hash covers the markup the tree spells out and the arguments it passes to views, not what those views render. Hash the tree a view returns, `((rf/view :app/root))`: `[(rf/view :app/root)]` hashes to the same constant for every app ([What the hash covers](../ssr/concepts.md#what-the-hash-covers)).
- **Example**:
  ```clojure
  ;; Capture the hash at render time; it rides the hydration payload as
  ;; :rf/render-hash and is re-checked client-side after the first render.
  (let [hiccup      ((rf/view :app/root))
        render-hash (ssr/render-tree-hash hiccup)]
    {:rf/render-hash render-hash})
  ```

### `adapter`

- **Kind**: var (map)
- **Signature**:
  ```clojure
  ssr/adapter   ;; the SSR substrate adapter map, {:kind :rf.adapter/ssr …}
  ```
- **Description**: The headless substrate adapter for the server (JVM). Pass it to `rf/init!`.
    - Its `:render-to-string` slot is this namespace's `render-to-string`.
    - Its `:render` slot throws `:rf.error/render-on-headless-adapter`; on the server, render with `render-to-string`.
- **Example**:
  ```clojure
  (rf/init! ssr/adapter)
  ```

## The head model

A head model is data describing the page's `<head>`: `:title`, `:meta`, `:link`, `:script`, `:json-ld`, `:html-attrs` and `:body-attrs`. Register a head function with `rf/reg-head` and select it from a route's `:head` metadata; read the model with `head-model` and emit it with `head-model->html`. [Head metadata](../ssr/head.md) shows a complete head and route.

### `reg-head`

- **Kind**: macro (`rf/reg-head`); also a function, `re-frame.ssr.head/reg-head`
- **Signature**:
  ```clojure
  (reg-head id ?metadata head-fn) → id
  ```
- **Description**: Registers a head function under `id`; a route selects it with `:head id` in its metadata. Returns `id`.
    - `head-fn` is `(fn [db route] head-model)`: pure, like a subscription.
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
  (head-model frame-id)                            → :rf/head-model
  (head-model frame-id {:head-id id :route route}) → :rf/head-model
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
  (head-model->html head-model)               → HTML string
  (head-model->html head-model {:wrap? bool}) → HTML string
  ```
- **Description**: Renders a head model to its inner-`<head>` HTML fragment; `{:wrap? true}` wraps it in `<head>…</head>`. The same function as [`re-frame.ssr.head/head-model->html`](re-frame.ssr.head.md#head-model-html), which documents the head model's keys, the output order and the errors.
- **Example**:
  ```clojure
  (ssr/head-model->html (ssr/head-model frame-id) {:wrap? true})
  ```

## Hydration

The server writes the hydration payload into the page as EDN, in a `<script id="__rf_payload" type="application/edn">`:

```clojure
{:rf/version       1           ;; pattern-protocol version, compared by :rf.ssr/check-version
 :rf/app-db        {…}         ;; the app-db keys the server's :payload policy allows
 :rf/runtime-db    {…}         ;; framework state: the route, machine snapshots (optional)
 :rf/render-hash   "a3f29c01"  ;; the server's render-tree hash (optional)
 :rf/head-hash     "…"         ;; the head model's hash (optional)
 :rf/frame-id      :app        ;; only when the server was given a :client-frame-id
 :rf/schema-digest "…"}        ;; only when the server was given a :schema-digest
```

On the client, `hydrate!` installs that state into the frame before the first render and checks the render against the server's; then the view adapter adopts the server's DOM, as in the example at the top of this page. Three checks guard hydration, and by default each reports the problem and lets the page carry on:

- `:rf.ssr/hydration-mismatch` — the client's first render differs from the server's. For views that return hiccup (Reagent, Reagent-slim), `hydrate!` compares render-tree hashes, which needs the payload's `:rf/render-hash`; native UIx and Fresco roots report mismatches through React's hydration instead. The mismatch is reported and the client's render replaces the server's markup; the frame setting `:ssr {:on-mismatch :hard-error}` makes the hash check throw instead. [`verify-hydration!`](#verify-hydration) lists what it reports.
- `:rf.ssr/version-mismatch` — the payload came from a different framework version.
- `:rf.ssr/schema-digest-mismatch` — the app's schema set has changed since the payload was built.

`:rf/hydrate` runs the last two, which check that the payload came from a compatible build ([client-only fx](#client-only-fx)). A payload that is not a map, or names a different frame, is refused before anything is installed ([`hydrate!`](#hydrate)'s errors). [The client side: hydrate, then verify](../ssr/concepts.md#the-client-side-hydrate-then-verify) explains the boot sequence.

The Node renderer's state is not this payload: [`re-frame.ssr.ring.node/renderer`](re-frame.ssr.ring.node.md#renderer) projects it under its own `:render-state` policy, and the render module reads it back with `deserialize` from `re-frame.ssr.render-state`, an implementation namespace with no page of its own.

### `hydrate!`

- **Kind**: function
- **Signature**:
  ```clojure
  (hydrate! opts) → applied-payload | nil
  ```
- **Description**: Boots the client from the server's payload. Call it after `rf/init!` and `make-frame`, before the view adapter mounts. It runs three steps in order:
    1. **Read** the payload: `:payload` if supplied, else (CLJS) the DOM's `__rf_payload` `<script>`, via [`read-server-payload`](#read-server-payload).
    2. **Hydrate**: `dispatch-sync [:rf/hydrate payload]` against `:frame` before the first render ([`:rf/hydrate`](#rfhydrate)).
    3. **Verify**: call `:render-tree-fn` under `:frame` and pass its tree to [`verify-hydration!`](#verify-hydration) (omit `:render-tree-fn` to skip).
    - Returns the payload it applied, or `nil` when there is none to apply: no payload script (a client-only first load), or a payload `:rf/hydrate` refused as malformed. Branch on it to choose between adopting the server's DOM and mounting fresh.
    - `hydrate!` installs state only. Adopting the server's DOM is a separate call to the view adapter, such as the Reagent adapter's `render!` with `{:hydrate? true}` ([`re-frame.adapter.reagent`](re-frame.adapter.reagent.md)).
    - Installation is idempotent per frame: a second `hydrate!` into the same frame with the same payload finds it installed, skips the seed and the verify, and still returns the payload. This is what lets several roots on one page hydrate one frame.
- **Options**:
    - `:frame` — required; the frame to hydrate, as its id or as the frame value `make-frame` returns, the same frame the root `frame-provider` names. The version and schema-digest checks run only on a `:client`-platform frame, the default on CLJS.
    - `:payload` — the payload map. Required on the JVM; on CLJS, omit it to read the payload from the DOM.
    - `:element-id` (CLJS) — the payload `<script>` id to read when `:payload` is omitted; default `"__rf_payload"`.
    - `:render-tree-fn` — a 0-arity fn returning the client's render tree, for the verify step; `hydrate!` calls it once, under `:frame`, so the view's subscriptions resolve. Pass `(fn [] ((rf/view :app/root)))`, calling the view to match what the server hashed. Pass it only when your views return hiccup (Reagent, Reagent-slim). Omit it for native UIx and Fresco roots, whose views return React elements: they report mismatches through the adapter's hydrating render instead.
    - `:container` (CLJS) — this root's container element, for a page with several roots. `hydrate!` reads the root manifest from the element immediately after it, a `<script type="application/edn" data-rf-root>` describing the root, and validates it; a missing or invalid manifest throws `:rf.error/root-manifest-invalid`. The bundled Ring handler writes no root manifest, so pass `:container` only when your host emits one.
    - `:manifest` — an explicit root manifest, validated in place of discovery.
    - `:root-id` — this root's id, recorded as the payload's installer and named in a conflict. Defaults to the manifest's `:root-id` when a manifest was resolved.
- **Errors**:
    - `:rf.error/no-frame-context` — no `:frame`. Emitted, then thrown.
    - `:rf.error/hydration-frame-id-mismatch` — the payload's `:rf/frame-id` names a different frame than `:frame`. Emitted, then thrown.
    - `:rf.error/malformed-hydration-payload` — the payload, or its `:rf/app-db` or `:rf/runtime-db` slice, is not a map. Emitted, not thrown: the frame's state is left unchanged and `hydrate!` returns `nil`.
    - `:rf.error/frame-payload-conflict` — a different payload is already installed in the same frame. Thrown before anything is installed.
    - `:rf.error/root-manifest-invalid` — `:container` has no root manifest beside it, or the manifest (discovered or passed as `:manifest`) is invalid. Thrown before anything is installed.
- **Example**:
  ```clojure
  ;; Client boot: read the payload, dispatch :rf/hydrate, then verify,
  ;; synchronously and before the adapter mounts.
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
- **Description**: Boots a page with several roots, isolating each root's failure from the others ([Several roots on one page](../ssr/concepts.md#several-roots-on-one-page)). `roots` is a collection of per-root opts maps: each is the map `hydrate!` takes, plus an optional 0-arity `:mount-fn` that runs right after that root's hydrate, inside the same failure boundary. Pass the mount as `:mount-fn` rather than mounting after the call: a mount that throws outside the boundary is not isolated.
    - A root whose hydrate or mount throws is reported with an always-on `:rf.error/root-boot-failed` record carrying `:root-id` and `:phase`: `:hydrate` when `hydrate!` threw (a frame-id mismatch, payload conflict or manifest refusal before anything was installed, or a `:hard-error` hash mismatch after the frame was seeded), `:mount` when `hydrate!` returned and `:mount-fn` threw. The remaining roots keep booting.
    - Outcomes come back in input order.
    - A failed root is not retried.
- **Example**:
  ```clojure
  ;; Two roots hydrating one frame. mount-header! and mount-cart! are 0-arity
  ;; fns that call the adapter's render! with {:hydrate? true}.
  (let [outcomes (ssr/hydrate-page!
                   [{:frame :app :root-id :page/header :mount-fn mount-header!}
                    {:frame :app :root-id :page/cart   :mount-fn mount-cart!}])]
    (doseq [{:keys [root-id error]} outcomes
            :when error]
      (js/console.warn "root did not boot:" (pr-str root-id) error)))
  ```

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
  (verify-hydration! frame render-tree)      → nil
  (verify-hydration! frame render-tree opts) → nil
  ```
- **Description**: Compares the client's render hash with the server hash stored by `:rf/hydrate`, and reports a mismatch. `hydrate!` calls it for you when given `:render-tree-fn`; call it directly when the host mounts first and must verify the tree it actually mounted.
    - `frame` is the frame's id, or the frame value `make-frame` returns.
    - The second argument is a render tree (it is hashed with [`render-tree-hash`](#render-tree-hash)) or a precomputed hash string.
    - When the payload carried no `:rf/render-hash` it compares nothing and reports nothing. The Ring handler writes that hash only when its `:root-view` resolves to a tree headed by an HTML element ([`ssr-handler`](re-frame.ssr.ring.md#ssr-handler)).
    - On a mismatch it emits the `:rf.ssr/hydration-mismatch` trace, with tags `:server-hash`, `:client-hash`, `:frame`, `:failing-id` (default `:rf/hydrate`), `:recovery` and, when supplied, `:first-diff-path`. It also emits an always-on error record under the same id, carrying the hashes, `:frame`, `:failing-id` and `:recovery` only. That record reaches the frame's `:observability :errors` sinks in a production build, where the trace is elided.
    - The hash says that the renders differ, not where: no path is computed. `opts` may carry `:first-diff-path` (from your own tree diff), `:failing-id` and `:server-hash`; `:server-hash` overrides the stored server hash ([Which node, and which substrate](../ssr/concepts.md#which-node-and-which-substrate)).
    - Two per-frame `:ssr` settings control it. `{:detect-mismatch? false}` skips the comparison. `{:on-mismatch :hard-error}` turns a detected mismatch into a thrown `ex-info` whose data carries `:rf.error/id :rf.ssr/hydration-mismatch`, both hashes and `:frame`; the default, `:warn`, records `:recovery :warned-and-replaced` and returns.
- **Example**:
  ```clojure
  ;; Host that mounts first, then verifies the mounted tree explicitly.
  (rf/with-frame :app/main
    (ssr/verify-hydration! :app/main ((rf/view :app/root))))

  ;; Fail the CI run on a mismatch instead of warning: set it on the client frame.
  (rf/make-frame {:id :app/main :platform :client :ssr {:on-mismatch :hard-error}})
  ```

## Streaming

Streaming sends the page shell first, with a fallback in place of each region marked with `boundary`, then renders each region and sends it as its own chunk. Use it when a few regions are slow to produce and the rest of the page should not wait for them; a page with no such region is simpler to serve without streaming. In the browser, `streaming-install!` applies the chunks as they arrive, and you hydrate from its `:on-ready` callback once the stream has finished. On the server, [`re-frame.ssr.ring/stream-handler`](re-frame.ssr.ring.md#stream-handler) runs the sequence under [Streaming render](#streaming-render). [Streaming](../ssr/streaming.md) walks through the wiring.

### `boundary`

- **Kind**: component (server and client)
- **Signature**:
  ```clojure
  (boundary attrs & body) → hiccup
  ```
- **Description**: Marks `body` as a streamed region. `attrs` requires two keys:
    - `:id` — the boundary's identity, unique on the page and stable across renders; it pairs an arriving chunk with its placeholder. A keyword or a string.
    - `:fallback` — hiccup rendered in the shell while `body` is still resolving, and rendered again by this component when the boundary is reported failed.

    On the server and the client:

    - Server: expands to the `:rf/suspense-boundary` marker the shell walker defers on. That marker is wire syntax; do not write it yourself.
    - Client: renders `body`, or `:fallback` when `:id` is in the page's failed-boundary record, written when the stream finalises. With no record (a client-only mount, a page that did not stream) it renders `body`.

    This is not React Suspense: there are no promises, thrown thenables or selective hydration. The server decides what defers; the client shows the fallback and swaps in content as chunks arrive.

- **Errors**:
    - `:rf.error/suspense-boundary-invalid-attrs` — `attrs` is not a map carrying both `:id` and `:fallback`.
    - `:rf.error/suspense-boundary-duplicate-id` — two boundaries on the page share an `:id` (compared by its printed form). The last one registered gets the chunk and the earlier one keeps its fallback.
    - `:rf.error/ssr-suspense-boundary-outside-stream` — the boundary reached `render-to-string` or `ssr-handler` rather than `stream-handler`.
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
    - It disconnects itself once the final `__rf_payload` node lands; from there, the `hydrate!` you call from `:on-ready` installs the final state.
    - The returned `stop!` disconnects it early. Stopping early abandons the stream: finalisation does not run and `:on-ready` never fires.
    - A page with no payload script at all, such as a client-only load, never finalises either, so `:on-ready` never fires there.
    - Failures do not stop the stream. Each reports an always-on `:rf.ssr/suspense-boundary-failed` record carrying `:id` and `:recovery`: `:inline-fallback` (the server's render of the region threw; its fallback stays), `:skipped-delta` (a delta chunk did not read as an EDN map and was not merged) or `:quarantined-delta` (a delta arrived for a failed boundary and was not merged).
- **Options**:
    - `:frame` — required; the frame that receives the deltas, as its id or as the frame value `make-frame` returns. Without it, `streaming-install!` emits and throws `:rf.error/no-frame-context`.
    - `:root` — the DOM root to watch; default `js/document`.
    - `:payload-id` — the final-payload `<script>` id; default `"__rf_payload"`.
    - `:on-ready` — a 1-arity fn called exactly once when the stream has finalised, with `{:resolved #{ids} :failed #{ids}}`. Call `hydrate!` and the adapter's hydrating render from here, and only from here: hydrating earlier, on a timer or by polling for the payload, meets DOM that still carries the stream's wrappers, and React discards the streamed markup. It runs synchronously inside `streaming-install!` if the payload had already landed, so define everything it uses first.
- **Example**:
  ```clojure
  ;; Streaming bootstrap: install before the first chunks can land (the initial
  ;; sweep also covers chunks that arrived earlier), and hydrate on :on-ready.
  (defonce app-root (reagent-adapter/client-root))

  (rf/make-frame {:id :app/main :platform :client})
  (ssr/streaming-install!
    {:frame    :app/main
     :on-ready (fn [_outcomes]
                 (let [payload (ssr/hydrate! {:frame :app/main})]
                   (reagent-adapter/render! app-root
                                            [rf/frame-provider {:frame :app/main} [(rf/view :app/root)]]
                                            (js/document.getElementById "app")
                                            {:hydrate? (some? payload)})))})
  ```

## Error projection

When a server-side handler, effect, subscription or view throws, the error is mapped through an error projector to a client-safe `:rf/public-error` (`:status`, `:code`, `:message`, `:retryable?`), and only that shape reaches the response. A frame names its projector in its [`:ssr` config](#frame-ssr-config), `{:public-error-id … :dev-error-detail? …}` on `make-frame` / `frame-root`, or through `ssr-handler`'s `:ssr` option. The config is per frame, not a `configure` key, so frames in one process can use different projectors and detail settings. [When the server throws](../ssr/concepts.md#when-the-server-throws) explains the model.

What the client sees is the projected `:status`.

- Under the Ring handler a projected 4xx keeps the page, so the app renders its own not-found or bad-request view and still hydrates; a projected 5xx replaces the page with the error page, rendered from the public error alone by `ssr-handler`'s `:error-view` ([`ssr-handler`](re-frame.ssr.ring.md#ssr-handler) has the failure table).
- A failed `:initial-events` step is not projected: an event handler, interceptor, coeffect or flow that throws there, or a rejected `:rf.cofx` value, aborts frame construction, and the request answers through `ssr-handler`'s `:on-error`. [`reg-error-projector`](#reg-error-projector) lists which errors reach a projector.
- Only the response is sanitised: the full error still reaches trace listeners and the `:observability :errors` sinks.
- Three failures are reported without changing the status: a head function that throws (the page renders with an empty head), a refused `:rf.server/safe-redirect` (the page renders without the redirect) and a failed streaming boundary (the region keeps its fallback).

### `reg-error-projector`

- **Kind**: macro (`rf/reg-error-projector`); also a function, `ssr/reg-error-projector`
- **Signature**:
  ```clojure
  (reg-error-projector id ?metadata projector-fn) → id
  ```
- **Description**: Registers an error projector under `id`; a frame selects it with `:ssr {:public-error-id id}`. `projector-fn` is `(fn [trace-event] :rf/public-error)`. Returns `id`.
    - `trace-event` is `{:operation <error id> :op-type :error :tags {…}}`. `:operation` is the `:rf.error/*` id, such as `:rf.error/handler-exception`, `:rf.error/sub-exception` or `:rf.error/ssr-render-failed`; `:tags` carries that error's detail: `:frame`, `:exception` when something threw, and discriminators such as `:kind` and `:where`.
    - Any `:rf.error/*` record stamped with the request frame reaches the projector, except the degradations listed [above](#error-projection). A request under the Ring handler meets `:rf.error/fx-handler-exception`, `:rf.error/sub-exception`, `:rf.error/drain-depth-exceeded`, `:rf.error/no-such-handler`, `:rf.error/schema-validation-failure` and `:rf.error/ssr-render-failed` (any throw while rendering the body, every Node-renderer failure included). `:rf.error/handler-exception`, `:rf.error/interceptor-exception`, `:rf.error/coeffect-exception`, `:rf.error/flow-eval-exception` and `:rf.error/cofx-value-invalid` raised by an `:initial-events` step never reach it: the step aborts frame construction and the request answers through `:on-error`. `:rf.error/no-such-route` arrives in development builds only.
    - The return shape is closed. It must carry exactly the four [`public-error-keys`](#public-error-keys): `:status` (an integer in `400`–`599`), `:code` (a keyword), `:message` (a string) and `:retryable?` (a boolean). Nothing may be missing and nothing added, including a `:details` of your own; only the runtime adds `:details`, after validation, under `:ssr {:dev-error-detail? true}`.
    - A non-conforming return makes [`project-error`](#project-error) emit `:rf.error/sanitised-on-projection` and serve [`fallback-public-error`](#fallback-public-error) instead, on every error, for as long as that projector is registered.
    - A custom projector does not inherit the default's tag checks. `:rf.error/no-such-handler` is a `404` only when `[:tags :kind]` is `:route`, and `:rf.error/schema-validation-failure` is a `400` only when `[:tags :where]` is `:event`. Check the tag as the example does. Otherwise an unregistered event id answers `404`, and a server-side `:where :fx-args` failure is reported as the client's fault ([`default-error-projector-fn`](#default-error-projector-fn) explains why). To add a few cases and keep the default's mapping for the rest, return `(ssr/default-error-projector-fn trace-event)` from your last arm.
- **Example**:
  ```clojure
  (rf/reg-error-projector :app/public-error
    (fn [trace-event]
      ;; Gate the 404 on `:kind :route`: only a URL that matched no route
      ;; is a missing page. An unregistered event id or frame id arrives
      ;; under the same category and is a server defect: 500, not 404.
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

    | Error | When | Returns |
    |---|---|---|
    | `:rf.error/no-such-handler` | `[:tags :kind]` is `:route` (a URL that matched no route) | `{:status 404 :code :not-found :message "Page not found" :retryable? false}` |
    | `:rf.error/no-such-route` | always (caller misuse of `route-url`, reported on the dev-only trace stream, so a release build never reaches it) | the same `404` |
    | `:rf.error/cofx-value-invalid` | always (a client-supplied coeffect rejected at dispatch) | `{:status 400 :code :bad-request :message "Invalid input" :retryable? false}` |
    | `:rf.error/schema-validation-failure` | `[:tags :where]` is `:event` (a client-supplied event payload) | the same `400` |
    | anything else | — | [`fallback-public-error`](#fallback-public-error) (`500`) |

    - An unregistered event id (`:kind :event`), an unknown Tool-Pair frame id (`:kind :frame`), a miss with no `:kind`, and a schema failure on a server-side surface such as `:where :fx-args` all get the `500`: a `404` or `400` would tell the client, and any crawler, that its request was at fault.
    - In a release build (`-Dre-frame.debug=false` on the JVM), the route-miss `404` still fires, because `:rf.error/no-such-handler` is always on. Most schema validation is elided, so the `400` schema arm fires only for handlers registered with `{:boundary? true}`, whose `:schema` is checked in every build. The arm sets the status only; to shape the response, validate in the handler body and emit `[:rf.server/set-status 400]`. [What reaches the projector in a release build](../ssr/concepts.md#what-reaches-the-projector-in-a-release-build) explains the rule.

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

- **Kind**: var (set)
- **Signature**:
  ```clojure
  public-error-keys   ;; => #{:status :code :message :retryable?}
  ```
- **Description**: The four keys of the `:rf/public-error` shape. Conforming projector output carries exactly these; the runtime adds `:details` only under `:dev-error-detail?`.

### `fallback-public-error`

- **Kind**: var (map)
- **Signature**:
  ```clojure
  fallback-public-error
  ;; => {:status 500 :code :internal-error :message "Something went wrong" :retryable? false}
  ```
- **Description**: The generic `500` public error. The runtime returns it whenever the active projector throws or returns a non-conforming shape.

## Frame `:ssr` config

A frame's `:ssr` map holds its SSR settings. Set it on `make-frame`, or, for the per-request server frame, through `ssr-handler`'s `:ssr` option. Every key is optional.

| Key | Set on | Default | Effect |
|---|---|---|---|
| `:public-error-id` | server frame | `:rf.ssr/default-error-projector` | The [error projector](#reg-error-projector) this frame's errors go through. |
| `:dev-error-detail?` | server frame | `false` | `true` adds `:details`, the raw trace event, to each projected public error. Leave it off in production. |
| `:on-view-exception` | server frame | project the exception | `:throw` re-throws a render-time exception unchanged instead of projecting it ([`project-render-exception!`](#project-render-exception)), to surface bugs during development. Under the Ring handlers the exception reaches `:on-error` (by default a plain-text `500`). |
| `:on-mismatch` | client frame | `:warn` | `:hard-error` makes a detected hydration mismatch throw ([`verify-hydration!`](#verify-hydration)). |
| `:detect-mismatch?` | client frame | `true` | `false` skips the hydration hash comparison. |

```clojure
;; Server: a custom projector, with error detail while developing.
(ssr.ring/ssr-handler {:initial-events [[:app/server-init]]
                       :root-view      (fn [] ((rf/view :app/root)))
                       :payload        [:articles]
                       :ssr            {:public-error-id   :app/public-error
                                        :dev-error-detail? true}})

;; Client: fail loudly on a hydration mismatch, for CI.
(rf/make-frame {:id :app :platform :client :ssr {:on-mismatch :hard-error}})
```

## Events

`re-frame.ssr` registers `:rf/hydrate`. `:rf/server-init` is a name the app registers by convention.

### `:rf/hydrate`

- **Kind**: event
- **Payload**:
  ```clojure
  [:rf/hydrate payload]
  ```
- **Description**: Seeds the client frame from the server's payload before the first render: `:rf/app-db` replaces `app-db` and `:rf/runtime-db` replaces the serialisable `runtime-db` slice; a slice the payload omits keeps the frame's current value. [`hydrate!`](#hydrate) dispatches it for you. On a `:client` frame it also runs the [client-only fx](#client-only-fx).
- **Errors** (the frame's state is left unchanged):
    - `:rf.error/malformed-hydration-payload` — a non-map payload, or a present but non-map `:rf/app-db` / `:rf/runtime-db` slice.
    - `:rf.error/hydration-frame-id-mismatch` — the payload's `:rf/frame-id` names a different frame than the dispatch target.
- **Example**:
  ```clojure
  ;; Framework-owned; dispatched on the client (usually via ssr/hydrate!) to
  ;; seed app-db from the payload before the first render.
  (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
  ```

### `:rf/server-init`

- **Kind**: event (registered by the app)
- **Payload**:
  ```clojure
  [:rf/server-init]
  ```
- **Description**: The conventional per-request setup event, named in [`ssr-handler`](re-frame.ssr.ring.md#ssr-handler)'s `:initial-events`. It reads the request through the [`:rf.server/request`](#coeffects) coeffect and dispatches setup events. It is server-only because only the per-request frame's `:initial-events` dispatch it.
- **Example**:
  ```clojure
  ;; :rf/server-init — registered by the app; the per-request frame's
  ;; :initial-events fire it. Here it resolves the route: the Ring handler waits
  ;; for that route's blocking resources before it renders. A fetch started any
  ;; other way is usually still in flight when the render begins.
  (rf/reg-event :rf/server-init
    {:rf.cofx/requires [:rf.server/request]}
    (fn [{:rf.server/keys [request]} _]
      {:fx [[:dispatch [:rf.route/handle-url-change (:uri request)]]]}))
  ```

## Keyword surfaces

Everything in this section is addressed by keyword rather than imported as a var.

### Server-only fx

All seven are server-only (`:platforms #{:server}`). They write the response accumulator, which the host adapter turns into the HTTP response. [Controlling the response](../ssr/response.md) shows them in use.

| Fx | Args |
|---|---|
| `[:rf.server/set-status int]` | An integer `100`–`599`. |
| `[:rf.server/set-header {:name :value}]` | Both strings. Replaces every existing header of that name, compared case-insensitively. |
| `[:rf.server/append-header {:name :value}]` | Both strings. Adds the header and keeps existing ones of that name, for multi-valued headers. |
| `[:rf.server/set-cookie {:name :value ?:path ?:domain ?:max-age ?:expires ?:secure ?:http-only ?:same-site}]` | `:name` and `:value` are required strings; `:path` and `:domain` strings; `:max-age` seconds (an integer or string); `:expires` epoch millis (an integer: the Ring handler refuses a string with `:rf.error/cookie-invalid-expires`); `:secure` and `:http-only` booleans; `:same-site` `:strict`, `:lax` or `:none` (or a string). An optional key present with `nil` is absent. |
| `[:rf.server/delete-cookie {:name ?:path ?:domain}]` | Writes the cookie with `:value ""` and `:max-age 0`. Pass the `:path` and `:domain` it was set with. |
| `[:rf.server/redirect {?:location ?:status}]` | Default `:status 302`; the HTML body is dropped. Use it for a `:location` you trust. Without `:location` the response is a 3xx with no `Location` header, and the Ring handler emits the `:rf.ssr/ssr-redirect-no-target` warning. |
| `[:rf.server/safe-redirect {:location ?:relative-only? ?:allow ?:status}]` | For an untrusted `:location`, such as a `?next=` parameter. `:location` is required; the checks are listed below the table. |

`:rf.server/safe-redirect` rejects a `:location` that fails any of these checks. It reports the check's error and writes no redirect; none of them throws, so the page renders without one.

- The scheme, when there is one, is `http` or `https`. `javascript:`, `data:`, `vbscript:` and every other scheme fail with `:rf.error/safe-redirect-scheme-rejected`.
- The URL parses, and a scheme comes with a host: `http:evil.example` names a scheme but no host. Otherwise `:rf.error/safe-redirect-invalid-url`.
- The URL satisfies `:relative-only?` and `:allow`. Otherwise `:rf.error/safe-redirect-host-disallowed`.

With neither `:relative-only?` nor `:allow`, any absolute `http` or `https` URL passes, whatever its host, so pass one of them to stop an off-origin redirect. `:allow` is a vector of host strings, matched exactly and case-insensitively; a relative `:location` always passes it.

All seven validate their arguments ([Invalid values throw](../ssr/response.md#invalid-values-throw)):

- A header name outside the RFC 7230 token grammar throws `:rf.error/header-invalid-name`. A header value containing CR, LF or NUL throws `:rf.error/header-invalid-value`.
- A cookie `:name` outside the RFC 6265 token grammar, or of an unsupported type, throws `:rf.error/cookie-invalid-name`. Any other cookie attribute (`:value`, `:path`, `:domain`, `:max-age`, `:same-site`, `:expires`) containing CR, LF or NUL throws `:rf.error/cookie-invalid-attribute`, as does a raw `;` in any attribute except the percent-encoded `:value`. The error's `:attribute` slot names the attribute.
- A redirect `:location` containing CR, LF or NUL throws `:rf.error/redirect-invalid-location`. There are no `:url` or `:to` target keys; passing either throws `:rf.error/redirect-retired-target-key`. Use `:location`.
- An argument of the wrong type (a non-map args map, a `:status` that is not an integer in `100`–`599`, a non-string header `:value` or redirect `:location`, a wrongly typed cookie attribute) throws `:rf.error/server-fx-args-invalid`, naming the offending `:key`. In a development build with the schemas artefact loaded, the fx's `:schema` rejects the argument first: `:rf.error/schema-validation-failure` (`:where :fx-args`) is reported and the fx is skipped. The default projector maps both to `500`.
- `:status` and `:redirect` are last-write-wins. A second write in the same drain emits `:rf.warning/multiple-status-set` / `:rf.warning/multiple-redirects`.
- A throw from any of the seven is contained like any effect's: that fx is skipped, the rest of the `:fx` vector still runs, and an always-on `:rf.error/fx-handler-exception` record reaches the projector, which maps it to `500`. Under the Ring handler the request answers the error page.

- **Example**:
  ```clojure
  ;; Shape the HTTP response from a server-side handler. Every :rf.server/* fx
  ;; is :platforms #{:server}, so the client render skips them.
  (rf/reg-event :app/respond
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
    (fn [_ _]
      {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))

  (rf/reg-event :auth/continue
    (fn [_ [_ next-url]]
      {:fx [[:rf.server/safe-redirect {:location next-url :relative-only? true}]]}))
  ```

### Client-only fx

Both are client-only (`:platforms #{:client}`). `:rf/hydrate` dispatches them after installing the server's state, to check that the payload came from a compatible build. They are best-effort: a mismatch emits a warning trace and hydration continues. [Deploy-drift checks come along for free](../ssr/concepts.md#deploy-drift-checks-come-along-for-free) shows the schema digest wired end to end.

| Fx | Args |
|---|---|
| `[:rf.ssr/check-version server-value]` | A scalar (the payload's `:rf/version`) or `{:expected ?:actual}`. Without `:actual`, the client value is the SSR artefact's compiled-in pattern-protocol constant, the same value the server stamped, so matching builds compare equal. A mismatch emits `:rf.ssr/version-mismatch`. |
| `[:rf.ssr/check-schema-digest server-value]` | A scalar (the payload's `:rf/schema-digest`) or `{:expected ?:actual}`. Without `:actual`, the client value is the digest of the app's registered schemas, from the schemas artefact; without that artefact the check emits `:rf.ssr/compatibility-check-skipped`. A mismatch emits `:rf.ssr/schema-digest-mismatch`. |

### Subscriptions — there are none

`re-frame.ssr` and `re-frame.ssr.ring` register no subscriptions. Their only registrations are the `:rf/hydrate` event, the server-only and client-only fx above, the `:rf.server/request` coeffect below and the built-in `:rf.ssr/default-error-projector`. There is no `:rf/head` subscription and no `:rf/public-error` subscription, so `@(rf/subscribe [:rf/head])` cannot resolve. Both keywords name a data shape (`:rf/head-model` and `:rf/public-error`), not a registry entry.

Read them through functions instead:

| What you want | How to read it |
|---|---|
| The active route's head model | [`ssr/head-model`](#head-model), and [`ssr/head-model->html`](#head-model-html) to emit it (see [The head model](#the-head-model)) |
| The client-safe public error | [`project-error`](#project-error), or [`apply-error-projection!`](#apply-error-projection), which also stamps the response `:status` |

The request's response accumulator (status, headers, cookies, redirect) is not a subscription either. It lives outside `app-db`, keyed by frame id, and is read with [`get-response`](#get-response).

### Coeffects

| Cofx | Returns |
|---|---|
| `:rf.server/request` | The active HTTP request map, as the host stored it with [`set-request!`](#set-request) (a Ring request under the bundled adapter), or `nil` when no host stored one. Server only. Its value is not recorded for replay, so use it for decisions ([Reading the request](../ssr/concepts.md#reading-the-request) explains why); a request-derived fact that must stay in `app-db` belongs in an event payload, such as `ssr-handler`'s `(fn [request] …)` form of `:initial-events`. |

- **Example**:
  ```clojure
  ;; A server handler declares the requirement, then reads the request directly
  ;; under :rf.server/request (Ring-shaped under the bundled adapter). It uses
  ;; the request to decide, and copies nothing from it into app-db.
  (rf/reg-event :app/check-method
    {:rf.cofx/requires [:rf.server/request]}
    (fn [{:rf.server/keys [request]} _]
      (if (= :get (:request-method request))
        {}
        {:fx [[:rf.server/set-status 405]]})))
  ```

### `:platforms` fx-gating metadata

`reg-fx` and `reg-cofx` accept a `:platforms` metadata key: a set containing `:server`, `:client` or both. The effect or coeffect runs only on the listed platforms. The default is `#{:server :client}`. It gates effects and coeffects only: an event registration stores the key but the router never reads it, so a server-only event such as [`:rf/server-init`](#rfserver-init) stays on the server by being dispatched only there.

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
  (emit-ui-tree tree)      → HTML string
  (emit-ui-tree tree opts) → HTML string
  ```
- **Description**: Serialises an already-rendered version-1 structural tree to an HTML string. Where `render-to-string` takes hiccup and renders it, `emit-ui-tree` takes a tree that is already rendered, such as the one `re-frame.fresco.test/tree` returns ([L2: one view body as a semantic tree](../core/fresco/15-testing.md#l2-one-view-body-as-a-semantic-tree)), and calls nothing: no view, no subscription, no frame binding. Every dynamic value is already a literal in the tree. Pure, JVM-runnable and deterministic to the byte.
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
    - Throws propagate: every [`render-to-string`](#render-to-string) error, and `:rf.error/suspense-boundary-invalid-attrs` for a boundary without both `:id` and `:fallback`. Boundaries whose `:id`s print the same emit `:rf.error/suspense-boundary-duplicate-id`, and only the last one is kept in `:continuations`.
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
    - It snapshots `app-db` before and after and returns the change as `:delta`: each new or changed top-level key mapped to its full new value, `{}` when nothing changed. The delta is unfiltered, so apply the same `:payload` allowlist as the final payload before writing it with [`streaming-hydrate-delta-script`](#streaming-hydrate-delta-script); `stream-handler` does this for you.
    - A nested `:rf/suspense-boundary` inside the subtree becomes a new continuation, returned under `:continuations` for the host to append to the tail of its FIFO drain queue (`[]` when there are none).
    - On a throw, it emits `:rf.ssr/suspense-boundary-failed` and returns the original fallback HTML with `:failed? true`, no `:delta` and no nested continuations. If the fallback render also throws, `:html` is `""`.
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
    - If `frame-id` is destroyed or re-created while the payload is built, `:rf/app-db` is `:rf/redacted` and `:rf/runtime-db` is omitted.
- **Options**:
    - `:payload` — required; the fail-closed payload policy. A vector allowlist of top-level `app-db` keys, or `:rf.ssr.payload/whole-app-db` to ship the whole `app-db`. Omitting it throws `:rf.error/ssr-missing-payload-policy`.
    - `:version` — overrides the payload's `:rf/version`, which otherwise comes from the SSR artefact's compiled-in pattern-protocol constant. It takes an integer, or a string of digits; any other value is ignored with a `:rf.ssr/invalid-version` warning.
    - `:client-frame-id` — the stable wire `:rf/frame-id`. Absent, the payload omits the key.
    - `:failed-boundaries` — the set of boundary ids whose continuation returned `:failed? true`. It is carried into the `runtime-db` slice the client `boundary` reads.
    - `:head-hash` — written as `:rf/head-hash`; omitted when `nil`.
    - `:schema-digest` — written as `:rf/schema-digest`, for the client's schema-digest check.
    - `:payload-include-sensitive` — the same permit as `ssr-handler`'s option: app-db paths classified `:sensitive` whose raw value may ship.
- **Errors**:
    - `:rf.error/ssr-missing-payload-policy`, `:rf.error/ssr-unknown-payload-policy`, `:rf.error/ssr-malformed-payload-allowlist` — the `:payload` policy, as for `ssr-handler`.
    - `:rf.error/ssr-hydration-payload-invalid` (JVM) — a number the browser would read back as a different value.
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

Each per-request frame collects its HTTP response (status, headers, cookies, redirect) in a framework-private store keyed by frame id. It lives outside `app-db`, so it never reaches the client in the hydration payload. The [server-only fx](#server-only-fx) write it; hosts read the resolved response with `get-response`, which first drains pending error projections. `peek-response` reads without draining.

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
- **Description**: Drains any pending error projection for `frame-id`, so `:status` reflects the active projector's output, then returns the response without internal bookkeeping keys. This is the read host adapters use. Every call clears the projector buffer: only the first call after an error trace projects it, and when several traces are pending the last one wins.
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
- **Description**: The same function as [`get-response`](#get-response).

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
  (apply-error-projection! frame-id)             → :rf/public-error | nil
  (apply-error-projection! frame-id trace-event) → :rf/public-error | nil
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

#### `pending-error-trace?`

- **Kind**: function
- **Signature**:
  ```clojure
  (pending-error-trace? frame-id) → boolean
  ```
- **Description**: Returns `true` when `frame-id` holds an error trace that has not been projected yet. It drains nothing. A host adapter asks it after rendering its error view: a subscription inside the error view that recovered to `nil` leaves a trace here rather than throwing, and that is the signal to fall back to the default error template.

#### `clear-pending-error-traces!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-pending-error-traces! frame-id)
  ```
- **Description**: Drops `frame-id`'s pending error traces without projecting them. [`on-frame-destroyed!`](#on-frame-destroyed) calls it at teardown.

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
  (on-frame-destroyed! frame-id) → nil
  ```
- **Description**: Drops the frame's SSR state: its pending error traces, request slot and response slot, and its claim on a hydration payload. Frame teardown calls it for you. Idempotent: a second call for the same frame does nothing.

### `drain-blocking-resources!`

- **Kind**: function
- **Signature**:
  ```clojure
  (drain-blocking-resources! frame-id)
    → {:settled? bool :timed-out [key …] :route-blocking-failure failure | nil}
  (drain-blocking-resources! frame-id opts)
    → {:settled? bool :timed-out [key …] :route-blocking-failure failure | nil}
  ```
- **Description**: Waits for the current navigation's blocking resources on `frame-id` to settle, or for the render deadline, so the render sees settled data rather than a hung `:loading`. The host render path calls it after frame setup and route resolution, before rendering.
    - `:settled?` is `false` when the deadline passed; `:timed-out` lists the scoped keys it settled as first-load failures (`[]` otherwise); `:route-blocking-failure` is the route's failure record, or `nil`.
    - Without the resources artefact it does nothing and returns `{:settled? true :timed-out [] :route-blocking-failure nil}`.
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
