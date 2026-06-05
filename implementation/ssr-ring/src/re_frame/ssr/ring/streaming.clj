(ns re-frame.ssr.ring.streaming
  "Chunked Ring response for streaming SSR. Per Spec 011 §Streaming SSR
  (rf2-ojakd / rf2-olb64 (a)).

  Wire shape (the order the conformance fixture pins):

    1. Shell chunk        — <!DOCTYPE html><html>…<body><div id=app>
                            <shell-with-<template>-fallbacks/></div>
    2. N resolved chunks  — one per boundary, in registration FIFO
                            order:
                              <template data-rf2-suspense-id=… …>
                                resolved-html
                              </template>
                              <script data-rf2-suspense-hydrate=…
                                      type=application/edn>delta</script>
    3. Final-payload      — <script id=\"__rf_payload\"
                              type=application/edn>full-payload</script>
    4. Closing chunk      — </body></html>

  Transport: HTTP `Transfer-Encoding: chunked`. The Ring response we
  return uses a `clojure.java.io/PipedOutputStream`-backed body
  (Ring's `clojure.java.io/IOFactory`-compatible InputStream wrapper)
  so the server (Jetty, http-kit, Aleph) flushes chunks as they're
  written.

  The lifecycle mirrors the non-streaming handler in
  `re-frame.ssr.ring.pipeline` but with the four-step chunk wiring
  inserted between `build-payload` and the response materialisation:

    setup-request-frame!         → seed per-request frame, drain on-create
    streaming/render-shell        → walk root-view, collect continuations
    flush shell-chunk             → first byte
    for each continuation:
      streaming/render-continuation
      flush resolved + delta script
    streaming/build-final-payload
    flush final __rf_payload + close
    destroy-frame! in finally

  Per the bundle-isolation contract, this ns is JVM-only (`.clj`) —
  shadow-cljs only picks up `.cljc` / `.cljs`. The client-side consumer
  of this wire shape is `re-frame.ssr.streaming.client/install!` (a
  CLJS-only, host-opt-in runtime; re-exported as `ssr/streaming-install!`,
  rf2-3hhv5): it swaps each `<template>` fallback for its resolved subtree
  in-place and merges the per-subtree `data-rf2-suspense-hydrate` delta as
  chunks arrive, reconciling against the final `__rf_payload`
  (`:replace-app-db`) when it lands."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.html-helpers :as html]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.ring.shell :as shell]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.trace :as trace])
  (:import [java.io PipedInputStream PipedOutputStream OutputStream]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

;; ---- shell envelope (split into prefix + suffix) -------------------------
;;
;; The non-streaming `default-html-shell` returns one finished string —
;; useful when everything renders synchronously. For streaming we need to
;; flush the prefix (open <html>, <head>, open <body>, open #app-div)
;; immediately and emit the suffix (close #app-div, payload script,
;; close </body></html>) after the continuations have drained.
;;
;; The split mirrors the default shell's structure 1:1. A one-piece
;; `:html-shell` fn is the NON-STREAMING contract and cannot be honoured
;; here (it can't run after streaming has started) — `stream-handler`
;; rejects `:html-shell` at construction (rf2-oq4m5). Streaming callers
;; customize the envelope through the four trusted shell-hook opts
;; (`:head` / `:body-end` / `:script-src` / `:app-element-id`, honoured by
;; the prefix/suffix below), or build a non-streaming `ssr-handler` when a
;; bespoke one-piece shell is required.

(defn default-streaming-prefix
  "The shell prefix flushed as the first chunk. Mirrors the non-streaming
  `default-html-shell`'s open + head + body-open + app-div-open. Shares
  the `:html-attrs`/`:lang` fallback with the non-streaming shell via
  `shell/html-attr-bag` so the two envelopes can't diverge."
  [head-html {:keys [html-attrs body-attrs lang app-element-id]
              :or   {lang "en" app-element-id "app"}}]
  (let [attr-bag (shell/html-attr-bag html-attrs lang)]
    (str "<!DOCTYPE html>"
         "<html" (html/attr-string attr-bag) ">"
         "<head>"
         "<meta charset=\"utf-8\">"
         (or head-html "")
         "</head>"
         "<body" (html/attr-string body-attrs) ">"
         ;; rf2-7x0qk — `:app-element-id` is an attribute-value position;
         ;; escape through `html/escape-attr` (same split as the non-
         ;; streaming `default-html-shell`). `:head` stays raw (content
         ;; position).
         "<div id=\"" (html/escape-attr app-element-id) "\">")))

(defn default-streaming-suffix
  "The shell suffix flushed after the final-payload chunk. Closes the
  app-div, emits the bootstrap script tag (if any), the body-end raw
  HTML, and the document close."
  [{:keys [body-end script-src]
    :or   {script-src "/main.js"}}]
  (str "</div>"
       (when script-src
         ;; rf2-7x0qk — `:script-src` is an attribute-value position;
         ;; escape through `html/escape-attr` (same split as the non-
         ;; streaming `default-html-shell`). `:body-end` stays raw
         ;; (content position).
         (str "<script src=\"" (html/escape-attr script-src) "\"></script>"))
       (or body-end "")
       "</body>"
       "</html>"))

;; ---- shell phase (request thread, BEFORE the head commits) --------------
;;
;; rf2-r06pc — the shell render runs on the REQUEST thread, BEFORE the
;; Ring response head is committed and BEFORE the writer thread is
;; spawned. This is the streaming counterpart to the non-streaming
;; handler's post-render re-flush (rf2-c0bq1, `pipeline.clj`):
;;
;;   - A root-view throw / shell-walk throw escalates to
;;     `:rf.error/ssr-render-failed` via the projector and FAILS CLOSED
;;     to a non-200 projected error page (Spec 011 §744/§748/§954) —
;;     handled by the handler's outer try/catch on the request thread.
;;   - A production reactive-sub throw during the shell render recovers
;;     to nil (the walk does NOT throw) but BUFFERS a fail-closed 500 on
;;     the always-on error-emit substrate (rf2-vvwmi). The handler
;;     re-reads the response accumulator (`ssr/get-response`) AFTER the
;;     shell render to pick that status up, exactly as `build-full-
;;     response*` does — and a non-200 there fails closed to the
;;     projected error page rather than streaming the broken HTML.
;;
;; Only once the shell is KNOWN-RENDERABLE (clean render AND a success
;; status) does the handler commit the chunked response head and hand
;; the already-rendered shell + continuations to the daemon writer. The
;; writer's only remaining failure surface is continuation drains (which
;; the inline-fallback contract already covers, Spec 011 §942-954) and
;; the final-payload / suffix writes — and those happen AFTER the first
;; chunk has committed, so they correctly degrade to a truncate-and-close
;; rather than re-stamping a status the wire has already sent.

(defn- ->utf8 ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- write-chunk! [^OutputStream out ^String s]
  (.write out (->utf8 s))
  (.flush out))

(defn render-streaming-shell!
  "Resolve + render the streaming shell on the CALLING (request) thread.
  Returns the pre-rendered pieces the daemon writer needs to drain the
  chunk stream:

    {:hiccup        <resolved root hiccup>     ;; for the final-hash
     :head-html     \"…\"                        ;; resolved <head> fragment
     :html-attrs    {…} or nil                  ;; stamped on <html>
     :body-attrs    {…} or nil                  ;; stamped on <body>
     :shell-html    \"…\"                        ;; chunk 1 body
     :continuations [{:id … :subtree …} …]}     ;; drain queue (FIFO)

  Throws propagate to the caller (the handler's outer try/catch routes
  them through the projector → fail-closed non-200, rf2-r06pc). The
  recovered-to-nil sub case does NOT throw here — its buffered fail-
  closed status is picked up by the handler's post-shell `get-response`
  re-read."
  [frame-id {:keys [root-view body-end csp-script-src-allowlist] :as opts}]
  ;; rf2-h3dg0 — run the dev-mode `:csp-script-src-allowlist` scan over the
  ;; raw `:body-end` hook on the REQUEST thread, mirroring what the non-
  ;; streaming `default-html-shell` does at shell-build time (shell.clj —
  ;; `check-body-end-csp-hosts!` is the FIRST thing it runs). The streaming
  ;; suffix (`default-streaming-suffix`) injects `:body-end` RAW just like
  ;; the non-streaming shell, but it only destructures `:body-end` /
  ;; `:script-src` and never ran the allowlist scan — so streaming SSR
  ;; silently lost the defense-in-depth CSP warning that non-streaming SSR
  ;; emits for the SAME config (Spec 011 §trusted-shell envelope). Running
  ;; it here (request thread, before the head commits) keeps the warning on
  ;; the same thread + listener context as the non-streaming path and leaves
  ;; `default-streaming-suffix` a pure string-builder symmetric with
  ;; `default-streaming-prefix`. Production builds elide the whole check via
  ;; `interop/debug-enabled?` inside `check-body-end-csp-hosts!`; the raw
  ;; emission of `:body-end` into the suffix is UNCHANGED — the scan is a
  ;; signal, never a block or rewrite.
  (shell/check-body-end-csp-hosts! body-end csp-script-src-allowlist)
  (rf/with-frame frame-id
    (let [hiccup     (lifecycle/resolve-root-view root-view)
          {:keys [head-html html-attrs body-attrs]}
          (if (:head opts)
            {:head-html (:head opts) :html-attrs nil :body-attrs nil}
            (lifecycle/resolve-head frame-id))
          {:keys [shell-html continuations]} (streaming/render-shell hiccup)]
      {:hiccup        hiccup
       :head-html     head-html
       :html-attrs    html-attrs
       :body-attrs    body-attrs
       :shell-html    shell-html
       :continuations continuations})))

;; ---- chunk writer (daemon thread, AFTER the head commits) ---------------
;;
;; The Ring response body is a `PipedInputStream` paired with a writer
;; thread that pushes chunks onto a `PipedOutputStream`. The writer
;; thread receives the ALREADY-RENDERED shell + continuations from the
;; request thread (rf2-r06pc) — it no longer resolves/renders the shell
;; itself, so a shell failure can never reach this thread. It holds the
;; per-request frame open across continuation drains and is the place
;; where post-commit exceptions are caught — anything that throws there
;; gracefully closes the pipe so the client sees a clean EOF rather than
;; a half-streamed response.
;;
;; Per Spec 011 §Failure semantics — inline fallback — exceptions
;; INSIDE a continuation render are caught by streaming/render-continuation
;; (which returns {:failed? true :html <fallback-html> :delta nil}); the
;; writer thread proceeds with the next boundary. Exceptions OUTSIDE a
;; continuation (e.g. a final-payload build throw, or a downstream pipe
;; broken-write) close the pipe with the partial response that was
;; already flushed — the first chunk has already committed the success
;; status to the wire, so a truncate-and-close is the only safe outcome
;; (the status can no longer change). The server logs the trace via
;; `:rf.error/ssr-streaming-writer-failed`.

(defn- run-streaming-writer!
  "Run the streaming writer on the calling (daemon) thread. The caller
  supplies an open `OutputStream` (the pipe sink) and the pre-rendered
  shell pieces from `render-streaming-shell!` (rf2-r06pc — the shell is
  resolved/rendered on the request thread BEFORE the head commits, so
  shell failures fail closed there; this thread only drains the chunk
  stream). On any throw, the catch arm emits a
  `:rf.error/ssr-streaming-writer-failed` trace and closes the stream
  cleanly so the Ring server can EOF the response."
  [^OutputStream out frame-id rendered opts]
  (try
    (let [{:keys [emit-hash? version schema-digest payload]} opts
          {:keys [hiccup head-html html-attrs body-attrs
                  shell-html continuations]} rendered
          shell-opts (merge opts
                            {:html-attrs html-attrs
                             :body-attrs body-attrs})]
      ;; Chunk 1 — shell prefix + shell HTML (with template fallbacks).
      (write-chunk! out (default-streaming-prefix head-html shell-opts))
      (write-chunk! out shell-html)
      ;; Chunks 2..N+1 — one per continuation, FIFO over registration.
      ;;
      ;; rf2-sgvn6 / rf2-b1v8v — drain a GROWABLE FIFO worklist, not a
      ;; fixed (doseq) over the shell's initial vector. A nested
      ;; `:rf/suspense-boundary` inside a continuation's subtree registers
      ;; a NEW continuation when that continuation renders (Spec 011
      ;; §922-924/§966/§983); `streaming/render-continuation` returns those
      ;; newly-discovered entries on `:continuations`. We append them at
      ;; the TAIL of the queue (`into` over a PersistentQueue preserves
      ;; FIFO) and keep draining until the queue empties — so the inner
      ;; boundary's resolved chunk streams AFTER all originally-registered
      ;; continuations, exactly as the document-order contract requires.
      (loop [queue (into clojure.lang.PersistentQueue/EMPTY continuations)]
        (when-let [entry (peek queue)]
          (let [{:keys [id html delta failed? continuations]}
                (rf/with-frame frame-id (streaming/render-continuation frame-id entry))
                tmpl-fn (if failed?
                          streaming/failed-template
                          streaming/resolved-template)]
            (write-chunk! out (tmpl-fn id html))
            ;; rf2-kjf3m.5 — emit the per-boundary hydration-delta <script>
            ;; ONLY when the delta carries something to hydrate. A
            ;; continuation that merely READS app-db (the common case —
            ;; deferred subtrees rarely mutate state) yields a delta of
            ;; `{}` from `streaming/subtree-delta`, which is `some?` but
            ;; empty; `(seq delta)` is the correct guard (falsy for both
            ;; `{}` and `nil`) so an unchanged boundary emits NO delta
            ;; script rather than an inert `<script …>{}</script>` chunk
            ;; the client would parse and discard. `failed?` continuations
            ;; carry `:delta nil` (also falsy here), so the `not failed?`
            ;; arm is now redundant but kept for intent clarity.
            (when (and (not failed?) (seq delta))
              (write-chunk! out (streaming/hydrate-delta-script id (pr-str delta))))
            ;; Pop the drained entry, append any nested continuations at
            ;; the tail (FIFO), continue until empty.
            (recur (into (pop queue) continuations)))))
      ;; Chunk N+2 — final canonical __rf_payload.
      ;;
      ;; rf2-5knxf.2 — the streaming final-payload `:rf/render-hash` is
      ;; computed over `hiccup` (the resolved root view returned by
      ;; `render-streaming-shell!`) via `render-tree-hash`, the IDENTICAL
      ;; mechanism the non-streaming `build-full-response*` uses (it hashes
      ;; `(resolve-root-view root-view)` the same way). This is the correct
      ;; structural hash, NOT a streaming-specific divergence:
      ;;
      ;;   - `render-tree-hash` is a PURE structural FNV-1a over the
      ;;     canonical-EDN of the hiccup (hash.cljc). It does NOT expand
      ;;     view-refs or registered views, and it does NOT throw on a
      ;;     `:rf/suspense-boundary` head — it just hashes the vector
      ;;     structurally. So a `:rf/suspense-boundary` node hashes to the
      ;;     same canonical-EDN bytes on the server AND on the client: the
      ;;     marker is NOT a source of mismatch. A streaming-aware client
      ;;     verifies via `verify-hydration!` against
      ;;     `:render-tree-fn #((rf/view :app/root))` — whose result is the
      ;;     SAME marker-bearing hiccup the server's `(rf/view :app/root)`
      ;;     produces. Both sides hash the marker-bearing tree to the same
      ;;     hex (empirically confirmed; see the
      ;;     `streaming-final-hash-matches-client-resolved-tree` regression
      ;;     in `ring_streaming_test`).
      ;;
      ;;   - The ONLY way server and client hashes diverge is the host
      ;;     passing a NON-symmetric pair of forms: a view-REF `:root-view
      ;;     [:app/root]` on the server (which `resolve-root-view` leaves
      ;;     UNEXPANDED → the hash is over the 1-element `[:app/root]`
      ;;     vector) vs an EXPANDED `:render-tree-fn #((rf/view :app/root))`
      ;;     on the client. This view-ref-vs-expanded asymmetry is SHARED by
      ;;     the non-streaming handler (identical `resolve-root-view` →
      ;;     `render-tree-hash` shape) and is the host's responsibility to
      ;;     avoid — pass `:root-view` and `:render-tree-fn` as matching
      ;;     forms (both expanding, e.g. server `:root-view #((rf/view
      ;;     :app/root))` / client `:render-tree-fn #((rf/view :app/root))`,
      ;;     mirroring the non-streaming worked example's `((rf/view
      ;;     :app/root))`). It is NOT a marker bug and NOT streaming-specific.
      ;;     Spec 011 §Hydration equivalence rule (structural, not textual)
      ;;     + §Hydration-mismatch detection.
      (let [final-hash    (rf/with-frame frame-id (ssr/render-tree-hash hiccup))
            final-payload (rf/with-frame frame-id
                            (streaming/build-final-payload
                              frame-id final-hash
                              {:version       version
                               :schema-digest schema-digest
                               :payload       payload}))]
        ;; Shared id-pinned, `</script>`-escaped payload <script>
        ;; (rf2-7ksyr) — same helper the non-streaming shell uses.
        (write-chunk! out (shell/payload-script-tag (pr-str final-payload))))
      ;; Chunk N+3 — shell suffix close.
      (write-chunk! out (default-streaming-suffix opts)))
    (catch Throwable t
      (trace/emit-error! :rf.error/ssr-streaming-writer-failed
                         {:frame    frame-id
                          :exception (.getMessage t)
                          :ex-class  (.getName (class t))
                          :recovery  :truncate-and-close}))
    (finally
      (try (.close out) (catch Throwable _ nil)))))

;; ---- public surface ------------------------------------------------------

(defn strip-content-length
  "Remove any `Content-Length` header (case-insensitively) from a Ring
  response header map. The streaming body is a chunk-producing
  `PipedInputStream` whose final byte count is unknown when the head
  commits, so the response MUST use `Transfer-Encoding: chunked`
  framing (Spec 011 §Streaming SSR — the wire shape pins chunked
  transfer). A stale fixed `Content-Length` — set/appended by app or
  server init code (`:rf.server/set-header` / `:rf.server/append-header`
  during the `:on-create` drain) before streaming was chosen — would
  otherwise survive onto the streamed response, and a Ring server may
  honour that length instead of chunking: truncated HTML, clients
  waiting on the wrong byte count, or lost progressive chunks.

  Ring header NAMES are caller-cased verbatim by the materialiser
  (`headers/merge-pair-into-header-map` preserves whatever casing the
  fx supplied), so a `Content-Length` can land under any casing
  (`Content-Length`, `content-length`, `CONTENT-LENGTH`, …). RFC 7230
  §3.2 makes header names case-insensitive tokens, so we drop EVERY
  key whose lower-case is `content-length` — letting the Ring server
  own transfer framing for the InputStream body."
  [headers-map]
  (into {}
        (remove (fn [[k _v]]
                  (= "content-length" (str/lower-case (str k)))))
        headers-map))

(defn stream-handler
  "Return a synchronous Ring handler that streams SSR responses via
  `Transfer-Encoding: chunked`. Per Spec 011 §Streaming SSR.

  Opts mirror `re-frame.ssr.ring/ssr-handler` — same `:on-create` /
  `:root-view` / `:payload` / `:on-error` (+ `:on-error-fallback`) /
  `:error-view` / `:emit-hash?` / `:version` / `:schema-digest` /
  `:content-type` plus the four trusted shell-hook opts (`:head` /
  `:body-end` / `:script-src` / `:app-element-id`, honoured by
  `default-streaming-prefix` / `default-streaming-suffix`) — with ONE
  exception (rf2-oq4m5):

    `:html-shell` is NOT supported by the streaming path and is REJECTED
    at handler-construction time (`:rf.error/ssr-streaming-unsupported-opt`).
    The non-streaming handler builds its response from a ONE-PIECE
    `:html-shell` fn `(body-html payload-edn opts) → string`; the streaming
    handler flushes the envelope as a SPLIT prefix/suffix straddling the
    continuation chunks (the wire shape below), so a one-piece shell
    callback can never run after streaming starts. Rather than silently
    drop a passed `:html-shell` (a fail-open contract gap — a custom shell
    commonly carries CSP nonces / asset URLs / root markup an app would
    lose when switching ssr-handler → stream-handler), the handler fails
    closed at boot. Customize the streaming envelope through the four
    trusted shell-hook opts, or use a non-streaming `ssr-handler` when a
    bespoke one-piece shell is required.

  Plus implicit streaming semantics on every request — non-streaming
  responses (no `:rf/suspense-boundary` in the tree) still ride the
  chunked path but with zero continuations, so the wire shape collapses
  to shell-prefix + shell-html + final-payload + shell-suffix.

  The returned handler:
    - sets up the per-request frame (request slot, frame registration,
      synchronous :on-create drain),
    - reads the response accumulator; if :redirect is set, short-
      circuits to a non-streamed Location response (Spec 011 §Redirect
      precedence) AND destroys the per-request frame inline (the writer
      thread is never spawned on this branch),
    - otherwise RENDERS THE SHELL on the request thread BEFORE committing
      the chunked response head (rf2-r06pc — the streaming counterpart
      of the non-streaming post-render re-flush, rf2-c0bq1). A root-view
      / shell-walk throw escalates to `:rf.error/ssr-render-failed` via
      the projector and a production reactive-sub throw during the shell
      render buffers a fail-closed status the post-shell `get-response`
      re-read picks up — BOTH fail closed to a non-200 projected error
      page (Spec 011 §744/§748/§954) on the request thread, with NO pipe
      and NO writer thread spawned. The chunked response is committed
      ONLY once the shell is known-renderable (clean render AND a success
      status),
    - then materialises the response head (status / headers / cookies)
      — so a header/cookie serialisation throw on a value that escaped
      the fx boundary's partial validation short-circuits to `:on-error`
      with no pipe + no thread to orphan (rf2-z5azc) — and only then
      spawns a streaming writer on a daemon thread that flushes the
      PRE-RENDERED shell → continuations → final payload → close,
      destroying the frame in that thread's finally so the per-frame
      side-channels clear (Spec 011 §Per-request frame teardown contract)
      without blocking the response close. The writer thread's only
      remaining failure surface is continuation drains (inline-fallback,
      Spec 011 §942-954) and the post-first-chunk final-payload / suffix
      writes — never the shell, which already committed its status on
      the request thread.

  The response body is a `PipedInputStream` Ring accepts directly; the
  pipe's writer side runs on a daemon thread so Jetty/http-kit/Aleph
  can begin sending bytes immediately while the writer continues to
  pump chunks. The pipe's sink-side close (in the writer's `finally`)
  signals EOF to the server.

  Concurrency model (Spec 011 §Streaming SSR — Writer concurrency model;
  rf2-fzew1): ONE raw daemon `java.lang.Thread` per in-flight streamed
  request — no framework pool, no framework in-flight cap, by design.
  The model is no-LEAK (every writer's `catch Throwable`/`finally` closes
  the pipe and tears the frame down on every exit path; the live count
  decays to zero — `concurrency_stress_test` proves it). The in-flight
  CEILING is the HOST server's accept-queue / worker-thread limit
  (Jetty/http-kit/Aleph), NOT a framework cap: operators size that one
  authoritative knob for high streaming concurrency or slow-client
  hardening. A framework pool is deliberately avoided — it would either
  duplicate the host's limit or break the proven no-leak teardown by
  decoupling thread lifetime from request lifetime. (JDK 21+ virtual
  threads / an opt-in `:writer-thread-factory` are additive future work.)

  Returns:

    (fn handler [ring-request] ring-response)"
  [raw-opts]
  ;; Construction-time validation — the SAME fail-closed-at-boot triple
  ;; `ssr-handler` runs, shared via `lifecycle/validate-construction-opts!`
  ;; so both handlers refuse to construct at the same boundary. For
  ;; streaming specifically, a missing :on-create would otherwise fail
  ;; per-request (500) and a missing :root-view would silently truncate
  ;; the chunked response from the writer thread; the trusted-shell opts
  ;; cross the same trust boundary as the non-streaming
  ;; `default-html-shell` — `:head` / `:body-end` injected RAW (content
  ;; positions), `:script-src` / `:app-element-id` escape-attr'd
  ;; (attribute-value positions, rf2-7x0qk) by the streaming
  ;; prefix/suffix. See `validate-construction-opts!` for the per-check
  ;; rationale.
  (lifecycle/validate-construction-opts! raw-opts)
  ;; rf2-oq4m5 — reject opts the streaming path cannot honour (currently
  ;; `:html-shell`) at construction time. `ssr-handler` builds its response
  ;; from a one-piece `:html-shell` fn; the streaming path flushes a SPLIT
  ;; prefix/suffix straddling the continuation chunks, so a one-piece shell
  ;; callback can never run after streaming starts and would otherwise be
  ;; silently dropped — a fail-OPEN API-contract gap (a production app
  ;; switching ssr-handler → stream-handler would lose CSP nonces / asset
  ;; URLs / root markup with no signal). Fail CLOSED at boot instead.
  (lifecycle/validate-streaming-opts! raw-opts)
  ;; Mirror ssr-handler's defaults so streaming and non-streaming
  ;; handlers feel symmetric to callers. `:on-error` resolves the same
  ;; way via the shared `lifecycle/resolve-on-error` (rf2-c1tac): caller's
  ;; `:on-error` wins, then `:on-error-fallback {:body … :content-type …}`
  ;; is templated through `make-default-on-error`, then the locked
  ;; `default-on-error` (rf2-kzvwq topology-leak contract).
  (let [opts        (-> (merge {:emit-hash?   true
                                :content-type "text/html; charset=utf-8"}
                               raw-opts)
                        (assoc :on-error (lifecycle/resolve-on-error raw-opts)))
        {:keys [on-error content-type]} opts]
    (fn ring-handler [request]
      (let [{:keys [frame-id short-circuit]}
            (pipeline/setup-request-frame! opts request)]
        (if short-circuit
          short-circuit
          (try
            (let [resp (ssr/get-response frame-id)]
              (if (some? (:redirect resp))
                ;; Redirect short-circuits the stream — no chunked body,
                ;; so the writer thread (whose `finally` normally tears
                ;; the frame down) is never spawned. Destroy the per-
                ;; request frame inline BEFORE returning, or every
                ;; redirected streaming request leaks the frame + its
                ;; three side-channel slots (request / response /
                ;; pending-error-trace) — a per-request leak on auth-
                ;; gated SSR routes where redirects are common (Spec 011
                ;; §Per-request frame teardown contract). The 2-arg form
                ;; (no `content-type`) matches the non-streaming redirect
                ;; path — a bodiless redirect has no meaningful Content-
                ;; Type to default.
                (try
                  (pipeline/ssr-response->ring-response resp nil)
                  (finally
                    (lifecycle/destroy-frame-quietly! frame-id)))
                ;; Streaming path. rf2-r06pc — RENDER THE SHELL on THIS
                ;; (request) thread BEFORE committing the chunked response
                ;; head, the streaming counterpart of the non-streaming
                ;; post-render re-flush (rf2-c0bq1). The shell render is
                ;; the request's structural foundation; its failure modes
                ;; MUST fail closed to a non-200 (Spec 011 §744/§748/§954),
                ;; NOT a silent 200/truncated chunked body from a detached
                ;; daemon thread that can no longer stamp the status.
                ;;
                ;;   - A root-view / shell-walk throw propagates here and
                ;;     is routed through `project-render-throw->ring-
                ;;     response` (→ `:rf.error/ssr-render-failed`, projector,
                ;;     non-200 projected error page) — same wire-body
                ;;     contract as the non-streaming `build-full-response`
                ;;     catch arm.
                ;;   - A production reactive-sub throw during the shell
                ;;     render recovers to nil (the walk does NOT throw) but
                ;;     buffers a fail-closed 500 on the always-on error-emit
                ;;     substrate (rf2-vvwmi). The post-shell `ssr/get-
                ;;     response` re-read drains that buffer and stamps the
                ;;     500 onto the response accumulator BEFORE the chunked
                ;;     head is materialised — so the committed wire status
                ;;     is the fail-closed 500, never the stale pre-render
                ;;     200 (the rendered shell still streams as the body,
                ;;     exactly as the non-streaming handler ships the
                ;;     recovered body with the 500 — the status is the
                ;;     fail-closed signal).
                ;;
                ;; NO pipe and NO writer thread is constructed until the
                ;; shell is known-renderable (clean render AND a success
                ;; status). A shell-render throw is caught by the dedicated
                ;; inner try below and converted to the PROJECTED error
                ;; response (NOT the `:on-error` transport net) — matching
                ;; the non-streaming `build-full-response` catch arm, where
                ;; a render-time throw projects rather than escaping to
                ;; `:on-error`. The handler's OUTER try/catch remains the
                ;; net for the OTHER throws (head materialise, redirect
                ;; materialise) → `:on-error`.
                (let [rendered  (try
                                  (render-streaming-shell! frame-id opts)
                                  (catch Throwable t
                                    ;; Shell render threw — fail closed to
                                    ;; the projected non-200 error page on
                                    ;; THIS thread, tear the frame down
                                    ;; inline (no writer was spawned), and
                                    ;; return. The deref below never runs.
                                    (let [err-resp (pipeline/project-render-throw->ring-response
                                                     frame-id t opts)]
                                      (lifecycle/destroy-frame-quietly! frame-id)
                                      (reduced err-resp))))]
                  (if (reduced? rendered)
                    @rendered
                    ;; Shell rendered without throwing. Re-read the
                    ;; response accumulator to surface any fail-closed
                    ;; status a recovered-to-nil reactive sub buffered
                    ;; during the shell render (rf2-vvwmi / rf2-r06pc) —
                    ;; the streaming analogue of `build-full-response*`'s
                    ;; post-render `ssr/flush-response!` re-read (rf2-c0bq1).
                    ;; A production reactive sub that throws mid-render
                    ;; recovers to nil and BUFFERS a fail-closed 500 on the
                    ;; always-on error-emit substrate; `ssr/get-response`
                    ;; drains that buffer and stamps the 500 onto `resp2`.
                    ;; We MATERIALISE the chunked response head from
                    ;; `resp2`, so the wire status is the fail-closed 500
                    ;; — never the stale pre-render 200 the OLD writer-
                    ;; thread order shipped (Spec 011 §744/§750).
                    ;;
                    ;; A deliberate app-set non-200 (`:rf.server/set-status`)
                    ;; surfaces here too and rides the wire unchanged —
                    ;; identical to the non-streaming handler, which streams
                    ;; the rendered body with whatever post-flush status the
                    ;; accumulator carries (the status is the fail-closed
                    ;; signal; the body is moot under a non-200).
                    ;;
                    ;; rf2-5knxf.1 — a `:redirect` is the ONE accumulator
                    ;; state that must NOT stream a body: a 3xx + Location is
                    ;; a bodiless wire response (Spec 011 §Redirect
                    ;; precedence), so streaming a full HTML document under it
                    ;; would ship a malformed redirect AND spawn a body-
                    ;; pumping writer for a request the contract says has no
                    ;; body. The non-streaming handler gets this for free —
                    ;; `ssr-response->ring-response` ignores the body arg on
                    ;; its `:redirect` branch (pipeline.clj). The streaming
                    ;; path must branch EXPLICITLY because it would otherwise
                    ;; overwrite the materialised `:body ""` with the pipe and
                    ;; start the writer. In v1 a `:redirect` cannot surface at
                    ;; this post-shell re-read (it is only set by the
                    ;; `:rf.server/redirect` fx during the `:on-create` drain,
                    ;; which the EARLY redirect branch above already catches;
                    ;; the error projector stamps `:status` only, never
                    ;; `:redirect` — error_listener.cljc). This branch is
                    ;; defense-in-depth for a future render-phase fx /
                    ;; projector / hand-built accumulator that learns to
                    ;; redirect — and it aligns the code with this very
                    ;; comment's parity claim.
                    (let [resp2 (ssr/get-response frame-id)]
                      (if (some? (:redirect resp2))
                        ;; Bodiless redirect — mirror the EARLY :on-create
                        ;; redirect branch above (and the non-streaming
                        ;; `pipeline.clj` redirect branch): materialise the
                        ;; Location response with NO body, spawn NO writer,
                        ;; and destroy the per-request frame inline (no writer
                        ;; thread will run its teardown `finally`). The 2-arg
                        ;; form passes no `content-type` — a bodiless redirect
                        ;; has no meaningful Content-Type to default, matching
                        ;; the non-streaming + early-branch redirect paths.
                        (try
                          (pipeline/ssr-response->ring-response resp2 nil)
                          (finally
                            (lifecycle/destroy-frame-quietly! frame-id)))
                        ;; Non-redirect — the streaming path. Materialise the
                        ;; head, wire the pipe, spawn the writer.
                        (let [;; rf2-z5azc — MATERIALISE the response head
                              ;; (status / headers / cookies) from the re-read
                              ;; `resp2` BEFORE constructing the pipe or
                              ;; spawning the writer. Cookie / header
                              ;; serialisation CAN throw at materialise time on
                              ;; a value that escaped the fx boundary's partial
                              ;; validation — e.g. a `:max-age` carrying CR/LF,
                              ;; which `cookie->set-cookie-header` rejects but
                              ;; the runtime `validate-cookie!` does not. If we
                              ;; spawned the writer first, that throw would
                              ;; orphan the pipe (the daemon writer pumps the
                              ;; full body into a reader-less pipe, blocks once
                              ;; the 16 KiB buffer fills, and leaks one live
                              ;; thread per request). By building `resp-map`
                              ;; first, a head-materialisation failure short-
                              ;; circuits to the outer catch BEFORE any thread
                              ;; or pipe exists → on-error, no detached writer,
                              ;; no orphaned pipe.
                              ;;
                              ;; No body default-stamp here (we pass our own
                              ;; InputStream); `:body` is assoc'd after the
                              ;; writer is wired below.
                              ;;
                              ;; rf2-h3dg0 — STRIP any `Content-Length` header
                              ;; (case-insensitively) from the materialised
                              ;; head before wiring the chunk-writer body. App
                              ;; / server init can `:rf.server/set-header` (or
                              ;; `append-header`) a `Content-Length` during the
                              ;; `:on-create` drain — a fixed length that is
                              ;; meaningless (and actively harmful) once the
                              ;; body becomes a chunk-producing PipedInputStream
                              ;; of unknown final size. Left in place, a Ring
                              ;; server may honour that length instead of using
                              ;; chunked transfer framing → truncated HTML /
                              ;; clients blocked on the wrong byte count / lost
                              ;; chunks, violating Spec 011's chunked-transfer
                              ;; streaming contract. The non-streaming handler
                              ;; never hits this — its body is a finished string
                              ;; whose Content-Length (if any) is correct. The
                              ;; streaming path owns transfer framing for the
                              ;; InputStream body, so it MUST drop the stale
                              ;; length and let the server frame the stream.
                              resp-map (-> (pipeline/ssr-response->ring-response
                                             resp2 "" content-type)
                                           (update :headers strip-content-length))
                              ;; 16 KiB pipe buffer — large enough to absorb the
                              ;; shell chunk in one write so the writer thread
                              ;; rarely blocks on a slow consumer, small enough
                              ;; that one stuck client doesn't pin a non-trivial
                              ;; chunk of heap per request.
                              pipe-in  (PipedInputStream. (* 16 1024))
                              pipe-out (PipedOutputStream. pipe-in)]
                          ;; Daemon thread (rf2-ekwda): a writer blocked on
                          ;; `.write` to the bounded 16 KiB pipe of a slow-loris
                          ;; client must NOT keep the JVM alive at shutdown. The
                          ;; ns + handler docstrings and both test namespaces
                          ;; assert daemon semantics; this is what makes that
                          ;; contract real.
                          (doto
                            (Thread.
                              ^Runnable
                              (fn writer-thread []
                                (try
                                  (run-streaming-writer! pipe-out frame-id rendered opts)
                                  (finally
                                    ;; The writer's own finally closes the
                                    ;; pipe; the frame teardown happens here so
                                    ;; it does NOT block the response close on
                                    ;; the slower destroy path.
                                    (lifecycle/destroy-frame-quietly! frame-id))))
                              ^String (str "rf2-ssr-streaming-" (name frame-id)))
                            (.setDaemon true)
                            (.start))
                          (assoc resp-map :body pipe-in))))))))
            (catch Throwable t
              ;; get-response throw, redirect-materialise throw, OR (per
              ;; rf2-z5azc) a head-materialisation throw raised BEFORE the
              ;; writer thread is spawned — none happen under the
              ;; streaming writer's own catch arm. Destroy the frame
              ;; inline and respond per on-error. rf2-ljjh0 —
              ;; `safe-on-error` contains a throwing caller `:on-error`:
              ;; it falls back to the locked `default-on-error` rather
              ;; than escaping as a raw container 500 with leaked
              ;; internals.
              (try (lifecycle/destroy-frame-quietly! frame-id)
                   (catch Throwable _ nil))
              (lifecycle/safe-on-error on-error request t))))))))
