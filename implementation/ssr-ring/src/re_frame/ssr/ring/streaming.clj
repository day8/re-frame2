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
  CLJS-only, host-opt-in runtime; re-exported as `ssr/streaming-install!`,
  rf2-3hhv5): it swaps each `<template>` fallback for its resolved subtree
  in-place and merges the per-subtree `data-rf2-suspense-hydrate` delta as
  chunks arrive, reconciling against the final `__rf_payload`
  (`:replace-frame-state`) when it lands."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
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
  `shell/html-attr-bag` so the two envelopes can't diverge.

  rf2-9fw2de: when `:render-hash` is supplied (the handler passes it iff
  `:emit-hash?` is true), `data-rf-render-hash` is stamped on the streaming
  root element — the `#app` div, the first DOM root of the streamed
  document — mirroring the non-streaming handler's root-element marker
  (Spec 011 §362-363). The hash value is the full-document structural hash
  shared with the final payload's `:rf/render-hash`, so toggling
  `:emit-hash?` has an observable effect on the wire and a host/tool can
  verify the streamed root against the payload."
  [head-html {:keys [html-attrs body-attrs lang app-element-id render-hash]
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
         ;; position). rf2-9fw2de — the `data-rf-render-hash` value is an
         ;; 8-char hex (canonical-EDN FNV) so it needs no escaping, but it
         ;; only emits when supplied (`:emit-hash?` true).
         "<div id=\"" (html/escape-attr app-element-id) "\""
         (when render-hash
           (str " data-rf-render-hash=\"" render-hash "\""))
         ">")))

