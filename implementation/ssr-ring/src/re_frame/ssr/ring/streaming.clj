(ns re-frame.ssr.ring.streaming
  "Chunked Ring response for streaming SSR, per Spec 011.

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

  Transport: the Ring response carries an unknown-length `PipedInputStream`
  body. The host server owns HTTP framing (for example chunked HTTP/1.1) and
  reads bytes as the paired writer produces them.

  The lifecycle mirrors the non-streaming handler in
  `re-frame.ssr.ring.pipeline` but with the four-step chunk wiring
  inserted between `build-payload` and the response materialisation:

    setup-request-frame!         → seed per-request frame, drain initial-events
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
  CLJS-only, host-opt-in runtime; re-exported as `ssr/streaming-install!`):
  it swaps each `<template>` fallback for its resolved subtree
  in-place and merges the per-subtree `data-rf2-suspense-hydrate` delta as
  chunks arrive, reconciling against the final `__rf_payload`
  (`:replace-frame-state`) when it lands."
  (:require [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
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
;; immediately and emit the suffix (payload script and document close)
;; close </body></html>) after the continuations have drained.
;;
;; The split mirrors the default shell's structure 1:1. A one-piece
;; `:html-shell` fn is the NON-STREAMING contract and cannot be honoured
;; here (it can't run after streaming has started) — `stream-handler`
;; rejects `:html-shell` at construction. Streaming callers
;; customize the envelope through the four trusted shell-hook opts
;; (`:head` / `:body-end` / `:script-src` / `:app-element-id`, honoured by
;; the prefix/suffix below), or build a non-streaming `ssr-handler` when a
;; bespoke one-piece shell is required.

(defn default-streaming-prefix
  "The shell prefix flushed as the first chunk. Mirrors the non-streaming
  `default-html-shell`'s open + head + body-open + app-div-open. Shares
  the `:html-attrs`/`:lang` fallback with the non-streaming shell via
  `shell/html-attr-bag` so the two envelopes can't diverge.

  When `:render-hash` is supplied (iff `:emit-hash?` is true),
  `data-rf-render-hash` is stamped on the streaming root element — the
  `#app` div. The value is the body-only structural hash shared with the
  payload's `:rf/render-hash`. `:head-hash`
  (optional) is the SEPARATE client-reconstructible head-model hash,
  stamped as `data-rf-head-hash` on `<head>` — omitted when nil."
  [head-html {:keys [render-hash] :as opts}]
  ;; Delegate all envelope and escaping decisions to the shared renderer;
  ;; streaming alone supplies the optional app-root render hash.
  (shell/document-prefix head-html render-hash opts))

(defn default-streaming-suffix
  "The shell suffix flushed after the final-payload chunk. Emits the
  bootstrap script tag (if any), the body-end raw HTML, and the document
  close.

  The app root (`</div>`) is closed
  closed at the END of the shell chunk (immediately after the shell HTML,
  see `run-streaming-writer!`), so the resolved templates, hydration-delta
  scripts, and the final `__rf_payload` script all stream OUTSIDE `#app`
  per Spec 011 §Chunk-ordering contract (chunk 1 is
  `…<div id=\"app\"><shell-html/></div>`). The suffix is therefore purely
  the bootstrap `<script>` + the raw `:body-end` + the document close —
  all of which already belonged outside `#app`, mirroring the non-streaming
  `default-html-shell` (which emits the payload script + bootstrap + body-end
  after `</div>`)."
  [opts]
  ;; The shared suffix owns bootstrap escaping, raw body-end content, and the
  ;; document close; the app root closes in the shell chunk.
  (shell/document-suffix opts))

;; ---- shell phase (request thread, before response materialisation) -------
;;
;; Render the shell and re-read projected status on the request thread before
;; committing the response head. Pre-commit failures can still return a
;; projected non-200; after the writer starts, failures can only use inline
;; continuation fallback or truncate-and-close.

(defn- write-chunk! [^OutputStream out ^String s]
  (.write out (.getBytes s StandardCharsets/UTF_8))
  (.flush out))

(defn render-streaming-shell!
  "Resolve + render the streaming shell on the CALLING (request) thread.
  Returns the pre-rendered pieces the daemon writer needs to drain the
  chunk stream:

    {:hiccup        <resolved root hiccup>     ;; for the final-hash
     :head-html     \"…\"                        ;; resolved <head> fragment
     :html-attrs    {…} or nil                  ;; stamped on <html>
     :body-attrs    {…} or nil                  ;; stamped on <body>
     :head-hash     \"…\" or nil                  ;; client-reconstructible head-model hash
     :doc-hash      \"…\" or nil                  ;; BODY-ONLY structural hash
     :shell-html    \"…\"                        ;; chunk 1 body
     :continuations [{:id … :subtree …} …]}     ;; drain queue (FIFO)

  `:doc-hash` is the body-only structural hash. It
  describes the PRE-drain shell — the exact tree streamed in chunk 1 — and
  drives the streaming root-element `data-rf-render-hash` marker (when
  `:emit-hash?` is true). It is **nil when `:root-view` resolves to the
  unresolved root form** — that root carries no hash on either channel
  (rf2-q1b96; see `lifecycle/render-document-hash`), so neither the chunk-1
  marker nor the final payload's `:rf/render-hash` appears.
  `:head-hash` is the SEPARATE client-
  reconstructible head-model hash (`lifecycle/render-head-hash` over
  `resolve-head`'s `:head-model`) — nil when the head is not client-
  reconstructible (explicit `:head` string / degraded resolution). The
  resolved `head-bag` itself is NOT carried past this fn — `:head-html` /
  `:html-attrs` / `:body-attrs` / `:head-hash` are the only pieces the
  daemon writer needs (`:head-model` was consumed here, for the hash,
  and does not itself ride the wire).

  When at least one continuation drains, `:doc-hash` no longer
  drives the FINAL-payload `:rf/render-hash`. The final payload ships the
  live POST-drain `app-db`, so its hash must describe the POST-drain render
  tree (the one a streaming hydrate re-renders + verifies against). The
  writer re-resolves the root view AFTER every continuation drains and
  recomputes that post-drain hash via `lifecycle/render-document-hash`
  (body-only). When no continuation mutates a root-read key the two hashes
  coincide; only a render-time continuation mutation a root subtree reads
  makes the streamed-shell marker (pre-drain) and the final-payload hash
  (post-drain) describe distinct moments — which they genuinely are.

  When there are zero continuations (no `:rf/suspense-boundary`
  in the tree — the common case), nothing runs between the shell render and
  the final-payload build that could mutate app-db, so the writer does NOT
  re-resolve the root view a second time — it reuses `:doc-hash` verbatim.
  This keeps a fn-form `:root-view` invoked EXACTLY ONCE per request
  for the common streaming request; only a request with at least one
  continuation re-resolves it.
  `:head-hash` needs no post-drain recompute in either case: the head is
  request-thread-resolved and drain-invariant in v1 (it derives from the
  route / explicit `:head` opt, never from continuation-mutated app-db), so
  the SAME pre-drain `:head-hash` is reused for both the streamed shell
  marker and the final payload.

  Throws propagate to the caller (the handler's outer try/catch routes
  them through the projector to a fail-closed non-200. The
  recovered-to-nil sub case does NOT throw here — its buffered fail-
  closed 5xx is picked up by the handler's post-shell
  `flush-response-result!` re-read, which then diverts to the non-streamed
  projected-error arm (rf2-oytx7j)."
  [frame-id {:keys [root-view] :as opts}]
  ;; Blocking route resources settle before the shell; suspense continuation
  ;; deferral is a separate axis. Absent resource hooks make this a no-op.
  (ssr/drain-blocking-resources! frame-id opts)
  ;; Pin the frame on the request thread for registered view and head lookups.
  (rf/with-frame frame-id
    (let [hiccup     (lifecycle/resolve-root-view root-view)
          head-bag   (if (:head opts)
                       {:head-html (:head opts) :html-attrs nil :body-attrs nil}
                       (lifecycle/resolve-head frame-id))
          {:keys [head-html html-attrs body-attrs]} head-bag
          ;; Compute the body-only shell hash once on the request thread.
          doc-hash   (lifecycle/render-document-hash hiccup)
          ;; The SEPARATE client-reconstructible head-model hash — nil when
          ;; the head is not client-reconstructible (explicit `:head`
          ;; string / degraded resolution).
          head-hash  (lifecycle/render-head-hash (:head-model head-bag))
          {:keys [shell-html continuations]} (streaming/render-shell hiccup)]
      {:hiccup        hiccup
       :head-html     head-html
       :html-attrs    html-attrs
       :body-attrs    body-attrs
        ;; Head state is drain-invariant, so the writer reuses this pre-drain
        ;; hash while only the body may be re-hashed after continuations.
       :head-hash     head-hash
       :doc-hash      doc-hash
       :shell-html    shell-html
       :continuations continuations})))

;; ---- chunk writer (daemon thread, after response materialisation) --------
;;
;; The Ring response body is a `PipedInputStream` paired with a writer
;; thread that pushes chunks onto a `PipedOutputStream`. The writer
;; thread receives the already-rendered shell and continuations from the
;; request thread. It holds the
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
;; already selected. The detached writer cannot replace the Ring response map,
;; so truncate-and-close is the only safe outcome. The server logs the trace via
;; `:rf.error/ssr-streaming-writer-failed`, which carries a `:phase` tag
;; (`:shell-prefix` / `:shell-html`
;; / `:continuation-template` / `:continuation-delta` / `:final-payload`
;; / `:suffix`), a `:boundary-id` tag when the failure is inside a
;; continuation drain, and a coarse `:committed? true` — so ops can tell
;; a broken client pipe from a bad final payload from a specific
;; boundary drain rather than seeing one undifferentiated event. The phases are:
;;
;;   :shell-prefix          — chunk 1a, the <!DOCTYPE>…<div id=app> open
;;   :shell-html            — chunk 1b, the shell body with <template>s
;;   :continuation-template — a resolved/failed boundary <template> chunk
;;   :continuation-delta    — a boundary's hydration-delta <script> chunk
;;   :final-payload         — the canonical __rf_payload <script>
;;   :suffix                — the </div>…</body></html> close
;;
;; Every writer phase is adapter-committed: the Ring response map and status
;; are fixed even if the host has not yet emitted bytes. Traces carry
;; `:committed? true` explicitly rather than asking consumers to infer this.

(defn- run-streaming-writer!
  "Run the streaming writer on the calling (daemon) thread. The caller
  supplies an open `OutputStream` (the pipe sink) and the pre-rendered
  shell pieces from `render-streaming-shell!` (the shell is
  resolved/rendered on the request thread before response materialisation, so
  shell failures fail closed there; this thread only drains the chunk
  stream). On any throw, the catch arm emits a
  `:rf.error/ssr-streaming-writer-failed` trace and closes the stream
  cleanly so the Ring server can EOF the response.

  The trace carries writer-phase context: a `:phase`
  tag naming which chunk was in flight when the write threw (one of
  `:shell-prefix` / `:shell-html` / `:continuation-template` /
  `:continuation-delta` / `:final-payload` / `:suffix`), a `:boundary-id`
  tag when the failure happened inside a continuation drain, and a coarse
  `:committed? true` (the detached writer cannot replace the selected response).
  That
  shape lets ops distinguish a broken client pipe from a bad final payload
  from a specific boundary drain in JFR / log streams instead of seeing
  one undifferentiated writer-failed event."
  [^OutputStream out frame-id rendered opts]
  ;; Phase tracker — a 2-tuple `[phase boundary-id]`. `boundary-id` is nil
  ;; outside a continuation drain. Updated as the writer advances so the
  ;; catch arm can name the in-flight phase.
  (let [phase (volatile! [:shell-prefix nil])]
   (try
    (let [{:keys [emit-hash? version schema-digest payload root-view client-frame-id]} opts
          ;; Body and head hashes were computed before the drain. The body may
          ;; be recomputed for final payload state; the head is drain-invariant.
          {:keys [head-html html-attrs body-attrs
                  doc-hash head-hash shell-html continuations]} rendered
          shell-opts (merge opts
                            {:html-attrs  html-attrs
                             :body-attrs  body-attrs
                             ;; Mark the body tree actually streamed in chunk 1.
                             ;; nil `doc-hash` (an unresolved root form —
                             ;; rf2-q1b96) omits the marker as well as the
                             ;; payload key; `default-streaming-prefix` stamps
                             ;; only from `:render-hash` and never recomputes,
                             ;; so no `:emit-hash?` gate is needed here.
                             :render-hash (when emit-hash? doc-hash)
                             ;; Wire hash markers share the emit toggle; the
                             ;; payload's head hash stays unconditional (the
                             ;; head model is client-reconstructible on every
                             ;; tier, so it is never degenerate).
                             :head-hash   (when emit-hash? head-hash)})]
      ;; Chunk 1 — shell prefix + shell HTML (with template fallbacks) +
      ;; the app-root close. Stamp the phase before each write
      ;; so the catch arm names the in-flight chunk. `:shell-prefix` is
      ;; already the initial volatile value, set explicitly here for
      ;; symmetry/readability.
      (vreset! phase [:shell-prefix nil])
      (write-chunk! out (default-streaming-prefix head-html shell-opts))
      ;; Close the app root in chunk 1. Continuation templates, protocol
      ;; scripts, payload, and bootstrap must remain outside the hydrated root.
      (vreset! phase [:shell-html nil])
      (write-chunk! out (str shell-html "</div>"))
      ;; Chunks 2..N+1 — one per continuation, FIFO over registration.
      ;;
      ;; Drain a growable FIFO: a nested
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
                ;; Pin `*current-frame*` on this daemon thread; bindings do not cross
                ;; the thread boundary), so `render-continuation`'s frame lookups
                ;; + registered-view resolution operate on the request frame.
                (rf/with-frame frame-id
                  (streaming/render-continuation frame-id entry))
                tmpl-fn (if failed?
                          streaming/failed-template
                          streaming/resolved-template)]
            ;; A continuation write names
            ;; the boundary :id so ops correlate the failure to a specific
            ;; deferred subtree (not just "some continuation broke").
            (vreset! phase [:continuation-template id])
            (write-chunk! out (tmpl-fn id html))
            ;; Emit the per-boundary hydration-delta script
            ;; ONLY when the delta carries something to hydrate. A
            ;; continuation that merely READS app-db (the common case —
            ;; deferred subtrees rarely mutate state) yields a delta of
            ;; `{}` from `streaming/subtree-delta`, which is `some?` but
            ;; empty; `(seq delta)` is the correct guard (falsy for both
            ;; `{}` and `nil`) so an unchanged boundary emits NO delta
            ;; script rather than an inert `<script …>{}</script>` chunk
            ;; the client would parse and discard. `failed?` continuations
            ;; carry `:delta nil` (also falsy here), so the `not failed?`
            ;; arm remains for intent clarity. A streaming hydration delta is
            ;; browser-delivered state, so it obeys the same allowlist-first,
            ;; frame-project-second boundary as the final payload: an
            ;; off-allowlist changed key is DROPPED by the handler `:payload`
            ;; policy and a frame-sensitive child inside an allowed changed key
            ;; redacts under `:rf.egress/ssr-hydration`. `project-delta` runs
            ;; the raw delta through `payload-policy/apply-policy` +
            ;; `project-app-db-egress` under the request frame on THIS daemon
            ;; thread (inside the `with-frame` scope — so
            ;; `project-app-db-egress`'s frame walk resolves the frame's elision
            ;; registry). A delta that projects to empty (every changed key
            ;; off-allowlist, or all redacted away to an empty map) emits NO
            ;; script.
            (let [projected (rf/with-frame frame-id
                              (streaming/project-delta delta frame-id
                                                       {:payload payload}))]
              (when (and (not failed?) (map? projected) (seq projected))
                (vreset! phase [:continuation-delta id])
                (write-chunk! out (streaming/hydrate-delta-script id (pr-str projected)))))
            ;; Pop the drained entry, append any nested continuations at
            ;; the tail (FIFO), continue until empty.
            (recur (into (pop queue) continuations)))))
      ;; Chunk N+2 — final canonical __rf_payload.
      ;;
      ;; The final payload reads post-drain app-db. If any continuation ran,
      ;; re-resolve the root under this daemon thread's frame scope so state and
      ;; body hash describe the same moment. With no continuations, reuse the
      ;; shell hash and preserve exactly-once root resolution. The shell marker
      ;; remains pre-drain because it describes chunk 1; the head hash is
      ;; drain-invariant. Server `:root-view` and client `:render-tree-fn` must
      ;; use symmetric EXPANDED forms — `(fn [] ((rf/view :app/root)))` against
      ;; `#((rf/view :app/root))`. "Symmetric unexpanded" is no longer a second
      ;; way to agree: both sides would hash the constant `[#fn[]]`, a check
      ;; that can never fail, so an unexpanded server root now carries no hash
      ;; at all (rf2-q1b96).
      ;;
      ;; Set phase before both payload construction and its write.
      (vreset! phase [:final-payload nil])
      ;; Dynamic frame bindings do not cross the request/writer thread boundary.
      (let [final-payload
            (rf/with-frame frame-id
              ;; Only continuations can mutate state between shell and payload.
              (let [post-drain-hash
                    (if (and root-view (seq continuations))
                      (lifecycle/render-document-hash
                        (lifecycle/resolve-root-view root-view))
                      doc-hash)]
                (streaming/build-final-payload
                  frame-id post-drain-hash
                  {:version         version
                   :schema-digest   schema-digest
                   :payload         payload
                   ;; Head state is drain-invariant.
                   :head-hash       head-hash
                   ;; rf2-lm2yzy — stable WIRE :rf/frame-id (nil ⇒ omit).
                   :client-frame-id client-frame-id})))]
        ;; Shared id-pinned, script-body-escaped payload element.
        (write-chunk! out (shell/payload-script-tag (pr-str final-payload))))
      ;; Chunk N+3 — shell suffix close.
      (vreset! phase [:suffix nil])
      (write-chunk! out (default-streaming-suffix opts)))
    (catch Throwable t
      ;; Stamp the in-flight phase and, inside a
      ;; continuation drain) the boundary id + a coarse `:committed?` so
      ;; the writer-failed trace names WHERE the post-commit stream broke.
      ;; `:recovery` is hoisted to top-level by `build-event` (Spec 009
      ;; §Error event shape); `:phase` / `:boundary-id` / `:committed?`
      ;; ride in `:tags`. `:boundary-id` is omitted entirely (not nil)
      ;; outside a continuation phase so the tag's presence is itself the
      ;; "failed inside a boundary drain" signal.
      (let [[ph boundary-id] @phase
            tags (cond-> {:frame      frame-id
                          :exception  (.getMessage t)
                          :ex-class   (.getName (class t))
                          :phase      ph
                          :committed? true
                          :recovery   :truncate-and-close}
                   (some? boundary-id) (assoc :boundary-id boundary-id))]
        (trace/emit-error! :rf.error/ssr-streaming-writer-failed tags)
        ;; Post-commit writer errors are always-on, non-projecting telemetry;
        ;; the status is already on the wire and cannot change.
        (lifecycle/emit-always-on-error!
          (assoc tags :error :rf.error/ssr-streaming-writer-failed
                      :time  (interop/now-ms)))))
    (finally
      (try (.close out) (catch Throwable _ nil))))))

