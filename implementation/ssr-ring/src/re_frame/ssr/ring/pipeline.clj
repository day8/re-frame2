(ns re-frame.ssr.ring.pipeline
  "The Ring request lifecycle pipeline.

  The handler is a 4-step pipeline:

    1. `setup-request-frame!` — gensym frame-id, populate request slot,
       register the per-request frame (which drains :initial-events
       synchronously). On failure, returns a {:short-circuit ring-resp}
       map so the outer pipeline emits the on-error response without
       attempting render.
    2. `ssr/flush-response-result!` — read the resolved response accumulator
       AND the projected `:public-error` (flushes any pending error
       projection once).
    3. classify (Spec 011 §Drain-time error classification):
         - `:redirect` set        → short-circuit to a Location response;
         - projected 5xx          → `materialise-projected-error` — the
           projected status + safe headers/cookies + a plain-String error
           body (`:error-view` or the locked default), NO root-view / shell /
           hydration payload / app-db;
         - projected 4xx / none   → `build-full-response` — render the
           root-view, build the hydration payload, wrap in the html-shell,
           materialise to Ring (the app renders its OWN not-found /
           bad-request UI and hydrates into a working SPA).
    4. `destroy-frame-quietly!` in `finally` clears the frame and its
       request-scoped side channels.

  Render-time exceptions (an unrenderable root/shell throw) and a post-render
  recovered-to-nil sub 5xx use the SAME projector and error-body arm as
  drain-time 5xx errors. Transport failures outside that projector use the
  handler's fixed-detail fallback."
  (:require [re-frame.core :as rf]
            [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring.headers :as headers]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.payload :as payload]
            [re-frame.trace :as trace]))

(set! *warn-on-reflection* true)

