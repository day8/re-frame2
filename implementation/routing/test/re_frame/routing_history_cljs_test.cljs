(ns re-frame.routing-history-cljs-test
  "CLJS tests for routing's browser-history surface on the node test target:
  the outbound `:rf.nav/push-url` / `:rf.nav/replace-url` fx and how a
  throwing history mutation is reported, the popstate / hashchange listener
  a `:url-bound? true` frame's lifecycle installs and removes, listener
  reconciliation when URL ownership or the owner's strategy changes, the
  live-window open-redirect classifier, URL restoration around a blocked popstate, and the
  registration-time strategy preflight. The fx-failure and preflight tests
  read the trace stream, which delivers in dev builds, and the fx-failure
  test reads the always-on error channel too.

  Node has no `window`, so the shared `with-window-stub-fixture` installs a
  stub that records `pushState` / `replaceState` onto an in-memory entry
  stack (`*history-state*`) and exposes `back` / `forward` / `dispatchEvent`.

  Per Spec 012 §URL changes are events, §Multi-frame routing, §Scroll
  restoration."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.url :as rf.routing.url]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.routing-browser-test-support
             :refer [*history-state* current-url with-window-stub-fixture]])
  (:require-macros [re-frame.test-support :refer [with-emit-recorder!]]))

(use-fixtures :each
  with-window-stub-fixture
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     ;; The scroll-position cache is a module-level host atom the runtime
     ;; reset does not touch, so drop it here.
     :init-fn (fn []
                (rf.routing/reset-counters!)
                (rf.routing/reset-scroll-cache!))}))

(defn- register-routes! []
  ;; The fixture's :rf/default carries no :url-bound?, so opt it in as this
  ;; suite's URL owner; without it the history fx never fire.
  (rf/make-frame {:id :rf/default :url-bound? true})
  (rf/reg-route :hist/home     {} "/")
  (rf/reg-route :hist/cart     {} "/cart")
  (rf/reg-route :hist/checkout {} "/checkout")
  (rf/reg-route :hist/article  {:params [:map [:id :string]]} "/articles/:id"))

(defn- route-id [frame-id]
  (:route-id (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
                     [:rf.runtime/routing :current])))

;; =========================================================================
;; The open-redirect gate on the live-window path
;; =========================================================================

(deftest navigate-non-string-url-fails-closed-cljs-rf2-w3qgc
  (testing "with a live window, a non-string :url classes EXTERNAL and is
            never pushed: js/URL would stringify it (`new URL(null, base)` is
            `/null`) and class it same-origin"
    (register-routes!)
    (rf/dispatch-sync [:rf.route/navigate {:url "/cart"}])
    (is (= :hist/cart (route-id :rf/default)) "precondition: the slice is on /cart")
    (let [entries (:entries @*history-state*)]
      (doseq [bad [nil (js-obj "toString" (fn [] "/checkout"))]]
        (rf/dispatch-sync [:rf.route/navigate {:url bad}])
        (is (= [true bad entries :hist/cart]
               [(rf.routing.url/external-url? bad)
                (rf.routing.url/request-url->app-url bad "/" identity)
                (:entries @*history-state*)
                (route-id :rf/default)])
            (str "non-string url " (pr-str bad)
                 " classes external, is left unchanged, pushes nothing and leaves the slice"))))))

(deftest external-url-browser-protocol-allowlist-cljs-rf2-aftbmz
  (testing "the live-window classifier fails closed through both clauses of
            its `or`: A, the http(s) protocol allowlist, and B, the origin
            compare"
    (let [doc-origin (.-origin (.-location js/globalThis.window))]
      (is (true? (rf.routing.url/external-url? (str "blob:" doc-origin "/1234-uuid")))
          "a same-origin blob: URL: the origins match, so clause A alone catches it")
      (is (true? (rf.routing.url/external-url? "//evil.example/x"))
          "a protocol-relative off-origin URL: clause B catches it"))))

