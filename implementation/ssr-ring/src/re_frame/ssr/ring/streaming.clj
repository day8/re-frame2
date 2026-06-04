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
  (:require [re-frame.core :as rf]
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
;; The split mirrors the default shell's structure 1:1 so a caller-
;; supplied `:html-shell` can opt out of streaming without rewriting
;; their envelope — they simply don't pass `:stream? true` and the
;; non-streaming path takes over.

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
  [frame-id {:keys [root-view] :as opts}]
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
      (doseq [entry continuations]
        (let [{:keys [id html delta failed?]}
              (rf/with-frame frame-id (streaming/render-continuation frame-id entry))
              tmpl-fn (if failed?
                        streaming/failed-template
                        streaming/resolved-template)]
          (write-chunk! out (tmpl-fn id html))
          (when (and (not failed?) (some? delta))
            (write-chunk! out (streaming/hydrate-delta-script id (pr-str delta))))))
      ;; Chunk N+2 — final canonical __rf_payload.
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

(defn stream-handler
  "Return a synchronous Ring handler that streams SSR responses via
  `Transfer-Encoding: chunked`. Per Spec 011 §Streaming SSR.

  Opts are the same as `re-frame.ssr.ring/ssr-handler` plus implicit
  streaming semantics on every request — non-streaming responses (no
  `:rf/suspense-boundary` in the tree) still ride the chunked path but
  with zero continuations, so the wire shape collapses to
  shell-prefix + shell-html + final-payload + shell-suffix.

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
                    ;; thread order shipped (Spec 011 §744/§750). A redirect
                    ;; or a deliberate app-set non-200 (`:rf.server/set-
                    ;; status`) surfaces here too and rides the wire
                    ;; unchanged — identical to the non-streaming handler,
                    ;; which streams the rendered body with whatever post-
                    ;; flush status the accumulator carries (the status is
                    ;; the fail-closed signal; the body is moot under a
                    ;; non-200).
                    (let [resp2 (ssr/get-response frame-id)
                          ;; rf2-z5azc — MATERIALISE the response head
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
                          resp-map (pipeline/ssr-response->ring-response
                                     resp2 "" content-type)
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
                      (assoc resp-map :body pipe-in))))))
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
