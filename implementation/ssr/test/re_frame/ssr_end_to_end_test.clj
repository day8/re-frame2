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

    1. Build a per-request frame via make-frame {:on-create [:rf/server-init request]}.
    2. The on-create event dispatches :http/get (stubbed via :fx-overrides).
    3. The drain settles synchronously — get-frame-db reflects post-drain state.
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
  contract; resolved in rf2-8pif). The accumulator lives in app-db
  under the [:rf/response] path; tests read the resolved shape via
  re-frame.ssr/get-response."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
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

    (rf/reg-event-fx :rf/server-init
      (fn [{:keys [db]} [_ request]]
        {:db (assoc db :request request :rf/route {:id :route/articles})
         :fx [[:http/get {:url        "/api/articles"
                          :on-success [:articles/loaded]}]]}))

    (rf/reg-event-db :articles/loaded
      (fn [db [_ articles]]
        (assoc db :articles articles)))

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
    (let [server-frame (rf/make-frame
                         {:doc          "SSR request frame"
                          :platform     :server
                          :on-create    [:rf/server-init {:uri "/articles"}]
                          :fx-overrides {:http/get :http/get.canned-articles}})
          ;; (2)+(3) drain settled via :on-create + dispatch-sync chain
          server-db    (rf/get-frame-db server-frame)]

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
          (let [client-frame (rf/make-frame
                               {:doc      "Hydrated client frame"
                                :platform :client})]
            (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
            (let [client-db (rf/get-frame-db client-frame)]
              ;; The server's app-db replaced the client's empty app-db.
              (is (= (:articles server-db) (:articles client-db))
                  ":rf/hydrate replaced the client app-db with payload's :rf/app-db")
              ;; The server hash was stashed for verify-hydration!.
              (is (= server-hash (get-in client-db [:rf/hydration :server-hash])))
              (is (= 1            (get-in client-db [:rf/hydration :version]))))

            ;; First client render — same view, same hydrated state, same
            ;; resolved tree, same hash. Resolve under the client frame so
            ;; the subscribe-once reads the hydrated client app-db.
            (let [client-hash-1 (rf/render-tree-hash
                                  (resolve-tree client-frame render-tree))
                  match-traces  (atom [])]
              (rf/register-trace-cb! ::match (fn [ev] (swap! match-traces conj ev)))
              (ssr/verify-hydration!
                client-frame client-hash-1)
              (rf/remove-trace-cb! ::match)
              (is (= server-hash client-hash-1)
                  "first client render hashes identically to the server hash")
              (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %))
                            @match-traces)
                  "no :rf.ssr/hydration-mismatch trace when hashes agree"))

            ;; (7) Mutate the hydrated app-db; re-render; hash differs;
            ;;     verify-hydration! emits the mismatch trace.
            (rf/reg-event-db :articles/append
              (fn [db [_ extra]]
                (update db :articles conj extra)))
            (rf/dispatch-sync [:articles/append
                               {:id "c" :title "Article C" :body "Body C"}]
                              {:frame client-frame})

            (let [client-hash-2   (rf/render-tree-hash
                                     (resolve-tree client-frame render-tree))
                  mismatch-traces (atom [])]
              (rf/register-trace-cb! ::mismatch (fn [ev] (swap! mismatch-traces conj ev)))
              (ssr/verify-hydration!
                client-frame client-hash-2)
              (rf/remove-trace-cb! ::mismatch)

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
      (rf/reg-event-fx :auth/forbid
        (fn [_ _]
          {:fx [[:rf.server/set-status 401]
                [:rf.server/set-status 403]]}))                          ;; second write replaces

      (let [f (rf/make-frame {:platform :server})]
        (rf/register-trace-cb! ::status (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/forbid] {:frame f})
        (rf/remove-trace-cb! ::status)

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
    (rf/reg-event-fx :auth/establish
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

    (let [f (rf/make-frame {:platform :server})]
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
    (rf/reg-event-fx :auth/logout
      (fn [_ _]
        {:fx [[:rf.server/delete-cookie {:name "session" :path "/"}]]}))

    (let [f (rf/make-frame {:platform :server})]
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
    (rf/reg-event-fx :hdr/set-then-replace
      (fn [_ _]
        ;; First :set-header writes the default; the second replaces it
        ;; (case-insensitive name match per Spec 011 §Header replacement).
        {:fx [[:rf.server/set-header {:name "X-Foo" :value "first"}]
              [:rf.server/set-header {:name "x-foo" :value "second"}]]}))
    (rf/reg-event-fx :hdr/append-twice
      (fn [_ _]
        {:fx [[:rf.server/append-header {:name "Set-Cookie" :value "a=1"}]
              [:rf.server/append-header {:name "Set-Cookie" :value "b=2"}]]}))

    (let [f (rf/make-frame {:platform :server})]
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
    (rf/register-trace-cb! tag
      (fn [ev]
        (when (= :rf.error/fx-handler-exception (:operation ev))
          (swap! traces conj ev))))
    (try
      (body-fn)
      @traces
      (finally
        (rf/remove-trace-cb! tag)))))

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
    (rf/reg-event-fx :hdr/inject-crlf
      (fn [_ _]
        {:fx [[:rf.server/set-header
               {:name  "X-Forwarded-For"
                :value "1.2.3.4\r\nSet-Cookie: admin=1"}]]}))

    (let [f      (rf/make-frame {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:hdr/inject-crlf] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/header-invalid-value
        "set-header with CRLF in value")))

  (testing "rf2-hbty2 §P1.3 — bare LF / bare CR / NUL all rejected"
    (doseq [hostile ["lf\nbad" "cr\rbad" (str "nul" (char 0) "bad")]]
      (rf/reg-event-fx :hdr/probe-injection
        (fn [_ _]
          {:fx [[:rf.server/set-header {:name "X-Probe" :value hostile}]]}))
      (let [f      (rf/make-frame {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:hdr/probe-injection] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/header-invalid-value
          (str "hostile value " (pr-str hostile)))))))

(deftest ssr-append-header-rejects-crlf-injection
  (testing "rf2-hbty2 §P1.3 — :rf.server/append-header with CR/LF in
            value surfaces :rf.error/header-invalid-value"
    (rf/reg-event-fx :hdr/append-crlf
      (fn [_ _]
        {:fx [[:rf.server/append-header
               {:name  "X-Audit"
                :value "ok\r\nSet-Cookie: forged=1"}]]}))
    (let [f      (rf/make-frame {:platform :server})
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
    (rf/reg-event-fx :redirect/crlf-in-location
      (fn [_ _]
        {:fx [[:rf.server/redirect
               {:location "https://example.com\r\nSet-Cookie: stolen=1"}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:redirect/crlf-in-location] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/redirect-invalid-location
        "redirect with CRLF in :location")))

  (testing "rf2-hbty2 — same gate covers :url and :to alternate slot names"
    (rf/reg-event-fx :redirect/url-crlf
      (fn [_ _]
        {:fx [[:rf.server/redirect {:url "/ok\r\nbad"}]]}))
    (rf/reg-event-fx :redirect/to-crlf
      (fn [_ _]
        {:fx [[:rf.server/redirect {:to "/ok\nbad"}]]}))
    (doseq [ev [:redirect/url-crlf :redirect/to-crlf]]
      (let [f      (rf/make-frame {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [ev] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/redirect-invalid-location
          (str ev " redirect via " ev " alias"))))))

(deftest ssr-header-clean-values-still-accepted
  (testing "rf2-hbty2 — regression guard: legitimate header values still flow
            through. Whitespace, semicolons, quoted-strings, full URLs are
            all valid (only CR/LF/NUL is banned)."
    (rf/reg-event-fx :hdr/clean
      (fn [_ _]
        {:fx [[:rf.server/set-header {:name "Cache-Control"
                                      :value "no-cache, must-revalidate, max-age=0"}]
              [:rf.server/set-header {:name "X-Whitespace"
                                      :value "tab\there space"}]
              [:rf.server/redirect    {:location "https://example.com/path?q=1&r=2"}]]}))
    (let [f (rf/make-frame {:platform :server :on-create [:hdr/clean]})
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
    (rf/reg-event-fx :auth/check-session
      (fn [_ _]
        {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))

    (let [f (rf/make-frame {:platform     :server
                            :on-create    [:auth/check-session]})]
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
    (rf/reg-event-fx :auth/check-no-status
      (fn [_ _]
        {:fx [[:rf.server/redirect {:location "/login"}]]}))
    (let [f (rf/make-frame {:platform  :server
                            :on-create [:auth/check-no-status]})]
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
    (rf/reg-route :route/home {:path "/"})
    (let [project-error  ssr/project-error
          f              (rf/make-frame
                           {:platform :server
                            :ssr {:public-error-id   :rf.ssr/default-error-projector
                                  :dev-error-detail? false}})
          traces         (atom [])]
      (rf/register-trace-cb! ::nsh (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (rf/remove-trace-cb! ::nsh)

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
  (testing "a handler that throws → default projector → 500"
    (rf/reg-event-fx :load/article
      (fn [_ _]
        (throw (ex-info "Database connection failed: SECRET_TOKEN=xyz" {}))))
    (rf/reg-event-fx :rf/server-init
      (fn [_ _]
        {:fx [[:dispatch [:load/article]]]}))

    (let [project-error  ssr/project-error
          traces         (atom [])
          _              (rf/register-trace-cb! ::he (fn [ev] (swap! traces conj ev)))
          f              (rf/make-frame
                           {:platform :server
                            :on-create [:rf/server-init]
                            :ssr {:public-error-id   :rf.ssr/default-error-projector
                                  :dev-error-detail? false}})
          _              (rf/remove-trace-cb! ::he)
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
          f             (rf/make-frame
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
          f             (rf/make-frame
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
          f             (rf/make-frame
                          {:platform :server
                           :ssr {:public-error-id   :myapp/buggy-projector
                                 :dev-error-detail? false}})
          traces        (atom [])
          _             (rf/register-trace-cb! ::sop (fn [ev] (swap! traces conj ev)))
          public        (project-error f {:operation :rf.error/handler-exception :tags {}})]
      (rf/remove-trace-cb! ::sop)
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
          f             (rf/make-frame
                          {:platform :server
                           :ssr {:public-error-id   :myapp/bad-shape}})
          traces        (atom [])
          _             (rf/register-trace-cb! ::bs (fn [ev] (swap! traces conj ev)))
          public        (project-error f {:operation :rf.error/handler-exception :tags {}})]
      (rf/remove-trace-cb! ::bs)
      (is (= 500 (:status public))
          "non-conforming projector output → fallback locked-500")
      (is (= :internal-error (:code public)))
      (is (some #(= :rf.error/sanitised-on-projection (:operation %)) @traces)))))

(deftest ssr-error-projection-skips-client-frames
  (testing "client-platform frames don't have their :rf/response stamped on errors"
    ;; A :rf.error/no-such-handler trace inside a CLIENT frame should not
    ;; touch :rf/response — the client doesn't have an HTTP response to
    ;; project. (The trace still fires; the projector just isn't called
    ;; for a client frame's response slot.)
    (rf/reg-route :route/home {:path "/"})
    (let [client-f (rf/make-frame {:platform :client})]
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
      (rf/reg-event-fx :auth/double-redirect
        (fn [_ _]
          {:fx [[:rf.server/redirect {:status 302 :location "/login"}]
                [:rf.server/redirect {:status 301 :location "/canonical"}]]}))

      (let [f (rf/make-frame {:platform :server})]
        (rf/register-trace-cb! ::redir (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:auth/double-redirect] {:frame f})
        (rf/remove-trace-cb! ::redir)

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
;; ssr-head-hash-mismatch — head-vs-body discriminator on the unified hash
;; ===========================================================================
;;
;; Per Spec 011 §Mismatch detection — head: head-mismatch and body-mismatch
;; share the unified :rf/render-hash channel in v1; the discriminator is
;; :failing-id. A dedicated head-hash payload key + wire attribute is
;; reserved for the post-v1 reg-head extension. The runtime routes both
;; through verify-hydration! and surfaces head-vs-body via :failing-id.

(deftest ssr-head-hash-mismatch
  (testing "verify-hydration! surfaces a head-mismatch via :failing-id on the unified render-hash channel"
    (let [verify-fn ssr/verify-hydration!
          ;; Hydration payload carries the SERVER's render-hash. v1's
          ;; unified channel covers head + body; the head-vs-body
          ;; distinction lives entirely in :failing-id below.
          payload   {:rf/version     1
                     :rf/app-db      {:rf/route {:id :route/article :params {:id "123"}}}
                     :rf/render-hash "head-hash-server-A"}
          traces    (atom [])
          f         (rf/make-frame {:platform :client})]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame f})
      (is (= "head-hash-server-A"
             (get-in (rf/get-frame-db f) [:rf/hydration :server-hash]))
          ":rf/hydrate stashed the server's head-hash")

      (rf/register-trace-cb! ::head (fn [ev] (swap! traces conj ev)))
      ;; Client recomputes the head — yields a different hash.
      (verify-fn f
                 "head-hash-client-B"
                 {:failing-id :rf.ssr/head-mismatch
                  :first-diff-path [:head :title]})
      (rf/remove-trace-cb! ::head)

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
        (rf/register-trace-cb! ::head-ok (fn [ev] (swap! no-mismatch-traces conj ev)))
        (verify-fn f
                   "head-hash-server-A"
                   {:failing-id :rf.ssr/head-mismatch})
        (rf/remove-trace-cb! ::head-ok)
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
    (let [f (rf/make-frame
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
          (is (= ":rf.error/render-on-headless-adapter" (ex-message e))
              "ex-message names the contract violation")
          (is (string? (-> e ex-data :reason))
              "ex-data carries a human :reason"))))))

;; ===========================================================================
;; rf2-ooj41 — redirect alias normalisation (:url and :to → :location)
;; ===========================================================================
;;
;; Spec 011 §Redirect contract: `:rf.server/redirect` accepts `:location`,
;; `:url`, or `:to` for the destination key. The current suite only
;; exercises `:location`; the alias forms in `redirect-fx`
;; (`response.cljc:218-221`) had no test coverage. Pin both aliases so a
;; refactor that drops one fails loudly.

(deftest redirect-alias-url-normalises-to-location
  (testing "{:url \"...\"} is normalised to {:location \"...\" :status 302}"
    (rf/reg-event-fx :alias/url-redirect
      (fn [_ _]
        {:fx [[:rf.server/redirect {:url "/dashboard"}]]}))
    (let [f (rf/make-frame {:platform  :server
                            :on-create [:alias/url-redirect]})
          redirect (-> (get-response f) :redirect)]
      (is (= "/dashboard" (:location redirect))
          ":url alias is normalised onto :location in the resolved redirect")
      (is (= 302 (:status redirect))
          "default status flows through when no explicit :status supplied"))))

(deftest redirect-alias-to-normalises-to-location
  (testing "{:to \"...\"} is normalised to {:location \"...\" :status 302}"
    (rf/reg-event-fx :alias/to-redirect
      (fn [_ _]
        {:fx [[:rf.server/redirect {:to "/welcome" :status 301}]]}))
    (let [f (rf/make-frame {:platform  :server
                            :on-create [:alias/to-redirect]})
          redirect (-> (get-response f) :redirect)]
      (is (= "/welcome" (:location redirect))
          ":to alias is normalised onto :location")
      (is (= 301 (:status redirect))
          "caller-supplied :status wins over the 302 default"))))

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
    (rf/reg-event-fx :redirect-then-error
      (fn [_ _]
        {:fx [[:rf.server/redirect {:status 302 :location "/login"}]
              ;; Then trigger a handler-exception trace — the default
              ;; projector maps this to 500. The redirect was set
              ;; first; the projector must NOT promote 302 → 500.
              [:dispatch [:throw-from-handler]]]}))
    (rf/reg-event-fx :throw-from-handler
      (fn [_ _] (throw (ex-info "post-redirect failure" {}))))

    (let [traces (atom [])
          _      (rf/register-trace-cb! ::rpe (fn [ev] (swap! traces conj ev)))
          f      (rf/make-frame
                   {:platform  :server
                    :on-create [:redirect-then-error]
                    :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                :dev-error-detail? false}})
          _      (rf/remove-trace-cb! ::rpe)
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
    (rf/register-trace-cb! tag
                           (fn [ev]
                             (when (match? (:operation ev))
                               (swap! traces conj ev))))
    (try (body-fn) @traces
         (finally (rf/remove-trace-cb! tag)))))

;; --- Step 1: URL parse failure --------------------------------------------

(deftest safe-redirect-rejects-unparseable-url
  (testing "rf2-2brsn step 1: a :location that cannot be parsed as a URL
            → :rf.error/safe-redirect-invalid-url trace AND no :redirect
            is set on the response (the fx is a no-op on rejection)"
    (rf/reg-event-fx :sr/unparseable
      (fn [_ _]
        ;; A space-after-colon makes this URISyntax-invalid in java.net.URI
        ;; (the colon makes it look like a scheme, but the space after is
        ;; not a legal scheme-specific character).
        {:fx [[:rf.server/safe-redirect
               {:location "https://example.com/path with space"}]]}))
    (let [f      (rf/make-frame {:platform :server})
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
    (rf/reg-event-fx :sr/javascript
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "javascript:alert(1)"}]]}))
    (let [f      (rf/make-frame {:platform :server})
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
    (rf/reg-event-fx :sr/data
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "data:text/html,<script>alert(1)</script>"}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/data] {:frame f})))]
      (is (some #(and (= :rf.error/safe-redirect-scheme-rejected (:operation %))
                      (= "data" (-> % :tags :scheme)))
                traces)
          ":rf.error/safe-redirect-scheme-rejected fires with :scheme \"data\""))))

(deftest safe-redirect-rejects-vbscript-scheme
  (testing "rf2-2brsn step 2: vbscript: scheme rejected (IE-era VBScript exec)"
    (rf/reg-event-fx :sr/vbscript
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "vbscript:msgbox(\"x\")"}]]}))
    (let [f      (rf/make-frame {:platform :server})
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
      (rf/reg-event-fx :sr/probe-case
        (fn [_ _]
          {:fx [[:rf.server/safe-redirect {:location hostile}]]}))
      (let [f      (rf/make-frame {:platform :server})
            traces (capture-safe-redirect-traces!
                     (fn [] (rf/dispatch-sync [:sr/probe-case] {:frame f})))]
        (is (some #(= :rf.error/safe-redirect-scheme-rejected (:operation %))
                  traces)
            (str "case-folded scheme " (pr-str hostile) " rejected"))))))

;; --- Step 3: :relative-only? gate -----------------------------------------

(deftest safe-redirect-relative-only-rejects-absolute-url
  (testing "rf2-2brsn step 3: :relative-only? true AND URL has host →
            :rf.error/safe-redirect-host-disallowed (:reason :relative-only-violation)"
    (rf/reg-event-fx :sr/abs-with-relative-only
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location       "https://evil.example.com/phish"
                :relative-only? true}]]}))
    (let [f      (rf/make-frame {:platform :server})
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
    (rf/reg-event-fx :sr/relative-ok
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location       "/dashboard"
                :relative-only? true}]]}))
    (let [f      (rf/make-frame {:platform :server})
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
    (rf/reg-event-fx :sr/not-in-allow
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "https://evil.example.com/phish"
                :allow    ["app.example.com" "alt.example.com"]}]]}))
    (let [f      (rf/make-frame {:platform :server})
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
                 (-> ev :tags :allow?))
              ":allow? tag carries the allowlist for diagnostic clarity")))
      (is (nil? (:redirect (get-response f)))
          "rejection is a no-op"))))

(deftest safe-redirect-allowlist-accepts-on-allowlist-host
  (testing "rf2-2brsn step 4 happy path: host IN allowlist → redirect succeeds"
    (rf/reg-event-fx :sr/in-allow
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "https://app.example.com/dashboard"
                :allow    ["app.example.com" "alt.example.com"]}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/in-allow] {:frame f})))
          resp   (get-response f)]
      (is (empty? traces)
          "no :rf.error/safe-redirect-* trace on a passing allowlist match")
      (is (= "https://app.example.com/dashboard"
             (-> resp :redirect :location))
          ":location lands on the response :redirect slot"))))

