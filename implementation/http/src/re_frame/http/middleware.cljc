(ns re-frame.http.middleware
  "Request- AND response-side HTTP interceptors for `:rf.http/managed`.

  Per Spec 014 §Middleware, an HTTP interceptor is a frame-agnostic
  DEFINITION and a frame ACTIVATES definitions by naming them:

    (rf/reg-http-interceptor :shop/auth {:before … :after …})   ;; top level
    (rf/make-frame {:id :shop/app
                    :http-interceptors [:shop/auth :shop/telemetry]})

  `reg-http-interceptor` stores the definition under the registrar kind
  `:http-interceptor`. Each definition carries an optional `:before`
  (request-side transform, fired before `:rf.http/managed` issues the
  request) and an optional `:after` (response-side transform, fired on the
  response BEFORE `:on-success` / `:on-failure` are dispatched). A frame that
  names no chain runs none, however many definitions are registered.

  ## ctx contract

  Each `:before` receives a ctx map with the documented shape:

    {:request    <request-map>   ;; the :request map from the args
     :args       <full-args-map> ;; the full :rf.http/managed args
     :frame      <frame-id>      ;; resolved frame id
     :event      <origin-event>  ;; originating event vector or nil
     :sensitive? <boolean>}      ;; the args' sensitivity, resolved before the chain

  A `:before` returns the (possibly-modified) ctx. The runtime threads
  the chain in the frame's vector order; the final `:request` is what the
  transport ships.

  Each `:after` receives `(fn [ctx response] response')` where:

    - `ctx`      — the SAME ctx the `:before` chain produced for THIS
                   request. Carrying the request ctx forward enables
                   request-correlated response handling: response-time
                   telemetry (wall-clock delta between a `:before`'s
                   start mark and the `:after`'s read), per-request
                   header parsing, auth-token refresh keyed off the
                   originating event, …
    - `response` — the canonical reply envelope:
                   `{:status :ok :value <decoded> …}` or
                   `{:status :error :error <failure-map> …}` (an abort is
                   `{:status :cancelled :error <aborted-map> …}`). The
                   shape matches the reply-payload `build-reply-event`
                   appends to `:on-success` / `:on-failure`.

  Returns the (possibly-transformed) response map; the runtime threads
  the next `:after` over the return value and finally substitutes the
  fully-threaded response into the reply-payload before
  `:on-success` / `:on-failure` fire.

  ## Chain ordering

  The frame's `:http-interceptors` vector IS the order; registration and
  load order configure nothing.

  - `:before` chain — vector order. `[:a :b]`: request flows
    a.before → b.before → transport.
  - `:after`  chain — REVERSE vector order. `[:a :b]`: response flows
    transport → b.after → a.after → reply dispatch.

  This mirrors the event-interceptor onion (Spec 002): the first id wraps
  the rest on the request side and again on the response side. Interceptors
  with no `:after` are transparent in the response chain (skipped, not
  nil-substituted).

  ## Resolution

  `capture-chain` reads the frame's `:http-interceptors` when a request is
  issued and resolves each id through the frame's own view (its image
  generation, or the live registration table), so a later image's
  definition of an id shadows the app's for frames built on that image. The
  resolved vector is captured once per request."
  (:require [re-frame.error        :as rf.error]
            [re-frame.frame        :as rf.frame]
            [re-frame.http.encoding :as rf.http.encoding]
            [re-frame.http.privacy :as rf.http.privacy]
            [re-frame.interop      :as rf.interop]
            [re-frame.late-bind    :as rf.late-bind]
            [re-frame.registrar    :as rf.registrar]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.trace        :as rf.trace]))

(def kind
  "The registrar kind HTTP interceptor definitions live under."
  :http-interceptor)