;; ---- public surface ------------------------------------------------------

(defn- redirect-response!
  "Short-circuit a streaming request to a bodiless Location response and
  destroy the per-request frame inline. Shared by both redirect branches in
  `stream-handler`: the early `:initial-events`-drain redirect and the
  post-shell `resp2` re-read redirect.

  A redirect short-circuits the stream — no chunked body, so the writer thread
  (whose `finally` normally tears the frame down) is never spawned on this
  branch. Without the inline `destroy-frame-quietly!` every redirected
  streaming request would leak the frame + its three side-channel slots
  (request / response / pending-error-trace) — a per-request leak on auth-gated
  SSR routes where redirects are common (Spec 011 §Per-request frame teardown
  contract). The 2-arg `ssr-response->ring-response` (no `content-type`)
  matches the non-streaming redirect path — a bodiless redirect has no
  meaningful Content-Type to default; `ssr-response->ring-response` ignores the
  body arg on its `:redirect` branch (pipeline.clj).

  `frame` is the per-request frame VALUE (incarnation-EXACT teardown authority,
  rf2-moftbs); `frame-id` remains the keyword for the failure trace."
  [resp frame-id frame]
  (try
    (pipeline/ssr-response->ring-response resp nil)
    (finally
      (lifecycle/destroy-frame-quietly! frame frame-id))))

