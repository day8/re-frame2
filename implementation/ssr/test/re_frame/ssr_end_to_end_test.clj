(ns re-frame.ssr-end-to-end-test
  "The SSR request lifecycle end to end, per Spec 011: a per-request server
  frame drains its `:initial-events`, renders with a `data-rf-render-hash`
  root stamp, builds the hydration payload, and a client frame hydrates and
  verifies it. Around that sit the `:rf.server/*` response fx, their
  injection gates, and the error projector.

  ## Posture split

  This namespace also runs under `-Dre-frame.debug=false`
  (`scripts/test-ssr-prod-gate.sh`). The security gates (CR/LF/NUL in header
  values and cookie attributes, the cookie-attribute grammar, safe-redirect)
  reject in that lane, so their assertions read the always-on `:errors` axis
  rather than the dev trace. Only genuinely dev-only observations — the
  `:rf.warning/*` family, the hydration-mismatch trace, the Spec 010 step-5
  diagnostic and safe-redirect's raw diagnostics — sit in
  `(when interop/debug-enabled? …)` arms, each beside the positive it
  belongs to, because a negative over the dev ring passes vacuously under
  the gate."
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

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---- helpers --------------------------------------------------------------

(def ^:private canned-articles
  [{:id "a" :title "Article A" :body "Body A"}
   {:id "b" :title "Article B" :body "Body B"}])

(def ^:private locked-500
  {:status 500 :code :internal-error :message "Something went wrong" :retryable? false})

(defn- reg-canned-http-get!
  "Register a no-op `:http/get` and a stub that answers its `:on-success`
  with `canned-articles` on the dispatching frame, for `:fx-overrides`."
  []
  (rf/reg-fx :http/get {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-fx :http/get.canned-articles
    {:platforms #{:server :client}}
    (fn [{:keys [frame]} {:keys [on-success]}]
      (rf/dispatch (conj on-success canned-articles) {:frame frame}))))

(defn- reg-articles-view!
  "Register `:articles` and a `:pages/articles` view rendering them."
  []
  (rf/reg-sub :articles (fn [db _] (:articles db)))
  (rf/reg-view* :pages/articles
    (fn []
      (let [arts (rf/subscribe-once [:articles])]
        [:div.page
         [:h1 "Recent articles"]
         [:ul
          (for [{:keys [id title body]} arts]
            ^{:key id} [:li [:h3 title] [:p body]])]]))))

(defn- resolve-tree
  "Resolve a `[view-fn args...]` reference under a frame so the tree, and so
  its hash, reflects that frame's app-db. A keyword head is a DOM element,
  not a view, and is returned untouched (a keyword is itself `ifn?`)."
  [frame-id render-tree]
  (rf/with-frame frame-id
    (let [head (first render-tree)]
      (if (and (ifn? head) (not (keyword? head)))
        (apply head (rest render-tree))
        render-tree))))

(defn- settled-response
  "The response as a host reads it: after the settle that projects buffered
  errors."
  [frame-id]
  (:response (rf.ssr/flush-response-result! frame-id)))

(defn- capture-fx-traces!
  "Every `:rf.error/fx-handler-exception` emitted during `body-fn`, read from
  BOTH error axes and normalised to `{:operation … :tags …}`. The always-on
  axis is what makes the injection gates observable under the production
  gate; the gates themselves throw in every build."
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

(defn- fx-error-extra
  "The `ex-data` of the first captured fx exception whose message carries
  `error-kw` (the fx-side validators throw with the error id in the message),
  or nil."
  [traces error-kw]
  (some (fn [ev]
          (let [e (-> ev :tags :exception)]
            (when (and e (str/includes? (str (.getMessage ^Throwable e)) (str error-kw)))
              (ex-data e))))
        traces))

(defn- expect-fx-error-keyword!
  [traces error-kw context-str]
  (is (some? (fx-error-extra traces error-kw))
      (str context-str " — expected an :rf.error/fx-handler-exception carrying "
           error-kw "; saw: " (pr-str (mapv :operation traces)))))

(defn- drive-fx!
  "Dispatch one fx on a fresh server frame; return `[frame traces]`."
  [fx-id args]
  (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
        ev-id  (keyword "rf2-lwtlk" (str "drive-" (name (gensym "e"))))]
    (rf/reg-event ev-id (fn [_ _] {:fx [[fx-id args]]}))
    [f (capture-fx-traces! #(rf/dispatch-sync [ev-id] {:frame f}))]))

;; ===========================================================================
;; The canonical happy path
;; ===========================================================================

(deftest ssr-full-request-lifecycle
  (reg-canned-http-get!)
  (reg-articles-view!)
  (rf/reg-event :rf/server-init
    (fn [{:keys [db]} [_ request]]
      {:db (assoc db :request request)
       :fx [[:http/get {:url "/api/articles" :on-success [:articles/loaded]}]]}))
  (rf/reg-event :articles/loaded
    (fn [{:keys [db]} [_ articles]] {:db (assoc db :articles articles)}))

  (let [server-frame (rf.frame/make-anon-frame-record!
                       {:platform       :server
                        :initial-events [[:rf/server-init {:uri "/articles"}]]
                        :fx-overrides   {:http/get :http/get.canned-articles}})
        server-db    (rf/app-db-value server-frame)
        render-tree  [(rf/view :pages/articles)]
        html         (rf/with-frame server-frame
                       (rf.ssr/render-to-string
                         render-tree {:render-hash (rf.ssr/render-tree-hash render-tree)}))
        ;; The wire stamp hashes the input tree; the payload carries the
        ;; RESOLVED (state-dependent) hash the client re-renders against.
        server-hash  (rf.ssr/render-tree-hash (resolve-tree server-frame render-tree))
        payload      (rf.ssr.payload-policy/build-payload server-frame server-db server-hash {})]
    (is (= {:request {:uri "/articles"} :articles canned-articles} server-db)
        "the drain settled the stubbed :http/get into the server app-db")
    (is (every? #(str/includes? html %) ["Article A" "Article B"]))
    (is (re-find #"<div[^>]*data-rf-render-hash=\"[0-9a-f]{8}\"" html)
        "root <div> carries data-rf-render-hash")
    (is (= {:rf/version 1 :rf/frame-id server-frame :rf/app-db server-db :rf/render-hash server-hash}
           payload))

    ;; One process stands in for both hosts, so the payload is re-stamped
    ;; for the client frame it hydrates into (a present-and-different
    ;; :rf/frame-id is rejected).
    (let [client-frame (rf.frame/make-anon-frame-record! {:platform :client})]
      (rf/dispatch-sync [:rf/hydrate (assoc payload :rf/frame-id client-frame)] {:frame client-frame})
      (is (= server-db (rf/app-db-value client-frame)))
      (is (= {:server-hash server-hash :version 1}
             (select-keys (get-in (rf/frame-state-value client-frame)
                                  [:rf.db/runtime :rf.runtime/ssr :hydration])
                          [:server-hash :version])))

      (let [client-hash-1 (rf.ssr/render-tree-hash (resolve-tree client-frame render-tree))
            match-traces  (atom [])]
        (rf/register-listener! :trace ::match (fn [ev] (swap! match-traces conj ev)))
        (rf.ssr/verify-hydration! client-frame client-hash-1)
        (rf/unregister-listener! :trace ::match)
        (is (= server-hash client-hash-1)
            "first client render hashes identically to the server")
        ;; DEV ARM — the mismatch trace is dev-only.
        (when rf.interop/debug-enabled?
          (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %)) @match-traces))))

      (rf/reg-event :articles/append
        (fn [{:keys [db]} [_ extra]] {:db (update db :articles conj extra)}))
      (rf/dispatch-sync [:articles/append {:id "c" :title "Article C" :body "Body C"}]
                        {:frame client-frame})
      (let [client-hash-2   (rf.ssr/render-tree-hash (resolve-tree client-frame render-tree))
            mismatch-traces (atom [])]
        (rf/register-listener! :trace ::mismatch (fn [ev] (swap! mismatch-traces conj ev)))
        (rf.ssr/verify-hydration! client-frame client-hash-2)
        (rf/unregister-listener! :trace ::mismatch)
        (is (not= server-hash client-hash-2)
            "mutating the hydrated db changes the render hash")
        ;; DEV ARM — the mismatch WARNING; the divergence above is computed in
        ;; every build.
        (when rf.interop/debug-enabled?
          (is (some (fn [ev]
                      (and (= :rf.ssr/hydration-mismatch (:operation ev))
                           (= :error (:op-type ev))
                           (= server-hash (:server-hash (:tags ev)))
                           (= client-hash-2 (:client-hash (:tags ev)))
                           (= :warned-and-replaced (:recovery ev))))
                    @mismatch-traces)
              (pr-str (mapv :operation @mismatch-traces))))))))

;; ===========================================================================
;; Last-write-wins slots, and the bookkeeping the public reads strip
;; ===========================================================================

(deftest ssr-set-status-precedence
  (testing "two set-status fx: last write wins on both public reads, which
            strip the internal status-writes bookkeeping; dev warns"
    (let [traces (atom [])
          f      (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf/reg-event :auth/forbid
        (fn [_ _] {:fx [[:rf.server/set-status 401] [:rf.server/set-status 403]]}))
      (rf/register-listener! :trace ::status (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:auth/forbid] {:frame f})
      (rf/unregister-listener! :trace ::status)
      (doseq [resp [(rf.ssr/peek-response f) (rf.ssr/get-response f)]]
        (is (= {:status 403}
               (select-keys resp [:status rf.ssr.response/status-writes-key]))))
      ;; DEV ARM — the warning is programmer advice; the policy is above.
      (when rf.interop/debug-enabled?
        (is (some (fn [ev]
                    (and (= :rf.warning/multiple-status-set (:operation ev))
                         (= [401 403] (:writes (:tags ev)))
                         (= 403 (:final-status (:tags ev)))
                         (= :warned-and-replaced (:recovery ev))))
                  @traces)
            (pr-str (mapv :operation @traces)))))))

(deftest ssr-multi-redirect
  (testing "two redirect fx → last write wins + :rf.warning/multiple-redirects"
    (let [traces (atom [])
          f      (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf/reg-event :auth/double-redirect
        (fn [_ _]
          {:fx [[:rf.server/redirect {:status 302 :location "/login"}]
                [:rf.server/redirect {:status 301 :location "/canonical"}]]}))
      (rf/register-listener! :trace ::redir (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:auth/double-redirect] {:frame f})
      (rf/unregister-listener! :trace ::redir)
      (is (= {:status 301 :location "/canonical"} (:redirect (settled-response f))))
      ;; DEV ARM — programmer advice, as for multiple-status-set.
      (when rf.interop/debug-enabled?
        (is (some (fn [ev]
                    (and (= :rf.warning/multiple-redirects (:operation ev))
                         (= 2 (count (:writes (:tags ev))))
                         (= {:status 301 :location "/canonical"} (:final-redirect (:tags ev)))
                         (= :warned-and-replaced (:recovery ev))))
                  @traces)
            (pr-str (mapv :operation @traces)))))))

;; ===========================================================================
;; Cookies and headers land as structured data
;; ===========================================================================

(deftest ssr-multi-cookie
  (testing "set-cookie fx accumulate, in order, as the structured maps given —
            serialisation is the host adapter's job (Spec 011 §Cookie shape)"
    (let [cookies [{:name "session" :value "abc123" :path "/"
                    :http-only true :secure true :same-site :lax}
                   {:name "csrf" :value "tok-xyz" :path "/" :secure true}
                   {:name "tracker" :value "off" :max-age 0}]]
      (rf/reg-event :auth/establish
        (fn [_ _] {:fx (mapv (fn [c] [:rf.server/set-cookie c]) cookies)}))
      (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
        (rf/dispatch-sync [:auth/establish] {:frame f})
        (is (= cookies (:cookies (settled-response f))))))))

(deftest ssr-delete-cookie
  (testing ":rf.server/delete-cookie writes a :max-age 0, empty-:value marker
            carrying :path, so the browser scope-matches"
    (rf/reg-event :auth/logout
      (fn [_ _] {:fx [[:rf.server/delete-cookie {:name "session" :path "/"}]]}))
    (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf/dispatch-sync [:auth/logout] {:frame f})
      (is (= [{:name "session" :value "" :max-age 0 :path "/"}]
             (:cookies (settled-response f)))))))

(deftest ssr-set-and-append-header
  (testing "set-header replaces case-insensitively; append-header keeps
            duplicates in source order"
    (rf/reg-event :hdr/set-then-replace
      (fn [_ _]
        {:fx [[:rf.server/set-header {:name "X-Foo" :value "first"}]
              [:rf.server/set-header {:name "x-foo" :value "second"}]]}))
    (rf/reg-event :hdr/append-twice
      (fn [_ _]
        {:fx [[:rf.server/append-header {:name "Set-Cookie" :value "a=1"}]
              [:rf.server/append-header {:name "Set-Cookie" :value "b=2"}]]}))
    (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf/dispatch-sync [:hdr/set-then-replace] {:frame f})
      (rf/dispatch-sync [:hdr/append-twice] {:frame f})
      (let [hdrs     (:headers (settled-response f))
            values-of (fn [n] (keep (fn [[k v]] (when (= n (str/lower-case k)) v)) hdrs))]
        (is (= ["second"] (values-of "x-foo")))
        (is (= ["a=1" "b=2"] (values-of "set-cookie")))))))

(deftest default-response-canonical-shape
  (testing "Spec 011 §Status defaults: 200, an HTML content-type, no cookies,
            no redirect"
    (is (= {:status   200
            :headers  [["content-type" "text/html; charset=utf-8"]]
            :cookies  []
            :redirect nil}
           (rf.ssr/default-response)))))

;; ===========================================================================
;; Header-splitting gates. A CR/LF/NUL in a header value or redirect
;; location would split the header on the wire, so the fx boundary throws
;; rather than strip. One predicate (`re-frame.ssr.http-validation`) serves
;; every value gate, so each character is exercised once and each call site
;; once.
;; ===========================================================================

(deftest ssr-header-fx-reject-injection-in-name-and-value
  (testing "set-header / append-header refuse a CR, LF or NUL in :value
            (:rf.error/header-invalid-value) and a :name outside the RFC 7230
            §3.2.6 token grammar (:rf.error/header-invalid-name)"
    (doseq [[fx-id args error-id]
            [[:rf.server/set-header    {:name "X-Probe" :value "cr\rbad"}         :rf.error/header-invalid-value]
             [:rf.server/set-header    {:name "X-Probe" :value "lf\nbad"}         :rf.error/header-invalid-value]
             [:rf.server/set-header    {:name "X-Probe" :value (str "nul" (char 0) "bad")}
              :rf.error/header-invalid-value]
             [:rf.server/append-header {:name "X-Audit" :value "ok\r\nSet-Cookie: forged=1"}
              :rf.error/header-invalid-value]
             [:rf.server/set-header    {:name "X-Test\r\nSet-Cookie: evil=1" :value "ok"}
              :rf.error/header-invalid-name]
             [:rf.server/set-header    {:name "Bad: Name" :value "ok"}            :rf.error/header-invalid-name]
             [:rf.server/set-header    {:name "" :value "ok"}                     :rf.error/header-invalid-name]
             [:rf.server/append-header {:name "X-Audit\r\nSet-Cookie: forged=1" :value "ok"}
              :rf.error/header-invalid-name]]]
      (let [[_ traces] (drive-fx! fx-id args)]
        (expect-fx-error-keyword! traces error-id (pr-str [fx-id args]))))))

(deftest ssr-redirect-crlf-nul-gate-survives-on-both-fx
  (testing "both redirect fx run the shared CR/LF/NUL gate, safe-redirect
            before its own URL gate"
    (doseq [[fx-id loc] [[:rf.server/redirect      "https://example.com\r\nSet-Cookie: stolen=1"]
                         [:rf.server/safe-redirect "https://example.com/a\rb"]]]
      (let [[_ traces] (drive-fx! fx-id {:location loc})]
        (expect-fx-error-keyword! traces :rf.error/redirect-invalid-location
                                  (str fx-id " rejects " (pr-str loc)))))))

(deftest ssr-header-clean-values-still-accepted
  (testing "commas, semicolons, spaces and a TAB are legal header-value
            content; only CR/LF/NUL is banned"
    (rf/reg-event :hdr/clean
      (fn [_ _]
        {:fx [[:rf.server/set-header {:name "Cache-Control" :value "no-cache, must-revalidate, max-age=0"}]
              [:rf.server/set-header {:name "X-Whitespace" :value "tab\there space"}]]}))
    (let [f (rf.frame/make-anon-frame-record! {:platform :server :initial-events [[:hdr/clean]]})]
      (is (every? (set (:headers (settled-response f)))
                  [["Cache-Control" "no-cache, must-revalidate, max-age=0"]
                   ["X-Whitespace" "tab\there space"]])))))

;; ===========================================================================
;; :rf.server/redirect — caller-trusted
;; ===========================================================================

(deftest ssr-redirect-trusted-path-has-no-url-shape-gate
  (testing "the caller-trusted redirect applies no URL-shape, origin or
            relative-only check: these targets, each of which safe-redirect
            would refuse or reshape, pass through verbatim"
    (doseq [loc ["https://example.com/search?q=a b"           ;; raw unencoded space
                 "https://other.example.org:8443/deep/path"   ;; any origin
                 "//cdn.example.com/asset"]]                  ;; protocol-relative
      (rf/reg-event :redirect/trusted
        (fn [_ _] {:fx [[:rf.server/redirect {:location loc}]]}))
      (let [f (rf.frame/make-anon-frame-record! {:platform :server :initial-events [[:redirect/trusted]]})]
        (is (= loc (-> (settled-response f) :redirect :location)))))))

(deftest ssr-redirect-populates-redirect-and-status
  (testing "redirect fills :redirect and flows its :status onto the response.
            Dropping body and payload under a redirect is the host adapter's
            decision (ssr-ring's handler-redirect-short-circuits)"
    (rf/reg-event :auth/check-session
      (fn [_ _] {:fx [[:rf.server/redirect {:status 301 :location "/login"}]]}))
    (let [resp (settled-response (rf.frame/make-anon-frame-record!
                               {:platform :server :initial-events [[:auth/check-session]]}))]
      (is (= {:status 301 :location "/login"} (:redirect resp)))
      (is (= 301 (:status resp)))))

  (testing ":status defaults to 302 (Spec 011 §Redirect)"
    (rf/reg-event :auth/check-no-status
      (fn [_ _] {:fx [[:rf.server/redirect {:location "/login"}]]}))
    (let [f (rf.frame/make-anon-frame-record! {:platform :server :initial-events [[:auth/check-no-status]]})]
      (is (= 302 (-> (settled-response f) :redirect :status))))))

(deftest ssr-redirect-retired-spelling-diagnostic-names-location
  (testing ":url / :to are rejected, not normalised: the diagnostic names the
            canonical :location, and no :redirect lands"
    (doseq [k [:url :to]]
      (let [[f traces] (drive-fx! :rf.server/redirect {k "/welcome" :status 301})]
        (is (= {:rf.error/id :rf.error/redirect-retired-target-key
                :canonical-key :location
                :retired-keys [k]}
               (select-keys (fx-error-extra traces :rf.error/redirect-retired-target-key)
                            [:rf.error/id :canonical-key :retired-keys])))
        (is (nil? (:redirect (settled-response f))))))))

;; ===========================================================================
;; Error projection (Spec 011 §Server error projection)
;; ===========================================================================

(defn- error-record->trace-event
  "The `{:operation :op-type :tags}` envelope the projector consumes, lifted
  from an always-on error record the way the runtime's projection listener
  lifts it."
  [record]
  {:op-type   :error
   :operation (:error record)
   :tags      (-> (dissoc record :error)
                  (update :recovery #(or % :no-recovery)))})

(defn- with-error-capture!
  "Run `body-fn`; return `{:result … :events …}` with every error emitted
  during it, from BOTH axes — the always-on axis is the one a production
  server projects from."
  [body-fn]
  (let [events (atom [])
        tag    (keyword "rf2-lwtlk" (str "err-cap-" (name (gensym "c"))))]
    (rf/register-listener! :trace tag
      (fn [ev] (when (= :error (:op-type ev)) (swap! events conj ev))))
    (rf.error-emit/register-error-listener! tag
      (fn [r] (swap! events conj (error-record->trace-event r))))
    (try
      {:result (body-fn) :events @events}
      (finally
        (rf/unregister-listener! :trace tag)
        (rf.error-emit/unregister-error-listener! tag)))))

(defn- projecting-frame [ssr-config]
  (rf.frame/make-anon-frame-record! {:platform :server :ssr ssr-config}))

(deftest ssr-default-error-projector-handler-exception
  (testing "a handler that throws in a request dispatch (not an
            :initial-events step, which tears the frame down instead) →
            default projector → 500, with no internal detail in prod mode"
    (rf/reg-event :load/article
      (fn [_ _] (throw (ex-info "Database connection failed: SECRET_TOKEN=xyz" {}))))
    (let [f      (projecting-frame {:public-error-id   :rf.ssr/default-error-projector
                                    :dev-error-detail? false})
          events (:events (with-error-capture! #(rf/dispatch-sync [:load/article] {:frame f})))
          err    (some #(when (= :rf.error/handler-exception (:operation %)) %) events)]
      (is (some? err))
      (is (= 500 (:status (settled-response f))))
      (is (= locked-500 (rf.ssr/project-error f err))))))

(deftest ssr-error-projector-dev-mode-includes-details
  (testing ":dev-error-detail? true puts the trace event verbatim under :details"
    (let [trace-event {:operation :rf.error/handler-exception
                       :op-type   :error
                       :tags      {:exception-message "boom" :failing-id :foo}}]
      (is (= (assoc locked-500 :details trace-event)
             (rf.ssr/project-error (projecting-frame {:public-error-id   :rf.ssr/default-error-projector
                                                      :dev-error-detail? true})
                                   trace-event))))))

(deftest ssr-error-projector-throws-falls-back-to-locked-500
  (testing "a throwing projector → the locked 500 + :rf.error/sanitised-on-projection"
    (rf/reg-error-projector :myapp/buggy-projector (fn [_] (throw (ex-info "projector bug" {}))))
    (let [{public :result events :events}
          (with-error-capture!
            #(rf.ssr/project-error (projecting-frame {:public-error-id :myapp/buggy-projector})
                                   {:operation :rf.error/handler-exception :tags {}}))]
      (is (= locked-500 public))
      (is (some #(= :rf.error/sanitised-on-projection (:operation %)) events)))))

(deftest ssr-error-projector-non-conforming-shape-falls-back
  (testing "a projector returning a non-conforming shape → the locked 500 +
            :rf.error/sanitised-on-projection"
    (rf/reg-error-projector :myapp/bad-shape (fn [_] {:wrong :shape}))
    (let [{public :result events :events}
          (with-error-capture!
            #(rf.ssr/project-error (projecting-frame {:public-error-id :myapp/bad-shape})
                                   {:operation :rf.error/handler-exception :tags {}}))]
      (is (= locked-500 public))
      (is (some #(= :rf.error/sanitised-on-projection (:operation %)) events)))))

(deftest ssr-error-projector-configured-but-unregistered-surfaces-diagnostic
  (testing "a configured but unregistered :public-error-id falls back to the
            locked 500 and SURFACES the misconfiguration, on the always-on axis
            too"
    (let [{public :result events :events}
          (with-error-capture!
            #(rf.ssr/project-error (projecting-frame {:public-error-id :myapp/never-registered})
                                   {:operation :rf.error/no-such-handler :tags {}}))
          diag (some #(when (= :rf.error/sanitised-on-projection (:operation %)) %) events)]
      (is (= locked-500 public))
      (is (= {:projection-failure-reason :missing-projector :projector-id :myapp/never-registered}
             (select-keys (:tags diag) [:projection-failure-reason :projector-id])))))

  (testing "with no :public-error-id the built-in default projector honours the
            mapping and emits no diagnostic"
    (let [{public :result events :events}
          (with-error-capture!
            #(rf.ssr/project-error (rf.frame/make-anon-frame-record! {:platform :server})
                                   {:operation :rf.error/no-such-handler :tags {:kind :route}}))]
      (is (= 404 (:status public)))
      (is (not-any? #(= :rf.error/sanitised-on-projection (:operation %)) events)))))

(deftest default-error-projector-fn-maps-all-enumerated-categories
  (testing "the default projector's case table (Spec 011 §Default projector):
            each arm, each gate's boundary, and the fallback"
    (let [not-found   {:status 404 :code :not-found :message "Page not found" :retryable? false}
          bad-request {:status 400 :code :bad-request :message "Invalid input" :retryable? false}]
      (doseq [[trace-event expected]
              [;; the 404 arm is gated on the URL-driven :kind :route miss
               [{:operation :rf.error/no-such-handler :tags {:kind :route}} not-found]
               [{:operation :rf.error/no-such-handler :tags {:kind :event}} locked-500]
               [{:operation :rf.error/no-such-handler} locked-500]
               [{:operation :rf.error/no-such-route} not-found]
               ;; a bad request coeffect is client input, unconditionally
               [{:operation :rf.error/cofx-value-invalid} bad-request]
               ;; the schema 400 arm is gated on the client-surface :where :event
               [{:operation :rf.error/schema-validation-failure :tags {:where :event}} bad-request]
               [{:operation :rf.error/schema-validation-failure :tags {:where :cofx}} locked-500]
               [{:operation :rf.error/schema-validation-failure :tags {:where :fx-args}} locked-500]
               [{:operation :rf.error/handler-exception} locked-500]]]
        (is (= expected (rf.ssr/default-error-projector-fn trace-event))
            (pr-str trace-event))))))

(deftest pure-reads-do-not-drain-the-settle-does
  (testing "peek-response and get-response are pure reads that leave a
            buffered error unprojected; flush-response-result! drains it
            onto :status"
    (rf/reg-route :route/home {} "/")
    (let [f (projecting-frame {:public-error-id   :rf.ssr/default-error-projector
                               :dev-error-detail? false})]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (is (= [200 200] [(:status (rf.ssr/peek-response f)) (:status (rf.ssr/get-response f))]))
      (is (= 404 (:status (:response (rf.ssr/flush-response-result! f))))))))

;; ===========================================================================
;; Hydration verification's host-supplied attribution
;; ===========================================================================

(deftest host-supplied-failing-id-surfaced-on-unified-channel
  ;; DEV ARM — the seam's value is read off the dev-only mismatch trace. Head
  ;; and body share the one render-hash channel, so the runtime itself emits
  ;; :failing-id :rf/hydrate; a host may supply its own attribution.
  (when rf.interop/debug-enabled?
    (testing "a host-supplied :failing-id and :first-diff-path reach the
              hydration-mismatch trace verbatim"
      (let [traces (atom [])
            f      (rf.frame/make-anon-frame-record! {:platform :client})]
        (rf/register-listener! :trace ::head (fn [ev] (swap! traces conj ev)))
        (rf.ssr/verify-hydration! f "head-hash-client-B"
                                  {:server-hash     "head-hash-server-A"
                                   :failing-id      :rf.ssr/head-mismatch
                                   :first-diff-path [:head :title]})
        (rf/unregister-listener! :trace ::head)
        (is (some #(and (= :rf.ssr/hydration-mismatch (:operation %))
                        (= {:server-hash     "head-hash-server-A"
                            :client-hash     "head-hash-client-B"
                            :failing-id      :rf.ssr/head-mismatch
                            :first-diff-path [:head :title]}
                           (select-keys (:tags %) [:server-hash :client-hash
                                                   :failing-id :first-diff-path])))
                  @traces)
            (pr-str (mapv (juxt :operation #(:failing-id (:tags %))) @traces)))))))

;; ===========================================================================
;; The SSR adapter
;; ===========================================================================

(deftest adapter-installs-ssr-render-to-string
  (testing "ssr/adapter is the :rf.adapter/ssr substrate and its
            :render-to-string slot is the production renderer"
    (is (= :rf.adapter/ssr (:kind rf.ssr/adapter)))
    (is (= "<div>smoke</div>" ((:render-to-string rf.ssr/adapter) [:div "smoke"] {})))))

(deftest adapter-render-throws-rf-error-render-on-headless-adapter
  (testing "the :render slot throws :rf.error/render-on-headless-adapter — SSR
            renders to a string only (Spec 006 §Plain-atom adapter)"
    (is (= :rf.error/render-on-headless-adapter
           (try ((:render rf.ssr/adapter) [:div] nil nil)
                nil
                (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e))))))))

;; ===========================================================================
;; :rf.server/safe-redirect — caller-untrusted. Every rejection arm and the
;; record it ships are pinned on the always-on axis in
;; `re-frame.ssr-safe-redirect-production-test`; what stays here is the
;; validation order, the dev trace's raw diagnostics, and the pass shape.
;; ===========================================================================

(defn- safe-redirect-error? [op]
  (and (keyword? op)
       (= "rf.error" (namespace op))
       (str/starts-with? (name op) "safe-redirect-")))

(defn- capture-safe-redirect-traces!
  "`:rf.error/safe-redirect-*` rejections during `body-fn`, from the ALWAYS-ON
  axis only (it fires in both postures, so a count is posture-independent),
  normalised to `{:operation … :tags …}`."
  [body-fn]
  (let [traces (atom [])
        tag    (keyword "rf2-lwtlk" (str "sr-cap-" (name (gensym "c"))))]
    (rf.error-emit/register-error-listener! tag
      (fn [r]
        (when (safe-redirect-error? (:error r))
          (swap! traces conj {:operation (:error r) :tags r}))))
    (try (body-fn) @traces
         (finally (rf.error-emit/unregister-error-listener! tag)))))

(defn- capture-safe-redirect-dev-traces!
  "The dev-trace companion: the trace receives the diagnostics whole, while
  the always-on record deliberately omits `:allowlist`, `:host` and the raw
  `:scheme` (`re-frame.ssr.egress/safe-redirect-record-slots`)."
  [body-fn]
  (let [traces (atom [])
        tag    (keyword "rf2-lwtlk" (str "sr-dev-cap-" (name (gensym "c"))))]
    (rf/register-listener! :trace tag
      (fn [ev]
        (when (safe-redirect-error? (:operation ev))
          (swap! traces conj ev))))
    (try (body-fn) @traces
         (finally (rf/unregister-listener! :trace tag)))))

(deftest safe-redirect-validation-order-scheme-prefix-precedes-parse
  (testing "a location both unparseable and `javascript:`-prefixed reports the
            scheme rejection only — the prefix check runs before the parse and
            short-circuits (Spec 009 §Error event catalogue)"
    (rf/reg-event :sr/order-parse-first
      (fn [_ _] {:fx [[:rf.server/safe-redirect {:location "javascript: not a real url "}]]}))
    (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
          traces (capture-safe-redirect-traces!
                   #(rf/dispatch-sync [:sr/order-parse-first] {:frame f}))]
      (is (= [:rf.error/safe-redirect-scheme-rejected] (mapv :operation traces))))))

(deftest safe-redirect-dev-trace-carries-the-raw-diagnostics
  ;; DEV ARM — these tags are excluded from the always-on record on purpose:
  ;; the raw scheme and rejected host are the caller's unbounded URL text, and
  ;; the allowlist is the app's own security configuration.
  (when rf.interop/debug-enabled?
    (testing "a rejected safe-redirect's dev trace names the raw diagnostics"
      (rf/reg-event :sr/dev-diagnostics
        (fn [_ [_ args]] {:fx [[:rf.server/safe-redirect args]]}))
      (doseq [[args op expected-tags]
              [[{:location "mailto:user@example.com"}
                :rf.error/safe-redirect-scheme-rejected
                {:scheme "mailto"}]
               [{:location "https://evil.example.com/phish" :relative-only? true}
                :rf.error/safe-redirect-host-disallowed
                {:host "evil.example.com"}]
               [{:location "https://evil.example.com/phish"
                 :allow    ["app.example.com" "alt.example.com"]}
                :rf.error/safe-redirect-host-disallowed
                {:host "evil.example.com" :allowlist ["app.example.com" "alt.example.com"]}]]]
        (let [f      (rf.frame/make-anon-frame-record! {:platform :server})
              traces (capture-safe-redirect-dev-traces!
                       #(rf/dispatch-sync [:sr/dev-diagnostics args] {:frame f}))
              ev     (first (filter #(= op (:operation %)) traces))]
          (is (= expected-tags (select-keys (:tags ev) (keys expected-tags)))
              (str (pr-str args) "; saw: " (pr-str (mapv :operation traces)))))))))

(deftest safe-redirect-accepts-normal-relative-path-control
  (testing "CONTROL: a relative path passes :relative-only? and lands with the
            default 302"
    (rf/reg-event :sr/relative-control
      (fn [_ _]
        {:fx [[:rf.server/safe-redirect {:location "/account/settings" :relative-only? true}]]}))
    (let [f (rf.frame/make-anon-frame-record! {:platform :server :initial-events [[:sr/relative-control]]})]
      (is (= {:status 302 :location "/account/settings"} (:redirect (settled-response f)))))))

;; ===========================================================================
;; Tag-name injection (emit). A tag name is written into the markup
;; unescaped, so it is held to the HTML5/SVG/MathML element-name grammar,
;; optionally with one `prefix:` segment.
;; ===========================================================================

(deftest ssr-render-rejects-hostile-tag-keywords
  (testing "the two documented reproductions, the leading-character and empty
            boundaries, and malformed or injected namespaced names throw
            :rf.error/invalid-tag-name"
    (doseq [hostile [(keyword "img src=x onerror=alert(1)")
                     (keyword "div> <script")
                     (keyword "")
                     (keyword "1-leading-digit")
                     (keyword "a:b:c")
                     (keyword "svg:rect onload=x")]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf\.error/invalid-tag-name"
                            (rf.ssr/render-to-string [hostile "x"] {}))
          (pr-str hostile)))))

(deftest ssr-render-accepts-legit-tag-keywords
  (testing "custom-element, camelCase SVG and XML-namespaced names emit, and the
            :tag#id.cls sugar composes with both"
    (doseq [[tree expected]
            [[[:my-component]                 "<my-component></my-component>"]
             [[:foreignObject "a"]            "<foreignObject>a</foreignObject>"]
             [[:div#main.col-12.bold "x"]     "<div id=\"main\" class=\"col-12 bold\">x</div>"]
             [[:svg:rect]                     "<svg:rect></svg:rect>"]
             [[:svg:rect#r.c]                 "<svg:rect id=\"r\" class=\"c\"></svg:rect>"]]]
      (is (= expected (rf.ssr/render-to-string tree {}))))))

;; ===========================================================================
;; Cookie gates. Spec 011 §CRLF fail-fast: every serialised attribute is
;; checked at the fx boundary; :value bans CR/LF/NUL (it is percent-encoded
;; downstream), every verbatim-concatenated attribute also bans `;`. Every
;; failure is :rf.error/cookie-invalid-attribute with the offending
;; :attribute and :value (Spec 009 catalogue).
;; ===========================================================================

(defn- cookie-attribute-error
  "The catalogued `{:attribute :value}` slots of the cookie-attribute
  rejection `fx-id` raised for `args`, or {} when none was raised."
  [fx-id args]
  (let [[_ traces] (drive-fx! fx-id args)]
    (select-keys (fx-error-extra traces :rf.error/cookie-invalid-attribute) [:attribute :value])))

(deftest ssr-set-cookie-crlf-checks-every-attribute
  (testing "a CRLF in :name is :rf.error/cookie-invalid-name (RFC 6265 §4.1.1)"
    (let [[_ traces] (drive-fx! :rf.server/set-cookie
                                {:name "session\r\nSet-Cookie: stolen=1" :value "abc"})]
      (expect-fx-error-keyword! traces :rf.error/cookie-invalid-name "CRLF in :name")))

  (testing ":value's CR/LF/NUL gate, and each of CR, LF and NUL on a
            verbatim attribute (the per-attribute rows are the `;` table)"
    (doseq [[attr hostile] [[:value   "abc\r\nSet-Cookie: stolen=1"]
                            [:max-age "cr\rbad"]
                            [:max-age "lf\nbad"]
                            [:max-age (str "nul" (char 0) "bad")]]]
      (is (= {:attribute attr :value hostile}
             (cookie-attribute-error :rf.server/set-cookie
                                     (assoc {:name "session" :value "x"} attr hostile)))))))

(deftest ssr-set-cookie-rejects-semicolon-attribute-delimiter
  (testing "a raw `;` in any verbatim-concatenated attribute would fabricate
            extra attributes (SameSite=None, Secure, …), so it is rejected"
    (doseq [[attr hostile] [[:path      "/; SameSite=None; Secure"]
                            [:domain    "evil.com; Secure"]
                            [:max-age   "3600; SameSite=None; Secure"]
                            [:same-site "Lax; Secure"]
                            [:expires   "Wed, 09 Jun 2027 10:18:14 GMT; Secure"]]]
      (is (= {:attribute attr :value hostile}
             (cookie-attribute-error :rf.server/set-cookie
                                     (assoc {:name "sid" :value "abc"} attr hostile))))))

  (testing "a `;` in :value is data, stored verbatim — no over-rejection"
    (rf/reg-event :ck/semicolon-value
      (fn [_ _] {:fx [[:rf.server/set-cookie {:name "sid" :value "a;b" :path "/"}]]}))
    (let [f (rf.frame/make-anon-frame-record! {:platform :server :initial-events [[:ck/semicolon-value]]})]
      (is (= ["a;b"] (mapv :value (:cookies (settled-response f)))))))

  (testing "delete-cookie, sugar over set-cookie, gates :path and :domain too"
    (doseq [attr [:path :domain]]
      (is (= {:attribute attr :value "x; Secure"}
             (cookie-attribute-error :rf.server/delete-cookie {:name "sid" attr "x; Secure"}))))))

(deftest ssr-set-cookie-rejects-non-string-name-type
  (testing "a cookie :name that is neither a string nor a Named throws the
            catalogued :rf.error/cookie-invalid-name on the direct fx-handler
            path (where no schema runs), not a raw ClassCastException"
    (let [f (rf.frame/make-anon-frame-record! {:platform :server})]
      (doseq [call [#(rf.ssr.response/set-cookie-fx {:frame f} {:name 42 :value "x"})
                    #(rf.ssr.response/delete-cookie-fx {:frame f} {:name 99 :path "/"})]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf\.error/cookie-invalid-name"
                              (call)))))))

(deftest ssr-set-cookie-clean-attributes-still-accepted
  (testing "an integer :max-age, a string :same-site and a clean :expires
            string pass the attribute gate and land unchanged"
    (let [cookie {:name      "session"
                  :value     "abc123"
                  :max-age   3600
                  :same-site "Strict"
                  :path      "/"
                  :expires   "Wed, 09 Jun 2027 10:18:14 GMT"}]
      (rf/reg-event :ck/clean-attrs (fn [_ _] {:fx [[:rf.server/set-cookie cookie]]}))
      (let [f (rf.frame/make-anon-frame-record! {:platform :server :initial-events [[:ck/clean-attrs]]})]
        (is (= [cookie] (:cookies (settled-response f))))))))

;; ===========================================================================
;; ssr-server-fx-args-schema-boundary — a TWO-POSTURE CONTRACT.
;;
;; A malformed `:rf.server/*` fx never reaches the response in any build, but
;; by different routes: in dev with schemas live the Spec 010 step-5 boundary
;; rejects first with the rich `:rf.error/schema-validation-failure :where
;; :fx-args`; in production step 5 is compiled out, the reserved fx's own
;; guard throws, and `re-frame.fx` containment fans an always-on
;; `:rf.error/fx-handler-exception`. The guards' accept/refuse corpus, read
;; on the pure accumulator, is `re-frame.ssr-reserved-fx-guards-test`.
;; ===========================================================================

(def ^:private malformed-server-fx
  "`[label fx-id fx-vec]` for each malformed reserved-fx call."
  [[":rf.server/set-status with a non-int arg"
    :rf.server/set-status    [:rf.server/set-status "not-an-int"]]
   [":rf.server/set-header missing :value"
    :rf.server/set-header    [:rf.server/set-header {:name "X-Foo"}]]
   [":rf.server/append-header with a non-string :value"
    :rf.server/append-header [:rf.server/append-header {:name "X-Bar" :value 42}]]
   [":rf.server/set-cookie missing :value"
    :rf.server/set-cookie    [:rf.server/set-cookie {:name "session"}]]
   [":rf.server/set-cookie with a bogus :same-site keyword"
    :rf.server/set-cookie    [:rf.server/set-cookie {:name "s" :value "v" :same-site :bogus}]]
   [":rf.server/delete-cookie missing :name"
    :rf.server/delete-cookie [:rf.server/delete-cookie {:path "/"}]]
   [":rf.server/redirect with a non-int :status"
    :rf.server/redirect      [:rf.server/redirect {:location "/x" :status "oops"}]]
   [":rf.server/safe-redirect with a non-int :status"
    :rf.server/safe-redirect [:rf.server/safe-redirect {:location "/ok" :status "not-int"}]]
   [":rf.server/safe-redirect missing :location"
    :rf.server/safe-redirect [:rf.server/safe-redirect {:status 302}]]])

(defn- drive-server-fx!
  "Dispatch `fx-vec` on a fresh server frame; return the public response
  (after the settle drains the projection), the dev schema-failure
  traces, and every always-on error record."
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
      {:response   (settled-response f)
       :dev-traces @dev
       :records    @records}
      (finally
        (rf/unregister-listener! :trace tag)
        (rf.error-emit/unregister-error-listener! tag)))))

(defn- fx-args-failure-for? [fx-id ev]
  (and (= :fx-args (-> ev :tags :where))
       (= fx-id (-> ev :tags :failing-id))))

(deftest ssr-server-fx-args-schema-boundary
  (testing "POSTURE-INDEPENDENT: a malformed set-status never reaches the
            wire — it surfaces as the locked 500, because the default
            projector's 400 arm is for client-surface failures and a server
            fx built by a server handler is a server defect"
    (is (= 500 (:status (:response (drive-server-fx! [:rf.server/set-status "not-an-int"]))))))

  (when rf.interop/debug-enabled?
    (testing "DEV ARM — step 5 rejects first, naming the fx and :where :fx-args"
      (doseq [[label fx-id fx-vec] malformed-server-fx]
        (let [traces (:dev-traces (drive-server-fx! fx-vec))]
          (is (some #(fx-args-failure-for? fx-id %) traces)
              (str label "; saw: " (pr-str (mapv (comp (juxt :where :failing-id) :tags) traces)))))))

    (testing "DEV ARM — the permissive redirect schema admits the no-target
              redirect (the documented warn→302 path the host adapter owns)"
      (let [traces (:dev-traces (drive-server-fx! [:rf.server/redirect {:status 302}]))]
        (is (not-any? #(fx-args-failure-for? :rf.server/redirect %) traces)))))

  (when-not rf.interop/debug-enabled?
    (testing "PRODUCTION ARM — the reserved fx's own guard throws and an
              always-on :rf.error/fx-handler-exception names the fx, which is
              what an off-box shipper receives"
      (doseq [[label fx-id fx-vec] malformed-server-fx]
        (let [{:keys [records]} (drive-server-fx! fx-vec)]
          (is (some #(and (= :rf.error/fx-handler-exception (:error %))
                          (= fx-id (:failing-id %)))
                    records)
              (str label "; saw: " (pr-str (mapv (juxt :error :failing-id) records)))))))))

;; ===========================================================================
;; ssr-with-fx-override / ssr-end-to-end — the :fx-overrides stub and the
;; dispatch-sync → render-to-string → embedded-hash flow on the default
;; frame, without the per-request frame.
;; ===========================================================================

(deftest ssr-with-fx-override
  (testing ":fx-overrides redirects :http/get to a stub whose reply lands in
            the frame's app-db"
    (reg-canned-http-get!)
    (rf/reg-event :rf/server-init
      (fn [_ _] {:fx [[:http/get {:url "/api/articles" :on-success [:articles/loaded]}]]}))
    (rf/reg-event :articles/loaded
      (fn [{:keys [db]} [_ articles]] {:db (assoc db :articles articles)}))
    (let [f (rf.frame/make-anon-frame-record!
              {:initial-events [[:rf/server-init]]
               :fx-overrides   {:http/get :http/get.canned-articles}})]
      (is (= canned-articles (:articles (rf/app-db-value f)))))))

(deftest ssr-end-to-end
  (testing "dispatch-sync → render-to-string → embedded hash"
    (rf/reg-event :articles/seed (fn [_ _] {:db {:articles canned-articles}}))
    (reg-articles-view!)
    (rf/dispatch-sync [:articles/seed])
    (let [tree [(rf/view :pages/articles)]
          html (rf.ssr/render-to-string tree {:render-hash (rf.ssr/render-tree-hash tree)})]
      (is (str/includes? html "<h3>Article A</h3>"))
      (is (re-find #"<div[^>]*data-rf-render-hash=\"[0-9a-f]{8}\"" html)
          "root <div> carries a data-rf-render-hash attribute"))))
