(ns re-frame.http.middleware
  "Per-frame request- AND response-side interceptor chain for `:rf.http/managed`.

  Per Spec 014 §Middleware, each frame has an ordered chain of HTTP
  interceptors. Each interceptor is an interceptor-map carrying an
  optional `:before` (request-side transform, fired in registration
  order before `:rf.http/managed` issues the request) and an optional
  `:after` (response-side transform, fired in REVERSE registration order
  on the response BEFORE `:on-success` / `:on-failure` are dispatched).

  The public surface is `(reg-http-interceptor id interceptor-map)`, where the
  map carries `:before`, `:after`, `:frame`, and standard registration
  metadata.

  ## ctx contract

  Each `:before` receives a ctx map with the documented shape:

    {:request <request-map>      ;; the :request map from the args
     :args    <full-args-map>    ;; the full :rf.http/managed args
     :frame   <frame-id>         ;; resolved frame id
     :event   <origin-event>}    ;; originating event vector or nil

  A `:before` returns the (possibly-modified) ctx. The runtime threads
  the chain in registration order; the final `:request` is what the
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

  - `:before` chain — registration order. A registered before B: request
    flows A.before → B.before → transport.
  - `:after`  chain — REVERSE registration order. A registered before B:
    response flows transport → B.after → A.after → reply dispatch.

  This mirrors the event-interceptor onion (Spec 002): the outermost
  registration wraps the innermost on the request side and again on the
  response side. Interceptors with no `:after` are transparent in the
  response chain (skipped, not nil-substituted).

  ## Storage

  Per-frame in a `defonce` atom keyed `frame-id → [interceptor ...]`,
  mirroring the per-frame flow registry pattern (see
  `re-frame.flows.registry`'s private `flows` atom + the
  `flows-snapshot` accessor). Frame-scoped: an interceptor registered
  against frame A does not fire for a request dispatched from frame B."
  (:require [re-frame.error        :as rf.error]
            [re-frame.frame        :as rf.frame]
            [re-frame.http.encoding :as rf.http.encoding]
            [re-frame.http.privacy :as rf.http.privacy]
            [re-frame.interop      :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.trace        :as rf.trace]))

(defonce
  ^{:doc "frame-id → vector of `:rf/http-interceptor-meta` slots — each a map
  carrying `:id`, optional `:before`, optional `:after`, `:frame`, and the
  captured registration-metadata (`:doc`, `:schema`, `:tags`, `:sensitive?`,
  flat source-coord keys `:ns`/`:line`/`:column`/`:file`). Per-frame so
  each frame's HTTP middleware chain is isolated. Order is
  registration-order; clearing an id and re-registering re-appends to the
  end."}
  interceptors
  (atom {}))

(defn- valid-args?
  "Validate the positional id and interceptor map. The map must carry at least one of
  `:before` / `:after` (each, if present, must be a fn), and
  if `:frame` is present it must be a keyword."
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
           (some? (:after  interceptor-map)))
       (or (nil? (:frame interceptor-map))
           (keyword? (:frame interceptor-map)))))

