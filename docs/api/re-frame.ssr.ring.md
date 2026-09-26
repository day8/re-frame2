# re-frame.ssr.ring

Serve server-rendered re-frame2 pages from a Ring-compatible server. `ssr-handler` returns a Ring handler that renders one request per call; `stream-handler` returns one that streams the response in chunks. For each request the handler creates a frame, runs your setup events, renders the root view and returns a Ring response map; it never writes to a socket itself.

Start with `ssr-handler`. Switch to `stream-handler` when a page has regions that are slow to produce and the rest of the page should reach the browser before them: mark those regions with [`ssr/boundary`](re-frame.ssr.md#boundary), and the handler sends the shell first and each region after it. Streaming has costs: it accepts no `:html-shell` or `:renderer`, holds a thread for each response in flight, and needs [`ssr/streaming-install!`](re-frame.ssr.md#streaming-install) on the client. A page with no boundary gains nothing from it.

Ships in the `day8/re-frame2-ssr-ring` artefact, which depends on `day8/re-frame2-ssr`. The rendering, head model, hydration, SSR events and `:rf.server/*` fx this handler drives are documented on [`re-frame.ssr`](re-frame.ssr.md).

```clojure
(:require [re-frame.ssr.ring :as ssr.ring])
```

```clojure
(require '[ring.adapter.jetty :as jetty]
         '[re-frame.core      :as rf]
         '[re-frame.ssr       :as ssr]
         '[re-frame.ssr.ring  :as ssr.ring])

(rf/init! ssr/adapter)

(def handler
  (ssr.ring/ssr-handler
    {:initial-events [[:rf/server-init]]              ;; setup events for each request's frame
     :root-view      (fn [] ((rf/view :app/root)))    ;; rendered once those events settle
     :payload        [:articles :session-user]}))    ;; app-db keys shipped to the client

(jetty/run-jetty handler {:port 3000 :join? false})
```

Those are the three required options; [The simplest server](../ssr/concepts.md#the-simplest-server) walks through what the handler does with them. The `:root-view` fn calls the view, so the handler can hash what it rendered and the client's [`ssr/hydrate!`](re-frame.ssr.md#hydrate) can check its first render against it. The client boot is on [`re-frame.ssr`](re-frame.ssr.md).

## Handler constructors

### `ssr-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (ssr-handler opts) → (fn [ring-request] ring-response)
  ```
- **Description**: Returns a synchronous Ring handler that renders one re-frame2 SSR request per call. For each request it:
    1. stores the Ring request in the per-frame request slot, where the `:rf.server/request` cofx reads it;
    2. creates the per-request frame, draining `:initial-events` synchronously;
    3. reads the resolved response accumulator: if it holds a `:redirect`, returns the redirect; if an error was projected to a 5xx, returns the error page (`:error-view`);
    4. otherwise waits for the route's blocking resources (up to 5 seconds, [`drain-blocking-resources!`](re-frame.ssr.md#drain-blocking-resources)), renders `:root-view`, resolves the head, builds the hydration payload, wraps them in the HTML shell, and turns structured cookies into `Set-Cookie` headers;
    5. destroys the frame in a `finally`.
- **Options**:
    - `:initial-events` (required) — the events dispatched, synchronously and in order, into the per-request frame when it is created. A vector of events, or `(fn [request] → initial-events-vector)` to derive them from the Ring request. The fn form is the replay-safe way to put a request-derived fact into a boot event's payload, e.g. `(fn [req] [[:auth/server-init {:user (extract-user req)}]])`. For non-durable request reads inside a handler, declare `:rf.cofx/requires [:rf.server/request]` on the registration instead.
        - The drain covers synchronous work only. A fetch these events start, such as an `:rf.http/managed` request, is usually still in flight when rendering begins, and the page renders without its data. Data the first render needs belongs in a resource the route declares `:blocking? true` ([`re-frame.routing`](re-frame.routing.md)), which the handler waits for.
        - Omitted: throws `:rf.error/ssr-ring-missing-initial-events` at construction.
        - Neither a vector nor a fn, or a fn returning a non-vector: `:rf.error/invalid-initial-events` per request, answered through `:on-error`.
        - An event handler, interceptor, coeffect or flow that throws during this drain, or a `:rf.cofx` value its `:schema` rejects, aborts frame construction with `:rf.error/initial-events-step-failed`. The request answers through `:on-error`; the error projector and `:error-view` never see it. An effect that throws, or an unregistered event, is projected instead.
    - `:root-view` (required unless `:renderer` is given) — what to render against the per-request frame once the drain settles: a hiccup vector such as `[(rf/view :app/root)]`, or a 0-arity fn returning hiccup. The head must be callable: the Var `rf/reg-view` defines, or `(rf/view :id)`. A keyword head is an HTML element on every host, so `[:app/root]` renders an empty `<root>` element.
        - Both forms emit identical HTML, but only the resolving fn form, `(fn [] ((rf/view :app/root)))`, is hashed. The vector form `[(rf/view :app/root)]` is an unresolved root and gets no `data-rf-render-hash` and no payload `:rf/render-hash`, whatever `:emit-hash?` says. Use the fn form if you want hydration mismatch detection. The same rule applies to what the fn returns: a root view whose body is only another view, `[(rf/view :pages/home)]`, is unresolved too, so the hashed root is one that returns an element, such as `[:div …]`.
        - Without a hash the page still renders and hydrates; the client's check simply compares nothing. A native UIx or Fresco root carries no hash by design: its views return React elements, not hiccup, and it reports mismatches through React's hydration instead.
        - Omitted without `:renderer`: throws `:rf.error/ssr-ring-missing-root-view` at construction. Any other shape raises `:rf.error/invalid-root-view` at render time, projected like any render throw to the 5xx error page.
    - `:payload` (required) — the hydration-payload policy. It fails closed, in one of two shapes:
        - A non-empty vector of top-level `app-db` keys (keywords) is an allowlist, and the recommended form. Only the listed keys ship in `:rf/app-db`; every other key is dropped, including keys added later.
        - `:rf.ssr.payload/whole-app-db` ships all of `app-db`. Use it only when the whole `app-db` is safe to expose.
        - At construction, an absent value or an empty allowlist throws `:rf.error/ssr-missing-payload-policy`, an unrecognised keyword throws `:rf.error/ssr-unknown-payload-policy`, and an allowlist with non-keyword entries throws `:rf.error/ssr-malformed-payload-allowlist`.
        - The payload is written as EDN and read back by the browser, so a number the browser would read back as a different value fails the request with `:rf.error/ssr-hydration-payload-invalid`, naming the path and class: a `Long` or `BigInt` past 2^53, a `BigDecimal`, a `Ratio` or a `Float`, map keys and set members included. Narrow the value (an id to a string, money to integer cents) or leave its key off the allowlist. A symbol whose text contains `</` or `<!` fails with `:rf.error/ssr-edn-script-breakout`. Either failure is projected like a render throw, so the request answers the error page. Under `stream-handler` the final payload is built after the head commits, so the same failure truncates the stream (`:rf.error/ssr-streaming-writer-failed`, `:phase :final-payload`).
    - `:payload-include-sensitive` — a vector of `app-db` paths that the frame classifies `:sensitive` but whose raw value may ship anyway, e.g. `[[:session :csrf]]`. Only paths inside the `:payload` allowlist; without this option every classified value ships as `:rf/redacted`. A malformed value throws `:rf.error/ssr-malformed-payload-allowlist` at construction.
    - `:client-frame-id` — a stable frame id written as the payload's `:rf/frame-id`, for deployments where server and client agree on one ahead of time. Default `nil`, which omits `:rf/frame-id`. Never use a per-request gensym: the client rejects a present and different id with `:rf.error/hydration-frame-id-mismatch`.
    - `:error-view` and `:on-error` — the two failure handlers, described in the table below.
    - `:ssr` — the per-frame `:ssr` config map, e.g. `{:dev-error-detail? true :public-error-id :myapp/projector}`.
    - `:url-strategy` — the per-request frame's `:url-strategy`, passed to `make-frame`, so `route-link` hrefs in the server markup match what the hydrated client produces. A malformed value, an explicit `nil` included, fails the request with `:rf.error/invalid-url-strategy` through `:on-error`.
    - `:fx-overrides` — the per-frame `:fx-overrides` map, passed through as is (e.g. to stub `:rf.http/managed` in tests).
    - The per-request frame takes no other `make-frame` keys. To ship its error and event records off-box, declare the process default with `(rf/configure! {:observability …})` ([`configure!`](re-frame.core.md#configure)).
    - `:ssr-blocking-timeout-ms` — how long the request waits for the route's blocking resources to settle before it renders; default `5000`. A resource still unsettled at the deadline settles as a first-load failure, so the request never hangs. The handler passes it to [`drain-blocking-resources!`](re-frame.ssr.md#drain-blocking-resources).
    - `:emit-hash?` — stamp the `data-rf-render-hash` marker on the root element and `data-rf-head-hash` on `<head>`; default `true`. It controls only those markers: the payload's `:rf/render-hash`, which the client checks, is written whenever the root is hashable (see `:root-view`).
    - `:version` — the hydration payload's `:rf/version`. It defaults to the SSR artefact's pattern-protocol version (`1`), the value the client's version check expects; set it only to force a mismatch.
    - `:schema-digest` — the hydration payload's `:rf/schema-digest`.
    - `:html-shell` — `(body-html payload-edn opts) → string`; default [`default-html-shell`](#default-html-shell). `opts` is the handler's options with this request's values in place: `:head` is the resolved head fragment (or your `:head` string), `:html-attrs` and `:body-attrs` come from the head model, and `:head-hash` is set when `:emit-hash?` is on. Write `:head` inside `<head>`, the attribute bags on `<html>` and `<body>`, and the payload as `default-html-shell` does.
    - `:content-type` — replaces the response Content-Type when supplied. It has no default: omit it (the normal case) and the response keeps `text/html; charset=utf-8`, from the runtime's default or from the app's own `:rf.server/set-header "content-type"`. It applies to successful pages only; a projected error page keeps the accumulated Content-Type. See [`handler-defaults`](#handler-defaults).
    - `:head` and `:body-end` — raw HTML strings injected into the envelope verbatim, without escaping. A page normally gets its `<title>` from the active route's `:head`, declared with `reg-head`, and a page with none ships no `<title>`. A `:head` string replaces that resolved head, default viewport meta included, for a static app without routing.
    - `:script-src` (default `"/main.js"`) and `:app-element-id` (default `"app"`) — the bootstrap script's `src` and the app element's `id`, attribute-escaped. `:script-src false` emits no bootstrap `<script src>`, for an app that boots from `:body-end` (for example with a `type="module"` tag).
    - `:renderer` — a fn that renders the body in place of the local renderer: `(fn [{:keys [frame-id request opts]}] → {:body-html <string> :render-hash <string-or-nil>})`. It is called once per request inside the request frame's scope, after the boot-event drain and the blocking-resource settle, and before head resolution and the payload build use its result. It receives the post-drain frame id, the Ring request and the handler opts, never a hiccup value, and returns only the body markup and an optional render hash.
        - A `nil` `:render-hash` omits both the `data-rf-render-hash` marker and the payload's `:rf/render-hash`.
        - A throw is projected like any render-time throw.
        - Omitted, the handler renders locally: resolve `:root-view`, hash, render.
        - The one other renderer the reference ships is `re-frame.ssr.ring.node/renderer`, which renders on a Node sidecar ([`renderer`](#renderer)).
        - `stream-handler` rejects this option at construction.
    - `:render-state` is not an `ssr-handler` option. It is a required option of `re-frame.ssr.ring.node/renderer`, and a copy at the handler's top level is ignored. It sets which state that renderer can see: a fail-closed per-partition allowlist of top-level keys, or a `(fn [frame-id] → partitions)` projector, in the same `{:rf/app-db {…} :rf/runtime-db {…}}` envelope as the payload but as a separate policy. [Render on Node](../ssr/concepts.md#render-on-node) explains why the two differ.

    `:error-view` and `:on-error` handle two different failures, and a robust deployment wires both. A failure the error projector catches is classified by its projected status. A projected 4xx (a routing miss, bad client input) keeps the app's own not-found or bad-request body and hydration payload and does not call `:error-view`; a projected 5xx ships the error page, with the projected status and no hydration payload. A root view or shell that throws while rendering always ships the error page, whatever status the projector chose, because there is no body to keep. A buggy `:error-view`, whether it throws or depends on a subscription that recovers to `nil`, falls back once to the default template and emits `:rf.error/ssr-ring-error-view-failed`. A buggy `:on-error` falls back to `default-on-error` and emits `:rf.error/ssr-ring-on-error-failed`.

    | Aspect | `:error-view` | `:on-error` |
    |---|---|---|
    | Which failure? | A projected 5xx the error projector catches: an effect that throws or an unregistered event during the drain, a subscription or view that throws while rendering, or an unrenderable root or shell. | A transport / Ring-layer failure the projector cannot see: a throw while setting up the per-request frame, a throw while materialising headers or cookies, or a failed initial event (an event handler, interceptor, coeffect or flow that throws, or a rejected `:rf.cofx` value). |
    | What does it produce? | The error-page body (hiccup). Either a registered-view keyword, resolved as `[(rf/view error-view) public-error]` (an unregistered keyword falls back to the default template), or a `(public-error) → hiccup` fn. It renders through the standard SSR emitter, with no app body or hydration payload beside it. Its HTML is the whole response body: it is not wrapped in `:html-shell`, gets no head from `reg-head`, and has no `<!DOCTYPE html>` added, so return a complete document, `[:html [:head …] [:body …]]`. | A raw Ring response map `{:status … :headers … :body …}`, returned to the server as is. |
    | What is its input? | Only the public-error map, sanitised by the projector and safe to render; never the request, the throwable or the frame. | The raw `(request throwable)`, including the unsanitised throwable. The default never reads it. |
    | Default when omitted? | A minimal default error template. Omitting it does not keep the root body. | A minimal fixed `500` ([`default-on-error`](#default-on-error)) that leaks no internal detail. |

    Never build `:head` or `:body-end` from untrusted input (a CMS field, a tenant-admin form, a query-string parameter): they are injected unescaped, so that is a script-injection hole. For content from outside your trust boundary, use [`reg-head`](re-frame.ssr.md#reg-head) for head fragments, and registered views plus the [`:rf.server/*` fx](re-frame.ssr.md#server-only-fx) for body content. For all four shell-hook options (`:head`, `:body-end`, `:script-src`, `:app-element-id`), `nil` means the default, and any other non-string value except `:script-src false` throws `:rf.error/ssr-trusted-shell-opt-invalid` at construction.

- **Example**:
  ```clojure
  ;; The required three, plus the options most deployments add.
  (def handler
    (ssr.ring/ssr-handler
      {:initial-events (fn [req] [[:auth/server-init {:user (extract-user req)}]])
       :root-view      (fn [] ((rf/view :app/root)))   ;; fn form: hashed for mismatch detection
       :payload        [:articles :session-user]
       :ssr            {:public-error-id :app/public-error}
       :error-view     :app/error-page                  ;; registered view; receives the public error
       :script-src     "/js/main.js"}))
  ```

### `stream-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (stream-handler opts) → (fn [ring-request] ring-response)
  ```
- **Description**: Returns a synchronous Ring handler whose response body streams as the page renders: the page shell first, with each [`boundary`](re-frame.ssr.md#boundary) region's fallback in place, then each region as it renders, then the final hydration payload. The body is a `java.io.PipedInputStream`, and the host server chooses the framing (chunked transfer on HTTP/1.1). Use it for a page with slow `boundary` regions, and `ssr-handler` otherwise; on the client, hydrate from [`ssr/streaming-install!`](re-frame.ssr.md#streaming-install)'s `:on-ready`.
    - A boundary whose drain changed `app-db` also carries a speculative hydration delta for its subtree, filtered through the same `:payload` policy as the final payload. A delta that is empty or entirely off the allowlist emits no delta script.
    - The final chunk carries the canonical full hydration payload. If a speculative delta and the payload ever disagree, the payload wins.
    - Failures are isolated per boundary: a boundary whose render throws keeps its fallback, with a `:rf.ssr/suspense-boundary-failed` trace, while the rest of the page streams on.
    - A tree with no boundaries still goes through the chunked path, with zero continuations: shell prefix, shell HTML, final payload, shell suffix.
    - A `:redirect` set during the drain short-circuits to a bodiless `Location` response before any chunk is written.
    - The shell renders on the request thread, before the response head is committed. A throw from the root view or the shell walk, or a projected 5xx found at that point, fails closed to a non-200 projected error page (`:rf.error/ssr-render-failed` through the projector), and no writer thread starts.
    - Once the head is committed the status cannot change. A failure while writing the rest of the stream, other than a boundary's render, emits the always-on `:rf.error/ssr-streaming-writer-failed` record, with a `:phase` tag naming the chunk in flight, and closes the stream, truncating the response; it is never projected.
    - Any `Content-Length` header set during the drain is removed (case-insensitively), so the host server controls the framing.
    - Each in-flight streamed request gets one raw daemon `java.lang.Thread`. There is no framework pool and no framework cap on in-flight streams. Every writer's `catch` / `finally` closes the pipe and tears the frame down on every exit path. A body nobody reads, such as one dropped by Ring's `wrap-head`, ends the same way once its blocked write has seen no byte consumed for 60 seconds. The ceiling is the host server's accept-queue or worker-thread limit (Jetty, http-kit, Aleph); size that limit for high streaming concurrency or slow-client hardening.
- **Options**: the same as `ssr-handler`: `:initial-events` (vector or `(fn [request] → …)`), `:root-view`, `:payload`, `:payload-include-sensitive`, `:client-frame-id`, `:url-strategy`, `:fx-overrides`, `:ssr-blocking-timeout-ms`, `:ssr`, `:on-error`, `:error-view`, `:emit-hash?`, `:version`, `:schema-digest` and `:content-type`, plus the four shell-hook options `:head`, `:body-end`, `:script-src` and `:app-element-id`, which [`default-streaming-prefix`](#default-streaming-prefix) and [`default-streaming-suffix`](#default-streaming-suffix) apply. Two are rejected at construction with `:rf.error/ssr-streaming-unsupported-opt`:
    - `:html-shell` — the streaming path flushes a prefix and a suffix around the continuation chunks, so a one-piece shell fn can never run. Customise the envelope with the shell-hook options, or use `ssr-handler` when you need a one-piece shell.
    - `:renderer` — the shell and every continuation render from the JVM-resolved `:root-view`, so a body rendered whole elsewhere has nothing to split. `:root-view` is therefore required here.
- **Example**:
  ```clojure
  (require '[ring.adapter.jetty :as jetty]
           '[re-frame.core      :as rf]
           '[re-frame.ssr.ring  :as ssr.ring])

  ;; Same opts as ssr-handler, minus :html-shell and :renderer (rejected at
  ;; construction). Customise the streaming envelope via the trusted shell-hook opts.
  (def handler
    (ssr.ring/stream-handler
      {:initial-events [[:rf/server-init]]
       :root-view      (fn [] ((rf/view :app/root)))
       :payload        [:articles :session-user]
       :script-src     "/js/main.js"}))

  (jetty/run-jetty handler {:port 3000 :join? false})
  ```

### `ssr-middleware`

- **Kind**: function
- **Signature**:
  ```clojure
  (ssr-middleware opts) → (fn [handler] wrapped-handler)
  ```
- **Description**: Returns Ring middleware that sends requests its `:match?` predicate accepts to an `ssr-handler`, and all other requests to the wrapped handler.
    - `opts` are `ssr-handler`'s options, including the required `:payload`, plus `:match?`, a `(request) → boolean` predicate. Truthy renders with SSR; falsy calls the wrapped handler.
    - `:match?` defaults to matching every GET request.
- **Example**:
  ```clojure
  ;; ssr-middleware is curried: (ssr-middleware opts) returns a Ring
  ;; middleware (handler) → wrapped-handler. Apply it to the fallback
  ;; handler, then compose normally.
  (def app
    (-> default-handler
        ((ssr.ring/ssr-middleware
           {:initial-events [[:rf/server-init]]
            :root-view      (fn [] ((rf/view :app/root)))
            :payload        [:articles :session-user]
            :match?         (fn [req] (= :get (:request-method req)))}))
        wrap-static-assets))
  ```

## Rendering on a Node sidecar

`re-frame.ssr.ring.node` ships the one other `:renderer`. It sends the settled request frame's state to the Node render sidecar and takes back the body markup; the head, payload, shell, status, headers and error projection stay on the JVM. [Render on Node](../ssr/concepts.md#render-on-node) walks through the bundle, the sidecar and deployment.

```clojure
(:require [re-frame.ssr.ring.node :as node])
```

### `renderer`

- **Kind**: function
- **Signature**:
  ```clojure
  (renderer opts) → (fn [{:keys [frame-id request opts]}] {:body-html … :render-hash nil})
  ```
- **Description**: Returns a value for `ssr-handler`'s `:renderer`. It validates `opts` and builds one HTTP client at construction; per request it projects the frame's state under `:render-state`, POSTs it to `<endpoint>/render` and returns the sidecar's body verbatim.
    - `:render-hash` is always `nil`, so the page carries no `data-rf-render-hash` and no payload `:rf/render-hash`.
    - The projection applies the handler's `:payload-include-sensitive`, so the markup and the payload agree on a permitted value.
    - The render module reads the state back with `re-frame.ssr.render-state/deserialize`.
    - A request waits at most `:timeout-ms` + `:admission-ms` + 600 ms, body included.
- **Options**:
    - `:entry` (required) — the bundle entry to render; a non-empty string.
    - `:build-id` (required) — a non-empty string equal to the server bundle's build id.
    - `:render-state` (required) — what the render may see: `{:app-db [<keys>] :runtime-db [<keys>]}`, fail-closed allowlists of top-level keys with either slot optional, or `(fn [frame-id] → {:rf/app-db {…} :rf/runtime-db {…}})`. Every value must read back equal through EDN: no fn, record, `#inst`, `#uuid`, ratio, big or float number, or integer past 2^53.
    - `:endpoint` — the sidecar's absolute `http` or `https` URL; default `"http://127.0.0.1:8148"`. A non-loopback URL is accepted; securing it is the operator's job.
    - `:args` — root arguments sent as EDN, under the same value rule.
    - `:timeout-ms` — the render deadline sent to the sidecar; a positive integer, default `1000`.
    - `:admission-ms` — how long the sidecar may queue the request; a non-negative integer, default `250`. Match the sidecar's `--admission-ms`.
- **Errors**:
    - At construction: `:rf.error/ssr-node-renderer-opt-invalid` (ex-data `:opt`, `:got`) for a malformed option; `:rf.error/ssr-missing-payload-policy` or `:rf.error/ssr-malformed-payload-allowlist`, with `:opt :render-state`, for a missing or malformed `:render-state`.
    - Per request, thrown at the render call. The handler projects each as `:rf.error/ssr-render-failed`, with the id below in its `:exception`, so the request answers the 5xx error page and `:on-error` is not called:
        - `:rf.error/ssr-node-unreachable` — no HTTP answer: connection refused, connect timeout, an I/O fault.
        - `:rf.error/ssr-node-deadline` — the render missed its deadline; `:observed-by` is `:sidecar` (its `504`) or `:jvm`.
        - `:rf.error/ssr-node-refused` — any other non-`200`; carries `:status` and the sidecar's `:refusal` code.
        - `:rf.error/ssr-node-build-skew` — a `200` whose `x-rf-ssr-build` header is missing or is not `:build-id`.
        - `:rf.error/ssr-render-state-invalid` — a projected value the EDN wire cannot carry, or a `:render-state` fn that returned a malformed envelope.
- **Example**:
  ```clojure
  (ssr.ring/ssr-handler
    {:initial-events [[:app/init]]
     :payload        [:todos]
     :renderer       (node/renderer
                       {:entry        "my-app/root"
                        :build-id     (System/getenv "MY_APP_BUILD_ID")
                        :render-state {:app-db     [:todos :session]
                                       :runtime-db [:rf.runtime/routing]}})})
  ```

## Defaults and overrides

### `handler-defaults`

- **Kind**: var (map)
- **Signature**:
  ```clojure
  handler-defaults
  ;; => {:emit-hash? true
  ;;     :html-shell default-html-shell}
  ```
- **Description**: The default `ssr-handler` options, merged under the caller's options at construction; caller values win. A data var, exposed so callers can read or extend the baseline. Two options are not in it:
    - `:on-error` — resolved separately (the default is [`default-on-error`](#default-on-error)).
    - `:content-type` — the option replaces the response Content-Type whenever it is supplied, so a default here would replace an app's own `:rf.server/set-header "content-type"` on every request. With the option absent, the runtime's default `text/html; charset=utf-8`, or the app's explicit Content-Type, applies. The response defaults to `text/html; charset=utf-8` either way; the value just does not come from this var.
- **Example**:
  ```clojure
  ;; Read the baseline the handler constructor merges under your opts.
  ssr.ring/handler-defaults
  ;; => {:emit-hash? true, :html-shell #object[...]}
  ```

### `default-html-shell`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-html-shell body-html payload-edn opts) → HTML string
  ```
- **Description**: Wraps the rendered body in a minimal, runnable HTML document. Supply your own with `ssr-handler`'s `:html-shell` option to change the `<head>`, scripts or styles.
    - Arguments: `body-html` is the string `re-frame.ssr/render-to-string` returned; `payload-edn` is the hydration payload, already serialised with `pr-str`; `opts` are the adapter options, of which `:head`, `:html-attrs`, `:body-attrs`, `:body-end`, `:script-src`, `:app-element-id` and `:lang` shape the envelope.
    - The payload `<script>` gets the fixed id `__rf_payload`, which the client bootstrap reads with `document.getElementById("__rf_payload")`. Its EDN body is escaped so a payload containing `</script>` cannot close the element. A custom shell must emit the payload `<script>` under this id with equivalent escaping, or supply its own bootstrap that reads a different id.
    - The shell emits no `<title>`; the head fragment passed as `:head` supplies it.
    - `:script-src` and `:app-element-id` are attribute-escaped; `:head` and `:body-end` are injected raw.
- **Example**:
  ```clojure
  ;; The default envelope, invoked directly (the handler does this for you).
  (ssr.ring/default-html-shell
    "<div>rendered body</div>"
    "{:rf/version 1 :rf/app-db {}}"        ;; pr-str'd hydration payload
    {:head       "<title>MyApp</title>"
     :script-src "/js/main.js"})
  ;; => "<!DOCTYPE html><html lang=\"en\"><head>…</head><body>…</body></html>"
  ```

### `default-on-error`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-on-error request throwable) → ring-response
  ```
- **Description**: The fixed `500` response `ssr-handler` and `stream-handler` return when `:on-error` is omitted.
    - It covers failures the SSR error projector cannot see: per-request setup throws, header or cookie materialisation throws, and the streaming handler's throws before its writer thread starts. The projector handles drain-time and render-time errors.
    - It ignores the throwable and returns a generic plain-text body, because `.getMessage` can reveal internal topology: JDBC URLs, deploy paths, partial SQL, server class names. For a branded body, supply an `:on-error` that likewise returns a fixed response and ignores the throwable.
    - It is a var holding a 2-arity fn, not a `defn`, so it carries no `:arglists`.
- **Example**:
  ```clojure
  ;; The host-locked transport-failure net (used when :on-error is omitted).
  (ssr.ring/default-on-error request some-throwable)
  ;; => {:status  500
  ;;     :headers {"Content-Type" "text/plain; charset=utf-8"}
  ;;     :body    "Internal error"}
  ```

## Framework integration

Not for application code — used by adapters, tools and the test harness.

### `default-streaming-prefix`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-streaming-prefix head-html opts) → HTML string
  ```
- **Description**: Returns the first streamed chunk: the document open, `<head>`, body open and the app element's open tag, matching `default-html-shell`. It uses the same `:html-attrs` / `:lang` fallback as the non-streaming shell, so the two envelopes cannot diverge.
    - `head-html` — the resolved head fragment.
    - `opts` — honours `:html-attrs`, `:body-attrs`, `:lang` (default `"en"`), `:app-element-id` (default `"app"`), `:head-hash` (stamped as `data-rf-head-hash` on `<head>`, omitted when `nil`) and `:render-hash`.
    - When `:render-hash` is supplied (the handler passes it when `:emit-hash?` is true), `data-rf-render-hash` is stamped on the `#app` element, the streamed document's first DOM root, as the non-streaming handler stamps its root element.
- **Example**:
  ```clojure
  ;; The first streamed chunk — open + <head> + <body> + app-div-open.
  (ssr.ring/default-streaming-prefix
    "<title>MyApp</title>"                  ;; resolved head HTML
    {:lang "en" :app-element-id "app"})
  ;; => "<!DOCTYPE html><html lang=\"en\"><head>…</head><body><div id=\"app\">"
  ```

### `default-streaming-suffix`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-streaming-suffix opts) → HTML string
  ```
- **Description**: Returns the chunk flushed after the final payload: the bootstrap `<script>` (`:script-src`, attribute-escaped; `nil` or absent means `"/main.js"`, and `false` omits the tag), the raw `:body-end` HTML, and `</body></html>`.
    - It does not close the app element. The shell chunk closes `#app`, so the resolved templates, hydration-delta scripts and final `__rf_payload` script all stream outside it, as in the non-streaming `default-html-shell`.
- **Example**:
  ```clojure
  ;; The trailing chunk — bootstrap <script>, raw :body-end, document close.
  (ssr.ring/default-streaming-suffix {:script-src "/js/main.js"})
  ;; => "<script src=\"/js/main.js\"></script></body></html>"
  ```

### `cookie->set-cookie-header`

- **Kind**: function
- **Signature**:
  ```clojure
  (cookie->set-cookie-header cookie-map) → Set-Cookie header string
  ```
- **Description**: Serialises one structured cookie map, the shape `:rf.server/set-cookie` takes, to a `Set-Cookie` header value, per RFC 6265 §4.1. Exposed so tests, other host adapters (Pedestal, http-kit) and one-off callers can serialise a cookie.
    - `:name` is required. `:value` is URL-encoded, and serialises as the empty string when absent. Every other key (`:max-age`, `:domain`, `:path`, `:expires` as an epoch-millis long, `:secure`, `:http-only`, `:same-site` as `:strict`, `:lax` or `:none`, or a string written verbatim) becomes an attribute after a semicolon.
- **Errors**:
    - `:rf.error/cookie-missing-name` — no `:name`.
    - `:rf.error/cookie-invalid-name` — a `:name` that is not a string, keyword or symbol, or does not match the RFC 6265 §4.1.1 token grammar.
    - `:rf.error/cookie-invalid-attribute` — `:domain`, `:path`, `:max-age` or `:same-site` containing CR, LF, NUL or a raw `;`. These are checked before concatenation, which prevents header splitting and attribute injection.
    - `:rf.error/cookie-invalid-expires` — a non-integer `:expires`.
- **Example**:
  ```clojure
  (ssr.ring/cookie->set-cookie-header
    {:name      "session"
     :value     "abc123"
     :max-age   3600
     :http-only true
     :same-site :lax
     :path      "/"})
  ;; => "session=abc123; Max-Age=3600; Path=/; HttpOnly; SameSite=Lax"
  ```

## See also

- [`re-frame.ssr`](re-frame.ssr.md) — rendering, the head model, hydration, streaming, error projection and the `:rf.server/*` fx this handler drives.
- [`re-frame.ssr.head`](re-frame.ssr.head.md) — the head model the handler resolves for each page.
- [`re-frame.core`](re-frame.core.md) — `init!`, `make-frame`, `reg-event`, and the `rf/reg-head` / `rf/reg-error-projector` facade entries.
- [`re-frame.routing`](re-frame.routing.md) — routes select a head with `:head` metadata.
- [Server-side rendering](../ssr/index.md) — the guide.