;; `:rf.route/navigate {:url …}` reduces an accepted raw reference to the
;; location the browser will reach BEFORE matching
;; (`request-url->app-url`), so the committed route, its
;; params and the pushed entry describe one location.
(deftest navigate-raw-url-normalizes-before-matching-cljs-rf2-fzbj-12
  (register-routes!)
  (let [doc-origin (.-origin (.-location js/globalThis.window))
        current    (fn [] (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                  [:rf.runtime/routing :current]))]
    (testing "an origin-bearing spelling resolves to its location's route, params
              and history entry"
      (rf/dispatch-sync [:rf.route/navigate {:url (str doc-origin "/articles/ok")}])
      (is (= [:hist/article {:id "ok"} "/articles/ok"]
             [(:route-id (current)) (:params (current)) (current-url *history-state*)])))
    (testing "query-only and fragment-only references resolve against the
              current document"
      (rf/dispatch-sync [:rf.route/navigate {:to :hist/cart}])
      (rf/dispatch-sync [:rf.route/navigate {:url "?q=1"}])
      (is (= [:hist/cart {"q" "1"} "/cart?q=1"]
             [(:route-id (current)) (:query (current)) (current-url *history-state*)]))
      (rf/dispatch-sync [:rf.route/navigate {:url "#frag"}])
      (is (= [:hist/cart "frag" "/cart?q=1#frag"]
             [(:route-id (current)) (:fragment (current)) (current-url *history-state*)])))
    (testing "an unmatched normalised reference names the browser's location in
              both the not-found params and the history entry"
      (rf/dispatch-sync [:rf.route/navigate {:url "/x/../no/such/path"}])
      (is (= [:rf.route/not-found {:url "/no/such/path"} "/no/such/path"]
             [(:route-id (current)) (:params (current)) (current-url *history-state*)])))))

(deftest scroll-position-captured-before-forward-nav-cljs
  (testing "leaving a route captures the browser scroll position under that
            route's URL, in the frame's host cache"
    (register-routes!)
    (rf/dispatch-sync [:rf.route/navigate {:url "/cart"}])
    (.scrollTo js/globalThis.window 12 345)
    (rf/dispatch-sync [:rf.route/navigate {:url "/checkout"}])
    (is (= [12 345]
           (rf.routing/lookup-scroll-position
             (rf.routing/frame-scroll-cache :rf/default)
             "/cart")))))

;; =========================================================================
;; URL ownership and the listener lifecycle
;; =========================================================================

(deftest a-refused-second-owner-leaves-the-listener-cljs
  (testing "a second :url-bound? true frame is refused at the preflight, so
            the owner's listener instance stays, alone"
    (register-routes!)
    (let [incumbent (first (get-in @*history-state* [:listeners "popstate"]))]
      (is (= :rf.error/duplicate-url-binding
             (:rf.error/id (ex-data (try (rf/make-frame {:id :aaa-early :url-bound? true})
                                         nil
                                         (catch :default e e))))))
      (is (nil? (rf.frame/frame-meta :aaa-early)))
      (is (= [incumbent] (get-in @*history-state* [:listeners "popstate"]))))))

(deftest destroying-the-owner-removes-the-listener-cljs
  (testing "destroying the URL owner leaves no owner, so no listener"
    (rf/reg-route :hist/home {} "/")
    (rf/make-frame {:id :owner/a :url-bound? true})
    (is (= 1 (count (get-in @*history-state* [:listeners "popstate"]))))
    (rf/destroy-frame! :owner/a)
    (is (nil? (rf.routing/url-owner-frame-id)))
    (is (empty? (get-in @*history-state* [:listeners "popstate"])))))

