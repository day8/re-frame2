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
    4. `destroy-frame-quietly!` in `finally` — `:ssr/on-frame-destroyed`
       (rf2-fcj33) clears the per-frame request slot.

  Per rf2-zkca8.1: `ssr-response->ring-response` (28 L, one fn) lives
  here rather than in its own ns. Its only consumers were the redirect
  short-circuit in `re-frame.ssr.ring` and `build-full-response` below;
  it's a pipeline-stage helper and reads top-down with the rest of the
  pipeline.

  Per rf2-zwgsv (Mike decision rf2-i9f0g Option B): the render-time
  failure path goes through the SSR error projector — same pipeline
  as drain-time fx/handler exceptions. `build-full-response` catches
  the render-side throw, calls `ssr/project-render-exception!` to
  stamp the projector's `:status` onto the response accumulator,
  then emits a minimal HTML error body driven by the projector's
  public-error map (Spec 011 §Server error projection §View-time
  exceptions). Render-time and drain-time exceptions thus share one
  body contract on the wire — the projector's public-error map — and
  no fixed `\"Internal error\"` fallback string ever reaches a client
  (rf2-kzvwq)."
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
  defaulting to `fallback` when absent (rf2-v0qbng).

  Ring requires `:status` to be an integer; Jetty/http-kit reject (or
  mis-serialise) a non-int status while committing the response, AFTER
  the handler has returned a nominally-successful map — past the
  `:on-error` recovery point. The fx-args `:schema` boundary
  (`:rf.fx.server/set-status-args` = `:int`) would reject a non-int
  `:rf.server/set-status` at dispatch time, but that boundary
  SOFT-PASSES when the optional schemas artefact is absent from the
  production classpath (Spec 010 §Recommended soft-pass) — the default
  ssr-ring runtime. The adapter is the last line: a non-int status has
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

  The optional 3-arg form accepts a `content-type` OVERRIDE (rf2-nncni3):
  when non-nil it force-replaces any Content-Type on the accumulator (any
  casing) with a single canonical `Content-Type` header. A nil
  `content-type` leaves the accumulator's Content-Type (the runtime seed or
  an app `:rf.server/set-header`) untouched. The override happens inside the
  header fold (`headers->ring-map+content-type-override`). The earlier
  default-when-absent form (rf2-uj9z8) was a silent no-op for the ssr-ring
  runtime — `default-response` always seeds a Content-Type, so the passed
  opt could never be applied (rf2-nncni3).

  Host-serialisability fail-closed (rf2-v0qbng): this materialiser is the
  LAST line between the runtime accumulator and the wire, and the
  `:rf.server/*` fx-args `:schema` boundary soft-passes off the optional
  schemas classpath (the default ssr-ring runtime). So it must emit a
  valid Ring response regardless of what slipped past that gate: `:status`
  is coerced to an int (`fail-closed-status` — non-int fails closed to
  500), the Location target is coerced to a string, and header values are
  coerced to strings in the fold (`headers/merge-pair-into-header-map`).

  Content-Length is stripped from EVERY response (rf2-d95m4i): the body is
  always adapter-assembled AFTER the drain (a String/empty body here, or the
  streaming InputStream that swaps in downstream), so an app-set
  `Content-Length` can never match it. The streaming path hardened against
  this (rf2-h3dg0); doing it in the shared materialiser closes the same
  hazard on the non-streaming (and redirect) paths and lets the Ring server
  own transfer framing (`headers/strip-content-length`)."
  ([resp body] (ssr-response->ring-response resp body nil))
  ([{:keys [status headers cookies redirect]} body default-content-type]
   (if redirect
     (let [{:keys [location] redirect-status :status} redirect
           ;; rf2-vngir: the canonical (and only) redirect target key is
           ;; `:location`. The materialiser back-door `(or location url to)`
           ;; was pruned with the runtime synonyms; a direct / hand-built
           ;; response map must also key its redirect target on `:location`.
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
                     ;; rf2-d95m4i — a bodiless redirect never matches a
                     ;; stale app-set Content-Length either; strip it.
                     (headers/strip-content-length))
        :body    ""})
     {:status  (fail-closed-status status 200)
      :headers (-> (headers/headers->ring-map+content-type-override
                     headers default-content-type)
                   (headers/append-set-cookies cookies)
                   ;; rf2-d95m4i — strip any app-set Content-Length: the body
                   ;; is the adapter-assembled shell + hydration payload built
                   ;; AFTER the drain, so a drain-time fixed length will not
                   ;; match it; let the Ring server frame the String body.
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
  ;; rf2-joibj: the gensym prefix MUST start with a non-numeric
  ;; character. Per the EDN spec (https://github.com/edn-format/edn)
  ;; symbol / keyword identifier names cannot begin with a digit, and
  ;; spec-strict readers (`clojure.edn/read-string`,
  ;; `cljs.tools.reader.edn/read-string`) reject `:rf.frame/<digits>`.
  ;; The per-request frame-id rides into the wire payload (Spec 011
  ;; §Hydration boot) where the browser's strict EDN reader pulls it
  ;; back during hydration — a digit-only local-part would crash the
  ;; first call to `cljs.reader/read-string` on the embedded payload.
  ;; `(gensym "f")` keeps the id unique per request, still namespaced,
  ;; AND spec-compliant. `clojure.core/keyword` does not validate, so
  ;; the read-side enforcement is the only signal — this naming is
  ;; load-bearing.
  (let [frame-id (keyword "rf.frame" (str (gensym "f")))]
    (ssr/set-request! frame-id request)
    (try
      (rf/reg-frame frame-id
        (cond-> {:doc       "ssr-ring per-request frame"
                 :platform  :server
                 ;; EP-0027 (rf2-7ae2to): the SSR-ring runtime LOWERS its
                 ;; request-derived init into the `:initial-events` construction
                 ;; vector — the frame-level on-create key is retired in
                 ;; `reg-frame`. The public
                 ;; `ssr-handler` `:initial-events` opt IS the EP-0027 setup
                 ;; vector (see `resolve-initial-events!`); per request it
                 ;; resolves to an `:initial-events` vector, passed verbatim into
                 ;; the per-request frame's `:initial-events` (EP-0027 §SSR —
                 ;; "a server computes its :initial-events vector per request").
                 ;;
                 ;; Audit rf2-cegm7 A2 / rf2-j54ee: a VECTOR `:initial-events`
                 ;; passes through verbatim — handlers read the request via the
                 ;; `:rf.server/request` cofx (the spec-documented canonical
                 ;; surface for NON-durable reads). rf2-kzns7l (additive): a
                 ;; `(fn [request] initial-events-vector)` is resolved HERE — the
                 ;; request slot is already populated (set-request! above), so the
                 ;; fn derives the setup vector from the live Ring request, baking
                 ;; a request-derived fact into a boot event's PAYLOAD (the
                 ;; replay-safe recordable boundary).
                 :initial-events (lifecycle/resolve-initial-events! initial-events request)}
          fx-overrides (assoc :fx-overrides fx-overrides)
          ssr          (assoc :ssr           ssr)))
      {:frame-id frame-id}
      (catch Throwable t
        (ssr/clear-request! frame-id)
        (lifecycle/destroy-frame-quietly! frame-id)
        ;; rf2-ljjh0 — the short-circuit response goes through
        ;; `safe-on-error` so a caller-supplied `:on-error` that THROWS
        ;; during a setup failure is contained (trace + locked
        ;; `default-on-error`) rather than escaping `setup-request-frame!`
        ;; as a raw container 500 with internal topology in the stack
        ;; trace. The setup-failure path runs OUTSIDE the handler's own
        ;; try/catch, so without this guard it is the one `:on-error`
        ;; call site not protected by the handler body's catch.
        {:short-circuit (lifecycle/safe-on-error on-error request t)}))))

(defn render-error-body
  "Build a minimal HTML body from a public-error map — the host's
  DEFAULT error template (Spec 011 §Server error projection step 5,
  the \"or the host's default error template\" branch). Used when no
  caller `:error-view` is registered and `render-to-string` has
  thrown, so the host can no longer rely on the user's root-view to
  produce wire bytes.

  Per rf2-uzjhl: built as hiccup then rendered via
  `ssr/render-to-string` (with `:doctype? true`, `:emit-hash? false`)
  so the public-error map flows through position-appropriate escaping
  the emitter already owns — same render path as the caller-supplied
  `:error-view` (`resolve-error-body` below). Removes the `(str ...)`
  concatenation + manual `escape-html` / `escape-attr` calls that
  duplicated the emitter's escaping behaviour.

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
        ;; EP-0008 (rf2-hhutya): ALSO ride the always-on axis. The
        ;; recoverable-degradation sibling of head-resolution — a buggy
        ;; error-view falls back to the locked default template (a degraded
        ;; outcome on the ALREADY-error response, not a new failure).
        ;; NON-PROJECTING: the always-on `error-emit-projection-listener`
        ;; skips this category (`non-projection-eligible-error?`, rf2-sccp5),
        ;; so promotion ships the off-box record WITHOUT re-projecting /
        ;; flipping the status the render-time path already stamped.
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
  so the outer try/catch (rf2-zwgsv render-time projector unification)
  reads as a simple wrap of the rendering / payload / shell pipeline,
  not as a 30-line body in the catch's scope.

  Per rf2-5br2y: the frame-aware stages (`resolve-root-view`,
  `render-to-string`, `resolve-head`) run inside a SINGLE outer
  `with-frame` so the `current-frame` push/pop happens once per request,
  not three times. `app-db-value` reads the named frame explicitly and
  doesn't need the binding; it sits outside the block to keep that
  explicitness visible."
  [frame-id resp
   {:keys [root-view emit-hash? version schema-digest payload
           html-shell content-type]
    :as   opts}]
  ;; rf2-er7qx2 — SSR blocking-resource drain. AFTER the `:initial-events` drain
  ;; (which resolved the route + enqueued the route's blocking resource
  ;; ensures) and BEFORE the render walk, drain the current nav-token's
  ;; blocking resources until they settle or the render deadline fires. A
  ;; never-settling blocking resource is settled to a structured first-load
  ;; failure in the frame's runtime-db, so the render walk below sees a
  ;; settled `:error` rather than an unchecked `:loading` / skeleton (Spec 016
  ;; §SSR and hydration steps 3-4). A no-op when the resources artefact is
  ;; absent (the `:resources/drain-blocking-ssr!` hook is nil).
  (ssr/drain-blocking-resources! frame-id opts)
  (let [;; Single `with-frame` block covers the frame-aware stages:
        ;; root-view resolution (a 0-arity fn may close over subscribe-time
        ;; reads), the render walk (subs on registered views), head
        ;; resolution (`rf/active-head` reads the frame's route registry),
        ;; AND the hydration-payload build. One push/pop per request.
        explicit-head (:head opts)
        {:keys [head-html html-attrs body-attrs body-html head-hash rf-payload]}
        ;; rf2-bzw8gd / rf2-tqjc7h: pin `*current-frame*` to the request frame
        ;; for the whole render walk (`rf/with-frame`), established ONCE per
        ;; request. The scope covers resolve-root-view, render-to-string's
        ;; `:view` lookups, resolve-head's `:head` / `:route` lookups, AND the
        ;; payload's resource-runtime projection (which reads `*current-frame*`
        ;; via `frame/resolve-current-frame`).
        (rf/with-frame frame-id
          (let [hiccup    (lifecycle/resolve-root-view root-view)
                ;; rf2-4dra9 / rf2-h2ujj: resolve the active route's
                ;; :head (or default-head fallback). The head fragment
                ;; goes through the shell as the :head opt; the
                ;; :html-attrs / :body-attrs bags ride alongside so the
                ;; shell can stamp them on <html> / <body> per Spec 011
                ;; §Default flow step 4. Callers that supplied an
                ;; explicit :head string take precedence — they chose to
                ;; bypass route-driven head resolution, and an explicit
                ;; string carries no attr-bag sidechannel (nor a
                ;; reconstructible `:head-model` — rf2-1oxjxk).
                head-bag  (if explicit-head
                            {:head-html explicit-head
                             :html-attrs nil
                             :body-attrs nil}
                            (lifecycle/resolve-head frame-id))
                ;; rf2-i15nh / rf2-atmvj: compute the structural hash ONCE
                ;; per request. rf2-1oxjxk (Option B, reverting rf2-9fw2de):
                ;; the wire hash is BODY-ONLY again — the documented client
                ;; boot (`ssr/hydrate!`'s `:render-tree-fn`) only ever hashes
                ;; the bare body tree, so folding the head fragment in here
                ;; fired a spurious mismatch on EVERY page (the client half
                ;; of rf2-9fw2de was never made). The same hex feeds the
                ;; root-element `data-rf-render-hash` (via render-to-string's
                ;; `:render-hash` opt) AND the payload's `:rf/render-hash`, so
                ;; the canonical-EDN walk runs once per request. When
                ;; `:emit-hash?` is false the hash is still needed for the
                ;; payload slot.
                hash-str  (lifecycle/render-document-hash hiccup)
                ;; Head divergence rides its OWN channel (rf2-1oxjxk): a
                ;; separate structural hash over the CANONICAL HEAD MODEL
                ;; (never emitted HTML), client-reconstructible via
                ;; `(rf/active-head frame-id)` (Spec 011 §Default flow step
                ;; 5). nil when the head is not client-reconstructible (an
                ;; explicit `:head` string, or a degraded/failed head
                ;; resolution) — `render-head-hash` OMITS the channel rather
                ;; than ship a hash the client could never match.
                head-hash (lifecycle/render-head-hash (:head-model head-bag))
                body-html (ssr/render-to-string
                            hiccup
                            {:doctype?    false
                             :emit-hash?  emit-hash?
                             :render-hash (when emit-hash? hash-str)})
                ;; rf2-p026f5 — build the hydration payload INSIDE the same
                ;; `with-frame` scope. app-db-value / runtime-db-value read the
                ;; named frame explicitly, but the runtime-db PROJECTION is NOT
                ;; frame-blind: the resources SSR hook
                ;; (`:ssr/extend-runtime-db-projection` →
                ;; `re-frame.resources.ssr/project-resources-runtime-db`)
                ;; resolves the CURRENT frame (`frame/resolve-current-frame`,
                ;; the `*current-frame*` dynamic var) to apply frame-owned
                ;; egress classification + named-scope DERIVED sensitivity
                ;; (Spec 015 §Derived sensitivity / Spec 016 clause 4). Built
                ;; OUTSIDE this scope (the prior shape) it ran FRAMELESS, so a
                ;; resource under a frame-sensitive `{:from-db}` scope leaked
                ;; its raw scope identity + data into the payload. Reading the
                ;; snapshot here — AFTER the render walk, INSIDE the block — is
                ;; load-bearing in BOTH dimensions: the post-walk value (a
                ;; continuation drain may have mutated the frame-state) AND the
                ;; frame scope the projection needs. This mirrors the streaming
                ;; path, where `build-final-payload` runs inside the same
                ;; `with-frame` rebinding (rf2-tbr67x / rf2-tqjc7h).
                ;; EP-0001 (rf2-30kzz2): the runtime-db rides as the
                ;; serializable `:rf/runtime-db` slice.
                app-db     (rf/app-db-value frame-id)
                runtime-db (:rf.db/runtime (rf/frame-state-value frame-id))
                rf-payload (payload/build-payload frame-id app-db runtime-db hash-str
                                                  {:version       version
                                                   :schema-digest schema-digest
                                                   :payload       payload
                                                   :head-hash     head-hash})]
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
        ;; rf2-c0bq1 — RE-FLUSH after the render walk. The `resp` arg was
        ;; read by the single `get-response` in `ssr-handler` BEFORE the
        ;; render ran, so it carries the pre-render status. A reactive
        ;; sub that THROWS during `render-to-string` under production
        ;; hardening (`interop/debug-enabled? = false`) recovers to nil —
        ;; `render-to-string` does NOT throw, so the outer
        ;; `build-full-response` catch never fires — but the always-on
        ;; error-emit substrate (rf2-vvwmi) BUFFERS a fail-closed 500 onto
        ;; pending-error-traces during the walk. Without this re-flush
        ;; that buffered 500 sits unread until frame-destroy drops it, and
        ;; the wire ships a silent 200 with the recovered-to-nil broken
        ;; HTML — defeating the rf2-vvwmi fix end-to-end and breaking the
        ;; Spec 011 §744/§750 "fail-closed, never a silent 200" contract.
        ;;
        ;; `flush-response!` drains the (post-render) buffer through the
        ;; default projector and stamps the projector's :status onto the
        ;; response accumulator (last-write-wins, redirect-guarded — see
        ;; `apply-error-projection!` error_listener.cljc:139). The happy
        ;; path is unaffected: no error buffered during render → empty
        ;; buffer → the drain is a no-op → :status stays whatever the
        ;; pre-render `resp` carried (default 200). A redirect set during
        ;; render still wins (the projector refuses to overwrite a
        ;; redirect response). We re-read the FULL response (not just
        ;; merge :status) so any header / cookie the render walk
        ;; accumulated also rides the wire.
        post-render-resp (ssr/flush-response! frame-id)]
    ;; Content-Type override (rf2-nncni3): the 3-arg form force-replaces
    ;; the accumulator's Content-Type with the handler's `:content-type`
    ;; opt when it is non-nil. The SSR runtime always seeds a Content-Type
    ;; on [:rf/response :headers], so the earlier default-when-absent form
    ;; could NEVER apply the opt — a caller `:content-type` was silently
    ;; dropped. With the opt now defaulting to nil (removed from
    ;; `handler-defaults`), a nil `content-type` leaves the runtime seed /
    ;; app-`set-header` value in control; a non-nil opt overrides it.
    (ssr-response->ring-response post-render-resp html content-type)))

(defn project-render-throw->ring-response
  "Route a render-time `Throwable` through the SSR error projector and
  materialise the projected (fail-closed, non-200) Ring error response.
  Shared by the non-streaming `build-full-response` catch arm AND the
  streaming `stream-handler` shell phase (rf2-r06pc) so BOTH render-side
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

  rf2-nu5w48 / rf2-tqjc7h: the WHOLE error-projection + error-body render path
  runs inside the request frame's `with-frame` scope, mirroring the happy-path
  `build-full-response*` binding (rf2-bzw8gd). `project-render-exception!`
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
      ;; rf2-nncni3 — do NOT apply the handler's `:content-type` override on
      ;; the error path. That opt labels the SUCCESSFUL rendered body (an XML
      ;; feed / bespoke type); the projected error body is always an HTML
      ;; fallback, so it keeps the runtime's `text/html; charset=utf-8` seed
      ;; (Spec 011 §Status defaults) — labelling an HTML error page as, say,
      ;; `application/xml` would be a wire-content mismatch. An app that set a
      ;; custom Content-Type via `:rf.server/set-header` before the throw
      ;; still has it on `resp*` (unchanged behaviour); we only decline to
      ;; force the construction-time opt here.
      (ssr-response->ring-response resp* body-html))))

(defn build-full-response
  "Render the caller's `:root-view` against `frame-id`, build the
  hydration payload, wrap in the html-shell, and materialise to a Ring
  response.

  Per rf2-6t36h the root-view resolves EXACTLY ONCE per request — both
  the wire HTML (via `render-to-string` + its embedded
  `data-rf-render-hash`) and the payload's `:rf/render-hash` derive
  from the same hiccup tree, so a non-idempotent fn-form root-view
  cannot fire a spurious `:rf.ssr/hydration-mismatch` on a successful
  hydration.

  Per rf2-zwgsv (Mike decision rf2-i9f0g Option B) — a render-time
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

  The try/catch is the unification point: render-time AND drain-time
  exceptions both go through the projector, so the wire body contract
  is uniform regardless of where the failure originated (rf2-zwgsv).

  Note: the outer `ssr-handler`'s `:on-error` hook still wraps this
  call. The remaining exceptions it catches are Ring-layer / transport
  failures the projector can't see (e.g. an exception in the host's
  Content-Type negotiator, or a re-throw from the projector pipeline
  itself when no server frame is registered). Those hit the fixed-
  string default per rf2-kzvwq's topology-leak rule."
  [frame-id resp opts]
  (try
    (build-full-response* frame-id resp opts)
    (catch Throwable t
      ;; The projector stamps :status onto the response accumulator and
      ;; the projected (fail-closed, non-200) error body is materialised
      ;; through the SAME path the streaming shell phase now reuses
      ;; (rf2-r06pc — `project-render-throw->ring-response`). Render-time
      ;; AND drain-time exceptions thus share one wire-body contract,
      ;; and the streaming + non-streaming shell-failure surfaces project
      ;; identically (Spec 011 §Server error projection §View-time
      ;; exceptions).
      (project-render-throw->ring-response frame-id t opts))))