(defn- stream-projected-error!
  "Materialise a projected 5xx as a plain-String error response on the
  REQUEST thread — NO pipe, NO writer thread — and destroy the per-request
  frame inline (the writer thread whose `finally` normally tears the frame
  down is never spawned on this branch). Mirrors the non-streaming
  projected-error arm: the projected status + safe accumulated headers/cookies,
  an `:error-view` (or the locked default template) body, and NO shell /
  continuations / `__rf_payload` / app-db. Shared by the drain-time 5xx branch
  and the post-shell recovered-to-nil 5xx branch of `stream-handler`. Per
  Spec 011 §Drain-time error classification + §Streaming pre-commit rule
  (rf2-oytx7j).

  `frame` is the per-request frame VALUE (incarnation-EXACT teardown authority,
  rf2-moftbs); `frame-id` remains the keyword the address-directed materialise +
  the failure trace use."
  [frame-id resp public-error opts frame]
  (try
    (pipeline/materialise-projected-error frame-id resp public-error opts)
    (finally
      (lifecycle/destroy-frame-quietly! frame frame-id))))

(defn- render-shell-or-projected-error
  "Render the streaming shell on THIS (request) thread, BEFORE the chunked
  response is materialised. The shell render is the
  request's structural foundation; its failure modes MUST fail closed to a
  non-200 (Spec 011 §744/§748/§954), NOT a silent 200 / truncated chunked body
  from a detached daemon thread that can no longer stamp the status.

  Returns the `render-streaming-shell!` result on a clean render, or — when the
  shell render THROWS (a root-view / shell-walk throw) — a `reduced` wrapping
  the PROJECTED non-200 error response. The throw is routed through
  `project-render-throw->ring-response` (→ `:rf.error/ssr-render-failed`,
  projector, non-200 projected error page) — the same wire-body contract as the
  non-streaming `build-full-response` catch arm — NOT the `:on-error` transport
  net; the frame is torn down inline (no writer was spawned). The caller derefs
  a `reduced?` result and returns it directly; the outer handler try/catch
  remains the net for the OTHER throws (head materialise, redirect materialise)
  → `:on-error`.

  A production reactive-sub throw during the shell render does NOT throw here —
  it recovers to nil but buffers a fail-closed 500 on the always-on error-emit
  substrate; the caller's post-shell `ssr/flush-response-result!` re-read
  drains that buffer, and a projected 5xx then diverts to the non-streamed
  projected-error arm (`stream-projected-error!`) — no writer thread, no
  partial-state shell — rather than streaming the degraded shell under a 500
  (rf2-oytx7j).

  `frame` is the per-request frame VALUE (incarnation-EXACT teardown authority,
  rf2-moftbs); `frame-id` remains the keyword the address-directed render +
  projector + failure trace use."
  [frame-id opts frame]
  (try
    (render-streaming-shell! frame-id opts)
    (catch Throwable t
      (let [err-resp (pipeline/project-render-throw->ring-response frame-id t opts)]
        (lifecycle/destroy-frame-quietly! frame frame-id)
        (reduced err-resp)))))

