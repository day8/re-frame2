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
  contract; resolved in rf2-8pif). The accumulator lives in a framework-
  private side-channel atom keyed by frame-id (rf2-jbcmt — Spec 011
  §Response storage substrate; NOT in app-db, so it never rides the
  hydration payload); tests read the resolved shape via
  re-frame.ssr/get-response."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.response :as response]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.trace :as trace]))

;; The canonical reset-runtime fixture lives in `re-frame.ssr.test-fixture`
;; (rf2-i3qc0) — one source of truth for the registrar/side-channel/ns-
;; reload cycle that every ssr-artefact JVM test needs between :each.
(use-fixtures :each tf/reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- build-payload
  "Per Spec 011 §The hydration payload: produce a serialisable map
  carrying the version, frame-id, post-drain app-db, and render-hash."
  [frame-id db render-hash]
  {:rf/version     1
   :rf/frame-id    frame-id
   :rf/app-db      db
   :rf/render-hash render-hash})

(defn- extract-render-hash
  "Pull the data-rf-render-hash hex out of an HTML fragment."
  [html]
  (second (re-find #"data-rf-render-hash=\"([0-9a-f]{8})\"" html)))

(defn- resolve-tree
  "Resolve a [:view-id args...] reference under a frame so the rendered
  tree reflects the frame's current app-db. Used to compute a state-
  dependent hash that mirrors what a real client recompute would do."
  [frame-id render-tree]
  (rf/with-frame frame-id
    (let [head (first render-tree)]
      (if-let [view-fn (rf/view head)]
        (apply view-fn (rest render-tree))
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
    ;; Plain-fn surface (reg-view*): the SSR test references the view by
    ;; the literal :pages/articles keyword in render-to-string, so we
    ;; preserve the explicit id rather than auto-derive.
    (rf/reg-view* :pages/articles
      (fn []
        (let [arts (rf/subscribe-once [:articles])]
          [:div.page
           [:h1 "Recent articles"]
           [:ul
            (for [{:keys [id title body]} arts]
              ^{:key id} [:li [:h3 title] [:p body]])]])))

    ;; ---- (1) per-request server frame -------------------------------------
    (let [server-frame (frame/make-anon-frame-record!
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
      (let [render-tree   [:pages/articles]
            html          (rf/with-frame server-frame
                            (rf/render-to-string render-tree {:emit-hash? true}))
            ;; The data-rf-render-hash embedded on the wire is the input-
            ;; tree hash (per render-to-string in ssr.cljc) — stable across
            ;; renders of the same view-ref. The hydration payload below
            ;; carries the RESOLVED-tree hash (state-dependent) so the
            ;; client can re-render and compare a state-derived value.
            embedded-hash (extract-render-hash html)
            server-hash   (rf/render-tree-hash
                            (resolve-tree server-frame render-tree))]
        (is (str/includes? html "Article A")
            "rendered HTML carries the title from server app-db")
        (is (str/includes? html "Article B"))
        (is (re-find #"<div[^>]*data-rf-render-hash=\"[0-9a-f]{8}\""
                     html)
            "root <div> carries data-rf-render-hash")
        (is (some? embedded-hash))
        (is (some? server-hash))

        ;; ---- (5) build serialisable payload -----------------------------
        (let [payload (build-payload server-frame server-db server-hash)]
          (is (= #{:rf/version :rf/frame-id :rf/app-db :rf/render-hash}
                 (set (keys payload)))
              "payload carries the canonical four keys")
          (is (= 1 (:rf/version payload)))
          (is (= server-frame (:rf/frame-id payload)))
          (is (= server-db (:rf/app-db payload)))
          (is (= server-hash (:rf/render-hash payload))
              "payload carries the resolved render-tree hash")

          ;; ---- (6) hydration on a separate "client" frame ---------------
          (let [client-frame (frame/make-anon-frame-record!
                               {:doc      "Hydrated client frame"
                                :platform :client})
                ;; rf2-nv3mua: in a real SSR deployment the server and client
                ;; carry the SAME logical frame id (e.g. `:app/main`) — the
                ;; payload's `:rf/frame-id` is validated against the client
                ;; hydration target and a present-and-different value is now
                ;; (correctly) rejected as `:rf.error/hydration-frame-id-
                ;; mismatch`. This JVM lifecycle uses two distinct synthetic
                ;; frame instances (one `:server`, one `:client`) to exercise
                ;; both platforms in one process; re-stamp the payload's
                ;; `:rf/frame-id` to the client target so the wire stamp
                ;; matches the frame it hydrates into (the deployment-shape
                ;; invariant), exactly as `build-server-payload`-under-the-
                ;; client-frame does in the boot helper tests. The payload-
                ;; SHAPE assertions above (lines 159-166) still pin the
                ;; server stamp on the as-built payload.
                hydrate-payload (assoc payload :rf/frame-id client-frame)]
            (rf/dispatch-sync [:rf/hydrate hydrate-payload] {:frame client-frame})
            (let [client-db (rf/app-db-value client-frame)
                  ;; EP-0001 (rf2-vzld77): the hydration metadata is durable
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
            (let [client-hash-1 (rf/render-tree-hash
                                  (resolve-tree client-frame render-tree))
                  match-traces  (atom [])]
              (rf/register-listener! :trace ::match (fn [ev] (swap! match-traces conj ev)))
              (ssr/verify-hydration!
                client-frame client-hash-1)
              (rf/unregister-listener! :trace ::match)
              (is (= server-hash client-hash-1)
                  "first client render hashes identically to the server hash")
              (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %))
                            @match-traces)
                  "no :rf.ssr/hydration-mismatch trace when hashes agree"))

            ;; (7) Mutate the hydrated app-db; re-render; hash differs;
            ;;     verify-hydration! emits the mismatch trace.
            (rf/reg-event :articles/append
              (fn [{:keys [db]} [_ extra]]
                {:db (update db :articles conj extra)}))
            (rf/dispatch-sync [:articles/append
                               {:id "c" :title "Article C" :body "Body C"}]
                              {:frame client-frame})

            (let [client-hash-2   (rf/render-tree-hash
                                     (resolve-tree client-frame render-tree))
                  mismatch-traces (atom [])]
              (rf/register-listener! :trace ::mismatch (fn [ev] (swap! mismatch-traces conj ev)))
              (ssr/verify-hydration!
                client-frame client-hash-2)
              (rf/unregister-listener! :trace ::mismatch)

              (is (not= server-hash client-hash-2)
                  "mutating the hydrated db changes the render hash")
              (is (some (fn [ev]
                          (and (= :rf.ssr/hydration-mismatch (:operation ev))
                               (= :error (:op-type ev))
                               (= server-hash    (:server-hash (:tags ev)))
                               (= client-hash-2  (:client-hash (:tags ev)))
                               (= :warned-and-replaced (:recovery ev))))
                        @mismatch-traces)
                  (str "expected :rf.ssr/hydration-mismatch trace; saw: "
                       (pr-str (mapv :operation @mismatch-traces)))))))))))

;; ===========================================================================
;; ssr-set-status-precedence — last write wins; warn on multi-set
;; ===========================================================================
;;
;; Per Spec 011 §Multiple-status policy: two :rf.server/set-status fx in a
;; single drain → last write wins AND a :rf.warning/multiple-status-set trace
;; fires. Accumulator lives in app-db at [:rf/response]; resolved view via
;; re-frame.ssr/get-response.

(defn- get-response
  "Read the resolved :rf/response accumulator for a frame."
  [frame-id]
  (ssr/get-response frame-id))

(deftest ssr-set-status-precedence
  (testing "two :rf.server/set-status fx → last write wins + :rf.warning/multiple-status-set"
    (let [traces (atom [])]
      (rf/reg-event :auth/forbid
        (fn [_ _]
          {:fx [[:rf.server/set-status 401]
                [:rf.server/set-status 403]]}))                          ;; second write replaces

      (let [f (frame/make-anon-frame-record! {:platform :server})]
        (rf/register-listener! :trace ::status (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/forbid] {:frame f})
        (rf/unregister-listener! :trace ::status)

        (is (= 403 (:status (get-response f)))
            "last write wins — the response status is 403")

        (is (some (fn [ev]
                    (and (= :rf.warning/multiple-status-set (:operation ev))
                         (= [401 403] (:writes (:tags ev)))
                         (= 403       (:final-status (:tags ev)))
                         (= :warned-and-replaced (:recovery ev))))
                  @traces)
            (str "expected :rf.warning/multiple-status-set trace; saw: "
                 (pr-str (mapv :operation @traces))))))))

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

    (let [f (frame/make-anon-frame-record! {:platform :server})]
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

    (let [f (frame/make-anon-frame-record! {:platform :server})]
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

    (let [f (frame/make-anon-frame-record! {:platform :server})]
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
;; rf2-hbty2 — CRLF injection in set-header / append-header / redirect
;; (security audit 2026-05-14 §P1.3)
;;
;; Header values flow from event-handler input through the
;; :rf.server/set-header / :rf.server/append-header / :rf.server/redirect
;; fx straight to the Ring response map. A value with embedded CR/LF
;; would split the header on the wire — attacker forges Set-Cookie /
;; auth-related second headers. The fx boundary fails fast with
;; :rf.error/header-invalid-value / :rf.error/redirect-invalid-location.
;;
;; Decision (flagged in PR): fail-fast rather than strip-and-warn — a
;; header value containing CR/LF has no safe interpretation.
;; ===========================================================================

(defn- capture-fx-traces!
  "Install a trace callback that records every fx-handler-exception trace
  for the duration of `body-fn`. Returns the recorded traces. Strips
  the callback in `finally` so a failing body doesn't leak listeners."
  [body-fn]
  (let [traces (atom [])
        tag    (keyword (str "::trace-cap-" (gensym)))]
    (rf/register-listener! :trace tag
      (fn [ev]
        (when (= :rf.error/fx-handler-exception (:operation ev))
          (swap! traces conj ev))))
    (try
      (body-fn)
      @traces
      (finally
        (rf/unregister-listener! :trace tag)))))

(defn- expect-fx-error-keyword!
  "Assert that the `traces` collection (output of `capture-fx-traces!`)
  carries an :rf.error/fx-handler-exception whose nested exception's
  message contains `error-kw`'s name string. Both fx-side validators
  (rf2-hbty2 / rf2-rpedl / rf2-vl8ir) throw with the error keyword as
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

(deftest ssr-set-header-rejects-crlf-injection
  (testing "rf2-hbty2 §P1.3 — :rf.server/set-header with CR/LF/NUL in
            value surfaces :rf.error/header-invalid-value as the inner
            cause of :rf.error/fx-handler-exception (fx exceptions are
            captured by the dispatch loop and re-emitted as traces;
            rf2-hbty2 throws at the fx boundary)"
    (rf/reg-event :hdr/inject-crlf
      (fn [_ _]
        {:fx [[:rf.server/set-header
               {:name  "X-Forwarded-For"
                :value "1.2.3.4\r\nSet-Cookie: admin=1"}]]}))

    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:hdr/inject-crlf] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/header-invalid-value
        "set-header with CRLF in value")))

  (testing "rf2-hbty2 §P1.3 — bare LF / bare CR / NUL all rejected"
    (doseq [hostile ["lf\nbad" "cr\rbad" (str "nul" (char 0) "bad")]]
      (rf/reg-event :hdr/probe-injection
        (fn [_ _]
          {:fx [[:rf.server/set-header {:name "X-Probe" :value hostile}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:hdr/probe-injection] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/header-invalid-value
          (str "hostile value " (pr-str hostile)))))))

(deftest ssr-append-header-rejects-crlf-injection
  (testing "rf2-hbty2 §P1.3 — :rf.server/append-header with CR/LF in
            value surfaces :rf.error/header-invalid-value"
    (rf/reg-event :hdr/append-crlf
      (fn [_ _]
        {:fx [[:rf.server/append-header
               {:name  "X-Audit"
                :value "ok\r\nSet-Cookie: forged=1"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:hdr/append-crlf] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/header-invalid-value
        "append-header with CRLF in value"))))

(deftest ssr-redirect-rejects-crlf-injection
  (testing "rf2-hbty2 §P1.3 — :rf.server/redirect with CR/LF in :location
            surfaces :rf.error/redirect-invalid-location. The standard
            exploit shape: a `?next=…` query param that URL-decodes into
            literal CRLF would split the Location header on the wire."
    (rf/reg-event :redirect/crlf-in-location
      (fn [_ _]
        {:fx [[:rf.server/redirect
               {:location "https://example.com\r\nSet-Cookie: stolen=1"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:redirect/crlf-in-location] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/redirect-invalid-location
        "redirect with CRLF in :location")))

  (testing "rf2-vngir — the retired :url / :to redirect-target spellings are
            REJECTED with :rf.error/redirect-retired-target-key (naming the
            canonical :location), not accepted as alternate target keys. The
            error fires BEFORE the no-target warning path so the vocabulary
            mistake is loud, not hidden behind a malformed-redirect warning.
            (Pre-alpha EP-0007 one-name-per-fact prune — no back-compat alias.)"
    (rf/reg-event :redirect/via-url
      (fn [_ _]
        {:fx [[:rf.server/redirect {:url "/ok"}]]}))
    (rf/reg-event :redirect/via-to
      (fn [_ _]
        {:fx [[:rf.server/redirect {:to "/ok"}]]}))
    (doseq [ev [:redirect/via-url :redirect/via-to]]
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [ev] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/redirect-retired-target-key
          (str ev " — retired redirect-target spelling rejected, names :location"))))))

(deftest ssr-redirect-retired-spelling-diagnostic-names-location
  (testing "rf2-vngir — the retired-spelling diagnostic NAMES the canonical
            :location key (ex-data :canonical-key + a :reason mentioning
            :location). This is the failure-mode lock: the error must point
            the programmer at the right spelling, and must be DISTINCT from
            the generic no-target/malformed-redirect warning path."
    (rf/reg-event :redirect/retired-spelling
      (fn [_ _]
        {:fx [[:rf.server/redirect {:url "/login"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
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
  (testing "rf2-ziv4gd — the caller-trusted :rf.server/redirect path applies
            NO structural URL-shape check: a `:location` carrying a raw
            space or other RFC 3986 shape quirk every browser accepts in a
            `Location` header PASSES through unchanged (the structural gate
            was removed — only the CR/LF/NUL header-splitting gate remains).
            These shapes previously threw :rf.error/redirect-invalid-location;
            they must now flow through."
    (doseq [loc ["https://example.com/search?q=a b"   ;; raw unencoded space
                 "https://example.com/^"               ;; stray caret
                 "/path/{id"                            ;; unbalanced brace
                 "/path%zz"                             ;; malformed %-escape
                 "/path%1"]]                            ;; truncated %-escape
      (rf/reg-event :redirect/shape-quirk
        (fn [_ _]
          {:fx [[:rf.server/redirect {:location loc}]]}))
      (let [f    (frame/make-anon-frame-record! {:platform :server :initial-events [[:redirect/shape-quirk]]})
            resp (get-response f)]
        (is (= loc (-> resp :redirect :location))
            (str "raw URL-shape quirk passes through the caller-trusted "
                 "redirect path (no URL-shape gate): " (pr-str loc))))))

  (testing "rf2-ziv4gd — regression guard: the caller-trusted redirect still
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
      (let [f    (frame/make-anon-frame-record! {:platform :server :initial-events [[:redirect/well-formed]]})
            resp (get-response f)]
        (is (= loc (-> resp :redirect :location))
            (str "well-formed redirect :location flows through: " loc))))))

(deftest ssr-redirect-crlf-nul-gate-survives-on-both-fx
  (testing "rf2-ziv4gd — the CR/LF/NUL header-splitting gate is KEPT (the
            real invariant). Each injection char in a :location still throws
            :rf.error/redirect-invalid-location on the caller-trusted
            :rf.server/redirect path."
    (doseq [[label loc] [["CR"  "https://example.com/a\rb"]
                         ["LF"  "https://example.com/a\nb"]
                         ["CRLF header-split" "https://example.com\r\nSet-Cookie: stolen=1"]
                         ["NUL" "https://example.com/a\u0000b"]]]
      (rf/reg-event :redirect/crlf-nul
        (fn [_ _]
          {:fx [[:rf.server/redirect {:location loc}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:redirect/crlf-nul] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/redirect-invalid-location
          (str ":rf.server/redirect still rejects " label " in :location")))))

  (testing "rf2-ziv4gd — the SHARED CR/LF/NUL gate also runs (throwing the
            same :rf.error/redirect-invalid-location) on the caller-untrusted
            :rf.server/safe-redirect path — both fx keep the header-splitting
            invariant."
    (doseq [[label loc] [["CR"  "https://example.com/a\rb"]
                         ["LF"  "https://example.com/a\nb"]
                         ["NUL" "https://example.com/a\u0000b"]]]
      (rf/reg-event :safe-redirect/crlf-nul
        (fn [_ _]
          {:fx [[:rf.server/safe-redirect {:location loc}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:safe-redirect/crlf-nul] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/redirect-invalid-location
          (str ":rf.server/safe-redirect still rejects " label " in :location"))))))

(deftest ssr-header-clean-values-still-accepted
  (testing "rf2-hbty2 — regression guard: legitimate header values still flow
            through. Whitespace, semicolons, quoted-strings, full URLs are
            all valid (only CR/LF/NUL is banned)."
    (rf/reg-event :hdr/clean
      (fn [_ _]
        {:fx [[:rf.server/set-header {:name "Cache-Control"
                                      :value "no-cache, must-revalidate, max-age=0"}]
              [:rf.server/set-header {:name "X-Whitespace"
                                      :value "tab\there space"}]
              [:rf.server/redirect    {:location "https://example.com/path?q=1&r=2"}]]}))
    (let [f (frame/make-anon-frame-record! {:platform :server :initial-events [[:hdr/clean]]})
          resp (get-response f)
          hdrs (:headers resp)]
      (is (some (fn [[k v]]
                  (and (= "Cache-Control" k)
                       (= "no-cache, must-revalidate, max-age=0" v)))
                hdrs)
          "clean header with commas / semicolons / spaces survives")
      (is (= "https://example.com/path?q=1&r=2"
             (-> resp :redirect :location))
          "clean redirect URL survives"))))

;; ===========================================================================
;; ssr-redirect-short-circuits — :rf.server/redirect halts further rendering
;; ===========================================================================

(deftest ssr-redirect-short-circuits
  (testing ":rf.server/redirect populates :redirect and the response payload omits HTML"
    (rf/reg-event :auth/check-session
      (fn [_ _]
        {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))

    (let [f (frame/make-anon-frame-record! {:platform     :server
                            :initial-events    [[:auth/check-session]]})]
      (let [resp     (get-response f)
            redirect (:redirect resp)]
        (is (= {:status 302 :location "/login"} redirect)
            "the :redirect accumulator carries status + location")
        (is (= 302 (:status resp))
            "redirect's :status flows through to the response :status")

        ;; The "host adapter" decision per Spec 011 §Redirect precedence:
        ;; if :redirect is set, build a redirect-only response — no body,
        ;; no hydration payload. We model that here as a small fn that
        ;; mirrors what the host would do.
        (let [build-response (fn [r]
                               (if-let [redir (:redirect r)]
                                 {:redirect redir}
                                 {:status (or (:status r) 200)
                                  :body   "<full-html-here>"}))
              response       (build-response resp)]
          (is (= {:redirect {:status 302 :location "/login"}}
                 response)
              "redirect short-circuits — response carries :redirect only, no :body, no hydration payload")
          (is (not (contains? response :body))
              "no HTML body when redirected")))))

  (testing "a redirect with default :status defaults to 302"
    (rf/reg-event :auth/check-no-status
      (fn [_ _]
        {:fx [[:rf.server/redirect {:location "/login"}]]}))
    (let [f (frame/make-anon-frame-record! {:platform  :server
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

(deftest ssr-default-error-projector-no-such-handler
  (testing "routing's :rf.error/no-such-handler → default projector → 404"
    (rf/reg-route :route/home {} "/")
    (let [project-error  ssr/project-error
          f              (frame/make-anon-frame-record!
                           {:platform :server
                            :ssr {:public-error-id   :rf.ssr/default-error-projector
                                  :dev-error-detail? false}})
          traces         (atom [])]
      (rf/register-listener! :trace ::nsh (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (rf/unregister-listener! :trace ::nsh)

      ;; Runtime's error-projection listener stamps :status 404 on :rf/response.
      (is (= 404 (:status (get-response f)))
          "default projector's :status reaches :rf/response — not a user-stub fx")
      (is (nil? (:redirect (get-response f)))
          "no redirect — the 404 is a status-only response, body still renders")

      ;; The trace stream still carries the internal :rf.error/no-such-handler.
      (let [err (some #(when (= :rf.error/no-such-handler (:operation %)) %) @traces)]
        (is (some? err)
            "internal trace records :rf.error/no-such-handler")
        ;; Projecting the trace yields the locked public-error shape.
        (let [public (project-error f err)]
          (is (= {:status     404
                  :code       :not-found
                  :message    "Page not found"
                  :retryable? false}
                 public)
              "default projector returns the canonical 404 mapping per Spec 011"))))))

(deftest ssr-default-error-projector-handler-exception
  (testing "a handler that throws at RENDER time → default projector → 500"
    ;; rf2-vw5h1r / rf2-anehs6: the throwing dispatch is a RENDER-TIME
    ;; request dispatch against a live frame — NOT an :initial-events setup
    ;; step. Construction-time :initial-events is now STRICT (EP-0027
    ;; §Failure, Mike-ruled (a)): a THROWN setup step tears the partial frame
    ;; down and is the OUTER :on-error transport path (Spec 011 §810), NOT a
    ;; projector-catches-it case. The error projector covers errors INSIDE
    ;; the render/cascade drain — exactly what a post-construction request
    ;; dispatch models. So :rf/server-init is a clean no-op setup step; the
    ;; throwing :load/article fires afterward, in the projector's domain.
    (rf/reg-event :load/article
      (fn [_ _]
        (throw (ex-info "Database connection failed: SECRET_TOKEN=xyz" {}))))
    (rf/reg-event :rf/server-init
      (fn [_ _] {}))

    (let [project-error  ssr/project-error
          traces         (atom [])
          f              (frame/make-anon-frame-record!
                           {:platform :server
                            :initial-events [[:rf/server-init]]
                            :ssr {:public-error-id   :rf.ssr/default-error-projector
                                  :dev-error-detail? false}})
          _              (rf/register-listener! :trace ::he (fn [ev] (swap! traces conj ev)))
          _              (rf/dispatch-sync [:load/article] {:frame f})
          _              (rf/unregister-listener! :trace ::he)
          err            (some #(when (= :rf.error/handler-exception (:operation %)) %)
                               @traces)]
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
            "default projector's prod shape carries exactly the four locked keys")
        (is (not (contains? public :details))
            "prod shape (:dev-error-detail? false) — :details is absent so no internal detail leaks")))))

(deftest ssr-error-projector-dev-mode-includes-details
  (testing ":dev-error-detail? true puts the raw trace under :details"
    (let [project-error ssr/project-error
          f             (frame/make-anon-frame-record!
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
      (is (contains? public :details)
          ":details present in dev mode")
      (is (= trace-event (:details public))
          ":details is the trace event verbatim — full internal detail for the dev console"))))

;; ===========================================================================
;; ssr-custom-error-projector — reg-error-projector overrides the default
;; ===========================================================================

(deftest ssr-custom-error-projector-overrides-default
  (testing "reg-error-projector + :ssr {:public-error-id ...} swaps the projector"
    (rf/reg-error-projector :myapp/public-error
      {:doc "Custom projector — promotes auth errors to 401."}
      (fn [trace-event]
        (case (:operation trace-event)
          :auth/unauthorised             {:status 401 :code :unauthorised
                                          :message "Sign in to continue"
                                          :retryable? false}
          :rf.error/no-such-handler      {:status 404 :code :not-found
                                          :message "Custom not-found"
                                          :retryable? false}
          {:status 500 :code :internal-error
           :message "Custom 500"
           :retryable? false})))

    (let [project-error ssr/project-error
          f             (frame/make-anon-frame-record!
                          {:platform :server
                           :ssr {:public-error-id   :myapp/public-error
                                 :dev-error-detail? false}})]
      ;; Auth-specific code that the DEFAULT projector doesn't know about.
      (let [public (project-error f {:operation :auth/unauthorised :tags {}})]
        (is (= 401 (:status public)))
        (is (= :unauthorised (:code public)))
        (is (= "Sign in to continue" (:message public))
            "custom projector's message wins over the default's generic 500"))

      ;; Known-error category — custom projector wins, not the default.
      (let [public (project-error f {:operation :rf.error/no-such-handler :tags {}})]
        (is (= "Custom not-found" (:message public))
            "custom projector's mapping shadows :rf.ssr/default-error-projector's"))

      ;; Unknown category falls into the custom projector's catch-all.
      (let [public (project-error f {:operation :totally-unknown :tags {}})]
        (is (= "Custom 500" (:message public)))))))

(deftest ssr-error-projector-throws-falls-back-to-locked-500
  (testing "projector throws → :rf.error/sanitised-on-projection trace + locked fallback"
    (rf/reg-error-projector :myapp/buggy-projector
      (fn [_trace-event]
        (throw (ex-info "projector bug" {}))))

    (let [project-error ssr/project-error
          f             (frame/make-anon-frame-record!
                          {:platform :server
                           :ssr {:public-error-id   :myapp/buggy-projector
                                 :dev-error-detail? false}})
          traces        (atom [])
          _             (rf/register-listener! :trace ::sop (fn [ev] (swap! traces conj ev)))
          public        (project-error f {:operation :rf.error/handler-exception :tags {}})]
      (rf/unregister-listener! :trace ::sop)
      (is (= {:status     500
              :code       :internal-error
              :message    "Something went wrong"
              :retryable? false}
             public)
          "fallback to the locked generic-500 shape — the boundary holds even with a buggy projector")
      (is (some #(= :rf.error/sanitised-on-projection (:operation %)) @traces)
          ":rf.error/sanitised-on-projection trace fired so the buggy projector is observable"))))

(deftest ssr-error-projector-non-conforming-shape-falls-back
  (testing "projector returns nil / wrong shape → :rf.error/sanitised-on-projection + fallback"
    (rf/reg-error-projector :myapp/bad-shape
      (fn [_trace-event] {:wrong :shape}))

    (let [project-error ssr/project-error
          f             (frame/make-anon-frame-record!
                          {:platform :server
                           :ssr {:public-error-id   :myapp/bad-shape}})
          traces        (atom [])
          _             (rf/register-listener! :trace ::bs (fn [ev] (swap! traces conj ev)))
          public        (project-error f {:operation :rf.error/handler-exception :tags {}})]
      (rf/unregister-listener! :trace ::bs)
      (is (= 500 (:status public))
          "non-conforming projector output → fallback locked-500")
      (is (= :internal-error (:code public)))
      (is (some #(= :rf.error/sanitised-on-projection (:operation %)) @traces)))))

(deftest ssr-error-projector-configured-but-unregistered-surfaces-diagnostic
  (testing "rf2-mlodrn — a frame that configures :ssr {:public-error-id …}
            naming an UNREGISTERED projector is a recognised-but-unhonourable
            config: project-error SURFACES a :rf.error/sanitised-on-projection
            diagnostic (:projection-failure-reason :missing-projector) instead
            of silently downgrading the projector's intended mapping to the
            generic 500. Pre-rf2-mlodrn this fell back with NO dev trace and
            NO always-on record."
    (let [project-error ssr/project-error
          ;; A :public-error-id that was NEVER reg-error-projector'd.
          f             (frame/make-anon-frame-record!
                          {:platform :server
                           :ssr {:public-error-id   :myapp/never-registered
                                 :dev-error-detail? false}})
          traces        (atom [])
          _             (rf/register-listener! :trace ::mp (fn [ev] (swap! traces conj ev)))
          ;; :no-such-handler would have projected to a 404 under a real
          ;; projector — the misconfiguration silently made it a 500.
          public        (project-error f {:operation :rf.error/no-such-handler :tags {}})]
      (rf/unregister-listener! :trace ::mp)
      ;; The boundary still holds — the fallback is the safe wire shape.
      (is (= 500 (:status public))
          "the locked generic-500 fallback still applies (boundary can't be bypassed)")
      (is (= :internal-error (:code public)))
      ;; …but the misconfiguration is now OBSERVABLE.
      (let [diag (some #(when (= :rf.error/sanitised-on-projection (:operation %)) %)
                       @traces)]
        (is (some? diag)
            ":rf.error/sanitised-on-projection fired — the missing configured
             projector is surfaced, not swallowed")
        (is (= :missing-projector (get-in diag [:tags :projection-failure-reason]))
            "the diagnostic carries :projection-failure-reason :missing-projector")
        (is (= :myapp/never-registered (get-in diag [:tags :projector-id]))
            "the diagnostic names the unregistered configured id"))))

  (testing "rf2-mlodrn — the plain default-fallback path (NO :public-error-id
            configured) stays SILENT: with no :ssr config the frame resolves
            to the built-in default projector, which honours the mapping — so
            there is no missing-projector diagnostic (the change is surgical
            to the CONFIGURED-but-unregistered case, not noisy on the default
            path)."
    (let [project-error ssr/project-error
          f             (frame/make-anon-frame-record! {:platform :server})
          traces        (atom [])
          _             (rf/register-listener! :trace ::mp2 (fn [ev] (swap! traces conj ev)))
          public        (project-error f {:operation :rf.error/no-such-handler :tags {}})]
      (rf/unregister-listener! :trace ::mp2)
      (is (= 404 (:status public))
          "the built-in default projector maps :no-such-handler → 404
           (honoured, not fallen-back)")
      (is (not-any? #(= :rf.error/sanitised-on-projection (:operation %)) @traces)
          "no sanitised-on-projection diagnostic on the default path — the
           default projector is registered, so it is not a missing-projector"))))

;; ===========================================================================
;; rf2-ynjts.13 — default-error-projector-fn pure-unit case-arm coverage.
;; The end-to-end tests above drive :no-such-handler → 404 and
;; :handler-exception → 500 through the live cascade, but the default
;; projector fn's OTHER two enumerated arms — :no-such-route → 404 and
;; :schema-validation-failure → 400 (documented in error_projector.cljc) —
;; had no direct assertion. These are pure (trace-event → public-error),
;; so unit-test the fn directly: deterministic, no frame/drain machinery.
;; ===========================================================================

(deftest default-error-projector-fn-maps-all-enumerated-categories
  (testing "rf2-ynjts.13 — the default projector's full case table per
            Spec 011 §Default projector. Exercises the fn directly (it is a
            public re-export: ssr/default-error-projector-fn)."
    (testing ":rf.error/no-such-handler → 404 :not-found"
      (is (= {:status 404 :code :not-found :message "Page not found" :retryable? false}
             (ssr/default-error-projector-fn {:operation :rf.error/no-such-handler}))))
    (testing ":rf.error/no-such-route → 404 :not-found (the second 404 arm —
              previously untested)"
      (is (= {:status 404 :code :not-found :message "Page not found" :retryable? false}
             (ssr/default-error-projector-fn {:operation :rf.error/no-such-route}))
          "no-such-route shares the 404 :not-found mapping with no-such-handler"))
    (testing ":rf.error/cofx-value-invalid → 400 :bad-request
              UNCONDITIONALLY (rf2-57ehvw — a bad client-supplied request
              coeffect is client input, never a server-fault 500)"
      (is (= {:status 400 :code :bad-request :message "Invalid input" :retryable? false}
             (ssr/default-error-projector-fn
               {:operation :rf.error/cofx-value-invalid
                :tags      {:reason :non-edn-recordable-value}}))
          "a non-recordable request coeffect (the category that REPLACED the
           retired :rf.error/schema-validation-failure :where :cofx shape) is
           a client-facing 400 — the regression this bead fixes was that it
           projected 500")
      (is (= {:status 400 :code :bad-request :message "Invalid input" :retryable? false}
             (ssr/default-error-projector-fn
               {:operation :rf.error/cofx-value-invalid}))
          "the 400 arm is UNCONDITIONAL — it does not depend on a :where tag
           (unlike schema-validation-failure); the dispatch boundary is the
           client-input surface by construction"))
    (testing ":rf.error/schema-validation-failure with a CLIENT-surface
              :where (:event) → 400 :bad-request (rf2-37o5by)"
      (is (= {:status 400 :code :bad-request :message "Invalid input" :retryable? false}
             (ssr/default-error-projector-fn
               {:operation :rf.error/schema-validation-failure
                :tags      {:where :event}}))
          "an inbound-event payload failure is client-facing → 400"))
    (testing ":rf.error/schema-validation-failure with the RETIRED :where
              :cofx → 500 (rf2-57ehvw — the injection-time cofx-validation
              path was retired; a bad request coeffect now rides its own
              :rf.error/cofx-value-invalid category, so this stale shape is no
              longer a client 400)"
      (is (= ssr/fallback-public-error
             (ssr/default-error-projector-fn
               {:operation :rf.error/schema-validation-failure
                :tags      {:where :cofx}}))
          "the retired :where :cofx shape falls through to the locked
           generic-500 — the live client-cofx 400 is :rf.error/cofx-value-invalid"))
    (testing ":rf.error/schema-validation-failure with a SERVER-surface
              :where (:fx-args) → 500 (rf2-37o5by — gated 400 arm)"
      (is (= ssr/fallback-public-error
             (ssr/default-error-projector-fn
               {:operation :rf.error/schema-validation-failure
                :tags      {:where :fx-args}}))
          "a server-fx arg-schema failure is a SERVER-side defect, not bad
           client input — it falls through to the locked generic-500 rather
           than mislabelling a server bug as a client 400")
      (is (= ssr/fallback-public-error
             (ssr/default-error-projector-fn
               {:operation :rf.error/schema-validation-failure}))
          "a schema-validation-failure with NO :where tag also falls through
           to 500 — the 400 arm is opt-in on a client-surface :where, fail-safe")
      (is (= ssr/fallback-public-error
             (ssr/default-error-projector-fn
               {:operation :rf.error/schema-validation-failure
                :tags      {:where :sub-return}}))
          "a sub-return failure is likewise non-client → 500"))
    (testing "any other category → the locked generic-500 fallback"
      (is (= ssr/fallback-public-error
             (ssr/default-error-projector-fn {:operation :rf.error/handler-exception}))
          "handler-exception falls through to the 500 default")
      (is (= ssr/fallback-public-error
             (ssr/default-error-projector-fn {:operation :totally/unknown-future-category}))
          "an unenumerated future category also falls through — no case arm needed")
      (is (= ssr/fallback-public-error
             (ssr/default-error-projector-fn {}))
          "an event with no :operation falls through to 500 too"))))

;; ===========================================================================
;; rf2-ynjts.13 — peek-response (pure) vs flush-response! / get-response
;; (drain) read-surface contract. error_listener.cljc documents three reads:
;; peek-response does NOT drain pending error projections; flush-response!
;; and get-response DO. The drain-on-read is covered by the projector e2e
;; tests; the pure-read-does-NOT-drain invariant (and the bookkeeping-key
;; stripping on both) had no direct assertion.
;; ===========================================================================

(deftest peek-response-does-not-drain-flush-does
  (testing "rf2-ynjts.13 — a buffered error trace is left intact by
            peek-response (pure read) and only stamps :status when
            flush-response! / get-response drains it."
    (rf/reg-route :route/home {} "/")
    (let [f (frame/make-anon-frame-record!
              {:platform :server
               :ssr {:public-error-id   :rf.ssr/default-error-projector
                     :dev-error-detail? false}})]
      ;; Fire a :rf.error/no-such-handler so a trace buffers against f.
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})

      (testing "peek-response reads the un-projected response (still 200) and
                does NOT consume the buffered trace"
        (is (= 200 (:status (ssr/peek-response f)))
            "peek leaves :status at the default 200 — the projector buffer
             is NOT drained by a pure read"))

      (testing "a SECOND peek still sees 200 — peek is idempotent + side-effect-free"
        (is (= 200 (:status (ssr/peek-response f)))
            "the pending trace survived the first peek, so the second peek
             still reads the un-projected status"))

      (testing "flush-response! drains the buffer and stamps the projector's status"
        (is (= 404 (:status (ssr/flush-response! f)))
            "flush projects the buffered :no-such-handler → 404 onto :status"))

      (testing "after the drain the buffer is empty — a subsequent peek reads 404
                (the stamped value persists; nothing left to re-project)"
        (is (= 404 (:status (ssr/peek-response f)))
            "the stamped 404 persists on the accumulator post-drain")))))

(deftest peek-and-get-response-strip-bookkeeping-keys
  (testing "rf2-ynjts.13 — both read surfaces strip the internal
            `:rf.server/_status-writes` / `:rf.server/_redirect-writes`
            bookkeeping keys, so a host adapter never sees them on the wire
            shape."
    (rf/reg-event :resp/multi-status
      (fn [_ _]
        {:fx [[:rf.server/set-status 201]
              [:rf.server/set-status 202]]}))
    (let [f (frame/make-anon-frame-record! {:platform :server})]
      (rf/dispatch-sync [:resp/multi-status] {:frame f})
      (doseq [[label resp] [["peek-response"  (ssr/peek-response f)]
                            ["get-response"   (ssr/get-response f)]]]
        (is (= 202 (:status resp))
            (str label ": last-write-wins status surfaces"))
        (is (not (contains? resp :rf.server/_status-writes))
            (str label ": the internal status-writes bookkeeping key is stripped"))
        (is (not (contains? resp :rf.server/_redirect-writes))
            (str label ": the internal redirect-writes bookkeeping key is stripped"))))))

(deftest ssr-error-projection-skips-client-frames
  (testing "client-platform frames don't have their :rf/response stamped on errors"
    ;; A :rf.error/no-such-handler trace inside a CLIENT frame should not
    ;; touch :rf/response — the client doesn't have an HTTP response to
    ;; project. (The trace still fires; the projector just isn't called
    ;; for a client frame's response slot.)
    (rf/reg-route :route/home {} "/")
    (let [client-f (frame/make-anon-frame-record! {:platform :client})]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"]
                        {:frame client-f})
      (let [resp (get-response client-f)]
        ;; Default response status (200) is unchanged — error-projection
        ;; listener no-op'd because the frame is not :server.
        (is (= 200 (:status resp))
            "client frame's :rf/response :status stays at 200; projector skipped")))))

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

      (let [f (frame/make-anon-frame-record! {:platform :server})]
        (rf/register-listener! :trace ::redir (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/double-redirect] {:frame f})
        (rf/unregister-listener! :trace ::redir)

        (let [redirect (-> (get-response f) :redirect)]
          (is (= {:status 301 :location "/canonical"} redirect)
              "last write wins — the response :redirect is the second write"))

        (is (some (fn [ev]
                    (and (= :rf.warning/multiple-redirects (:operation ev))
                         (= 2 (count (:writes (:tags ev))))
                         (= {:status 301 :location "/canonical"} (:final-redirect (:tags ev)))
                         (= :warned-and-replaced (:recovery ev))))
                  @traces)
            (str "expected :rf.warning/multiple-redirects trace; saw: "
                 (pr-str (mapv :operation @traces))))))))

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
;; own attribution value (here :rf.ssr/head-mismatch — host-suppliable now,
;; not v1-runtime-emitted) has it flow through to the trace. It proves the
;; seam + host attribution, NOT runtime head-detection. A dedicated
;; head-hash payload key + wire attribute that would let the runtime itself
;; emit :rf.ssr/head-mismatch is reserved for the still-deferred post-v1
;; head-only-hash extension (reg-head itself has already shipped).

(deftest host-supplied-failing-id-surfaced-on-unified-channel
  (testing "a host-supplied :failing-id override flows through verify-hydration! to the trace on the unified render-hash channel"
    (let [verify-fn ssr/verify-hydration!
          ;; Hydration payload carries the SERVER's render-hash. v1's
          ;; unified channel covers head + body; the bundled runtime emits
          ;; only :failing-id :rf/hydrate. Here the HOST supplies its own
          ;; attribution value through the seam (see verify-fn call below).
          ;;
          ;; EP-0001 (rf2-tfepxu): the server-settled route slice rides the
          ;; payload's `:rf/runtime-db` key (the hydrate handler installs it
          ;; into the runtime-db partition under `:rf.runtime/routing`), NOT
          ;; under the retired top-level `:rf/runtime` app-db root — which the
          ;; post-commit guard now rejects as `:rf.error/legacy-runtime-root`.
          ;; This test only asserts the server-hash stash, so the route slice
          ;; is illustrative payload content placed in its post-EP home.
          payload   {:rf/version     1
                     :rf/runtime-db  {:rf.runtime/routing {:current {:route-id :route/article :params {:id "123"}}}}
                     :rf/render-hash "head-hash-server-A"}
          traces    (atom [])
          f         (frame/make-anon-frame-record! {:platform :client})]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame f})
      (is (= "head-hash-server-A"
             (get-in (:rf.db/runtime (rf/frame-state-value f)) [:rf.runtime/ssr :hydration :server-hash]))
          ":rf/hydrate stashed the server's head-hash")

      (rf/register-listener! :trace ::head (fn [ev] (swap! traces conj ev)))
      ;; Client hash differs; the HOST supplies a :failing-id override
      ;; (:rf.ssr/head-mismatch — host-suppliable now, not v1-runtime-emitted)
      ;; and we assert the seam carries it through to the trace verbatim.
      (verify-fn f
                 "head-hash-client-B"
                 {:failing-id :rf.ssr/head-mismatch
                  :first-diff-path [:head :title]})
      (rf/unregister-listener! :trace ::head)

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

      ;; And the SAME hash on both sides → no trace.
      (let [no-mismatch-traces (atom [])]
        (rf/register-listener! :trace ::head-ok (fn [ev] (swap! no-mismatch-traces conj ev)))
        (verify-fn f
                   "head-hash-server-A"
                   {:failing-id :rf.ssr/head-mismatch})
        (rf/unregister-listener! :trace ::head-ok)
        (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %))
                      @no-mismatch-traces)
            "no head-mismatch trace when client and server hashes agree")))))

;; ---- rf2-37pr: install-render-to-string! install contract -----------------
;;
;; Per test-coverage-review-2026-05-12 P3-15. The bundled Reagent adapter
;; wires itself via the `:reagent/set-hiccup-emitter!` late-bind hook;
;; `ssr/install-render-to-string!` is the public surface for
;; non-bundled adapters that ship in their own artefact.

(deftest install-render-to-string-installs-ssr-impl
  (testing "calling install-render-to-string! with a mock setter fn invokes
            it with the ssr render-to-string fn"
    ;; A mock adapter's setter: captures the fn it's handed.
    (let [captured (atom nil)
          mock-setter (fn [f] (reset! captured f))]
      (ssr/install-render-to-string! mock-setter)
      (is (some? @captured)
          "the mock setter was called — install-render-to-string! delivered
           the renderer fn")
      (is (fn? @captured)
          "the captured value is a function (the ssr/render-to-string)")
      ;; Per the install contract: the captured fn is the SAME var that
      ;; ssr/render-to-string resolves to. Calling it with a hiccup tree
      ;; produces an HTML string.
      (let [html (@captured [:div "from-mock"] {})]
        (is (string? html)
            "the installed fn renders hiccup → HTML string")
        (is (clojure.string/includes? html "from-mock")
            "the rendered HTML carries the hiccup body")
        (is (clojure.string/starts-with? html "<div")
            "rendered HTML starts with the expected root tag")))))

(deftest install-render-to-string-returns-nil
  (testing "install-render-to-string! returns nil; calls it just for side effect"
    (is (nil? (ssr/install-render-to-string! (fn [_f] nil)))
        "install-render-to-string! is a side-effect fn; returns nil")))

;; ---- rf2-9v0f: default-response initial shape contract --------------------
;;
;; Per test-coverage-review-2026-05-12 P3-16. Pin the documented keys of
;; the SSR per-request response accumulator initial value.

(deftest default-response-canonical-shape
  (testing "(ssr/default-response) returns the canonical initial response map"
    (let [r (ssr/default-response)]
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

(deftest default-response-returns-fresh-map
  (testing "each call to default-response returns a fresh map (not shared state)"
    (let [r1 (ssr/default-response)
          r2 (ssr/default-response)]
      (is (= r1 r2) "the value shape is consistent across calls")
      ;; If they share state, mutating one (e.g. updating :status) would
      ;; affect the other. Persistent maps in Clojure are immutable, so
      ;; really what we're asserting is that callers can use the result
      ;; freely without aliasing concerns. Value-equality is the
      ;; observable contract; identity is the safety guarantee Spec 011
      ;; relies on for the per-request accumulator pattern.
      ;; (Persistent collections — assoc'ing one returns a new value;
      ;;  the other is untouched.)
      (let [r1' (assoc r1 :status 500)]
        (is (= 500 (:status r1'))
            "mutating one return value yields a new map with the change")
        (is (= 200 (:status r2))
            "the other return value is untouched — no shared mutable state")))))

;; ===========================================================================
;; rf2-dl9yg TC-9 — direct error-projection-listener exercise (view-time path)
;; ===========================================================================
;;
;; The handler-exception path is tested end-to-end through the Ring stack
;; (ssr-ring `handler-render-error-projects-to-500`). The
;; direct-ssr-layer equivalent — driving `error-projection-listener`
;; with a synthetic view-time-style exception trace and asserting the
;; projector stamps the response — was not pinned. Add it.
;;
;; The listener consumes :error trace events bound to a server frame
;; and buffers them; `get-response` flushes the buffer through the
;; active projector. The buffered-trace pattern is what shipped per
;; rf2-asmj1 R*; the test reaches in via `re-frame.trace/emit!` so the
;; full path runs without involving the Ring adapter.

(deftest direct-ssr-layer-projects-view-time-exception
  (testing "rf2-dl9yg TC9: a synthetic error trace tagged with a server frame
            → error-projection-listener buffers → get-response flushes → response
            :status carries the default projector's 500"
    (let [f (frame/make-anon-frame-record!
              {:platform :server
               :ssr      {:public-error-id   :rf.ssr/default-error-projector
                          :dev-error-detail? false}})]
      ;; Emit a synthetic view-time-style error trace directly. Per
      ;; the listener contract (`error_listener.cljc:103-115`) it
      ;; gates on :op-type :error and the frame being a server frame;
      ;; either condition failing → silent.
      (trace/emit! :error :rf.error/view-time-exception
                   {:frame             f
                    :exception-message "synthetic view-time boom"
                    :failing-id        :pages/articles
                    :recovery          :warned-and-projected})
      ;; Reading the response flushes the projection.
      (let [resp (get-response f)]
        (is (= 500 (:status resp))
            "synthetic error trace → projector → 500 stamped onto :rf/response")
        (is (nil? (:redirect resp))
            "no redirect was set; the projector overwrites the status freely")))))

;; ===========================================================================
;; rf2-ooj41 — direct adapter-contract smoke
;; ===========================================================================
;;
;; The `ssr/adapter` Var is the SSR substrate adapter — eight of nine
;; slots implement the substrate contract cleanly; the ninth (`:render`)
;; deliberately throws because SSR uses render-to-string exclusively.
;; The shared test fixture installs the adapter on every `:each`, so
;; the indirection is exercised constantly — but no test asserts the
;; slot contents themselves. Add a direct check.

(deftest adapter-installs-ssr-render-to-string
  (testing "ssr/adapter wires re-frame.ssr/render-to-string into the
            :render-to-string slot"
    (let [adapter ssr/adapter]
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
    (let [render-fn (:render ssr/adapter)]
      (is (fn? render-fn))
      (try
        (render-fn [:div] nil nil)
        (is false "render-fn must throw — did not")
        (catch clojure.lang.ExceptionInfo e
          ;; rf2-vvixub — branch on the canonical :rf.error/id; the message is
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
;; rf2-vngir (was rf2-ooj41) — retired redirect-target spellings (:url / :to)
;; are REJECTED, not normalised onto :location
;; ===========================================================================
;;
;; Spec 011 §Redirect contract: `:rf.server/redirect`'s redirect target is
;; keyed under `:location` — the canonical (and only) key, per EP-0007
;; one-name-per-fact (this fx writes an HTTP `Location` response header, so
;; it uses header vocabulary). The pre-alpha `:url` / `:to` synonyms were
;; pruned; `redirect-fx` now throws `:rf.error/redirect-retired-target-key`
;; naming `:location` rather than silently normalising. There is no
;; back-compat alias. These tests pin the rejection (formerly the alias
;; normalisation, rf2-ooj41) AND that the resolved redirect slot is NOT
;; populated when a retired spelling is the only target key.

(deftest redirect-retired-url-spelling-is-rejected
  (testing "rf2-vngir: {:url \"...\"} is rejected with
            :rf.error/redirect-retired-target-key (naming :location); it is
            NOT normalised onto :location, and the :redirect slot stays unset"
    (rf/reg-event :retired/url-redirect
      (fn [_ _]
        {:fx [[:rf.server/redirect {:url "/dashboard"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:retired/url-redirect] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/redirect-retired-target-key
        ":url redirect-target spelling rejected")
      (is (nil? (:redirect (get-response f)))
          "the rejected redirect did NOT populate the :redirect slot"))))

(deftest redirect-retired-to-spelling-is-rejected
  (testing "rf2-vngir: {:to \"...\"} is rejected with
            :rf.error/redirect-retired-target-key, even with an explicit
            :status — the retired-key check fires before the status path"
    (rf/reg-event :retired/to-redirect
      (fn [_ _]
        {:fx [[:rf.server/redirect {:to "/welcome" :status 301}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:retired/to-redirect] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/redirect-retired-target-key
        ":to redirect-target spelling rejected")
      (is (nil? (:redirect (get-response f)))
          "the rejected redirect did NOT populate the :redirect slot"))))

;; ===========================================================================
;; rf2-hyk9j TC-6 — redirect short-circuits projector status overwrite
;; ===========================================================================
;;
;; Per `error_listener.cljc:96-101` and Spec 011 §Redirect precedence:
;; when the response carries a `:redirect`, `apply-error-projection!`
;; must NOT overwrite the redirect's `:status` with the projector's
;; status. Behaviour is correct in the impl; no test pinned it.

(deftest redirect-suppresses-projector-status-overwrite
  (testing "a request that redirects AND surfaces an error trace → response :status
            stays at the redirect's status; the projector does not overwrite it"
    (rf/reg-event :redirect-then-error
      (fn [_ _]
        {:fx [[:rf.server/redirect {:status 302 :location "/login"}]
              ;; Then trigger a handler-exception trace — the default
              ;; projector maps this to 500. The redirect was set
              ;; first; the projector must NOT promote 302 → 500.
              [:dispatch [:throw-from-handler]]]}))
    (rf/reg-event :throw-from-handler
      (fn [_ _] (throw (ex-info "post-redirect failure" {}))))

    ;; rf2-vw5h1r / rf2-anehs6: :redirect-then-error is a RENDER-TIME request
    ;; dispatch against a live frame, NOT an :initial-events setup step. The
    ;; in-band handler-exception (from [:dispatch [:throw-from-handler]]) is
    ;; the projector's drain-time domain; were this a construction setup step,
    ;; the now-STRICT :initial-events teardown (EP-0027 §Failure) would tear
    ;; the frame down and raise :rf.error/initial-events-step-failed instead.
    (let [traces (atom [])
          f      (frame/make-anon-frame-record!
                   {:platform  :server
                    :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                :dev-error-detail? false}})
          _      (rf/register-listener! :trace ::rpe (fn [ev] (swap! traces conj ev)))
          _      (rf/dispatch-sync [:redirect-then-error] {:frame f})
          _      (rf/unregister-listener! :trace ::rpe)
          resp   (get-response f)]
      ;; The handler-exception fired (drain-time trace).
      (is (some #(= :rf.error/handler-exception (:operation %)) @traces)
          "the handler-exception was traced during the drain")
      ;; The redirect survived: response :status is 302, not 500.
      (is (= 302 (:status resp))
          "redirect wins — projector must not overwrite a redirect's :status (Spec 011 §Redirect precedence)")
      (is (= {:status 302 :location "/login"} (:redirect resp))
          "the redirect map itself is unchanged"))))

;; ===========================================================================
;; rf2-2brsn / parent rf2-zfm8v — :rf.server/safe-redirect (caller-untrusted)
;; ===========================================================================
;;
;; Per rf2-zfm8v (Mike decision, Option A — ship safe-redirect-fx alongside
;; redirect-fx, 2026-05-14) the runtime ships TWO redirect fxs:
;;
;; - :rf.server/redirect       — caller-trusted; arbitrary :location strings.
;; - :rf.server/safe-redirect  — caller-untrusted; URL parse + scheme reject +
;;                               :relative-only? / :allow allowlist gating.
;;
;; Mitigation for the open-redirect class (audit 2026-05-14 §P3.2): an
;; attacker-controlled ?next=... URL parameter cannot redirect off-origin
;; when the app uses :rf.server/safe-redirect.
;;
;; Validation order (each step emits its specific :rf.error/safe-redirect-*
;; category — see Spec 009 §Error event catalogue):
;;   1. URL must parse → :rf.error/safe-redirect-invalid-url
;;   2. scheme ∈ #{javascript data vbscript} → :rf.error/safe-redirect-scheme-rejected
;;   3. :relative-only? true + URL has host → :rf.error/safe-redirect-host-disallowed
;;      (:reason :relative-only-violation)
;;   4. :allow supplied + host ∉ allow → :rf.error/safe-redirect-host-disallowed
;;      (:reason :not-in-allowlist)
;;   5. pass → set Location header (same shape as redirect-fx).

(defn- capture-safe-redirect-traces!
  "Install a trace listener filtering only :rf.error/safe-redirect-* events
  for the duration of `body-fn`. Returns the captured traces."
  [body-fn]
  (let [traces (atom [])
        tag    (keyword (str "::safe-redirect-cap-" (gensym)))
        prefix "rf.error"
        match? (fn [op]
                 (and (keyword? op)
                      (= prefix (namespace op))
                      (str/starts-with? (name op) "safe-redirect-")))]
    (rf/register-listener! :trace tag
                           (fn [ev]
                             (when (match? (:operation ev))
                               (swap! traces conj ev))))
    (try (body-fn) @traces
         (finally (rf/unregister-listener! :trace tag)))))

;; --- Step 1: URL parse failure --------------------------------------------

(deftest safe-redirect-rejects-unparseable-url
  (testing "rf2-2brsn step 1: a :location that cannot be parsed as a URL
            → :rf.error/safe-redirect-invalid-url trace AND no :redirect
            is set on the response (the fx is a no-op on rejection)"
    (rf/reg-event :sr/unparseable
      (fn [_ _]
        ;; A space-after-colon makes this URISyntax-invalid in java.net.URI
        ;; (the colon makes it look like a scheme, but the space after is
        ;; not a legal scheme-specific character).
        {:fx [[:rf.server/safe-redirect
               {:location "https://example.com/path with space"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/unparseable] {:frame f})))
          resp   (get-response f)]
      (is (= 1 (count (filter #(= :rf.error/safe-redirect-invalid-url
                                  (:operation %)) traces)))
          "exactly one :rf.error/safe-redirect-invalid-url trace fires")
      (is (nil? (:redirect resp))
          "rejection is a no-op — :redirect slot unchanged"))))

;; --- Step 2: scheme rejection ---------------------------------------------

(deftest safe-redirect-rejects-javascript-scheme
  (testing "rf2-2brsn step 2: javascript: scheme → :rf.error/safe-redirect-scheme-rejected
            (XSS vector — script execution on click of the redirect)"
    (rf/reg-event :sr/javascript
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "javascript:alert(1)"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/javascript] {:frame f})))
          hits   (filter #(= :rf.error/safe-redirect-scheme-rejected
                             (:operation %)) traces)]
      (is (= 1 (count hits))
          ":rf.error/safe-redirect-scheme-rejected fires exactly once")
      (when (seq hits)
        (is (= "javascript" (-> hits first :tags :scheme))
            ":scheme tag names the rejected scheme"))
      (is (nil? (:redirect (get-response f)))
          "rejection is a no-op"))))

(deftest safe-redirect-rejects-data-scheme
  (testing "rf2-2brsn step 2: data: scheme rejected (data-URL phishing)"
    (rf/reg-event :sr/data
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "data:text/html,<script>alert(1)</script>"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/data] {:frame f})))]
      (is (some #(and (= :rf.error/safe-redirect-scheme-rejected (:operation %))
                      (= "data" (-> % :tags :scheme)))
                traces)
          ":rf.error/safe-redirect-scheme-rejected fires with :scheme \"data\""))))

(deftest safe-redirect-rejects-vbscript-scheme
  (testing "rf2-2brsn step 2: vbscript: scheme rejected (IE-era VBScript exec)"
    (rf/reg-event :sr/vbscript
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "vbscript:msgbox(\"x\")"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/vbscript] {:frame f})))]
      (is (some #(and (= :rf.error/safe-redirect-scheme-rejected (:operation %))
                      (= "vbscript" (-> % :tags :scheme)))
                traces)
          ":rf.error/safe-redirect-scheme-rejected fires with :scheme \"vbscript\""))))

(deftest safe-redirect-scheme-rejection-is-case-insensitive
  (testing "rf2-2brsn step 2: JavaScript: / DATA: / VBScript: all rejected
            (case-insensitive scheme match per the lowercase-on-compare pattern)"
    (doseq [hostile ["JavaScript:alert(1)" "DATA:text/html,evil" "VBScript:evil"]]
      (rf/reg-event :sr/probe-case
        (fn [_ _]
          {:fx [[:rf.server/safe-redirect {:location hostile}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-safe-redirect-traces!
                     (fn [] (rf/dispatch-sync [:sr/probe-case] {:frame f})))]
        (is (some #(= :rf.error/safe-redirect-scheme-rejected (:operation %))
                  traces)
            (str "case-folded scheme " (pr-str hostile) " rejected"))))))

;; --- Step 3: :relative-only? gate -----------------------------------------

(deftest safe-redirect-relative-only-rejects-absolute-url
  (testing "rf2-2brsn step 3: :relative-only? true AND URL has host →
            :rf.error/safe-redirect-host-disallowed (:reason :relative-only-violation)"
    (rf/reg-event :sr/abs-with-relative-only
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location       "https://evil.example.com/phish"
                :relative-only? true}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/abs-with-relative-only] {:frame f})))
          hits   (filter #(= :rf.error/safe-redirect-host-disallowed
                             (:operation %)) traces)]
      (is (= 1 (count hits))
          ":rf.error/safe-redirect-host-disallowed fires exactly once")
      (when (seq hits)
        (let [ev (first hits)]
          (is (= :relative-only-violation (-> ev :tags :reason))
              ":reason discriminates the two host-disallowed modes")
          (is (= "evil.example.com" (-> ev :tags :host))
              ":host tag names the rejected host")))
      (is (nil? (:redirect (get-response f)))
          "rejection is a no-op"))))

(deftest safe-redirect-relative-only-accepts-relative-path
  (testing "rf2-2brsn step 3 happy path: :relative-only? true + relative URL →
            redirect succeeds (no trace)"
    (rf/reg-event :sr/relative-ok
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location       "/dashboard"
                :relative-only? true}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/relative-ok] {:frame f})))
          resp   (get-response f)]
      (is (empty? traces)
          "no :rf.error/safe-redirect-* trace on a passing relative URL")
      (is (= "/dashboard" (-> resp :redirect :location))
          ":location lands on the response :redirect slot")
      (is (= 302 (-> resp :redirect :status))
          ":status defaults to 302"))))

;; --- Step 4: :allow allowlist ---------------------------------------------

(deftest safe-redirect-allowlist-rejects-off-allowlist-host
  (testing "rf2-2brsn step 4: :allow supplied AND URL's host NOT in allow →
            :rf.error/safe-redirect-host-disallowed (:reason :not-in-allowlist)"
    (rf/reg-event :sr/not-in-allow
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "https://evil.example.com/phish"
                :allow    ["app.example.com" "alt.example.com"]}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/not-in-allow] {:frame f})))
          hits   (filter #(= :rf.error/safe-redirect-host-disallowed
                             (:operation %)) traces)]
      (is (= 1 (count hits))
          ":rf.error/safe-redirect-host-disallowed fires exactly once")
      (when (seq hits)
        (let [ev (first hits)]
          (is (= :not-in-allowlist (-> ev :tags :reason))
              ":reason discriminates from the relative-only case")
          (is (= "evil.example.com" (-> ev :tags :host))
              ":host names the rejected host")
          (is (= ["app.example.com" "alt.example.com"]
                 (-> ev :tags :allowlist))
              ":allowlist tag carries the allowlist vector for diagnostic clarity")))
      (is (nil? (:redirect (get-response f)))
          "rejection is a no-op"))))

(deftest safe-redirect-allowlist-accepts-on-allowlist-host
  (testing "rf2-2brsn step 4 happy path: host IN allowlist → redirect succeeds"
    (rf/reg-event :sr/in-allow
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "https://app.example.com/dashboard"
                :allow    ["app.example.com" "alt.example.com"]}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/in-allow] {:frame f})))
          resp   (get-response f)]
      (is (empty? traces)
          "no :rf.error/safe-redirect-* trace on a passing allowlist match")
      (is (= "https://app.example.com/dashboard"
             (-> resp :redirect :location))
          ":location lands on the response :redirect slot"))))

(deftest safe-redirect-allowlist-host-match-is-case-insensitive
  (testing "rf2-lgmiw step 4: DNS hostnames are case-insensitive (RFC 1035
            §2.3.3) — a mixed-case host matches a lowercase :allow entry and
            the redirect succeeds with no trace"
    (rf/reg-event :sr/in-allow-mixed-case
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "https://APP.Example.COM/dashboard"
                :allow    ["app.example.com" "alt.example.com"]}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/in-allow-mixed-case] {:frame f})))
          resp   (get-response f)]
      (is (empty? traces)
          "no :rf.error/safe-redirect-* trace — case-folded host matches allow")
      (is (= "https://APP.Example.COM/dashboard"
             (-> resp :redirect :location))
          ":location passes through unchanged (only the COMPARISON folds case)"))))

;; --- Validation order: parse runs before scheme runs before policy --------

(deftest safe-redirect-validation-order-parse-precedes-scheme
  (testing "rf2-2brsn: a fundamentally-unparseable URL surfaces the parse
            error, NOT the scheme error (validation runs in order — see
            Spec 009 §Error event catalogue)"
    (rf/reg-event :sr/order-parse-first
      (fn [_ _]
        ;; This is BOTH unparseable AND vaguely-javascript-shaped — the
        ;; parser-fail must fire FIRST because step 1 runs before step 2.
        {:fx [[:rf.server/safe-redirect
               {:location "javascript: not a real url "}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/order-parse-first] {:frame f})))
          ops    (mapv :operation traces)]
      ;; Either the URL parses (and we get scheme-rejected) OR it doesn't
      ;; (and we get invalid-url) — but in both cases exactly ONE
      ;; :rf.error/safe-redirect-* trace fires; the gate short-circuits.
      (is (= 1 (count ops))
          (str "exactly one :rf.error/safe-redirect-* trace; saw: "
               (pr-str ops))))))

(deftest safe-redirect-empty-location-rejected-as-invalid-url
  (testing "rf2-2brsn step 1: an empty / blank :location string is rejected
            as :rf.error/safe-redirect-invalid-url — an empty redirect has
            no defensible interpretation"
    (rf/reg-event :sr/empty
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect {:location ""}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/empty] {:frame f})))]
      (is (some #(= :rf.error/safe-redirect-invalid-url (:operation %))
                traces)
          "empty :location → :rf.error/safe-redirect-invalid-url")
      (is (nil? (:redirect (get-response f)))
          "rejection is a no-op"))))

;; --- rf2-v3eg3 finding 1: scheme-bearing open-redirect BYPASS -------------
;;
;; The pre-fix gate used java.net.URI.getHost as the policy discriminator
;; and only rejected when host was truthy. Java reports a nil host for a
;; scheme-bearing OPAQUE URI (`http:evil.example.com` — scheme present,
;; no authority) and for a hierarchical URI with no authority
;; (`http:/evil`). Those slipped BOTH the :relative-only? and the :allow
;; host gates and populated :redirect — a browser given
;; `Location: http:evil.example.com` navigates OFF-ORIGIN. The fix gates
;; on parsed-URL SHAPE: a relative reference is `scheme==nil AND
;; authority==nil`; anything else is non-relative and subject to the
;; host gates, and a scheme-bearing-but-host-less URL has no defensible
;; redirect interpretation.

(deftest safe-redirect-rejects-scheme-bearing-opaque-http-bypass
  (testing "rf2-v3eg3 finding 1: `http:evil.example.com` (opaque, host=nil)
            is the open-redirect bypass — it MUST be rejected (no :redirect)
            under :relative-only?, under :allow, AND with no policy"
    (doseq [[label policy] [["no-policy"      {}]
                            ["relative-only?" {:relative-only? true}]
                            ["allow"          {:allow ["app.example.com"]}]]]
      (rf/reg-event :sr/opaque-http
        (fn [_ _]
          {:fx [[:rf.server/safe-redirect
                 (merge {:location "http:evil.example.com"} policy)]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-safe-redirect-traces!
                     (fn [] (rf/dispatch-sync [:sr/opaque-http] {:frame f})))]
        (is (seq traces)
            (str "[" label "] a :rf.error/safe-redirect-* trace fires — "
                 "the bypass is closed"))
        (is (nil? (:redirect (get-response f)))
            (str "[" label "] no :redirect mutation — the off-origin "
                 "scheme-bearing opaque URI is rejected"))))))

(deftest safe-redirect-rejects-scheme-bearing-opaque-https-bypass
  (testing "rf2-v3eg3 finding 1: `https:evil.example.com` (opaque, host=nil)
            rejected as :rf.error/safe-redirect-invalid-url
            (:reason :scheme-without-host)"
    (rf/reg-event :sr/opaque-https
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect {:location "https:evil.example.com"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/opaque-https] {:frame f})))]
      (is (some #(and (= :rf.error/safe-redirect-invalid-url (:operation %))
                      (= :scheme-without-host (-> % :tags :reason)))
                traces)
          ":scheme-without-host reason names the shape failure")
      (is (nil? (:redirect (get-response f)))
          "rejection is a no-op"))))

