# re-frame.ssr.ring

The Ring/Pedestal host adapter for re-frame2 server-side rendering. It materialises the structured response produced by the SSR runtime in [`re-frame.ssr`](re-frame.ssr.md) into the wire format a Ring-compatible server expects. It never writes to a socket directly. The per-request lifecycle, the rendering and head primitives, the SSR events / subs / cofx, and the `:rf.server/*` fx all live in [`re-frame.ssr`](re-frame.ssr.md) (artefact `day8/re-frame2-ssr`), not here.

Ships in the `day8/re-frame2-ssr-ring` artefact. See [Server-side rendering — the tutorial](../ssr/concepts.md) for the conceptual walkthrough.

```clojure
(:require [re-frame.ssr.ring :as ssr.ring])
```

## Handler constructors

### `ssr-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (ssr-handler opts) → (fn [ring-request] ring-response)
  ```
- **Description**: Returns a synchronous Ring handler that renders one re-frame2 SSR request per call.

  The returned handler owns the whole per-request lifecycle:

  1. Populate the per-frame request slot.
  2. Register the per-request frame, draining `:initial-events` synchronously so the `:rf.server/request` cofx can resolve.
  3. Read the resolved response accumulator, and branch on `:redirect`.
  4. Otherwise, render `:root-view`, build the hydration payload, wrap it in the HTML shell, and materialise structured cookies to `Set-Cookie` headers.
  5. Destroy the frame in a `finally`.

  Required opts:

  - `:initial-events` — an ordered vector of events, dispatched synchronously and in order into the per-request frame at creation. Alternatively, a `(fn [request] → initial-events-vector)` that derives the vector from the Ring request. The fn form is the replay-safe seam for folding a request-derived fact into a boot event's payload, e.g. `(fn [req] [[:auth/server-init {:user (extract-user req)}]])`. For non-durable request reads inside a handler, declare `:rf.cofx/requires [:rf.server/request]` on the registration instead.
    - Omission throws `:rf.error/ssr-ring-missing-initial-events` at construction.
    - A value that is neither a vector nor a fn, or a fn returning a non-vector, throws `:rf.error/invalid-initial-events` per request.
  - `:root-view` — a hiccup vector (e.g. `[:app/root]`) or a 0-arity fn returning hiccup, rendered against the per-request frame after the drain settles.
    - Omission throws `:rf.error/ssr-ring-missing-root-view` at construction.
    - Any other shape throws `:rf.error/invalid-root-view` at render time.
  - `:payload` — **REQUIRED, fail-closed.** The hydration-payload policy, one opt with two shapes:
    - A non-empty vector of top-level app-db keys (keywords) is an allowlist (recommended). Only the listed keys ship in `:rf/app-db`. Every other key is dropped, including keys added later.
    - The keyword `:rf.ssr.payload/whole-app-db` ships the whole `app-db`. Use it only when the app-db is structurally safe to expose.
    - Absence or an empty allowlist throws `:rf.error/ssr-missing-payload-policy`. An unrecognised keyword throws `:rf.error/ssr-unknown-payload-policy`. An allowlist with non-keyword entries throws `:rf.error/ssr-malformed-payload-allowlist`. All three throw at construction.

  Optional opts:

  - `:fx-overrides` — per-frame `:fx-overrides` map, passed through verbatim (e.g. to stub `:rf.http/managed` in tests).
  - `:ssr` — per-frame `:ssr` config map, e.g. `{:dev-error-detail? true :public-error-id :myapp/projector}`.
  - `:emit-hash?` — embed `data-rf-render-hash` on the root element; default `true`.
  - `:version` — hydration payload's `:rf/version`; default `1`.
  - `:schema-digest` — hydration payload's `:rf/schema-digest`.
  - `:html-shell` — `(body-html payload-edn opts) → string`; defaults to [`default-html-shell`](#default-html-shell).
  - `:content-type` — default `"text/html; charset=utf-8"`.

  Error handling — `:error-view` vs `:on-error`. These two opts handle two different failures, and a robust deployment wires both. A buggy `:error-view` falls back to the default template; a buggy `:on-error` falls back to `default-on-error`. Neither bug bypasses the error boundary.

  | Aspect | `:error-view` | `:on-error` |
  |---|---|---|
  | Which failure? | An exception inside the re-frame drain or the render walk. The error projector catches these. | A transport / Ring-layer failure the projector cannot see: a per-request frame setup throw, a render-time CLJ exception, a header/cookie materialise throw, or a thrown initial-event. |
  | What does it produce? | The projected error-page body (hiccup): a registered-view keyword (resolved as `[error-view public-error]`) or a `(public-error) → hiccup` fn. It renders through the standard SSR emitter. | A raw Ring response map `{:status … :headers … :body …}` returned verbatim to the server. |
  | What is its input? | The public-error map, sanitised by the projector and safe to render. | The raw `(request throwable)`, carrying the unsanitised throwable. The locked default never reads it. |
  | Default when omitted? | Minimal default error template. | Minimal locked 500 ([`default-on-error`](#default-on-error), topology-leak-safe). |

  Trusted shell-hook string opts. Four optional strings cross the trust boundary into the rendered HTML envelope, split by injection position:

  - `:head` and `:body-end` — raw content hooks, injected verbatim with no escaping.
  - `:script-src` (default `"/main.js"`) and `:app-element-id` (default `"app"`) — escaped attribute hooks, escape-attr'd into a quoted attribute value.

  Non-string non-nil values throw `:rf.error/ssr-trusted-shell-opt-invalid` at construction. Wiring the raw content hooks from untrusted input (a CMS field, a tenant-admin form, a query-string parameter) is an arbitrary-script-injection XSS vector. When content originates upstream of the trust boundary, use the structured alternatives: [`reg-head`](re-frame.ssr.md) for head fragments, and registered views plus the [`:rf.server/*` fx](re-frame.ssr.md) for body content.
- **Example**:
  ```clojure
  (require '[ring.adapter.jetty :as jetty]
           '[re-frame.core      :as rf]
           '[re-frame.ssr.ring  :as ssr.ring])

  (rf/init! (requiring-resolve 'my-app/ssr-adapter))

  (def handler
    (ssr.ring/ssr-handler
      {:initial-events [[:rf/server-init]]
       :root-view      [:app/root]
       ;; :payload is REQUIRED, fail-closed. A vector is an allowlist of
       ;; top-level app-db keys to ship; :rf.ssr.payload/whole-app-db opts
       ;; into the whole db. Omit it and construction throws
       ;; :rf.error/ssr-missing-payload-policy.
       :payload        [:articles :session-user]
       :html-shell     my-app/shell}))

  (jetty/run-jetty handler {:port 3000 :join? false})
  ```

### `stream-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (stream-handler opts) → (fn [ring-request] ring-response)
  ```
- **Description**: The streaming counterpart of `ssr-handler`. Returns a synchronous Ring handler that streams SSR responses via `Transfer-Encoding: chunked`.

  Streaming behaviour:

  - Flushes a shell on the first byte, then streams `:rf/suspense-boundary` subtrees as their data resolves.
  - A boundary whose drain changed app-db also carries a speculative per-subtree hydration delta, projected through the same `:payload` policy as the final payload. An off-allowlist or empty delta emits no delta script.
  - The final chunk carries the canonical full hydration payload. If a speculative delta and the canonical payload ever disagree, the payload wins.
  - Failure isolation is per-boundary: a boundary whose render throws keeps its fallback (with a `:rf.ssr/suspense-boundary-failed` trace) while the rest of the page streams on.
  - Non-streaming responses (no `:rf/suspense-boundary` in the tree) still ride the chunked path, with zero continuations. The wire shape collapses to shell-prefix + shell-html + final-payload + shell-suffix.
  - A `:redirect` set during the drain short-circuits to a bodiless Location response before any chunk is written.
  - The shell renders on the request thread, before the chunked head commits. A root-view or shell-walk throw fails closed to a non-200 projected error page (`:rf.error/ssr-render-failed` via the projector), with no writer thread spawned.
  - Any `Content-Length` header accumulated during the drain is stripped (case-insensitively) so the host server owns chunked transfer framing.

  Opts mirror `ssr-handler`: `:initial-events` (both vector and `(fn [request] → …)` forms), `:root-view`, `:payload`, `:fx-overrides`, `:ssr`, `:on-error`, `:error-view`, `:emit-hash?`, `:version`, `:schema-digest`, `:content-type`, plus the four trusted shell-hook opts (`:head` / `:body-end` / `:script-src` / `:app-element-id`, honoured by [`default-streaming-prefix`](#default-streaming-prefix) / [`default-streaming-suffix`](#default-streaming-suffix)).

  One exception: `:html-shell` is not supported and is rejected at construction (`:rf.error/ssr-streaming-unsupported-opt`). The streaming path flushes a split prefix/suffix straddling the continuation chunks, so a one-piece shell callback can never run. Customise the streaming envelope through the trusted shell-hook opts, or use `ssr-handler` when a bespoke one-piece shell is required.

  Concurrency model: one raw daemon `java.lang.Thread` per in-flight streamed request. There is no framework pool and no framework in-flight cap. Every writer's `catch`/`finally` closes the pipe and tears the frame down on every exit path (no-leak). The in-flight ceiling is the host server's accept-queue / worker-thread limit (Jetty / http-kit / Aleph). Operators size that limit as the one knob for high streaming concurrency or slow-client hardening.
- **Example**:
  ```clojure
  (require '[ring.adapter.jetty :as jetty]
           '[re-frame.ssr.ring  :as ssr.ring])

  ;; Same opts as ssr-handler, minus :html-shell (rejected at construction).
  ;; Customise the streaming envelope via the trusted shell-hook opts.
  (def handler
    (ssr.ring/stream-handler
      {:initial-events [[:rf/server-init]]
       :root-view      [:app/root]
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
- **Description**: Returns Ring middleware that delegates to `ssr-handler` for requests its `:match?` predicate accepts, and to the wrapped handler otherwise. Curried: `(ssr-middleware opts)` returns a `(handler) → wrapped-handler` middleware.

  Opts are `ssr-handler`'s opts (including the required, fail-closed `:payload`) plus `:match?`, a `(request) → boolean` predicate. When it returns truthy, SSR renders. When it returns falsy, the call falls through to the wrapped handler. `:match?` defaults to matching every GET request.
- **Example**:
  ```clojure
  ;; ssr-middleware is CURRIED: (ssr-middleware opts) returns a Ring
  ;; middleware (handler) → wrapped-handler. Apply it to the fallback
  ;; handler, then compose normally.
  (def app
    (-> default-handler
        ((ssr.ring/ssr-middleware
           {:initial-events [[:rf/server-init]]
            :root-view      [:app/root]
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
  ;; => {:emit-hash?   true
  ;;     :html-shell   default-html-shell
  ;;     :content-type "text/html; charset=utf-8"}
  ```
- **Description**: The default `ssr-handler` opts, merged under caller-supplied opts at construction (caller values win). This is a data var, not a fn. It is exposed so callers can read or extend the baseline. `:on-error` is deliberately absent: it is resolved separately, so the defaults stay orthogonal to the on-error precedence.
- **Example**:
  ```clojure
  ;; Read the baseline the handler constructor merges under your opts.
  ssr.ring/handler-defaults
  ;; => {:emit-hash? true, :html-shell #object[...], :content-type "text/html; charset=utf-8"}
  ```

### `default-html-shell`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-html-shell body-html payload-edn opts) → HTML string
  ```
- **Description**: The default HTML envelope: wraps the rendered body in a minimal, runnable document. Override via the `:html-shell` opt on `ssr-handler` for a custom `<head>` / scripts / styles.

  Arguments:

  - `body-html` — the string returned by `re-frame.ssr/render-to-string`.
  - `payload-edn` — the hydration payload pre-serialised with `pr-str`.
  - `opts` — the adapter opts; the keys `:head` / `:html-attrs` / `:body-attrs` / `:body-end` / `:script-src` / `:app-element-id` / `:lang` influence the envelope.

  Behaviour:

  - The hydration-payload `<script>` is stamped with the framework-pinned id `__rf_payload`; the client bootstrap reads it via `document.getElementById("__rf_payload")`. Its EDN body is escaped EDN-aware, so a payload containing `</script>` cannot close the envelope. A custom shell must emit the payload `<script>` under this id, with equivalent escaping, or substitute its own bootstrap that reads a custom id.
  - The shell does not emit `<title>`. The head fragment threaded in as `:head` is the canonical source.
  - The two attribute-value hooks (`:script-src`, `:app-element-id`) are escape-attr'd. The two content hooks (`:head`, `:body-end`) are injected raw.
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
- **Description**: The minimal 500 response used when a handler caller omits `:on-error`. Shared by `ssr-handler` and `stream-handler`, so the topology-leak contract lives in one place. It covers exceptions the SSR error projector can't see: Ring-layer throws, render-time CLJ exceptions, and writer-thread-pre-spawn throws. Trace-emitted drain errors are handled by the projector instead.

  The body must not leak the throwable's message: `.getMessage` carries internal topology, such as JDBC URLs, deploy-root file paths, partial SQL, and server-internal class names. So the fn ignores the throwable and emits a fixed generic plaintext body. Apps wanting a branded transport-failure body supply an explicit leak-safe `:on-error` fn that returns a fixed response and ignores the throwable. Exposed as a value: a 2-arity fn, not a `defn`, so it carries no `:arglists`.
- **Example**:
  ```clojure
  ;; The host-locked transport-failure net (used when :on-error is omitted).
  (ssr.ring/default-on-error request some-throwable)
  ;; => {:status  500
  ;;     :headers {"Content-Type" "text/plain; charset=utf-8"}
  ;;     :body    "Internal error"}
  ```

## Streaming envelope

### `default-streaming-prefix`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-streaming-prefix head-html opts) → HTML string
  ```
- **Description**: The shell prefix flushed as the first streamed chunk. It mirrors `default-html-shell`'s open + `<head>` + body-open + app-div-open. It also shares the `:html-attrs` / `:lang` fallback with the non-streaming shell, so the two envelopes can't diverge.

  - `head-html` — the resolved head fragment.
  - `opts` — honours `:html-attrs` / `:body-attrs` / `:lang` (default `"en"`) / `:app-element-id` (default `"app"`) / `:render-hash`.

  When `:render-hash` is supplied (the handler passes it iff `:emit-hash?` is true), `data-rf-render-hash` is stamped on the `#app` div, the first DOM root of the streamed document. This mirrors the non-streaming handler's root-element marker.
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
- **Description**: The shell suffix flushed after the final-payload chunk. It emits the bootstrap `<script>` (if `:script-src` is set; default `"/main.js"`, escape-attr'd), the raw `:body-end` HTML, and the document close (`</body></html>`).

  The app root (`</div>`) is not closed here. It is closed at the end of the shell chunk, so the resolved templates, hydration-delta scripts, and the final `__rf_payload` script all stream outside `#app`. The suffix is therefore purely bootstrap script + raw `:body-end` + document close. All of it is already outside `#app`, mirroring the non-streaming `default-html-shell`.
- **Example**:
  ```clojure
  ;; The trailing chunk — bootstrap <script>, raw :body-end, document close.
  (ssr.ring/default-streaming-suffix {:script-src "/js/main.js"})
  ;; => "<script src=\"/js/main.js\"></script></body></html>"
  ```

## Cookie serialisation

### `cookie->set-cookie-header`

- **Kind**: function
- **Signature**:
  ```clojure
  (cookie->set-cookie-header cookie-map) → Set-Cookie header string
  ```
- **Description**: Serialises one structured `re-frame.ssr` cookie map to a `Set-Cookie` header value, per RFC 6265 §4.1. It is re-exposed from the façade so that tests, alternate host adapters (Pedestal, http-kit), and user code needing a one-off serialisation can reach it without an internal namespace.

  Fields:

  - `:name` — required.
  - `:value` — URL-encoded; serialises as the empty string when absent.
  - Everything else (`:max-age`, `:domain`, `:path`, `:expires` (epoch-millis long), `:secure`, `:http-only`, `:same-site`) is an attribute appended after semicolons.

  Validation is fail-loud:

  - `:name` must be a string or a keyword/symbol, and it must match the RFC 6265 §4.1.1 token grammar. Either violation throws `:rf.error/cookie-invalid-name`.
  - A missing `:name` throws `:rf.error/cookie-missing-name`.
  - `:domain` / `:path` / `:max-age` / `:same-site` are checked for CR / LF / NUL before concatenation; a violation throws `:rf.error/cookie-invalid-attribute`. This closes header-splitting injection.
  - A non-integer `:expires` throws `:rf.error/cookie-invalid-expires`.
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

- [`re-frame.ssr`](re-frame.ssr.md) — the SSR runtime: `render-to-string`, the streaming triple, the head model (`reg-head` / `active-head` / `render-head`), error projection (`reg-error-projector` / `project-error`), the SSR events / subs / cofx, and the per-request `:rf.server/*` fx.
- [`re-frame.core`](re-frame.core.md) — `init!`, `make-frame`, `reg-event`, and the render / head primitives re-exported on the facade.
- [`re-frame.routing`](re-frame.routing.md) — routes opt into per-route head models via `:head` metadata.
- [Server-side rendering — the tutorial](../ssr/concepts.md) — the conceptual walkthrough.