(deftest url-ownership-handoff-cross-strategy-cljs-rf2-3fc89f-11
  (testing "when the history owner opts out by re-registering :url-bound?
            false, its popstate comes down; a hash frame then declaring the
            binding installs exactly one hashchange"
    (rf/reg-route :hist/home   {} "/")
    (rf/reg-route :hist/active {} "/active")
    (rf/make-frame {:id :owner/a :url-bound? true})
    (rf/make-frame {:id :owner/a :url-bound? false})
    (is (empty? (get-in @*history-state* [:listeners "popstate"]))
        "A's popstate is torn down")
    (rf/make-frame {:id :owner/b :url-bound? true :url-strategy rf.routing/hash-url-strategy})
    (is (= [0 1] [(count (get-in @*history-state* [:listeners "popstate"]))
                  (count (get-in @*history-state* [:listeners "hashchange"]))])
        "exactly one hashchange installed")
    (.pushState js/globalThis.window.history nil "" "#/active")
    (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
    (is (= :hist/active (route-id :owner/b))
        "the hashchange listener decodes #/active and drives B")))

(deftest same-owner-strategy-change-rewires-once-cljs-rf2-3fc89f-11
  (testing "re-registering the owner with a changed :url-strategy rewires
            once: the popstate is torn down and exactly one hashchange installed"
    (rf/reg-route :hist/home {} "/")
    (rf/make-frame {:id :owner/a :url-bound? true})
    (rf/make-frame {:id :owner/a :url-bound? true :url-strategy rf.routing/hash-url-strategy})
    (is (= [0 1] [(count (get-in @*history-state* [:listeners "popstate"]))
                  (count (get-in @*history-state* [:listeners "hashchange"]))])
        "rewired once, not stacked")))

(deftest destroying-non-owner-leaves-incumbent-listener-untouched-cljs-rf2-3fc89f-11
  (testing "destroying a non-owner frame leaves the owner's listener
            instance in place: no tear-down and reinstall, no stacking"
    (rf/reg-route :hist/home {} "/")
    (rf/make-frame {:id :owner/a :url-bound? true})
    (rf/make-frame {:id :owner/b})
    (let [incumbent (first (get-in @*history-state* [:listeners "popstate"]))]
      (rf/destroy-frame! :owner/b)
      (is (= [incumbent] (get-in @*history-state* [:listeners "popstate"]))))))

;; =========================================================================
;; Registration-time strategy preflight
;; =========================================================================

(deftest invalid-strategy-reregistration-rejects-and-preserves-listener-cljs-rf2-j538f7-11
  (testing "re-registering the URL owner with a strategy missing the CLJS
            browser legs fails loud at the registration-time preflight, before
            any write: the frames-registry record and the listener instance
            survive, and no :rf.frame/re-registered trace fires"
    (rf/reg-route :hist/home {} "/")
    (rf/make-frame {:id :owner/a :url-bound? true})
    (let [incumbent     (first (get-in @*history-state* [:listeners "popstate"]))
          record-before (get @rf.frame/frames :owner/a)
          captured      (atom [])
          cb-key        (keyword (gensym "j538f7-rereg-trace-"))]
      (rf.trace.tooling/register-listener! cb-key (fn [ev] (swap! captured conj ev)))
      (try
        ;; Valid on the JVM (encode + decode), but missing the :push! /
        ;; :replace! / :install-listener! legs the CLJS host requires.
        (let [ex (try
                   (rf/make-frame {:id :owner/a :url-bound?   true
                                   :url-strategy {:encode identity
                                                  :decode (constantly "/")}})
                   nil
                   (catch :default e e))]
          (is (= [:rf.error/invalid-url-strategy :owner/a]
                 ((juxt :rf.error/id :frame) (ex-data ex)))))
        (is (identical? record-before (get @rf.frame/frames :owner/a))
            "the frames-registry record is the same object: the frames swap never ran")
        (is (empty? (filter #(= :rf.frame/re-registered (:operation %)) @captured))
            "no :rf.frame/re-registered trace fired")
        (is (= [incumbent] (get-in @*history-state* [:listeners "popstate"]))
            "the working popstate listener survived, alone")
        (finally
          (rf.trace.tooling/unregister-listener! cb-key))))))

(deftest first-registration-preflight-zero-residue-make-frame-1-arity-cljs-rf2-ktmto9
  (testing "a URL owner's first rf/make-frame with a malformed custom
            :url-strategy fails at the registration-time preflight with zero
            residue: no :initial-events run, no trace-policy write, no trace
            event"
    (let [probe    (atom 0)
          captured (atom [])
          cb-key   (keyword (gensym "ktmto9-first-trace-"))]
      (rf/reg-event :ktmto9/probe!
                    (fn [{:keys [db]} _] (swap! probe inc) {:db db}))
      (rf.trace.tooling/register-listener! cb-key (fn [ev] (swap! captured conj ev)))
      (try
        (let [ex (try (rf/make-frame {:id                      :ktmto9/bad-first
                                      :url-bound?              true
                                      :url-strategy            {:decode (constantly "/")}
                                      :rf.trace/frame-no-emit? true
                                      :initial-events          [[:ktmto9/probe!]]})
                      nil
                      (catch :default e e))]
          (is (= [:rf.error/invalid-url-strategy :ktmto9/bad-first]
                 ((juxt :rf.error/id :frame) (ex-data ex)))))
        (is (zero? @probe) "the :initial-events step never ran")
        (is (not (rf.trace/frame-trace-disabled? :ktmto9/bad-first))
            "the :rf.trace/frame-no-emit? flag was never written")
        (is (empty? (filter #(= :ktmto9/bad-first (get-in % [:tags :frame])) @captured))
            "no trace event mentions the failed frame")
        (finally
          (rf.trace.tooling/unregister-listener! cb-key))))))

(deftest throwing-installer-preserves-incumbent-listener-cljs-rf2-ktmto9
  (testing "a shape-valid strategy whose :install-listener! throws passes the
            static preflight (it never runs legs) and fails loud in the
            post-registration reconcile; the replacement installs before the
            incumbent is torn down, so the incumbent survives and still drives
            the owner"
    (rf/reg-route :hist/home {} "/")
    (rf/reg-route :hist/cart {} "/cart")
    (rf/make-frame {:id :owner/a :url-bound? true})
    (let [incumbent (first (get-in @*history-state* [:listeners "popstate"]))
          throwing  (merge rf.routing/history-url-strategy
                           {:install-listener! (fn [_on-change]
                                                 (throw (ex-info "installer boom" {})))})]
      (is (some? (try (rf/make-frame {:id :owner/a :url-bound? true :url-strategy throwing})
                      nil
                      (catch :default e e)))
          "the throwing installer propagates")
      (is (= [incumbent] (get-in @*history-state* [:listeners "popstate"]))
          "the incumbent popstate listener survived the failed handoff, alone")
      (rf/dispatch-sync [:rf.route/navigate {:to :hist/cart}] {:frame :owner/a})
      (.back (.-history js/globalThis.window))
      (.dispatchEvent js/globalThis.window #js {:type "popstate"})
      (is (= :hist/home (route-id :owner/a))
          "Back drives A through the surviving listener"))))

;; =========================================================================
;; A blocked popstate restores the URL; continue moves it on
;; =========================================================================
;;
;; A popstate block finds the address bar already on the rejected URL, so the
;; runtime restores it to the slice's URL by replace and records
;; `:url-restored?`; `:rf.route/continue` then replays with `:replace? true`, so
;; the resumed navigation adds no second entry on top of the one the browser
;; is sitting on.

(deftest blocked-popstate-continue-restores-url-cljs
  (testing "a :can-leave block on a Back popstate restores the address bar;
            continue then moves it to the requested URL by replace, so slice
            and URL agree with no new history entry"
    (rf/make-frame {:id :rf/default :url-bound? true})
    (rf/reg-route :hist/cart   {} "/cart")
    (rf/reg-route :hist/editor {:params    [:map [:id :string]]
                                :can-leave :hist/can-leave?} "/editor/articles/:id")
    (rf/reg-event :hist/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :hist/can-leave?
                (fn [db _] (not (boolean (get-in db [:editor :dirty?])))))
    (rf/dispatch-sync [:rf.route/navigate {:url "/cart"}])
    (rf/dispatch-sync [:rf.route/navigate {:url "/editor/articles/X"}])
    (rf/dispatch-sync [:hist/dirty true])
    (.back (.-history js/globalThis.window))
    (rf/dispatch-sync [:rf.route/handle-url-change (current-url *history-state*)])
    (is (= "/editor/articles/X" (current-url *history-state*))
        "the block restored the address bar to the editor route")
    (let [pending        (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/routing :pending-navigation])
          entries-before (count (:entries @*history-state*))]
      (is (true? (:url-restored? pending))
          "the pending navigation records the restore")
      (rf/dispatch-sync [:rf.route/continue (:id pending)])
      (is (= [:hist/cart "/cart" entries-before]
             [(route-id :rf/default)
              (current-url *history-state*)
              (count (:entries @*history-state*))])
          "continue commits /cart and moves the address bar there by replace"))))

;; =========================================================================
;; A throwing pushState / replaceState is reported as an fx error
;; =========================================================================
;;
;; A browser throw from either history fx reaches the fx runner, the second
;; line of defence behind the sinks' `external-url?` gate: the entry's one
;; outcome is the always-on `:rf.error/fx-handler-exception`, and the drain
;; survives.

(defn- with-throwing-history-method!
  "Swap the stub history `method` (pushState or replaceState) for one that
  throws `message`, run `thunk`, then restore it."
  [method message thunk]
  (let [history  (.-history js/globalThis.window)
        original (aget history method)]
    (aset history method
          (fn [& _]
            (throw (js/Error. message))))
    (try
      (thunk)
      (finally
        (aset history method original)))))

(defn- with-history-fx-outcomes
  "Run `thunk` and return the tags of every fx outcome trace it emitted for
  `:rf.nav/push-url` or `:rf.nav/replace-url`, each carrying its
  `:operation`."
  [thunk]
  (let [captured (atom [])
        cb-key   (keyword (gensym "fx-outcome-"))]
    (rf.trace.tooling/register-listener!
      cb-key
      (fn [ev]
        (when (and (#{:rf.fx/handled :rf.fx/skipped-on-platform
                      :rf.error/fx-handler-exception}
                    (:operation ev))
                   (#{:rf.nav/push-url :rf.nav/replace-url}
                    (get-in ev [:tags :rf.fx/id])))
          (swap! captured conj (assoc (:tags ev) :operation (:operation ev))))))
    (try
      (thunk)
      @captured
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

(deftest history-fx-throwing-browser-mutation-is-an-fx-error-cljs
  (testing "each history fx's throwing pushState / replaceState reports one
            outcome, the always-on :rf.error/fx-handler-exception, and no
            :rf.fx/handled; no exception escapes the drain"
    (register-routes!)
    ;; The failed push still commits the slice on /cart (state first), so the
    ;; :replace? navigation after it routes through :rf.nav/replace-url.
    (doseq [[method message event fx-id url]
            [["pushState" "boom-push" [:rf.route/navigate {:url "/cart"}]
              :rf.nav/push-url "/cart"]
             ["replaceState" "boom-replace" [:rf.route/navigate {:to :hist/checkout :replace? true}]
              :rf.nav/replace-url "/checkout"]]]
      (with-emit-recorder! [errors {:pred #(= :rf.error/fx-handler-exception
                                              (:rf.error/id %))}]
        (is (= [[:rf.error/fx-handler-exception fx-id url message]]
               (mapv (juxt :operation :rf.fx/id :rf.fx/args :exception-message)
                     (with-history-fx-outcomes
                       ;; A leaked throw errors the deftest.
                       #(with-throwing-history-method! method message
                          (fn [] (rf/dispatch-sync event))))))
            method)
        (is (= [[fx-id :rf/default]]
               (mapv (juxt :failing-id :frame) @errors))
            (str method " reaches the always-on error channel"))))))