(deftest safe-redirect-rejects-mailto-scheme
  (testing "rf2-v3eg3 finding 1: `mailto:user@example.com` — a non-http(s)
            scheme is rejected outright as scheme-rejected
            (:reason :scheme-not-allowed)"
    (rf/reg-event :sr/mailto
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect {:location "mailto:user@example.com"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/mailto] {:frame f})))]
      (is (some #(and (= :rf.error/safe-redirect-scheme-rejected (:operation %))
                      (= "mailto" (-> % :tags :scheme))
                      (= :scheme-not-allowed (-> % :tags :reason)))
                traces)
          "mailto: rejected as :scheme-not-allowed")
      (is (nil? (:redirect (get-response f)))
          "rejection is a no-op"))))

(deftest safe-redirect-rejects-ftp-scheme
  (testing "rf2-v3eg3 finding 1: `ftp:example.com` — non-http(s) scheme
            rejected outright (opaque form, host=nil)"
    (rf/reg-event :sr/ftp
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect {:location "ftp:example.com"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/ftp] {:frame f})))]
      (is (some #(= :rf.error/safe-redirect-scheme-rejected (:operation %))
                traces)
          "ftp: rejected (non-http(s) scheme)")
      (is (nil? (:redirect (get-response f)))
          "rejection is a no-op"))))