(defn- valid-args?
  "Validate the positional id and interceptor map. The map must carry at least
  one of `:before` / `:after`, each, if present, a fn."
  [id interceptor-map]
  (and (keyword? id)
       (map? interceptor-map)
       (or (nil? (:before interceptor-map))
           (fn?  (:before interceptor-map)))
       (or (nil? (:after  interceptor-map))
           (fn?  (:after  interceptor-map)))
       ;; at least one of :before / :after must be supplied — registering
       ;; a no-op interceptor is meaningless and almost certainly a typo
       (or (some? (:before interceptor-map))
           (some? (:after  interceptor-map)))))

(defn- throw-frame-key!
  "A definition is frame-agnostic, so a `:frame` in the interceptor map has no
  meaning; ignoring it would activate nothing where the author asked for a
  frame-local interceptor. The message names the activation route."
  [id interceptor-map]
  (rf.error/throw-error!
    :rf.error/http-bad-interceptor 'rf/reg-http-interceptor
    (str "reg-http-interceptor `" id "` carries `:frame "
         (pr-str (:frame interceptor-map)) "`, but an HTTP interceptor is a "
         "frame-agnostic definition. Register it at the top level without "
         "`:frame`, and name it in the frame's `:http-interceptors` config "
         "vector, e.g. (rf/make-frame {:id :my/app :http-interceptors ["
         id "]}).")
    {:extra {:received {:id id :interceptor-map interceptor-map}}}))

(defn reg-http-interceptor
  "Register the HTTP interceptor definition `id`. Per Spec 014 §Middleware.

  Signature: `(reg-http-interceptor id interceptor-map)` — `id` is a
  keyword; `interceptor-map` carries:

    - `:before` (optional) — `(fn [ctx] ctx')`, request-side transform
    - `:after`  (optional) — `(fn [ctx response] response')`,
                              response-side transform
    - `:doc` / `:tags` / `:schema` — standard
      `:rf/registration-metadata` (per `:rf/http-interceptor-meta`)

  A definition is frame-agnostic and runs only for a frame that names `id` in
  its `:http-interceptors` config vector, in that vector's position. A
  definition no frame names is simply unused — there is no warning.

  Request sensitivity is not interceptor metadata: it is each request's
  per-call `:sensitive?` or `[:request :sensitive?]` (see
  `re-frame.http.privacy/request-sensitive?`).

  At least one of `:before` / `:after` MUST be supplied — a no-op
  interceptor is rejected.

  Example:
    (reg-http-interceptor :auth
      {:doc \"Stamp Bearer token.\"
       :before (fn [ctx] ...)
       :after  (fn [ctx response] ...)})

  `ctx` carries `:request` (the request map), `:args` (the full
  `:rf.http/managed` args), `:frame` (the frame-id), and `:event` (the
  originating event vector). `:before` returns a (possibly-modified)
  ctx. `:after` receives the ctx unchanged plus the canonical reply
  envelope (`{:status :ok :value v …}` or `{:status :error :error f …}`)
  and returns the (possibly-transformed) response.

  Source-coords (`:ns` / `:line` / `:column` / `:file`) are auto-captured
  at the `rf/reg-http-interceptor` call site by the JVM-emitted macro in
  `re-frame.core` (per Spec 001 §Source-coordinate capture). The stored
  definition conforms to `:rf/http-interceptor-meta` (Spec-Schemas).

  Re-registering an id replaces its definition; every frame naming it picks
  up the new one on its next request. Clear one with
  `(rf/clear :http-interceptor id)`.

  Throws `:rf.error/http-bad-interceptor` if any arg shape is invalid, or if
  the map carries `:frame`.

  Returns the registered `id`."
  [id interceptor-map]
  (when (and (map? interceptor-map) (contains? interceptor-map :frame))
    (throw-frame-key! id interceptor-map))
  (when-not (valid-args? id interceptor-map)
    (rf.error/throw-error!
      :rf.error/http-bad-interceptor 'rf/reg-http-interceptor
      "expected (reg-http-interceptor id interceptor-map): id keyword; interceptor-map a map carrying at least one of :before / :after (each a fn), optional :rf/registration-metadata"
      {:extra {:received {:id id :interceptor-map interceptor-map}}}))
  (let [before (:before interceptor-map)
        after  (:after  interceptor-map)]
    (rf.registrar/register! kind id
                            (cond-> (rf.source-coords/merge-coords
                                      (dissoc interceptor-map :before :after))
                              true   (assoc :id id)
                              before (assoc :before before)
                              after  (assoc :after after)))
    (when rf.interop/debug-enabled?
      (rf.trace/emit! :info :rf.http.interceptor/registered {:id id}))
    id))