(defn- stream-rendered-response
  "Materialise the streaming response head from the post-shell response
  accumulator `resp2`, wire the pipe, and spawn the daemon writer — the
  non-redirect leaf of `stream-handler` once the shell is known-renderable.

  Materialise the head (status / headers / cookies) from `resp2` before
  constructing the pipe or spawning the writer. Cookie / header
  serialisation CAN throw at materialise time on a value that escaped the fx
  boundary's partial validation — e.g. a `:max-age` carrying CR/LF, which
  `cookie->set-cookie-header` rejects but the runtime `validate-cookie!` does
  not. If the writer were spawned first, that throw would orphan the pipe (the
  daemon writer pumps the full body into a reader-less pipe, blocks once the
  16 KiB buffer fills, and leaks one live thread per request). Building
  `resp-map` first lets a head-materialisation failure short-circuit to the
  handler's outer catch BEFORE any thread or pipe exists → on-error, no detached
  writer, no orphaned pipe.

  Any `Content-Length` header (case-insensitively) is
  stripped from the materialised head before wiring the chunk-writer body. App
  / server init can `:rf.server/set-header` (or `append-header`) a
  `Content-Length` during the `:initial-events` drain — a fixed length that is
  meaningless (and actively harmful) once the body becomes a chunk-producing
  PipedInputStream of unknown final size. Left in place, a Ring server may
  honour that length instead of chunked transfer framing → truncated HTML /
  clients blocked on the wrong byte count / lost chunks, violating Spec 011's
  chunked-transfer streaming contract. The shared materialiser strips it, so
  the head is already
  Content-Length-free when it returns here.

  The writer runs on a daemon thread: one blocked on `.write` to the
  bounded 16 KiB pipe of a slow-loris client must NOT keep the JVM alive at
  shutdown. Its `finally` tears the frame down (off the response-close path,
  via the slower destroy).

  `frame` is the per-request frame VALUE (incarnation-EXACT teardown authority,
  rf2-moftbs); `frame-id` remains the keyword the address-directed materialise,
  writer, thread name, and failure trace use."
  [frame-id rendered resp2 content-type opts frame]
  (let [;; No body default-stamp here (we pass our own InputStream); `:body` is
        ;; assoc'd after the writer is wired below. Content-Length is already
        ;; stripped by the shared materialiser.
        resp-map (pipeline/ssr-response->ring-response resp2 "" content-type)
        ;; 16 KiB pipe buffer — large enough to absorb the shell chunk in one
        ;; write so the writer rarely blocks on a slow consumer, small enough
        ;; that one stuck client doesn't pin a non-trivial chunk of heap.
        pipe-in  (PipedInputStream. (* 16 1024))
        pipe-out (PipedOutputStream. pipe-in)]
    (doto
      (Thread.
        ^Runnable
        (fn writer-thread []
          (try
            (run-streaming-writer! pipe-out frame-id rendered opts)
            (finally
              ;; The writer's own finally closes the pipe; the frame teardown
              ;; happens here so it does NOT block the response close on the
              ;; slower destroy path. Destroy the VALUE (incarnation-EXACT,
              ;; rf2-moftbs); the keyword names the frame on any failure trace.
              (lifecycle/destroy-frame-quietly! frame frame-id))))
        ^String (str "rf2-ssr-streaming-" (name frame-id)))
      (.setDaemon true)
      (.start))
    (assoc resp-map :body pipe-in)))