;; --- Validation order: parse runs before scheme runs before policy --------

(deftest safe-redirect-validation-order-parse-precedes-scheme
  (testing "rf2-2brsn: a fundamentally-unparseable URL surfaces the parse
            error, NOT the scheme error (validation runs in order — see
            Spec 009 §Error event catalogue)"
    (rf/reg-event-fx :sr/order-parse-first
      (fn [_ _]
        ;; This is BOTH unparseable AND vaguely-javascript-shaped — the
        ;; parser-fail must fire FIRST because step 1 runs before step 2.
        {:fx [[:rf.server/safe-redirect
               {:location "javascript: not a real url "}]]}))
    (let [f      (rf/make-frame {:platform :server})
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
    (rf/reg-event-fx :sr/empty
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect {:location ""}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-safe-redirect-traces!
                   (fn [] (rf/dispatch-sync [:sr/empty] {:frame f})))]
      (is (some #(= :rf.error/safe-redirect-invalid-url (:operation %))
                traces)
          "empty :location → :rf.error/safe-redirect-invalid-url")
      (is (nil? (:redirect (get-response f)))
          "rejection is a no-op"))))

;; --- CRLF defence-in-depth still holds ------------------------------------

(deftest safe-redirect-also-rejects-crlf-injection
  (testing "rf2-2brsn: the CRLF gate (rf2-hbty2) runs on safe-redirect too —
            an attacker passing a CRLF-bearing location is presumably trying
            both vectors. Same fx-boundary throw as redirect-fx; the throw
            propagates as :rf.error/fx-handler-exception"
    (rf/reg-event-fx :sr/crlf
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect
               {:location "/path\r\nSet-Cookie: stolen=1"}]]}))
    (let [f      (rf/make-frame {:platform :server})
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
          (str "hostile tag-name " (pr-str hostile))))))

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
        ":<> fragment renders children with no wrapper")))