(defn clear-http-interceptor
  "Remove the HTTP interceptor definition `id` — the `:http-interceptor` arm of
  `(rf/clear :http-interceptor id)`. Emits `:rf.http.interceptor/cleared` when
  a definition existed. A frame that still names `id` fails its next request
  at issue, as it would for an id never registered. Returns `id`."
  [id]
  (let [existed? (contains? (rf.registrar/registrations kind) id)]
    (rf.registrar/unregister! kind id)
    (when (and existed? rf.interop/debug-enabled?)
      (rf.trace/emit! :info :rf.http.interceptor/cleared {:id id}))
    id))

(defn clear-all-http-interceptors!
  "Test-time helper: remove every HTTP interceptor definition."
  []
  (rf.registrar/clear-kind! kind)
  nil)

(defn- throw-unregistered!
  [frame-id ids id]
  (rf.error/throw-error!
    :rf.error/unregistered-interceptor 'rf.http.middleware/capture-chain
    (str "frame `" frame-id "` names HTTP interceptor `" id "` in its "
         "`:http-interceptors` chain " (pr-str ids) ", but no "
         "`reg-http-interceptor` defines it. Register the definition, or "
         "remove the id from the frame's `:http-interceptors`.")
    {:recovery :fix-registration
     :extra    {:ref               id
                :id                id
                :frame             frame-id
                :http-interceptors ids}}))

(defn capture-chain
  "Resolve `frame-id`'s HTTP interceptor chain for ONE request, at ISSUE time.
  Returns the vector of definitions the frame's `:http-interceptors` names, in
  that order; that same vector drives the request's `:before` walk, its
  `:after` walk and every retry attempt.

  Each id resolves through the frame's own view (its image generation, or the
  live registration table), so a frame built on a later image runs that
  image's definition of an id. An id with no definition fails the request
  here with `:rf.error/unregistered-interceptor`, before any `:before` runs.

  Resolution is at issue time (Spec 014 §Chain order and frame scope):
  re-registering or clearing a definition, or re-making the frame with a
  different `:http-interceptors`, affects only requests issued afterwards.
  Callers pass the captured vector to `run-interceptor-chain!` /
  `run-after-chain!` — neither walker resolves anything, so there is no window
  in which a response walks a chain its request never had.

  A frame that names no chain captures `[]`, a real snapshot rather than a
  signal to resolve later.

  Snapshotting does not freeze the world: a captured fn can still read the
  current app-db, deref an atom or call a redefined var. What is stable
  across the two walks is chain MEMBERSHIP, ORDER and the stored function
  values."
  [frame-id]
  (let [ids (:http-interceptors (rf.frame/frame-config frame-id))]
    (if (empty? ids)
      []
      (let [resolve (fn []
                      (mapv (fn [id]
                              (or (rf.registrar/lookup kind id)
                                  (throw-unregistered! frame-id ids id)))
                            ids))]
        (if-let [with-frame-view (rf.late-bind/get-fn-cached
                                   :live-frame/call-with-frame-resolution)]
          (with-frame-view frame-id resolve)
          (resolve))))))

(defn interceptors-snapshot
  "Test-time helper. With no argument, every registered HTTP interceptor
  definition as `{id definition}`; with a `frame-id`, the chain a request
  issued from that frame now would capture (`capture-chain`). Each definition
  is the stored `:rf/http-interceptor-meta` map (`:id`, optional `:before` /
  `:after`, captured source-coords + registration-metadata).

  Not part of the user-facing API — application code registers with
  `reg-http-interceptor` and activates with the frame's `:http-interceptors`."
  ([] (rf.registrar/registrations kind))
  ([frame-id] (capture-chain frame-id)))

