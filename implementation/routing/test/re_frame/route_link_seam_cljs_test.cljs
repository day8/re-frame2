(ns re-frame.route-link-seam-cljs-test
  "The substrate-neutral link seam (rf2-vxgfnd.95.5) — `rf.routing.link/link-model` +
  `rf.routing.link/activate-link!`. These are the two routing-owned late-bound hooks a
  view artefact's own route-link consumes so that artefact
  reimplements NONE of the routing link law.

  `link-model` (pure) is asserted for href synthesis, dispatch-payload shape,
  and native-anchor detection; `activate-link!` is asserted for the full
  router-attributed click decision (caller `:on-click` first, modifier / native
  deferral, `.preventDefault` + dispatch to the CAPTURED render frame stamped
  `:source :router`). The click law itself is the SAME `plain-left-click?` /
  `native-anchor?` the `:route/link` view uses (route_link_cljs_test); this file
  pins the SEAM the ui view rides.

  Per Spec 012 §Linking from views and the rf2-5yovjt ruling."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn rf.routing/reset-counters!}))

(defn- mk-event
  [{:keys [button meta ctrl shift alt default-prevented]
    :or {button 0 meta false ctrl false shift false alt false
         default-prevented false}}]
  (let [o #js {:button button :metaKey meta :ctrlKey ctrl :shiftKey shift
               :altKey alt :defaultPrevented default-prevented}]
    (set! (.-preventDefault o) (fn [] (set! (.-defaultPrevented o) true)))
    o))

(defn- activate!
  "Run `rf.routing.link/activate-link!` against a synthetic event; capture whether a
  `:rf.route/url-requested` was dispatched, its `:source` tag and target
  `:frame`, and whether preventDefault fired."
  [{:keys [event on-click render-frame payload native?]}]
  (let [dispatched (atom nil)
        source     (atom nil)
        frame-tag  (atom nil)
        cb-key     (keyword (gensym "seam-capture-"))]
    (rf.trace.tooling/register-listener!
      cb-key
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/url-requested (-> ev :tags :rf.event/v first)))
          (reset! dispatched (-> ev :tags :rf.event/v))
          (reset! source (:source ev))
          ;; :frame rides on :tags (only :source is hoisted top-level, per Spec 009)
          (reset! frame-tag (-> ev :tags :frame)))))
    (try
      (rf.routing.link/activate-link! event on-click render-frame payload native?)
      {:dispatched @dispatched :source @source :frame @frame-tag
       :prevented? (.-defaultPrevented event)}
      (finally (rf.trace.tooling/unregister-listener! cb-key)))))

;; ---------------------------------------------------------------------------
;; link-model — the pure routing calculation
;; ---------------------------------------------------------------------------

(deftest link-model-synthesises-href-and-payload
  (rf/reg-route :route/cart {} "/cart")
  (rf/reg-route :route/article {:params [:map [:id :string]]
                                :query  [:map [:tab [:enum :summary :details]]]}
                "/articles/:id")
  (testing "href + payload for a plain route (history default → path-form)"
    (let [{:keys [href payload native?]} (rf.routing.link/link-model {:to :route/cart} nil)]
      (is (= "/cart" href))
      (is (false? native?))
      (is (= [:rf.route/url-requested {:url "/cart" :to :route/cart}] payload))))
  (testing "params + query synthesise into href AND payload (route-url includes the query string, matching rf/route-link)"
    (let [{:keys [href payload]} (rf.routing.link/link-model {:to :route/article
                                                   :params {:id "intro"}
                                                   :query {:tab :summary}}
                                                  nil)]
      (is (= "/articles/intro?tab=summary" href))
      (is (= {:url "/articles/intro?tab=summary" :to :route/article
              :params {:id "intro"} :query {:tab :summary}}
             (second payload)))))
  ;; rf2-e9974: `:fragment` is the one payload slot neither link surface had an
  ;; assertion for, and it is now synthesised by the SHARED
  ;; `url-requested-payload` that `rf/route-link` also runs — so the slot is
  ;; pinned here and at the `rf/route-link` click (see
  ;; `plain-left-click-passes-params-query-and-fragment`).
  (testing "a fragment rides the payload as well as the href"
    (let [{:keys [href payload]} (rf.routing.link/link-model {:to :route/cart :fragment "totals"} nil)]
      (is (= "/cart#totals" href))
      (is (= {:url "/cart#totals" :to :route/cart :fragment "totals"}
             (second payload))))))

