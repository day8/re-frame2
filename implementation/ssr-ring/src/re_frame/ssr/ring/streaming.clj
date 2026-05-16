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
  shadow-cljs only picks up `.cljc` / `.cljs`. Streaming bootstrap on
  the client lives in `re-frame.ssr.streaming.client` (a .cljs shim,
  forthcoming as host-opt-in)."
  (:require [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.constants :as constants]
            [re-frame.ssr.html-helpers :as html]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.ring.trust :as trust]
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
  `default-html-shell`'s open + head + body-open + app-div-open."
  [head-html {:keys [html-attrs body-attrs lang app-element-id]
              :or   {lang "en" app-element-id "app"}}]
  (let [html-attr-bag (if (seq html-attrs)
                        (cond-> html-attrs
                          (not (contains? html-attrs :lang)) (assoc :lang lang))
                        {:lang lang})]
    (str "<!DOCTYPE html>"
         "<html" (html/attr-string html-attr-bag) ">"
         "<head>"
         "<meta charset=\"utf-8\">"
         (or head-html "")
         "</head>"
         "<body" (html/attr-string body-attrs) ">"
         "<div id=\"" app-element-id "\">")))

(defn default-streaming-suffix
  "The shell suffix flushed after the final-payload chunk. Closes the
  app-div, emits the bootstrap script tag (if any), the body-end raw
  HTML, and the document close."
  [{:keys [body-end script-src]
    :or   {script-src "/main.js"}}]
  (str "</div>"
       (when script-src
         (str "<script src=\"" script-src "\"></script>"))
       (or body-end "")
       "</body>"
       "</html>"))

;; ---- chunk writer --------------------------------------------------------
;;
;; The Ring response body is a `PipedInputStream` paired with a writer
;; thread that pushes chunks onto a `PipedOutputStream`. The writer
;; thread holds the per-request frame open across continuations and is
;; the place where exceptions are caught — anything that throws there
;; gracefully closes the pipe so the client sees a clean EOF rather
;; than a half-streamed response.
;;
;; Per Spec 011 §Failure semantics — inline fallback — exceptions
;; INSIDE a continuation render are caught by streaming/render-continuation
;; (which returns {:failed? true :html <fallback-html> :delta nil}); the
;; writer thread proceeds with the next boundary. Exceptions OUTSIDE a
;; continuation (e.g. an error projecting the response, or a final-
;; payload build throw) close the pipe with the partial response that
;; was already flushed — the client sees a truncated but valid HTML
;; structure (open tags balanced by what was emitted) and the server
;; logs the trace via the standard error-projection path.

(defn- ^bytes ->utf8 ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- write-chunk! [^OutputStream out ^String s]
  (.write out (->utf8 s))
  (.flush out))