;; The `:before` (`run-interceptor-chain!`) and `:after`
;; (`run-after-chain!`) chains share one walk shape: reduce over the
;; captured chain, skip interceptors lacking the relevant slot, run the
;; slot fn, wrap any throw as `:rf.error/http-interceptor-failed`, and
;; reject a non-map return with `:rf.error/http-interceptor-bad-return`
;; (each built under the request's effective sensitivity, traced through the
;; privacy composer, then thrown — see `raise-chain-error!`). The return
;; check sits OUTSIDE the slot fn's `try`, so a bad return reaches the
;; trace as its own id rather than as the `:cause` of a failure. `run-chain*`
;; factors that body out; the two public fns are thin wrappers that vary
;; only on: the slot key (`:before` / `:after`), how the slot fn is
;; invoked (`(slot acc)` vs `(slot fixed-ctx acc)`), the chain order
;; (vector / reverse), the `:where` symbol + optional `:phase`, the
;; URL source (the threaded `acc` for `:before`; the fixed middleware-ctx
;; for `:after`), and the bad-return sentence. The load-bearing comments
;; on each wrapper carry the per-path rationale.
(defn- raise-chain-error!
  "Build the chain error `error-id` from the ex-data slots `extra`, trace it,
  and throw it.

  Its human sentence, `reason-of` applied to the `echo-key` slot, echoes one
  author-controlled slot: a throw's `:cause` or a bad return's `:returned`.
  The thrown ex-info is recorded beyond this walk: on the request side it
  escapes the `:rf.http/managed` handler into core's fx boundary, on the
  response side the reply-tail fence catches it, and each boundary records
  the exception and text quoting its message, on the always-on error record
  as well as the dev trace, so in production too. So the slots are the
  failure composer's projection under the effective `sensitive?`, with the
  registration's carriers, and the sentence is rendered from the projected
  slot: the error keeps its id and names its interceptor, and carries no more
  of the author's data than its own trace row does. On every request that
  projection redacts the URL's denylisted query values; a non-sensitive chain
  error carries its other slots verbatim.

  The trace row is that ex-data through the privacy composer under the same
  `sensitive?`, which stamps `:sensitive?` on a denylist hit."
  [error-id where extra echo-key reason-of sensitive?]
  (let [extra (rf.http.privacy/redact-failure extra sensitive? (rf.http.privacy/managed-carriers))
        data  (rf.error/thrown-ex-info error-id where (reason-of (get extra echo-key))
                                       {:extra extra})]
    (when rf.interop/debug-enabled?
      (rf.trace/emit-error! error-id
                            (rf.http.privacy/prepare-emit-failure (ex-data data) sensitive?)))
    (throw data)))

