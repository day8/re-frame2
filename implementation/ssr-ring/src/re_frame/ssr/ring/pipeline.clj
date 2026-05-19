(ns re-frame.ssr.ring.pipeline
  "The Ring request lifecycle pipeline.

  The handler is a 4-step pipeline:

    1. `setup-request-frame!` — gensym frame-id, populate request slot,
       register the per-request frame (which drains :on-create
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
  exceptions). Pre-rf2-zwgsv a render-side throw escaped to the
  outer ssr-handler try/catch and produced the fixed
  `\"Internal error\"` text (rf2-kzvwq) — different body contract
  on the wire depending on where the failure originated. Unified
  now."
  (:require [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.html-helpers :as html-helpers]
            [re-frame.ssr.ring.headers :as headers]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.payload :as payload]))

(set! *warn-on-reflection* true)

(defn ssr-response->ring-response
  "Materialise the runtime's resolved response accumulator (per
  Spec 011 §HTTP response contract) into a Ring response map. The
  `:body` arg is the rendered HTML (or nil for redirect-only
  responses). `:redirect` short-circuits per Spec 011 §Redirect
  precedence — status + Location header, no body.

  The optional 3-arg form (rf2-uj9z8) accepts a `default-content-type`
  that's stamped onto the Ring header map if (and only if) the response
  pairs don't already carry a Content-Type (case-insensitive). The
  defaulting happens INSIDE the single header-fold pass — pre-fix the
  pipeline called `ensure-content-type` over the pairs vector, then
  `headers->ring-map` re-walked the same pairs to fold them. Single
  pass now."
  ([resp body] (ssr-response->ring-response resp body nil))
  ([{:keys [status headers cookies redirect]} body default-content-type]
   (if redirect
     (let [{:keys [location url to] redirect-status :status} redirect
           target (or location url to)]
       {:status  (or redirect-status status 302)
        :headers (-> (headers/headers->ring-map+default-content-type
                       headers default-content-type)
                     (headers/append-set-cookies cookies)
                     (cond-> target (assoc "Location" target)))
        :body    ""})
     {:status  (or status 200)
      :headers (-> (headers/headers->ring-map+default-content-type
                     headers default-content-type)
                   (headers/append-set-cookies cookies))
      :body    (or body "")})))