(defn- run-streaming-writer!
  "Run the streaming writer on the calling thread. The caller supplies
  an open `OutputStream` (the pipe sink). On any throw, the catch arm
  emits a `:rf.error/ssr-streaming-writer-failed` trace and closes the
  stream cleanly so the Ring server can EOF the response."
  [^OutputStream out frame-id resp opts]
  (try
    (let [{:keys [root-view emit-hash? version schema-digest payload-keys
                  payload-policy content-type]} opts
          hiccup     (rf/with-frame frame-id
                       (lifecycle/resolve-root-view root-view))
          {:keys [head-html html-attrs body-attrs]}
          (if (:head opts)
            {:head-html (:head opts) :html-attrs nil :body-attrs nil}
            (lifecycle/resolve-head frame-id))
          {:keys [shell-html continuations]}
          (rf/with-frame frame-id (streaming/render-shell hiccup))
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
                              {:version        version
                               :schema-digest  schema-digest
                               :payload-keys   payload-keys
                               :payload-policy payload-policy}))]
        (write-chunk! out
                      (str "<script id=\"" constants/payload-script-id
                           "\" type=\"application/edn\">"
                           (html/escape-script-body-string
                             (pr-str final-payload))
                           "</script>")))
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
      precedence),
    - otherwise spawns a streaming writer (same calling thread) that
      flushes shell → continuations → final payload → close,
    - destroys the frame in finally so the per-frame side-channels
      clear (Spec 011 §Per-request frame teardown contract).

  The response body is a `PipedInputStream` Ring accepts directly; the
  pipe's writer side runs on a daemon thread so Jetty/http-kit/Aleph
  can begin sending bytes immediately while the writer continues to
  pump chunks. The pipe's sink-side close (in the writer's `finally`)
  signals EOF to the server.

  Returns:

    (fn handler [ring-request] ring-response)"
  [raw-opts]
  ;; Per rf2-gtgf9 — validate the hydration-payload policy at handler-
  ;; construction time so misconfigured deployments fail at boot rather
  ;; than at first request. Mirrors `ssr-handler`'s validate-handler-
  ;; opts! call site in `re-frame.ssr.ring`. Throws
  ;; `:rf.error/ssr-missing-payload-policy` on absence of both
  ;; `:payload-keys` and `:payload-policy`.
  (payload-policy/validate-policy-opts! raw-opts)
  ;; Per rf2-o6ndb — validate the four trusted-shell-hook opts are
  ;; strings (or nil) at construction time. The streaming prefix/suffix
  ;; injects these RAW into the rendered HTML envelope, same trust
  ;; contract as the non-streaming `default-html-shell`. Throws
  ;; `:rf.error/ssr-trusted-shell-opt-invalid` on a structural-shape
  ;; mistake (map / vector / symbol / number).
  (trust/validate-trusted-shell-opts! raw-opts)
  ;; Mirror ssr-handler's defaults + validation so streaming and non-
  ;; streaming handlers feel symmetric to callers.
  (let [opts        (merge {:emit-hash?   true
                            :content-type "text/html; charset=utf-8"
                            :on-error     (fn [_req _t]
                                            {:status  500
                                             :headers {"Content-Type" "text/plain; charset=utf-8"}
                                             :body    "Internal error"})}
                           raw-opts)
        {:keys [on-error content-type]} opts]
    (fn ring-handler [request]
      (let [{:keys [frame-id short-circuit]}
            (pipeline/setup-request-frame! opts request)]
        (if short-circuit
          short-circuit
          (try
            (let [resp (ssr/get-response frame-id)]
              (if (some? (:redirect resp))
                ;; Redirect short-circuits the stream — no chunked body.
                (pipeline/ssr-response->ring-response resp nil content-type)
                ;; Streaming path: build a pipe + spawn the writer thread.
                ;; 16 KiB pipe buffer — large enough to absorb the shell
                ;; chunk in one write so the writer thread rarely blocks
                ;; on a slow consumer, small enough that one stuck client
                ;; doesn't pin a non-trivial chunk of heap per request.
                (let [pipe-in  (PipedInputStream. (* 16 1024))
                      pipe-out (PipedOutputStream. pipe-in)]
                  (.start
                    (Thread.
                      ^Runnable
                      (fn writer-thread []
                        (try
                          (run-streaming-writer! pipe-out frame-id resp opts)
                          (finally
                            ;; The writer's own finally closes the
                            ;; pipe; the frame teardown happens here so
                            ;; it does NOT block the response close on
                            ;; the slower destroy path.
                            (lifecycle/destroy-frame-quietly! frame-id))))
                      ^String (str "rf2-ssr-streaming-" (name frame-id))))
                  ;; Build the Ring response off the response
                  ;; accumulator's status/headers/cookies — no body
                  ;; default-stamp (we pass our own InputStream).
                  (let [resp-map
                        (pipeline/ssr-response->ring-response resp "" content-type)]
                    (assoc resp-map :body pipe-in)))))
            (catch Throwable t
              ;; Setup-path throw OR get-response throw — neither happens
              ;; under the streaming writer's catch arm; destroy the
              ;; frame inline and respond per on-error.
              (try (lifecycle/destroy-frame-quietly! frame-id)
                   (catch Throwable _ nil))
              (on-error request t))))))))