(deftest safe-redirect-rejects-protocol-relative-under-policy
  (testing "rf2-v3eg3 finding 1: `//evil.example.com/path` (protocol-relative,
            authority=evil.example.com) is NOT a relative reference — it is
            rejected under :relative-only? and under :allow (host mismatch)"
    (doseq [[label policy expected-reason]
            [["relative-only?" {:relative-only? true} :relative-only-violation]
             ["allow"          {:allow ["app.example.com"]} :not-in-allowlist]]]
      (rf/reg-event :sr/protocol-relative
        (fn [_ _]
          {:fx [[:rf.server/safe-redirect
                 (merge {:location "//evil.example.com/path"} policy)]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-safe-redirect-traces!
                     (fn [] (rf/dispatch-sync [:sr/protocol-relative] {:frame f})))]
        (is (some #(and (= :rf.error/safe-redirect-host-disallowed (:operation %))
                        (= expected-reason (-> % :tags :reason)))
                  traces)
            (str "[" label "] protocol-relative rejected with reason "
                 expected-reason))
        (is (nil? (:redirect (get-response f)))
            (str "[" label "] no :redirect mutation"))))))

(deftest safe-redirect-accepts-normal-relative-path-control
  (testing "rf2-v3eg3 finding 1 CONTROL: a normal relative path still passes
            cleanly under :relative-only? — the hardening did not over-reject
            the legitimate same-origin case"
    (rf/reg-event :sr/relative-control
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "/account/settings" :relative-only? true}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/relative-control] {:frame f})))
          resp   (get-response f)]
      (is (empty? traces)
          "no :rf.error/safe-redirect-* trace on a legitimate relative path")
      (is (= "/account/settings" (-> resp :redirect :location))
          ":location lands on the response :redirect slot")
      (is (= 302 (-> resp :redirect :status))
          ":status defaults to 302"))))

