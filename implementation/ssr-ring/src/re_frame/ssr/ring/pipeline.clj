(ns re-frame.ssr.ring.pipeline
  "The Ring request lifecycle pipeline.

  The handler is a 4-step pipeline:

    1. `setup-request-frame!` — gensym frame-id, populate request slot,
       register the per-request frame (which drains :initial-events
       synchronously). On failure, returns a {:short-circuit ring-resp}
       map so the outer pipeline emits the on-error response without
       attempting render.
    2. `ssr/get-response`     — read the resolved response accumulator
       (flushes any pending error projection).
    3. branch on :redirect    — short-circuit to a Location response via
       `ssr-response->ring-response`, OR `build-full-response` — render
       the root-view, build the hydration payload, wrap in the html-
       shell, materialise to Ring.
    4. `destroy-frame-quietly!` in `finally` clears the frame and its
       request-scoped side channels.

  Render-time exceptions use the same projector and public-error wire body as
  drain-time exceptions. Transport failures outside that projector use the
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

(defn ^:private fail-closed-status
  "Coerce a resolved response `:status` to a valid Ring status int,
  defaulting to `fallback` when absent.

  Ring requires `:status` to be an integer; Jetty/http-kit reject (or
  mis-serialise) a non-int status while committing the response, AFTER
  the handler has returned a nominally-successful map, past the `:on-error`
  recovery point. Schema validation is optional, so this adapter is the last
  line: a non-integer status has
  no faithful coercion (we will not guess that \"404\" means 404), so we
  fail closed to a 500 — a valid, fail-closed Ring response — and surface
  the defect on the trace bus rather than ship a malformed map."
  [status fallback]
  (cond
    (nil? status)         fallback
    (integer? status)     status
    :else
    (do
      (trace/emit! :warning :rf.ssr/ssr-non-integer-status
                   {:where       :ssr-ring/ssr-response->ring-response
                    :status       status
                    :status-type (some-> status class .getName)
                    :reason      (str "response :status is a non-integer value of "
                                      "type " (or (some-> status class .getName) "nil")
                                      " — Ring statuses must be integers; the "
                                      "materialiser fails closed to 500 rather than "
                                      "emit a malformed Ring map (host-dependent; "
                                      "almost certainly a caller bug)")
                    :recovery    :failed-closed-to-500})
      500)))

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
  500), the Location target is coerced to a string, and header values are
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
           target location]
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
                       :status   (fail-closed-status (or redirect-status status) 302)
                       :reason   (str ":rf.server/redirect set :redirect with no "
                                      ":location — the response carries a "
                                      "3xx status with no Location header (malformed "
                                      "redirect; the browser has no target)")
                       :recovery :warned-and-emitted-statusonly}))
       {:status  (fail-closed-status (or redirect-status status) 302)
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
  `{:frame-id frame-id}` on success, or `{:short-circuit ring-response}`
  when setup fails (the caller short-circuits to that response, skipping
  render).

  The slot is populated BEFORE `reg-frame` so the synchronous
  `:initial-events` drain can resolve the `:rf.server/request` cofx (Spec
  011 §Request storage substrate). `make-frame` gensyms the id
  internally and would return only after the drain, so we inline its
  (gensym + reg-frame) shape here and call `ssr/set-request!` between
  them. The assembled config is identical to what `make-frame` would
  have submitted.

  On failure mid-drain, the request slot AND any partial frame
  registration are cleared so neither leaks across requests. The frame
  may be registered in the `frames` atom (see frame.cljc — `swap!
  frames` happens before `dispatch-sync`), so the best-effort destroy
  is required even though `reg-frame` threw."
  [{:keys [initial-events fx-overrides ssr on-error]} request]
  ;; The alphabetic prefix keeps the payload frame id valid for strict EDN
  ;; readers; keyword construction itself does not validate local names.
  (let [frame-id (keyword "rf.frame" (str (gensym "f")))]
    (ssr/set-request! frame-id request)
    (try
      (rf/reg-frame frame-id
        (cond-> {:doc       "ssr-ring per-request frame"
                 :platform  :server
                  ;; Resolve after `set-request!`: the function form can derive
                  ;; durable event payloads while handlers use the request coeffect
                  ;; for ambient reads.
                 :initial-events (lifecycle/resolve-initial-events! initial-events request)}
          fx-overrides (assoc :fx-overrides fx-overrides)
          ssr          (assoc :ssr           ssr)))
      {:frame-id frame-id}
      (catch Throwable t
        (ssr/clear-request! frame-id)
        (lifecycle/destroy-frame-quietly! frame-id)
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

(defn resolve-error-body
  "Resolve the projected-error HTML body (Spec 011 §Server error
  projection step 5 — \"a registered view (or the host's default error
  template) … receiving the public-error map as its prop\").

  When the caller supplied an `:error-view` opt, render it through the
  standard SSR emitter so the public-error map flows through position-
  appropriate escaping and the app's own styling/shell, instead of the
  hardcoded minimal `render-error-body`:

    - a keyword → resolved as a registered view: `[error-view public-error]`
      (the view receives the public-error map as its single prop),
    - a 1-arity fn → called with the public-error map, returning hiccup.

  Both paths render via `render-to-string` (no doctype, no hash — the
  error body is the inner shell body). If the error-view itself throws
  (a buggy error page must not take down the error response), we fall
  back to the host default `render-error-body` — the boundary cannot be
  bypassed by a bug in the caller's error view, mirroring the runtime's
  projector-throws-→-locked-500 fallback (Spec 011 §Where sanitisation
  happens). When no `:error-view` is supplied, the default template is
  used directly."
  [frame-id error-view public-error]
  (if (nil? error-view)
    (render-error-body public-error)
    (try
      (let [hiccup (cond
                     (keyword? error-view) [error-view public-error]
                     (fn? error-view)      (error-view public-error)
                     :else
                     (error/throw-error!
                       :rf.error/ssr-ring-invalid-error-view
                       'rf.ssr/ssr-handler
                       (str ":error-view must be a registered-view keyword "
                            "or a 1-arity fn; pass one of those to ssr-handler.")
                       {:recovery :supply-a-view-keyword-or-1-arity-fn
                        :extra    {:received error-view}}))]
        (rf/with-frame frame-id
          (ssr/render-to-string hiccup {:doctype? false :emit-hash? false})))
      (catch Throwable t
        ;; A buggy error-view must not bypass the error boundary —
        ;; surface the throw on the trace bus and fall back to the
        ;; locked host default template.
        (trace/emit-error! :rf.error/ssr-ring-error-view-failed
                           {:frame     frame-id
                            :exception (.getMessage t)
                            :ex-class  (.getName (class t))
                            :recovery  :fell-back-to-default-error-template})
        ;; Always-on but non-projecting: this is degradation of an existing
        ;; error response, not a second status transition.
        (lifecycle/emit-always-on-error!
          {:error     :rf.error/ssr-ring-error-view-failed
           :frame     frame-id
           :time      (interop/now-ms)
           :exception (.getMessage t)
           :ex-class  (.getName (class t))
           :recovery  :fell-back-to-default-error-template})
        (render-error-body public-error)))))

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
                hash-str  (lifecycle/render-document-hash hiccup)
                ;; Hash the head model on its separate reconstructible channel.
                head-hash (lifecycle/render-head-hash (:head-model head-bag))
                body-html (ssr/render-to-string
                            hiccup
                            {:doctype?    false
                             :emit-hash?  emit-hash?
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
        ;; full accumulator so post-render headers and cookies also survive.
        post-render-resp (ssr/flush-response! frame-id)]
    ;; A non-nil construction option overrides the seeded/app Content-Type.
    (ssr-response->ring-response post-render-resp html content-type)))

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
  (rf/with-frame frame-id
    (let [public-error  (ssr/project-render-exception! frame-id t)
          resp*         (ssr/peek-response frame-id)
          public-error* (or public-error
                            {:status 500 :code :internal-error
                             :message "Something went wrong"
                             :retryable? false})
          body-html     (resolve-error-body frame-id (:error-view opts) public-error*)]
      ;; The construction-time Content-Type labels only successful bodies. A
      ;; projected error is HTML, so keep the response accumulator's header.
      (ssr-response->ring-response resp* body-html))))

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
