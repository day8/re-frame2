# re-frame.ssr

Render a re-frame2 app to HTML on the server and resume it in the browser. Each request renders in its own frame: its events run to completion, the root view becomes an HTML string, and the resulting state ships in the page as a hydration payload. On the client, `hydrate!` installs that state before the first render, and the view adapter then adopts the server's markup. Most apps serve requests through the Ring handler in [`re-frame.ssr.ring`](re-frame.ssr.ring.md), which calls this namespace for them.

Ships in the `day8/re-frame2-ssr` artefact; require `re-frame.ssr` once at boot. Without it, `rf/reg-head` and `rf/reg-error-projector` throw `:rf.error/ssr-artefact-missing`, naming the artefact and the namespace to require.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.ssr  :as ssr])
```

The examples use an app-registered setup event, `:app/server-init`, and the `:app/root` view. The [SSR tutorial](../ssr/tutorial.md) builds both.

```clojure
;; Server (JVM): render one request in its own frame. In production,
;; re-frame.ssr.ring/ssr-handler does this for every request and writes the
;; hydration payload into the page beside the HTML.
(rf/init! ssr/adapter)

(rf/with-new-frame [f (rf/make-frame {})]
  (rf/dispatch-sync [:app/server-init] {:frame f})
  (ssr/render-to-string [(rf/view :app/root)] {:doctype? true}))
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
    - Inline `<script>` / `<style>` string content is HTML raw text: emitted verbatim, never entity-escaped and never refused. The only rewrite is React's: an embedded `</script>` / `</style>` is respelled so the parser cannot end the element early.
    - Put structured data on its own channel: JSON-LD and other head content through `reg-head`, which escapes `<` as `\u003c`, and state through the `__rf_payload` hydration payload.
    - It drops props that have no HTML form, as react-dom does: `on*` handlers and other fn-valued props, `:key` and `:ref`, and the `:children` and `:dangerouslySetInnerHTML` props. `:dangerouslySetInnerHTML` becomes the element's body instead of an attribute: its `:__html` is written raw, as react-dom writes it, so the markup is yours to make safe. It wins over any children, a void element ignores it, and a `nil` `:__html` gives an empty body. A `javascript:` URL in `:href`, `:src`, `:action`, `:form-action` or `:xlink-href` is replaced by react-dom's blocked-URL placeholder.
- **Options** (all optional):
    - `:doctype?` — prefixes `<!DOCTYPE html>`.
- **Errors**:
    - `:rf.error/invalid-tag-name` — a malformed tag name.
    - `:rf.error/invalid-hiccup-head` — a vector head that is neither a keyword nor a callable (a string, `nil`, a number, a collection), or an unrecognised `:rf/*` keyword head.
    - `:rf.error/ssr-invalid-attribute-name` — a malformed attribute key.
    - `:rf.error/ssr-reagent-native-head` — a `:>` interop head.
    - `:rf.error/ssr-nonrenderable-component` — a component still returned a fn after its Form-2 render fn was called; return hiccup from the render fn.
- **Example**:
  ```clojure
  ;; Render a settled frame as a whole document.
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:app/server-init] {:frame f})
    (ssr/render-to-string [(rf/view :app/root)] {:doctype? true}))
  ```

`render-to-string` runs in the browser too, so the cell below shows the HTML for a
tree that hits the rules above: shorthand joined with `:class`, escaped text and
attributes, a void element, dropped props, a blocked `javascript:` URL,
`:dangerouslySetInnerHTML` and script raw text. Edit the tree and press Mod-Enter.

```cljs-rf2
(require '[re-frame.ssr :as ssr])

(def tree
  [:div#cart.panel {:class "open" :key "cart" :on-click (fn [_] nil)}
   [:h2 "Fish & chips <today>"]
   [:img {:src "/img/fish.png" :alt "A \"fresh\" fish"}]
   [:a {:href "javascript:alert(1)"} "A blocked link"]
   [:div {:dangerouslySetInnerHTML {:__html "<em>written raw</em>"}}]
   [:script "if (a < b) { done = \"</script>\"; }"]])

[:pre {:style {:white-space "pre-wrap"}}
 (ssr/render-to-string tree)]
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
- **Description**: Registers a head function under a namespaced keyword `id`; a route selects it with `:head id` in its metadata. Returns `id`. Registering the same id again replaces its definition.
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
 :rf/frame-id      :rf/default ;; only when the server was given a :client-frame-id
 :rf/schema-digest "…"}        ;; only when the server was given a :schema-digest
```