(defn- run-chain*
  [{:keys [chain frame-id slot-key invoke sensitive-of where phase url-of slot-noun]} init]
  (reduce
    (fn [acc interceptor]
      (let [{:keys [id]} interceptor
            slot          (get interceptor slot-key)]
        (if slot
          (let [out (try
                      (invoke slot acc)
                      (catch #?(:clj Throwable :cljs :default) t
                        ;; Prefer the inner throw's :reason (a human sentence
                        ;; naming the offending interceptor) over the raw message.
                        (let [cause (or (:reason (ex-data t))
                                        #?(:clj  (.getMessage ^Throwable t)
                                           :cljs (.-message t)))]
                          ;; `sensitive-of` recomputes the EFFECTIVE
                          ;; sensitivity from the CURRENT accumulator (the evolving
                          ;; ctx a prior `:before` may have MARKED sensitive), not a
                          ;; flag captured before the chain ran. With a captured
                          ;; flag, a `:before` that set `[:request :sensitive?] true`
                          ;; followed by a later `:before` that threw would trace and
                          ;; throw this error under the stale non-sensitive flag,
                          ;; leaking the throw's message and non-denylisted query
                          ;; values for a now-sensitive request.
                          (raise-chain-error!
                            :rf.error/http-interceptor-failed where
                            (cond-> {:frame          frame-id
                                     :interceptor-id id
                                     :url            (url-of acc)
                                     :cause          cause}
                              phase (assoc :phase phase))
                            :cause
                            (fn [cause]
                              (str "HTTP interceptor `" id "` threw while processing "
                                   "the " (if phase (name phase) "request / response")
                                   " chain for `" frame-id "`. Fix the interceptor's "
                                   "handler so it does not throw (Spec 014 "
                                   "§Middleware)."
                                   (when cause (str " Cause: " cause))))
                            (sensitive-of acc)))))]
            (if (map? out)
              out
              ;; Canonical thrown-error shape (Spec 009): the central
              ;; builder derives the message from :reason + the
              ;; [:rf.error/<id>] token, so the human sentence (naming the
              ;; offending interceptor id) leads the message. Built under the
              ;; same effective sensitivity as a throw: the returned value is
              ;; usually a slice of the ctx the interceptor was handed, so a
              ;; sensitive request's error echoes it only as the composer
              ;; projects `:returned`.
              (raise-chain-error!
                :rf.error/http-interceptor-bad-return 'rf/reg-http-interceptor
                {:id id :returned out}
                :returned
                (fn [returned]
                  (str "HTTP interceptor `" id "` " slot-noun ". A `:before` / "
                       ":after` HTTP interceptor (Spec 014 §Middleware) must "
                       "return a map (the threaded request / response ctx); "
                       "return the (possibly transformed) ctx, not "
                       (pr-str returned) "."))
                (sensitive-of acc))))
          acc)))
    init
    chain))

(defn run-interceptor-chain!
  "Walk the captured interceptor `chain` for `frame-id` in order, threading
  `ctx` through each `:before`. Returns the final ctx, or throws
  `:rf.error/http-interceptor-failed` if any `:before` throws and
  `:rf.error/http-interceptor-bad-return` if one returns a non-map.

  `chain` is the caller's issue-time capture (`capture-chain`), NOT a fresh
  resolution. The same vector must drive this request's `:after` walk, so a
  definition registered or re-made from inside a `:before` reaches only LATER
  requests.

  Interceptors without a `:before` slot are transparent in the request
  chain (acc passes through unchanged).

  `ctx` carries a top-level `:sensitive?` flag (resolved by
  `managed-handler` from the per-call args)
  so the failure-path trace event redacts the request URL via the
  query-param denylist before it reaches the trace surface. Without
  this gate, an `Authorization`-token-bearing query string (e.g.
  `?access_token=…`) would leak into traces whenever an interceptor
  threw."
  [frame-id chain ctx]
  (run-chain*
    {:chain      chain
     :frame-id   frame-id
     :slot-key   :before
     ;; `:before` reads the URL from the threaded accumulator — the
     ;; evolving ctx, whose `:request` an earlier `:before` may have
     ;; rewritten — so the trace carries the URL as the failing
     ;; interceptor actually saw it.
     :invoke     (fn [before acc] (before acc))
     :url-of     #(get-in % [:request :url])
     ;; Recompute effective sensitivity from the CURRENT
     ;; accumulator at the failure site, not a flag fixed before the
     ;; chain ran. A `:before` may MARK the request sensitive (set
     ;; `[:request :sensitive?] true`) — mirrors `privacy/request-
     ;; sensitive?`'s top-level-or-`:request` OR-reduction, applied to
     ;; the threaded ctx so a later `:before`'s throw redacts under the
     ;; sensitivity the request carries at the moment it failed.
     :sensitive-of (fn [acc]
                     (or (true? (:sensitive? acc))
                         (true? (get-in acc [:request :sensitive?]))))
     :where      'rf.http/run-interceptor-chain!
     :slot-noun  ":before did not return a ctx map"}
    ctx))