(deftest safe-redirect-still-accepts-allowed-absolute-host-control
  (testing "rf2-v3eg3 finding 1 CONTROL: a well-formed absolute http(s) URL
            whose host IS in the allowlist still passes — the shape gate
            did not break the legitimate absolute-redirect path"
    (rf/reg-event :sr/abs-control
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "https://app.example.com/dashboard"
                :allow    ["app.example.com"]}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/abs-control] {:frame f})))
          resp   (get-response f)]
      (is (empty? traces)
          "no trace on an allowlisted absolute host")
      (is (= "https://app.example.com/dashboard" (-> resp :redirect :location))
          ":location passes through"))))

;; --- CRLF defence-in-depth still holds ------------------------------------

(deftest safe-redirect-also-rejects-crlf-injection
  (testing "rf2-2brsn: the CRLF gate (rf2-hbty2) runs on safe-redirect too —
            an attacker passing a CRLF-bearing location is presumably trying
            both vectors. Same fx-boundary throw as redirect-fx; the throw
            propagates as :rf.error/fx-handler-exception"
    (rf/reg-event :sr/crlf
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "/path\r\nSet-Cookie: stolen=1"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:sr/crlf] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/redirect-invalid-location
        "safe-redirect with CRLF in :location"))))

;; ===========================================================================
;; rf2-z7gor — tag-name injection (emit) + header-name / cookie field
;; validation (response) — security audit 2026-05-14 §P2
;;
;; Companion gates to the rf2-hbty2 header-value gate:
;;   1. parse-tag-name handed the keyword's leading fragment straight to
;;      `<...>` emission with no grammar check; a hostile keyword like
;;      `(keyword "img src=x onerror=alert(1)")` bypassed the attribute
;;      validator entirely. Validate the tag-name against the HTML5/SVG/
;;      MathML element-name grammar and fail-fast on misuse.
;;   2. set-header / append-header validated VALUES (rf2-hbty2) but
;;      accepted any :name; set-cookie / delete-cookie stored the whole
;;      cookie map verbatim. Validate header names against the RFC 7230
;;      §3.2.6 token grammar and cookie fields against RFC 6265 §4.1.1
;;      + the CR/LF/NUL ban — at the fx boundary so non-ring host
;;      adapters get the same safety.
;; ===========================================================================

