(ns re-frame.routing-reply-test
  "Unit tests for `re-frame.routing.reply` — route-loader async work
  lowered onto the uniform reply envelope (EP-0011 §Route Loader
  Completion; rf2-zqefg3.5).

  Pins the route work-id tuple, the nav-token-as-`:suppress`-gate
  delegation to the shared `re-frame.reply` correctness boundary, and the
  data-only stale reply / trace facts joined to `:work/id`. These are
  pure-fn tests over the lowering substrate — no runtime stand-up; the
  end-to-end gate behaviour is exercised by
  `re-frame.routing-nav-token-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.reply :as reply]
            [re-frame.routing.reply :as route-reply]))

;; ---- §Work-id correlation -------------------------------------------------

(deftest route-work-id-tuple-shape
  (testing "the route-loader work-id head is [:rf.work/route route-id nav-token loader-id]"
    (is (= [:rf.work/route :route/article "nav-1" :article/loaded]
           (route-reply/work-id {:route-id  :route/article
                                 :nav-token "nav-1"
                                 :loader-id :article/loaded}))
        "the four-element route head per Managed-Effects §Work-id correlation")
    (is (= [:rf.work/route nil nil nil]
           (route-reply/work-id {}))
        "nil components keep a valid, distinct work id (a loader that named nothing)"))
  (testing "the work id is =-comparable and EDN-serializable"
    (let [wid (route-reply/work-id {:route-id :r :nav-token "n" :loader-id :l})]
      (is (= wid (read-string (pr-str wid)))
          "round-trips through EDN unchanged"))))

;; ---- §Stale suppression — the nav-token is the ONE :suppress gate ---------

(deftest nav-token-gate-shape
  (testing "the carried/current gates are the data-only {:route/nav-token <t>} map"
    (is (= {:route/nav-token "nav-1"} (route-reply/gate "nav-1")))
    (is (= {:route/nav-token "nav-2"} (route-reply/current-gate "nav-2")))))

(deftest suppress?-delegates-to-shared-reply-stale?
  (testing "suppress? is exactly re-frame.reply/stale? over the :route/nav-token gate
            — the route family does NOT re-implement the comparison"
    (doseq [[carried current] [["nav-1" "nav-2"] ["nav-2" "nav-2"] [nil "nav-2"] ["nav-1" nil]]]
      (is (= (reply/stale? (route-reply/gate carried) (route-reply/current-gate current))
             (route-reply/suppress? carried current))
          (str "suppress? matches the shared stale? for carried=" carried " current=" current))))
  (testing "stale when the epoch advanced; live when it matches"
    (is (true?  (route-reply/suppress? "nav-1" "nav-2")) "superseded → stale")
    (is (false? (route-reply/suppress? "nav-2" "nav-2")) "current → live")
    ;; A nil CAPTURED token while a navigation is live is suppressed: the
    ;; gate is present (`{:route/nav-token nil}`) with a nil value, which
    ;; never equals the active token. This matches the original
    ;; `(= nav-token current)` semantics and is the regression guard for
    ;; the pre-fix bug where a cofx threaded nil and was silently eaten —
    ;; nil is now suppressed (not committed) exactly as before.
    (is (true? (route-reply/suppress? nil "nav-2"))
        "a nil captured token under a live navigation is stale (never matches)")
    (is (false? (route-reply/suppress? nil nil))
        "nil captured against a nil current (no navigation) matches — both gates equal")))

;; ---- §Stale suppression — the suppress outcome ----------------------------

(deftest suppress-produces-stale-reply-joined-to-work-id
  (testing "a superseded route completion produces a non-delivered :status :stale
            reply carrying the route :work/id + :work/kind :route, and trace facts
            joined to :work/id with both carried + current gates"
    (let [{:keys [deliver? reply trace] :as outcome}
          (route-reply/suppress {:route-id  :route/article
                                 :nav-token "nav-1"
                                 :loader-id :article/loaded
                                 :frame     :rf/default}
                                "nav-2")]
      (is (false? deliver?) "the app reply target MUST NOT run for a stale completion")
      (is (= :suppressed (:work/status outcome)) "ledger terminal for a stale route load")

      (testing "the reply map is a valid, app-state-safe :status :stale reply"
        (is (reply/valid-reply? reply) "conforms to the reply-map contract")
        (is (= :stale (:status reply)))
        (is (true? (:stale? reply)))
        (is (= :rf.route/nav-token-stale (:stale/reason reply)))
        (is (not (contains? reply :value)) "a stale reply carries no :value (no app mutation)")
        (is (= :route (:work/kind reply)))
        (is (= [:rf.work/route :route/article "nav-1" :article/loaded] (:work/id reply))
            "the reply carries the route work-id")
        (is (= :rf/default (:rf.frame/id reply)) "the carried frame stamp rides the reply"))

      (testing "the trace facts are joined to :work/id and carry both correlation gates"
        (is (true? (:rf.reply/suppressed? trace)))
        (is (= [:rf.work/route :route/article "nav-1" :article/loaded] (:work/id trace))
            "EP-0011 §Route Loader Completion: the suppression trace is joined to :work/id")
        (is (= {:route/nav-token "nav-1"} (:rf.reply/carried trace)) "carried gate")
        (is (= {:route/nav-token "nav-2"} (:rf.reply/current trace)) "current gate")
        (is (= :rf.route/nav-token-stale (:stale/reason trace)))))))

(deftest suppress-carries-completed-at-on-stale-reply
  (testing "rf2-ux8sgg — a route loader that supplies the reply completion
            time (`:completed-at`, the recordable :rf/time-ms fact, EP-0017)
            carries it verbatim onto the stale reply; absence omits it. This
            pins the production lane the pure substrate exposes — the stale
            route reply is tied to the actual replayed completion token."
    (let [{:keys [reply]}
          (route-reply/suppress {:route-id     :route/article
                                 :nav-token    "nav-1"
                                 :loader-id    :article/loaded
                                 :frame        :rf/default
                                 :completed-at 1717000000000}
                                "nav-2")]
      (is (= 1717000000000 (:completed-at reply))
          "the supplied completion time rides the stale reply (not dropped)")
      (is (= :stale (:status reply)) "still a stale reply")
      (is (not (contains? reply :value)) "still app-state-safe (no :value)"))
    (testing "absence omits the slot — a loader that sourced no completion time"
      (let [{:keys [reply]}
            (route-reply/suppress {:route-id  :route/article
                                   :nav-token "nav-1"
                                   :loader-id :article/loaded}
                                  "nav-2")]
        (is (not (contains? reply :completed-at))
            ":completed-at is omitted when the caller supplies none")))))

(deftest suppress-honours-dispatch-stale-opt-in
  (testing "a framework test/tool target may opt into stale delivery via :dispatch-stale?
            (app targets MUST NOT) — delegated to re-frame.reply/suppress"
    (is (false? (:deliver? (route-reply/suppress {:nav-token "nav-1"} "nav-2" nil)))
        "no opt-in → not delivered")
    (is (false? (:deliver? (route-reply/suppress {:nav-token "nav-1"} "nav-2"
                                                 {:event [:t] :dispatch-stale? false})))
        ":dispatch-stale? false → not delivered")
    (is (true? (:deliver? (route-reply/suppress {:nav-token "nav-1"} "nav-2"
                                                (reply/with-stale-authority {:event [:t] :dispatch-stale? true}))))
        ":dispatch-stale? true on a framework/tool-authorised target → delivered")
    (is (thrown? clojure.lang.ExceptionInfo
                 (route-reply/suppress {:nav-token "nav-1"} "nav-2"
                                       {:event [:t] :dispatch-stale? true}))
        "an APP route target setting :dispatch-stale? true without framework authority FAILS LOUD")))

;; ---- rf2-2avo53 — live completion through the shared substrate -------------

(deftest live-reply-builds-status-ok-with-route-work-id
  (testing "live-reply builds the :status :ok route-loader reply joined to the
            complete route :work/id, carrying :value / frame / completed-at"
    (let [reply (route-reply/live-reply {:route-id     :route/article
                                         :nav-token    "nav-1"
                                         :loader-id    :article/load-replied
                                         :frame        :rf/default
                                         :completed-at 1717000000000}
                                        {:title "Welcome"})]
      (is (reply/valid-reply? reply) "conforms to the reply-map contract")
      (is (= :ok (:status reply)))
      (is (= :completed (:work/status reply)))
      (is (= :route (:work/kind reply)))
      (is (= {:title "Welcome"} (:value reply)) "the loader result is :value (EP-0007)")
      (is (= [:rf.work/route :route/article "nav-1" :article/load-replied] (:work/id reply))
          "the complete route work-id rides the live reply")
      (is (= :rf/default (:rf.frame/id reply)))
      (is (= 1717000000000 (:completed-at reply))))
    (testing "frame / completed-at are omitted when absent; :value rides as nil
              for a successful load with no payload (:ok REQUIRES :value present)"
      (let [reply (route-reply/live-reply {:route-id  :route/article
                                           :nav-token "nav-1"
                                           :loader-id :article/load-replied})]
        (is (reply/valid-reply? reply) ":ok with :value nil is valid")
        (is (contains? reply :value) ":value rides even when nil (:ok requires it)")
        (is (nil? (:value reply)))
        (is (not (contains? reply :completed-at)))
        (is (not (contains? reply :rf.frame/id)))))))

(deftest complete-live-appends-reply-via-shared-complete
  (testing "complete-live appends the :status :ok reply map to the target event
            through the shared re-frame.reply/complete — the production lowering"
    (let [ctx    {:route-id :route/article :nav-token "nav-1"
                  :loader-id :article/load-replied :frame :rf/default}
          target [:article/load-replied {:id "A"}]
          ev     (route-reply/complete-live ctx target {:title "Welcome"})]
      (is (= :article/load-replied (first ev)) "the target event id leads")
      (is (= {:id "A"} (second ev)) "the target's leading args are intact")
      (let [reply (last ev)]
        (is (map? reply) "the reply map is the appended final argument")
        (is (= :ok (:status reply)))
        (is (= {:title "Welcome"} (:value reply))))
      (is (= ev (reply/complete target (route-reply/live-reply ctx {:title "Welcome"})))
          "complete-live IS re-frame.reply/complete over live-reply — no bespoke path"))
    (testing "the EP-0011 functor law holds at the route surface: complete-live
              composes with re-frame.reply/map-completed-event"
      (let [ctx    {:route-id :r :nav-token "n" :loader-id :l}
            target [:article/load-replied {:id "A"}]
            f      (fn [event] [:wrapped event])
            reply  (route-reply/live-reply ctx {:title "Welcome"})]
        (is (= (reply/complete (reply/map-completed-event f target) reply)
               (f (reply/complete target reply)))
            "complete(map-completed-event(f, t), reply) == f(complete(t, reply))")))
    (testing "a nil target yields nil — no continuation to complete"
      (is (nil? (route-reply/complete-live {:nav-token "n"} nil {:title "x"}))))))

;; ---- §Tracing -------------------------------------------------------------

(deftest trace-reply-routes-wire-slots-through-shared-walker
  (testing "trace-reply is the shared re-frame.reply/trace-summary — identity facts
            ride verbatim; wire slots elide through the one shared walker"
    (let [r {:status :ok :work/id [:rf.work/route :r "n" :l] :work/kind :route
             :rf.frame/id :rf/default :value {:title "Welcome"}}
          summary (route-reply/trace-reply r {:frame :rf/default})]
      (is (= (reply/trace-summary r {:frame :rf/default}) summary)
          "delegates to the shared trace-summary — never a family-private elider")
      (is (= :ok (:status summary)) "identity facts ride verbatim")
      (is (= [:rf.work/route :r "n" :l] (:work/id summary))))))