(defn run-after-chain!
  "Per Spec 014 §Middleware. Walk this request's CAPTURED
  interceptor `chain` in REVERSE order, threading
  `response` through each `:after`. Returns the (possibly-transformed)
  response map.

  `chain` is the vector the request captured at ISSUE time
  (`capture-chain`), carried forward by the transport alongside the
  middleware-ctx. It is deliberately NOT a fresh resolution: the ctx
  and the chain that consumes it are frozen together, so an `:after` only
  ever runs for a request whose `:before` chain it was part of. An empty
  captured chain is a real snapshot — there is no re-resolution fallback.

  Each `:after` receives `(fn [ctx response] response')` — `ctx` is the
  middleware-ctx the `:before` chain produced for THIS request (carried
  forward by the transport so the `:after` sees the exact same shape
  the `:before` ended with). `response` is the canonical reply envelope
  (`{:status :ok :value v …}` or `{:status :error :error f …}`).

  Interceptors without an `:after` slot are transparent in the response
  chain (acc passes through unchanged). Throws by `:after` propagate
  to the caller via the same `:rf.error/http-interceptor-failed` shape
  the `:before` path uses, and a non-map return via the same
  `:rf.error/http-interceptor-bad-return`, so a misbehaving response-side
  interceptor surfaces on the same trace events."
  [frame-id chain middleware-ctx response]
  (run-chain*
    {;; Reverse order — mirror of the event-interceptor onion (Spec 002).
     ;; Reversed over the request's CAPTURED chain, never a
     ;; response-time re-resolution of the frame's chain.
     :chain      (reverse chain)
     :frame-id   frame-id
     :slot-key   :after
     ;; `:after` always sees the fixed middleware-ctx the `:before` chain
     ;; produced (the response, not the ctx, is what threads through the
     ;; reduce), so the URL is read from that ctx rather than the acc.
     ;; And it is the SAME captured chain that produced that
     ;; ctx: both halves of the pairing are frozen at issue, so an
     ;; `:after` is never handed a ctx its own `:before` never touched.
     :invoke     (fn [after acc] (after middleware-ctx acc))
     :url-of     (fn [_acc] (get-in middleware-ctx [:request :url]))
     ;; The `:after` chain threads the RESPONSE through `acc`;
     ;; the request ctx is the fixed `middleware-ctx` the `:before` chain
     ;; ended with, so effective sensitivity is recomputed from THAT
     ;; (constant across `:after` steps), not the evolving response acc.
     ;; Mirrors `privacy/request-sensitive?`'s OR-reduction.
     :sensitive-of (fn [_acc]
                     (or (true? (:sensitive? middleware-ctx))
                         (true? (get-in middleware-ctx [:request :sensitive?]))))
     :where      'rf.http/run-after-chain!
     :phase      :after
     :slot-noun  ":after did not return a response map"}
    response))

(defonce ^:private failure-swallowed-warned?
  ;; One-shot latch so the "real failure swallowed by
  ;; `:on-failure nil`" warning fires once per runtime, not once per
  ;; swallowed request. Fire-and-forget telemetry beacons (`:on-failure
  ;; nil`) are a legitimate steady-state pattern, so a per-request trace
  ;; would be noise; the single warning makes the FIRST silently-dropped
  ;; non-aborted failure visible (the no-silent-swallow principle) without
  ;; flooding the trace surface for callers who knowingly opted out.
  (atom false))

(defn- warn-failure-swallowed!
  "Surface a swallowed real failure once per runtime.

  When a reply's final envelope routes to the failure branch and that branch
  has no target — an explicit `:on-failure nil`, or a failure branch left
  unaddressed — `build-reply-event` silences the reply (fire-and-forget). But
  a NON-aborted failure (transport / 5xx / decode / accept / timeout, or a
  success an `:after` turned into an error) routed into that silence is a real
  error the app never sees — the anti-pattern the committed no-silent-swallow
  principle calls out. Emit a one-shot `:rf.warning/failure-swallowed` so the
  dropped failure is observable in dev / tooling.

  Aborts (`:rf.http/aborted`, any reason) are EXCLUDED: a cancelled
  request that no longer wants its reply is correct-by-design silence,
  not a swallowed error."
  [failure url sensitive?]
  (when (and rf.interop/debug-enabled?
             (not= :rf.http/aborted (:kind failure))
             (compare-and-set! failure-swallowed-warned? false true))
    (rf.trace/emit! :warning :rf.warning/failure-swallowed
                 (rf.http.privacy/prepare-emit-tags
                   {:url     url
                    :failure failure
                    :reason  (str "an HTTP request failed with `:kind "
                                  (pr-str (:kind failure))
                                  "` but the failure reply had no target "
                                  "(`:on-failure nil`, or the failure branch "
                                  "was left unaddressed) — the failure was "
                                  "dropped with no handler. If the silence is "
                                  "intentional (fire-and-forget telemetry), "
                                  "ignore this; otherwise supply an "
                                  "`:on-failure` or `:reply-to` target.")}
                   (true? sensitive?)))))