(defn default-streaming-suffix
  "The shell suffix flushed after the final-payload chunk. Emits the
  bootstrap script tag (if any), the body-end raw HTML, and the document
  close.

  rf2-z9dduj — the app root (`</div>`) is NO LONGER closed here. It is
  closed at the END of the shell chunk (immediately after the shell HTML,
  see `run-streaming-writer!`), so the resolved templates, hydration-delta
  scripts, and the final `__rf_payload` script all stream OUTSIDE `#app`
  per Spec 011 §Chunk-ordering contract (chunk 1 is
  `…<div id=\"app\"><shell-html/></div>`). The suffix is therefore purely
  the bootstrap `<script>` + the raw `:body-end` + the document close —
  all of which already belonged outside `#app`, mirroring the non-streaming
  `default-html-shell` (which emits the payload script + bootstrap + body-end
  after `</div>`)."
  [{:keys [body-end script-src]
    :or   {script-src "/main.js"}}]
  (str (when script-src
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
     :doc-hash      \"…\"                         ;; full-document structural hash
     :shell-html    \"…\"                        ;; chunk 1 body
     :continuations [{:id … :subtree …} …]}     ;; drain queue (FIFO)

  rf2-9fw2de: `:doc-hash` is the canonical FULL-document structural hash
  (body tree + resolved head fragment + html/body attr bags) computed via
  `lifecycle/render-document-hash`. It describes the PRE-drain shell — the
  exact tree streamed in chunk 1 — and drives the streaming root-element
  `data-rf-render-hash` marker (when `:emit-hash?` is true), folding the head
  into the unified hash channel per Spec 011 §624-626/§648-650.

  rf2-1kqvbx: `:doc-hash` no longer drives the FINAL-payload
  `:rf/render-hash`. The final payload ships the live POST-drain `app-db`,
  so its hash must describe the POST-drain render tree (the one a streaming
  hydrate re-renders + verifies against). The writer re-resolves the root
  view AFTER every continuation drains and recomputes that post-drain hash
  via `lifecycle/render-document-hash` over the carried `:head-bag` (the head
  is request-thread-resolved and drain-invariant in v1). When no continuation
  mutates a root-read key the two hashes coincide; only a render-time
  continuation mutation a root subtree reads makes the streamed-shell marker
  (pre-drain) and the final-payload hash (post-drain) describe distinct
  moments — which they genuinely are.

  Throws propagate to the caller (the handler's outer try/catch routes
  them through the projector → fail-closed non-200, rf2-r06pc). The
  recovered-to-nil sub case does NOT throw here — its buffered fail-
  closed status is picked up by the handler's post-shell `get-response`
  re-read."
  [frame-id {:keys [root-view] :as opts}]
  ;; rf2-er7qx2 — SSR blocking-resource drain (streaming counterpart of the
  ;; non-streaming `build-full-response*` drain). AFTER the `:initial-events` drain
  ;; resolved the route + enqueued the route's blocking resource ensures and
  ;; BEFORE the shell render walk, drain the current nav-token's blocking
  ;; resources until they settle or the render deadline fires; a never-settling
  ;; blocking resource is settled to a first-load failure in the frame's
  ;; runtime-db so the shell render sees a settled `:error`, never a hung
  ;; `:loading` / skeleton (Spec 016 §SSR and hydration steps 3-4). A no-op
  ;; when the resources artefact is absent. The streaming SUSPENSE-boundary
  ;; deferral is a separate axis — blocking ROUTE resources still settle before
  ;; the shell, exactly as in the non-streaming path.
  (ssr/drain-blocking-resources! frame-id opts)
  ;; rf2-bzw8gd / rf2-tqjc7h: pin `*current-frame*` to the request frame for the
  ;; shell render walk (`rf/with-frame`), the streaming counterpart of the
  ;; non-streaming `build-full-response*` binding. Covers the registered-view +
  ;; head/route lookups. Runs on the REQUEST thread.
  (rf/with-frame frame-id
    (let [hiccup     (lifecycle/resolve-root-view root-view)
          head-bag   (if (:head opts)
                       {:head-html (:head opts) :html-attrs nil :body-attrs nil}
                       (lifecycle/resolve-head frame-id))
          {:keys [head-html html-attrs body-attrs]} head-bag
          ;; rf2-9fw2de: the streaming structural hash folds the head
          ;; fragment in, identically to the non-streaming handler — Spec
          ;; 011 §624-626/§648-650 lock head + body onto the unified
          ;; `:rf/render-hash` channel. Computed once here on the request
          ;; thread; drives the final-payload `:rf/render-hash` AND (when
          ;; `:emit-hash?`) the streaming root-element marker.
          doc-hash   (lifecycle/render-document-hash hiccup head-bag)
          {:keys [shell-html continuations]} (streaming/render-shell hiccup)]
      {:hiccup        hiccup
       :head-html     head-html
       :html-attrs    html-attrs
       :body-attrs    body-attrs
       ;; rf2-1kqvbx — carry the resolved `head-bag` to the daemon writer so
       ;; it can recompute the POST-drain final-payload `:rf/render-hash` over
       ;; the re-resolved root tree using the SAME head channel the pre-drain
       ;; `doc-hash` used. The head is resolved once on the request thread and
       ;; does not change across continuation drains (it derives from the route
       ;; / explicit `:head` opt, not from continuation-mutated app-db in v1),
       ;; so the post-drain hash folds in the identical head fragment + attr
       ;; bags — only the body tree re-renders against the drained state.
       :head-bag      head-bag
       :doc-hash      doc-hash
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
;; `:rf.error/ssr-streaming-writer-failed`, which carries WRITER-PHASE
;; context (rf2-l1qgjw): a `:phase` tag (`:shell-prefix` / `:shell-html`
;; / `:continuation-template` / `:continuation-delta` / `:final-payload`
;; / `:suffix`), a `:boundary-id` tag when the failure is inside a
;; continuation drain, and a coarse `:committed? true` — so ops can tell
;; a broken client pipe from a bad final payload from a specific
;; boundary drain rather than seeing one undifferentiated event.

;; rf2-l1qgjw issue 2 — writer-failure phase context. The writer drains
;; several distinct chunk phases AFTER the response head has committed
;; (Spec 011 §Failure semantics — once the first chunk lands the status
;; is on the wire and can no longer change). When a write throws, the
;; bare `:frame`/`:exception`/`:ex-class`/`:recovery` shape gave ops the
;; SAME trace for a broken client pipe, a bad final payload, a bad suffix
;; hook, or a specific continuation drain — turning incident triage into
;; log archaeology. We track the active phase in a `volatile!` as the
;; writer advances and stamp it (plus the continuation `:boundary-id` and
;; a coarse `:committed?`) onto the trace so the recovery channel names
;; WHERE the stream broke. The phases, in wire order:
;;
;;   :shell-prefix          — chunk 1a, the <!DOCTYPE>…<div id=app> open
;;   :shell-html            — chunk 1b, the shell body with <template>s
;;   :continuation-template — a resolved/failed boundary <template> chunk
;;   :continuation-delta    — a boundary's hydration-delta <script> chunk
;;   :final-payload         — the canonical __rf_payload <script>
;;   :suffix                — the </div>…</body></html> close
;;
;; `:committed?` is true from the moment the FIRST byte is attempted
;; (`:shell-prefix` onward) — i.e. for every phase here, since the writer
;; only runs after the request thread committed the chunked head. It is
;; carried explicitly (rather than inferred from `:phase`) so the contract
;; is self-describing for log/observability consumers and stays correct if
;; a future pre-commit phase is ever added to this thread.

(defn- run-streaming-writer!
  "Run the streaming writer on the calling (daemon) thread. The caller
  supplies an open `OutputStream` (the pipe sink) and the pre-rendered
  shell pieces from `render-streaming-shell!` (rf2-r06pc — the shell is
  resolved/rendered on the request thread BEFORE the head commits, so
  shell failures fail closed there; this thread only drains the chunk
  stream). On any throw, the catch arm emits a
  `:rf.error/ssr-streaming-writer-failed` trace and closes the stream
  cleanly so the Ring server can EOF the response.

  The trace carries WRITER-PHASE context (rf2-l1qgjw issue 2): a `:phase`
  tag naming which chunk was in flight when the write threw (one of
  `:shell-prefix` / `:shell-html` / `:continuation-template` /
  `:continuation-delta` / `:final-payload` / `:suffix`), a `:boundary-id`
  tag when the failure happened inside a continuation drain, and a coarse
  `:committed? true` (every writer phase runs post-head-commit). That
  shape lets ops distinguish a broken client pipe from a bad final payload
  from a specific boundary drain in JFR / log streams instead of seeing
  one undifferentiated writer-failed event."
  [^OutputStream out frame-id rendered opts]
  ;; Phase tracker — a 2-tuple `[phase boundary-id]`. `boundary-id` is nil
  ;; outside a continuation drain. Updated as the writer advances so the
  ;; catch arm can name the in-flight phase.
  (let [phase (volatile! [:shell-prefix nil])]
   (try
    (let [{:keys [emit-hash? version schema-digest payload root-view]} opts
          ;; rf2-9fw2de — the writer no longer recomputes the hash from
          ;; `hiccup`; `doc-hash` (full-document, PRE-drain) was computed once
          ;; on the request thread by `render-streaming-shell!`. `:hiccup`
          ;; stays in the `rendered` map for diagnostics but is not
          ;; destructured here. rf2-1kqvbx — `:head-bag` rides along so the
          ;; post-drain final-payload hash folds in the SAME head channel.
          {:keys [head-html html-attrs body-attrs
                  doc-hash head-bag shell-html continuations]} rendered
          shell-opts (merge opts
                            {:html-attrs  html-attrs
                             :body-attrs  body-attrs
                             ;; rf2-9fw2de / rf2-1kqvbx — honour `:emit-hash?`
                             ;; on the streaming path: stamp the PRE-drain
                             ;; full-document hash onto the root `#app` div when
                             ;; true, no marker when false (a true no-op was the
                             ;; bug). This marks the tree ACTUALLY streamed in
                             ;; chunk 1 (the shell, with fallbacks + pre-drain
                             ;; root reads). The FINAL payload carries a
                             ;; POST-drain hash (recomputed below); the two
                             ;; coincide unless a continuation mutates a
                             ;; root-read key — see the final-payload block.
                             :render-hash (when emit-hash? doc-hash)})]
      ;; Chunk 1 — shell prefix + shell HTML (with template fallbacks) +
      ;; the app-root close. rf2-l1qgjw — stamp the phase before each write
      ;; so the catch arm names the in-flight chunk. `:shell-prefix` is
      ;; already the initial volatile value, set explicitly here for
      ;; symmetry/readability.
      (vreset! phase [:shell-prefix nil])
      (write-chunk! out (default-streaming-prefix head-html shell-opts))
      ;; rf2-z9dduj — CLOSE the app root (`</div>`) at the END of the shell
      ;; chunk, immediately after the shell HTML and BEFORE any resolved
      ;; template / hydration-delta / final `__rf_payload` chunk. Spec 011
      ;; §Chunk-ordering contract pins chunk 1 as
      ;; `…<div id="app"><shell-html/></div>` — the app root is CLOSED in the
      ;; shell chunk, and the resolved chunks + final payload + bootstrap +
      ;; body-end stream OUTSIDE it (chunks 2..N). The non-streaming
      ;; `default-html-shell` already closes `</div>` right after the body and
      ;; emits the payload script outside `#app`; the streaming path used to
      ;; leave the close in `default-streaming-suffix` AFTER the protocol
      ;; chunks, so resolved templates + the `__rf_payload` script landed
      ;; INSIDE the application root — control/protocol nodes in the DOM a
      ;; client renderer/hydrator owns, risking a hydration mismatch /
      ;; replacement (Spec 011 §998-1001). The close is appended to the same
      ;; `:shell-html` write (one flush) so a write throw is still attributed
      ;; to the `:shell-html` phase, and the suffix is now purely the bootstrap
      ;; script + body-end + `</body></html>`.
      (vreset! phase [:shell-html nil])
      (write-chunk! out (str shell-html "</div>"))
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
                ;; rf2-bzw8gd / rf2-tqjc7h: pin `*current-frame*` on this DAEMON
                ;; thread (the request thread's `*current-frame*` does not cross
                ;; the thread boundary), so `render-continuation`'s frame lookups
                ;; + registered-view resolution operate on the request frame.
                (rf/with-frame frame-id
                  (streaming/render-continuation frame-id entry))
                tmpl-fn (if failed?
                          streaming/failed-template
                          streaming/resolved-template)]
            ;; rf2-l1qgjw — a write throw inside a continuation drain names
            ;; the boundary :id so ops correlate the failure to a specific
            ;; deferred subtree (not just "some continuation broke").
            (vreset! phase [:continuation-template id])
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
            ;; rf2-uc3cs4 — a streaming hydration delta is browser-delivered
            ;; hydration state, so it obeys the SAME allowlist-first-then-
            ;; project boundary the final `__rf_payload` does (EP-0015 §14): an
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
      ;; rf2-1kqvbx — the streaming final-payload `:rf/render-hash` must
      ;; describe the POST-drain render state, NOT the pre-drain shell.
      ;; `build-final-payload` reads the LIVE frame `app-db` AFTER every
      ;; continuation has drained, so its `:rf/app-db` is the post-drain
      ;; state; pairing it with the pre-drain `doc-hash` shipped a payload
      ;; whose state and hash described different moments. When a continuation
      ;; mutates app-db and the ROOT tree reads that key, a streaming hydrate
      ;; re-renders the root tree against the payload's post-drain `:rf/app-db`,
      ;; hashes it, and fires a spurious `:rf.ssr/hydration-mismatch` against
      ;; the stale pre-drain hash (Spec 011 §Hydration equivalence rule). So we
      ;; RE-RESOLVE the root view here — on this daemon thread, inside the
      ;; `rf/with-frame` binding, AFTER the drain loop — and recompute
      ;; the full-document hash over the post-drain tree via the SAME
      ;; `lifecycle/render-document-hash` mechanism (folding the carried
      ;; `head-bag`, drain-invariant in v1). The streamed-shell
      ;; `data-rf-render-hash` marker keeps the PRE-drain `doc-hash` (it marks
      ;; the tree actually streamed in chunk 1); the two coincide when no
      ;; continuation mutates a root-read key.
      ;;
      ;; rf2-5knxf.2 — the hash is still the full-document structural hash via
      ;; `lifecycle/render-document-hash` — the IDENTICAL mechanism the
      ;; non-streaming `build-full-response*` uses. rf2-9fw2de: it folds the
      ;; resolved head fragment (+ html/body attr bags) into the canonical
      ;; input alongside the body tree, so a head-only divergence changes the
      ;; hash (Spec 011 §624-626/§648-650). This is the correct structural
      ;; hash, NOT a streaming-specific divergence:
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
      ;; rf2-l1qgjw — the `:final-payload` phase spans BOTH the
      ;; `build-final-payload` assembly (which can throw on a bad payload
      ;; policy / serialise) AND its write, so a throw in either surfaces
      ;; as `:phase :final-payload` rather than leaking out as the prior
      ;; `:continuation-template`/`:shell-html` phase.
      (vreset! phase [:final-payload nil])
      ;; rf2-tbr67x / rf2-nu5w48: pin `*current-frame*` on THIS daemon thread
      ;; around the post-drain hash recompute + `build-final-payload` (the
      ;; request thread's `*current-frame*` does not cross the thread boundary),
      ;; the SAME `rf/with-frame` the continuation render uses above. The
      ;; root-view re-resolution's registered-view / head lookups + the payload
      ;; reads operate on the request frame.
      (let [final-payload
            (rf/with-frame frame-id
              ;; rf2-1kqvbx — re-resolve the root view against the POST-drain
              ;; frame state and recompute the full-document hash over it, so
              ;; the payload's `:rf/render-hash` describes the SAME moment as
              ;; its post-drain `:rf/app-db`. Falls back to the pre-drain
              ;; `doc-hash` when no `:root-view` could be re-resolved
              ;; (defensive — `validate-construction-opts!` requires
              ;; `:root-view`, so this is belt-and-braces).
              (let [post-drain-hash
                    (if root-view
                      (lifecycle/render-document-hash
                        (lifecycle/resolve-root-view root-view)
                        head-bag)
                      doc-hash)]
                (streaming/build-final-payload
                  frame-id post-drain-hash
                  {:version       version
                   :schema-digest schema-digest
                   :payload       payload})))]
        ;; Shared id-pinned, `</script>`-escaped payload <script>
        ;; (rf2-7ksyr) — same helper the non-streaming shell uses.
        (write-chunk! out (shell/payload-script-tag (pr-str final-payload))))
      ;; Chunk N+3 — shell suffix close.
      (vreset! phase [:suffix nil])
      (write-chunk! out (default-streaming-suffix opts)))
    (catch Throwable t
      ;; rf2-l1qgjw — stamp the in-flight phase + (when inside a
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
        ;; EP-0008 (rf2-hhutya): ALSO ride the always-on axis. NON-
        ;; PROJECTING — this fires on the daemon writer thread POST-head-
        ;; commit (`:committed? true`); the chunked 200 is already on the
        ;; wire and the status can no longer change, so this is pure off-box
        ;; telemetry (the always-on `error-emit-projection-listener` never
        ;; stamps a status from it — there is no response accumulator left to
        ;; stamp). An off-box shipper on a `-Dre-frame.debug=false` host must
        ;; still see a writer-phase failure (broken client pipe vs bad final
        ;; payload). Union record: the trace `tags` ARE the flat category
        ;; keys, so promote them onto the union shape verbatim.
        (lifecycle/emit-always-on-error!
          (assoc tags :error :rf.error/ssr-streaming-writer-failed
                      :time  (interop/now-ms)))))
    (finally
      (try (.close out) (catch Throwable _ nil))))))

;; ---- public surface ------------------------------------------------------

(defn strip-content-length
  "Remove any `Content-Length` header (case-insensitively) from a Ring
  response header map. The streaming body is a chunk-producing
  `PipedInputStream` whose final byte count is unknown when the head
  commits, so the response MUST use `Transfer-Encoding: chunked`
  framing (Spec 011 §Streaming SSR — the wire shape pins chunked
  transfer). A stale fixed `Content-Length` — set/appended by app or
  server init code (`:rf.server/set-header` / `:rf.server/append-header`
  during the `:initial-events` drain) before streaming was chosen — would
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

(defn- redirect-response!
  "Short-circuit a streaming request to a bodiless Location response and
  destroy the per-request frame inline. Shared (rf2-tqjc7h) by BOTH redirect
  branches in `stream-handler`: the early `:initial-events`-drain redirect and the
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
  body arg on its `:redirect` branch (pipeline.clj)."
  [resp frame-id]
  (try
    (pipeline/ssr-response->ring-response resp nil)
    (finally
      (lifecycle/destroy-frame-quietly! frame-id))))

(defn- render-shell-or-projected-error
  "Render the streaming shell on THIS (request) thread, BEFORE the chunked
  response head commits. rf2-r06pc — the streaming counterpart of the
  non-streaming post-render re-flush (rf2-c0bq1). The shell render is the
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
  substrate (rf2-vvwmi); the caller's post-shell `ssr/get-response` re-read
  drains that buffer and stamps the 500 onto the response accumulator before
  the chunked head is materialised."
  [frame-id opts]
  (try
    (render-streaming-shell! frame-id opts)
    (catch Throwable t
      (let [err-resp (pipeline/project-render-throw->ring-response frame-id t opts)]
        (lifecycle/destroy-frame-quietly! frame-id)
        (reduced err-resp)))))

(defn- stream-rendered-response
  "Materialise the chunked response head from the (post-shell re-read) response
  accumulator `resp2`, wire the pipe, and spawn the daemon writer — the
  non-redirect leaf of `stream-handler` once the shell is known-renderable.

  rf2-z5azc — MATERIALISE the head (status / headers / cookies) from `resp2`
  BEFORE constructing the pipe or spawning the writer. Cookie / header
  serialisation CAN throw at materialise time on a value that escaped the fx
  boundary's partial validation — e.g. a `:max-age` carrying CR/LF, which
  `cookie->set-cookie-header` rejects but the runtime `validate-cookie!` does
  not. If the writer were spawned first, that throw would orphan the pipe (the
  daemon writer pumps the full body into a reader-less pipe, blocks once the
  16 KiB buffer fills, and leaks one live thread per request). Building
  `resp-map` first lets a head-materialisation failure short-circuit to the
  handler's outer catch BEFORE any thread or pipe exists → on-error, no detached
  writer, no orphaned pipe.

  rf2-h3dg0 — STRIP any `Content-Length` header (case-insensitively) from the
  materialised head before wiring the chunk-writer body. App / server init can
  `:rf.server/set-header` (or `append-header`) a `Content-Length` during the
  `:initial-events` drain — a fixed length that is meaningless (and actively harmful)
  once the body becomes a chunk-producing PipedInputStream of unknown final
  size. Left in place, a Ring server may honour that length instead of chunked
  transfer framing → truncated HTML / clients blocked on the wrong byte count /
  lost chunks, violating Spec 011's chunked-transfer streaming contract.

  rf2-ekwda — the writer runs on a DAEMON thread: one blocked on `.write` to the
  bounded 16 KiB pipe of a slow-loris client must NOT keep the JVM alive at
  shutdown. Its `finally` tears the frame down (off the response-close path,
  via the slower destroy)."
  [frame-id rendered resp2 content-type opts]
  (let [;; No body default-stamp here (we pass our own InputStream); `:body` is
        ;; assoc'd after the writer is wired below.
        resp-map (-> (pipeline/ssr-response->ring-response resp2 "" content-type)
                     (update :headers strip-content-length))
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
              ;; slower destroy path.
              (lifecycle/destroy-frame-quietly! frame-id))))
        ^String (str "rf2-ssr-streaming-" (name frame-id)))
      (.setDaemon true)
      (.start))
    (assoc resp-map :body pipe-in)))

(defn stream-handler
  "Return a synchronous Ring handler that streams SSR responses via
  `Transfer-Encoding: chunked`. Per Spec 011 §Streaming SSR.

  Opts mirror `re-frame.ssr.ring/ssr-handler` — same `:initial-events` /
  `:root-view` / `:payload` / `:on-error` /
  `:error-view` / `:emit-hash?` / `:version` / `:schema-digest` /
  `:content-type` plus the four trusted shell-hook opts (`:head` /
  `:body-end` / `:script-src` / `:app-element-id`, honoured by
  `default-streaming-prefix` / `default-streaming-suffix`). `:initial-events`
  accepts BOTH forms `ssr-handler` does — an `:initial-events` vector OR a
  `(fn [request] -> initial-events-vector)` deriving the setup vector from
  the Ring request (rf2-kzns7l; both flow through the shared
  `pipeline/setup-request-frame!`). One exception (rf2-oq4m5):

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
      synchronous :initial-events drain),
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
  ;; streaming specifically, a missing :initial-events would otherwise fail
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
  ;; way via the shared `lifecycle/resolve-on-error`: caller's
  ;; `:on-error` wins, else the locked `default-on-error` (rf2-kzvwq
  ;; topology-leak contract).
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
            ;; rf2-r06pc — the request-thread body, top-down: read the response
            ;; accumulator; on a `:redirect` short-circuit BEFORE any chunked
            ;; head / writer; otherwise render the shell on THIS thread (failing
            ;; closed to a projected non-200 on a render throw), re-read the
            ;; accumulator for a fail-closed status a recovered-to-nil sub
            ;; buffered during the render (rf2-vvwmi), and stream — committing
            ;; the chunked head ONLY once the shell is known-renderable. The
            ;; helpers (`render-shell-or-projected-error`, `stream-rendered-
            ;; response`, `redirect-response!`) carry the per-step rationale.
            (let [resp (ssr/get-response frame-id)]
              (if (some? (:redirect resp))
                ;; Redirect — short-circuit per Spec 011 §Redirect precedence.
                (redirect-response! resp frame-id)
                (let [rendered (render-shell-or-projected-error frame-id opts)]
                  (if (reduced? rendered)
                    ;; Shell render threw — return the projected error page
                    ;; (frame already torn down inline).
                    @rendered
                    ;; Shell rendered cleanly. Re-read the accumulator to
                    ;; surface any fail-closed status (rf2-vvwmi /
                    ;; rf2-c0bq1) before materialising the chunked head.
                    ;;
                    ;; rf2-5knxf.1 — a `:redirect` here is defense-in-depth:
                    ;; in v1 it cannot surface at the post-shell re-read
                    ;; (only the `:initial-events`-drain `:rf.server/redirect`
                    ;; sets it, caught by the early branch above; the error
                    ;; projector stamps `:status` only, never `:redirect`).
                    ;; The branch aligns the streaming path with the
                    ;; non-streaming handler's redirect-ignores-body parity
                    ;; for a future render-phase fx / hand-built accumulator
                    ;; that learns to redirect.
                    (let [resp2 (ssr/get-response frame-id)]
                      (if (some? (:redirect resp2))
                        (redirect-response! resp2 frame-id)
                        (stream-rendered-response
                          frame-id rendered resp2 content-type opts)))))))
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