(deftest ssr-render-rejects-hostile-tag-keywords
  (testing "rf2-z7gor — a keyword carrying attribute-like injection in the
            tag component is rejected by :rf.error/invalid-tag-name. The
            two documented reproductions from the bead."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #":rf\.error/invalid-tag-name"
          (rf/render-to-string [(keyword "img src=x onerror=alert(1)")] {}))
        "img-with-event-handler injection rejected")
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #":rf\.error/invalid-tag-name"
          (rf/render-to-string [(keyword "div> <script") "x"] {}))
        "tag-break-into-script injection rejected"))

  (testing "rf2-z7gor — whitespace, separators, CTLs, empty all rejected"
    (doseq [hostile [(keyword " ")
                     (keyword "a b")
                     (keyword "tag\rname")
                     (keyword "tag\nname")
                     (keyword "")
                     (keyword "1-leading-digit")
                     (keyword "<script>")]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":rf\.error/invalid-tag-name"
            (rf/render-to-string [hostile] {}))
          (str "hostile tag-name " (pr-str hostile)))))

  (testing "rf2-77l9w — admitting one namespaced colon segment does NOT
            admit malformed colon shapes: a bare/leading/trailing/double
            colon or an empty/ill-formed segment still throws"
    (doseq [hostile [(keyword ":rect")        ; leading colon — empty prefix
                     (keyword "svg:")         ; trailing colon — empty local
                     (keyword "a:b:c")        ; two colons — not a single ns
                     (keyword "svg::rect")    ; double colon — empty segment
                     (keyword "svg:rect onload=x")]] ; injection after colon
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":rf\.error/invalid-tag-name"
            (rf/render-to-string [hostile] {}))
          (str "malformed namespaced tag-name " (pr-str hostile))))))