On the client, `hydrate!` installs that state into the frame before the first render; then the view adapter adopts the server's DOM, as in the [`hydrate!` example](#hydrate). Three reports guard hydration, and each lets the page carry on:

- `:rf.ssr/hydration-mismatch` — React recovered from a difference between the client's first render and the server's markup (differing text, or a missing, extra or wrong-type element) while a root hydrated. The hydrating root reports it, not `hydrate!`: the adapter's `render!` with `{:hydrate? true}` installs the reporter on every hydrating root, in every build. In development it emits a `:warning` trace whose tags are React's message (`:error`) and the reporting root's door (`:where`), with `:recovery :warned-and-replaced` at the trace's top level; in every build it also emits an always-on error record under the same id, carrying `:where`, `:recovery` and, when the root knows it, `:frame`, never the message. An attribute-only difference is not a recovery and is not reported; React's development `console.error` is the only signal ([When the renders disagree](../ssr/concepts.md#when-the-renders-disagree)).
- `:rf.ssr/version-mismatch` — the payload and client disagree on the SSR pattern-protocol version, which is separate from the library release version.
- `:rf.ssr/schema-digest-mismatch` — the app's schema set has changed since the payload was built.

`:rf/hydrate` runs the last two, which check that the payload came from a compatible build ([client-only fx](#client-only-fx)). A payload that is not a map, or names a different frame, is refused before anything is installed ([`hydrate!`](#hydrate)'s errors). [The client side: hydrate, then adopt](../ssr/concepts.md#the-client-side-hydrate-then-adopt) explains the boot sequence.

The Node renderer's state is not this payload: [`re-frame.ssr.ring.node/renderer`](re-frame.ssr.ring.node.md#renderer) projects it under its own `:render-state` policy, and the render module reads it back with `deserialize` from `re-frame.ssr.render-state`, an implementation namespace with no page of its own.

### `hydration-payload`

- **Kind**: function (server)
- **Signature**:
  ```clojure
  (hydration-payload frame opts) → payload-map
  ```
- **Description**: Builds the hydration payload for a server frame, for a host that runs its own request lifecycle instead of [`ssr-handler`](re-frame.ssr.ring.md). It applies the same policy and projections `ssr-handler` does, under this frame's classification:
    - `:rf/app-db` is the app-db keys the `:payload` policy allows, with any path the frame classifies `:sensitive` redacted.
    - `:rf/runtime-db` is only the durable runtime state (the route, machine snapshots, resource entries), with classified values redacted and the classification registry left out. It is absent when there is nothing durable to ship.
    - `frame` is the frame's id or the frame value `make-frame` returns. Call it once the frame has settled and before `destroy-frame!`: a frame that is not live projects its app-db as fully redacted.
- **Options**: `ssr-handler`'s payload options.
    - `:payload` — required. A vector of top-level app-db keys to ship (`[]` ships nothing), or `:rf.ssr.payload/whole-app-db`.
    - `:payload-include-sensitive` — app-db paths whose raw value may ship although the frame classifies it `:sensitive`.
    - `:client-frame-id` — a stable client frame id to stamp as `:rf/frame-id`. Leave it out for a per-request frame, whose id would fail the client's frame-id check.
    - `:schema-digest` — stamped as `:rf/schema-digest`.
- **Errors**: a missing or malformed policy throws `:rf.error/ssr-missing-payload-policy`, `:rf.error/ssr-unknown-payload-policy` or `:rf.error/ssr-malformed-payload-allowlist`, the errors `ssr-handler` throws at construction.
- **Example**:
  ```clojure
  ;; Server (JVM), in a hand-rolled request handler, while the per-request
  ;; frame fid is still live. pr-str the result into the page's
  ;; __rf_payload script, through the EDN-aware escaper.
  (ssr/hydration-payload fid {:payload [:articles]})
  ;; => {:rf/version 1, :rf/app-db {:articles […]}}
  ```

### `hydrate!`

- **Kind**: function
- **Signature**:
  ```clojure
  (hydrate! opts) → applied-payload | nil
  ```
- **Description**: Boots the client from the server's payload. Call it after `rf/init!` and `make-frame`, before the view adapter mounts. It runs two steps in order:
    1. **Read** the payload: `:payload` if supplied, else (CLJS) the DOM's `__rf_payload` `<script>`, via [`read-server-payload`](#read-server-payload).
    2. **Hydrate**: `dispatch-sync [:rf/hydrate payload]` against `:frame` before the first render ([`:rf/hydrate`](#rfhydrate)).
    - Returns the payload it applied, or `nil` when there is none to apply: no payload script (a client-only first load), a payload `:rf/hydrate` refused as malformed, or a `:frame` that is not live (never made, or destroyed). Branch on it to choose between adopting the server's DOM and mounting fresh.
    - `hydrate!` installs state only and compares nothing. Adopting the server's DOM is a separate call to the view adapter, such as the Reagent adapter's `render!` with `{:hydrate? true}` ([`re-frame.adapter.reagent`](re-frame.adapter.reagent.md)), and that root reports any mismatch React recovers from.
    - Installation is idempotent per frame: a second `hydrate!` into the same frame with the same payload finds it installed, skips the seed, and still returns the payload. This is what lets several roots on one page hydrate one frame.
- **Options**:
    - `:frame` — required; the frame to hydrate, as its id or as the frame value `make-frame` returns, the same frame the root `frame-provider` names. The version and schema-digest checks run only on a `:client`-platform frame, the default on CLJS.
    - `:payload` — the payload map. Required on the JVM; on CLJS, omit it to read the payload from the DOM.
    - `:element-id` (CLJS) — the payload `<script>` id to read when `:payload` is omitted; default `"__rf_payload"`.
    - `:root-id` — this root's id, recorded as the payload's installer and named in a conflict.
- **Errors**:
    - `:rf.error/no-frame-context` — no `:frame`. Emitted, then thrown.
    - `:rf.error/hydration-frame-id-mismatch` — the payload's `:rf/frame-id` names a different frame than `:frame`. Emitted, then thrown.
    - `:rf.error/malformed-hydration-payload` — the payload, or its `:rf/app-db` or `:rf/runtime-db` slice, is not a map. Emitted, not thrown: the frame's state is left unchanged and `hydrate!` returns `nil`.
    - `:rf.error/frame-destroyed` — there is a payload, and `:frame` names no live frame. Emitted, not thrown: nothing is installed, and `hydrate!` returns `nil`.
    - `:rf.error/frame-payload-conflict` — a different payload is already installed in the same frame. Thrown before anything is installed.
- **Example**:
  ```clojure
  ;; Client (CLJS), using the same :app/root registration as the server.
  (:require [re-frame.core            :as rf]
            [re-frame.ssr             :as ssr]
            [re-frame.adapter.reagent :as reagent-adapter])

  (rf/init! reagent-adapter/adapter)
  (defonce app-root (reagent-adapter/client-root))
  (rf/make-frame {:id :rf/default :platform :client})

  (let [payload (ssr/hydrate! {:frame :rf/default})]
    (reagent-adapter/render!
      app-root
      [rf/frame-provider {:frame :rf/default} [(rf/view :app/root)]]
      (js/document.getElementById "app")
      {:hydrate? (some? payload)}))
  ```

### `hydrate-page!`

- **Kind**: function
- **Signature**:
  ```clojure
  (hydrate-page! roots) → [{:root-id … :status :hydrated :payload …}
                           | {:root-id … :status :failed :error throwable} …]
  ```
- **Description**: Boots a page with several roots, isolating each root's failure from the others ([Several roots on one page](../ssr/concepts.md#several-roots-on-one-page)). `roots` is a collection of per-root opts maps: each is the map `hydrate!` takes, plus an optional 0-arity `:mount-fn` that runs right after that root's hydrate, inside the same failure boundary. Pass the mount as `:mount-fn` rather than mounting after the call: a mount that throws outside the boundary is not isolated.
    - A root whose hydrate or mount throws is reported with an always-on `:rf.error/root-boot-failed` record carrying `:root-id` and `:phase`: `:hydrate` when `hydrate!` threw (a frame-id mismatch or payload conflict, before anything was installed), `:mount` when `hydrate!` returned and `:mount-fn` threw. The remaining roots keep booting.
    - Outcomes come back in input order. `:status :hydrated` means neither step threw; its `:payload` can be `nil` when `hydrate!` applied nothing. The mount callback still runs in that case.
    - `:mount-fn` receives no payload argument and establishes its own frame context, usually with `rf/frame-provider`. For a page that may have no payload, choose client-only mounting in your boot code; the callback is not automatically switched to it.
    - A failed root is not retried.
- **Example**:
  ```clojure
  ;; This page carries a valid shared payload for two roots in one frame.
  ;; mount-header! and mount-cart! are 0-arity fns that use frame-provider
  ;; for :rf/default and call the adapter's render! with {:hydrate? true}.
  (let [outcomes (ssr/hydrate-page!
                   [{:frame :rf/default :root-id :page/header :mount-fn mount-header!}
                    {:frame :rf/default :root-id :page/cart   :mount-fn mount-cart!}])]
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
    (:rf/version payload))
  ```

## Error projection

When a server-side handler, effect, subscription or view throws, the error is mapped through an error projector to a client-safe `:rf/public-error` (`:status`, `:code`, `:message`, `:retryable?`), and only that shape reaches the response. A frame names its projector in its [`:ssr` config](#frame-ssr-config), `{:public-error-id … :dev-error-detail? …}` on `make-frame` / `frame-root`, or through `ssr-handler`'s `:ssr` option. The config is per frame, not a `configure` key, so frames in one process can use different projectors and detail settings. [When the server throws](../ssr/concepts.md#when-the-server-throws) explains the model.

What the client sees is the projected `:status`.

- Under the Ring handler a projected 4xx keeps the page, so the app renders its own not-found or bad-request view and still hydrates; a projected 5xx replaces the page with the error page, rendered from the public error alone by `ssr-handler`'s `:error-view` ([`ssr-handler`](re-frame.ssr.ring.md#ssr-handler) has the failure table).
- A failed `:initial-events` step is not projected: an event handler, interceptor, coeffect or flow that throws there, or a rejected `:rf.cofx` value, aborts frame construction, and the request answers through `ssr-handler`'s `:on-error`. [`reg-error-projector`](#reg-error-projector) lists which errors reach a projector.
- Only the response is sanitised: the full error still reaches trace listeners and the `:observability :errors` sinks.
- Two failures are reported without changing the status: a head function that throws (the page renders with an empty head) and a refused `:rf.server/redirect` (the page renders without the redirect).

### `reg-error-projector`

- **Kind**: macro (`rf/reg-error-projector`); also a function, `ssr/reg-error-projector`
- **Signature**:
  ```clojure
  (reg-error-projector id ?metadata projector-fn) → id
  ```
- **Description**: Registers an error projector under `id`; a frame selects it with `:ssr {:public-error-id id}`. `projector-fn` is `(fn [trace-event] :rf/public-error)`, and may return `nil` to leave that error to the default projector. Returns `id`.
    - `trace-event` is `{:operation <error id> :op-type :error :tags {…}}`. `:operation` is the `:rf.error/*` id, such as `:rf.error/handler-exception`, `:rf.error/sub-exception` or `:rf.error/ssr-render-failed`; `:tags` carries that error's detail: `:frame`, `:exception` when something threw, and discriminators such as `:kind` and `:where`.
    - Any `:rf.error/*` record stamped with the request frame reaches the projector, except the degradations listed [above](#error-projection). A request under the Ring handler meets `:rf.error/fx-handler-exception`, `:rf.error/sub-exception`, `:rf.error/drain-depth-exceeded`, `:rf.error/no-such-handler`, `:rf.error/schema-validation-failure` and `:rf.error/ssr-render-failed` (any throw while rendering the body, every Node-renderer failure included). `:rf.error/handler-exception`, `:rf.error/interceptor-exception`, `:rf.error/coeffect-exception`, `:rf.error/flow-eval-exception` and `:rf.error/cofx-value-invalid` raised by an `:initial-events` step never reach it: the step aborts frame construction and the request answers through `:on-error`. `:rf.error/no-such-route` never arrives: `route-url` throws it at the call site, so a miss inside a handler reaches the projector as `:rf.error/handler-exception`.
    - The return shape is closed. It must carry exactly the four [`public-error-keys`](#public-error-keys): `:status` (an integer in `400`–`599`), `:code` (a keyword), `:message` (a string) and `:retryable?` (a boolean). Nothing may be missing and nothing added, including a `:details` of your own; only the runtime adds `:details`, after validation, under `:ssr {:dev-error-detail? true}`.
    - A `nil` return defers: [`project-error`](#project-error) applies [`default-error-projector-fn`](#default-error-projector-fn) to the same trace event and records nothing. So handle your own cases and return `nil` for the rest, and the default's `404` and `400` arms still answer them.
    - Any other non-conforming return makes [`project-error`](#project-error) emit `:rf.error/sanitised-on-projection` and serve [`fallback-public-error`](#fallback-public-error) instead, on every error, for as long as that projector is registered.
    - An arm of your own for a category the default gates must check the same tag. `:rf.error/no-such-handler` is a `404` only when `[:tags :kind]` is `:route`, and `:rf.error/schema-validation-failure` is a `400` only when `[:tags :where]` is `:event`. Check the tag as the example does. Otherwise an unregistered event id answers `404`, and a server-side `:where :fx-args` failure is reported as the client's fault ([`default-error-projector-fn`](#default-error-projector-fn) explains why).
- **Example**:
  ```clojure
  (rf/reg-error-projector :app/public-error
    (fn [trace-event]
      ;; Reword the route-miss 404, gated on `:kind :route` as the default
      ;; gates it: an unregistered event id or frame id arrives under the
      ;; same category and is a server defect. Everything else returns nil,
      ;; so the default projector answers it.
      (when (and (= :rf.error/no-such-handler (:operation trace-event))
                 (= :route (get-in trace-event [:tags :kind])))
        {:status     404
         :code       :not-found
         :message    "We couldn't find that page."
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
    | `:rf.error/no-such-route` | always (caller misuse of `route-url`, which throws it at the call site, so only a direct `project-error` call meets it) | the same `404` |
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
    - If the projector returns `nil`, [`default-error-projector-fn`](#default-error-projector-fn) answers the same trace event, and nothing is recorded.
    - If the projector throws or returns any other non-conforming value, `project-error` emits `:rf.error/sanitised-on-projection` and returns [`fallback-public-error`](#fallback-public-error), so a projector bug cannot bypass the projection.
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
- **Description**: The generic `500` public error. The runtime returns it whenever the active projector throws or returns a non-conforming value other than `nil`.

## Frame `:ssr` config

A frame's `:ssr` map holds its SSR settings. Set it on `make-frame`, or, for the per-request server frame, in `ssr-handler`'s `:frame` config. Every key is optional.

| Key | Set on | Default | Effect |
|---|---|---|---|
| `:public-error-id` | server frame | `:rf.ssr/default-error-projector` | The [error projector](#reg-error-projector) this frame's errors go through. |
| `:dev-error-detail?` | server frame | `false` | `true` adds `:details`, the raw trace event, to each projected public error. Leave it off in production. |
| `:on-view-exception` | server frame | project the exception | `:throw` re-throws a render-time exception unchanged instead of projecting it ([`project-render-exception!`](#project-render-exception)), to surface bugs during development. Under the Ring handlers the exception reaches `:on-error` (by default a plain-text `500`). |

```clojure
;; Server: a custom projector, with error detail while developing.
(ssr.ring/ssr-handler {:frame     {:initial-events [[:app/server-init]]
                                   :ssr            {:public-error-id   :app/public-error
                                                    :dev-error-detail? true}}
                       :root-view (fn [] ((rf/view :app/root)))
                       :payload   [:articles]})
```

## Events

`re-frame.ssr` registers `:rf/hydrate`.

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

## Keyword surfaces

Everything in this section is addressed by keyword rather than imported as a var.

### Server-only fx

All six are server-only (`:platforms #{:server}`). They write the response accumulator, which the host adapter turns into the HTTP response. [Controlling the response](../ssr/response.md) shows them in use.

| Fx | Args |
|---|---|
| `[:rf.server/set-status int]` | An integer `100`–`599`. |
| `[:rf.server/set-header {:name :value}]` | Both strings. Replaces every existing header of that name, compared case-insensitively. |
| `[:rf.server/append-header {:name :value}]` | Both strings. Adds the header and keeps existing ones of that name, for multi-valued headers. |
| `[:rf.server/set-cookie {:name :value ?:path ?:domain ?:max-age ?:expires ?:secure ?:http-only ?:same-site}]` | `:name` and `:value` are required strings; `:path` and `:domain` strings; `:max-age` seconds (an integer or string); `:expires` epoch millis (an integer: the Ring handler refuses a string with `:rf.error/cookie-invalid-expires`); `:secure` and `:http-only` booleans; `:same-site` `:strict`, `:lax` or `:none` (or a string). An optional key present with `nil` is absent. |
| `[:rf.server/delete-cookie {:name ?:path ?:domain}]` | Writes the cookie with `:value ""` and `:max-age 0`. Pass the `:path` and `:domain` it was set with. |
| `[:rf.server/redirect {?:location ?:allow ?:status}]` | Default `:status 302`; the HTML body is dropped. Relative-only unless `:allow` names more: a vector of hosts, or `:any`. The checks are listed below the table. Without `:location` the response is a 3xx with no `Location` header, and the Ring handler emits the `:rf.ssr/ssr-redirect-no-target` warning. |

`:rf.server/redirect` rejects a `:location` that fails any of these checks, so an untrusted `?next=` parameter is safe to pass as it is. It reports the check's error and writes no redirect; none of them throws, so the page renders without one.

- The scheme, when there is one, is `http` or `https`. `javascript:`, `data:`, `vbscript:` and every other scheme fail with `:rf.error/redirect-scheme-rejected`.
- The URL parses, and a scheme comes with a host: `http:evil.example` names a scheme but no host. Otherwise `:rf.error/redirect-invalid-url`.
- The URL is a relative reference, or `:allow` permits its host. Otherwise `:rf.error/redirect-host-disallowed`.

`:allow` is a vector of host strings, matched exactly and case-insensitively, or `:any`, which permits any host and still runs the first two checks. Without `:allow` only a relative `:location` passes; a relative `:location` always passes. A `:relative-only?` key is refused, since relative-only is the default.

All six validate their arguments ([Invalid values throw](../ssr/response.md#invalid-values-throw)):

- A header name outside the RFC 7230 token grammar throws `:rf.error/header-invalid-name`. A header value containing CR, LF or NUL throws `:rf.error/header-invalid-value`.
- A cookie `:name` outside the RFC 6265 token grammar, or of an unsupported type, throws `:rf.error/cookie-invalid-name`. Any other cookie attribute (`:value`, `:path`, `:domain`, `:max-age`, `:same-site`, `:expires`) containing CR, LF or NUL throws `:rf.error/cookie-invalid-attribute`, as does a raw `;` in any attribute except the percent-encoded `:value`. The error's `:attribute` slot names the attribute.
- A redirect `:location` containing CR, LF or NUL throws `:rf.error/redirect-invalid-location`. There are no `:url` or `:to` target keys; passing either throws `:rf.error/redirect-retired-target-key`. Use `:location`.
- An argument of the wrong type (a non-map args map, a `:status` that is not an integer in `100`–`599`, a non-string header `:value` or redirect `:location`, a wrongly typed cookie attribute) throws `:rf.error/server-fx-args-invalid`, naming the offending `:key`. The default projector maps it to `500`.
- `:status` and `:redirect` are last-write-wins: a later write in the same drain replaces an earlier one.
- A throw from any of the six is contained like any effect's: that fx is skipped, the rest of the `:fx` vector still runs, and an always-on `:rf.error/fx-handler-exception` record reaches the projector, which maps it to `500`. Under the Ring handler the request answers the error page.

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

  ;; Redirects are relative-only unless :allow names more, so an
  ;; attacker-supplied ?next= param cannot send the browser off-origin.
  (rf/reg-event :auth/bounce
    (fn [_ _]
      {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))

  (rf/reg-event :auth/continue
    (fn [_ [_ next-url]]
      {:fx [[:rf.server/redirect {:location next-url}]]}))

  (rf/reg-event :auth/to-billing
    (fn [_ _]
      {:fx [[:rf.server/redirect {:location "https://billing.example.com/portal"
                                  :allow    ["billing.example.com"]}]]}))
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

`reg-fx` and `reg-cofx` accept a `:platforms` metadata key: a set containing `:server`, `:client` or both. The effect or coeffect runs only on the listed platforms. The default is `#{:server :client}`. It gates effects and coeffects only: an event registration stores the key but the router never reads it, so a server-only event such as the app's per-request setup event ([Reading the request](../ssr/concepts.md#reading-the-request)) stays on the server by being dispatched only there.

```clojure
(rf/reg-fx :my/fx
  {:platforms #{:server}}
  (fn [ctx args] ...))
```

A skipped effect emits a `:rf.fx/skipped-on-platform` trace event, so debug tools can see the gate; a skipped coeffect emits `:rf.cofx/skipped-on-platform`. [`:platforms`: one handler, gated per runtime](../ssr/concepts.md#platforms--one-handler-gated-per-runtime) shows it in an app.

## Framework integration

Not for application code — used by adapters, tools and the test harness.

### The response accumulator

Each per-request frame collects its HTTP response (status, headers, cookies, redirect) in a framework-private store keyed by frame id. It lives outside `app-db`, so it never reaches the client in the hydration payload. The [server-only fx](#server-only-fx) write it; hosts settle it with `flush-response-result!`, which drains pending error projections, and read it with `get-response` or `peek-response`, which never drain.

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
- **Description**: Returns `frame-id`'s response without internal bookkeeping keys. A pure read, the same as `peek-response`: it projects no pending error, so reading never changes what the settle ([`flush-response-result!`](#flush-response-result)) later sees. Settle first for a `:status` that carries the projector's output.
- **Example**:
  ```clojure
  ;; Host adapter: after the settle, read the response to build the wire reply.
  (ssr/get-response :app/request-frame)
  ;; => {:status 200 :headers [["content-type" "text/html; charset=utf-8"]] :cookies [] :redirect nil}
  ```

#### `peek-response`

- **Kind**: function
- **Signature**:
  ```clojure
  (peek-response frame-id) → response-map
  ```
- **Description**: Returns the resolved response without draining pending error projections, so it is safe for debugging or mid-request inspection.

#### `flush-response!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-response! frame-id) → response-map
  ```
- **Description**: The same pure read as [`get-response`](#get-response).

#### `flush-response-result!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-response-result! frame-id) → {:response response-map :public-error public-error-or-nil}
  ```
- **Description**: The settle. Drains `frame-id`'s pending error projections and returns the response beside the projected `:rf/public-error` (`nil` when no projection fired). When several errors are pending, every one is projected and the highest status wins, the later one among equals, so a server fault is not hidden by a 4xx that followed it.
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
    - 1-arity: drains the frame's error-trace buffer, projects every trace, and keeps the highest status (the later one among equals).
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
    - `:pump!` — a 1-arity `(fn [tick-ms] …)` that pumps pending events. The default yields to the host platform, so an in-flight async reply can land between checks. An explicit `nil` disables the pump.
    - `:tick-ms` — the polling interval hint; default `5`.
- **Example**:
  ```clojure
  ;; Host render path: settle blocking resources before walking the tree.
  (ssr/drain-blocking-resources! :app/request-frame {:ssr-blocking-timeout-ms 5000})
  ;; => {:settled? true :timed-out [] :route-blocking-failure nil}
  ```
