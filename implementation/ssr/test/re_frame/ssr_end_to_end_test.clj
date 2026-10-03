(ns re-frame.ssr-end-to-end-test
  "Comprehensive SSR request-lifecycle coverage. Per Spec 011.

  The smoke-test suite already pins each SSR concern in isolation:
  render-to-string basics, hydration metadata stash, render-tree-hash
  stability, the :http/get :fx-overrides redirect, and the
  dispatch-sync → render-to-string → embedded hash smoke.

  This namespace stitches the whole flow together in one place — the
  canonical happy path AND the structured-error edges (multi-status,
  multi-cookie, redirect short-circuit, head-hash mismatch). The shape
  mirrors what a real SSR host would do per request:

    1. Build a per-request frame via make-frame {:initial-events [[:rf/server-init request]]}.
    2. The on-create event dispatches :http/get (stubbed via :fx-overrides).
    3. The drain settles synchronously — app-db-value reflects post-drain state.
    4. render-to-string against the registered root view emits HTML
       carrying data-rf-render-hash on the root element.
    5. Build a serialisable payload: {:rf/version :rf/frame-id :rf/app-db :rf/render-hash}.
    6. On a separate (client) frame, dispatch-sync [:rf/hydrate payload]
       — the client app-db becomes the server's app-db. Subsequent
       client render produces the same hash. Mutate, re-render, hash
       differs, :rf.ssr/hydration-mismatch trace fires.

  The :rf.server/* fx (set-status / set-header / append-header /
  set-cookie / delete-cookie / redirect) are registered by the runtime
  at re-frame.ssr namespace-load time (per Spec 011 §HTTP response
  contract). The accumulator lives in a framework-
  private side-channel atom keyed by frame-id (Spec 011
  §Response storage substrate; NOT in app-db, so it never rides the
  hydration payload); tests read the resolved shape via
  re-frame.ssr/get-response.

  ## Posture split

  This namespace runs in the real `-Dre-frame.debug=false` lane
  (`scripts/test-ssr-prod-gate.sh`). The SECURITY GATES below (CR/LF and
  NUL in header values and cookie attributes, the cookie-attribute grammar,
  the safe-redirect five-step open-redirect gate) are production-live and
  reject in that lane. What is dev-only is the OBSERVATION through
  `re-frame.trace`, which the gate empties.

  So the split here deliberately favours always-on captures over guards,
  because a `(when interop/debug-enabled? …)` arm around a
  security assertion buys the lane nothing: the arm does not run in the
  posture that ships, and the gate it describes does.

    ALWAYS-ON (the large majority). `capture-fx-traces!` and
    `capture-safe-redirect-traces!` read the production-survivable
    `:errors` axis. `re-frame.fx`'s `emit-fx-error!` and
    `re-frame.ssr.response`'s `emit-safe-redirect-error!` both fan every
    rejection along BOTH axes, so the same failure is observable in a
    release build — these assertions adjudicate the posture that ships.

    DEV ARMS, inside `(when interop/debug-enabled? …)`. Three
    things are genuinely dev-only and are marked DEV ARM at each site:
    the `:rf.warning/*` family (`:rf.ssr/multiple-status-set`,
    `-multiple-redirects`), the `:rf.ssr/hydration-mismatch` /
    head-mismatch traces, and the RICH diagnostics that the always-on
    record deliberately does not carry — the Spec 010 step-5
    `:rf.error/schema-validation-failure`, and safe-redirect's `:allowlist`,
    `:host` and raw `:scheme` tags, which
    `re-frame.ssr.egress/safe-redirect-record-slots` excludes on purpose (an
    app's own security configuration must not ride off-box, and neither must
    the caller's unbounded URL components — see the `capture-safe-redirect-*`
    contracts).

  NEGATIVES TRAVEL WITH THEIR POSITIVES. A `(is (empty? traces))` or
  `(is (not-any? … ))` over the dev trace ring passes AUTOMATICALLY under
  this gate, where the ring is empty for every input — the quiet half of the
  same false green. Each one below either sits in the dev arm beside the
  positive it belongs to, or is REAL because its capture is always-on."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.response :as rf.ssr.response]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

;; The canonical reset-runtime fixture lives in `re-frame.ssr.test-fixture`
;; — one source of truth for the registrar/side-channel/ns-
;; reload cycle that every ssr-artefact JVM test needs between :each.
(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- resolve-tree
  "Resolve a `[view-fn args...]` reference under a frame so the rendered
  tree reflects the frame's current app-db. Used to compute a state-
  dependent hash that mirrors what a real client recompute would do.

  A view reference is a CALLABLE head (the Var `reg-view`
  defs, or `(rf/view :id)`), never a keyword: a keyword head is a DOM /
  custom element on every host, so it has nothing to resolve and is
  returned untouched. The `keyword?` guard is load-bearing — a keyword is
  itself `ifn?`, so a bare `ifn?` test would `apply` a plain DOM head like
  `:div` to its own children."
  [frame-id render-tree]
  (rf/with-frame frame-id
    (let [head (first render-tree)]
      (if (and (ifn? head) (not (keyword? head)))
        (apply head (rest render-tree))
        render-tree))))

;; ===========================================================================
;; ssr-full-request-lifecycle — the canonical happy path
;; ===========================================================================

(deftest ssr-full-request-lifecycle
  (testing "request → on-create dispatch → :http/get stub → drain → render → payload → hydrate → match → mutate → mismatch"
    ;; ---- registry: events, sub, view, real :http/get fx (no-op shell) -----
    (rf/reg-fx :http/get
      {:platforms #{:server :client}}
      (fn [_ _] nil))                                                ;; real impl absent on JVM; the override below replaces it

    (rf/reg-fx :http/get.canned-articles
      {:platforms #{:server :client}}
      (fn [{:keys [frame]} {:keys [on-success]}]
        ;; The stub synthesises a synchronous "response" by dispatching
        ;; the :on-success event (with the canned body conj'd) on the
        ;; ACTIVE frame — :rf.server/* fx receive {:frame ...} as the
        ;; first arg per Spec 002 §Routing the dispatch envelope.
        (when on-success
          (rf/dispatch (conj on-success
                             [{:id "a" :title "Article A" :body "Body A"}
                              {:id "b" :title "Article B" :body "Body B"}])
                       {:frame frame}))))

    (rf/reg-event :rf/server-init
      (fn [{:keys [db]} [_ request]]
        {:db (-> db
                 (assoc :request request)
                 (assoc-in [:rf.runtime/routing :current] {:route-id :route/articles}))
         :fx [[:http/get {:url        "/api/articles"
                          :on-success [:articles/loaded]}]]}))

    (rf/reg-event :articles/loaded
      (fn [{:keys [db]} [_ articles]]
        {:db (assoc db :articles articles)}))

    (rf/reg-sub :articles (fn [db _] (:articles db)))
    ;; Plain-fn surface (reg-view*) with an explicit id: the render below
    ;; reaches the view through `(rf/view :pages/articles)`, the callable
    ;; handle keyed on that id, so the id is named here rather than
    ;; auto-derived.
    (rf/reg-view* :pages/articles
      (fn []
        (let [arts (rf/subscribe-once [:articles])]
          [:div.page
           [:h1 "Recent articles"]
           [:ul
            (for [{:keys [id title body]} arts]
              ^{:key id} [:li [:h3 title] [:p body]])]])))

    ;; ---- (1) per-request server frame -------------------------------------
    (let [server-frame (rf.frame/make-anon-frame-record!
                         {:doc          "SSR request frame"
                          :platform     :server
                          :initial-events    [[:rf/server-init {:uri "/articles"}]]
                          :fx-overrides {:http/get :http/get.canned-articles}})
          ;; (2)+(3) drain settled via :initial-events + dispatch-sync chain
          server-db    (rf/app-db-value server-frame)]

      (is (= 2 (count (:articles server-db)))
          "post-drain server app-db carries the canned articles")
      (is (= "Article A" (-> server-db :articles first :title)))
      (is (= {:uri "/articles"} (:request server-db))
          "the request map flowed through :rf/server-init into app-db")

      ;; ---- (4) render against the registered root view -------------------
      (let [render-tree   [(rf/view :pages/articles)]
            html          (rf/with-frame server-frame
                            (rf.ssr/render-to-string
                              render-tree
                              {:render-hash (rf.ssr/render-tree-hash render-tree)}))
            ;; The data-rf-render-hash embedded on the wire is the input-
            ;; tree hash (per render-to-string in ssr.cljc) — stable across
            ;; renders of the same view-ref. The hydration payload below
            ;; carries the RESOLVED-tree hash (state-dependent) so the
            ;; client can re-render and compare a state-derived value.
            server-hash   (rf.ssr/render-tree-hash
                            (resolve-tree server-frame render-tree))]
        (is (str/includes? html "Article A")
            "rendered HTML carries the title from server app-db")
        (is (str/includes? html "Article B"))
        (is (re-find #"<div[^>]*data-rf-render-hash=\"[0-9a-f]{8}\""
                     html)
            "root <div> carries data-rf-render-hash")
        (is (some? server-hash))

        ;; ---- (5) build serialisable payload -----------------------------
        (let [payload (rf.ssr.payload-policy/build-payload
                        server-frame server-db server-hash {})]
          (is (= #{:rf/version :rf/frame-id :rf/app-db :rf/render-hash}
                 (set (keys payload)))
              "payload carries the canonical four keys")
          (is (= 1 (:rf/version payload)))
          (is (= server-frame (:rf/frame-id payload)))
          (is (= server-db (:rf/app-db payload)))
          (is (= server-hash (:rf/render-hash payload))
              "payload carries the resolved render-tree hash")

          ;; ---- (6) hydration on a separate "client" frame ---------------
          (let [client-frame (rf.frame/make-anon-frame-record!
                               {:doc      "Hydrated client frame"
                                :platform :client})
                ;; In a real SSR deployment the server and client
                ;; carry the SAME logical frame id (e.g. `:app/main`) — the
                ;; payload's `:rf/frame-id` is validated against the client
                ;; hydration target and a present-and-different value is
                ;; rejected as `:rf.error/hydration-frame-id-
                ;; mismatch`. This JVM lifecycle uses two distinct synthetic
                ;; frame instances (one `:server`, one `:client`) to exercise
                ;; both platforms in one process; re-stamp the payload's
                ;; `:rf/frame-id` to the client target so the wire stamp
                ;; matches the frame it hydrates into (the deployment-shape
                ;; invariant), exactly as `build-server-payload`-under-the-
                ;; client-frame does in the boot helper tests. The payload-
                ;; SHAPE assertions above pin the
                ;; server stamp on the as-built payload.
                hydrate-payload (assoc payload :rf/frame-id client-frame)]
            (rf/dispatch-sync [:rf/hydrate hydrate-payload] {:frame client-frame})
            (let [client-db (rf/app-db-value client-frame)
                  ;; EP-0001: the hydration metadata is durable
                  ;; runtime-db state.
                  client-rt (:rf.db/runtime (rf/frame-state-value client-frame))]
              ;; The server's app-db replaced the client's empty app-db.
              (is (= (:articles server-db) (:articles client-db))
                  ":rf/hydrate replaced the client app-db with payload's :rf/app-db")
              ;; The server hash was stashed for verify-hydration!.
              (is (= server-hash (get-in client-rt [:rf.runtime/ssr :hydration :server-hash])))
              (is (= 1            (get-in client-rt [:rf.runtime/ssr :hydration :version]))))

            ;; First client render — same view, same hydrated state, same
            ;; resolved tree, same hash. Resolve under the client frame so
            ;; the subscribe-once reads the hydrated client app-db.
            (let [client-hash-1 (rf.ssr/render-tree-hash
                                  (resolve-tree client-frame render-tree))
                  match-traces  (atom [])]
              (rf/register-listener! :trace ::match (fn [ev] (swap! match-traces conj ev)))
              (rf.ssr/verify-hydration!
                client-frame client-hash-1)
              (rf/unregister-listener! :trace ::match)
              (is (= server-hash client-hash-1)
                  "first client render hashes identically to the server hash")
              ;; DEV ARM — the `:rf.ssr/hydration-mismatch` TRACE
              ;; is dev-only (Spec 011 §Hydration mismatch: the recovery is
              ;; `:warned-and-replaced`). The CATEGORY is not
              ;; dev-only — it also fans an always-on record — but
              ;; this assertion reads `@match-traces`, a TRACE listener, so
              ;; it is the trace channel that puts it in this arm, not the
              ;; category. This is also a NEGATIVE over the trace ring, so
              ;; under the production gate it would pass with hydration
              ;; verification removed entirely — it sits in the arm WITH
              ;; the positive it discriminates against, not outside it. The
              ;; hash equality above is the posture-independent half and runs
              ;; in this lane.
              (when rf.interop/debug-enabled?
                (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %))
                              @match-traces)
                    "no :rf.ssr/hydration-mismatch trace when hashes agree")))

            ;; (7) Mutate the hydrated app-db; re-render; hash differs;
            ;;     verify-hydration! emits the mismatch trace.
            (rf/reg-event :articles/append
              (fn [{:keys [db]} [_ extra]]
                {:db (update db :articles conj extra)}))
            (rf/dispatch-sync [:articles/append
                               {:id "c" :title "Article C" :body "Body C"}]
                              {:frame client-frame})

            (let [client-hash-2   (rf.ssr/render-tree-hash
                                     (resolve-tree client-frame render-tree))
                  mismatch-traces (atom [])]
              (rf/register-listener! :trace ::mismatch (fn [ev] (swap! mismatch-traces conj ev)))
              (rf.ssr/verify-hydration!
                client-frame client-hash-2)
              (rf/unregister-listener! :trace ::mismatch)

              (is (not= server-hash client-hash-2)
                  "mutating the hydrated db changes the render hash")
              ;; DEV ARM — the mismatch WARNING. Its
              ;; production-visible half is the hash inequality asserted just
              ;; above: the framework computes the divergence in every build,
              ;; and only the telling-you-about-it is dev-gated.
              (when rf.interop/debug-enabled?
                (is (some (fn [ev]
                            (and (= :rf.ssr/hydration-mismatch (:operation ev))
                                 (= :error (:op-type ev))
                                 (= server-hash    (:server-hash (:tags ev)))
                                 (= client-hash-2  (:client-hash (:tags ev)))
                                 (= :warned-and-replaced (:recovery ev))))
                          @mismatch-traces)
                    (str "expected :rf.ssr/hydration-mismatch trace; saw: "
                         (pr-str (mapv :operation @mismatch-traces))))))))))))

;; ===========================================================================
;; ssr-set-status-precedence — last write wins; warn on multi-set
;; ===========================================================================
;;
;; Per Spec 011 §Multiple-status policy: two :rf.server/set-status fx in a
;; single drain → last write wins AND a :rf.warning/multiple-status-set trace
;; fires. The accumulator is a per-frame side channel read through
;; `re-frame.ssr/get-response`; it never enters app-db.

(defn- get-response
  "Read the resolved response accumulator for a frame."
  [frame-id]
  (rf.ssr/get-response frame-id))

(deftest ssr-set-status-precedence
  (testing "two :rf.server/set-status fx → last write wins + :rf.warning/multiple-status-set"
    (let [traces (atom [])]
      (rf/reg-event :auth/forbid
        (fn [_ _]
          {:fx [[:rf.server/set-status 401]
                [:rf.server/set-status 403]]}))                          ;; second write replaces

      (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
        (rf/register-listener! :trace ::status (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/forbid] {:frame f})
        (rf/unregister-listener! :trace ::status)

        (is (= 403 (:status (get-response f)))
            "last write wins — the response status is 403")

        ;; DEV ARM — the `:rf.warning/*` family is genuinely
        ;; dev-only: per Spec 011 §Multiple-status policy the POLICY is
        ;; last-write-wins and the warning is advice to the programmer, not
        ;; part of the response. The policy itself is asserted above and runs
        ;; in this lane; only the advice is gated.
        (when rf.interop/debug-enabled?
          (is (some (fn [ev]
                      (and (= :rf.warning/multiple-status-set (:operation ev))
                           (= [401 403] (:writes (:tags ev)))
                           (= 403       (:final-status (:tags ev)))
                           (= :warned-and-replaced (:recovery ev))))
                    @traces)
              (str "expected :rf.warning/multiple-status-set trace; saw: "
                   (pr-str (mapv :operation @traces)))))))))

;; ===========================================================================
;; ssr-multi-cookie — multiple set-cookie fxs accumulate as STRUCTURED MAPS
;; ===========================================================================

(deftest ssr-multi-cookie
  (testing "multiple :rf.server/set-cookie fxs accumulate; runtime stores structured maps not strings"
    (rf/reg-event :auth/establish
      (fn [_ _]
        {:fx [[:rf.server/set-cookie {:name      "session"
                                      :value     "abc123"
                                      :path      "/"
                                      :http-only true
                                      :secure    true
                                      :same-site :lax}]
              [:rf.server/set-cookie {:name    "csrf"
                                      :value   "tok-xyz"
                                      :path    "/"
                                      :secure  true}]
              [:rf.server/set-cookie {:name    "tracker"
                                      :value   "off"
                                      :max-age 0}]]}))

    (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf/dispatch-sync [:auth/establish] {:frame f})

      (let [cookies (:cookies (get-response f))]
        (is (= 3 (count cookies))
            "three cookies accumulated in :cookies")
        ;; Lock: the runtime emits STRUCTURED MAPS — cookie-attribute
        ;; serialisation (RFC 6265 wire form, attribute quoting) is the
        ;; host adapter's job per Spec 011 §Cookie shape.
        (is (every? map? cookies)
            "every cookie is a structured map, not a serialised string")
        (is (every? (fn [c] (every? string? [(:name c) (:value c)]))
                    cookies)
            "every cookie has :name and :value as strings")
        (is (= "session" (-> cookies (nth 0) :name)))
        (is (= "csrf"    (-> cookies (nth 1) :name)))
        (is (= "tracker" (-> cookies (nth 2) :name)))
        (is (= :lax (-> cookies (nth 0) :same-site))
            ":same-site stays a keyword in the map; the adapter renders 'Lax'")
        (is (true? (-> cookies (nth 0) :secure))
            "boolean attrs stay booleans in the map")
        (is (zero? (-> cookies (nth 2) :max-age))
            "delete-marker semantics live in the map; not pre-serialised")))))

;; ===========================================================================
;; ssr-delete-cookie — :rf.server/delete-cookie emits a Max-Age=0 marker
;; ===========================================================================

(deftest ssr-delete-cookie
  (testing ":rf.server/delete-cookie writes a structured cookie with :max-age 0 and empty :value"
    (rf/reg-event :auth/logout
      (fn [_ _]
        {:fx [[:rf.server/delete-cookie {:name "session" :path "/"}]]}))

    (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf/dispatch-sync [:auth/logout] {:frame f})
      (let [[c] (:cookies (get-response f))]
        (is (= "session" (:name c)))
        (is (= ""        (:value c)))
        (is (zero?       (:max-age c)))
        (is (= "/"       (:path c))
            ":path passes through to the delete marker so the browser scope-matches")))))

;; ===========================================================================
;; ssr-set-and-append-header — :rf.server/set-header replaces; append accumulates
;; ===========================================================================

(deftest ssr-set-and-append-header
  (testing ":rf.server/set-header replaces case-insensitively; :rf.server/append-header preserves duplicates"
    (rf/reg-event :hdr/set-then-replace
      (fn [_ _]
        ;; First :set-header writes the default; the second replaces it
        ;; (case-insensitive name match per Spec 011 §Header replacement).
        {:fx [[:rf.server/set-header {:name "X-Foo" :value "first"}]
              [:rf.server/set-header {:name "x-foo" :value "second"}]]}))
    (rf/reg-event :hdr/append-twice
      (fn [_ _]
        {:fx [[:rf.server/append-header {:name "Set-Cookie" :value "a=1"}]
              [:rf.server/append-header {:name "Set-Cookie" :value "b=2"}]]}))

    (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf/dispatch-sync [:hdr/set-then-replace] {:frame f})
      (rf/dispatch-sync [:hdr/append-twice]     {:frame f})
      (let [hdrs  (:headers (get-response f))
            x-foo (filter (fn [[n _]] (= "x-foo" (clojure.string/lower-case n))) hdrs)
            sc    (filter (fn [[n _]] (= "set-cookie" (clojure.string/lower-case n))) hdrs)]
        (is (= 1 (count x-foo))
            ":rf.server/set-header replaced the prior X-Foo header")
        (is (= "second" (-> x-foo first second))
            "the second set-header value won")
        (is (= 2 (count sc))
            ":rf.server/append-header preserved both Set-Cookie entries")
        (is (= ["a=1" "b=2"] (mapv second sc))
            "append-header preserves source order")))))

;; ===========================================================================
;; CRLF injection in set-header / append-header / redirect
;;
;; Header values flow from event-handler input through the
;; :rf.server/set-header / :rf.server/append-header / :rf.server/redirect
;; fx straight to the Ring response map. A value with embedded CR/LF
;; would split the header on the wire — attacker forges Set-Cookie /
;; auth-related second headers. The fx boundary fails fast with
;; :rf.error/header-invalid-value / :rf.error/redirect-invalid-location.
;;
;; Fail fast rather than strip-and-warn — a
;; header value containing CR/LF has no safe interpretation.
;; ===========================================================================

(defn- capture-fx-traces!
  "Record every `:rf.error/fx-handler-exception` the framework emitted during
  `body-fn`, reading BOTH error axes and returning ONE sequence normalised to
  the dev-trace shape `{:operation … :tags …}`. Strips both callbacks in
  `finally` so a failing body doesn't leak listeners.

  WHY BOTH AXES, AND WHY THIS IS NOT A DEV ARM. Under
  `-Dre-frame.debug=false` the `:trace` ring is empty, so a `:trace`-only
  helper would leave roughly sixty assertions below — the CR/LF and NUL
  injection gates on `set-header` / `append-header` / `redirect`, the whole
  cookie-attribute grammar, the retired redirect spellings — seeing nothing.
  The gates themselves are a `throw` at the fx boundary in
  `re-frame.ssr.response`, unconditional in every build; only the `:trace`
  OBSERVATION is dev-only.

  Guarding them would therefore be wrong twice over: it
  would move a live security boundary's proof out of the posture that ships,
  and it would leave the lane asserting nothing about the artefact's most
  load-bearing code. `re-frame.fx`'s `emit-fx-error!` fans every
  contained fx exception through `error-emit/emit-error-both!` — axis 1 the
  ALWAYS-ON listener record (`{:error :event-id :frame :exception …}`, the
  off-box shipper's source), axis 2 the dev-only `trace/emit-error!` — so
  the production-visible witness is there to be read.

  The union is normalised to one shape for `expect-fx-error-keyword!` and
  `fx-error-extra`: both read only `(-> ev :tags :exception)`, and that is the
  SAME exception object on both axes (axis 1 carries it in `:exception`,
  axis 2 in `:tags :exception`). In a dev build both fire and the sequence
  carries two entries per error; no consumer counts them — each asks `seq`
  or `some` — and the duplication is one failure seen twice, not two."
  [body-fn]
  (let [traces (atom [])
        tag    (keyword "rf2-lwtlk" (str "fx-cap-" (name (gensym "c"))))]
    (rf/register-listener! :trace tag
      (fn [ev]
        (when (= :rf.error/fx-handler-exception (:operation ev))
          (swap! traces conj ev))))
    (rf.error-emit/register-error-listener! tag
      (fn [r]
        (when (= :rf.error/fx-handler-exception (:error r))
          (swap! traces conj {:operation (:error r) :tags r}))))
    (try
      (body-fn)
      @traces
      (finally
        (rf/unregister-listener! :trace tag)
        (rf.error-emit/unregister-error-listener! tag)))))

(defn- expect-fx-error-keyword!
  "Assert that the `traces` collection (output of `capture-fx-traces!`)
  carries an :rf.error/fx-handler-exception whose nested exception's
  message contains `error-kw`'s name string. The fx-side validators
  throw with the error keyword as
  the ex-info message, so the substring check is reliable."
  [traces error-kw context-str]
  (let [hits (filter
               (fn [ev]
                 (let [e (-> ev :tags :exception)]
                   (and e (str/includes? (str (.getMessage e))
                                         (str error-kw)))))
               traces)]
    (is (seq hits)
        (str context-str " — expected an :rf.error/fx-handler-exception"
             " trace carrying " error-kw
             "; saw: " (pr-str (mapv (comp :operation) traces))))))

(defn- fx-error-extra
  "Return the `ex-data` of the first captured :rf.error/fx-handler-exception
  trace whose inner exception carries `error-kw`. The cookie
  contract stores WHICH attribute carried the injection char in
  the ex-data's `:attribute` slot rather than in the error id, so tests assert
  the offending attribute here. nil when no captured trace matches."
  [traces error-kw]
  (some (fn [ev]
          (let [e (-> ev :tags :exception)]
            (when (and e (str/includes? (str (.getMessage e)) (str error-kw)))
              (ex-data e))))
        traces))

(deftest ssr-header-fx-reject-injection-in-name-and-value
  (testing ":rf.server/set-header and :rf.server/append-header refuse a
            header-splitting value (CR / LF / NUL →
            :rf.error/header-invalid-value) and a name outside the RFC 7230
            §3.2.6 token grammar (:rf.error/header-invalid-name). Each
            surfaces as the inner cause of :rf.error/fx-handler-exception:
            the gate throws at the fx boundary, and the dispatch loop
            captures the fx exception and re-emits it."
    (doseq [[label fx-id args error-id]
            [["set-header: CRLF in :value"
              :rf.server/set-header
              {:name "X-Forwarded-For" :value "1.2.3.4\r\nSet-Cookie: admin=1"}
              :rf.error/header-invalid-value]
             ["set-header: bare LF in :value"
              :rf.server/set-header {:name "X-Probe" :value "lf\nbad"}
              :rf.error/header-invalid-value]
             ["set-header: bare CR in :value"
              :rf.server/set-header {:name "X-Probe" :value "cr\rbad"}
              :rf.error/header-invalid-value]
             ["set-header: NUL in :value"
              :rf.server/set-header {:name "X-Probe" :value (str "nul" (char 0) "bad")}
              :rf.error/header-invalid-value]
             ["append-header: CRLF in :value"
              :rf.server/append-header
              {:name "X-Audit" :value "ok\r\nSet-Cookie: forged=1"}
              :rf.error/header-invalid-value]
             ["set-header: CRLF in :name"
              :rf.server/set-header
              {:name "X-Test\r\nSet-Cookie: evil=1" :value "ok"}
              :rf.error/header-invalid-name]
             ["set-header: a separator in :name"
              :rf.server/set-header {:name "Bad: Name" :value "ok"}
              :rf.error/header-invalid-name]
             ["set-header: whitespace in :name"
              :rf.server/set-header {:name "Bad Name" :value "ok"}
              :rf.error/header-invalid-name]
             ["set-header: an empty :name"
              :rf.server/set-header {:name "" :value "ok"}
              :rf.error/header-invalid-name]
             ["set-header: parens in :name"
              :rf.server/set-header {:name "with(parens)" :value "ok"}
              :rf.error/header-invalid-name]
             ["set-header: NUL in :name"
              :rf.server/set-header {:name (str "nul" (char 0) "bad") :value "ok"}
              :rf.error/header-invalid-name]
             ["append-header: CRLF in :name"
              :rf.server/append-header
              {:name "X-Audit\r\nSet-Cookie: forged=1" :value "ok"}
              :rf.error/header-invalid-name]]]
      (rf/reg-event :hdr/probe-injection
        (fn [_ _]
          {:fx [[fx-id args]]}))
      (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:hdr/probe-injection] {:frame f})))]
        (expect-fx-error-keyword! traces error-id label)))))

(deftest ssr-redirect-retired-spelling-diagnostic-names-location
  (testing "the retired-spelling diagnostic NAMES the canonical
            :location key (ex-data :canonical-key + a :reason mentioning
            :location). This is the failure-mode lock: the error must point
            the programmer at the right spelling, and must be DISTINCT from
            the generic no-target/malformed-redirect warning path."
    (rf/reg-event :redirect/retired-spelling
      (fn [_ _]
        {:fx [[:rf.server/redirect {:url "/login"}]]}))
    (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:redirect/retired-spelling] {:frame f})))
          ex     (some (fn [ev]
                         (let [e (-> ev :tags :exception)]
                           (when (and e (str/includes? (str (.getMessage ^Throwable e))
                                                        ":rf.error/redirect-retired-target-key"))
                             e)))
                       traces)
          data   (ex-data ex)]
      (is (some? ex) "a redirect-retired-target-key exception was captured")
      (is (= :rf.error/redirect-retired-target-key (:rf.error/id data))
          "the ex-data carries the retired-target-key error id")
      (is (= :location (:canonical-key data))
          "the diagnostic names :location as the canonical key")
      (is (= [:url] (:retired-keys data))
          "the diagnostic names the offending retired spelling(s)")
      (is (str/includes? (str (:reason data)) ":location")
          "the :reason text names :location so the programmer rewrites the spelling"))))

(deftest ssr-redirect-trusted-path-has-no-url-shape-gate
  (testing "the caller-trusted :rf.server/redirect path applies
            NO structural URL-shape check: a `:location` carrying a raw
            space or other RFC 3986 shape quirk every browser accepts in a
            `Location` header PASSES through unchanged (only the CR/LF/NUL
            header-splitting gate applies)."
    (doseq [loc ["https://example.com/search?q=a b"   ;; raw unencoded space
                 "https://example.com/^"               ;; stray caret
                 "/path/{id"                            ;; unbalanced brace
                 "/path%zz"                             ;; malformed %-escape
                 "/path%1"]]                            ;; truncated %-escape
      (rf/reg-event :redirect/shape-quirk
        (fn [_ _]
          {:fx [[:rf.server/redirect {:location loc}]]}))
      (let [f    (rf.frame/make-anon-frame-record! {:platform :server :initial-events [[:redirect/shape-quirk]]})
            resp (get-response f)]
        (is (= loc (-> resp :redirect :location))
            (str "raw URL-shape quirk passes through the caller-trusted "
                 "redirect path (no URL-shape gate): " (pr-str loc))))))

  (testing "the caller-trusted redirect
            accepts arbitrary well-formed targets — absolute http(s) URLs to
            any origin, protocol-relative, relative refs, query / fragment /
            port / encoded-space edge cases — all flow through without error."
    (doseq [loc ["https://example.com/path?q=1&r=2#frag"
                 "https://other.example.org:8443/deep/path"
                 "//cdn.example.com/asset"
                 "/login"
                 "dashboard"
                 "a/b/c"
                 "/path%20with%20encoded%20space"]]
      (rf/reg-event :redirect/well-formed
        (fn [_ _]
          {:fx [[:rf.server/redirect {:location loc}]]}))
      (let [f    (rf.frame/make-anon-frame-record! {:platform :server :initial-events [[:redirect/well-formed]]})
            resp (get-response f)]
        (is (= loc (-> resp :redirect :location))
            (str "well-formed redirect :location flows through: " loc))))))

(deftest ssr-redirect-crlf-nul-gate-survives-on-both-fx
  (testing "the CR/LF/NUL header-splitting gate is the
            real invariant. Each injection char in a :location throws
            :rf.error/redirect-invalid-location on the caller-trusted
            :rf.server/redirect path."
    (doseq [[label loc] [["CR"  "https://example.com/a\rb"]
                         ["LF"  "https://example.com/a\nb"]
                         ["CRLF header-split" "https://example.com\r\nSet-Cookie: stolen=1"]
                         ["NUL" "https://example.com/a\u0000b"]]]
      (rf/reg-event :redirect/crlf-nul
        (fn [_ _]
          {:fx [[:rf.server/redirect {:location loc}]]}))
      (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:redirect/crlf-nul] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/redirect-invalid-location
          (str ":rf.server/redirect rejects " label " in :location")))))

  (testing "the SHARED CR/LF/NUL gate also runs (throwing the
            same :rf.error/redirect-invalid-location) on the caller-untrusted
            :rf.server/safe-redirect path — both fx keep the header-splitting
            invariant."
    (doseq [[label loc] [["CR"  "https://example.com/a\rb"]
                         ["LF"  "https://example.com/a\nb"]
                         ["NUL" "https://example.com/a\u0000b"]]]
      (rf/reg-event :safe-redirect/crlf-nul
        (fn [_ _]
          {:fx [[:rf.server/safe-redirect {:location loc}]]}))
      (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:safe-redirect/crlf-nul] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/redirect-invalid-location
          (str ":rf.server/safe-redirect rejects " label " in :location"))))))

(deftest ssr-header-clean-values-still-accepted
  (testing "legitimate header values flow
            through. Whitespace, semicolons, quoted-strings, full URLs are
            all valid (only CR/LF/NUL is banned)."
    (rf/reg-event :hdr/clean
      (fn [_ _]
        {:fx [[:rf.server/set-header {:name "Cache-Control"
                                      :value "no-cache, must-revalidate, max-age=0"}]
              [:rf.server/set-header {:name "X-Whitespace"
                                      :value "tab\there space"}]
              [:rf.server/redirect    {:location "https://example.com/path?q=1&r=2"}]]}))
    (let [f (rf.frame/make-anon-frame-record! {:platform :server :initial-events [[:hdr/clean]]})
          resp (get-response f)
          hdrs (:headers resp)]
      (is (some (fn [[k v]]
                  (and (= "Cache-Control" k)
                       (= "no-cache, must-revalidate, max-age=0" v)))
                hdrs)
          "clean header with commas / semicolons / spaces survives")
      (is (some (fn [[k v]]
                  (and (= "X-Whitespace" k)
                       (= "tab\there space" v)))
                hdrs)
          "a TAB is legal header whitespace and survives verbatim")
      (is (= "https://example.com/path?q=1&r=2"
             (-> resp :redirect :location))
          "clean redirect URL survives"))))

;; ===========================================================================
;; ssr-redirect-populates-redirect-and-status — :rf.server/redirect fills the
;; :redirect slot and flows its :status onto the response
;; ===========================================================================

(deftest ssr-redirect-populates-redirect-and-status
  (testing ":rf.server/redirect populates :redirect and :status. Dropping the
            body and the hydration payload under a redirect is the host
            adapter's decision, pinned by ssr-ring's
            `handler-redirect-short-circuits`."
    (rf/reg-event :auth/check-session
      (fn [_ _]
        {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))

    (let [f (rf.frame/make-anon-frame-record! {:platform     :server
                            :initial-events    [[:auth/check-session]]})]
      (let [resp     (get-response f)
            redirect (:redirect resp)]
        (is (= {:status 302 :location "/login"} redirect)
            "the :redirect accumulator carries status + location")
        (is (= 302 (:status resp))
            "redirect's :status flows through to the response :status"))))

  (testing "a redirect with default :status defaults to 302"
    (rf/reg-event :auth/check-no-status
      (fn [_ _]
        {:fx [[:rf.server/redirect {:location "/login"}]]}))
    (let [f (rf.frame/make-anon-frame-record! {:platform  :server
                            :initial-events [[:auth/check-no-status]]})]
      (is (= 302 (-> (get-response f) :redirect :status))
          ":rf.server/redirect defaults :status to 302 per Spec 011 §Redirect"))))

;; ===========================================================================
;; ssr-default-error-projector — runtime maps known errors to public shapes
;; ===========================================================================
;;
;; Per Spec 011 §Server error projection / §Default projector. The runtime
;; ships :rf.ssr/default-error-projector. When an :error trace fires inside
;; a server frame, the runtime's listener applies the active projector and
;; stamps the public-error's :status onto :rf/response. Asserts the
;; PROJECTOR's output reaches the response accumulator — not a user-stub-
;; rolled :rf.server/set-status.

(defn- error-record->trace-event
  "Synthesise the `{:operation :op-type :tags}` envelope the projector
  pipeline consumes from an EP-0008 union error record `{:error <kw> :frame
  <id> :time <ms> + flat category keys}`.

  This is the SAME generic lift `error-emit-projection-listener` performs —
  every non-`:error` slot rides onto `:tags`, `:recovery` defaults to
  `:no-recovery` — so an event handed to `ssr/project-error` from here is the
  event the runtime's own always-on projection listener would have handed it.
  It is deliberately the same lift `re-frame.ssr-conformance-test` uses for
  the identical sourcing problem in the conformance corpus."
  [record]
  {:op-type   :error
   :operation (:error record)
   :tags      (-> (dissoc record :error)
                  (update :recovery #(or % :no-recovery)))})

(defn- with-error-capture!
  "Run `body-fn` and return `{:result <its value> :events <seq>}`, where
  `:events` carries every error the framework emitted during it, read from
  BOTH axes and normalised to the projector's `{:operation :op-type :tags}`
  envelope.

  Sourced from `re-frame.trace` alone, the projector cluster below would find
  nothing under `-Dre-frame.debug=false`, where that ring is empty, so
  `(some? err)` would fail and the projections those tests exist to pin
  would never be exercised. The categories involved —
  `:rf.error/no-such-handler`,
  `:rf.error/handler-exception`, and `:rf.error/sanitised-on-projection` —
  ALL ride the always-on axis in a release build; indeed
  `error-emit-projection-listener` is precisely what stamps `:status` on a
  production JVM, so the always-on record is the SOURCE OF TRUTH here and the
  dev bus is the copy. Reading both means these assertions adjudicate
  the production projection path rather than a dev artefact.

  A dev build sees both axes and the sequence carries the same failure twice;
  every consumer below asks `some`, never a count."
  [body-fn]
  (let [events (atom [])
        tag    (keyword "rf2-lwtlk" (str "err-cap-" (name (gensym "c"))))]
    (rf/register-listener! :trace tag
      (fn [ev] (when (= :error (:op-type ev)) (swap! events conj ev))))
    (rf.error-emit/register-error-listener! tag
      (fn [r] (swap! events conj (error-record->trace-event r))))
    (try
      (let [result (body-fn)]
        {:result result :events @events})
      (finally
        (rf/unregister-listener! :trace tag)
        (rf.error-emit/unregister-error-listener! tag)))))

(deftest ssr-default-error-projector-handler-exception
  (testing "a handler that throws at RENDER time → default projector → 500"
    ;; The throwing dispatch is a RENDER-TIME
    ;; request dispatch against a live frame — NOT an :initial-events setup
    ;; step. Construction-time :initial-events is STRICT (EP-0027
    ;; §Failure): a THROWN setup step tears the partial frame
    ;; down and is the OUTER :on-error transport path (Spec 011 §`:on-error`
    ;; vs `:error-view`), NOT a projector-catches-it case. The error projector covers errors INSIDE
    ;; the render/cascade drain — exactly what a post-construction request
    ;; dispatch models. So :rf/server-init is a clean no-op setup step; the
    ;; throwing :load/article fires afterward, in the projector's domain.
    (rf/reg-event :load/article
      (fn [_ _]
        (throw (ex-info "Database connection failed: SECRET_TOKEN=xyz" {}))))
    (rf/reg-event :rf/server-init
      (fn [_ _] {}))

    (let [project-error  rf.ssr/project-error
          f              (rf.frame/make-anon-frame-record!
                           {:platform :server
                            :initial-events [[:rf/server-init]]
                            :ssr {:public-error-id   :rf.ssr/default-error-projector
                                  :dev-error-detail? false}})
          events         (:events (with-error-capture!
                                    (fn [] (rf/dispatch-sync [:load/article] {:frame f}))))
          err            (some #(when (= :rf.error/handler-exception (:operation %)) %)
                               events)]
      (is (some? err)
          "handler-exception fired during the drain")
      (is (= 500 (:status (get-response f)))
          "default projector's :status 500 reaches :rf/response")
      (let [public (project-error f err)]
        (is (= {:status     500
                :code       :internal-error
                :message    "Something went wrong"
                :retryable? false}
               public)
            "default projector's prod shape carries exactly the four locked keys — under
             :dev-error-detail? false there is no :details, so no internal detail leaks")))))

(deftest ssr-error-projector-dev-mode-includes-details
  (testing ":dev-error-detail? true puts the raw trace under :details"
    (let [project-error rf.ssr/project-error
          f             (rf.frame/make-anon-frame-record!
                          {:platform :server
                           :ssr {:public-error-id   :rf.ssr/default-error-projector
                                 :dev-error-detail? true}})
          trace-event   {:operation :rf.error/handler-exception
                         :op-type   :error
                         :tags      {:exception-message "boom"
                                     :failing-id        :foo}}
          public        (project-error f trace-event)]
      (is (= 500 (:status public)))
      (is (= :internal-error (:code public)))
      (is (= trace-event (:details public))
          ":details is the trace event verbatim — full internal detail for the dev console"))))

;; ===========================================================================
;; A configured projector that throws, returns a non-conforming shape, or is
;; not registered — the locked fallback and its diagnostic. A configured
;; projector overriding the default is pinned by
;; `re-frame.ssr-flush-response-result-test` and, on the live route-miss path,
;; by `re-frame.ssr-route-miss-404-production-test`.
;; ===========================================================================

(deftest ssr-error-projector-throws-falls-back-to-locked-500
  (testing "projector throws → :rf.error/sanitised-on-projection trace + locked fallback"
    (rf/reg-error-projector :myapp/buggy-projector
      (fn [_trace-event]
        (throw (ex-info "projector bug" {}))))

    (let [project-error rf.ssr/project-error
          f             (rf.frame/make-anon-frame-record!
                          {:platform :server
                           :ssr {:public-error-id   :myapp/buggy-projector
                                 :dev-error-detail? false}})
          {public :result
           events :events} (with-error-capture!
                             (fn [] (project-error
                                      f {:operation :rf.error/handler-exception :tags {}})))]
      (is (= {:status     500
              :code       :internal-error
              :message    "Something went wrong"
              :retryable? false}
             public)
          "fallback to the locked generic-500 shape — the boundary holds even with a buggy projector")
      (is (some #(= :rf.error/sanitised-on-projection (:operation %)) events)
          ":rf.error/sanitised-on-projection fired so the buggy projector is observable"))))

(deftest ssr-error-projector-non-conforming-shape-falls-back
  (testing "projector returns nil / wrong shape → :rf.error/sanitised-on-projection + fallback"
    (rf/reg-error-projector :myapp/bad-shape
      (fn [_trace-event] {:wrong :shape}))

    (let [project-error rf.ssr/project-error
          f             (rf.frame/make-anon-frame-record!
                          {:platform :server
                           :ssr {:public-error-id   :myapp/bad-shape}})
          {public :result
           events :events} (with-error-capture!
                             (fn [] (project-error
                                      f {:operation :rf.error/handler-exception :tags {}})))]
      (is (= 500 (:status public))
          "non-conforming projector output → fallback locked-500")
      (is (= :internal-error (:code public)))
      (is (some #(= :rf.error/sanitised-on-projection (:operation %)) events)))))

(deftest ssr-error-projector-configured-but-unregistered-surfaces-diagnostic
  (testing "a frame that configures :ssr {:public-error-id …}
            naming an UNREGISTERED projector is a recognised-but-unhonourable
            config: project-error SURFACES a :rf.error/sanitised-on-projection
            diagnostic (:projection-failure-reason :missing-projector) instead
            of silently downgrading the projector's intended mapping to the
            generic 500."
    (let [project-error rf.ssr/project-error
          ;; A :public-error-id that was NEVER reg-error-projector'd.
          f             (rf.frame/make-anon-frame-record!
                          {:platform :server
                           :ssr {:public-error-id   :myapp/never-registered
                                 :dev-error-detail? false}})
          ;; :no-such-handler would have projected to a 404 under a real
          ;; projector — the misconfiguration turns it into a 500.
          {public :result
           events :events} (with-error-capture!
                             (fn [] (project-error
                                      f {:operation :rf.error/no-such-handler :tags {}})))]
      ;; The boundary still holds — the fallback is the safe wire shape.
      (is (= 500 (:status public))
          "the locked generic-500 fallback still applies (boundary can't be bypassed)")
      (is (= :internal-error (:code public)))
      ;; …but the misconfiguration is OBSERVABLE — and, because this read
      ;; takes the always-on axis too, observable in the build that ships
      ;; rather than only in the one the operator is not running.
      (let [diag (some #(when (= :rf.error/sanitised-on-projection (:operation %)) %)
                       events)]
        (is (some? diag)
            ":rf.error/sanitised-on-projection fired — the missing configured
             projector is surfaced, not swallowed")
        (is (= :missing-projector (get-in diag [:tags :projection-failure-reason]))
            "the diagnostic carries :projection-failure-reason :missing-projector")
        (is (= :myapp/never-registered (get-in diag [:tags :projector-id]))
            "the diagnostic names the unregistered configured id"))))

  (testing "the plain default-fallback path (NO :public-error-id
            configured) stays SILENT: with no :ssr config the frame resolves
            to the built-in default projector, which honours the mapping — so
            there is no missing-projector diagnostic (the diagnostic is
            confined to the CONFIGURED-but-unregistered case, not noisy on
            the default path)."
    (let [project-error rf.ssr/project-error
          f             (rf.frame/make-anon-frame-record! {:platform :server})
          ;; The 404 arm is gated on `:kind :route` — the
          ;; URL-driven miss. `:tags {}` projects 500, so the
          ;; honoured-vs-fallen-back distinction this deftest is about
          ;; needs the route discriminator to be visible.
          {public :result
           events :events} (with-error-capture!
                             (fn [] (project-error
                                      f {:operation :rf.error/no-such-handler
                                         :tags      {:kind :route}})))]
      (is (= 404 (:status public))
          "the built-in default projector maps a :kind :route
           :no-such-handler → 404 (honoured, not fallen-back)")
      ;; This NEGATIVE reads the always-on axis too. Sourced from the dev bus
      ;; alone it would pass AUTOMATICALLY under
      ;; `-Dre-frame.debug=false`, where the ring is empty for every input:
      ;; it would report "the default path is quiet" in a build where
      ;; NOTHING can be heard. Reading the always-on axis makes it a real
      ;; discriminator — it fails if the default path ever starts
      ;; emitting a missing-projector diagnostic in production.
      (is (not-any? #(= :rf.error/sanitised-on-projection (:operation %)) events)
          "no sanitised-on-projection diagnostic on the default path — the
           default projector is registered, so it is not a missing-projector"))))

;; ===========================================================================
;; default-error-projector-fn pure-unit case table.
;; The live cascade drives :no-such-handler → 404 in
;; `re-frame.ssr-route-miss-404-production-test`, a boundary rejection → 400 in
;; `re-frame.ssr-boundary-rejection-400-production-test`, and
;; :handler-exception → 500 above. This is the projector fn's whole case
;; table, pure (trace-event → public-error), so it is unit-tested directly:
;; deterministic, no frame/drain machinery.
;; ===========================================================================

(deftest default-error-projector-fn-maps-all-enumerated-categories
  (testing "the default projector's full case table per
            Spec 011 §Default projector. Exercises the fn directly (it is a
            public re-export: ssr/default-error-projector-fn)."
    (testing ":rf.error/no-such-handler → 404 :not-found ONLY with :kind :route.
              The arm is GATED on the route discriminator: an unregistered
              event id (:kind :event) or a Tool-Pair surface naming an unknown
              frame (:kind :frame) is a SERVER defect, and a miss with no
              :kind is unclassified, so each falls to the locked 500"
      (is (= {:status 404 :code :not-found :message "Page not found" :retryable? false}
             (rf.ssr/default-error-projector-fn {:operation :rf.error/no-such-handler
                                              :tags      {:kind :route}})))
      (doseq [tags [{:kind :event} {:kind :frame} {}]]
        (is (= rf.ssr/fallback-public-error
               (rf.ssr/default-error-projector-fn {:operation :rf.error/no-such-handler
                                                :tags      tags}))
            (str ":rf.error/no-such-handler with " (pr-str tags) " → the locked 500"))))
    (testing ":rf.error/no-such-route → 404 :not-found (the second 404 arm)"
      (is (= {:status 404 :code :not-found :message "Page not found" :retryable? false}
             (rf.ssr/default-error-projector-fn {:operation :rf.error/no-such-route}))
          "no-such-route shares the 404 :not-found mapping with no-such-handler"))
    (testing ":rf.error/cofx-value-invalid → 400 :bad-request
              UNCONDITIONALLY (a bad client-supplied request
              coeffect is client input, never a server-fault 500)"
      (is (= {:status 400 :code :bad-request :message "Invalid input" :retryable? false}
             (rf.ssr/default-error-projector-fn
               {:operation :rf.error/cofx-value-invalid
                :tags      {:reason :non-edn-recordable-value}}))
          "a non-recordable request coeffect (its own category, not a
           :rf.error/schema-validation-failure :where :cofx shape) is
           a client-facing 400, never a 500")
      (is (= {:status 400 :code :bad-request :message "Invalid input" :retryable? false}
             (rf.ssr/default-error-projector-fn
               {:operation :rf.error/cofx-value-invalid}))
          "the 400 arm is UNCONDITIONAL — it does not depend on a :where tag
           (unlike schema-validation-failure); the dispatch boundary is the
           client-input surface by construction"))
    (testing ":rf.error/schema-validation-failure with a CLIENT-surface
              :where (:event) → 400 :bad-request"
      (is (= {:status 400 :code :bad-request :message "Invalid input" :retryable? false}
             (rf.ssr/default-error-projector-fn
               {:operation :rf.error/schema-validation-failure
                :tags      {:where :event}}))
          "an inbound-event payload failure is client-facing → 400"))
    (testing ":rf.error/schema-validation-failure with :where
              :cofx → 500 (there is no injection-time cofx-validation
              path; a bad request coeffect rides its own
              :rf.error/cofx-value-invalid category, so this shape is
              not a client 400)"
      (is (= rf.ssr/fallback-public-error
             (rf.ssr/default-error-projector-fn
               {:operation :rf.error/schema-validation-failure
                :tags      {:where :cofx}}))
          "a :where :cofx shape falls through to the locked
           generic-500 — the client-cofx 400 is :rf.error/cofx-value-invalid"))
    (testing ":rf.error/schema-validation-failure with a SERVER-surface
              :where (:fx-args) → 500 (gated 400 arm)"
      (is (= rf.ssr/fallback-public-error
             (rf.ssr/default-error-projector-fn
               {:operation :rf.error/schema-validation-failure
                :tags      {:where :fx-args}}))
          "a server-fx arg-schema failure is a SERVER-side defect, not bad
           client input — it falls through to the locked generic-500 rather
           than mislabelling a server bug as a client 400")
      (is (= rf.ssr/fallback-public-error
             (rf.ssr/default-error-projector-fn
               {:operation :rf.error/schema-validation-failure}))
          "a schema-validation-failure with NO :where tag also falls through
           to 500 — the 400 arm is opt-in on a client-surface :where, fail-safe")
      (is (= rf.ssr/fallback-public-error
             (rf.ssr/default-error-projector-fn
               {:operation :rf.error/schema-validation-failure
                :tags      {:where :sub-return}}))
          "a sub-return failure is likewise non-client → 500"))
    (testing "any other category → the locked generic-500 fallback"
      (is (= rf.ssr/fallback-public-error
             (rf.ssr/default-error-projector-fn {:operation :rf.error/handler-exception}))
          "handler-exception falls through to the 500 default")
      (is (= rf.ssr/fallback-public-error
             (rf.ssr/default-error-projector-fn {:operation :totally/unknown-future-category}))
          "an unenumerated future category also falls through — no case arm needed")
      (is (= rf.ssr/fallback-public-error
             (rf.ssr/default-error-projector-fn {}))
          "an event with no :operation falls through to 500 too"))))

;; ===========================================================================
;; peek-response (pure) vs flush-response! / get-response
;; (drain) read-surface contract. error_listener.cljc documents three reads:
;; peek-response does NOT drain pending error projections; flush-response!
;; and get-response DO. The drain-on-read is covered by the projector e2e
;; tests; the pure-read-does-NOT-drain invariant (and the bookkeeping-key
;; stripping on both) is pinned here.
;; ===========================================================================

(deftest peek-response-does-not-drain-flush-does
  (testing "a buffered error trace is left intact by
            peek-response (pure read) and only stamps :status when
            flush-response! / get-response drains it."
    (rf/reg-route :route/home {} "/")
    (let [f (rf.frame/make-anon-frame-record!
              {:platform :server
               :ssr {:public-error-id   :rf.ssr/default-error-projector
                     :dev-error-detail? false}})]
      ;; Fire a :rf.error/no-such-handler so a trace buffers against f.
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})

      (testing "peek-response reads the un-projected response (still 200) and
                does NOT consume the buffered trace"
        (is (= 200 (:status (rf.ssr/peek-response f)))
            "peek leaves :status at the default 200 — the projector buffer
             is NOT drained by a pure read"))

      (testing "flush-response! drains the buffer and stamps the projector's status"
        (is (= 404 (:status (rf.ssr/flush-response! f)))
            "flush projects the buffered :no-such-handler → 404 onto :status")))))

(deftest peek-and-get-response-strip-bookkeeping-keys
  (testing "both read surfaces strip the internal
            `:rf.server/_status-writes` / `:rf.server/_redirect-writes`
            bookkeeping keys, so a host adapter never sees them on the wire
            shape."
    (rf/reg-event :resp/multi-status
      (fn [_ _]
        {:fx [[:rf.server/set-status 201]
              [:rf.server/set-status 202]]}))
    (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf/dispatch-sync [:resp/multi-status] {:frame f})
      (doseq [[label resp] [["peek-response"  (rf.ssr/peek-response f)]
                            ["get-response"   (rf.ssr/get-response f)]]]
        (is (= 202 (:status resp))
            (str label ": last-write-wins status surfaces"))
        (is (not (contains? resp :rf.server/_status-writes))
            (str label ": the internal status-writes bookkeeping key is stripped"))
        (is (not (contains? resp :rf.server/_redirect-writes))
            (str label ": the internal redirect-writes bookkeeping key is stripped"))))))

;; ===========================================================================
;; ssr-multi-redirect — multi-write emits :rf.warning/multiple-redirects
;; ===========================================================================

(deftest ssr-multi-redirect
  (testing "two :rf.server/redirect fxs → last write wins + :rf.warning/multiple-redirects"
    (let [traces (atom [])]
      (rf/reg-event :auth/double-redirect
        (fn [_ _]
          {:fx [[:rf.server/redirect {:status 302 :location "/login"}]
                [:rf.server/redirect {:status 301 :location "/canonical"}]]}))

      (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
        (rf/register-listener! :trace ::redir (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/double-redirect] {:frame f})
        (rf/unregister-listener! :trace ::redir)

        (let [redirect (-> (get-response f) :redirect)]
          (is (= {:status 301 :location "/canonical"} redirect)
              "last write wins — the response :redirect is the second write"))

        ;; DEV ARM — same shape as :rf.warning/multiple-status-set
        ;; above: the last-write-wins POLICY is the contract and is asserted
        ;; posture-independently just above; the warning is programmer advice
        ;; and is dev-only by design.
        (when rf.interop/debug-enabled?
          (is (some (fn [ev]
                      (and (= :rf.warning/multiple-redirects (:operation ev))
                           (= 2 (count (:writes (:tags ev))))
                           (= {:status 301 :location "/canonical"} (:final-redirect (:tags ev)))
                           (= :warned-and-replaced (:recovery ev))))
                    @traces)
              (str "expected :rf.warning/multiple-redirects trace; saw: "
                   (pr-str (mapv :operation @traces)))))))))

;; ===========================================================================
;; host-supplied :failing-id is surfaced on the unified render-hash channel
;; ===========================================================================
;;
;; Per Spec 011 §Mismatch detection — head + §Hydration-mismatch detection:
;; head and body share the unified :rf/render-hash channel in v1, so the
;; bundled runtime cannot tell head-only from body-only divergence and
;; emits a single :failing-id :rf/hydrate on any mismatch. :failing-id is a
;; GENERIC host-supplied attribution seam on verify-hydration!, NOT a value
;; the runtime toggles. This test exercises that SEAM: a host supplying its
;; own attribution value (here :rf.ssr/head-mismatch — host-suppliable,
;; not v1-runtime-emitted) has it flow through to the trace. It proves the
;; seam + host attribution, NOT runtime head-detection. A dedicated
;; head-hash payload key + wire attribute that would let the runtime itself
;; emit :rf.ssr/head-mismatch is reserved for a deferred post-v1
;; head-only-hash extension (reg-head itself exists).

(deftest host-supplied-failing-id-surfaced-on-unified-channel
  (testing "a host-supplied :failing-id override flows through verify-hydration! to the trace on the unified render-hash channel"
    (let [verify-fn rf.ssr/verify-hydration!
          ;; Hydration payload carries the SERVER's render-hash. v1's
          ;; unified channel covers head + body; the bundled runtime emits
          ;; only :failing-id :rf/hydrate. Here the HOST supplies its own
          ;; attribution value through the seam (see verify-fn call below).
          ;;
          ;; EP-0001: the server-settled route slice rides the
          ;; payload's `:rf/runtime-db` key (the hydrate handler installs it
          ;; into the runtime-db partition under `:rf.runtime/routing`).
          ;; There is no top-level `:rf/runtime` app-db root — the
          ;; post-commit guard rejects one as `:rf.error/legacy-runtime-root`.
          ;; This test only asserts the server-hash stash, so the route slice
          ;; is illustrative payload content placed in its runtime-db home.
          payload   {:rf/version     1
                     :rf/runtime-db  {:rf.runtime/routing {:current {:route-id :route/article :params {:id "123"}}}}
                     :rf/render-hash "head-hash-server-A"}
          traces    (atom [])
          f         (rf.frame/make-anon-frame-record! {:platform :client})]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame f})
      (is (= "head-hash-server-A"
             (get-in (:rf.db/runtime (rf/frame-state-value f)) [:rf.runtime/ssr :hydration :server-hash]))
          ":rf/hydrate stashed the server's head-hash")

      (rf/register-listener! :trace ::head (fn [ev] (swap! traces conj ev)))
      ;; Client hash differs; the HOST supplies a :failing-id override
      ;; (:rf.ssr/head-mismatch — host-suppliable, not v1-runtime-emitted)
      ;; and we assert the seam carries it through to the trace verbatim.
      (verify-fn f
                 "head-hash-client-B"
                 {:failing-id :rf.ssr/head-mismatch
                  :first-diff-path [:head :title]})
      (rf/unregister-listener! :trace ::head)

      ;; DEV ARM — the host-supplied `:failing-id` seam puts a
      ;; host's own attribution on the `:rf.ssr/hydration-mismatch` warning,
      ;; and THIS ASSERTION reads it off the dev-only TRACE (recovery
      ;; `:warned-and-replaced` — the client re-renders either way), which is
      ;; what puts it in this arm.
      ;;
      ;; The SEAM itself is not dev-posture — `:failing-id`
      ;; is one of the structural slots the always-on record carries, and it
      ;; is the body/head discriminator there, so a host's attribution
      ;; reaches an off-box shipper in a `goog.DEBUG=false` build. Only the
      ;; CHANNEL this deftest watches is dev-posture. A production witness
      ;; for the record-borne `:failing-id` would belong in
      ;; `ssr_error_emit_promotion_test`, which has no leg for this category.
      ;; What is NOT dev-posture is the
      ;; payload stash asserted above: `:rf/hydrate` puts the server hash into
      ;; the runtime-db partition in every build, and that assertion runs in
      ;; this lane — which is why guarding here does not leave the deftest
      ;; executing nothing under the gate.
      (when rf.interop/debug-enabled?
        (is (some (fn [ev]
                    (and (= :rf.ssr/hydration-mismatch (:operation ev))
                         (= "head-hash-server-A" (:server-hash (:tags ev)))
                         (= "head-hash-client-B" (:client-hash (:tags ev)))
                         (= :rf.ssr/head-mismatch (:failing-id (:tags ev)))
                         (= [:head :title] (:first-diff-path (:tags ev)))
                         (= :warned-and-replaced (:recovery ev))))
                  @traces)
            (str "expected head-mismatch trace; saw: "
                 (pr-str (mapv (juxt :operation #(:failing-id (:tags %))) @traces))))

        ;; And the SAME hash on both sides → no trace. A NEGATIVE over the
        ;; ring, so it belongs in the arm with the positive above it.
        (let [no-mismatch-traces (atom [])]
          (rf/register-listener! :trace ::head-ok (fn [ev] (swap! no-mismatch-traces conj ev)))
          (verify-fn f
                     "head-hash-server-A"
                     {:failing-id :rf.ssr/head-mismatch})
          (rf/unregister-listener! :trace ::head-ok)
          (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %))
                        @no-mismatch-traces)
              "no head-mismatch trace when client and server hashes agree"))))))

;; ---- default-response initial shape contract ------------------------------
;;
;; Pin the documented keys of
;; the SSR per-request response accumulator initial value.

(deftest default-response-canonical-shape
  (testing "(ssr/default-response) returns the canonical initial response map"
    (let [r (rf.ssr/default-response)]
      (is (map? r) "default-response returns a map")
      ;; Per Spec 011 §HTTP response contract / §Status defaults:
      (is (= 200 (:status r))
          ":status defaults to 200")
      (is (vector? (:headers r))
          ":headers is a vector (header pairs, ordered)")
      ;; The default content-type header for HTML responses lives in
      ;; the initial map.
      (is (some (fn [[name value]]
                  (and (= "content-type" name)
                       (clojure.string/includes? (str value) "text/html")))
                (:headers r))
          "default :headers carries a text/html content-type entry")
      (is (vector? (:cookies r))
          ":cookies is a vector")
      (is (empty? (:cookies r))
          ":cookies starts empty")
      (is (nil? (:redirect r))
          ":redirect starts nil"))))

;; ===========================================================================
;; Direct adapter-contract smoke
;; ===========================================================================
;;
;; The `ssr/adapter` Var is the SSR substrate adapter — eight of nine
;; slots implement the substrate contract cleanly; the ninth (`:render`)
;; deliberately throws because SSR uses render-to-string exclusively.
;; The shared test fixture installs the adapter on every `:each`, so
;; the indirection is exercised constantly; these deftests assert the
;; slot contents themselves.

(deftest adapter-installs-ssr-render-to-string
  (testing "ssr/adapter wires re-frame.ssr/render-to-string into the
            :render-to-string slot"
    (let [adapter rf.ssr/adapter]
      (is (= :rf.adapter/ssr (:kind adapter))
          ":kind identifies the SSR substrate")
      (is (fn? (:render-to-string adapter))
          ":render-to-string is a callable fn")
      ;; The slot fn is the production renderer — calling it against a
      ;; tiny hiccup tree round-trips to an HTML string.
      (let [html ((:render-to-string adapter) [:div "smoke"] {})]
        (is (string? html))
        (is (str/includes? html "smoke")
            ":render-to-string emits HTML carrying the hiccup body"))
      ;; The five state-container slots are present and callable.
      (is (fn? (:make-state-container adapter)))
      (is (fn? (:read-container adapter)))
      (is (fn? (:replace-container! adapter)))
      (is (fn? (:subscribe-container adapter)))
      (is (fn? (:make-derived-value adapter))))))

(deftest adapter-render-throws-rf-error-render-on-headless-adapter
  (testing "ssr/adapter :render slot throws :rf.error/render-on-headless-adapter
            — SSR uses render-to-string exclusively (Spec 006 §Plain-atom adapter)"
    (let [render-fn (:render rf.ssr/adapter)]
      (is (fn? render-fn))
      (try
        (render-fn [:div] nil nil)
        (is false "render-fn must throw — did not")
        (catch clojure.lang.ExceptionInfo e
          ;; Branch on the canonical :rf.error/id; the message is
          ;; the human :reason sentence + the [:rf.error/<id>] token, NOT a bare
          ;; keyword (tests must not exact-equal the non-normative message).
          (is (= :rf.error/render-on-headless-adapter
                 (:rf.error/id (ex-data e)))
              "ex-data carries the canonical discriminator")
          (is (re-find #"\[:rf\.error/render-on-headless-adapter\]" (ex-message e))
              "ex-message carries the [:rf.error/<id>] greppability token")
          (is (string? (-> e ex-data :reason))
              "ex-data carries a human :reason"))))))

;; ===========================================================================
;; Retired redirect-target spellings (:url / :to)
;; are REJECTED, not normalised onto :location
;; ===========================================================================
;;
;; Spec 011 §Redirect contract: `:rf.server/redirect`'s redirect target is
;; keyed under `:location` — the canonical (and only) key, per EP-0007
;; one-name-per-fact (this fx writes an HTTP `Location` response header, so
;; it uses header vocabulary). There are no `:url` / `:to` synonyms:
;; `redirect-fx` throws `:rf.error/redirect-retired-target-key`
;; naming `:location` rather than silently normalising. There is no
;; back-compat alias. The test below pins the rejection AND that the resolved
;; redirect slot is NOT populated when a retired spelling is the only target
;; key; `ssr-redirect-retired-spelling-diagnostic-names-location` pins what
;; the diagnostic names.

(deftest redirect-retired-to-spelling-is-rejected
  (testing "{:to \"...\"} is rejected with
            :rf.error/redirect-retired-target-key, even with an explicit
            :status — the retired-key check fires before the status path"
    (rf/reg-event :retired/to-redirect
      (fn [_ _]
        {:fx [[:rf.server/redirect {:to "/welcome" :status 301}]]}))
    (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:retired/to-redirect] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/redirect-retired-target-key
        ":to redirect-target spelling rejected")
      (is (nil? (:redirect (get-response f)))
          "the rejected redirect did NOT populate the :redirect slot"))))

;; ===========================================================================
;; :rf.server/safe-redirect (caller-untrusted)
;; ===========================================================================
;;
;; The runtime ships TWO redirect fxs:
;;
;; - :rf.server/redirect       — caller-trusted; arbitrary :location strings.
;; - :rf.server/safe-redirect  — caller-untrusted; URL parse + scheme reject +
;;                               :relative-only? / :allow allowlist gating.
;;
;; Mitigation for the open-redirect class: an
;; attacker-controlled ?next=... URL parameter cannot redirect off-origin
;; when the app uses :rf.server/safe-redirect.
;;
;; Validation order (each step emits its specific :rf.error/safe-redirect-*
;; category — see Spec 009 §Error event catalogue). Step 2's prefix check
;; runs BEFORE step 1, so an unparseable `data:` URL still reports its scheme:
;;   2. `<scheme>:` prefix ∈ #{javascript data vbscript} → :rf.error/safe-redirect-scheme-rejected
;;   1. URL must parse → :rf.error/safe-redirect-invalid-url
;;   2. parsed scheme rejected, or not http/https → :rf.error/safe-redirect-scheme-rejected;
;;      a scheme with no host → :rf.error/safe-redirect-invalid-url
;;   3. :relative-only? true + URL not relative → :rf.error/safe-redirect-host-disallowed
;;      (:reason :relative-only-violation)
;;   4. :allow supplied + host ∉ allow → :rf.error/safe-redirect-host-disallowed
;;      (:reason :not-in-allowlist)
;;   5. pass → set Location header (same shape as redirect-fx).

(defn- safe-redirect-error?
  "Is `op` one of the `:rf.error/safe-redirect-*` rejection categories?"
  [op]
  (and (keyword? op)
       (= "rf.error" (namespace op))
       (str/starts-with? (name op) "safe-redirect-")))

(defn- capture-safe-redirect-traces!
  "Record every `:rf.error/safe-redirect-*` rejection emitted during `body-fn`,
  reading the ALWAYS-ON `:errors` axis and returning it normalised to the
  dev-trace shape `{:operation … :tags …}`.

  WHY THE ALWAYS-ON AXIS AND NOT A DEV ARM. This is
  the open-redirect gate: the one surface in this file where a dev-posture
  proof would be actively misleading. These rejections ride the always-on
  axis precisely so a production JVM does not swallow them —
  `emit-safe-redirect-error!` fans axis 1 (`dispatch-safe-redirect-record!`,
  the record an off-box shipper sees) beside axis 2 (`trace/emit-error!`).
  Reading axis 1 means the assertions below prove the gate in
  the build an attacker actually meets.

  ONLY axis 1, deliberately — some of these count (`(= 1 (count ...))`),
  and a union would report two in a dev build and one in a release build,
  making the count itself posture-dependent. Axis 1 fires in both, so one
  reading serves both.

  WHAT AXIS 1 DOES NOT CARRY. The record is a CLOSED STRUCTURAL PROJECTION
  (`re-frame.ssr.egress/safe-redirect-record-slots` =
  `#{:frame :recovery :reason :scheme-class}`), and every value in it is a
  framework-owned keyword or the frame's own id — not merely a closed set of
  KEYS but a closed set of VALUES. `:reason` and `:scheme-class` are what the
  assertions below discriminate on.

  Excluded ON PURPOSE, each for its own reason: `:location` (the caller's
  URL), `:allowlist` (the application's own security configuration), and
  the raw `:scheme` and `:host`. The
  last two look structural because they are parsed, but parsing says where a
  substring sat in the grammar, not who wrote it: a scheme is arbitrary text
  under RFC 3986 §3.1 and a rejected host is by construction a name the app
  did NOT authorise, so each could carry a sentinel and each could be varied
  per request to flood a metrics dimension. `:scheme` therefore arrives as the
  classified `:scheme-class`, and `:host` does not arrive at all.

  The assertions that read `:allowlist`, `:host` or a raw `:scheme` spelling
  are therefore the genuine dev arms in this cluster. They sit in one DEV ARM
  table, `safe-redirect-dev-trace-carries-the-raw-diagnostics`, and read
  axis 2, where the diagnostics arrive whole."
  [body-fn]
  (let [traces (atom [])
        tag    (keyword "rf2-lwtlk" (str "sr-cap-" (name (gensym "c"))))]
    (rf.error-emit/register-error-listener! tag
                           (fn [r]
                             (when (safe-redirect-error? (:error r))
                               (swap! traces conj {:operation (:error r)
                                                   :tags      r}))))
    (try (body-fn) @traces
         (finally (rf.error-emit/unregister-error-listener! tag)))))

(defn- capture-safe-redirect-dev-traces!
  "The DEV-ONLY companion to [[capture-safe-redirect-traces!]], reading
  `trace/emit-error!`'s axis-2 surface — which receives the diagnostics
  WHOLE rather than projected. Used by the dev-arm table that reads the
  tags `safe-redirect-record-slots` excludes from the always-on record
  by design — `:allowlist` (the app's own security configuration), `:host`
  and the raw `:scheme` spelling (the caller's unbounded URL components)."
  [body-fn]
  (let [traces (atom [])
        tag    (keyword "rf2-lwtlk" (str "sr-dev-cap-" (name (gensym "c"))))]
    (rf/register-listener! :trace tag
                           (fn [ev]
                             (when (safe-redirect-error? (:operation ev))
                               (swap! traces conj ev))))
    (try (body-fn) @traces
         (finally (rf/unregister-listener! :trace tag)))))

;; --- Step 1: URL parse failure --------------------------------------------

(deftest safe-redirect-rejects-unparseable-url
  (testing "step 1: a :location that cannot be parsed as a URL
            → :rf.error/safe-redirect-invalid-url trace AND no :redirect
            is set on the response (the fx is a no-op on rejection)"
    (rf/reg-event :sr/unparseable
      (fn [_ _]
        ;; A space-after-colon makes this URISyntax-invalid in java.net.URI
        ;; (the colon makes it look like a scheme, but the space after is
        ;; not a legal scheme-specific character).
        {:fx [[:rf.server/safe-redirect
               {:location "https://example.com/path with space"}]]}))
    (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/unparseable] {:frame f})))
          resp   (get-response f)]
      (is (= 1 (count (filter #(= :rf.error/safe-redirect-invalid-url
                                  (:operation %)) traces)))
          "exactly one :rf.error/safe-redirect-invalid-url trace fires")
      (is (nil? (:redirect resp))
          "rejection is a no-op — :redirect slot unchanged"))))

;; --- Validation order: the scheme prefix runs before the parse -----------

(deftest safe-redirect-validation-order-scheme-prefix-precedes-parse
  (testing "a URL that is BOTH unparseable AND carries a rejected
            scheme prefix surfaces the scheme error, NOT the parse error,
            and only that one (validation runs in order and short-circuits —
            see Spec 009 §Error event catalogue)"
    (rf/reg-event :sr/order-parse-first
      (fn [_ _]
        ;; Unparseable AND `javascript:`-prefixed — the prefix check fires
        ;; first, because it runs before the parse.
        {:fx [[:rf.server/safe-redirect
               {:location "javascript: not a real url "}]]}))
    (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/order-parse-first] {:frame f})))
          ops    (mapv :operation traces)]
      ;; Exactly ONE :rf.error/safe-redirect-* trace fires; the gate
      ;; short-circuits at the prefix check.
      (is (= [:rf.error/safe-redirect-scheme-rejected] ops)
          (str "exactly one :rf.error/safe-redirect-scheme-rejected trace; saw: "
               (pr-str ops))))))

;; --- The dev trace's raw diagnostics, and the shape gate's control ---------
;;
;; The gate works on parsed-URL SHAPE: a relative reference is `scheme==nil
;; AND authority==nil`, anything else is non-relative and subject to the host
;; gates, and a scheme-bearing-but-host-less URL (`http:evil.example.com`) has
;; no defensible redirect interpretation. Each rejection arm — its category,
;; `:reason`, `:scheme-class`, the closed record and the refusal itself — the
;; network-path bypasses and the case-folded policy matches are pinned on the
;; always-on axis in `re-frame.ssr-safe-redirect-production-test`. What stays
;; here is what the dev trace carries and the always-on record deliberately
;; does not — the raw scheme, the rejected host, the allowlist — and the
;; control showing the shape gate does not over-reject.

(deftest safe-redirect-dev-trace-carries-the-raw-diagnostics
  ;; DEV ARM — the tags `re-frame.ssr.egress/safe-redirect-record-slots`
  ;; excludes from the always-on record. The raw `:scheme` spelling and the
  ;; rejected `:host` are the CALLER's unbounded URL components (a scheme is
  ;; arbitrary text under RFC 3986 §3.1, and a rejected host is by
  ;; construction a name the app did not authorise); `:allowlist` is the
  ;; application's own security configuration, whose contents hand a reader
  ;; the exact boundary being probed. Axis 2 receives the diagnostics whole,
  ;; for the programmer reading their own process.
  (when rf.interop/debug-enabled?
    (testing "a rejected :rf.server/safe-redirect's dev trace names the raw
              diagnostics the always-on record leaves out"
      (rf/reg-event :sr/dev-diagnostics
        (fn [_ [_ args]]
          {:fx [[:rf.server/safe-redirect args]]}))
      (doseq [[label args op expected-tags]
              [["mailto"
                {:location "mailto:user@example.com"}
                :rf.error/safe-redirect-scheme-rejected
                {:scheme "mailto"}]
               ["relative-only"
                {:location       "https://evil.example.com/phish"
                 :relative-only? true}
                :rf.error/safe-redirect-host-disallowed
                {:host "evil.example.com"}]
               ["allowlist"
                {:location "https://evil.example.com/phish"
                 :allow    ["app.example.com" "alt.example.com"]}
                :rf.error/safe-redirect-host-disallowed
                {:host      "evil.example.com"
                 :allowlist ["app.example.com" "alt.example.com"]}]]]
        (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
              traces (capture-safe-redirect-dev-traces!
                       (fn [] (rf/dispatch-sync [:sr/dev-diagnostics args] {:frame f})))
              ev     (first (filter #(= op (:operation %)) traces))]
          (doseq [[k v] expected-tags]
            (is (= v (get-in ev [:tags k]))
                (str label " — the " op " dev trace carries " k " " (pr-str v)
                     "; saw: " (pr-str (mapv :operation traces))))))))))

(deftest safe-redirect-accepts-normal-relative-path-control
  (testing "CONTROL: a normal relative path passes
            cleanly under :relative-only? — the shape gate does not
            over-reject the legitimate same-origin case"
    (rf/reg-event :sr/relative-control
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "/account/settings" :relative-only? true}]]}))
    (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/relative-control] {:frame f})))
          resp   (get-response f)]
      (is (empty? traces)
          "no :rf.error/safe-redirect-* trace on a legitimate relative path")
      (is (= "/account/settings" (-> resp :redirect :location))
          ":location lands on the response :redirect slot")
      (is (= 302 (-> resp :redirect :status))
          ":status defaults to 302"))))

;; ===========================================================================
;; Tag-name injection (emit) + header-name / cookie field
;; validation (response)
;;
;; Companion gates to the header-value gate:
;;   1. A parse-tag-name that handed the keyword's leading fragment straight
;;      to `<...>` emission with no grammar check would let a hostile keyword
;;      like `(keyword "img src=x onerror=alert(1)")` bypass the attribute
;;      validator entirely. The tag-name is validated against the HTML5/SVG/
;;      MathML element-name grammar and fails fast on misuse.
;;   2. Validating header VALUES alone would accept any :name, and storing
;;      the cookie map verbatim would pass any field. Header names are
;;      validated against the RFC 7230
;;      §3.2.6 token grammar and cookie fields against RFC 6265 §4.1.1
;;      + the CR/LF/NUL ban — at the fx boundary, so non-ring host
;;      adapters get the same safety. The header-name rows sit with the
;;      header-value rows in `ssr-header-fx-reject-injection-in-name-and-value`.
;; ===========================================================================

(deftest ssr-render-rejects-hostile-tag-keywords
  (testing "a keyword carrying attribute-like injection in the
            tag component is rejected by :rf.error/invalid-tag-name. The
            two documented reproductions."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #":rf\.error/invalid-tag-name"
          (rf.ssr/render-to-string [(keyword "img src=x onerror=alert(1)")] {}))
        "img-with-event-handler injection rejected")
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #":rf\.error/invalid-tag-name"
          (rf.ssr/render-to-string [(keyword "div> <script") "x"] {}))
        "tag-break-into-script injection rejected"))

  (testing "whitespace, separators, CTLs, empty all rejected"
    (doseq [hostile [(keyword " ")
                     (keyword "a b")
                     (keyword "tag\rname")
                     (keyword "tag\nname")
                     (keyword "")
                     (keyword "1-leading-digit")
                     (keyword "<script>")]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":rf\.error/invalid-tag-name"
            (rf.ssr/render-to-string [hostile] {}))
          (str "hostile tag-name " (pr-str hostile)))))

  (testing "admitting one namespaced colon segment does NOT
            admit malformed colon shapes: a bare/leading/trailing/double
            colon or an empty/ill-formed segment throws"
    (doseq [hostile [(keyword ":rect")        ; leading colon — empty prefix
                     (keyword "svg:")         ; trailing colon — empty local
                     (keyword "a:b:c")        ; two colons — not a single ns
                     (keyword "svg::rect")    ; double colon — empty segment
                     (keyword "svg:rect onload=x")]] ; injection after colon
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":rf\.error/invalid-tag-name"
            (rf.ssr/render-to-string [hostile] {}))
          (str "malformed namespaced tag-name " (pr-str hostile))))))

(deftest ssr-render-accepts-legit-tag-keywords
  (testing "HTML / SVG / MathML / custom
            element names + the :tag#id.cls sugar all flow"
    (is (= "<div>x</div>"
           (rf.ssr/render-to-string [:div "x"] {})))
    (is (= "<my-component></my-component>"
           (rf.ssr/render-to-string [:my-component] {})))
    (is (= "<svg></svg>"
           (rf.ssr/render-to-string [:svg] {})))
    (is (= "<foreignObject>a</foreignObject>"
           (rf.ssr/render-to-string [:foreignObject "a"] {}))
        "SVG camelCase element names parse")
    (is (= "<div id=\"main\" class=\"col-12 bold\">x</div>"
           (rf.ssr/render-to-string [:div#main.col-12.bold "x"] {}))
        ":tag#id.cls sugar parses (validator runs on the tag fragment)")
    (is (= "<p>a</p><p>b</p>"
           (rf.ssr/render-to-string [:<> [:p "a"] [:p "b"]] {}))
        ":<> fragment renders children with no wrapper"))

  (testing "XML-namespaced SVG/MathML tags carry a single colon
            segment and are admitted by the grammar"
    (is (= "<svg:rect></svg:rect>"
           (rf.ssr/render-to-string [:svg:rect] {}))
        "namespaced SVG tag `:svg:rect` is accepted")
    (is (= "<xlink:href>a</xlink:href>"
           (rf.ssr/render-to-string [:xlink:href "a"] {}))
        "xlink-namespaced tag is accepted")
    (is (= "<svg:rect id=\"r\" class=\"c\"></svg:rect>"
           (rf.ssr/render-to-string [:svg:rect#r.c] {}))
        "namespaced tag composes with the #id.cls sugar")))

(deftest ssr-set-cookie-crlf-checks-every-attribute
  (testing ":rf.server/set-cookie with CRLF in :name surfaces
            :rf.error/cookie-invalid-name (RFC 6265 §4.1.1 token grammar)"
    (rf/reg-event :ck/crlf-in-name
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name  "session\r\nSet-Cookie: stolen=1"
                :value "abc"}]]}))
    (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/crlf-in-name] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-name
        "set-cookie with CRLF in :name")))

  (testing "Spec 011 §CRLF fail-fast: :rf.server/set-cookie
            CRLF-checks EVERY attribute the host adapter serialises —
            :value, :path, :domain, :max-age, :same-site, :expires. The fx
            boundary is the single enforcement point for non-Ring host
            adapters; a string :max-age sourced from request context must not
            re-enter the header line as CRLF-bearing payload. Every failure
            surfaces the single catalogued :rf.error/cookie-invalid-attribute,
            with the offending attribute in the :attribute payload slot."
    (doseq [[label attr hostile]
            [["CRLF in :value"     :value     "abc\r\nSet-Cookie: stolen=1"]
             ["CRLF in :path"      :path      "/\r\nSet-Cookie: stolen=1"]
             ["CRLF in :domain"    :domain    "example.com\r\nSet-Cookie: stolen=1"]
             ["CRLF in a string :max-age (a forged second Set-Cookie line)"
              :max-age "3600\r\nSet-Cookie: admin=1; Path=/"]
             ["CRLF in :same-site" :same-site "Lax\r\nSet-Cookie: admin=1"]
             ["bare LF in :max-age" :max-age  "lf\nbad"]
             ["bare CR in :max-age" :max-age  "cr\rbad"]
             ["NUL in :max-age"     :max-age  (str "nul" (char 0) "bad")]]]
      (rf/reg-event :ck/crlf-in-attribute
        (fn [_ _]
          {:fx [[:rf.server/set-cookie
                 (assoc {:name "session" :value "x"} attr hostile)]]}))
      (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:ck/crlf-in-attribute] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/cookie-invalid-attribute
          (str "set-cookie with " label))
        (is (= attr (:attribute (fx-error-extra traces :rf.error/cookie-invalid-attribute)))
            (str label " — the offending attribute (" attr ") rides the :attribute payload slot")))))

  ;; A CRLF-bearing :expires is an INJECTION failure and lands on
  ;; :rf.error/cookie-invalid-attribute (:attribute :expires), not on
  ;; :rf.error/cookie-invalid-expires. That id is reserved for a NON-integer
  ;; epoch at the Ring materialiser (a shape error, not an injection) — see
  ;; the ssr-ring cookie tests.
  (testing ":expires with CRLF → :rf.error/cookie-invalid-attribute (:attribute :expires)"
    (rf/reg-event :ck/crlf-in-expires
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name    "session"
                :value   "x"
                :expires "Wed, 09 Jun 2027 10:18:14 GMT\r\nSet-Cookie: admin=1"}]]}))
    (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/crlf-in-expires] {:frame f})))
          extra  (fx-error-extra traces :rf.error/cookie-invalid-attribute)]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-attribute
        "set-cookie with CRLF in :expires lands on the catalogued attribute id, NOT cookie-invalid-expires")
      (is (= :expires (:attribute extra))
          "the offending attribute (:expires) rides the :attribute payload slot")
      (is (contains? extra :value)
          "the offending value rides the :value payload slot"))))

(deftest ssr-set-cookie-rejects-semicolon-attribute-delimiter
  (testing "Spec 011 §Cookie shape: :rf.server/set-cookie
            rejects a raw `;` in every VERBATIM-concatenated attribute
            (:path / :domain / :max-age / :same-site / :expires). The `;` is
            the RFC 6265 §4.1.1 cookie-attribute delimiter — a value carrying
            one would escape its attribute and fabricate additional
            attributes (SameSite=None, Secure, …), defeating the structured-
            cookie boundary. The fx boundary is the single enforcement point
            for non-Ring host adapters that serialise the accumulator
            themselves; failures route through the one catalogued
            :rf.error/cookie-invalid-attribute id with the offending
            :attribute in the payload."
    (doseq [[attr hostile] [[:path      "/; SameSite=None; Secure"]
                            [:domain    "evil.com; Secure"]
                            [:max-age   "3600; SameSite=None; Secure"]
                            [:same-site "Lax; Secure"]
                            [:expires   "Wed, 09 Jun 2027 10:18:14 GMT; Secure"]]]
      (rf/reg-event :ck/semicolon-attr
        (fn [_ _]
          {:fx [[:rf.server/set-cookie
                 (assoc {:name "sid" :value "abc"} attr hostile)]]}))
      (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:ck/semicolon-attr] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/cookie-invalid-attribute
          (str "set-cookie with `;` in " attr " " (pr-str hostile)))
        (is (= attr (:attribute (fx-error-extra traces :rf.error/cookie-invalid-attribute)))
            (str "the offending attribute (" attr ") rides the :attribute payload slot"))
        (is (empty? (:cookies (get-response f)))
            (str "no cookie lands on the accumulator — `;` in " attr
                 " is a rejected no-op")))))

  (testing "a `;` in cookie :value is DATA (the host serialiser
            percent-encodes it), so set-cookie ACCEPTS it and stores it
            verbatim on the accumulator — no over-rejection"
    (rf/reg-event :ck/semicolon-value
      (fn [_ _]
        {:fx [[:rf.server/set-cookie {:name "sid" :value "a;b" :path "/"}]]}))
    (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/semicolon-value] {:frame f})))
          cookies (:cookies (get-response f))]
      (is (empty? (fx-error-extra traces :rf.error/cookie-invalid-attribute))
          "no cookie-invalid-attribute error for a `;` in :value")
      (is (= 1 (count cookies)) "the cookie lands on the accumulator")
      (is (= "a;b" (:value (first cookies)))
          "the `;`-bearing :value is stored verbatim (encoding is host-adapter business)")))

  (testing ":rf.server/delete-cookie runs the same delimiter
            gate on :path and :domain (it is sugar over set-cookie)"
    (doseq [attr [:path :domain]]
      (rf/reg-event :ck/del-semicolon
        (fn [_ _]
          {:fx [[:rf.server/delete-cookie
                 (assoc {:name "sid"} attr "x; Secure")]]}))
      (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:ck/del-semicolon] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/cookie-invalid-attribute
          (str "delete-cookie with `;` in " attr))
        (is (= attr (:attribute (fx-error-extra traces :rf.error/cookie-invalid-attribute)))
            (str "delete-cookie `;` in " attr " rides the :attribute payload slot"))))))

(deftest ssr-set-cookie-rejects-non-string-name-type
  (testing "a cookie :name that is neither a string nor a Named
            (keyword / symbol) is rejected at the fx boundary with the
            documented :rf.error/cookie-invalid-name, NOT a raw host
            ClassCastException. Exercised on the DIRECT fx-handler path — the
            schema-soft-pass path the fx boundary must self-defend: with the
            :rf.server/cookie args schema absent or soft-passing (Spec 010),
            `(name n)` on a non-Named value would otherwise throw a bare host
            exception with nil ex-data, bypassing the structured-error
            contract and reaching adapter / :on-error code."
    (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
      (doseq [bad-name [42 3.14 [:not :a :name] {:cookie :map} true]]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #":rf\.error/cookie-invalid-name"
              (rf.ssr.response/set-cookie-fx {:frame f} {:name bad-name :value "x"}))
            (str "set-cookie-fx with a non-string/non-Named :name "
                 (pr-str bad-name)
                 " must throw :rf.error/cookie-invalid-name, not a raw host"
                 " exception"))
        (is (empty? (:cookies (rf.ssr.response/response-of f)))
            (str "the rejected cookie (" (pr-str bad-name)
                 ") never lands on the accumulator")))
      ;; delete-cookie is sugar over the same validator — same TYPE guard.
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/cookie-invalid-name"
            (rf.ssr.response/delete-cookie-fx {:frame f} {:name 99 :path "/"}))
          "delete-cookie-fx runs the same cookie-name type guard")))

  (testing "the type gate separates a
            Named from the values `(name n)` cannot survive, and a string
            :name lands.

            NARROWED: a keyword / symbol :name passes THIS
            gate (`(name :csrf)` is a fine token) and is then refused by the
            always-on SHAPE gate, because the schemas, Spec 011 §Cookie shape
            and Spec-Schemas all publish `:string` — admitting a Named here
            would let `{:name :csrf}` be skipped in dev, land in production,
            and reach host adapters as a keyword. Same ordering as set-header:
            grammar gate first with its own catalogued id, shape gate second.
            The full contract lives in `re-frame.ssr-reserved-fx-guards-test`."
    (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf.ssr.response/set-cookie-fx {:frame f} {:name "session" :value "a"})
      (doseq [named-name [:csrf 'tracker]]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #":rf\.error/server-fx-args-invalid"
              (rf.ssr.response/set-cookie-fx {:frame f} {:name named-name :value "b"}))
            (str "a " (pr-str named-name) " :name is refused by the shape gate,"
                 " not by the type gate — it is a well-formed token of the"
                 " wrong published type")))
      (is (= ["session"] (mapv :name (:cookies (rf.ssr.response/response-of f))))
          "only the string-named cookie landed, and its :name is a string"))))

(deftest ssr-set-cookie-clean-attributes-still-accepted
  (testing "legitimate cookie attributes
            flow — integer :max-age, keyword/string :same-site, a clean
            :expires string. The CRLF gate str-coerces and only bans
            CR/LF/NUL; benign values pass."
    (rf/reg-event :ck/clean-attrs
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name      "session"
                :value     "abc123"
                :max-age   3600                ;; int — (str 3600) is clean
                :same-site "Strict"
                :path      "/"
                :expires   "Wed, 09 Jun 2027 10:18:14 GMT"}]]}))
    (let [f       (rf.frame/make-anon-frame-record! {:platform :server :initial-events [[:ck/clean-attrs]]})
          cookies (:cookies (get-response f))]
      (is (= 1 (count cookies))
          "the clean cookie lands on the accumulator")
      (is (= 3600 (-> cookies first :max-age))
          "integer :max-age survives unchanged (not coerced to string)")
      (is (= "Strict" (-> cookies first :same-site))
          ":same-site survives"))))

;; ===========================================================================
;; ssr-server-fx-args-schema-boundary
;;
;; Spec 011 §Standard fx + [Spec-Schemas §Standard fx args
;; schemas] declare the `:rf.fx.server/*-args` / `:rf.server/cookie`
;; schemas as REGISTERED, and assert "args validation runs as part of the
;; standard `:schema` boundary check" (per Spec 010 §Validation order step
;; 5). Without a `:schema` on the `:rf.server/*` reg-fx calls that boundary
;; would never fire — a malformed arg (e.g. a string `:rf.server/set-status`)
;; would fall straight onto the response accumulator and onto the wire.
;; These tests prove the boundary fires: a structurally-malformed server fx arg is REJECTED at dispatch
;; with `:rf.error/schema-validation-failure :where :fx-args`, the
;; offending fx is SKIPPED (Spec 010 §Per-step recovery row 5 — the
;; accumulator is untouched), and the permissive no-target redirect still
;; passes. That well-formed args land for every reserved fx is the acceptance
;; corpus in `re-frame.ssr-reserved-fx-guards-test`.
;;
;; The schemas artefact (transitively Malli) is on the ssr JVM test
;; classpath and `re-frame.ssr.test-fixture` requires `re-frame.schemas`,
;; so the late-bind validator (`:schemas/validate-fx!`) is LIVE here.
;;
;; ---------------------------------------------------------------------------
;; A TWO-POSTURE CONTRACT
;;
;; `validate-fx!` is `(if interop/debug-enabled? (run-validation …)
;; true)`, so the Spec 010 §Validation-order step-5 boundary does not run in
;; a release build at all — on its own it would let a malformed
;; `:rf.server/*` fx RUN, its args landing on the response accumulator that
;; `ssr/get-response` publishes to every host adapter, so
;; `[:rf.server/set-status "not-an-int"]` would leave a STRING on the HTTP
;; status line.
;;
;; So the reserved family guards its OWN args
;; unconditionally (`re-frame.ssr.response`), while the general step-5 gate for
;; USER fx stays dev-only — trust-the-programmer covers user declarations, and
;; validating every fx in every app to protect seven closed framework effects
;; is posture-hostile.
;;
;; So the SEMANTICS below do not differ by posture, and they are asserted
;; unguarded in §1. What differs, by design, is the RECOVERY PATH — and
;; that is the whole of the split:
;;
;;   dev + schemas : Malli step-5 rejects FIRST, the fx is `:skipped`, and the
;;                   programmer gets the rich `:rf.error/schema-validation-
;;                   failure` `:where :fx-args` naming the failing path (§2).
;;   production    : the guard throws, `re-frame.fx` containment catches it,
;;                   and an ALWAYS-ON `:rf.error/fx-handler-exception` names
;;                   the offending fx to an off-box shipper (§3).
;;
;; Note what that ordering means for §1: it is NOT a claim that one mechanism
;; fires. It is the claim both mechanisms exist to make, and it holds whichever
;; of them got there first — which is exactly why it belongs outside both arms.
;;
;; `re-frame.ssr-reserved-fx-guards-test` is the guards' own acceptance suite
;; and reads the PURE accumulator (`ssr/peek-response`). This one is the
;; END-TO-END view: every read below goes through `get-response`, the public
;; host-adapter surface, after the error projection has drained.
;; ===========================================================================

(defn- expect-fx-args-schema-failure!
  "Assert `traces` carries a `:rf.error/schema-validation-failure` whose
  `:where` is `:fx-args` and whose `:failing-id` is `fx-id`."
  [traces fx-id context-str]
  (let [hits (filter
               (fn [ev]
                 (let [t (:tags ev)]
                   (and (= :fx-args (:where t))
                        (= fx-id    (:failing-id t)))))
               traces)]
    (is (seq hits)
        (str context-str " — expected a :rf.error/schema-validation-failure"
             " :where :fx-args for " fx-id
             "; saw: " (pr-str (mapv (fn [ev] [(:operation ev)
                                               (-> ev :tags :where)
                                               (-> ev :tags :failing-id)])
                                     traces))))))

(def ^:private malformed-server-fx
  "The nine malformed reserved-fx calls this boundary refuses, as
  `[label fx-id fx-vec]`.

  The REFUSAL itself is posture-independent, so this table drives
  only the two DIAGNOSTIC arms (§2 / §3), where the recovery path
  differs by design. The ACCUMULATOR claims are heterogeneous — each fx family
  owns a different response slot — so they stay written out, unguarded, in §1."
  [[":rf.server/set-status with a non-int arg"
    :rf.server/set-status    [:rf.server/set-status "not-an-int"]]
   [":rf.server/set-header missing :value"
    :rf.server/set-header    [:rf.server/set-header {:name "X-Foo"}]]
   [":rf.server/append-header with a non-string :value"
    :rf.server/append-header [:rf.server/append-header {:name "X-Bar" :value 42}]]
   [":rf.server/set-cookie missing :value (via [:ref :rf.server/cookie])"
    :rf.server/set-cookie    [:rf.server/set-cookie {:name "session"}]]
   [":rf.server/set-cookie with a bogus :same-site keyword"
    :rf.server/set-cookie    [:rf.server/set-cookie {:name "s" :value "v"
                                                     :same-site :bogus}]]
   [":rf.server/delete-cookie missing :name"
    :rf.server/delete-cookie [:rf.server/delete-cookie {:path "/"}]]
   [":rf.server/redirect with a non-int :status"
    :rf.server/redirect      [:rf.server/redirect {:location "/x" :status "oops"}]]
   [":rf.server/safe-redirect with a non-int :status"
    :rf.server/safe-redirect [:rf.server/safe-redirect {:location "/ok"
                                                        :status   "not-int"}]]
   [":rf.server/safe-redirect missing :location"
    :rf.server/safe-redirect [:rf.server/safe-redirect {:status 302}]]])

(defn- drive-server-fx!
  "Dispatch `fx-vec` on a fresh server frame and return
  `{:response … :dev-traces … :records …}` — the PUBLIC host-adapter read
  (`ssr/get-response`, taken after the error projection has drained), every
  dev `:rf.error/schema-validation-failure` trace, and every record the
  ALWAYS-ON `:errors` axis saw. One drive, read by all three sections below."
  [fx-vec]
  (let [f       (rf.frame/make-anon-frame-record! {:platform :server})
        ev-id   (keyword "rf2-lwtlk" (str "attempt-" (name (gensym "e"))))
        tag     (keyword "rf2-lwtlk" (str "sfx-cap-" (name (gensym "c"))))
        dev     (atom [])
        records (atom [])]
    (rf/reg-event ev-id (fn [_ _] {:fx [fx-vec]}))
    (rf/register-listener! :trace tag
      (fn [ev] (when (= :rf.error/schema-validation-failure (:operation ev))
                 (swap! dev conj ev))))
    (rf.error-emit/register-error-listener! tag (fn [r] (swap! records conj r)))
    (try
      (rf/dispatch-sync [ev-id] {:frame f})
      {:response   (get-response f)
       :dev-traces @dev
       :records    @records}
      (finally
        (rf/unregister-listener! :trace tag)
        (rf.error-emit/unregister-error-listener! tag)))))

(deftest ssr-server-fx-args-schema-boundary
  ;; -------------------------------------------------------------------------
  ;; §1 — POSTURE-INDEPENDENT. The malformed args never reach the wire.
  ;;
  ;; Not a claim that one MECHANISM fires — it is the claim both mechanisms
  ;; exist to make, and it holds whichever of them got there first. That is
  ;; why it sits outside both arms.
  ;; -------------------------------------------------------------------------
  (testing "a malformed :rf.server/* fx never reaches the response
            accumulator, in EVERY build (Spec 011 §Standard fx / Spec-Schemas
            §Standard fx args schemas / Spec 010 §Validation order step 5)"

    (testing ":rf.server/set-status with a non-int arg"
      ;; The concrete attack: a string
      ;; status that would ride straight onto the wire, saved only by one host
      ;; adapter's coercion.
      ;;
      ;; The default projector's 400 arm is GATED on a
      ;; CLIENT-surface `:where` (`:event` / `:cofx`). A server-fx arg failure
      ;; is a SERVER-side defect: a server handler built a malformed fx args
      ;; map. That is not bad client input, so it must NOT mislabel as a
      ;; client-facing 400; it falls through to the locked generic-500.
      (let [{:keys [response]} (drive-server-fx! [:rf.server/set-status "not-an-int"])]
        (is (= 500 (:status response))
            "the malformed status never reached the wire: it surfaced as 500
             :internal-error, because the 400 arm is gated to :where :event/:cofx")))

    (testing ":rf.server/set-header missing :value"
      (let [{:keys [response]} (drive-server-fx! [:rf.server/set-header {:name "X-Foo"}])]
        (is (not-any? (fn [[k _]] (= "X-Foo" k)) (:headers response))
            "the malformed header was skipped; nothing landed")
        (is (not-any? (fn [[_ v]] (nil? v)) (:headers response))
            "and no header carries a nil value a host would have to invent a
             meaning for")))

    (testing ":rf.server/append-header with a non-string :value"
      (let [{:keys [response]} (drive-server-fx! [:rf.server/append-header
                                                  {:name "X-Bar" :value 42}])]
        (is (not-any? (fn [[k _]] (= "X-Bar" k)) (:headers response))
            "the malformed header was skipped; nothing landed")))

    (testing ":rf.server/set-cookie missing :value"
      (let [{:keys [response]} (drive-server-fx! [:rf.server/set-cookie {:name "session"}])]
        (is (empty? (:cookies response))
            "the malformed cookie was skipped; accumulator stays empty")))

    (testing ":rf.server/set-cookie with a bogus :same-site keyword"
      ;; :same-site accepts the enum #{:strict :lax :none} (or a string, per
      ;; the documented divergence) — a bogus KEYWORD is neither.
      (let [{:keys [response]} (drive-server-fx! [:rf.server/set-cookie
                                                  {:name "s" :value "v"
                                                   :same-site :bogus}])]
        (is (empty? (:cookies response))
            "the malformed cookie was skipped; accumulator stays empty")))

    (testing ":rf.server/delete-cookie missing :name"
      (let [{:keys [response]} (drive-server-fx! [:rf.server/delete-cookie {:path "/"}])]
        (is (empty? (:cookies response))
            "the nameless delete-cookie was skipped; accumulator stays empty")))

    (testing ":rf.server/redirect with a non-int :status"
      (let [{:keys [response]} (drive-server-fx! [:rf.server/redirect
                                                  {:location "/x" :status "oops"}])]
        (is (nil? (:redirect response))
            "no :redirect landed")
        (is (integer? (:status response))
            "and the non-int status did not flow onto :status via Spec 011
             §Redirect precedence step 1")))

    (testing ":rf.server/safe-redirect with a non-int :status"
      ;; Without a :schema on safe-redirect, a valid-location-but-non-int-
      ;; :status arg would pass the (absent) boundary and safe-redirect-fx's
      ;; step-5 pass would write the string :status straight onto the
      ;; response accumulator.
      (let [{:keys [response]} (drive-server-fx! [:rf.server/safe-redirect
                                                  {:location "/ok" :status "not-int"}])]
        (is (nil? (:redirect response))
            "the malformed safe-redirect was skipped — no :redirect landed")
        (is (not= "not-int" (:status response))
            "the non-int status never reached the response accumulator")))

    (testing ":rf.server/safe-redirect missing :location"
      ;; safe-redirect REQUIRES a :location (its validation target) — a
      ;; target-less safe-redirect is a programmer error, NOT the documented
      ;; no-target graceful path that the caller-trusted :rf.server/redirect
      ;; carries.
      (let [{:keys [response]} (drive-server-fx! [:rf.server/safe-redirect {:status 302}])]
        (is (nil? (:redirect response))
            "the target-less safe-redirect was skipped — no :redirect landed")))

    (testing ":rf.server/redirect with no target key PASSES — the warn path,
              not a rejection"
      ;; A redirect with
      ;; no :location/:url/:to is NOT a structural error — the schema is
      ;; permissive (all target keys optional, zero allowed) so the no-target
      ;; case PASSES the Spec 010 §step-5 boundary and falls through to the
      ;; runtime's graceful no-target path. redirect-fx accepts it (location
      ;; is caller-trusted/optional), sets :redirect, and the host adapter is
      ;; the last line — it emits :rf.ssr/ssr-redirect-no-target + a 3xx with
      ;; no Location header so the defect is observable. A schema [:fn]
      ;; requiring a target would 400 here BEFORE the warn→302 path runs,
      ;; contradicting that path. This is the NON-VACUITY control for the
      ;; whole table above: without it, every §1 claim would be satisfied by
      ;; a boundary that refused everything.
      (let [{:keys [response]} (drive-server-fx! [:rf.server/redirect {:status 302}])]
        (is (= {:status 302} (:redirect response))
            "the no-target redirect passed the boundary and set :redirect —
             the adapter's warn+302 path takes over downstream"))))

  ;; -------------------------------------------------------------------------
  ;; §2 — DEV ARM. The rich step-5 diagnostic.
  ;; -------------------------------------------------------------------------
  (when rf.interop/debug-enabled?
    (testing "DEV ARM — with schemas live the Spec 010 §step-5
              boundary rejects FIRST, so the programmer gets the RICH
              diagnostic: :rf.error/schema-validation-failure :where :fx-args,
              naming the failing fx and path, with the fx :skipped and the
              page still rendering. This is the half that genuinely does not
              exist in a release build — `validate-fx!` is
              `(if interop/debug-enabled? (run-validation …) true)`, so there
              it compiles to `true` and never emits."
      (doseq [[label fx-id fx-vec] malformed-server-fx]
        (expect-fx-args-schema-failure!
          (:dev-traces (drive-server-fx! fx-vec)) fx-id label)))

    (testing "DEV ARM (the negative) — the permissive redirect
              schema does NOT reject the no-target case. This is a NEGATIVE
              over the dev trace ring, so under the production gate it would
              pass with the step-5 boundary removed altogether; it belongs in
              the arm with the positives it discriminates against, not
              outside it."
      (let [{:keys [dev-traces]} (drive-server-fx! [:rf.server/redirect {:status 302}])]
        (is (empty? (filter (fn [ev] (and (= :fx-args (-> ev :tags :where))
                                          (= :rf.server/redirect
                                             (-> ev :tags :failing-id))))
                            dev-traces))
            (str "no :fx-args schema failure for a no-target redirect — the"
                 " schema permits it; saw: "
                 (pr-str (mapv (comp :failing-id :tags) dev-traces)))))))

  ;; -------------------------------------------------------------------------
  ;; §3 — PRODUCTION ARM. The always-on witness that makes §1 true in a
  ;;      release build, and the one an off-box shipper actually receives.
  ;; -------------------------------------------------------------------------
  (when-not rf.interop/debug-enabled?
    (testing "PRODUCTION ARM — with step-5 compiled out, the
              reserved fx's OWN guard (re-frame.ssr.response)
              throws, `re-frame.fx` containment catches it, and
              `emit-fx-error!` fans an ALWAYS-ON
              :rf.error/fx-handler-exception naming the offending fx. That
              record is what reaches Sentry / Datadog / the frame-owned
              :observability :errors sink in the build that ships — unlike
              a silent accumulator write, which would be visible
              only as a malformed HTTP response."
      (doseq [[label fx-id fx-vec] malformed-server-fx]
        (let [{:keys [records]} (drive-server-fx! fx-vec)
              hits (filter #(= :rf.error/fx-handler-exception (:error %)) records)]
          (is (seq hits)
              (str label " — an always-on :rf.error/fx-handler-exception"
                   " record reached the :errors axis; saw: "
                   (pr-str (mapv :error records))))
          (is (some #(= fx-id (:failing-id %)) hits)
              (str label " — the record names " fx-id " as the failing fx;"
                   " saw: " (pr-str (mapv :failing-id hits)))))))))

;; ===========================================================================
;; ssr-with-fx-override / ssr-end-to-end. The :fx-overrides redirect and the dispatch-sync →
;; render-to-string → embedded-hash flow are concise smoke complements to
;; ssr-full-request-lifecycle above; they pin the override + hash-emit
;; paths without the per-request frame ceremony.
;; ===========================================================================

(deftest ssr-with-fx-override
  (testing "SSR flow with :fx-overrides redirecting :http/get to a stub"
    (let [stub-fired? (atom false)]
      ;; Stub fx that synthesises an HTTP response. Threads the active
      ;; frame through to the dispatch so :articles/loaded lands in the
      ;; right frame's app-db (per Spec 002 §Routing the dispatch envelope:
      ;; fx handlers receive {:frame frame-id} as their first arg).
      (rf/reg-fx :http/get.canned-articles
        {:platforms #{:server :client}}
        (fn [{:keys [frame]} {:keys [on-success]}]
          (reset! stub-fired? true)
          (when on-success
            (rf/dispatch (conj on-success
                               [{:id "a" :title "Article A"}
                                {:id "b" :title "Article B"}])
                         {:frame frame}))))
      ;; The real fx must be registered for the override to know what
      ;; "http/get" is — register a no-op so it exists.
      (rf/reg-fx :http/get
        {:platforms #{:server :client}}
        (fn [_ _] nil))

      (rf/reg-event :rf/server-init
        (fn [{:keys [db]} [_ _request]]
          {:db (assoc-in db [:rf.runtime/routing :current] {:route-id :route/articles})
           :fx [[:http/get {:url "/api/articles"
                            :on-success [:articles/loaded]}]]}))
      (rf/reg-event :articles/loaded
        (fn [{:keys [db]} [_ articles]] {:db (assoc db :articles articles)}))

      (let [traces (atom [])]
        (rf/register-listener! :trace ::ssr (fn [ev] (swap! traces conj ev)))
        (let [f  (rf.frame/make-anon-frame-record!
                   {:initial-events    [[:rf/server-init {:uri "/articles"}]]
                    :fx-overrides {:http/get :http/get.canned-articles}})
              db (rf/app-db-value f)]
          (rf/unregister-listener! :trace ::ssr)
          (is @stub-fired? "the override redirected the fx to the stub")
          (is (= 2 (count (:articles db)))
              (str "expected 2 articles in db; traces: "
                   (pr-str (mapv :operation @traces))))
          (is (= "Article A" (-> db :articles first :title))))))))

(deftest ssr-end-to-end
  (testing "complete SSR flow: dispatch-sync → render-to-string → embedded hash"
    ;; Register a trivial articles app — an event seeds state, a sub
    ;; reads it, a view renders it.
    (rf/reg-event :articles/seed
      (fn [_coeffects _event]
        {:db {:articles [{:id "a" :title "Article A" :body "Body A"}
                         {:id "b" :title "Article B" :body "Body B"}]}}))
    (rf/reg-sub :articles (fn [db _] (:articles db)))
    ;; Test exercises the `(rf/view :id)` callable head — the runtime
    ;; handle, as opposed to the Var the defn-shape macro defs — so it
    ;; registers through the plain-fn surface `reg-view*` with an explicit
    ;; id, giving an id to look the handle up by and no Var to reach for.
    ;;
    ;; A keyword-id [:pages/articles] hiccup head references no
    ;; view on ANY host: a keyword head is a DOM / custom element
    ;; everywhere, so `[:pages/articles]` would paint an empty
    ;; `<articles>` element and the assertions below would fail.
    (rf/reg-view* :pages/articles
      (fn []
        (let [arts (rf/subscribe-once [:articles])]
          [:div.page
           [:h1 "Recent articles"]
           [:ul
            (for [{:keys [id title body]} arts]
              ^{:key id} [:li [:h3 title] [:p body]])]])))

    ;; Server flow: dispatch the seed event, render the root, capture hash.
    (rf/dispatch-sync [:articles/seed])
    (let [tree [(rf/view :pages/articles)]
          html (rf.ssr/render-to-string tree {:render-hash (rf.ssr/render-tree-hash tree)})]
      (is (str/includes? html "Article A")
          "rendered HTML contains the title from app-db")
      (is (str/includes? html "Article B"))
      (is (re-find #"<div[^>]*data-rf-render-hash=\"[0-9a-f]{8}\""
                   html)
          "root <div> carries a data-rf-render-hash attribute"))))