(defn reg-http-interceptor
  "Register an HTTP interceptor on a frame's `:rf.http/managed` middleware
  chain. Per Spec 014 §Middleware.

  Signature: `(reg-http-interceptor id interceptor-map)` — `id` is a
  keyword; `interceptor-map` carries:

    - `:before` (optional) — `(fn [ctx] ctx')`, request-side transform
    - `:after`  (optional) — `(fn [ctx response] response')`,
                              response-side transform
    - `:frame`  (optional) — frame id (the EP-0002 *override*). Absent,
                              the carried-invariant scope chain resolves it;
                              registering under no scope raises
                              `:rf.error/no-frame-context`.
    - `:doc` / `:tags` / `:schema` / `:sensitive?` — standard
      `:rf/registration-metadata` (per `:rf/http-interceptor-meta`)

  At least one of `:before` / `:after` MUST be supplied — a no-op
  interceptor is rejected.

  Example:
    (reg-http-interceptor :auth
      {:doc \"Stamp Bearer token.\"
       :before (fn [ctx] ...)
       :after  (fn [ctx response] ...)})

  The `:before` chain runs in REGISTRATION ORDER before the request
  fires. The `:after` chain runs in REVERSE REGISTRATION ORDER after
  the response is built, BEFORE `:on-success` / `:on-failure` are
  dispatched. `:after` sees the SAME ctx the `:before` chain produced
  (enables request-correlated telemetry).

  `ctx` carries `:request` (the request map), `:args` (the full
  `:rf.http/managed` args), `:frame` (the frame-id), and `:event` (the
  originating event vector). `:before` returns a (possibly-modified)
  ctx. `:after` receives the ctx unchanged plus the canonical reply
  envelope (`{:status :ok :value v …}` or `{:status :error :error f …}`,
  rf2-ibksxg) and returns the (possibly-transformed) response.

  Source-coords (`:ns` / `:line` / `:column` / `:file`) are auto-captured
  at the `rf/reg-http-interceptor` call site by the JVM-emitted macro in
  `re-frame.core` (per Spec 001 §Source-coordinate capture). The stored
  slot conforms to `:rf/http-interceptor-meta` (Spec-Schemas).

  Re-registering an id replaces the slot in place (keeping registration
  order). Order is preserved across replace; first registration wins
  for position.

  After `clear-http-interceptor` removes a slot, a subsequent
  `reg-http-interceptor` of the same id is a fresh registration and
  appends to the end of the chain — the prior position is forgotten on
  clear (per Spec 014 §Chain order and frame scope, rf2-kg5nw).

  Throws `:rf.error/http-bad-interceptor` if any arg shape is invalid.

  Returns the registered `id`."
  [id interceptor-map]
  (when-not (valid-args? id interceptor-map)
    (rf.error/throw-error!
      :rf.error/http-bad-interceptor 'rf/reg-http-interceptor
      "expected (reg-http-interceptor id interceptor-map): id keyword; interceptor-map a map carrying at least one of :before / :after (each a fn), optional :frame keyword, optional :rf/registration-metadata"
      {:extra {:received {:id id :interceptor-map interceptor-map}}}))
  (let [frame-id  (or (:frame interceptor-map)
                      ;; EP-0002 — HTTP interceptor registration is
                      ;; CONTEXT-REQUIRED FRAME-LOCAL: the explicit `:frame`
                      ;; (the *override*) wins, else the carried-invariant
                      ;; scope chain (a `with-frame` scope, a `frame-provider`
                      ;; (SCOPE) or `frame-root` (ENSURE) boundary,
                      ;; or a frame `:initial-events` step). Registering under no
                      ;; scope and no explicit `:frame` raises the always-on
                      ;; `:rf.error/no-frame-context` rather than installing
                      ;; the interceptor against a synthesised `:rf/default`
                      ;; chain (per Spec 002 §Frame target resolution).
                      (rf.frame/require-current-frame!
                        :reg-http-interceptor
                        {:where 'rf/reg-http-interceptor :event-id id}))
        before    (:before interceptor-map)
        after     (:after  interceptor-map)
        user-meta (dissoc interceptor-map :frame :before :after)
        slot      (cond-> (rf.source-coords/merge-coords user-meta)
                    true   (assoc :id    id
                                  :frame frame-id)
                    before (assoc :before before)
                    after  (assoc :after  after))]
    (swap! interceptors update frame-id
           (fn [chain]
             (let [chain (or chain [])
                   idx   (->> chain
                              (keep-indexed (fn [i v] (when (= (:id v) id) i)))
                              first)]
               (if idx
                 (assoc chain idx slot)
                 (conj chain slot)))))
    (when rf.interop/debug-enabled?
      (rf.trace/emit! :info :rf.http.interceptor/registered
                   {:frame frame-id
                    :id    id}))
    id))

(defn clear-http-interceptor*
  "Internal frame-first clear seam: drop the slot named `id` from `frame`'s
  chain and emit the `:rf.http.interceptor/cleared` trace when a slot actually
  existed. `frame` is a RESOLVED frame TARGET (a frame-id keyword or a live
  frame value) — normalized to its frame-id before keying the per-frame
  registry, so both spellings land on the same chain.

  This is the artefact-internal `(frame id)` seam — NOT part of the public
  `re-frame.core` surface (only `clear-http-interceptor` is late-bound to
  core). Internal cleanup that already holds a resolved frame — the
  `:rf.fx/clear-http-interceptor` fx (which carries the fx-context frame) —
  routes through here directly rather than the public opts form (rf2-s32bf).
  Both public arities also funnel through it once they have resolved a frame.
  Returns `id`."
  [frame id]
  (let [frame-id (rf.frame/frame-target->id frame)
        existed? (some? (some (fn [v] (when (= (:id v) id) v))
                              (get @interceptors frame-id)))]
    (swap! interceptors update frame-id
           (fn [chain]
             (vec (remove (fn [v] (= (:id v) id)) chain))))
    (when (and existed? rf.interop/debug-enabled?)
      (rf.trace/emit! :info :rf.http.interceptor/cleared
                   {:frame frame-id
                    :id    id}))
    id))

(def ^:private valid-clear-opts?
  "The public two-arity opts map must be EXACTLY `{:frame target}`.

  rf2-kuky.80 replaced the private copy that used to live here with the ONE
  shared validator in `re-frame.frame`, which `rf/clear` and the flows arm now
  use as well. The rule (map, sole key `:frame`, value a frame-id keyword or a
  live frame value) is unchanged; only the number of copies is. That the copy
  WAS private here is why the identically-shaped flows door still carried the
  tolerant destructure this one had already been fixed for (rf2-s32bf)."
  rf.frame/frame-opts?)

(defn clear-http-interceptor
  "Unregister an HTTP interceptor by id from a frame's chain.

  EP-0002 — context-required frame-local. The public surface is EXACT:

    (clear-http-interceptor id)                  ;; ambient scope
    (clear-http-interceptor id {:frame target})  ;; explicit frame

  The single-arity `(clear-http-interceptor id)` resolves the frame through
  the carried-invariant scope chain (a `with-frame` scope, or the closest
  enclosing frame boundary — a `frame-provider` (SCOPE) or a `frame-root`
  (ENSURE));
  under no scope it raises the always-on `:rf.error/no-frame-context` rather
  than clearing against a synthesised `:rf/default`.

  The two-arity opts form names the frame explicitly (the *override*) —
  `target` is a frame-id keyword or a live frame value. The opts map is
  FAIL-CLOSED: it must be EXACTLY `{:frame target}` with a present, non-nil
  target. A missing `:frame`, a nil target, a misspelled/unknown or extra
  key, and a non-map second argument all raise the typed
  `:rf.error/http-bad-interceptor` (mirroring `reg-http-interceptor`'s arg
  validation) BEFORE any ambient frame is resolved or touched. This closes
  the silent mis-clear the old `(or (:frame opts) ambient-frame)` resolution
  carried (rf2-s32bf): `(clear-http-interceptor id {})` or a typo'd
  `{:fram f}` used to silently clear the AMBIENT frame instead of failing.

  Two-scalar frame-first `(clear-http-interceptor frame id)` is NOT a public
  shape. Artefact-internal cleanup that already holds a resolved frame — the
  `:rf.fx/clear-http-interceptor` fx — routes through the `clear-http-interceptor*`
  seam instead. No-arg form is not supported — explicit ids only."
  ([id]
   (clear-http-interceptor*
     (rf.frame/require-current-frame!
       :clear-http-interceptor
       {:where 'rf/clear :event-id id})
     id))
  ([id opts]
   ;; rf2-s32bf — the PUBLIC two-arity is ONLY `(id {:frame target})`.
   ;; Fail-closed: reject anything that is not exactly `{:frame target}`
   ;; (present, non-nil) BEFORE resolving or touching the ambient frame. This
   ;; also rejects the old two-scalar frame-first shape — a keyword or other
   ;; scalar second arg is not a valid opts map. The explicit target names the
   ;; frame; the opts form never falls back to the ambient scope.
   (when-not (valid-clear-opts? opts)
     (rf.error/throw-error!
       :rf.error/http-bad-interceptor 'rf/clear
       "expected (clear-http-interceptor id {:frame target}): the two-arity opts map must be exactly {:frame target} with a present, non-nil frame target (a frame-id keyword or a live frame value). Two-scalar frame-first (frame id) is not a public shape."
       {:extra {:received {:id id :opts opts}}}))
   (clear-http-interceptor* (:frame opts) id)))

(defn clear-all-http-interceptors!
  "Test-time helper: drop the per-frame interceptor registry."
  []
  (reset! interceptors {})
  nil)

(defn interceptors-snapshot
  "Test-time helper: read the current value of the per-frame interceptor
  registry — `frame-id → [interceptor-slot ...]`. Each slot is the stored
  `:rf/http-interceptor-meta` map (`:id`, optional `:before` / `:after`,
  `:frame`, captured source-coords + registration-metadata).

  For tests that need to assert chain ORDER, slot CONTENTS (source-coords,
  user-meta), or per-frame ISOLATION without reaching into the `interceptors`
  atom directly. Not part of the user-facing API — application code routes
  through `reg-http-interceptor` / `clear-http-interceptor`. Mirrors the
  registry's `in-flight-snapshot` / `actor-in-flight-snapshot` test-observability
  helpers (rf2-hp772l)."
  ([] @interceptors)
  ([frame-id] (get @interceptors frame-id)))

;; rf2-jkake.9 — the `:before` (`run-interceptor-chain!`) and `:after`
;; (`run-after-chain!`) chains share one walk shape: reduce over the
;; per-frame chain, skip interceptors lacking the relevant slot, run the
;; slot fn, reject a non-map return with `:rf.error/http-interceptor-bad-
;; return`, and wrap any throw as `:rf.error/http-interceptor-failed`
;; (routed through the privacy composer + re-thrown). `run-chain*`
;; factors that body out; the two public fns are thin wrappers that vary
;; only on: the slot key (`:before` / `:after`), how the slot fn is
;; invoked (`(slot acc)` vs `(slot fixed-ctx acc)`), the chain order
;; (registration / reverse), the `:where` symbol + optional `:phase`, the
;; URL source (the threaded `acc` for `:before`; the fixed middleware-ctx
;; for `:after`), and the bad-return sentence. The load-bearing comments
;; on each wrapper preserve the per-path rationale.
(defn- run-chain*
  [{:keys [chain frame-id slot-key invoke sensitive-of where phase url-of slot-noun]} init]
  (reduce
    (fn [acc interceptor]
      (let [{:keys [id]} interceptor
            slot          (get interceptor slot-key)]
        (if slot
          (try
            (let [out (invoke slot acc)]
              (if (map? out)
                out
                ;; Canonical thrown-error shape (Spec 009 / rf2-vvixub): the
                ;; central builder derives the message from :reason + the
                ;; [:rf.error/<id>] token, so the human sentence (naming the
                ;; offending interceptor id) leads the message. The outer
                ;; wrapper carries :interceptor-id so a chain failure is
                ;; locatable via ex-data; the :id key here is kept for
                ;; programmatic consumers.
                (rf.error/throw-error!
                  :rf.error/http-interceptor-bad-return 'rf/reg-http-interceptor
                  (str "HTTP interceptor `" id "` " slot-noun ". A `:before` / "
                       ":after` HTTP interceptor (Spec 014 §Middleware) must "
                       "return a map (the threaded request / response ctx); "
                       "return the (possibly transformed) ctx, not " (pr-str out) ".")
                  {:extra {:id id :returned out}})))
            (catch #?(:clj Throwable :cljs :default) t
              (let [cause  (or (:reason (ex-data t))
                               #?(:clj  (.getMessage ^Throwable t)
                                  :cljs (.-message t)))
                    ;; Prefer the inner throw's :reason (a human sentence
                    ;; naming the offending interceptor) over the raw message.
                    reason (str "HTTP interceptor `" id "` threw while processing "
                                "the " (if phase (name phase) "request / response")
                                " chain for `" frame-id "`. Fix the interceptor's "
                                "handler so it does not throw (Spec 014 "
                                "§Middleware)."
                                (when cause (str " Cause: " cause)))
                    data   (rf.error/thrown-ex-info
                             :rf.error/http-interceptor-failed where reason
                             {:extra (cond-> {:frame          frame-id
                                              :interceptor-id id
                                              :url            (url-of acc)
                                              :cause          cause}
                                       phase (assoc :phase phase))})]
                (when rf.interop/debug-enabled?
                  ;; rf2-1jcpm — route through the privacy composer so a
                  ;; denylisted query param (`?api_key=…`) is scrubbed
                  ;; and `:sensitive?` is stamped on the trace event when
                  ;; either the handler/per-call sensitivity OR the URL's
                  ;; query string carries a denylisted param name.
                  ;;
                  ;; rf2-rznrz — `sensitive-of` recomputes the EFFECTIVE
                  ;; sensitivity from the CURRENT accumulator (the evolving
                  ;; ctx a prior `:before` may have MARKED sensitive), not a
                  ;; flag captured before the chain ran. Previously a
                  ;; `:before` that set `[:request :sensitive?] true`
                  ;; followed by a later `:before` that threw emitted this
                  ;; diagnostic with the stale non-sensitive flag, leaking
                  ;; non-denylisted query values for a now-sensitive request.
                  (rf.trace/emit-error! :rf.error/http-interceptor-failed
                                     (rf.http.privacy/prepare-emit-failure
                                       (ex-data data)
                                       (sensitive-of acc))))
                (throw data))))
          acc)))
    init
    chain))

(defn run-interceptor-chain!
  "Walk the registration-order interceptor chain for `frame-id`, threading
  `ctx` through each `:before`. Returns the final ctx, or throws
  `:rf.error/http-interceptor-failed` if any `:before` throws.

  Interceptors without a `:before` slot are transparent in the request
  chain (acc passes through unchanged).

  `ctx` carries a top-level `:sensitive?` flag (resolved by
  `managed-handler` from per-call args + handler-registration metadata)
  so the failure-path trace event redacts the request URL via the
  query-param denylist before it reaches the trace surface. Without
  this gate, an `Authorization`-token-bearing query string (e.g.
  `?access_token=…`) leaked into traces whenever an interceptor
  threw — rf2-1jcpm (round-2 security audit finding 1)."
  [frame-id ctx]
  (run-chain*
    {:chain      (get @interceptors frame-id)
     :frame-id   frame-id
     :slot-key   :before
     ;; `:before` reads the URL from the threaded accumulator — the
     ;; evolving ctx, whose `:request` an earlier `:before` may have
     ;; rewritten — so the trace carries the URL as the failing
     ;; interceptor actually saw it.
     :invoke     (fn [before acc] (before acc))
     :url-of     #(get-in % [:request :url])
     ;; rf2-rznrz — recompute effective sensitivity from the CURRENT
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
  "Per rf2-uheqq + Spec 014 §Middleware. Walk the per-frame interceptor
  chain for `frame-id` in REVERSE registration order, threading
  `response` through each `:after`. Returns the (possibly-transformed)
  response map.

  Each `:after` receives `(fn [ctx response] response')` — `ctx` is the
  middleware-ctx the `:before` chain produced for THIS request (carried
  forward by the transport so the `:after` sees the exact same shape
  the `:before` ended with). `response` is the canonical reply envelope
  (`{:status :ok :value v …}` or `{:status :error :error f …}`,
  rf2-ibksxg).

  Interceptors without an `:after` slot are transparent in the response
  chain (acc passes through unchanged). Throws by `:after` propagate
  to the caller via the same `:rf.error/http-interceptor-failed` shape
  the `:before` path uses, so a misbehaving response-side interceptor
  surfaces on the same trace event."
  [frame-id middleware-ctx response]
  (run-chain*
    {;; Reverse order — mirror of the event-interceptor onion (Spec 002).
     :chain      (reverse (get @interceptors frame-id))
     :frame-id   frame-id
     :slot-key   :after
     ;; `:after` always sees the fixed middleware-ctx the `:before` chain
     ;; produced (the response, not the ctx, is what threads through the
     ;; reduce), so the URL is read from that ctx rather than the acc.
     :invoke     (fn [after acc] (after middleware-ctx acc))
     :url-of     (fn [_acc] (get-in middleware-ctx [:request :url]))
     ;; rf2-rznrz — the `:after` chain threads the RESPONSE through `acc`;
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

(defn run-after-then-dispatch!
  "The shared reply tail: thread `reply-payload` through the per-frame
  `:after` interceptor chain, then hand the result to the late-bind
  reply router (`encoding/dispatch-reply-via-late-bind!`).

  Single source of truth for the two reply paths that were previously
  byte-identical save for the `:after`-guard:
   - `http-transport/dispatch-reply!` — the real-transport completion
     path. Synthetic / test-path callers may carry NO `:middleware-ctx`
     (they built a ctx directly without going through `managed-handler`);
     the `(if middleware-ctx …)` guard skips the `:after` chain and
     passes the payload through unchanged — the chain's contract is to
     see the `:before`'s ctx, not a synthesised one.
   - `http-test-support/dispatch-canned-reply!` — the canned-stub test
     path, which always produces a `:middleware-ctx` via
     `run-request-chain` and therefore always runs the `:after` chain.

  `opts` carries `:frame`, `:middleware-ctx`, the three keys
  `dispatch-reply-via-late-bind!` consumes (`:origin-event`,
  `:explicit-on`, `:kind`), and the EP-0010 `:completed-at` causal
  completion time threaded onto the reply dispatch's `:rf.cofx`
  (rf2-n1rh0f / rf2-alc1lf). No-op when the router is absent / the reply is silenced
  (delegated to `dispatch-reply-via-late-bind!`)."
  [{:keys [frame middleware-ctx origin-event explicit-on reply-payload kind completed-at]}]
  (let [final-payload (if middleware-ctx
                        (run-after-chain! frame middleware-ctx reply-payload)
                        reply-payload)]
    (rf.http.encoding/dispatch-reply-via-late-bind!
      {:origin-event  origin-event
       :explicit-on   explicit-on
       :reply-payload final-payload
       :kind          kind
       :completed-at  completed-at}
      frame)))
