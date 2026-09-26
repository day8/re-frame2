# re-frame.ssr.ring

Serve server-rendered re-frame2 pages from a Ring-compatible server. `ssr-handler` returns a Ring handler that renders one request per call; `stream-handler` returns one that streams the response in chunks. For each request the handler creates a frame, runs your setup events, renders the root view and returns a Ring response map; it never writes to a socket itself.

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
    {:initial-events [[:rf/server-init]]            ;; setup events for each request's frame
     :root-view      [(rf/view :app/root)]           ;; rendered once those events settle
     :payload        [:articles :session-user]}))  ;; app-db keys shipped to the client

(jetty/run-jetty handler {:port 3000 :join? false})
```

Those are the three required options; [The simplest server](../ssr/concepts.md#the-simplest-server) walks through what the handler does with them.

## Handler constructors

### `ssr-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (ssr-handler opts) → (fn [ring-request] ring-response)
  ```
- **Description**: Returns a synchronous Ring handler that renders one re-frame2 SSR request per call. For each request it:
    1. stores the Ring request in the per-frame request slot;
    2. creates the per-request frame, draining `:initial-events` synchronously, so the `:rf.server/request` cofx can resolve;
    3. reads the resolved response accumulator and, if it holds a `:redirect`, returns the redirect;
    4. otherwise renders `:root-view`, builds the hydration payload, wraps both in the HTML shell, and turns structured cookies into `Set-Cookie` headers;
    5. destroys the frame in a `finally`.
- **Options**:
    - `:initial-events` (required) — the events dispatched, synchronously and in order, into the per-request frame when it is created. A vector of events, or `(fn [request] → initial-events-vector)` to derive them from the Ring request. The fn form is the replay-safe way to put a request-derived fact into a boot event's payload, e.g. `(fn [req] [[:auth/server-init {:user (extract-user req)}]])`. For non-durable request reads inside a handler, declare `:rf.cofx/requires [:rf.server/request]` on the registration instead.
        - Omitted: throws `:rf.error/ssr-ring-missing-initial-events` at construction.
        - Neither a vector nor a fn, or a fn returning a non-vector: throws `:rf.error/invalid-initial-events` per request.
    - `:root-view` (required unless `:renderer` is given) — what to render against the per-request frame once the drain settles: a hiccup vector such as `[(rf/view :app/root)]`, or a 0-arity fn returning hiccup. The head must be callable: the Var `rf/reg-view` defines, or `(rf/view :id)`. A keyword head is an HTML element on every host, so `[:app/root]` renders an empty `<root>` element.
        - Both forms emit identical HTML, but only the resolving fn form, `(fn [] ((rf/view :app/root)))`, is hashed. The vector form `[(rf/view :app/root)]` is an unresolved root and gets no `data-rf-render-hash` and no payload `:rf/render-hash`, whatever `:emit-hash?` says. Use the fn form if you want hydration mismatch detection.
        - Omitted without `:renderer`: throws `:rf.error/ssr-ring-missing-root-view` at construction. Any other shape throws `:rf.error/invalid-root-view` at render time.
    - `:payload` (required) — the hydration-payload policy. It fails closed, in one of two shapes:
        - A non-empty vector of top-level `app-db` keys (keywords) is an allowlist, and the recommended form. Only the listed keys ship in `:rf/app-db`; every other key is dropped, including keys added later.
        - `:rf.ssr.payload/whole-app-db` ships all of `app-db`. Use it only when the whole `app-db` is safe to expose.
        - At construction, an absent value or an empty allowlist throws `:rf.error/ssr-missing-payload-policy`, an unrecognised keyword throws `:rf.error/ssr-unknown-payload-policy`, and an allowlist with non-keyword entries throws `:rf.error/ssr-malformed-payload-allowlist`.
    - `:payload-include-sensitive` — a vector of `app-db` paths that the frame classifies `:sensitive` but whose raw value may ship anyway, e.g. `[[:session :csrf]]`. Only paths inside the `:payload` allowlist; without this option every classified value ships as `:rf/redacted`. A malformed value throws `:rf.error/ssr-malformed-payload-allowlist` at construction.
    - `:client-frame-id` — a stable frame id written as the payload's `:rf/frame-id`, for deployments where server and client agree on one ahead of time. Default `nil`, which omits `:rf/frame-id`. Never use a per-request gensym: the client rejects a present and different id with `:rf.error/hydration-frame-id-mismatch`.
    - `:error-view` and `:on-error` — the two failure handlers, described in the table below.
    - `:ssr` — the per-frame `:ssr` config map, e.g. `{:dev-error-detail? true :public-error-id :myapp/projector}`.
    - `:url-strategy` — the per-request frame's `:url-strategy`, passed to `make-frame`, so `route-link` hrefs in the server markup match what the hydrated client produces. A malformed value, an explicit `nil` included, fails the request with `:rf.error/invalid-url-strategy` through `:on-error`.
    - `:fx-overrides` — the per-frame `:fx-overrides` map, passed through as is (e.g. to stub `:rf.http/managed` in tests).
    - `:emit-hash?` — stamp `data-rf-render-hash` on the root element; default `true`.
    - `:version` — the hydration payload's `:rf/version`; default `1`.
    - `:schema-digest` — the hydration payload's `:rf/schema-digest`.
    - `:html-shell` — `(body-html payload-edn opts) → string`; default [`default-html-shell`](#default-html-shell).
    - `:content-type` — replaces the response Content-Type when supplied. It has no default: omit it (the normal case) and the response keeps `text/html; charset=utf-8`, from the runtime's default or from the app's own `:rf.server/set-header "content-type"`. See [`handler-defaults`](#handler-defaults).
    - `:head` and `:body-end` — raw HTML strings injected into the envelope verbatim, without escaping. A page normally gets its `<title>` from the active route's `:head`, declared with `reg-head`, and a page with none ships no `<title>`. A `:head` string replaces that resolved head, default viewport meta included, for a static app without routing.
    - `:script-src` (default `"/main.js"`) and `:app-element-id` (default `"app"`) — the bootstrap script's `src` and the app element's `id`, attribute-escaped. `:script-src false` emits no bootstrap `<script src>`, for an app that boots from `:body-end` (for example with a `type="module"` tag).
    - `:renderer` — a fn that renders the body in place of the local renderer: `(fn [{:keys [frame-id request opts]}] → {:body-html <string> :render-hash <string-or-nil>})`. It is called once per request inside the request frame's scope, after the boot-event drain and the blocking-resource settle, and before head resolution and the payload build use its result. It receives the post-drain frame id, the Ring request and the handler opts, never a hiccup value, and returns only the body markup and an optional render hash.
        - A `nil` `:render-hash` omits both the `data-rf-render-hash` marker and the payload's `:rf/render-hash`.
        - A throw is projected like any render-time throw.
        - Omitted, the handler renders locally: resolve `:root-view`, hash, render.
        - The one other renderer the reference ships is `re-frame.ssr.ring.node/renderer`, which renders on a Node sidecar ([Render on Node](../ssr/concepts.md#render-on-node)).
        - `stream-handler` rejects this option at construction.
    - `:render-state` is not an `ssr-handler` option. It is a required option of `re-frame.ssr.ring.node/renderer`, and a copy at the handler's top level is ignored. It sets which state that renderer can see: a fail-closed per-partition allowlist of top-level keys, or a `(fn [frame-id] → partitions)` projector, in the same `{:rf/app-db {…} :rf/runtime-db {…}}` envelope as the payload but as a separate policy. [Render on Node](../ssr/concepts.md#render-on-node) explains why the two differ.

    `:error-view` and `:on-error` handle two different failures, and a robust deployment wires both. A failure the error projector catches is classified by its projected status. A projected 4xx (a routing miss, bad client input) keeps the app's own not-found or bad-request body and hydration payload and does not call `:error-view`; a projected 5xx ships the error page. A buggy `:error-view`, whether it throws or depends on a subscription that recovers to `nil`, falls back once to the default template. A buggy `:on-error` falls back to `default-on-error`.

    | Aspect | `:error-view` | `:on-error` |
    |---|---|---|
    | Which failure? | A projected 5xx the error projector catches: a drain-time handler / fx / sub exception, a render-time view throw, or an unrenderable root or shell. | A transport / Ring-layer failure the projector cannot see: a throw while setting up the per-request frame, a throw while materialising headers or cookies, or a thrown initial event. |
    | What does it produce? | The error-page body (hiccup). Either a registered-view keyword, resolved as `[(rf/view error-view) public-error]` (an unregistered keyword falls back to the default template), or a `(public-error) → hiccup` fn. It renders through the standard SSR emitter, with no app body or hydration payload beside it. | A raw Ring response map `{:status … :headers … :body …}`, returned to the server as is. |
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
- **Description**: Returns a synchronous Ring handler that streams the response with `Transfer-Encoding: chunked`: the page shell first, then each [`boundary`](re-frame.ssr.md#boundary) region as its data resolves, then the final hydration payload.
    - A boundary whose drain changed `app-db` also carries a speculative hydration delta for its subtree, filtered through the same `:payload` policy as the final payload. A delta that is empty or entirely off the allowlist emits no delta script.
    - The final chunk carries the canonical full hydration payload. If a speculative delta and the payload ever disagree, the payload wins.
    - Failures are isolated per boundary: a boundary whose render throws keeps its fallback, with a `:rf.ssr/suspense-boundary-failed` trace, while the rest of the page streams on.
    - A tree with no boundaries still goes through the chunked path, with zero continuations: shell prefix, shell HTML, final payload, shell suffix.
    - A `:redirect` set during the drain short-circuits to a bodiless `Location` response before any chunk is written.
    - The shell renders on the request thread, before the chunked response head is committed. A throw from the root view or the shell walk fails closed to a non-200 projected error page (`:rf.error/ssr-render-failed` through the projector), and no writer thread starts.
    - Any `Content-Length` header set during the drain is removed (case-insensitively), so the host server controls chunked framing.
    - Each in-flight streamed request gets one raw daemon `java.lang.Thread`. There is no framework pool and no framework cap on in-flight streams. Every writer's `catch` / `finally` closes the pipe and tears the frame down on every exit path. The ceiling is the host server's accept-queue or worker-thread limit (Jetty, http-kit, Aleph); size that limit for high streaming concurrency or slow-client hardening.
- **Options**: the same as `ssr-handler`: `:initial-events` (vector or `(fn [request] → …)`), `:root-view`, `:payload`, `:payload-include-sensitive`, `:client-frame-id`, `:url-strategy`, `:fx-overrides`, `:ssr`, `:on-error`, `:error-view`, `:emit-hash?`, `:version`, `:schema-digest` and `:content-type`, plus the four shell-hook options `:head`, `:body-end`, `:script-src` and `:app-element-id`, which [`default-streaming-prefix`](#default-streaming-prefix) and [`default-streaming-suffix`](#default-streaming-suffix) apply. Two are rejected at construction with `:rf.error/ssr-streaming-unsupported-opt`:
    - `:html-shell` — the streaming path flushes a prefix and a suffix around the continuation chunks, so a one-piece shell fn can never run. Customise the envelope with the shell-hook options, or use `ssr-handler` when you need a one-piece shell.
    - `:renderer` — the shell and every continuation render from the JVM-resolved `:root-view`, so a body rendered whole elsewhere has nothing to split. `:root-view` is therefore required here.
- **Example**:
  ```clojure
  (require '[ring.adapter.jetty :as jetty]
           '[re-frame.core      :as rf]
           '[re-frame.ssr.ring  :as ssr.ring])

  ;; Same opts as ssr-handler, minus :html-shell (rejected at construction).
  ;; Customise the streaming envelope via the trusted shell-hook opts.
  (def handler
    (ssr.ring/stream-handler
      {:initial-events [[:rf/server-init]]
       :root-view      [(rf/view :app/root)]
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
  ;; ssr-middleware is CURRIED: (ssr-middleware opts) returns a Ring
  ;; middleware (handler) → wrapped-handler. Apply it to the fallback
  ;; handler, then compose normally.
  (def app
    (-> default-handler
        ((ssr.ring/ssr-middleware
           {:initial-events [[:rf/server-init]]
            :root-view      [(rf/view :app/root)]
            :payload        [:articles :session-user]
            :match?         (fn [req] (= :get (:request-method req)))}))
        wrap-static-assets))
  ```

## Defaults and overrides

### `handler-defaults`

- **Kind**: var
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
    - `opts` — honours `:html-attrs`, `:body-attrs`, `:lang` (default `"en"`), `:app-element-id` (default `"app"`) and `:render-hash`.
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
    - `:name` is required. `:value` is URL-encoded, and serialises as the empty string when absent. Every other key (`:max-age`, `:domain`, `:path`, `:expires` as an epoch-millis long, `:secure`, `:http-only`, `:same-site`) becomes an attribute after a semicolon.
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