(defn stream-handler
  "Return a synchronous Ring handler that streams SSR responses via
  an unknown-length InputStream body. The Ring host chooses protocol framing.
  Per Spec 011 §Streaming SSR.

  Opts mirror `re-frame.ssr.ring/ssr-handler` — same `:initial-events` /
  `:root-view` / `:payload` / `:on-error` /
  `:error-view` / `:emit-hash?` / `:version` / `:schema-digest` /
  `:content-type` / `:fx-overrides` / `:ssr` (the last two are threaded
  through the shared `pipeline/setup-request-frame!` into the per-request
  `(rf/make-frame …)`, exactly as `ssr-handler` does) plus the four trusted
  shell-hook opts (`:head` / `:body-end` / `:script-src` /
  `:app-element-id`, honoured by `default-streaming-prefix` /
  `default-streaming-suffix`). `:initial-events`
  accepts BOTH forms `ssr-handler` does — an `:initial-events` vector OR a
  `(fn [request] -> initial-events-vector)` deriving the setup vector from
  the Ring request; both flow through the shared setup pipeline. One exception:

    `:html-shell` is NOT supported by the streaming path and is REJECTED
    at handler-construction time (`:rf.error/ssr-streaming-unsupported-opt`).
    The non-streaming handler builds its response from a ONE-PIECE
    `:html-shell` fn `(body-html payload-edn opts) → string`; the streaming
    handler flushes the envelope as a SPLIT prefix/suffix straddling the
    continuation chunks (the wire shape below), so a one-piece shell
    callback can never run after streaming starts. The handler rejects it at
    boot instead of discarding security or document configuration. Customize
    the streaming envelope through the four
    trusted shell-hook opts, or use a non-streaming `ssr-handler` when a
    bespoke one-piece shell is required.

  Plus implicit streaming semantics on every request — non-streaming
  responses (no `:rf/suspense-boundary` in the tree) still ride the
  streaming path but with zero continuations, so the wire shape collapses
  to shell-prefix + shell-html + final-payload + shell-suffix.

  The returned handler:
    - sets up the per-request frame (request slot, frame registration,
      synchronous :initial-events drain),
    - reads the response accumulator; if :redirect is set, short-
      circuits to a non-streamed Location response (Spec 011 §Redirect
      precedence) AND destroys the per-request frame inline (the writer
      thread is never spawned on this branch),
    - otherwise renders the shell on the request thread before materialising
      the response head. A root-view / shell-walk throw escalates to
      `:rf.error/ssr-render-failed` via the projector, and a production
      reactive-sub throw during the shell render buffers a fail-closed 5xx
      the post-shell `flush-response-result!` re-read picks up — BOTH fail
      closed to a non-200 projected error page (`:error-view` or the locked
      default template) on the request thread, with NO pipe or writer thread
      spawned and NO partial-state shell / hydration payload shipped
      (rf2-oytx7j). The streaming response is selected only once the shell is
      known-renderable (a clean render AND no projected 5xx),
    - then materialises the response head (status / headers / cookies)
      — so a header/cookie serialisation throw on a value that escaped
      the fx boundary's partial validation short-circuits to `:on-error`
      with no pipe or thread to orphan — and only then
      spawns a streaming writer on a daemon thread that flushes the
      PRE-RENDERED shell → continuations → final payload → close,
      destroying the frame in that thread's finally so the per-frame
      side-channels clear (Spec 011 §Per-request frame teardown contract)
      without blocking the response close. The writer thread's only
      remaining failure surface is continuation drains (inline-fallback,
      Spec 011 §942-954) and the post-first-chunk final-payload / suffix
      writes — never the shell, whose status was fixed on the request thread.

  The response body is a `PipedInputStream` Ring accepts directly; the
  pipe's writer side runs on a daemon thread so Jetty/http-kit/Aleph
  can begin sending bytes immediately while the writer continues to
  pump chunks. The pipe's sink-side close (in the writer's `finally`)
  signals EOF to the server.

  Concurrency model (Spec 011 §Streaming SSR — Writer concurrency model): one
  raw daemon `java.lang.Thread` per in-flight streamed
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
  ;; streaming specifically, a missing :initial-events would otherwise fail
  ;; per-request (500) and a missing :root-view would silently truncate
  ;; the chunked response from the writer thread; the trusted-shell opts
  ;; cross the same trust boundary as the non-streaming
  ;; `default-html-shell` — `:head` / `:body-end` injected RAW (content
  ;; positions), `:script-src` / `:app-element-id` escape-attr'd
  ;; (attribute-value positions) by the streaming
  ;; prefix/suffix. See `validate-construction-opts!` for details.
  (lifecycle/validate-construction-opts! raw-opts)
  ;; Reject opts the streaming path cannot honour (currently
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
  ;; way via the shared `lifecycle/resolve-on-error`: caller's
  ;; `:on-error` wins, else the locked no-detail default.
  ;; `:content-type` carries no default here (mirrors
  ;; `ssr-ring/handler-defaults`): the opt is a genuine override that
  ;; force-replaces the streamed head's Content-Type when supplied. A
  ;; non-nil default would clobber an app's own `:rf.server/set-header
  ;; "content-type"`; an absent (nil) opt leaves the runtime's
  ;; default-seeded `text/html; charset=utf-8` — or the app's Content-Type
  ;; — in control (the on-the-wire default is unchanged).
  (let [opts        (-> (merge {:emit-hash? true} raw-opts)
                        (assoc :on-error (lifecycle/resolve-on-error raw-opts)))
        {:keys [on-error content-type]} opts]
    (fn ring-handler [request]
      (let [{:keys [frame-id frame short-circuit]}
            (pipeline/setup-request-frame! opts request)]
        (if short-circuit
          short-circuit
          (try
            ;; Request-thread flow: read the response
            ;; accumulator; on a `:redirect` short-circuit BEFORE any chunked
            ;; head / writer; otherwise render the shell on THIS thread (failing
            ;; closed to a projected non-200 on a render throw), re-read the
            ;; accumulator for a fail-closed status a recovered-to-nil sub
            ;; buffered during the render, and stream — fixing
            ;; the chunked head ONLY once the shell is known-renderable. The
            ;; helpers (`render-shell-or-projected-error`, `stream-rendered-
            ;; response`, `redirect-response!`) carry the per-step rationale.
            (let [{:keys [response public-error]} (ssr/flush-response-result! frame-id)]
              (cond
                ;; Redirect precedence FIRST (Spec 011 §Redirect precedence).
                (some? (:redirect response))
                (redirect-response! response frame-id frame)

                ;; A drain-time projected 5xx — NO shell, NO writer thread; a
                ;; plain-String projected-error body on the request thread
                ;; (Spec 011 §Streaming pre-commit rule, rf2-oytx7j).
                (pipeline/projected-5xx? public-error)
                (stream-projected-error! frame-id response public-error opts frame)

                :else
                (let [rendered (render-shell-or-projected-error frame-id opts frame)]
                  (if (reduced? rendered)
                    ;; Shell render threw — return the projected error page
                    ;; (frame already torn down inline).
                    @rendered
                    ;; Shell rendered cleanly. Re-read the accumulator AND the
                    ;; projected public-error to surface any fail-closed status
                    ;; before materialising the head.
                    (let [{resp2 :response err2 :public-error}
                          (ssr/flush-response-result! frame-id)]
                      (cond
                        ;; A redirect here is defense in depth: in v1 it
                        ;; cannot surface at the post-shell re-read (only the
                        ;; `:initial-events`-drain `:rf.server/redirect` sets
                        ;; it, caught by the early branch; the error projector
                        ;; stamps `:status` only, never `:redirect`). The
                        ;; branch aligns the streaming path with the
                        ;; non-streaming handler's redirect-ignores-body parity
                        ;; for a future render-phase fx that learns to redirect.
                        (some? (:redirect resp2))
                        (redirect-response! resp2 frame-id frame)

                        ;; A recovered-to-nil sub during the shell render
                        ;; buffered a fail-closed 5xx — take the NON-STREAMED
                        ;; error arm (no writer thread, plain-String body).
                        ;; rf2-oytx7j SUPERSEDES the old stream-the-degraded-
                        ;; shell-under-500 behaviour: a 5xx before the chunked
                        ;; head commits never ships a partial-state shell.
                        (pipeline/projected-5xx? err2)
                        (stream-projected-error! frame-id resp2 err2 opts frame)

                        :else
                        (stream-rendered-response
                          frame-id rendered resp2 content-type opts frame)))))))
            (catch Throwable t
              ;; A get-response, redirect-materialisation, or head-materialisation
              ;; throw raised before the
              ;; writer thread is spawned — none happen under the
              ;; streaming writer's own catch arm. Destroy the frame
              ;; inline and respond per on-error. `safe-on-error` contains a
              ;; throwing caller `:on-error`:
              ;; it falls back to the locked `default-on-error` rather
              ;; than escaping as a raw container 500 with leaked
              ;; internals.
              ;; Destroy the VALUE (incarnation-EXACT, rf2-moftbs); the keyword
              ;; `frame-id` names the frame on any failure trace.
              (try (lifecycle/destroy-frame-quietly! frame frame-id)
                   (catch Throwable _ nil))
              (lifecycle/safe-on-error on-error request t))))))))
