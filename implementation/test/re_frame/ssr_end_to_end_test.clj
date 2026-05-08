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
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (rf/init!)
  ;; Framework registrations happen at namespace-load time in
  ;; routing.cljc / ssr.cljc / machines.cljc; clear-all! wiped them, so
  ;; reload to resurrect :rf/hydrate, :rf.route/navigate, etc.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

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
    (fn []
      (let [head (first render-tree)]
        (if-let [view-fn (rf/get-view head)]
          (apply view-fn (rest render-tree))
          render-tree)))))

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
        {:db (assoc db :request request :route {:id :route/articles})
         :fx [[:http/get {:url        "/api/articles"
                          :on-success [:articles/loaded]}]]}))

    (rf/reg-event-db :articles/loaded
      (fn [db [_ articles]]
        (assoc db :articles articles)))

    (rf/reg-sub :articles (fn [db _] (:articles db)))
    (rf/reg-view :pages/articles
      (fn []
        (let [arts (rf/subscribe-value [:articles])]
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
                            (fn [] (rf/render-to-string render-tree {:emit-hash? true})))
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
            ;; the subscribe-value reads the hydrated client app-db.
            (let [client-hash-1 (rf/render-tree-hash
                                  (resolve-tree client-frame render-tree))
                  match-traces  (atom [])]
              (rf/register-trace-cb! ::match (fn [ev] (swap! match-traces conj ev)))
              ((requiring-resolve 're-frame.ssr/verify-hydration!)
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
              ((requiring-resolve 're-frame.ssr/verify-hydration!)
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
  ((requiring-resolve 're-frame.ssr/get-response) frame-id))

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
    (let [project-error  (requiring-resolve 're-frame.ssr/project-error)
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

    (let [project-error  (requiring-resolve 're-frame.ssr/project-error)
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
    (let [project-error (requiring-resolve 're-frame.ssr/project-error)
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

    (let [project-error (requiring-resolve 're-frame.ssr/project-error)
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

    (let [project-error (requiring-resolve 're-frame.ssr/project-error)
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

    (let [project-error (requiring-resolve 're-frame.ssr/project-error)
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
;; ssr-head-hash-mismatch — head-model hash differs across server/client
;; ===========================================================================
;;
;; Per Spec 011 §Mismatch detection — head: the head-model is hashed
;; separately from the body so head/body mismatches can be reported with
;; the right operation tag. The runtime today routes both through
;; verify-hydration!; we surface a head-mismatch by tagging the
;; :failing-id with :rf.ssr/head-mismatch — once rf2-8pif distinguishes
;; head and body natively this can become :operation :head.

(deftest ssr-head-hash-mismatch
  (testing "verify-hydration! detects a head-hash mismatch and tags the trace as :head"
    (let [verify-fn @(resolve 're-frame.ssr/verify-hydration!)
          ;; Hydration payload carries the SERVER's head-hash.
          ;; (We park it under :rf/render-hash because the runtime's
          ;; current verify-hydration! reads server-hash from there;
          ;; the head/body distinction lives in :failing-id below.)
          payload   {:rf/version     1
                     :rf/app-db      {:route {:id :route/article :params {:id "123"}}}
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