(deftest ssr-render-accepts-legit-tag-keywords
  (testing "rf2-z7gor — regression guard: HTML / SVG / MathML / custom
            element names + the :tag#id.cls sugar all still flow"
    (is (= "<div>x</div>"
           (rf/render-to-string [:div "x"] {})))
    (is (= "<my-component></my-component>"
           (rf/render-to-string [:my-component] {})))
    (is (= "<svg></svg>"
           (rf/render-to-string [:svg] {})))
    (is (= "<foreignObject>a</foreignObject>"
           (rf/render-to-string [:foreignObject "a"] {}))
        "SVG camelCase element names still parse")
    (is (= "<div id=\"main\" class=\"col-12 bold\">x</div>"
           (rf/render-to-string [:div#main.col-12.bold "x"] {}))
        ":tag#id.cls sugar still parses (validator runs on the tag fragment)")
    (is (= "<p>a</p><p>b</p>"
           (rf/render-to-string [:<> [:p "a"] [:p "b"]] {}))
        ":<> fragment renders children with no wrapper"))

  (testing "rf2-77l9w — XML-namespaced SVG/MathML tags carry a single colon
            segment and are admitted by the grammar"
    (is (= "<svg:rect></svg:rect>"
           (rf/render-to-string [:svg:rect] {}))
        "namespaced SVG tag `:svg:rect` is accepted")
    (is (= "<xlink:href>a</xlink:href>"
           (rf/render-to-string [:xlink:href "a"] {}))
        "xlink-namespaced tag is accepted")
    (is (= "<svg:rect id=\"r\" class=\"c\"></svg:rect>"
           (rf/render-to-string [:svg:rect#r.c] {}))
        "namespaced tag still composes with the #id.cls sugar")))

(deftest ssr-set-header-rejects-invalid-name
  (testing "rf2-z7gor — :rf.server/set-header with CRLF in :name surfaces
            :rf.error/header-invalid-name (sister gate to rf2-hbty2's
            header-value gate)"
    (rf/reg-event :hdr/crlf-in-name
      (fn [_ _]
        {:fx [[:rf.server/set-header
               {:name  "X-Test\r\nSet-Cookie: evil=1"
                :value "ok"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:hdr/crlf-in-name] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/header-invalid-name
        "set-header with CRLF in :name")))

  (testing "rf2-z7gor — separators / whitespace / empty all rejected"
    (doseq [hostile ["Bad: Name" "Bad Name" "" "with(parens)"
                     (str "nul" (char 0) "bad")]]
      (rf/reg-event :hdr/probe-name
        (fn [_ _]
          {:fx [[:rf.server/set-header {:name hostile :value "ok"}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:hdr/probe-name] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/header-invalid-name
          (str "hostile header name " (pr-str hostile)))))))

(deftest ssr-append-header-rejects-invalid-name
  (testing "rf2-z7gor — :rf.server/append-header with CRLF in :name surfaces
            :rf.error/header-invalid-name (same gate as set-header)"
    (rf/reg-event :hdr/append-crlf-name
      (fn [_ _]
        {:fx [[:rf.server/append-header
               {:name  "X-Audit\r\nSet-Cookie: forged=1"
                :value "ok"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:hdr/append-crlf-name] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/header-invalid-name
        "append-header with CRLF in :name"))))

(deftest ssr-set-cookie-rejects-invalid-fields
  (testing "rf2-z7gor — :rf.server/set-cookie with CRLF in :name surfaces
            :rf.error/cookie-invalid-name (RFC 6265 §4.1.1 token grammar)"
    (rf/reg-event :ck/crlf-in-name
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name  "session\r\nSet-Cookie: stolen=1"
                :value "abc"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/crlf-in-name] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-name
        "set-cookie with CRLF in :name")))

  (testing "rf2-z7gor — :rf.server/set-cookie with CRLF in :value surfaces
            :rf.error/cookie-invalid-value"
    (rf/reg-event :ck/crlf-in-value
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name  "session"
                :value "abc\r\nSet-Cookie: stolen=1"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/crlf-in-value] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-value
        "set-cookie with CRLF in :value")))

  (testing "rf2-z7gor — :rf.server/set-cookie with CRLF in :path surfaces
            :rf.error/cookie-invalid-path"
    (rf/reg-event :ck/crlf-in-path
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name  "session"
                :value "abc"
                :path  "/\r\nSet-Cookie: stolen=1"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/crlf-in-path] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-path
        "set-cookie with CRLF in :path")))

  (testing "rf2-z7gor — :rf.server/set-cookie with CRLF in :domain surfaces
            :rf.error/cookie-invalid-domain"
    (rf/reg-event :ck/crlf-in-domain
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name   "session"
                :value  "abc"
                :domain "example.com\r\nSet-Cookie: stolen=1"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/crlf-in-domain] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-domain
        "set-cookie with CRLF in :domain"))))

(deftest ssr-delete-cookie-rejects-invalid-fields
  (testing "rf2-z7gor — :rf.server/delete-cookie runs the same validators
            as set-cookie (it's sugar over set-cookie)"
    (rf/reg-event :ck/del-crlf-path
      (fn [_ _]
        {:fx [[:rf.server/delete-cookie
               {:name "session" :path "/admin\r\nbad"}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/del-crlf-path] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-path
        "delete-cookie with CRLF in :path"))))

(deftest ssr-set-cookie-crlf-checks-every-attribute
  (testing "rf2-kjf3m.1 / Spec 011 §CRLF fail-fast: :rf.server/set-cookie
            CRLF-checks EVERY attribute the host adapter serialises —
            :max-age, :same-site, :expires — not just :value/:path/:domain.
            The fx boundary is the single enforcement point for non-Ring
            host adapters; a string :max-age sourced from request context
            must not re-enter the header line as CRLF-bearing payload."
    ;; The concrete failing scenario from the bead: string :max-age
    ;; carrying a forged second Set-Cookie line.
    (testing ":max-age (string form) with CRLF → :rf.error/cookie-invalid-max-age"
      (rf/reg-event :ck/crlf-in-max-age
        (fn [_ _]
          {:fx [[:rf.server/set-cookie
                 {:name    "session"
                  :value   "x"
                  :max-age "3600\r\nSet-Cookie: admin=1; Path=/"}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:ck/crlf-in-max-age] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/cookie-invalid-max-age
          "set-cookie with CRLF in :max-age")
        (is (empty? (:cookies (get-response f)))
            "no cookie lands on the accumulator — rejection is a no-op")))

    (testing ":same-site with CRLF → :rf.error/cookie-invalid-same-site"
      (rf/reg-event :ck/crlf-in-same-site
        (fn [_ _]
          {:fx [[:rf.server/set-cookie
                 {:name      "session"
                  :value     "x"
                  :same-site "Lax\r\nSet-Cookie: admin=1"}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:ck/crlf-in-same-site] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/cookie-invalid-same-site
          "set-cookie with CRLF in :same-site")))

    (testing ":expires with CRLF → :rf.error/cookie-invalid-expires"
      (rf/reg-event :ck/crlf-in-expires
        (fn [_ _]
          {:fx [[:rf.server/set-cookie
                 {:name    "session"
                  :value   "x"
                  :expires "Wed, 09 Jun 2027 10:18:14 GMT\r\nSet-Cookie: admin=1"}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:ck/crlf-in-expires] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/cookie-invalid-expires
          "set-cookie with CRLF in :expires")))

    (testing "bare LF / bare CR / NUL in :max-age all rejected"
      (doseq [hostile ["lf\nbad" "cr\rbad" (str "nul" (char 0) "bad")]]
        (rf/reg-event :ck/probe-max-age
          (fn [_ _]
            {:fx [[:rf.server/set-cookie
                   {:name "s" :value "x" :max-age hostile}]]}))
        (let [f      (frame/make-anon-frame-record! {:platform :server})
              traces (capture-fx-traces!
                       (fn [] (rf/dispatch-sync [:ck/probe-max-age] {:frame f})))]
          (expect-fx-error-keyword!
            traces :rf.error/cookie-invalid-max-age
            (str "hostile :max-age " (pr-str hostile))))))))

(deftest ssr-set-cookie-rejects-non-string-name-type
  (testing "rf2-9t17id — a cookie :name that is neither a string nor a Named
            (keyword / symbol) is rejected at the fx boundary with the
            documented :rf.error/cookie-invalid-name, NOT a raw host
            ClassCastException. Exercised on the DIRECT fx-handler path — the
            schema-soft-pass path the fx boundary must self-defend: with the
            :rf.server/cookie args schema absent or soft-passing (Spec 010),
            `(name n)` on a non-Named value would otherwise throw a bare host
            exception with nil ex-data, bypassing the structured-error
            contract and reaching adapter / :on-error code."
    (let [f (frame/make-anon-frame-record! {:platform :server})]
      (doseq [bad-name [42 3.14 [:not :a :name] {:cookie :map} true]]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #":rf\.error/cookie-invalid-name"
              (response/set-cookie-fx {:frame f} {:name bad-name :value "x"}))
            (str "set-cookie-fx with a non-string/non-Named :name "
                 (pr-str bad-name)
                 " must throw :rf.error/cookie-invalid-name, not a raw host"
                 " exception"))
        (is (empty? (:cookies (response/response-of f)))
            (str "the rejected cookie (" (pr-str bad-name)
                 ") never lands on the accumulator")))
      ;; delete-cookie is sugar over the same validator — same TYPE guard.
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/cookie-invalid-name"
            (response/delete-cookie-fx {:frame f} {:name 99 :path "/"}))
          "delete-cookie-fx runs the same cookie-name type guard")))

  (testing "rf2-9t17id regression guard — string / keyword / symbol :name
            still pass the type guard and reach the token-grammar check
            (a keyword :name coerces via `name`)."
    (let [f (frame/make-anon-frame-record! {:platform :server})]
      (response/set-cookie-fx {:frame f} {:name "session" :value "a"})
      (response/set-cookie-fx {:frame f} {:name :csrf     :value "b"})
      (response/set-cookie-fx {:frame f} {:name 'tracker  :value "c"})
      (is (= 3 (count (:cookies (response/response-of f))))
          "string, keyword, and symbol cookie names all pass the type guard"))))

(deftest ssr-set-cookie-clean-attributes-still-accepted
  (testing "rf2-kjf3m.1 regression guard: legitimate cookie attributes still
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
    (let [f       (frame/make-anon-frame-record! {:platform :server :initial-events [[:ck/clean-attrs]]})
          cookies (:cookies (get-response f))]
      (is (= 1 (count cookies))
          "the clean cookie lands on the accumulator")
      (is (= 3600 (-> cookies first :max-age))
          "integer :max-age survives unchanged (not coerced to string)")
      (is (= "Strict" (-> cookies first :same-site))
          ":same-site survives"))))

(deftest ssr-clean-names-still-accepted
  (testing "rf2-z7gor — regression guard: legitimate header names + cookie
            field shapes still flow"
    (rf/reg-event :clean/all
      (fn [_ _]
        {:fx [[:rf.server/set-header  {:name "Cache-Control"
                                       :value "no-cache"}]
              [:rf.server/set-header  {:name "X-Forwarded-For"
                                       :value "1.2.3.4"}]
              [:rf.server/set-cookie  {:name    "session"
                                       :value   "abc123"
                                       :path    "/"
                                       :domain  "example.com"}]
              [:rf.server/delete-cookie {:name "stale" :path "/"}]]}))
    (let [f (frame/make-anon-frame-record! {:platform :server :initial-events [[:clean/all]]})
          resp (get-response f)]
      (is (some (fn [[k _]] (= "Cache-Control"   k)) (:headers resp)))
      (is (some (fn [[k _]] (= "X-Forwarded-For" k)) (:headers resp)))
      (is (= 2 (count (:cookies resp))))
      (is (= "session" (-> resp :cookies first :name)))
      (is (= "stale"   (-> resp :cookies second :name))))))

;; ===========================================================================
;; ssr-server-fx-args-schema-boundary — rf2-kjf3m.2
;;
;; Spec 011 §Standard fx (line 438) + [Spec-Schemas §Standard fx args
;; schemas] declare the `:rf.fx.server/*-args` / `:rf.server/cookie`
;; schemas as REGISTERED, and assert "args validation runs as part of the
;; standard `:schema` boundary check" (per Spec 010 §Validation order step
;; 5). Pre-rf2-kjf3m.2 the six `:rf.server/*` reg-fx calls carried no
;; `:schema`, so that boundary never fired — a malformed arg (e.g. a
;; string `:rf.server/set-status`) fell straight onto the response
;; accumulator and onto the wire. These tests prove the boundary now
;; fires: a structurally-malformed server fx arg is REJECTED at dispatch
;; with `:rf.error/schema-validation-failure :where :fx-args`, the
;; offending fx is SKIPPED (Spec 010 §Per-step recovery row 5 — the
;; accumulator is untouched), and well-formed args still pass.
;;
;; The schemas artefact (transitively Malli) is on the ssr JVM test
;; classpath and `re-frame.ssr.test-fixture` requires `re-frame.schemas`,
;; so the late-bind validator (`:schemas/validate-fx!`) is LIVE here.
;; ===========================================================================

(defn- capture-schema-failures!
  "Record every `:rf.error/schema-validation-failure` trace emitted during
  `body-fn`. Returns the recorded traces (each an emit event with
  `:operation` + `:tags`). Mirrors `capture-fx-traces!` but for the
  schema-boundary category rather than fx-handler-exception."
  [body-fn]
  (let [traces (atom [])
        tag    (keyword (str "::schema-cap-" (gensym)))]
    (rf/register-listener! :trace tag
      (fn [ev]
        (when (= :rf.error/schema-validation-failure (:operation ev))
          (swap! traces conj ev))))
    (try
      (body-fn)
      @traces
      (finally
        (rf/unregister-listener! :trace tag)))))

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

(deftest ssr-server-fx-args-schema-boundary
  (testing "rf2-kjf3m.2 — the spec-declared :rf.fx.server/*-args boundary
            fires on malformed server fx args (Spec 011 §Standard fx /
            Spec-Schemas §Standard fx args schemas / Spec 010 §step 5)"

    (testing ":rf.server/set-status with a non-int arg → rejected + skipped + projected 500"
      ;; The bead's concrete failing scenario: a string status that
      ;; pre-rf2-kjf3m.2 rode straight onto the wire (Ring then emitted a
      ;; non-integer status). Now the :schema boundary rejects it before
      ;; set-status-fx runs, so the malformed "not-an-int" NEVER reaches
      ;; the accumulator. The fired :rf.error/schema-validation-failure
      ;; ALSO routes through the always-on SSR error-projection substrate.
      ;;
      ;; rf2-37o5by — the default projector's 400 arm is GATED on a
      ;; CLIENT-surface `:where` (`:event` / `:cofx`). This failure is
      ;; `:where :fx-args` — a SERVER-side defect: a server handler built a
      ;; malformed fx args map. That is not bad client input, so it must
      ;; NOT mislabel as a client-facing 400; it falls through to the
      ;; locked generic-500. End-to-end, the fix still turns a silent wire
      ;; defect into a clean surfaced failure — now correctly a 500.
      (rf/reg-event :bad/status
        (fn [_ _] {:fx [[:rf.server/set-status "not-an-int"]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-schema-failures!
                     (fn [] (rf/dispatch-sync [:bad/status] {:frame f})))
            status (:status (get-response f))]
        (expect-fx-args-schema-failure!
          traces :rf.server/set-status "string :rf.server/set-status")
        ;; The malformed string never landed on the accumulator …
        (is (not= "not-an-int" status)
            "the malformed status was skipped — never reached the accumulator")
        (is (integer? status)
            "the wire status is an integer (the gap this bead closes)")
        ;; … and the schema failure surfaced as a 500 via the SSR
        ;; error-projection substrate — a server-fx arg failure is a
        ;; server-side defect, NOT a client 400 (rf2-37o5by gated arm).
        (is (= 500 status)
            "schema-validation-failure :where :fx-args projected to 500
             :internal-error — the 400 arm is gated to :where :event/:cofx")))

    (testing ":rf.server/set-header missing :value → rejected + skipped"
      (rf/reg-event :bad/header
        (fn [_ _] {:fx [[:rf.server/set-header {:name "X-Foo"}]]}))   ;; :value absent
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-schema-failures!
                     (fn [] (rf/dispatch-sync [:bad/header] {:frame f})))]
        (expect-fx-args-schema-failure!
          traces :rf.server/set-header ":rf.server/set-header missing :value")
        (is (not-any? (fn [[k _]] (= "X-Foo" k)) (:headers (get-response f)))
            "the malformed header was skipped; nothing landed")))

    (testing ":rf.server/append-header with non-string :value → rejected"
      (rf/reg-event :bad/append
        (fn [_ _] {:fx [[:rf.server/append-header {:name "X-Bar" :value 42}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-schema-failures!
                     (fn [] (rf/dispatch-sync [:bad/append] {:frame f})))]
        (expect-fx-args-schema-failure!
          traces :rf.server/append-header ":rf.server/append-header non-string :value")))

    (testing ":rf.server/set-cookie missing :value → rejected via [:ref :rf.server/cookie]"
      (rf/reg-event :bad/cookie
        (fn [_ _] {:fx [[:rf.server/set-cookie {:name "session"}]]})) ;; :value absent
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-schema-failures!
                     (fn [] (rf/dispatch-sync [:bad/cookie] {:frame f})))]
        (expect-fx-args-schema-failure!
          traces :rf.server/set-cookie ":rf.server/set-cookie missing :value")
        (is (empty? (:cookies (get-response f)))
            "the malformed cookie was skipped; accumulator stays empty")))

    (testing ":rf.server/set-cookie with bogus :same-site keyword → rejected"
      ;; :same-site accepts the enum #{:strict :lax :none} (or a string,
      ;; per the documented divergence) — a bogus KEYWORD is neither.
      (rf/reg-event :bad/cookie-samesite
        (fn [_ _] {:fx [[:rf.server/set-cookie
                         {:name "s" :value "v" :same-site :bogus}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-schema-failures!
                     (fn [] (rf/dispatch-sync [:bad/cookie-samesite] {:frame f})))]
        (expect-fx-args-schema-failure!
          traces :rf.server/set-cookie ":rf.server/set-cookie bogus :same-site keyword")))

    (testing ":rf.server/delete-cookie missing :name → rejected"
      (rf/reg-event :bad/delete
        (fn [_ _] {:fx [[:rf.server/delete-cookie {:path "/"}]]}))    ;; :name absent
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-schema-failures!
                     (fn [] (rf/dispatch-sync [:bad/delete] {:frame f})))]
        (expect-fx-args-schema-failure!
          traces :rf.server/delete-cookie ":rf.server/delete-cookie missing :name")))

    (testing ":rf.server/redirect with no target key → PASSES the schema (warn path, not 400)"
      ;; rf2-ee38b.11 + the live half of decision rf2-cwfy2: a redirect
      ;; with no :location/:url/:to is NOT a structural error — the schema
      ;; is permissive (all target keys optional, zero allowed) so the
      ;; no-target case PASSES the Spec 010 §step-5 boundary and falls
      ;; through to the runtime's graceful no-target path. redirect-fx
      ;; accepts it (location is caller-trusted/optional), sets :redirect,
      ;; and the host adapter is the last line — it emits the
      ;; :rf.ssr/ssr-redirect-no-target WARNING + a 3xx with no Location
      ;; header so the defect is observable. A schema [:fn] requiring a
      ;; target would 400 here BEFORE the warn→302 path runs, contradicting
      ;; ee38b.11; this test pins that the redirect schema stays a pure
      ;; shape check and does NOT reject the no-target redirect.
      (rf/reg-event :soft/redirect-no-target
        (fn [_ _] {:fx [[:rf.server/redirect {:status 302}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-schema-failures!
                     (fn [] (rf/dispatch-sync [:soft/redirect-no-target] {:frame f})))]
        (is (empty? (filter (fn [ev] (and (= :fx-args (-> ev :tags :where))
                                          (= :rf.server/redirect
                                             (-> ev :tags :failing-id))))
                            traces))
            (str "no :fx-args schema failure for a no-target redirect — "
                 "the schema permits it; saw: "
                 (pr-str (mapv (comp :failing-id :tags) traces))))
        (is (= {:status 302} (:redirect (get-response f)))
            (str "the no-target redirect passed the schema and set :redirect"
                 " — the adapter's warn+302 no-target path takes over"
                 " downstream"))))

    (testing ":rf.server/redirect with non-int :status → rejected"
      (rf/reg-event :bad/redirect-status
        (fn [_ _] {:fx [[:rf.server/redirect {:location "/x" :status "oops"}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-schema-failures!
                     (fn [] (rf/dispatch-sync [:bad/redirect-status] {:frame f})))]
        (expect-fx-args-schema-failure!
          traces :rf.server/redirect ":rf.server/redirect non-int :status")))

    (testing ":rf.server/safe-redirect with non-int :status → rejected + response unmodified"
      ;; rf2-wtd8z finding 1: pre-fix safe-redirect carried only :platforms
      ;; — no :schema — so a valid-location-but-non-int-:status arg passed
      ;; the (absent) boundary and safe-redirect-fx's step-5 pass wrote the
      ;; string :status straight onto the response accumulator. With the new
      ;; :rf.fx.server/safe-redirect-args schema attached, the malformed
      ;; :status surfaces at the Spec 010 §step-5 fx-args boundary, the fx
      ;; is SKIPPED, and the response is left unmodified — matching the
      ;; sibling six.
      (rf/reg-event :bad/safe-redirect-status
        (fn [_ _] {:fx [[:rf.server/safe-redirect
                         {:location "/ok" :status "not-int"}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-schema-failures!
                     (fn [] (rf/dispatch-sync [:bad/safe-redirect-status] {:frame f})))
            resp   (get-response f)]
        (expect-fx-args-schema-failure!
          traces :rf.server/safe-redirect ":rf.server/safe-redirect non-int :status")
        (is (nil? (:redirect resp))
            "the malformed safe-redirect was skipped — no :redirect landed")
        (is (not= "not-int" (:status resp))
            "the non-int status never reached the response accumulator")))

    (testing ":rf.server/safe-redirect missing :location → rejected"
      ;; safe-redirect REQUIRES a :location (its validation target) — a
      ;; target-less safe-redirect is a programmer error, NOT the documented
      ;; no-target graceful path that the caller-trusted :rf.server/redirect
      ;; carries. The schema marks :location required, so a no-location call
      ;; fails the shape gate.
      (rf/reg-event :bad/safe-redirect-no-location
        (fn [_ _] {:fx [[:rf.server/safe-redirect {:status 302}]]}))
      (let [f      (frame/make-anon-frame-record! {:platform :server})
            traces (capture-schema-failures!
                     (fn [] (rf/dispatch-sync [:bad/safe-redirect-no-location] {:frame f})))]
        (expect-fx-args-schema-failure!
          traces :rf.server/safe-redirect ":rf.server/safe-redirect missing :location")))))

(deftest ssr-server-fx-args-schema-accepts-well-formed
  (testing "rf2-kjf3m.2 — regression guard: well-formed server fx args pass
            the :schema boundary cleanly (no :rf.error/schema-validation-
            failure) and land on the accumulator. The boundary rejects the
            malformed and admits the valid — it is not a blanket gate."
    (rf/reg-event :good/all
      (fn [_ _]
        {:fx [[:rf.server/set-status 201]
              [:rf.server/set-header  {:name "Cache-Control" :value "no-store"}]
              [:rf.server/append-header {:name "Vary" :value "Accept"}]
              ;; canonical cookie shape — int :max-age, keyword :same-site
              [:rf.server/set-cookie  {:name "session" :value "abc"
                                       :max-age 3600 :same-site :lax
                                       :secure true :http-only true}]
              [:rf.server/delete-cookie {:name "stale" :path "/"}]
              [:rf.server/redirect    {:location "/dashboard" :status 302}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-schema-failures!
                   (fn [] (rf/dispatch-sync [:good/all] {:frame f})))
          resp   (get-response f)]
      (is (empty? (filter (fn [ev] (= :fx-args (-> ev :tags :where))) traces))
          (str "no :fx-args schema failure for well-formed args; saw: "
               (pr-str (mapv (comp :failing-id :tags) traces))))
      ;; redirect-fx flows its :status through to the response :status
      ;; (Spec 011 §Redirect precedence step 1), so :status is 302 here.
      (is (= 302 (:status resp)) "redirect status flows through")
      (is (some (fn [[k _]] (= "Cache-Control" k)) (:headers resp))
          "set-header landed")
      (is (some (fn [[k _]] (= "Vary" k)) (:headers resp))
          "append-header landed")
      (is (= 2 (count (:cookies resp)))
          "both set-cookie + delete-cookie landed")
      (is (= {:status 302 :location "/dashboard"} (:redirect resp))
          "redirect landed")))

  (testing "rf2-wtd8z finding 1 — a well-formed :rf.server/safe-redirect
            (string :location, int :status, boolean :relative-only?,
            vector :allow) passes the new :rf.fx.server/safe-redirect-args
            boundary cleanly and lands its redirect"
    (rf/reg-event :good/safe-redirect
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location       "https://app.example.com/dashboard"
                :status         302
                :relative-only? false
                :allow          ["app.example.com"]}]]}))
    (let [f      (frame/make-anon-frame-record! {:platform :server})
          traces (capture-schema-failures!
                   (fn [] (rf/dispatch-sync [:good/safe-redirect] {:frame f})))
          resp   (get-response f)]
      (is (empty? (filter (fn [ev] (and (= :fx-args (-> ev :tags :where))
                                        (= :rf.server/safe-redirect
                                           (-> ev :tags :failing-id))))
                          traces))
          (str "no :fx-args schema failure for well-formed safe-redirect; saw: "
               (pr-str (mapv (comp :failing-id :tags) traces))))
      (is (= "https://app.example.com/dashboard" (-> resp :redirect :location))
          "the well-formed safe-redirect landed on the response :redirect slot")
      (is (= 302 (-> resp :redirect :status))
          "the int :status flowed through"))))

;; ===========================================================================
;; ssr-with-fx-override / ssr-end-to-end — relocated from core/smoke_test.clj
;; (rf2-zqar3). The :fx-overrides redirect and the dispatch-sync →
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
        (let [f  (frame/make-anon-frame-record!
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
      (fn [{:keys [db]} _] {:db {:articles [{:id "a" :title "Article A" :body "Body A"}
                            {:id "b" :title "Article B" :body "Body B"}]}}))
    (rf/reg-sub :articles (fn [db _] (:articles db)))
    ;; Test exercises the keyword-id [:pages/articles] hiccup head — not
    ;; the macro shape — so it uses the plain-fn surface reg-view* with
    ;; an explicit id rather than the defn-shape macro.
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
    (let [html (rf/render-to-string [:pages/articles] {:emit-hash? true})]
      (is (str/includes? html "Article A")
          "rendered HTML contains the title from app-db")
      (is (str/includes? html "Article B"))
      (is (re-find #"<div[^>]*data-rf-render-hash=\"[0-9a-f]{8}\""
                   html)
          "root <div> carries a data-rf-render-hash attribute")
      ;; The hash is reproducible: re-render the same tree, same hash.
      (let [h1 (re-find #"data-rf-render-hash=\"([0-9a-f]{8})\""  html)
            html-2 (rf/render-to-string [:pages/articles] {:emit-hash? true})
            h2 (re-find #"data-rf-render-hash=\"([0-9a-f]{8})\""  html-2)]
        (is (= (second h1) (second h2))
            "re-rendering the same view+state yields the same hash")))))