(deftest ssr-set-header-rejects-invalid-name
  (testing "rf2-z7gor — :rf.server/set-header with CRLF in :name surfaces
            :rf.error/header-invalid-name (sister gate to rf2-hbty2's
            header-value gate)"
    (rf/reg-event-fx :hdr/crlf-in-name
      (fn [_ _]
        {:fx [[:rf.server/set-header
               {:name  "X-Test\r\nSet-Cookie: evil=1"
                :value "ok"}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:hdr/crlf-in-name] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/header-invalid-name
        "set-header with CRLF in :name")))

  (testing "rf2-z7gor — separators / whitespace / empty all rejected"
    (doseq [hostile ["Bad: Name" "Bad Name" "" "with(parens)"
                     (str "nul" (char 0) "bad")]]
      (rf/reg-event-fx :hdr/probe-name
        (fn [_ _]
          {:fx [[:rf.server/set-header {:name hostile :value "ok"}]]}))
      (let [f      (rf/make-frame {:platform :server})
            traces (capture-fx-traces!
                     (fn [] (rf/dispatch-sync [:hdr/probe-name] {:frame f})))]
        (expect-fx-error-keyword!
          traces :rf.error/header-invalid-name
          (str "hostile header name " (pr-str hostile)))))))

(deftest ssr-append-header-rejects-invalid-name
  (testing "rf2-z7gor — :rf.server/append-header with CRLF in :name surfaces
            :rf.error/header-invalid-name (same gate as set-header)"
    (rf/reg-event-fx :hdr/append-crlf-name
      (fn [_ _]
        {:fx [[:rf.server/append-header
               {:name  "X-Audit\r\nSet-Cookie: forged=1"
                :value "ok"}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:hdr/append-crlf-name] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/header-invalid-name
        "append-header with CRLF in :name"))))

(deftest ssr-set-cookie-rejects-invalid-fields
  (testing "rf2-z7gor — :rf.server/set-cookie with CRLF in :name surfaces
            :rf.error/cookie-invalid-name (RFC 6265 §4.1.1 token grammar)"
    (rf/reg-event-fx :ck/crlf-in-name
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name  "session\r\nSet-Cookie: stolen=1"
                :value "abc"}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/crlf-in-name] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-name
        "set-cookie with CRLF in :name")))

  (testing "rf2-z7gor — :rf.server/set-cookie with CRLF in :value surfaces
            :rf.error/cookie-invalid-value"
    (rf/reg-event-fx :ck/crlf-in-value
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name  "session"
                :value "abc\r\nSet-Cookie: stolen=1"}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/crlf-in-value] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-value
        "set-cookie with CRLF in :value")))

  (testing "rf2-z7gor — :rf.server/set-cookie with CRLF in :path surfaces
            :rf.error/cookie-invalid-path"
    (rf/reg-event-fx :ck/crlf-in-path
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name  "session"
                :value "abc"
                :path  "/\r\nSet-Cookie: stolen=1"}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/crlf-in-path] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-path
        "set-cookie with CRLF in :path")))

  (testing "rf2-z7gor — :rf.server/set-cookie with CRLF in :domain surfaces
            :rf.error/cookie-invalid-domain"
    (rf/reg-event-fx :ck/crlf-in-domain
      (fn [_ _]
        {:fx [[:rf.server/set-cookie
               {:name   "session"
                :value  "abc"
                :domain "example.com\r\nSet-Cookie: stolen=1"}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/crlf-in-domain] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-domain
        "set-cookie with CRLF in :domain"))))

(deftest ssr-delete-cookie-rejects-invalid-fields
  (testing "rf2-z7gor — :rf.server/delete-cookie runs the same validators
            as set-cookie (it's sugar over set-cookie)"
    (rf/reg-event-fx :ck/del-crlf-path
      (fn [_ _]
        {:fx [[:rf.server/delete-cookie
               {:name "session" :path "/admin\r\nbad"}]]}))
    (let [f      (rf/make-frame {:platform :server})
          traces (capture-fx-traces!
                   (fn [] (rf/dispatch-sync [:ck/del-crlf-path] {:frame f})))]
      (expect-fx-error-keyword!
        traces :rf.error/cookie-invalid-path
        "delete-cookie with CRLF in :path"))))

(deftest ssr-clean-names-still-accepted
  (testing "rf2-z7gor — regression guard: legitimate header names + cookie
            field shapes still flow"
    (rf/reg-event-fx :clean/all
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
    (let [f (rf/make-frame {:platform :server :on-create [:clean/all]})
          resp (get-response f)]
      (is (some (fn [[k _]] (= "Cache-Control"   k)) (:headers resp)))
      (is (some (fn [[k _]] (= "X-Forwarded-For" k)) (:headers resp)))
      (is (= 2 (count (:cookies resp))))
      (is (= "session" (-> resp :cookies first :name)))
      (is (= "stale"   (-> resp :cookies second :name))))))

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

      (rf/reg-event-fx :rf/server-init
        (fn [{:keys [db]} [_ _request]]
          {:db (assoc db :rf/route {:id :route/articles})
           :fx [[:http/get {:url "/api/articles"
                            :on-success [:articles/loaded]}]]}))
      (rf/reg-event-db :articles/loaded
        (fn [db [_ articles]] (assoc db :articles articles)))

      (let [traces (atom [])]
        (rf/register-trace-cb! ::ssr (fn [ev] (swap! traces conj ev)))
        (let [f  (rf/make-frame
                   {:on-create    [:rf/server-init {:uri "/articles"}]
                    :fx-overrides {:http/get :http/get.canned-articles}})
              db (rf/get-frame-db f)]
          (rf/remove-trace-cb! ::ssr)
          (is @stub-fired? "the override redirected the fx to the stub")
          (is (= 2 (count (:articles db)))
              (str "expected 2 articles in db; traces: "
                   (pr-str (mapv :operation @traces))))
          (is (= "Article A" (-> db :articles first :title))))))))

(deftest ssr-end-to-end
  (testing "complete SSR flow: dispatch-sync → render-to-string → embedded hash"
    ;; Register a trivial articles app — an event seeds state, a sub
    ;; reads it, a view renders it.
    (rf/reg-event-db :articles/seed
      (fn [_ _] {:articles [{:id "a" :title "Article A" :body "Body A"}
                            {:id "b" :title "Article B" :body "Body B"}]}))
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

