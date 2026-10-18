(ns re-frame.http.transport
  "Shared (platform-neutral) request lifecycle for `:rf.http/managed`.

  Platform transports live in sibling adapters: `re-frame.http.transport-cljs` owns the Fetch
  path (`cljs-fetch` + `classify-cljs-error`); `re-frame.http.transport-jvm`
  owns the `java.net.http.HttpClient` path (`jvm-fetch` +
  `classify-jvm-error`) and the per-row CLJS-only-key degradation tracing
  (`check-cljs-only-keys!`). This namespace owns everything else — the
  request record's four transitions, the 4xx/5xx/2xx/else response cascade,
  the retry decision (`maybe-retry!`), reply dispatch, retry tracing, privacy
  redaction, and supersession suppression — as platform-neutral Clojure.
  `send!` is the one site that selects the platform adapter under a reader
  conditional.

  Per-row CLJS-only request keys (`:abort-signal`, `:mode`, `:cache`,
  `:referrer`, `:integrity`) are no-ops on JVM with a one-line trace
  per occurrence via `transport-jvm/check-cljs-only-keys!`.

  Per Spec 014 §Failure categories the response cascade classifies status
  codes BEFORE decode (4xx/5xx route to `:rf.http/http-4xx` /
  `:rf.http/http-5xx` with the raw body-text). Per Spec 014 §Retry and
  backoff `maybe-retry!` decides between retry and final failure from the
  failing attempt's failure category and the request's `:retry` config."
  (:require [re-frame.error             :as rf.error]
            [re-frame.http.decode       :as rf.http.decode]
            [re-frame.http.encoding     :as rf.http.encoding]
            [re-frame.http.middleware   :as rf.http.middleware]
            [re-frame.http.privacy      :as rf.http.privacy]
            [re-frame.http.privacy-body :as rf.http.privacy-body]
            [re-frame.http.registry     :as rf.http.registry]
            [re-frame.http.reply        :as rf.http.reply]
            [re-frame.http.transport-cljs :as rf.http.transport-cljs]
            [re-frame.http.transport-jvm  :as rf.http.transport-jvm]
            [re-frame.interop           :as rf.interop]
            [re-frame.late-bind         :as rf.late-bind]
            [re-frame.trace             :as rf.trace])
  #?(:clj (:import [java.util.concurrent CompletableFuture])))

;; ---- test-only interleaving seam -------------------------------------------
;;
;; On the JVM a completion runs on an HttpClient thread, a retry on the backoff
;; scheduler's thread, and an abort on the event thread, so the orderings
;; between them are reachable only by wall-clock timing. This seam lets a
;; concurrency test inject an abort (or a same-id successor) DETERMINISTICALLY
;; at a transition boundary, just before that transition takes the record's
;; guard:
;;
;;   :issue/before-send        `send!`, once the body is prepared
;;   :completed/before-commit  `completed!`, once the attempt's outcome is decided
;;   :retry/before-attempt     `fire!`, when the backoff timer fires
;;
;; A single atom deref at each point; nil in production (never set).

(defonce ^:private test-interleave-hook
  ;; A fn `(fn [point ctx])` invoked at named lifecycle points, or nil.
  (atom nil))

(defn set-test-interleave-hook!
  "Test-only: install a `(fn [point ctx])` interleaving hook, or
  clear it with nil. Not part of the user-facing API."
  [f]
  (reset! test-interleave-hook f)
  nil)

(defn- interleave!
  "Invoke the test interleaving hook for `point` if one is installed. A no-op
  (single atom deref) in production."
  [point ctx]
  (when-let [f @test-interleave-hook]
    (f point ctx))
  nil)

(def ^:private reply-suppressing-abort-reasons
  "Abort reasons whose `:rf.http/aborted` failure is NOT dispatched to the app
  `:on-failure` reply target — the cancellation replaces the original outcome,
  so delivering the old reply would race / corrupt the post-cancel state.

  `:request-id-superseded` means a fresh request with the same
  `:request-id` replaced this one. `:epoch-restored` means epoch
  restore unwound the timeline this request belonged to; per EP-0011 /
  Managed-Effects §restore (\"epoch restore MUST NOT revive host work\") a
  pre-restore completion MUST NOT deliver to its original `:rf/reply-to` target.
  `:frame-destroyed` means the request's owning frame was DESTROYED:
  the target frame is already marked destroyed, so dispatching a
  live cancellation reply into it is invalid — the late completion MUST NOT
  deliver to its original `:rf/reply-to` target either.
  All still emit their trace facts (the suppressed-attempt's stale envelope);
  only the app dispatch is suppressed.

  Cross-ref: this set gates only the REPLY. For `:request-id-superseded`
  the canonical EP-0011 stale-trace is NOT emitted here — it is emitted
  separately by `managed-handler` → `emit-superseded-stale-trace!` at
  supersede time (the `:epoch-restored` / `:frame-destroyed` siblings are
  emitted by `http-registry/abort-in-flight-for-frame!` /
  `abort-in-flight-on-frame-destroyed!`). `dispatch-aborted!` here fires only
  the `:rf.http/aborted` `:info` trace and then suppresses the reply for these
  reasons."
  #{:request-id-superseded :epoch-restored :frame-destroyed})

(defn reply-suppressing-abort-reason?
  "True when an `:rf.http/aborted` failure carrying `reason` must NOT dispatch
  its `:on-failure` reply (`reply-suppressing-abort-reasons`)."
  [reason]
  (contains? reply-suppressing-abort-reasons reason))

(defn- emit-reply-tail-error!
  "Surface a REPLY-TAIL exception — a throw AFTER the transport already
  completed, during reply delivery: an `:after` interceptor throw
  (classified `:rf.error/http-interceptor-failed`), or a malformed reply
  target the dispatch-time guard did not catch (`:rf.error/http-bad-reply-
  target`, belt-and-braces path).

  Per Spec 014 §Failure mode a response-side throw MUST NOT be reclassified
  as a transport rejection. Letting it propagate back into the platform
  completion handler would lose it: on CLJS the `.catch` would read it as a
  `:rf.http/transport` rejection of a request that has already completed, and
  on the JVM it would escape the unobserved `whenComplete` future. Either way
  nothing would say the reply never arrived. This
  boundary catches the reply-tail throw and surfaces it ONCE as
  `:rf.error/http-reply-tail-failed` — the response-side analogue of the
  request-side `:rf.error/fx-handler-exception` boundary — observably (not
  swallowed on the JVM) and WITHOUT retry.

  No teardown is performed here — only the observable emit. A reply tail
  runs after its request has completed and left the registry, and the
  completion fence ends a still-live request itself before calling this. The
  reply itself is not delivered (delivery is what threw); the emit names the
  situation and the failing interceptor /
  target so the broken `:after` / reply target is fixable. Privacy-composed
  by the failure composer, matching the sibling `:rf.http/*` error rows: the
  URL redacts under the effective `:sensitive?` flag / query-param denylist,
  and so does the string `:cause`, because it is the caught throw's message —
  an `:after`'s own text, or a bad-return sentence echoing what the `:after`
  returned — and either can carry the request's secrets.

  ALWAYS-ON, in production too. Behind an outer `interop/debug-enabled?` gate
  the emit would reach the dev trace ONLY, so in a production CLJS bundle —
  the one place a broken `:after` cannot be caught by running the tests — the
  reply would vanish with nothing left behind. Spec 014 §Failure mode promises
  the throw is surfaced \"observably\", and a dev-only surface does not keep
  that promise. The emit fans out through BOTH error substrates via the
  `:error-emit/emit-error-both` late-bind hook, exactly as core's
  `fx/emit-fx-error!` does for the request-side `:rf.error/fx-handler-exception`
  boundary this one is the response-side analogue of: axis 1 is the always-on
  listener registry (production-survivable, the off-box shipper's source of
  truth), axis 2 is the dev trace. The `debug-enabled?` gate applies to the
  dev trace from INSIDE `trace/emit-error!`. In production this path emits
  one record per reply tail that throws.

  The hook is read through `late-bind` rather than a static require because
  `emit-fx-error!` does the same for the same reason (a load cycle through the
  error-emit substrate); `nil` when the substrate namespace has not loaded, in
  which case nothing is emitted — it is a foundational always-on surface, so in
  practice it is present."
  [ctx e]
  (let [reply-error-id (:rf.error/id (ex-data e))
        origin-event   (:origin-event ctx)
        trace-tags
        (rf.http.privacy/prepare-emit-failure
          {:url            (:url ctx)
           :kind           (:kind ctx)
           :reply-error-id reply-error-id
           :cause          (rf.error/ex-message-safe e)
           :recovery       :no-recovery
           :reason         (str "A managed-HTTP reply tail threw AFTER the "
                                "transport completed"
                                (when reply-error-id
                                  (str " (" reply-error-id ")"))
                                " — reply delivery / an `:after` interceptor / "
                                "the reply target failed. The throw is NOT "
                                "classified as a transport rejection (no retry, "
                                "no re-send of the already-completed request) "
                                "and is surfaced here rather than swallowed. Fix "
                                "the `:after` interceptor or reply target so it "
                                "does not throw (Spec 014 §Middleware §Failure mode).")}
          (true? (:sensitive? ctx)))]
    ;; Both channels through the shared helper — axis 1 the always-on corpus
    ;; listener (survives CLJS `:advanced` + `goog.DEBUG=false`), axis 2 the
    ;; dev trace (DCE'd there). `elapsed-ms 0` — this is not a timed path;
    ;; `(rf.interop/now-ms)` is the emit instant. Mirrors `fx/emit-fx-error!`.
    (when-let [emit-error-both! (rf.late-bind/get-fn :error-emit/emit-error-both)]
      (emit-error-both! :rf.error/http-reply-tail-failed
                        origin-event (first origin-event) (:frame ctx) e 0
                        (rf.interop/now-ms) trace-tags)))
  nil)

(defn- dispatch-reply!
  "Threads the reply-payload through the per-frame `:after` interceptor
  chain (REVERSE registration order) BEFORE handing off to the
  late-bind router for `:on-success` / `:on-failure` dispatch. The branch
  is chosen from the FINAL envelope's `:status`, after the chain, so both
  branch targets travel to the shared tail
  (`middleware/run-after-then-dispatch!`) and the transport outcome never
  picks the target.

  Per Spec 014 §Middleware, each `:after` sees `(ctx,
  response)` — `ctx` is the SAME middleware-ctx the `:before` chain
  produced for this request (carried forward via the normalised ctx's
  `:middleware-ctx` slot, populated by `managed-handler`). The
  `:after` chain may transform the response shape; its return value is
  what `build-reply-event` appends to the user's `:on-success` /
  `:on-failure` event vector.

  The CHAIN is carried forward with the ctx, on the normalised
  ctx's `:interceptor-chain` slot (also populated by `managed-handler`,
  from a capture taken before the `:before` walk). The `:after` walk runs
  that captured chain, so a registry change landing while the request was
  in flight — or during a retry — cannot alter which interceptors see
  this response.

  When no middleware-ctx is in scope (synthetic / test-path callers that
  build a ctx directly without going through `managed-handler`), the
  `:after` chain is skipped and the reply-payload passes through
  unchanged — the chain's contract is to see the `:before`'s ctx, not a
  synthesised one.

  The `:after` chain + late-bind dispatch is the REPLY TAIL,
  and it runs AFTER the request completed (its outcome committed and the
  record retired before this fn is reached). A throw here (a
  throwing `:after`, or a belt-and-braces malformed-reply-target throw) MUST
  NOT propagate back into the platform completion handler — on CLJS the Fetch
  `.catch` would misread it as a `:rf.http/transport` rejection; on the JVM it
  would escape the unobserved `whenComplete` future and vanish (the caller
  hangs). The fence catches it and routes it to a NON-retrying,
  once-observable `:rf.error/http-reply-tail-failed` emit — never the
  transport-rejection classifier. Per Spec 014 §Failure mode."
  [{:keys [origin-event success-target failure-target
           reply-payload frame middleware-ctx interceptor-chain
           completed-at]
    :as   ctx}]
  ;; Live and canned replies share this `:after` + late-bind path. The
  ;; `:after` chain is skipped when no middleware-ctx is present
  ;; (synthetic / test-path callers).
  (try
    (rf.http.middleware/run-after-then-dispatch!
      {:frame          frame
       :middleware-ctx middleware-ctx
       ;; The chain this request captured at issue, carried on
       ;; the normalised ctx beside `:middleware-ctx` through every
       ;; attempt. The `:after` walk uses it instead of a
       ;; response-time registry deref.
       :chain          interceptor-chain
       :origin-event   origin-event
       :success-target success-target
       :failure-target failure-target
       :reply-payload  reply-payload
       ;; EP-0010 / EP-0017: the host completion time
       ;; rides the reply dispatch's `:rf.cofx` `:rf/time-ms` so a reply reducer
       ;; reads it as causal data, never a fresh clock.
       :completed-at   completed-at})
    ;; Reply-tail fence. Post-transport-success throw → a
    ;; non-retrying, observable `:rf.error/http-reply-tail-failed`, NOT the
    ;; transport-rejection classifier (no CLJS retry-storm, no JVM swallow).
    (catch #?(:clj Throwable :cljs :default) e
      (emit-reply-tail-error! ctx e))))

;; Every completion (success, failure, or abort) flows through one canonical
;; reply map built in `re-frame.http.reply` — `:status` (`:ok` / `:error` /
;; `:cancelled`), `:value` on `:ok`, the classified `:rf.http/*` failure map
;; verbatim under `:error`, `:rf.reply/work-id` `[:rf.work/http issuance
;; attempt]`, `:rf.reply/work-kind :http`, `:rf.reply/work-status`, `:attempt`,
;; `:rf.frame/id`, `:completed-at`, and `:correlation {:request-id …}` (the
;; `:request-id` is correlation metadata, NOT a second stale-suppression key).
;; A live-transport SUCCESS additionally carries the response wire facts under
;; `:meta` — `{:status :status-text :headers}` as the transport normalized
;; them, so `:after` middleware and the app target can read the
;; actual status/headers on success as the failure paths already do on `:error`.
;; `reply-ctx` below supplies that map's correlation and identity facts; the
;; completion itself supplies `:value`, `:error` and `:meta`.
;; The same canonical reply is delivered to the app target verbatim:
;; `rf.http.encoding/build-reply-event` appends it as the last argument of the
;; target event vector. `:reply-to` addresses both branches, and
;; `:on-success` / `:on-failure` are pure ROUTING sugar, supplied as a pair,
;; addressing one branch each (an explicit `nil` silences that branch). Every
;; form receives the same canonical map, so no addressing form selects a second
;; payload dialect. A request that leaves a branch unaddressed never reaches
;; this path: `handlers/validate-reply-target!` refuses it at dispatch, with
;; `:rf.error/http-no-reply-target` when nothing is addressed and
;; `:rf.error/http-bad-reply-target` when one split key lacks its partner.

(defn- reply-ctx
  "Project the transport ctx onto the data-only correlation/identity facts
  `re-frame.http.reply` consumes to build a canonical reply map. Reads the
  host completion clock ONCE here, at finalisation, and threads it as
  `:completed-at` (the reply handler MUST NOT re-read the clock —
  Managed-Effects §Causal completion metadata).

  `:completed-at` is durable causal time: it
  flows through `:rf.cofx` `:rf/time-ms` into resource `:loaded-at` /
  `:stale-at` and mutation `:settled-at`, which freshness readers compare
  against `js/Date.now`. It MUST therefore be wall-clock epoch ms
  (`epoch-now-ms`), NOT `now-ms` — on CLJS `now-ms` is `performance.now()`
  (origin-relative), so a `now-ms`-stamped `:stale-at` reads stale
  immediately against `js/Date`. This transport boundary is the single
  host-clock read for completion."
  [ctx]
  {:request-id   (:request-id ctx)
   :origin-event (:origin-event ctx)
   ;; The frame's issuance number rides into the work-id, so no two
   ;; requests the frame issued share one.
   :issuance     (:issuance ctx)
   :attempt      (:attempt ctx)
   :frame        (:frame ctx)
   :completed-at (rf.interop/epoch-now-ms)})

(defn- self-identify
  "Stamp the four self-identifying fields (`:request
  {:method :url}`, `:request-id`, `:attempt` / `:max-attempts`, `:work/id`)
  onto a classified `:rf.http/*` failure map from the request ctx, so the
  PUBLIC failure reply (and the trace that inherits it) names WHICH request
  failed, not just what kind of failure it was.

  Delegates the field construction to `http-reply/self-identify-failure`;
  this fn projects the transport `ctx` onto the identity map that helper
  consumes (the effective `:method` off the `:request` envelope, `:get` when
  the request set none, `:max-attempts` off the `:retry` policy, plus `:url` /
  `:request-id` / `:origin-event` / `:issuance` / `:attempt`). Applies
  uniformly to ALL seven failure categories."
  [failure ctx]
  (rf.http.reply/self-identify-failure
    failure
    {:method       (or (:method (:request ctx)) :get)
     :url          (:url ctx)
     :request-id   (:request-id ctx)
     :origin-event (:origin-event ctx)
     :issuance     (:issuance ctx)
     :attempt      (:attempt ctx)
     :max-attempts (:max-attempts (:retry ctx))}))

;; `dispatch-failure!` and `dispatch-success!` below spell the failure-reply
;; and success-reply dispatch shapes once for a completed request's reply and
;; the abort path's `dispatch-aborted!`, which makes the abort/natural
;; symmetry visible in code.

(defn- emit-reply-trace!
  "Emit a managed-async completion trace row built from the
  CANONICAL reply-envelope facts (Managed-Effects §Tracing), not the
  private callback payload. The wire-bearing slots (`:value` / `:error` /
  `:correlation`) route through the shared `http-reply/trace-reply` →
  `re-frame.reply/trace-summary` → `re-frame.elision/elide-wire-value`
  walker (never a family-private elider); the identity facts (`:status`,
  `:rf.reply/work-id`, `:rf.reply/work-kind`, `:rf.reply/work-status`, `:attempt`, `:rf.frame/id`,
  `:completed-at`) ride verbatim. Gated on `debug-enabled?` like the other
  `:rf.http/*` trace rows."
  [ctx reply]
  (when rf.interop/debug-enabled?
    ;; The success reply's decoded body is classified through its schema
    ;; before it reaches the trace stream. It is a registration-
    ;; owned transient payload classified per-slot via `:sensitive?` props on
    ;; the request's `:decode` SCHEMA (the EP-0005 mechanism). Apply those
    ;; per-slot marks to the value BEFORE it rides the trace, so a body slot
    ;; the owner's `:decode` schema marks sensitive (`[:token]`) redacts even
    ;; when the request was NOT declared per-call `:sensitive?`. A non-schema
    ;; `:decode` (`:auto` / `:json` / `:text` / binary / custom fn) is a
    ;; no-op here — its body is governed by the per-call `:sensitive?` flag on
    ;; the dev trace (and by the off-box fail-closed disposition for captures).
    ;; Only the success status carries a DECODED body; failure / cancel carry
    ;; the classified failure map at `:error`, which gets its own family
    ;; redaction below rather than this schema pass — its body is
    ;; raw and unschematized by construction. The schema's per-slot marks cover
    ;; `:sensitive?` (→ `:rf/redacted`) and `:large?` (→ `:rf.size/large-elided`)
    ;; through the shared marks walker. They are paths into the decoded body
    ;; (`:decoded` on the ctx), so they apply per-slot only when the success
    ;; `:value` IS that body; a value an `:accept` reshaped is unschematized
    ;; (`classify-success-value`).
    (let [reply' (if (and (= :ok (:status reply))
                          (contains? reply :value)
                          (rf.http.privacy-body/schema-decode? (:decode ctx)))
                   (update reply :value rf.http.privacy-body/classify-success-value
                           (:decoded ctx) (:decode ctx))
                   reply)
          ;; The success reply's `:meta` carries the response
          ;; wire facts (status / status-text / normalized headers). The
          ;; DELIVERED reply rides raw (on-box app data — the caller's own
          ;; response); the trace surface redacts every header whose name
          ;; is in the merged denylist (immutable built-in defaults ∪ the
          ;; app-declared `:carriers {:headers [..]}` extension) BEFORE the
          ;; summary walk, matching the failure-map `:headers` posture.
          ;; Per-call `:sensitive?` then force-redacts the whole `:meta`
          ;; wire slot via `trace-reply` below, as for every wire slot.
          reply' (rf.http.privacy/redact-response-meta reply')
          ;; An `:error` / `:cancelled` reply seats the
          ;; classified `:rf.http/*` failure map VERBATIM under `:error`
          ;; (`http-reply/failure-reply`), so WITHOUT this step a 4xx/5xx
          ;; would put its response `:headers` — `set-cookie`, `www-authenticate`
          ;; — on this trace row unredacted, on an ordinary request that
          ;; never asked for `:sensitive?`. Spec 014 §Privacy rule 1
          ;; redacts denylisted headers "regardless of the effective
          ;; `:sensitive?` flag", and §Response-meta states this row's
          ;; `:headers` posture matches the failure map's — so the failure
          ;; map runs through the same family redactor every OTHER
          ;; `:rf.http/*` emit site already uses (`prepare-emit-failure`
          ;; for `emit-error!`, `prepare-emit-tags` for `retry-attempt`).
          ;; It also scrubs denylisted query-param values in the failure's
          ;; `:url` and, when the request IS `:sensitive?`, its body slots.
          ;; The DELIVERED reply is untouched — `dispatch-failure!` hands
          ;; the app its own raw `reply`; this redaction is trace-only.
          failure (:error reply)
          reply' (if failure
                   (update reply' :error
                           rf.http.privacy/redact-failure
                           (true? (:sensitive? ctx))
                           (rf.http.privacy/managed-carriers))
                   reply')]
      ;; Thread the CARRIED frame into the elider opts (EP-0002 — wire-egress
      ;; frame resolves from the carried stamp; HTTP completions fire from the
      ;; transport callback, OUTSIDE any `with-frame` scope, so without an
      ;; explicit `:frame` the elider fails closed and redacts every wire slot).
      ;; The per-call `:sensitive?` flag (Spec 014 §Privacy) is forwarded so a
      ;; sensitive request redacts its payload slots wholesale, matching the
      ;; `:rf.http/*` trace posture.
      ;;
      ;; Off-box egress fails closed. The
      ;; on-box `:value` above is the dev-operator view (an unschematized body
      ;; rides raw — the local operator sees their own process). For OFF-BOX
      ;; egress an unschematized body is whole-sensitive and MUST be omitted.
      ;; The request's `:decode` is request-private — it never rides the trace
      ;; event — so we STAMP the off-box disposition forward under
      ;; `:rf.http/off-box-body`; the off-box trace-events projector
      ;; (`re-frame.epoch.tool-pair`) consults it and omits / classifies the
      ;; body slot. BOTH statuses carry a body slot to gate: success at
      ;; `:value`, failure at `[:error :body]` / `[:error :body-text]` /
      ;; `[:error :decoded]`. A success `:value` an `:accept` reshaped is
      ;; unschematized, so it is `:omit` whatever the `:decode`.
      (rf.trace/emit! :info :rf.http/replied
                   (cond-> (rf.http.reply/trace-reply reply' (cond-> {:sensitive? (true? (:sensitive? ctx))}
                                                            (:frame ctx) (assoc :frame (:frame ctx))))
                     (= :ok (:status reply))
                     (assoc :rf.http/off-box-body
                            (rf.http.privacy-body/success-value-disposition
                              (:value reply) (:decoded ctx) (:decode ctx)))

                     ;; The FAILURE arm of the same
                     ;; off-box fail-closed rule (Spec 014 §Response-body
                     ;; classification Rule 4). A failure reply's body rides
                     ;; nested under `:error`, so the slot paths tool-pair omits
                     ;; are `[:error :decoded]` / `[:error :body]` /
                     ;; `[:error :body-text]`. This mirrors the stamp the
                     ;; `emit-error!` site already applies to the SAME failure
                     ;; map, including its ordering: `:decoded` (an
                     ;; `:rf.http/accept-failure`'s pre-`:accept` body) rides the
                     ;; schema disposition, while a RAW error body is
                     ;; unconditionally `:omit` — unschematized by construction,
                     ;; since status classification runs before decode — and so
                     ;; takes precedence when both are somehow present.
                     (contains? failure :decoded)
                     (assoc :rf.http/off-box-body
                            (rf.http.privacy-body/off-box-body-disposition (:decode ctx)))

                     (or (contains? failure :body)
                         (contains? failure :body-text))
                     (assoc :rf.http/off-box-body :omit))))))

(defn emit-superseded-stale-trace!
  "Emit the canonical `:status :stale` /
  `:rf.reply/work-status :suppressed` reply-envelope trace for a SUPERSEDED HTTP
  attempt, WITHOUT dispatching any app target (Managed-Effects §Stale
  suppression — clauses 2/3/4: the superseded attempt's reply outcome becomes
  `:stale`, its ledger row reaches `:suppressed`, and the trace stream records
  the carried + current correlation).

  Called from `managed-handler` when a fresh request supersedes a prior
  in-flight one with the same `:request-id`. `superseded` is the OLD
  request's record (carrying its `:request-id` / `:origin-event` / `:issuance`
  / `:attempt` / `:frame` identity facts, published at issuance);
  `current-work-id` is the SUPERSEDING attempt's work-id (the new live
  `:work/id`). The carried (superseded) and current (superseding) work ids are
  `=`-distinct because each took its own issuance number — so tooling
  and conformance can tell the suppressed attempt from its replacement by
  `:work/id` (the EP-0011 single-attempt-identity rule).

  The wire-bearing slots route through the shared
  `http-reply/trace-reply` → `re-frame.reply/trace-summary` →
  `re-frame.elision/elide-wire-value` walker; the carried/current correlation
  rides as `:rf.reply/carried` / `:rf.reply/current` (the shared
  `re-frame.reply/suppress` trace facts) so Xray's reply-envelope view joins
  it as a uniform stale-suppression row. Gated on `debug-enabled?` like the
  other `:rf.http/*` trace rows."
  [superseded current-work-id]
  (when rf.interop/debug-enabled?
    (let [stale-ctx {:request-id   (:request-id superseded)
                     :origin-event (:origin-event superseded)
                     :issuance     (:issuance superseded)
                     :attempt      (:attempt superseded)
                     :frame        (:frame superseded)}
          {:keys [reply trace]} (rf.http.reply/suppress stale-ctx current-work-id)
          summary (rf.http.reply/trace-reply
                    reply
                    (cond-> {:sensitive? (true? (:sensitive? superseded))}
                      (:frame superseded) (assoc :frame (:frame superseded))))]
      (rf.trace/emit! :info :rf.http/stale-suppressed
                   (cond-> {:rf.reply/status       (:status summary)
                            :rf.reply/work-status   (:rf.reply/work-status summary)
                            :rf.reply/stale-reason  (:rf.reply/stale-reason summary)
                            :rf.reply/work-id       (:rf.reply/work-id summary)
                            :rf.reply/work-kind              :http
                            ;; the shared carried/current correlation facts
                            ;; the `re-frame.reply/suppress` trace computes —
                            ;; carried = superseded work-id, current =
                            ;; superseding work-id (Managed-Effects §Tracing).
                            :rf.reply/carried       (:rf.reply/carried trace)
                            :rf.reply/current       (:rf.reply/current trace)
                            :recovery               :superseded-by-fresh-request}
                     (:frame superseded) (assoc :frame (:frame superseded)))))))

(defn- dispatch-failure!
  "Dispatch a `:failure` reply carrying `failure` as its `:failure` slot.

  The `:rf.http/*` failure map lowers through the canonical reply envelope as
  `:status :error`
  (or `:status :cancelled` for an abort, `:rf.reply/work-status :timed-out` for a
  timeout) canonical reply (`http-reply/failure-reply`), a completion trace
  row is emitted from those canonical facts, and the SAME canonical reply
  threads through the `:after` chain to the late-bind dispatch. The final
  envelope's `:status` picks the target, so an `:after` that turns the
  failure into `:ok` delivers it to the success target."
  [ctx failure]
  (let [reply (rf.http.reply/failure-reply (reply-ctx ctx) failure)]
    (emit-reply-trace! ctx reply)
    (dispatch-reply! (assoc ctx
                            :kind          :failure
                            :reply-payload reply
                            :completed-at  (:completed-at reply)))))

(defn- dispatch-success!
  "Dispatch a `:success` reply carrying `value` as its `:value` slot.

  `value` lowers through the canonical reply envelope as `:status :ok` /
  `:rf.reply/work-status
  :completed` canonical reply (`http-reply/success-reply`), a completion
  trace row is emitted from those canonical facts, and the SAME canonical
  reply threads through the `:after` chain to the late-bind dispatch, whose
  final `:status` picks the target.

  The ctx's `:response-meta` (the successful response's
  actual `:status` / `:status-text` / normalized `:headers`, threaded from
  `handle-response!`'s 2xx branch) rides the canonical reply under `:meta`
  so both the `:after` chain and the app reply target can read it."
  [ctx value]
  (let [reply (rf.http.reply/success-reply (reply-ctx ctx) value (:response-meta ctx))]
    (emit-reply-trace! ctx reply)
    (dispatch-reply! (assoc ctx
                            :kind          :success
                            :reply-payload reply
                            :completed-at  (:completed-at reply)))))

(defn emit-actor-destroy-stale-trace!
  "Emit the canonical `:status :stale` /
  `:rf.reply/work-status :suppressed` reply-envelope trace for an actor-destroy abort
  whose reply target is OBSOLETE (it addressed the destroyed actor itself),
  WITHOUT dispatching the app target (Managed-Effects §Cancellation /
  §Stale suppression — clauses 1/2/3/4: the obsolete actor-bound target's
  reply outcome becomes `:stale`, its ledger row reaches `:suppressed`, and
  the trace stream records the carried correlation).

  Mirrors `emit-superseded-stale-trace!` but for the actor-teardown
  obsolescence case: the carried correlation is the aborted attempt's own
  work-id; there is no live successor (`:current` is nil — the actor that
  owned the target is gone). The wire-bearing slots route through the shared
  `http-reply/trace-reply` → `re-frame.reply/trace-summary` →
  `re-frame.elision/elide-wire-value` walker. Gated on `debug-enabled?` like
  the other `:rf.http/*` trace rows."
  [ctx]
  (when rf.interop/debug-enabled?
    (let [{:keys [reply trace]} (rf.http.reply/actor-destroy-suppress ctx)
          summary (rf.http.reply/trace-reply
                    reply
                    (cond-> {:sensitive? (true? (:sensitive? ctx))}
                      (:frame ctx) (assoc :frame (:frame ctx))))]
      (rf.trace/emit! :info :rf.http/stale-suppressed
                   (cond-> {:rf.reply/status       (:status summary)
                            :rf.reply/work-status   (:rf.reply/work-status summary)
                            :rf.reply/stale-reason  (:rf.reply/stale-reason summary)
                            :rf.reply/work-id       (:rf.reply/work-id summary)
                            :rf.reply/work-kind              :http
                            :rf.reply/carried       (:rf.reply/carried trace)
                            :rf.reply/current       (:rf.reply/current trace)
                            :recovery               :actor-destroyed-target-obsolete}
                     (:frame ctx) (assoc :frame (:frame ctx)))))))

(defn- reply-target-id
  "The head (event-id) of the reply event an abort would dispatch: the
  failure target's head when it is an event vector, else the originating
  event-id (`resolve-origin-event`'s head). Used to decide whether an
  actor-destroy abort's reply target is obsolete."
  [ctx]
  (let [target (:failure-target ctx)]
    (if (vector? target)
      (first target)
      (first (:origin-event ctx)))))

(defn- emit-failure-trace!
  "Emit the trace row for a classified `:rf.http/*` failure of `kind`. The
  failure KIND picks the `:op-type`, never the `:reason`. An abort
  (`:rf.http/aborted`, whatever its reason) is `:info`. Every abort reason names
  a deliberate act: a cancel, a supersession, an actor or frame teardown, an
  epoch restore, or a resource refetch. The reply protocol already delivers an
  abort as `:status :cancelled`, not `:status :error`. Every other kind is a real
  failure and keeps `emit-error!`. `redacted` is the privacy-prepared failure
  map. Its `:recovery` rides the `:info` row too, because `build-event` hoists
  a supplied `:recovery` on either branch. Callers gate on `debug-enabled?`."
  [kind redacted]
  (if (= :rf.http/aborted kind)
    (rf.trace/emit! :info :rf.http/aborted redacted)
    (rf.trace/emit-error! kind redacted)))

(defn- dispatch-aborted!
  "Emit the `:rf.http/aborted` trace + dispatch the abort reply for a
  cancelled request, honouring supersession and obsolete actor-target
  suppression.

  Reached from `abort!` alone, once the abort has committed, cancelled the
  current phase's host work and retired the request — so an aborted request
  looks identical to consumers whichever phase it was in: preparing, in
  flight, or sleeping in its backoff. `reason` is `:user` /
  `:actor-destroyed` / `:request-id-superseded` / `:epoch-restored` /
  `:frame-destroyed` / `:resource-superseded` / `:test-reset`. Every one
  emits its trace row at `:info` (`emit-failure-trace!`). A `:timeout` is NOT
  an abort: it classifies to `:rf.http/timeout` (a failure kind) and routes
  through `maybe-retry!`, never here. `ctx` must carry `:request-id`,
  `:actor-id`, `:url`, `:sensitive?` and the current `:attempt`.

  Per Managed-Effects §Cancellation, an `:actor-destroyed`
  abort whose reply target is OBSOLETE — its event-id names the destroyed
  actor itself (the machine-shape wrapper's `[self-id [:rf.http/failed]]`
  default, or any request whose default reply addresses its own actor) — does
  NOT deliver a live `:cancelled`/failure reply; it lowers to the canonical
  `:status :stale` / `:rf.reply/work-status :suppressed` outcome (the `:rf.http/aborted`
  trace still fires; only the app reply delivery is suppressed). An
  `:actor-destroyed` abort whose target is an ORDINARY (still-meaningful)
  event keeps the live failure delivery, as do explicit `:user` aborts."
  [ctx reason]
  ;; The aborted failure is self-identifying too (`:request` /
  ;; `:request-id` / `:attempt` / `:max-attempts` / `:work/id`), so both the
  ;; `:rf.http/aborted` trace and the delivered `:status :cancelled` reply name
  ;; which request was cancelled. `:rf.http/aborted` is the eighth category.
  (let [failure (self-identify {:kind     :rf.http/aborted
                                :reason   reason
                                :actor-id (:actor-id ctx)}
                               ctx)]
    (when rf.interop/debug-enabled?
      (let [sensitive? (true? (:sensitive? ctx))
            redacted   (rf.http.privacy/prepare-emit-failure
                         (assoc failure
                                :url      (:url ctx)
                                :recovery :no-recovery)
                         sensitive?)]
        (emit-failure-trace! :rf.http/aborted redacted)))
    (cond
      ;; Supersede and epoch-restore reasons
      ;; suppress the reply (the cancellation replaces the original outcome; the
      ;; superseded attempt's canonical stale trace is emitted by
      ;; `emit-superseded-stale-trace!` at supersede time, the restore-suppressed
      ;; attempt's by `http-registry/abort-in-flight-for-frame!`).
      (reply-suppressing-abort-reason? reason)
      nil

      ;; An actor-destroy abort whose reply target is
      ;; OBSOLETE (it addresses the destroyed actor itself) suppresses the app
      ;; delivery as a canonical `:status :stale` / `:rf.reply/work-status :suppressed`
      ;; outcome (Managed-Effects §Cancellation). The app target MUST NOT run.
      (and (= :actor-destroyed reason)
           (rf.http.reply/actor-destroy-target-obsolete?
             (reply-target-id ctx) (:actor-id ctx)))
      (emit-actor-destroy-stale-trace! (reply-ctx ctx))

      ;; Other abort reasons (`:user`) and actor-destroy aborts whose
      ;; target is still meaningful (an ordinary event) deliver the
      ;; failure reply normally as a live `:status :cancelled`.
      :else
      (dispatch-failure! ctx failure))))

(defn- emit-and-dispatch-failure!
  "The failure reply's tail: privacy composition, trace emission, then the
  reply dispatch. Reached once a failure outcome has committed, so the
  request is already done and out of the registry.

  The failure map is made self-identifying (`:request
  {:method :url}` / `:request-id` / `:attempt` / `:max-attempts` /
  `:work/id`) here, ONCE, so both the emitted trace AND the dispatched
  reply carry the identity — the trace inherits it for free and the app
  reply names which request failed."
  [ctx failure]
  (let [failure (self-identify failure ctx)]
  (when rf.interop/debug-enabled?
    ;; Redact response-side payload slots (body, body-text,
    ;; decoded, detail) and the headers denylist before the trace surface
    ;; sees them; stamp :sensitive? when applicable. The CLJS and JVM
    ;; transports share the same contract.
    (let [sensitive? (true? (:sensitive? ctx))
          redacted   (rf.http.privacy/prepare-emit-failure
                       (assoc failure
                              :request-id (:request-id ctx)
                              :url        (:url ctx)
                              :recovery   :no-recovery)
                       sensitive?)
          ;; OFF-BOX FAIL-CLOSED (EP-0015 disposition 5). An
          ;; `:rf.http/accept-failure` carries the pre-`:accept` decoded body
          ;; at `:decoded` — same off-box rule as the success `:value`: an
          ;; unschematized body is whole-sensitive and omitted off-box. Stamp
          ;; the off-box disposition forward (`:decode` is request-private and
          ;; never on the trace event) so the off-box trace-events projector
          ;; omits / classifies the `:decoded` slot. Only failures carrying a
          ;; body slot are gated.
          ;;
          ;; The SAME disposition-5 fail-closed rule for the
          ;; RAW error-response body: an `:rf.http/http-4xx` / `:rf.http/http-5xx`
          ;; carries the raw `:body` (response body-text), and an
          ;; `:rf.http/decode-failure` carries the raw `:body-text`. By
          ;; construction these bodies are UNSCHEMATIZED — status classification
          ;; (4xx/5xx) runs BEFORE decode, and a `:rf.http/decode-failure` is the
          ;; decode step itself failing — so the request's `:decode` schema was
          ;; never (and could never be) applied to them. The off-box rule is
          ;; therefore UNCONDITIONALLY `:omit` for a raw error body, irrespective
          ;; of the per-call `:sensitive?` flag (error bodies frequently echo
          ;; request context / tokens). On-box ring stays raw; the off-box
          ;; trace-events projector omits the slot, lifted only by the
          ;; trusted-local `:rf.egress/include-sensitive?` opt-in.
          redacted   (cond-> redacted
                       (contains? failure :decoded)
                       (assoc :rf.http/off-box-body
                              (rf.http.privacy-body/off-box-body-disposition (:decode ctx)))

                       (or (contains? failure :body)
                           (contains? failure :body-text))
                       (assoc :rf.http/off-box-body :omit))]
      (emit-failure-trace! (:kind failure) redacted)))
  (dispatch-failure! ctx failure)))

(defn- emit-retry-attempt!
  "Emit one arm of the `:rf.http/retry-attempt` info trace.
  The two honest arms are discriminated by `next-backoff-ms` / `recovery`:

    - an intermediate attempt that is ACTUALLY starting (a real retry) →
      the backoff that governed it (`next-backoff-ms` non-nil) + `:retried`;
    - the terminal retry-sequence STOP marker (`next-backoff-ms` nil) +
      `:no-recovery` — the sequence stopped and nothing further is
      scheduled, either because the retry budget was spent or because a
      later attempt failed with a category outside `:retry :on`.

  `:recovery` is hoisted to the top level of the `:info` event by
  `trace/build-event` only when the producer supplies it; an absent tag
  would leave consumers reading nil. Callers gate on `interop/debug-enabled?`
  (the production-elision seam) and, for the terminal arm, on the
  real-retry-sequence guard.

  The nested intermediate `:failure` may carry a raw
  error `:body` / `:body-text` (a retryable `:rf.http/http-4xx` /
  `:rf.http/http-5xx`); stamp the off-box `:omit` disposition forward
  (the raw error body is unschematized by construction — fail-closed) so
  the off-box trace-events projector omits the nested `[:failure :body]`
  slot off-box."
  [ctx failure next-backoff-ms recovery]
  (rf.trace/emit! :info :rf.http/retry-attempt
               (cond-> (rf.http.privacy/prepare-emit-tags
                         {:request-id      (:request-id ctx)
                          :url             (:url ctx)
                          :attempt         (:attempt ctx)
                          :max-attempts    (get-in ctx [:retry :max-attempts])
                          :failure         failure
                          :next-backoff-ms next-backoff-ms
                          :recovery        recovery}
                         (true? (:sensitive? ctx)))
                 (or (contains? failure :body)
                     (contains? failure :body-text))
                 (assoc :rf.http/off-box-body :omit))))

;; ---- the request record -----------------------------------------------------
;;
;; One managed request is ONE record for its whole life — published once at
;; issuance into `re-frame.http.registry`, with every key it will carry — and
;; it changes only through four transitions:
;;
;;   send!       :preparing → :in-flight     the host send, its cancel published
;;   completed!  :preparing | :in-flight (attempt n)
;;                 → :backoff (a retry) or :done (the reply)
;;   fire!       :backoff → :preparing        attempt n+1, then send!
;;   abort!      any phase but :done → :done  cancel, then reply or suppress
;;
;; Each transition decides outside the record's guard and commits inside it.
;; The guard is the record's monitor on the JVM, where the HttpClient
;; completion thread and the backoff scheduler thread race the event thread;
;; CLJS is single-threaded, so there a commit is a plain `swap!`. User code —
;; `:decode`, `:accept`, `:after`, the reply dispatch, a body thunk — never
;; runs under the guard.
;;
;; Reaching `:done` retires the record (`commit!`), and only one transition
;; can reach it: whichever of `completed!` and `abort!` commits first decides
;; the request's one outcome, and the other finds it done and does nothing.
;; Every transition checks the phase AND the attempt it was started for, so a
;; callback from an earlier attempt, or one that lost to an abort, is inert.

(defn- guarded
  "Run `f` under `record`'s guard: its monitor on the JVM. CLJS is
  single-threaded, so there `f` simply runs."
  [record f]
  #?(:clj  (locking record (f))
     :cljs (f)))

(defn- retire!
  "Retire a finished request: take its record out of the registry (by its
  token, so a successor reusing its frame, request id or actor is never
  touched), and detach its external `:abort-signal` listener, so a shared
  or parent controller retains nothing. Reached only through `commit!`."
  [r]
  (rf.http.registry/clear-in-flight! (:request-id r) r)
  #?(:cljs (rf.http.transport-cljs/detach-external-abort! (:external-abort r)))
  nil)

(defn- commit!
  "Commit `(apply f value args)` as `record`'s new value — the only write a
  record takes, made by the four transitions inside its guard. A record that
  reaches `:done` is retired here, so retirement happens exactly once."
  [record f & args]
  (let [after (apply swap! record f args)]
    (when (= :done (:phase after))
      (retire! after))
    after))

(defn- current?
  "Is `r` still attempt `(:attempt ctx)`, in one of `phases`?"
  [r ctx phases]
  (and (contains? phases (:phase r))
       (= (:attempt ctx) (:attempt r))))

(defn- abort!
  "Any phase but `:done` → `:done`, then cancel the current phase's host work
  and reply (or suppress). The request's one abort entry point: every
  cancellation source — `:rf.http/managed-abort`, supersession, actor destroy,
  the frame sweeps, an external `:abort-signal` — reaches it through the
  record's `:abort-fn`.

  Abort intent is observed here, at the commit. A request already done — its
  outcome committed, or another abort first — is left alone, so two sources
  acting at once deliver one reply. The cancel read under the guard is the one
  the current phase published: on the JVM an abort that arrives while `send!`
  is inside the host call waits for the guard, then finds that call's future
  and cancels it, so the app is never told a request was cancelled and then
  has it sent. The cancel itself and the reply run after the guard is
  released."
  [record ctx reason]
  (when-let [r (guarded record
                 (fn []
                   (let [r @record]
                     (when-not (= :done (:phase r))
                       (commit! record assoc :phase :done :abort reason :cancel nil)
                       r))))]
    (when-let [cancel (:cancel r)]
      (try (cancel reason)
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    (dispatch-aborted! (assoc ctx :attempt (:attempt r)) reason)))

(defn- deliver!
  "Deliver a committed outcome's reply. Runs outside the guard: the `:after`
  chain and the reply dispatch are user code."
  [ctx {:keys [failure stop?] :as outcome}]
  (if (contains? outcome :value)
    (dispatch-success! ctx (:value outcome))
    (do
      ;; The terminal retry-sequence STOP row precedes the failure's own rows.
      (when (and stop? rf.interop/debug-enabled?)
        (emit-retry-attempt! ctx failure nil :no-recovery))
      (emit-and-dispatch-failure! ctx failure))))

(declare fire!)

(defn- completed!
  "Attempt n's outcome: `:preparing` / `:in-flight` (attempt n) → `:backoff`
  when the outcome retries, otherwise `:done`, then the reply. `outcome` is
  one of:

   - `{:value v}` — a success;
   - `{:failure f}` — a failure that ends the request (`:stop?` marks the end
     of a retry sequence);
   - `{:failure f :retry-ms ms}` — a failure that retries after `ms`;
   - `{:fault e}` — a throw the completion fence caught. A request still live
     ends without a reply, and the throw is surfaced as
     `:rf.error/http-reply-tail-failed` either way.

  The outcome is decided before this runs — status classification, `:decode`
  and `:accept` happen outside the guard — and commits only while attempt n is
  still current. An abort that committed first has already replied, so the
  late classification is dropped: abort always wins. An outcome that commits
  first is final, and a later abort finds the request done.

  A retry arms its backoff timer INSIDE the guard and publishes the timer's
  cancel in the same commit, so an abort either commits first — and no timer
  is ever armed — or finds the armed timer and clears it. On the JVM a timer
  that fires at once waits on the guard until the commit has landed."
  [record ctx outcome]
  (interleave! :completed/before-commit ctx)
  (let [{:keys [failure retry-ms fault]} outcome
        committed?
        (guarded record
          (fn []
            (when (current? @record ctx #{:preparing :in-flight})
              (if retry-ms
                (let [timer (rf.interop/set-timeout! #(fire! record ctx failure retry-ms)
                                                     retry-ms)]
                  (commit! record assoc :phase :backoff
                           :cancel (fn [_] (rf.interop/clear-timeout! timer))))
                (commit! record assoc :phase :done :cancel nil))
              true)))]
    (cond
      fault                           (emit-reply-tail-error! ctx fault)
      (and committed? (not retry-ms)) (deliver! ctx outcome))))

(defn- maybe-retry!
  "Decide whether attempt n's `failure` retries, and hand the outcome to
  `completed!`. A retry needs budget left (`:max-attempts`) and a failure kind
  the policy's `:on` names; `:rf.http/aborted` never retries.

  The decision does not settle whether the request is still live —
  `completed!`'s commit does. An abort that lands after this decision commits
  first, the retry is never armed, and the cancelled request is never
  re-issued (Spec 014 §Abort precedence).

  A failure that does not retry ends the request. When retries were part of
  its lifecycle — an attempt after the first, or a retry-eligible kind under a
  multi-attempt policy — that end is marked by the terminal
  `:rf.http/retry-attempt` STOP row (`:next-backoff-ms nil`, `:recovery
  :no-recovery`). It is NOT always exhaustion: the sequence also stops when a
  later attempt fails with a kind outside `:retry :on`. A non-retried,
  non-eligible failure on attempt 1 emits no such row — there was no retry
  sequence to stop. The honest `:retried` row for a retry that DOES go ahead
  is emitted by `fire!`, once attempt n+1 is really starting."
  [record ctx failure]
  (let [{:keys [retry attempt]}           ctx
        {:keys [on max-attempts backoff]} retry
        on-set (or on #{})
        kind   (:kind failure)]
    (completed! record ctx
                (if (and (some? max-attempts)
                         (> max-attempts attempt)
                         (contains? on-set kind)
                         (not= :rf.http/aborted kind))
                  {:failure  failure
                   :retry-ms (rf.http.encoding/compute-backoff-ms (or backoff {}) attempt)}
                  {:failure failure
                   :stop?   (and (some? max-attempts)
                                 (> max-attempts 1)
                                 (or (> attempt 1)
                                     (contains? on-set kind)))}))))

(defn- complete-fenced!
  "THE COMPLETION FENCE. Invoke `f`, the WHOLE body of a platform
  completion callback (the Fetch `.then` on CLJS, the `CompletableFuture`
  `.whenComplete` on the JVM), and catch anything it throws.

  The reply-tail fence in `dispatch-reply!` covers the BOTTOM of that
  callback — the `:after` chain and the late-bind reply dispatch. This covers
  everything ABOVE it: the 4xx/5xx/2xx cascade, response-body schema
  classification, the retry decision and the commit. An unfenced throw there
  would fail differently — and badly — on each host:

  - CLJS — the throw would reject the promise `.then` returns, so the `.catch`
    chained after it would feed `classify-cljs-error` → `:rf.http/transport` →
    `maybe-retry!`, which RE-SENDS a request whose wire outcome had already
    SUCCEEDED. The double-send presents to the caller as success: nothing
    observable says the request ran twice, and a non-idempotent endpoint is
    mutated twice.
  - JVM — the throw would escape into the `whenComplete` stage nobody holds,
    which completes that discarded future exceptionally and swallows it. No
    reply is dispatched and the request stays in the registry for ever,
    silently.

  Those are exactly the two registers Spec 014 §Failure mode already names for
  a response-side throw, which is why this routes to the SAME non-retrying,
  observable `:rf.error/http-reply-tail-failed` emit the reply-tail fence uses
  rather than minting a second category: from the app's point of view the
  situation is identical — the transport succeeded and the outcome could not be
  delivered. `:reply-error-id` carries the caught throw's own `:rf.error/id`,
  which is what tells the two apart on the wire; one reachable case is
  `:rf.error/schemas-artefact-missing`, thrown by
  `privacy-body/classify-decoded` when the request's `:decode` schema declares
  a per-slot mark and the shared walker hook is unbound.

  The throw is handed to `completed!` as a fault: a request still live ends
  there — done and retired, with NO reply — and the error is emitted either
  way. Inventing a reply would race the one a committed outcome may already
  have delivered; the app's `:on-success` / `:on-failure` does not fire, and
  the emit is what makes the failure fixable."
  [record ctx f]
  (try
    (f)
    (catch #?(:clj Throwable :cljs :default) e
      (completed! record ctx {:fault e})
      nil)))

(defn- handle-response!
  "Shared 4xx/5xx/2xx/else response cascade. `result` is the platform
  transport's normalised response map (`{:ok? :status :status-text
  :headers :body-text}`). Per Spec 014 §Failure categories: status
  classification runs BEFORE decode. 4xx/5xx route to
  `:rf.http/http-4xx` / `:rf.http/http-5xx` with the raw body-text —
  decode never fires on a non-success response, so an HTML 404 from a
  JSON endpoint classifies as `:rf.http/http-4xx` (not
  `:rf.http/decode-failure`). Decode runs only on 2xx; if that fails
  the failure category is `:rf.http/decode-failure`.

  Decode and accept are user code, so all of this runs outside the record's
  guard; the attempt's outcome commits only afterwards, in `completed!`."
  [record ctx result]
  (let [{:keys [decode accept request-id]} ctx
        {:keys [ok? status status-text headers body-text body-binary]} result]
    (cond
      (and (>= status 400) (< status 500))
      (maybe-retry! record ctx
                    {:kind        :rf.http/http-4xx
                     :status      status
                     :status-text status-text
                     :body        body-text
                     :headers     headers})

      (>= status 500)
      (maybe-retry! record ctx
                    {:kind        :rf.http/http-5xx
                     :status      status
                     :status-text status-text
                     :body        body-text
                     :headers     headers})

      ok?
      ;; DECODE and ACCEPT are SEPARATE phases (Spec 014
      ;; §Classification order steps 3 + 4). Fusing them under one try/catch
      ;; would misclassify an `:accept` throw as
      ;; `:rf.http/decode-failure` (step-4 error masquerading as step-3),
      ;; and a malformed `:accept` return (nil / a map without :ok/:failure)
      ;; would match no outcome — ending the request with NO reply, so the
      ;; caller would hang forever. So:
      ;;
      ;;   - the decode try/catch catches ONLY decoder exceptions →
      ;;     `:rf.http/decode-failure`;
      ;;   - accept runs in its OWN try/catch and its return is SHAPE-
      ;;     VALIDATED (`encoding/valid-accept-return?`); an accept throw OR
      ;;     a malformed return classifies as `:rf.http/accept-failure`
      ;;     (the closed-set canonical bad-accept category) and ALWAYS
      ;;     dispatches a reply.
      (let [decode-result
            (try
              {:decoded
               (rf.http.decode/decode-response-body
                 {:body-text        body-text
                  ;; The CLJS transport reads a native
                  ;; Blob / ArrayBuffer / FormData for binary decode
                  ;; modes and rides it here; `decode-response-body`
                  ;; returns it verbatim for `:blob` / `:array-buffer`
                  ;; / `:form-data`. Absent on the text path / on JVM.
                  :body-binary      body-binary
                  :headers          headers
                  :decode           decode
                  ;; Thread the keyword-cap from the
                  ;; normalised ctx into the decoder; nil means
                  ;; the reader uses its default.
                  :max-decoded-keys (:max-decoded-keys ctx)})}
              (catch #?(:clj Throwable :cljs :default) e
                {:decode-error e}))]
        (if-let [e (:decode-error decode-result)]
          (let [d (ex-data e)
                ;; The keyword-interning DoS cap (Spec 014
                ;; §Keyword-interning cap) throws a structured
                ;; `:rf.error/malformed-json` ex-info carrying `:cause`
                ;; (`:too-many-keys`) and `:limit` (the configured cap).
                ;; The spec (lines 145, 285, 289) mandates the overflow
                ;; surface as `:rf.http/decode-failure` with
                ;; `:reason :too-many-keys` and that `:limit`. Propagate
                ;; both onto the failure map so a caller branching on
                ;; `:reason` sees the documented shape (and a DoS-cap
                ;; overflow is programmatically distinguishable from an
                ;; ordinary JSON syntax error — `:schema-validation-failure?`
                ;; is false for both). `:reason` carries the ex-data's
                ;; `:cause` keyword; the human-readable string `:cause`
                ;; slot below is the ex-message, unchanged.
                malformed? (= :rf.error/malformed-json (:rf.error/id d))
                reason     (when malformed? (:cause d))
                limit      (when malformed? (:limit d))]
            (maybe-retry!
              record ctx
              (cond-> {:kind                       :rf.http/decode-failure
                       :body-text                  body-text
                       :cause                      #?(:clj (.getMessage ^Throwable e)
                                                      :cljs (.-message e))
                       :schema-validation-failure? (= :rf.error/http-schema-validation-failed
                                                      (:rf.error/id d))}
                ;; A bare JSON syntax error (`:rf.error/malformed-json`
                ;; with no `:cause`/`:limit`) carries no `:reason`/`:limit`
                ;; slots — only the cap-overflow shape does.
                (some? reason) (assoc :reason reason)
                (some? limit)  (assoc :limit limit))))
          ;; ---- ACCEPT phase -----------------------------------------
          ;; Decode succeeded. Run `:accept` in its OWN try/catch and
          ;; shape-validate the return. Three outcomes:
          ;;   - returns `{:ok v}` → the success outcome; `{:failure m}` →
          ;;     the `:rf.http/accept-failure` domain-failure outcome;
          ;;   - throws → `:rf.http/accept-failure` (the throw is the
          ;;     app's accept bug; misclassifying it as decode-failure
          ;;     would point telemetry at the wrong phase);
          ;;   - returns a malformed shape (nil / non-map / map without
          ;;     exactly one of :ok/:failure) → `:rf.http/accept-failure`,
          ;;     so the request is never stranded with no reply.
          ;; `:rf.http/accept-failure` is non-retryable by construction
          ;; (Spec 014 §Failure categories), so it goes straight to
          ;; `completed!` — never `maybe-retry!`, and so never the terminal
          ;; retry-sequence STOP row.
          (let [decoded (:decoded decode-result)
                ;; Carry the successful response's wire facts
                ;; (actual status, status text, and the transport's already-
                ;; normalized header map — the SAME cross-host shape the
                ;; 4xx/5xx failure maps ride) forward to success
                ;; completion, where they land on the canonical reply's
                ;; `:meta` family-extension slot, so both `:after` and
                ;; the app reply target can observe status and headers for
                ;; a SUCCESSFUL request (the facts the spec's rate-limit /
                ;; Cache-Control `:after` use cases parse).
                ctx'    (assoc ctx
                               :decoded decoded
                               :response-meta {:status      status
                                               :status-text status-text
                                               :headers     headers})
                ;; RESPONSE-BODY classification (EP-0015 §8,
                ;; issue 5). The pre-`:accept` decoded body rides at
                ;; `:decoded` on an `:rf.http/accept-failure` trace; apply
                ;; the request's `:decode` schema per-slot `:sensitive?`
                ;; marks so a sensitive body slot redacts even when the
                ;; request was not declared per-call `:sensitive?`. A
                ;; non-schema `:decode` is a no-op here (the per-call flag /
                ;; off-box disposition govern an unschematized body).
                decoded' (rf.http.privacy-body/classify-decoded decoded decode)
                outcome (try
                          {:accepted (rf.http.encoding/run-accept accept decoded)}
                          (catch #?(:clj Throwable :cljs :default) e
                            {:accept-error e}))]
            (cond
              (:accept-error outcome)
              (completed!
                record ctx'
                {:failure {:kind       :rf.http/accept-failure
                           :detail     {:rf.http/bad-accept :threw
                                        :message #?(:clj (.getMessage ^Throwable (:accept-error outcome))
                                                    :cljs (.-message ^js (:accept-error outcome)))}
                           :decoded    decoded'
                           :request-id request-id}})

              (rf.http.encoding/valid-accept-return? (:accepted outcome))
              (let [accepted (:accepted outcome)]
                (if (contains? accepted :ok)
                  (completed! record ctx' {:value (:ok accepted)})
                  ;; A domain `:accept` failure (`{:failure user-map}` on a
                  ;; successful 2xx decode) is an `:rf.http/accept-failure`,
                  ;; exactly like the throw / malformed-return branches. Its
                  ;; reply goes through `emit-and-dispatch-failure!`, which
                  ;; emits the failure-CATEGORY trace and stamps the off-box
                  ;; disposition for the `:decoded` slot — the schema-classified
                  ;; body, so a `:decode`-schema sensitive slot redacts on the
                  ;; on-box trace.
                  (completed! record ctx'
                              {:failure {:kind       :rf.http/accept-failure
                                         :detail     (:failure accepted)
                                         :decoded    decoded'
                                         :request-id request-id}})))

              :else
              (completed!
                record ctx'
                {:failure {:kind       :rf.http/accept-failure
                           :detail     {:rf.http/bad-accept :malformed-return
                                        :returned (:accepted outcome)}
                           :decoded    decoded'
                           :request-id request-id}})))))

      :else
      ;; Non-2xx that didn't fall in 4xx/5xx (e.g., 1xx/3xx that the
      ;; runtime didn't follow) — surface as 4xx-shaped failure with
      ;; the raw body-text. This routes through
      ;; `maybe-retry!` so the retry
      ;; semantics match the `:rf.http/http-4xx` category label: a caller
      ;; with `:retry {:on #{:rf.http/http-4xx} …}` retries a real 4xx
      ;; and this synthetic-4xx (1xx/3xx) alike. The branch is rare in
      ;; practice (the JVM `NORMAL` / Fetch stacks follow 3xx by default).
      (maybe-retry!
        record ctx
        {:kind        :rf.http/http-4xx
         :status      status
         :status-text status-text
         :body        body-text
         :headers     headers}))))

(defn- prepare-body!
  "The managed request-preparation PHASE. Realizes the
  `:body` (re-invoking the thunk when `:body` is a `(fn body)`, per Spec
  014 §Body encoding — each attempt obtains a fresh handle) and encodes
  it, computing the effective request `:headers` (adding the encoder's
  `Content-Type` when the request did not already set one).

  Returns either:
   - `{:ok {:enc-body … :headers …}}` on success, or
   - `{:error failure}` where `failure` is a canonical `:rf.http/transport`
     shape. `:rf.http/transport` is the spec's category for an error
     \"before the HTTP transaction completed\" (Spec 014 §Failure
     categories) — body realization / encoding is exactly such a
     pre-transaction failure, so no new (spec-closed) category is minted.
     The shape carries a `:stage :request-prep` discriminator so telemetry
     can tell a prep failure apart from a network-transport failure, plus
     the usual `:message` / `:cause` tags the category documents.

  A throwing body thunk or a non-serialisable body (e.g. a value
  `JSON.stringify` / form-encode rejects) is caught here rather than
  escaping the attempt as a generic `:rf.error/fx-handler-exception`.
  `:rf.http/transport` is in the retryable subset, so a caller with
  `:retry {:on #{:rf.http/transport} …}` re-runs the whole attempt
  (re-invoking the thunk for a fresh handle) — consistent with the
  thunk-per-attempt contract."
  [request]
  (try
    (let [body          (let [b (:body request)] (if (fn? b) (b) b))
          [enc-body ct] (rf.http.encoding/encode-body body (:request-content-type request))
          headers       (cond-> (or (:headers request) {})
                          (and ct (nil? (rf.http.decode/content-type-of (:headers request))))
                          (assoc "Content-Type" ct))]
      {:ok {:enc-body enc-body :headers headers}})
    (catch #?(:clj Throwable :cljs :default) e
      {:error {:kind    :rf.http/transport
               :stage   :request-prep
               :message #?(:clj (.getMessage ^Throwable e)
                           :cljs (.-message ^js e))
               :cause   #?(:clj (.getName (class e))
                           :cljs (some-> (.-name ^js e)))}})))

(defn- send!
  "`:preparing` → `:in-flight`. The phase check, the host send and the
  publication of the send's cancel run as ONE guarded step, so cancellation
  is ordered against issuance in both directions: an abort that commits first
  leaves nothing to send, and an abort that arrives during the host call waits
  for the guard, then finds the cancel published and uses it. Checking the
  phase, releasing the guard and only then sending would let an abort land in
  between — telling the app the request was cancelled, and sending it anyway.
  Once the send has won, the request may already have reached the server: JDK
  exchange cancellation is best-effort.

  On CLJS nothing can run between the check and the call. The cancel is
  published before `cljs-fetch` runs, so an abort re-entering from inside the
  call still finds it. The completion callbacks are wired outside the guard."
  [record ctx {:keys [enc-body headers]}]
  (interleave! :issue/before-send ctx)
  (let [{:keys [request timeout-ms]} ctx
        method (or (:method request) :get)
        url    (:url ctx)]
    #?(:cljs
       (when-let [promise
                  (guarded record
                    (fn []
                      (when (current? @record ctx #{:preparing})
                        ;; The attempt's own controller: an abort and the
                        ;; per-attempt timeout both abort it. The caller's
                        ;; `:abort-signal` is never handed to Fetch — it is
                        ;; bound to the request's abort at issuance.
                        (let [controller (js/AbortController.)]
                          (commit! record assoc :phase :in-flight
                                   :cancel (fn [reason] (.abort controller (clj->js reason))))
                          (rf.http.transport-cljs/cljs-fetch
                            {:method              method
                             :url                 url
                             :headers             headers
                             :body                enc-body
                             :credentials         (:credentials request)
                             :mode                (:mode request)
                             :redirect            (:redirect request)
                             :cache               (:cache request)
                             :referrer            (:referrer request)
                             :integrity           (:integrity request)
                             :timeout-ms          timeout-ms
                             :internal-controller controller
                             ;; The transport picks the Fetch body-reader
                             ;; (`.text()` vs `.blob()` / `.arrayBuffer()` /
                             ;; `.formData()`) from the resolved decode mode.
                             :decode              (:decode ctx)
                             ;; So a Fetch `Headers.append` validation throw
                             ;; surfaces as a redacted
                             ;; `:rf.warning/http-header-invalid` trace instead of
                             ;; escaping as a generic `:rf.error/fx-handler-exception`.
                             :sensitive?          (true? (:sensitive? ctx))
                             :frame               (:frame ctx)})))))]
         (-> promise
             ;; The completion fence. Without it a throw anywhere in the
             ;; response cascade rejects this promise, and the `.catch` below
             ;; reclassifies it as `:rf.http/transport` → `maybe-retry!` → a
             ;; RE-SEND of a request whose 2xx already landed.
             (.then (fn [result]
                      (complete-fenced! record ctx #(handle-response! record ctx result))))
             ;; A genuine transport rejection. After an abort the Fetch also
             ;; rejects (the controller aborted it); the request is done by
             ;; then, so `completed!` commits nothing.
             (.catch (fn [err]
                       (maybe-retry! record ctx (rf.http.transport-cljs/classify-cljs-error err url))))))
       :clj
       ;; A monotonic start mark for the timeout's `:elapsed-ms`, taken before
       ;; the request is issued so the measured elapsed spans the whole attempt.
       (let [started-ns (System/nanoTime)
             elapsed-ms #(quot (- (System/nanoTime) started-ns) 1000000)]
         (try
           (when-let [^CompletableFuture cf
                      (guarded record
                        (fn []
                          (when (current? @record ctx #{:preparing})
                            (let [^CompletableFuture cf
                                  (rf.http.transport-jvm/jvm-fetch
                                    {:method     method
                                     :url        url
                                     :headers    headers
                                     :body       enc-body
                                     :timeout-ms timeout-ms
                                     ;; Binary decode modes (`:blob` /
                                     ;; `:array-buffer` / `:form-data`) read the
                                     ;; body as bytes.
                                     :decode     (:decode ctx)
                                     ;; The spec's `:redirect` envelope key
                                     ;; (default `:follow`) selects the
                                     ;; redirect-policy-specific client.
                                     :redirect   (:redirect request)
                                     :sensitive? (true? (:sensitive? ctx))
                                     ;; The originating frame stamps the warning
                                     ;; and supplies its general elision policy;
                                     ;; HTTP carrier names come from effect
                                     ;; registration.
                                     :frame      (:frame ctx)})]
                              (commit! record assoc :phase :in-flight
                                       ;; `true` = may-interrupt-if-running.
                                       :cancel (fn [_] (.cancel cf true)))
                              cf))))]
             ;; The callback fires after an abort's `.cancel` too, with a
             ;; CancellationException; the request is done by then, so
             ;; `completed!` commits nothing. The completion fence: this
             ;; BiConsumer's returned stage is discarded, so an unfenced throw
             ;; in the response cascade would vanish — no reply, the request
             ;; registered for ever.
             (.whenComplete cf
                            (reify java.util.function.BiConsumer
                              (accept [_ result throwable]
                                (complete-fenced!
                                  record ctx
                                  #(if throwable
                                     (maybe-retry! record ctx (rf.http.transport-jvm/classify-jvm-error throwable timeout-ms (elapsed-ms)))
                                     (handle-response! record ctx result)))))))
           (catch Throwable t
             (maybe-retry! record ctx (rf.http.transport-jvm/classify-jvm-error t timeout-ms (elapsed-ms)))))))))

(defn- run-attempt!
  "Run attempt n of the request: realise and encode the body, then `send!`.

  Preparation is a managed phase that runs outside the guard — a `:body`
  thunk is user code, re-invoked per attempt (Spec 014 §Body encoding). A
  preparation failure is the attempt's outcome, routed through `maybe-retry!`
  exactly like a network failure, so `:on-failure`, the retry policy, abort
  precedence and redaction all apply to it. A request no longer preparing — an
  external `:abort-signal` already aborted at issuance, or an abort landing as
  the backoff timer fired — is skipped before any body thunk runs."
  [record ctx]
  (when (current? @record ctx #{:preparing})
    (let [prep (prepare-body! (:request ctx))]
      (if-let [failure (:error prep)]
        (maybe-retry! record ctx failure)
        (send! record ctx (:ok prep))))))

(defn- fire!
  "The backoff timer fired: `:backoff` → `:preparing` for attempt n+1, then
  run it. `ctx` is the failed attempt n's, which the record kept as its work
  identity while it slept.

  The honest `:rf.http/retry-attempt` `:recovery :retried` row is emitted
  here, once the commit shows attempt n+1 really starting — never when the
  retry was merely scheduled — so a request cancelled during its backoff emits
  no phantom `:retried` (Spec 009's recovery law). It names the failed
  attempt and the backoff that governed the new one."
  [record ctx failure delay-ms]
  (interleave! :retry/before-attempt ctx)
  (let [next-ctx (update ctx :attempt inc)]
    (when (guarded record
            (fn []
              (when (current? @record ctx #{:backoff})
                (commit! record assoc :phase :preparing :attempt (:attempt next-ctx) :cancel nil)
                true)))
      (when rf.interop/debug-enabled?
        (emit-retry-attempt! ctx failure delay-ms :retried))
      (run-attempt! record next-ctx))))

(defn issue!
  "Issue a managed request: publish its record — once, with every key it will
  carry, before any cancellation path can reach it — then run attempt 1.
  `ctx` is the normalised request (`re-frame.http.handlers`).

  The external `:abort-signal` (CLJS) is bound here, once, to the request's
  abort, so it reaches whichever phase is current; retirement detaches it. An
  already-aborted signal aborts the request on the spot, and attempt 1 then
  never prepares a body or issues anything."
  [ctx]
  (let [{:keys [request]} ctx
        ctx    (assoc ctx :url (rf.http.encoding/merge-params (:url request) (:params request)))
        record (atom nil)]
    (reset! record
            (cond-> {:token        (rf.http.registry/next-token!)
                     :frame        (:frame ctx)
                     :request-id   (:request-id ctx)
                     :actor-id     (:actor-id ctx)
                     :origin-event (:origin-event ctx)
                     :issuance     (:issuance ctx)
                     :attempt      (:attempt ctx)
                     :url          (:url ctx)
                     :sensitive?   (true? (:sensitive? ctx))
                     :phase        :preparing
                     :cancel       nil
                     :abort        nil
                     :abort-fn     (fn [reason] (abort! record ctx reason))}
              #?@(:cljs [(:abort-signal ctx)
                         (assoc :external-abort
                                (rf.http.transport-cljs/make-external-abort (:abort-signal ctx)))])))
    (rf.http.registry/publish! record)
    #?(:cljs (rf.http.transport-cljs/bind-external-abort!
               (:external-abort @record)
               (fn [] (abort! record ctx :user))))
    (run-attempt! record ctx)
    nil))