(defn run-after-then-dispatch!
  "The shared reply tail: thread `reply-payload` through the request's
  `:after` interceptor chain, route the FINAL envelope by its `:status`, and
  hand it to the late-bind reply router
  (`encoding/dispatch-reply-via-late-bind!`).

  The branch is chosen AFTER the chain, from the envelope the chain returned:
  `:status :ok` goes to the success target, any other `:status` (or none) to
  the failure target. `:on-success` / `:on-failure` are routing sugar over one
  reply, so the handler a reply reaches always agrees with its `:status` — an
  `:after` that rejects a 200 lands the error on the failure pipeline, and one
  that treats a 404 as empty lands the `:ok` on the success pipeline.
  `:reply-to` lowers to the same target for both branches, so it receives the
  final envelope either way. The request is already finalised when this runs,
  so an `:after`-produced failure is terminal and never re-enters retry.

  A non-aborted reply routed to a failure branch with no target is dropped;
  `warn-failure-swallowed!` makes the first one per runtime observable.

  Single source of truth for the two reply paths, which differ only in
  the `:after`-guard:
   - `http-transport/dispatch-reply!` — the real-transport completion
     path. Synthetic / test-path callers may carry NO `:middleware-ctx`
     (they built a ctx directly without going through `managed-handler`);
     the `(if middleware-ctx …)` guard skips the `:after` chain and
     passes the payload through unchanged — the chain's contract is to
     see the `:before`'s ctx, not a synthesised one.
   - `http-test-support/dispatch-canned-reply!` — the canned-stub test
     path, which always produces a `:middleware-ctx` via
     `capture-and-run-request-chain` and therefore always runs the `:after`
     chain.

  `opts` carries `:frame`, `:middleware-ctx`, `:chain` (the
  request's issue-time chain capture, which travels with the ctx),
  `:origin-event`, both branch descriptors `:explicit-on-success` /
  `:explicit-on-failure` (`encoding/reply-target`'s `{:supplied? :value}`),
  the request's `:url` and effective `:sensitive?` (read only by the swallow
  warning), and the EP-0010 `:completed-at` causal completion time threaded
  onto the reply dispatch's `:rf.cofx`. No-op when the router is absent / the
  reply is silenced (delegated to `dispatch-reply-via-late-bind!`).

  The `:after` walk is keyed off the presence of
  `:middleware-ctx`, which is the synthetic-caller guard above; a caller
  carrying a ctx carries its captured chain with it, and an EMPTY chain
  runs no `:after` rather than resolving the frame's chain afresh."
  [{:keys [frame middleware-ctx chain origin-event explicit-on-success explicit-on-failure
           reply-payload url sensitive? completed-at]}]
  (let [final-payload (if middleware-ctx
                        (run-after-chain! frame chain middleware-ctx reply-payload)
                        reply-payload)
        success?      (= :ok (:status final-payload))
        explicit-on   (if success? explicit-on-success explicit-on-failure)]
    (when (and (not success?) (nil? (:value explicit-on)))
      (warn-failure-swallowed! (:error final-payload) url sensitive?))
    (rf.http.encoding/dispatch-reply-via-late-bind!
      {:origin-event  origin-event
       :explicit-on   explicit-on
       :reply-payload final-payload
       :completed-at  completed-at}
      frame)))