(defn setup-request-frame!
  "Register a per-request frame and populate the request slot. Returns
  `{:frame-id fid}` on success, or `{:short-circuit ring-response}` when
  setup fails (the caller short-circuits to that response, skipping
  render).

  The slot is populated BEFORE `reg-frame` so the synchronous
  `:on-create` drain can resolve the `:rf.server/request` cofx (Spec
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
  [{:keys [on-create fx-overrides ssr on-error]} request]
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
  (let [fid (keyword "rf.frame" (str (gensym "f")))]
    (ssr/set-request! fid request)
    (try
      (rf/reg-frame fid
        (cond-> {:doc       "ssr-ring per-request frame"
                 :platform  :server
                 ;; Audit rf2-cegm7 A2 / rf2-j54ee: pass :on-create
                 ;; verbatim. Handlers read the request via the
                 ;; `:rf.server/request` cofx — the spec-documented
                 ;; canonical surface.
                 :on-create (lifecycle/validate-on-create! on-create)}
          fx-overrides (assoc :fx-overrides fx-overrides)
          ssr          (assoc :ssr           ssr)))
      {:frame-id fid}
      (catch Throwable t
        (ssr/clear-request! fid)
        (lifecycle/destroy-frame-quietly! fid)
        {:short-circuit (on-error request t)}))))

(defn ^:private render-error-body
  "Build a minimal HTML body from a public-error map. Used by the
  render-time projector path (rf2-zwgsv) when `render-to-string`
  throws and the host can no longer rely on the user's root-view to
  produce wire bytes. The body is fully escaped through
  `escape-html` — the public-error's `:message` is caller-controlled
  (custom projectors may produce arbitrary strings) so we treat it
  as untrusted-for-HTML.

  Carries no internal trace detail — Spec 011 §Server error
  projection §Where sanitisation happens locks the wire surface to
  the four public-error keys. The internal Throwable already rode
  the trace bus via `project-render-exception!`'s
  `trace/emit-error!` call; monitoring listeners see it, the wire
  does not."
  [{:keys [status code message]}]
  (let [status* (or status 500)
        code*   (when code (name code))
        msg*    (or message "Something went wrong")]
    (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
         "<title>" (html-helpers/escape-html msg*) "</title>"
         "</head><body>"
         "<h1>" (html-helpers/escape-html msg*) "</h1>"
         (when code*
           (str "<p data-rf-error-code=\""
                (html-helpers/escape-attr code*) "\">"
                "Error code: " (html-helpers/escape-html code*)
                " (status " status* ")"
                "</p>"))
         "</body></html>")))

(defn ^:private build-full-response*
  "The non-error path of `build-full-response`. Split into its own fn
  so the outer try/catch (rf2-zwgsv render-time projector unification)
  reads as a simple wrap of the rendering / payload / shell pipeline,
  not as a 30-line body in the catch's scope."
  [frame-id resp
   {:keys [root-view emit-hash? version schema-digest payload-keys
           payload-policy html-shell content-type]
    :as   opts}]
  (let [hiccup      (rf/with-frame frame-id (lifecycle/resolve-root-view root-view))
        ;; rf2-i15nh / rf2-atmvj: compute the structural hash ONCE per
        ;; request. The same hex feeds the root-element
        ;; `data-rf-render-hash` (via render-to-string's `:render-hash`
        ;; opt) AND the payload's `:rf/render-hash`. Pre-rf2-i15nh
        ;; render-to-string computed the hash internally on the
        ;; `:emit-hash?` branch and the pipeline called render-tree-hash
        ;; again here — two full canonical-EDN walks per request. The
        ;; opt-threaded form drops it to one. When `:emit-hash?` is
        ;; false the hash is still needed for the payload slot.
        hash-str    (ssr/render-tree-hash hiccup)
        body-html   (rf/with-frame frame-id
                      (ssr/render-to-string hiccup
                                            {:doctype?    false
                                             :emit-hash?  emit-hash?
                                             :render-hash (when emit-hash? hash-str)}))
        app-db      (rf/get-frame-db frame-id)
        payload     (payload/build-payload frame-id app-db hash-str
                                           {:version        version
                                            :schema-digest  schema-digest
                                            :payload-keys   payload-keys
                                            :payload-policy payload-policy})
        payload-edn (pr-str payload)
        ;; rf2-4dra9 / rf2-h2ujj: resolve the active route's :head
        ;; (or default-head fallback). The head fragment goes through
        ;; the shell as the :head opt; the :html-attrs / :body-attrs
        ;; bags ride alongside so the shell can stamp them on <html>
        ;; / <body> per Spec 011 §Default flow step 4.
        ;;
        ;; Callers that supplied an explicit :head string take
        ;; precedence — they chose to bypass route-driven head
        ;; resolution, and an explicit string carries no attr-bag
        ;; sidechannel (an explicit head opt is the escape hatch for
        ;; bespoke fragments).
        {:keys [head-html html-attrs body-attrs]}
        (if (:head opts)
          {:head-html (:head opts) :html-attrs nil :body-attrs nil}
          (lifecycle/resolve-head frame-id))
        shell-opts  (assoc opts
                           :head        head-html
                           :html-attrs  html-attrs
                           :body-attrs  body-attrs)
        html        (html-shell body-html payload-edn shell-opts)]
    ;; Content-Type defaulting (rf2-uj9z8): pre-fix the pipeline called
    ;; ensure-content-type over the pairs vector, then
    ;; ssr-response->ring-response re-walked the same pairs to fold them
    ;; into the Ring header map — two passes. The 3-arg form folds and
    ;; defaults in one walk. The SSR runtime defaults [:rf/response
    ;; :headers] to include content-type so the default is usually a
    ;; no-op, but we still pass `content-type` through so an opts
    ;; override and absence-of-default both work.
    (ssr-response->ring-response resp html content-type)))

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

  Pre-rf2-zwgsv this code didn't try/catch — render-time throws
  bubbled to the outer `ssr-handler` try/catch and got the fixed
  `\"Internal error\"` body (rf2-kzvwq). That left two body
  contracts on the wire depending on where the failure originated:
  drain-time → projector body; render-time → fixed string. The
  unification ensures both go through the projector.

  Note: the outer `ssr-handler`'s `:on-error` hook still wraps this
  call. The remaining exceptions it catches are Ring-layer / transport
  failures the projector can't see (e.g. an exception in the host's
  Content-Type negotiator, or a re-throw from the projector pipeline
  itself when no server frame is registered). Those still hit the
  fixed-string default per rf2-kzvwq's topology-leak rule."
  [frame-id resp opts]
  (try
    (build-full-response* frame-id resp opts)
    (catch Throwable t
      (let [public        (ssr/project-render-exception! frame-id t)
            ;; The projector stamped :status onto the response
            ;; accumulator inside `project-render-exception!`;
            ;; peek-response returns the resolved snapshot without
            ;; re-draining (the drain already happened). When
            ;; projection returned nil (e.g. no server frame) the
            ;; resolved response is whatever was last accumulated;
            ;; the render-error-body fallback below still emits the
            ;; locked-500 generic shape, so the wire still carries
            ;; a well-formed body.
            resp*         (ssr/peek-response frame-id)
            public*       (or public
                              {:status 500 :code :internal-error
                               :message "Something went wrong"
                               :retryable? false})
            body-html     (render-error-body public*)
            content-type  (:content-type opts)]
        (ssr-response->ring-response resp* body-html content-type)))))
