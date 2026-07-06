# re-frame.ssr.ring

The Ring/Pedestal host adapter for re-frame2 server-side rendering. The SSR runtime in [`re-frame.ssr`](re-frame.ssr.md) owns the per-request lifecycle (create a per-request frame, drain its `:initial-events`, render, build the hydration payload, read the structured response accumulator, destroy the frame) but never writes to a network socket directly; this namespace materialises that structured response into the wire format a Ring-compatible server expects. Ring is the canonical Clojure HTTP-server abstraction — Jetty, http-kit, Pedestal, Reitit-ring, and Aleph all accept Ring-shaped handlers — so this single adapter covers the bulk of the Clojure HTTP ecosystem. Add the `day8/re-frame2-ssr-ring` artefact to your deps and require this namespace to get the host-adapter surface; the rendering / head primitives, the SSR events / subs / cofx, and the per-request `:rf.server/*` fx live in [`re-frame.ssr`](re-frame.ssr.md) (artefact `day8/re-frame2-ssr`), not here.

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
- **Description**: Return a Ring-shaped (synchronous) handler that renders one re-frame2 SSR request per call. The returned handler owns the whole per-request lifecycle: populate the per-frame request slot, register the per-request frame (which drains `:initial-events` synchronously so the `:rf.server/request` cofx can resolve), read the resolved response accumulator, branch on `:redirect`, otherwise render the `:root-view` + build the hydration payload + wrap it in the HTML shell + materialise structured cookies to `Set-Cookie` headers, and destroy the frame in a `finally`.

  Required opts:

  - `:initial-events` — an ordered **vector** of events dispatched synchronously, in order, into the per-request frame at creation; **or** a `(fn [request] → initial-events-vector)` that derives the setup vector from the Ring request. The fn form is the replay-safe seam for folding a request-derived fact into a boot event's **payload** (e.g. `(fn [req] [[:auth/server-init {:user (extract-user req)}]])`). For non-durable request reads *inside* a handler, declare `:rf.cofx/requires [:rf.server/request]` on the registration instead. Omission throws `:rf.error/ssr-ring-missing-initial-events` at construction; a value that is neither a vector nor a fn — or a fn returning a non-vector — throws `:rf.error/invalid-initial-events` per request.
  - `:root-view` — a hiccup vector (e.g. `[:app/root]`) **or** a 0-arity fn returning hiccup, rendered against the per-request frame after the drain settles. Omission throws `:rf.error/ssr-ring-missing-root-view` at construction; any other shape throws `:rf.error/invalid-root-view` at render time.
  - `:payload` — **REQUIRED, fail-closed.** The hydration-payload policy: one opt, two shapes. A non-empty **vector** of top-level app-db keys (keywords) is an allowlist (recommended — only the listed keys ship in `:rf/app-db`, every other key is dropped, including keys added later as the app evolves); the **keyword** `:rf.ssr.payload/whole-app-db` opts into shipping the whole `app-db` (use only when the app-db is structurally safe to expose end-to-end). All three defects throw at handler-construction time: absence (or an empty allowlist) throws `:rf.error/ssr-missing-payload-policy`; an unrecognised keyword throws `:rf.error/ssr-unknown-payload-policy`; an allowlist containing non-keyword entries throws `:rf.error/ssr-malformed-payload-allowlist`.

  Optional opts: `:fx-overrides` (per-frame `:fx-overrides` map, passed through verbatim — useful for stubbing `:rf.http/managed` in tests), `:ssr` (per-frame `:ssr` config map, e.g. `{:dev-error-detail? true :public-error-id :myapp/projector}`), `:emit-hash?` (embed `data-rf-render-hash` on the root element; default `true`), `:version` (hydration payload's `:rf/version`; default `1`), `:schema-digest` (hydration payload's `:rf/schema-digest`), `:html-shell` (`(body-html payload-edn opts) → string`; defaults to [`default-html-shell`](#default-html-shell)), and `:content-type` (default `"text/html; charset=utf-8"`).

  **Error handling — `:on-error` vs `:error-view`.** Two opts handle two different failures; a robust deployment usually wires **both**:

  | Aspect | `:error-view` | `:on-error` |
  |---|---|---|
  | Which failure? | An exception inside the re-frame drain or the render walk — something the error projector catches. | A transport / Ring-layer failure the projector cannot see — per-request frame setup throw, render-time CLJ exception, header/cookie materialise throw, a thrown initial-event. |
  | What does it produce? | The projected error-page **body** (hiccup) — a registered-view keyword (resolved as `[error-view public-error]`) or a `(public-error) → hiccup` fn, rendered through the standard SSR emitter. | A raw Ring response map `{:status … :headers … :body …}` returned verbatim to the server. |
  | What is its input? | The **public-error map** (sanitised by the projector — safe to render). | The raw `(request throwable)` — the unsanitised throwable. The locked default never reads it. |
  | Default when omitted? | Minimal default error template. | Minimal locked 500 ([`default-on-error`](#default-on-error), topology-leak-safe). |

  A buggy `:error-view` falls back to the default template and a buggy `:on-error` falls back to `default-on-error` — neither bug bypasses the error boundary.

  **Trusted shell-hook string opts.** Four optional strings cross the trust boundary into the rendered HTML envelope, split by injection position. `:head` and `:body-end` are **raw content hooks** (injected verbatim, no escaping); `:script-src` (default `"/main.js"`) and `:app-element-id` (default `"app"`) are **escaped attribute hooks** (escape-attr'd into a quoted attribute value). The framework names these as trusted-string surfaces; the trust call itself is the caller's. Non-string non-nil values throw `:rf.error/ssr-trusted-shell-opt-invalid` at construction. Wiring the raw content hooks from untrusted input (CMS field, tenant-admin form, query-string parameter) is an arbitrary-script-injection XSS vector — use the structured alternatives ([`reg-head`](re-frame.ssr.md) for head fragments, registered views + the [`:rf.server/*` fx](re-frame.ssr.md) for body content) when content originates upstream of the trust boundary.
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
- **Description**: The streaming counterpart of `ssr-handler` — returns a synchronous Ring handler that streams SSR responses via `Transfer-Encoding: chunked`. It flushes a shell on the first byte and streams `:rf/suspense-boundary` subtrees as their data resolves; a boundary whose drain changed app-db also carries a speculative per-subtree hydration delta (projected through the same `:payload` policy as the final payload — an off-allowlist or empty delta emits no delta script), and the final chunk carries the canonical full hydration payload (if a speculative delta and the canonical payload ever disagree, the payload wins). Failure isolation is per-boundary: a boundary whose render throws keeps its fallback (with a `:rf.ssr/suspense-boundary-failed` trace) while the rest of the page streams on. Non-streaming responses (no `:rf/suspense-boundary` in the tree) still ride the chunked path with zero continuations, collapsing the wire shape to shell-prefix + shell-html + final-payload + shell-suffix. A `:redirect` set during the drain short-circuits to a bodiless Location response before any chunk is written. The shell renders on the request thread **before** the chunked head commits: a root-view / shell-walk throw fails closed to a non-200 projected error page (`:rf.error/ssr-render-failed` via the projector) with no writer thread spawned. Any `Content-Length` header accumulated during the drain is stripped (case-insensitively) so the host server owns chunked transfer framing.

  Opts mirror `ssr-handler` — same `:initial-events` (both the vector and the `(fn [request] → …)` forms), `:root-view`, `:payload`, `:fx-overrides`, `:ssr`, `:on-error`, `:error-view`, `:emit-hash?`, `:version`, `:schema-digest`, `:content-type`, plus the four trusted shell-hook opts (`:head` / `:body-end` / `:script-src` / `:app-element-id`, honoured by [`default-streaming-prefix`](#default-streaming-prefix) / [`default-streaming-suffix`](#default-streaming-suffix)). One exception: `:html-shell` is **not** supported and is rejected at handler-construction time (`:rf.error/ssr-streaming-unsupported-opt`) — the streaming path flushes a split prefix/suffix straddling the continuation chunks, so a one-piece shell callback can never run; customise the streaming envelope through the trusted shell-hook opts, or use `ssr-handler` when a bespoke one-piece shell is required.

  Concurrency model: one raw daemon `java.lang.Thread` per in-flight streamed request — no framework pool and no framework in-flight cap by design. The model is no-leak (every writer's `catch`/`finally` closes the pipe and tears the frame down on every exit path); the in-flight ceiling is the **host** server's accept-queue / worker-thread limit (Jetty / http-kit / Aleph), which operators size as the one authoritative knob for high streaming concurrency or slow-client hardening.
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
- **Description**: Return Ring middleware that delegates to `ssr-handler` for the requests its `:match?` predicate accepts, and to the wrapped handler otherwise. Useful when SSR is one of several handlers in a Ring stack — e.g. static-asset middleware in front, JSON-API routes alongside. Opts are `ssr-handler`'s opts (including the required, fail-closed `:payload`) plus `:match?` — a `(request) → boolean` predicate; when truthy, SSR renders, when falsy the call falls through to the wrapped handler. `:match?` defaults to matching every GET request.
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
- **Description**: The default `ssr-handler` opts merged *under* caller-supplied opts at construction time (caller values win). A **data** var, not a fn — exposed so callers can read or extend the baseline. `:on-error` is deliberately not here: it is resolved separately so the defaults stay orthogonal to the on-error precedence.
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
- **Description**: The default HTML envelope: wraps the rendered body in a minimal-but-runnable document so a first-time user gets a working SSR endpoint without writing string-concatenation glue. Override via the `:html-shell` opt in `ssr-handler` when you need a custom `<head>` / scripts / styles. Arguments: `body-html` is the string returned by `re-frame.ssr/render-to-string`; `payload-edn` is the hydration payload pre-serialised with `pr-str`; `opts` are the adapter opts (the keys `:head` / `:html-attrs` / `:body-attrs` / `:body-end` / `:script-src` / `:app-element-id` / `:lang` influence the envelope). The hydration-payload `<script>` is stamped with the framework-pinned id `__rf_payload` (the client bootstrap reads it via `document.getElementById("__rf_payload")`) and its EDN body is escaped EDN-aware so a payload containing `</script>` cannot close the envelope — a custom shell must emit the payload `<script>` under this id (and apply equivalent escaping), or substitute its own bootstrap that reads a custom id. `<title>` is **not** emitted by the shell; the head fragment threaded in as `:head` is the canonical source. The two attribute-value hooks (`:script-src`, `:app-element-id`) are escape-attr'd; the two content hooks (`:head`, `:body-end`) are injected raw.
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
- **Description**: The minimal 500 response used when a handler caller doesn't supply `:on-error`. Shared by `ssr-handler` **and** `stream-handler` so the topology-leak contract lives in exactly one place. The SSR runtime's error projector handles trace-emitted errors during drain; this hook covers exceptions the projector can't see (Ring-layer throws, render-time CLJ exceptions, writer-thread-pre-spawn throws). The body must not leak the throwable's message (`.getMessage` carries internal topology — JDBC URLs, deploy-root file paths, partial SQL, server-internal class names), so the fn **ignores** the throwable and emits a fixed generic plaintext body. Apps that want a branded transport-failure body supply an explicit leak-safe `:on-error` fn that returns a fixed response and ignores the throwable. (Exposed as a value — a 2-arity fn — not a `defn`, so it carries no `:arglists`.)
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
- **Description**: The shell prefix flushed as the first streamed chunk — it mirrors `default-html-shell`'s open + `<head>` + body-open + app-div-open, and shares the `:html-attrs` / `:lang` fallback with the non-streaming shell so the two envelopes can't diverge. `head-html` is the resolved head fragment; `opts` honours `:html-attrs` / `:body-attrs` / `:lang` (default `"en"`) / `:app-element-id` (default `"app"`) / `:render-hash`. When `:render-hash` is supplied (the handler passes it iff `:emit-hash?` is true), `data-rf-render-hash` is stamped on the `#app` div — the first DOM root of the streamed document — mirroring the non-streaming handler's root-element marker.
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
- **Description**: The shell suffix flushed after the final-payload chunk — it emits the bootstrap `<script>` (if `:script-src` is set; default `"/main.js"`, escape-attr'd), the raw `:body-end` HTML, and the document close (`</body></html>`). The app root (`</div>`) is **not** closed here: it is closed at the end of the shell chunk, so the resolved templates, hydration-delta scripts, and the final `__rf_payload` script all stream outside `#app`. The suffix is therefore purely the bootstrap script + raw `:body-end` + the document close — all of which already belong outside `#app`, mirroring the non-streaming `default-html-shell`.
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
- **Description**: Serialise one structured `re-frame.ssr` cookie map to a `Set-Cookie` header value per RFC 6265 §4.1. The cookie's `:name` is required; `:value` is URL-encoded and serialises as the empty string when absent; everything else (`:max-age`, `:domain`, `:path`, `:expires` (epoch-millis long), `:secure`, `:http-only`, `:same-site`) is an attribute appended after semicolons. Re-exposed from the façade so tests, alternate host adapters (Pedestal, http-kit), and user code that needs a one-off serialisation can reach it without depending on an internal namespace. Validation is fail-loud: `:name` must be a string or keyword/symbol and is checked against the RFC 6265 §4.1.1 token grammar (either violation throws `:rf.error/cookie-invalid-name`); `:domain` / `:path` / `:max-age` / `:same-site` are checked for CR / LF / NUL before concatenation (throws `:rf.error/cookie-invalid-attribute`) to close header-splitting injection; a missing `:name` throws `:rf.error/cookie-missing-name`; a non-integer `:expires` throws `:rf.error/cookie-invalid-expires`.
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