;; ---- the fail-closed status rewrite reports itself ------------------------
;;
;; MEASURED under the real gate (`-Dre-frame.debug=false`) before this change:
;; a non-integer `:status` reaching the materialiser rewrote the app's 200 to a
;; 500 and emitted NOTHING. `trace/emit!` is the dev bus, so the warning count
;; was 0 in production and 2 in dev — the 2 being a double-emit all of its own
;; (below). An operator saw a 500 with no record of why, on either axis.
;;
;; WHAT STILL REACHES THIS (re-measured after PR #7204, rf2-dtpfv). The reserved
;; `:rf.server/*` fx now guard their own args UNCONDITIONALLY, so the framework's
;; own fx path can no longer feed the flip: `[:rf.server/set-status "not-an-int"]`
;; leaves `:status 500` (a Long — the guard threw, containment fanned
;; `:rf.error/fx-handler-exception`, the SSR projector stamped it) and
;; `[:rf.server/redirect {:status "302"}]` leaves `:redirect nil`. What remains
;; LIVE is the path the ruling reserved this net for: a HOST that hand-builds the
;; accumulator (`re-frame.ssr.response/swap-response!`) or calls this PUBLIC
;; materialiser with its own response map. That is a real caller — `get-response`
;; is a documented host-adapter surface — so the arm is a backstop, not dead code,
;; and a backstop that fires silently is the one thing it must not be.

(def ^:private status-defect-record-slots
  "The CLOSED key set of the always-on
  `:rf.error/ssr-ring-response-status-invalid` record — the only keys
  [[report-non-integer-status!]] builds it FROM.

  BUILT FROM, never filtered down to. A deny-list scrub stays one component
  away from the next leak (rf2-6jqa8 shipped four URL secrets before that
  lesson landed); a closed allow-list means a slot someone adds upstream has no
  route to Sentry even in principle. Pinned closed by
  `pipeline-materialiser-test`, which also asserts the ABSENCE of `:status` by
  name — the offending VALUE is the one thing this record must never carry.

    :error       the category (union-record shape)
    :frame       ALWAYS nil — see [[report-non-integer-status!]]
    :time        the emit instant (union-record shape)
    :where       the call site; a constant keyword, and the slot the three
                 sibling `:rf.ssr/*` materialiser diagnostics already carry
    :status-type the offending value's CLASS NAME — program structure, not app
                 data (the documented `:ex-class` residual class), and the one
                 lead that turns \"a 500 happened\" into \"something `str`-ed
                 the status\"
    :reason      a closed framework keyword, never prose (rf2-6jqa8: free prose
                 on this axis is how raw material finds its way back into a
                 record). The sentence stays on the dev trace, where a reader
                 who needs it is already standing
    :recovery    the fail-closed disposition"
  #{:error :frame :time :where :status-type :reason :recovery})

(defn ^:private report-non-integer-status!
  "Report ONE non-integer response `:status` on BOTH observability axes and
  return nil. The single emit site for the fail-closed rewrite, so the two axes
  cannot disagree about the defect they describe and no arm can forget one.

  Axis 1 is the ALWAYS-ON error-emit record (surface #4) — the half that
  reaches an off-box shipper on a `-Dre-frame.debug=false` JVM SSR host, built
  from the closed [[status-defect-record-slots]]. Axis 2 is the dev trace,
  which keeps the diagnostics whole, INCLUDING the offending value: it is
  DCE'd/gated, and a developer staring at a 500 wants to see the `\"404\"` that
  caused it.

  `:frame` is ALWAYS nil, and that is a deliberate constant rather than a
  missing feature. This materialiser is a pure `(response-map, body) → Ring-map`
  function with no frame argument; the two `with-frame` blocks in this
  namespace both CLOSE before the happy-path call site, so an ambient read
  would populate the slot on the error arm and leave it nil on exactly the path
  this promotion exists for. A slot that is sometimes-populated with no way for
  the consumer to tell which is worse than one that is honestly always nil —
  the `:rf.error/malformed-hydration-payload` frameless spelling. The cost is
  that the record takes only the corpus-wide route and not the frame-owned
  `:observability :errors` sink, which for a wire-shape defect is the right
  half anyway.

  NON-PROJECTING: the category is a member of
  `re-frame.ssr.error-listener/non-projection-eligible-errors`. It fires at
  MATERIALISATION time, strictly after the status is resolved, so re-projecting
  it could only fight the rewrite it is reporting."
  [status]
  (let [;; Computed ONCE and shared by both axes.
        status-type (some-> status class .getName)]
    (lifecycle/emit-always-on-error!
      {:error       :rf.error/ssr-ring-response-status-invalid
       :frame       nil
       :time        (interop/now-ms)
       :where       :ssr-ring/ssr-response->ring-response
       :status-type status-type
       :reason      :non-integer-status
       :recovery    :failed-closed-to-500})
    (trace/emit! :warning :rf.ssr/ssr-non-integer-status
                 {:where       :ssr-ring/ssr-response->ring-response
                  :status      status
                  :status-type status-type
                  :reason      (str "response :status is a non-integer value of "
                                    "type " (or status-type "nil")
                                    " — Ring statuses must be integers; the "
                                    "materialiser fails closed to 500 rather than "
                                    "emit a malformed Ring map (host-dependent; "
                                    "almost certainly a caller bug)")
                  :recovery    :failed-closed-to-500}))
  nil)

(defn ^:private fail-closed-status
  "Coerce a resolved response `:status` to a valid Ring status int,
  defaulting to `fallback` when absent.

  Ring requires `:status` to be an integer; Jetty/http-kit reject (or
  mis-serialise) a non-int status while committing the response, AFTER
  the handler has returned a nominally-successful map, past the `:on-error`
  recovery point. Schema validation is optional, so this adapter is the last
  line: a non-integer status has
  no faithful coercion (we will not guess that \"404\" means 404), so we
  fail closed to a 500 — a valid, fail-closed Ring response — and REPORT the
  rewrite on both axes ([[report-non-integer-status!]]) rather than ship a
  malformed map or, worse, ship a silent one.

  Call it ONCE per materialisation: the `:else` arm has a side effect, so a
  second call for the same status fans a second record for one flip."
  [status fallback]
  (cond
    (nil? status)     fallback
    (integer? status) status
    :else             (do (report-non-integer-status! status) 500)))

(defn ssr-response->ring-response
  "Materialise the runtime's resolved response accumulator (per
  Spec 011 §HTTP response contract) into a Ring response map. The
  `:body` arg is the rendered HTML (or nil for redirect-only
  responses). `:redirect` short-circuits per Spec 011 §Redirect
  precedence — status + Location header, no body.

  The optional 3-arg form accepts a `content-type` override:
  when non-nil it force-replaces any Content-Type on the accumulator (any
  casing) with a single canonical `Content-Type` header. A nil
  `content-type` leaves the accumulator's Content-Type (the runtime seed or
  an app `:rf.server/set-header`) untouched. An override is necessary because
  the runtime seeds a default Content-Type before the adapter runs.

  This materialiser is the last line between the runtime accumulator and the
  wire, and schema validation is optional. It must emit a
  valid Ring response regardless of what slipped past that gate: `:status`
  is coerced to an int (`fail-closed-status` — non-int fails closed to
  500, and REPORTS the rewrite on the always-on axis as well as the dev
  trace), the Location target is coerced to a string, and header values are
  coerced to strings in the fold (`headers/merge-pair-into-header-map`).

  Content-Length is stripped from every response: the body is
  always adapter-assembled AFTER the drain (a String/empty body here, or the
  streaming InputStream that swaps in downstream), so an app-set
  `Content-Length` can never match it. The Ring server owns transfer framing."
  ([resp body] (ssr-response->ring-response resp body nil))
  ([{:keys [status headers cookies redirect]} body default-content-type]
   (if redirect
     (let [{:keys [location] redirect-status :status} redirect
           ;; `:location` is the only redirect target key.
           target location
           ;; Resolve the wire status ONCE, before the no-target warning that
           ;; also reports it. `fail-closed-status` EMITS on its defect arm, so
           ;; the previous two calls (the warning's payload and the response
           ;; map) fanned TWO signals for ONE flip — measured as 2
           ;; `:rf.ssr/ssr-non-integer-status` warnings for
           ;; `{:redirect {:status "302"}}`. Harmless while the only consumer
           ;; was a dev warning; a double record on an off-box shipper is a
           ;; double alert.
           wire-status (fail-closed-status (or redirect-status status) 302)]
       ;; A redirect with no target is a malformed wire response — a
       ;; 3xx with no `Location` leaves the browser nowhere to go. The
       ;; runtime accepts a target-less `:rf.server/redirect` (the
       ;; location is caller-trusted and optional at the fx boundary),
       ;; so the adapter is the last line: emit a warning trace so the
       ;; defect is observable rather than silently shipping a broken
       ;; redirect. We still emit the status (we have no target to
       ;; invent) — the trace is the signal.
       (when-not target
         (trace/emit! :warning :rf.ssr/ssr-redirect-no-target
                      {:where    :ssr-ring/ssr-response->ring-response
                       :status   wire-status
                       :reason   (str ":rf.server/redirect set :redirect with no "
                                      ":location — the response carries a "
                                      "3xx status with no Location header (malformed "
                                      "redirect; the browser has no target)")
                       :recovery :warned-and-emitted-statusonly}))
       {:status  wire-status
        :headers (-> (headers/headers->ring-map+content-type-override
                       headers default-content-type)
                     (headers/append-set-cookies cookies)
                     ;; The Location value must be a string — a non-string
                     ;; target (the `:location` is caller-trusted at the fx
                     ;; boundary) is coerced so the Ring header map is valid.
                     (cond-> target (assoc "Location" (if (string? target)
                                                        target
                                                        (str target))))
                      ;; A bodiless redirect cannot honour an app-set length.
                     (headers/strip-content-length))
        :body    ""})
     {:status  (fail-closed-status status 200)
      :headers (-> (headers/headers->ring-map+content-type-override
                     headers default-content-type)
                   (headers/append-set-cookies cookies)
                    ;; The adapter assembles the body after the drain, so let
                    ;; the Ring server frame it.
                   (headers/strip-content-length))
      :body    (or body "")})))

(defn setup-request-frame!
  "Register a per-request frame and populate the request slot. Returns
  `{:frame-id frame-id :frame frame-value}` on success, or
  `{:short-circuit ring-response}` when setup fails (the caller short-circuits
  to that response, skipping render).

  `:frame` is the frame VALUE `make-frame` returns — it carries the EXACT
  incarnation token, so the handler's teardown (`destroy-frame-quietly!`) is
  incarnation-EXACT and can never reap a same-id successor a request drain
  destroyed-and-reseated under the gensym id (rf2-moftbs). `:frame-id` (the bare
  keyword) stays the address for every in-request operation (`with-frame`,
  `flush-response-result!`, `app-db-value`, the writer thread name) — those are
  correctly address-directed against the current incarnation.

  The slot is populated BEFORE `make-frame` so the synchronous
  `:initial-events` drain can resolve the `:rf.server/request` cofx (Spec
  011 §Request storage substrate). An id-less `make-frame` gensyms the id
  internally and would return only after the drain, so we allocate the
  gensym'd id OURSELVES, call `ssr/set-request!`, and then hand the id to
  `make-frame` explicitly (`:id frame-id`) — the same lifecycle order,
  through the ONE public constructor.

  On failure mid-drain, the request slot is cleared so it does not leak
  across requests. The failed incarnation itself is NOT reaped here:
  core construction is its exact-token owner — `make-frame` tears the
  partial frame down with its exact installed token before rethrowing
  (frame.cljc, PR #6072). A bare-id reap in the catch would be address-
  directed and could destroy a same-id successor B seated in the window
  after A's exact rollback released the per-id transaction (rf2-c6lp3)."
  [{:keys [initial-events fx-overrides ssr on-error]} request]
  ;; The alphabetic prefix keeps the payload frame id valid for strict EDN
  ;; readers; keyword construction itself does not validate local names.
  (let [frame-id (keyword "rf.frame" (str (gensym "f")))]
    (ssr/set-request! frame-id request)
    (try
      ;; KEEP the frame VALUE — it carries the exact incarnation token the
      ;; handler's teardown consumes for incarnation-EXACT cleanup (rf2-moftbs).
      (let [frame-value
            (rf/make-frame
              (cond-> {:id        frame-id
                       :doc       "ssr-ring per-request frame"
                       :platform  :server
                        ;; Resolve after `set-request!`: the function form can derive
                        ;; durable event payloads while handlers use the request coeffect
                        ;; for ambient reads.
                       :initial-events (lifecycle/resolve-initial-events! initial-events request)}
                fx-overrides (assoc :fx-overrides fx-overrides)
                ssr          (assoc :ssr           ssr)))]
        {:frame-id frame-id :frame frame-value})
      (catch Throwable t
        ;; Clear only the request side channel. Core construction is the
        ;; EXACT-TOKEN owner of the failed incarnation's rollback: `make-frame`
        ;; already tore incarnation A down with its exact installed token before
        ;; rethrowing (frame.cljc — the setup-runner + WON-install catch both
        ;; destroy `installed-token`, PR #6072). A redundant bare-id
        ;; `destroy-frame-quietly! frame-id` here would be ADDRESS-directed: once
        ;; A's exact rollback releases the per-id transaction, a same-id
        ;; successor B can be seated before this catch continues, and the keyword
        ;; target would then pin and destroy B (rf2-c6lp3). So we do NOT reap by
        ;; bare id — construction rollback owns exactly one incarnation, and the
        ;; request slot is the only per-request state this catch owns.
        (ssr/clear-request! frame-id)
        ;; Setup runs outside the handler body's catch, so contain a failing
        ;; caller hook here as well.
        {:short-circuit (lifecycle/safe-on-error on-error request t)}))))

(defn render-error-body
  "Build a minimal HTML body from a public-error map — the host's
  DEFAULT error template (Spec 011 §Server error projection step 5,
  the \"or the host's default error template\" branch). Used when no
  caller `:error-view` is registered and `render-to-string` has
  thrown, so the host can no longer rely on the user's root-view to
  produce wire bytes.

  Built as hiccup and rendered via
  `ssr/render-to-string` (with `:doctype? true`, `:emit-hash? false`)
  so the public-error map flows through position-appropriate escaping
  the emitter already owns — the same path as a caller-supplied error view.

  Carries no internal trace detail — the wire surface is locked to the
  public-error keys; the internal Throwable already rode the trace bus
  via `project-render-exception!`."
  [{:keys [status code message]}]
  (let [status* (or status 500)
        code*   (when code (name code))
        msg*    (or message "Something went wrong")
        hiccup  [:html
                 [:head
                  [:meta {:charset "utf-8"}]
                  [:title msg*]]
                 [:body
                  [:h1 msg*]
                  (when code*
                    [:p {:data-rf-error-code code*}
                     (str "Error code: " code* " (status " status* ")")])]]]
    (ssr/render-to-string hiccup {:doctype? true :emit-hash? false})))

(defn ^:private emit-error-view-failed!
  "Emit the recoverable-degradation `:rf.error/ssr-ring-error-view-failed`
  (Spec 009) on BOTH observability axes — the dev trace bus (`emit-error!`)
  and the always-on error-emit axis (surface #4) — then return nil. Shared
  by the two containment arms of `resolve-error-body`:

    - a THROWING error view (`detail` is the `Throwable` — carries
      `:exception` message + `:ex-class`), and
    - a reactive sub INSIDE the error view that RECOVERED to nil under
      production hardening (`detail` is a keyword `:reason`, rf2-oytx7j).

  NON-PROJECTING by design: the SSR `error-emit-projection-listener` skips
  this category (`non-projection-eligible-errors`), so the emit ships the
  off-box record but NEVER re-projects the secondary failure or flips the
  ORIGINAL projected status/headers — the boundary is one-way."
  [frame-id detail]
  (let [common     {:frame    frame-id
                    :recovery :fell-back-to-default-error-template}
        detail-map (if (instance? Throwable detail)
                     {:exception (.getMessage ^Throwable detail)
                      :ex-class  (.getName (class detail))}
                     {:reason detail})]
    (trace/emit-error! :rf.error/ssr-ring-error-view-failed
                       (merge common detail-map))
    (lifecycle/emit-always-on-error!
      (merge common detail-map
             {:error :rf.error/ssr-ring-error-view-failed
              :time  (interop/now-ms)}))
    nil))

(defn resolve-error-body
  "Resolve the projected-error HTML body (Spec 011 §Server error
  projection step 5 — \"a registered view (or the host's default error
  template) … receiving the public-error map as its prop\").

  When the caller supplied an `:error-view` opt, render it through the
  standard SSR emitter so the public-error map flows through position-
  appropriate escaping and the app's own styling/shell, instead of the
  hardcoded minimal `render-error-body`:

    - a keyword → looked up in the view registry and used as a CALLABLE
      head: `[(rf/view error-view) public-error]` (the view receives the
      public-error map as its single prop),
    - a 1-arity fn → called with the public-error map, returning hiccup.

  rf2-j81hs — the keyword arm used to build `[error-view public-error]`
  and lean on the emitter resolving a keyword head through the registry.
  That resolution is gone (a keyword head is a DOM element on every
  host), so the lookup happens HERE instead. The `:error-view` OPT is
  unchanged — a registered-view keyword is still accepted, and callers
  see no difference; only the internal construction moved from an
  emitter-side probe to an explicit `rf/view` call. An `:error-view`
  keyword naming no registered view now fails loud into the containment
  path below (previously it silently emitted a phantom
  `<error-page>` element as the error page, which is the exact
  silent-mis-render this bead exists to remove).

  Both paths render via `render-to-string` (no doctype, no hash — the
  error body is the inner shell body). When no `:error-view` is supplied,
  the default template is used directly.

  Containment is ONE-WAY — a buggy error view must not bypass the error
  boundary. Two failure modes fall back ONCE to the locked host default
  `render-error-body` (from the ORIGINAL public-error) with NO re-projection,
  emitting `:rf.error/ssr-ring-error-view-failed`:

    1. The error view THROWS — caught below.
    2. A reactive sub INSIDE the error view RECOVERS to nil under production
       hardening — it does NOT throw, so the `try/catch` cannot see it, but
       it buffers a fail-closed projection. On entry to the error arm the
       pending-error buffer is empty (the drain / render-throw / post-render
       flush already consumed it), so `ssr/pending-error-trace?` after the
       render can only be that recovered-to-nil error-view sub (rf2-oytx7j —
       the open-proof that the `try` alone cannot claim complete containment).
       We fall back and clear the buffer so the secondary failure is never
       projected."
  [frame-id error-view public-error]
  (if (nil? error-view)
    (render-error-body public-error)
    (try
      (let [hiccup (cond
                     (keyword? error-view)
                     (if-let [view-fn (rf/view error-view)]
                       [view-fn public-error]
                       (error/throw-error!
                         :rf.error/ssr-ring-invalid-error-view
                         'rf.ssr/ssr-handler
                         (str ":error-view keyword " error-view " names no "
                              "registered view. Register it with reg-view, or "
                              "pass a 1-arity fn to ssr-handler.")
                         {:recovery :register-the-view-or-supply-a-1-arity-fn
                          :extra    {:received error-view}}))

                     (fn? error-view)      (error-view public-error)
                     :else
                     (error/throw-error!
                       :rf.error/ssr-ring-invalid-error-view
                       'rf.ssr/ssr-handler
                       (str ":error-view must be a registered-view keyword "
                            "or a 1-arity fn; pass one of those to ssr-handler.")
                       {:recovery :supply-a-view-keyword-or-1-arity-fn
                        :extra    {:received error-view}}))
            body   (rf/with-frame frame-id
                     (ssr/render-to-string hiccup {:doctype? false :emit-hash? false}))]
        ;; Open-proof containment (rf2-oytx7j): a reactive sub inside the
        ;; error view that recovered-to-nil buffered a fail-closed projection
        ;; without throwing. Detect it (pure peek), fall back ONCE to the
        ;; locked template, and clear the buffer so it is never re-projected.
        (if (ssr/pending-error-trace? frame-id)
          (do
            (emit-error-view-failed! frame-id
                                     :reactive-sub-recovered-to-nil-in-error-view)
            (ssr/clear-pending-error-traces! frame-id)
            (render-error-body public-error))
          body))
      (catch Throwable t
        ;; A buggy error-view must not bypass the error boundary — surface
        ;; the throw on both observability axes and fall back to the locked
        ;; host default template, mirroring the runtime's projector-throws-→-
        ;; locked-500 fallback (Spec 011 §Where sanitisation happens).
        (emit-error-view-failed! frame-id t)
        (render-error-body public-error)))))

(defn projected-5xx?
  "Classification predicate (Spec 011 §Drain-time error classification,
  rf2-oytx7j). True when `public-error` is a projected SERVER FAULT
  (`:status` in 500..599): the app-db is in an arbitrary partial state, so
  the request diverts to the projected error page BEFORE the body commits.

  False for a projected 4xx (400..499 — a routing miss / bad client input:
  the app renders its OWN not-found / bad-request UI and hydrates into a
  working SPA) AND for a nil `public-error` (no error was projected — e.g. an
  app that manually `:rf.server/set-status`-es a 500 stays on the app arm;
  status alone is not proof of a projection). Both handlers branch on this
  after the initial-event drain and again after the render/shell work; a
  redirect is checked FIRST, so a pending projection never overrides it."
  [public-error]
  (boolean (when-let [s (:status public-error)]
             (<= 500 s 599))))

(defn ^:private materialise-error-arm
  "Materialise the projected-error arm onto `resp`: resolve the error body
  (the caller's `:error-view`, or the locked default template) under the
  request frame scope and wrap it, KEEPING the response accumulator's
  Content-Type (a projected error is HTML — the successful-body
  `:content-type` override does not apply). Shared by the render-throw path
  (`project-render-throw->ring-response`) and the already-projected drain /
  post-render 5xx path (`materialise-projected-error`)."
  [frame-id resp public-error opts]
  (rf/with-frame frame-id
    (ssr-response->ring-response
      resp
      (resolve-error-body frame-id (:error-view opts) public-error))))

(defn materialise-projected-error
  "Materialise an ALREADY-projected 5xx (a drain-time handler/fx exception,
  a custom 5xx projection, or a post-render recovered-to-nil sub) to a Ring
  error response, WITHOUT re-projecting: keep the projected `:status` and the
  safe headers/cookies already accumulated in `resp`, ship a plain-String
  error body (`:error-view`, or the locked default template), and emit NO
  root-view / html-shell / `__rf_payload` / app-db / render or head hashes.

  Besides avoiding a misleading half-drained page, dropping the payload
  removes a needless partial-state egress surface when the caller opted into
  whole-app-db hydration. Redirect precedence is honoured by the caller (it
  branches on `:redirect` first); stale Content-Length is stripped by the
  shared materialiser. Per Spec 011 §Drain-time error classification
  (rf2-oytx7j)."
  [frame-id resp public-error opts]
  (materialise-error-arm frame-id resp public-error opts))

(defn ^:private build-full-response*
  "The non-error path of `build-full-response`. Split into its own fn
  so the outer projector catch reads as a simple wrapper.

  Root, body, head, and hydration projection share one frame scope. This is
  required for registered lookups and frame-sensitive egress classification."
  [frame-id resp
   {:keys [root-view emit-hash? version schema-digest payload
           html-shell content-type client-frame-id]
    :as   opts}]
  ;; Blocking route resources settle before rendering; absent resource hooks
  ;; make this a no-op.
  (ssr/drain-blocking-resources! frame-id opts)
  (let [;; Single `with-frame` block covers the frame-aware stages:
        ;; root-view resolution (a 0-arity fn may close over subscribe-time
        ;; reads), the render walk (subs on registered views), head
        ;; resolution (`rf/active-head` reads the frame's route registry),
        ;; AND the hydration-payload build. One push/pop per request.
        explicit-head (:head opts)
        {:keys [head-html html-attrs body-attrs body-html head-hash rf-payload]}
        ;; Pin the request frame across view/head lookups and payload projection.
        (rf/with-frame frame-id
          (let [hiccup    (lifecycle/resolve-root-view root-view)
                ;; Explicit head HTML bypasses route-derived attributes and has
                ;; no client-reconstructible model.
                head-bag  (if explicit-head
                            {:head-html explicit-head
                             :html-attrs nil
                             :body-attrs nil}
                            (lifecycle/resolve-head frame-id))
                ;; Compute the body hash once for both emitted marker and payload.
                ;; nil for an unresolved root form — that root gets NO hash on
                ;; either channel (rf2-q1b96; see `render-document-hash`).
                hash-str  (lifecycle/render-document-hash hiccup)
                ;; Hash the head model on its separate reconstructible channel.
                head-hash (lifecycle/render-head-hash (:head-model head-bag))
                body-html (ssr/render-to-string
                            hiccup
                            ;; `:emit-hash?` must ALSO fall away with the hash:
                            ;; `render-to-string` treats a true `:emit-hash?`
                            ;; with no `:render-hash` as "compute it yourself"
                            ;; (rf2-atmvj), which would re-stamp the very
                            ;; constant this root is being spared.
                            {:doctype?    false
                             :emit-hash?  (and emit-hash? (some? hash-str))
                             :render-hash (when emit-hash? hash-str)})
                ;; Read after rendering and inside the frame scope. Optional
                ;; resource projection uses the carried frame to apply derived
                ;; sensitivity before serializing the durable runtime slice.
                app-db     (rf/app-db-value frame-id)
                runtime-db (:rf.db/runtime (rf/frame-state-value frame-id))
                rf-payload (payload/build-payload frame-id app-db runtime-db hash-str
                                                  {:version         version
                                                   :schema-digest   schema-digest
                                                   :payload         payload
                                                   :head-hash       head-hash
                                                   ;; rf2-lm2yzy — stable WIRE
                                                   ;; :rf/frame-id (nil ⇒ omit).
                                                   :client-frame-id client-frame-id})]
            (assoc head-bag
                   :body-html  body-html
                   :head-hash  head-hash
                   :rf-payload rf-payload)))
        payload-edn (pr-str rf-payload)
        shell-opts  (assoc opts
                           :head        head-html
                           :html-attrs  html-attrs
                           :body-attrs  body-attrs
                           ;; The WIRE `data-rf-head-hash` marker is gated by
                           ;; `:emit-hash?`, mirroring `data-rf-render-hash`
                           ;; (the payload's `:rf/head-hash` stays unconditional
                           ;; — see `rf-payload` above).
                           :head-hash   (when emit-hash? head-hash))
        html        (html-shell body-html payload-edn shell-opts)
        ;; Re-flush after rendering. A hardened reactive subscription may recover
        ;; to nil while buffering a projected failure; reading only the pre-render
        ;; response would incorrectly ship that broken render as 200. Re-read the
        ;; full accumulator (AND the projected public-error) so post-render
        ;; headers and cookies survive AND a post-render projected 5xx diverts
        ;; to the error arm (rf2-oytx7j).
        {post-render-resp :response post-error :public-error}
        (ssr/flush-response-result! frame-id)]
    (if (projected-5xx? post-error)
      ;; A recovered-to-nil sub buffered a fail-closed 5xx during the render
      ;; walk. DISCARD the degraded html + hydration payload and materialise
      ;; the projected-error arm instead (Spec 011 §Drain-time error
      ;; classification — projected 5xx before commit → error page, no
      ;; hydration; never a degraded body shipped under a 500).
      (materialise-projected-error frame-id post-render-resp post-error opts)
      ;; A non-nil construction option overrides the seeded/app Content-Type.
      (ssr-response->ring-response post-render-resp html content-type))))

(defn project-render-throw->ring-response
  "Route a render-time `Throwable` through the SSR error projector and
  materialise the projected (fail-closed, non-200) Ring error response.
  Shared by the non-streaming `build-full-response` catch arm and the
  streaming `stream-handler` shell phase so both render-side
  failure surfaces emit one uniform projected-error body contract.

  Steps (Spec 011 §Server error projection §View-time exceptions):

    1. `ssr/project-render-exception!` — synthesises a
       `:rf.error/ssr-render-failed` trace event, drives the active
       projector, stamps the public-error's `:status` onto the response
       accumulator. Returns the public-error map (or nil — frame missing
       / not a server frame).
    2. `ssr/peek-response` — pure read of the now-stamped response
       accumulator (the projection drain already ran).
    3. `resolve-error-body` — caller-registered `:error-view` (receiving
       the public-error map) when present, else the host default
       template.
    4. `ssr-response->ring-response` — materialise through the SAME
       happy-path materialiser, so headers / cookies the drain DID
       accumulate before the throw still ride the wire.

  When projection returns nil (e.g. no server frame) the locked
  generic-500 public-error is substituted so the wire still carries a
  well-formed fail-closed body.

  The whole error projection and error-body render path runs inside the
  request frame's scope, mirroring the happy path. `project-render-exception!`
  stamps the per-frame response accumulator, `peek-response` reads it, and a
  caller `:error-view` resolves through the registrar (`resolve-error-body`'s
  `[error-view public-error]` view lookup); `resolve-error-body` re-pins
  `*current-frame*` around its own render."
  [frame-id ^Throwable t opts]
  (let [public-error  (ssr/project-render-exception! frame-id t)
        resp*         (ssr/peek-response frame-id)
        ;; An unrenderable root/shell throw cannot supply a body, so it ALWAYS
        ;; takes the error page — even when a custom projector chose a 4xx
        ;; (e.g. the 418 teapot test): classification-by-status is the
        ;; drain/post-render rule; a render throw is the necessary exception
        ;; (Spec 011 §Drain-time error classification). When projection returns
        ;; nil (no server frame) the locked generic-500 is substituted.
        public-error* (or public-error ssr/fallback-public-error)]
    ;; The construction-time Content-Type labels only successful bodies; a
    ;; projected error is HTML, so `materialise-error-arm` keeps the
    ;; accumulator's header. It re-pins the request frame for the error-body
    ;; render, mirroring the happy path.
    (materialise-error-arm frame-id resp* public-error* opts)))

(defn build-full-response
  "Render the caller's `:root-view` against `frame-id`, build the
  hydration payload, wrap in the html-shell, and materialise to a Ring
  response.

  The root view resolves once per request: both
  the wire HTML (via `render-to-string` + its embedded
  `data-rf-render-hash`) and the payload's `:rf/render-hash` derive
  from the same hiccup tree, so a non-idempotent fn-form root-view
  cannot fire a spurious `:rf.ssr/hydration-mismatch` on a successful
  hydration.

  A render-time
  throw (e.g. the `validate-tag-name!` rejection of
  `(keyword \"has space\")`, a view-fn `(throw (ex-info ...))`, a
  hiccup-walker structural error) is routed through the SAME error
  projector that catches drain-time fx/handler exceptions. The
  outer try/catch here:

    1. Calls `ssr/project-render-exception!` — synthesises a
       `:rf.error/ssr-render-failed` trace event, drives the active
       projector, stamps the public-error's `:status` onto the
       response accumulator (Spec 011 §Server error projection
       §Where sanitisation happens — \"runtime sets
       `:rf.server/set-status` to the public-error's `:status`\").
    2. Reads the now-stamped response via `ssr/peek-response`
       (pure read — the projection drain already ran).
    3. Builds a minimal escaped HTML error body from the
       public-error's `:message` / `:code` via `render-error-body`.
    4. Materialises the Ring response through the same
       `ssr-response->ring-response` materialiser the happy path
       uses — so headers / cookies the drain DID accumulate before
       the render-time throw still ride the wire.

  The try/catch is the unification point: render-time and drain-time
  exceptions both go through the projector, so the wire body contract
  is uniform regardless of where the failure originated.

  Note: the outer `ssr-handler`'s `:on-error` hook still wraps this
  call. The remaining exceptions it catches are Ring-layer / transport
  failures the projector can't see (e.g. an exception in the host's
  Content-Type negotiator, or a re-throw from the projector pipeline
  itself when no server frame is registered). Those use the fixed, no-detail
  transport fallback."
  [frame-id resp opts]
  (try
    (build-full-response* frame-id resp opts)
    (catch Throwable t
      ;; The projector stamps :status onto the response accumulator and
      ;; the projected (fail-closed, non-200) error body is materialised
      ;; through the same path the streaming shell phase reuses. Render-time
      ;; AND drain-time exceptions thus share one wire-body contract,
      ;; and the streaming + non-streaming shell-failure surfaces project
      ;; identically (Spec 011 §Server error projection §View-time
      ;; exceptions).
      (project-render-throw->ring-response frame-id t opts))))