(deftest link-model-detects-native-anchors
  (rf/reg-route :route/cart {} "/cart")
  (testing "target=_blank / _top / a download name mark the anchor native"
    (is (true? (:native? (rf.routing.link/link-model {:to :route/cart :target "_blank"} nil))))
    (is (true? (:native? (rf.routing.link/link-model {:to :route/cart :target "_top"} nil))))
    (is (true? (:native? (rf.routing.link/link-model {:to :route/cart :download "f.pdf"} nil)))))
  (testing "target=_self / no native attrs stay interceptable"
    (is (false? (:native? (rf.routing.link/link-model {:to :route/cart :target "_self"} nil))))
    (is (false? (:native? (rf.routing.link/link-model {:to :route/cart} nil))))))

;; ---------------------------------------------------------------------------
;; activate-link! — the router-attributed click decision
;; ---------------------------------------------------------------------------

(deftest activate-plain-left-click-dispatches-to-render-frame
  (rf/reg-route :route/cart {} "/cart")
  (rf/make-frame {:id :frame/main :initial-events [[:rf/set-db {}]]})
  (let [model   (rf.routing.link/link-model {:to :route/cart} :frame/main)
        {:keys [dispatched source frame prevented?]}
        (activate! {:event (mk-event {}) :on-click nil
                    :render-frame :frame/main
                    :payload (:payload model) :native? (:native? model)})]
    (is prevented? "plain left-click prevents default")
    (is (= [:rf.route/url-requested {:url "/cart" :to :route/cart}] dispatched))
    (is (= :router source) "the click stamps :source :router (rf2-t1lxr / rf2-1ve9h)")
    (is (= :frame/main frame)
        "dispatch targets the captured render frame verbatim (committed-frame target)")))

(deftest activate-modifier-and-middle-clicks-defer
  (rf/reg-route :route/cart {} "/cart")
  (let [model (rf.routing.link/link-model {:to :route/cart} nil)
        defer (fn [ev] (activate! {:event ev :on-click nil :render-frame nil
                                   :payload (:payload model) :native? false}))]
    (doseq [ev [(mk-event {:meta true}) (mk-event {:ctrl true})
                (mk-event {:shift true}) (mk-event {:alt true})
                (mk-event {:button 1})]]
      (let [{:keys [dispatched prevented?]} (defer ev)]
        (is (not prevented?) "modifier / auxiliary click leaves the click for the browser")
        (is (nil? dispatched) "no :rf.route/url-requested dispatch")))))

(deftest activate-native-anchor-defers
  (rf/reg-route :route/cart {} "/cart")
  (let [model (rf.routing.link/link-model {:to :route/cart :target "_blank"} nil)
        {:keys [dispatched prevented?]}
        (activate! {:event (mk-event {}) :on-click nil :render-frame nil
                    :payload (:payload model) :native? (:native? model)})]
    (is (not prevented?) "native anchor (target=_blank) defers to the browser even on a plain click")
    (is (nil? dispatched))))

(deftest activate-runs-caller-on-click-first-and-honours-its-veto
  (rf/reg-route :route/cart {} "/cart")
  (let [model  (rf.routing.link/link-model {:to :route/cart} nil)
        order  (atom [])
        ;; the caller's :on-click runs first and vetoes by preventing default
        veto   (fn [e] (swap! order conj :caller) (.preventDefault e))
        {:keys [dispatched prevented?]}
        (activate! {:event (mk-event {}) :on-click veto :render-frame nil
                    :payload (:payload model) :native? (:native? model)})]
    (is (= [:caller] @order) "the caller :on-click ran")
    (is prevented? "defaultPrevented is true — the caller took over")
    (is (nil? dispatched)
        "the framework skipped its interception once the caller prevented default"))
  (testing "a non-vetoing caller :on-click still runs, then the framework dispatches"
    (let [model  (rf.routing.link/link-model {:to :route/cart} nil)
          ran    (atom false)
          {:keys [dispatched prevented?]}
          (activate! {:event (mk-event {}) :on-click (fn [_] (reset! ran true))
                      :render-frame nil :payload (:payload model) :native? false})]
      (is @ran "the caller :on-click ran")
      (is prevented?)
      (is (= :rf.route/url-requested (first dispatched))))))
