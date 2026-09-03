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
            [re-frame.reply :as rf.reply]
            [re-frame.routing.reply :as rf.routing.reply]))

;; ---- §Work-id correlation -------------------------------------------------

(deftest route-work-id-tuple-shape
  (testing "the route-loader work-id head is [:rf.work/route route-id nav-token loader-id]"
    (is (= [:rf.work/route :route/article "nav-1" :article/loaded]
           (rf.routing.reply/work-id {:route-id  :route/article
                                 :nav-token "nav-1"
                                 :loader-id :article/loaded}))
        "the four-element route head per Managed-Effects §Work-id correlation")
    (is (= [:rf.work/route nil nil nil]
           (rf.routing.reply/work-id {}))
        "nil components keep a valid, distinct work id (a loader that named nothing)"))
  (testing "the work id is =-comparable and EDN-serializable"
    (let [wid (rf.routing.reply/work-id {:route-id :r :nav-token "n" :loader-id :l})]
      (is (= wid (read-string (pr-str wid)))
          "round-trips through EDN unchanged"))))

;; ---- §Stale suppression — the nav-token is the ONE :suppress gate ---------

(deftest nav-token-gate-shape
  (testing "the carried/current gates are the data-only {:route/nav-token <t>} map"
    (is (= {:route/nav-token "nav-1"} (rf.routing.reply/gate "nav-1")))
    (is (= {:route/nav-token "nav-2"} (rf.routing.reply/current-gate "nav-2")))))

(deftest suppress?-delegates-to-shared-reply-stale?
  (testing "suppress? is exactly re-frame.reply/stale? over the :route/nav-token gate
            — the route family does NOT re-implement the comparison"
    (doseq [[carried current] [["nav-1" "nav-2"] ["nav-2" "nav-2"] [nil "nav-2"] ["nav-1" nil]]]
      (is (= (rf.reply/stale? (rf.routing.reply/gate carried) (rf.routing.reply/current-gate current))
             (rf.routing.reply/suppress? carried current))
          (str "suppress? matches the shared stale? for carried=" carried " current=" current))))
  (testing "stale when the epoch advanced; live when it matches"
    (is (true?  (rf.routing.reply/suppress? "nav-1" "nav-2")) "superseded → stale")
    (is (false? (rf.routing.reply/suppress? "nav-2" "nav-2")) "current → live")
    ;; A nil CAPTURED token while a navigation is live is suppressed: the
    ;; gate is present (`{:route/nav-token nil}`) with a nil value, which
    ;; never equals the active token. This matches the original
    ;; `(= nav-token current)` semantics and is the regression guard for
    ;; the pre-fix bug where a cofx threaded nil and was silently eaten —
    ;; nil is now suppressed (not committed) exactly as before.
    (is (true? (rf.routing.reply/suppress? nil "nav-2"))
        "a nil captured token under a live navigation is stale (never matches)")
    (is (false? (rf.routing.reply/suppress? nil nil))
        "nil captured against a nil current (no navigation) matches — both gates equal")))

;; ---- §Stale suppression — the suppress outcome ----------------------------

(deftest suppress-produces-stale-reply-joined-to-work-id
  (testing "a superseded route completion produces a non-delivered :status :stale
            reply carrying the route :work/id + :work/kind :route, and trace facts
            joined to :work/id with both carried + current gates"
    (let [{:keys [deliver? reply trace] :as outcome}
          (rf.routing.reply/suppress {:route-id  :route/article
                                 :nav-token "nav-1"
                                 :loader-id :article/loaded
                                 :frame     :rf/default}
                                "nav-2")]
      (is (false? deliver?) "the app reply target MUST NOT run for a stale completion")
      (is (= :suppressed (:rf.reply/work-status outcome)) "ledger terminal for a stale route load")

      (testing "the reply map is a valid, app-state-safe :status :stale reply"
        (is (rf.reply/valid-reply? reply) "conforms to the reply-map contract")
        (is (= :stale (:status reply)))
        (is (true? (:stale? reply)))
        (is (= :rf.route/nav-token-stale (:rf.reply/stale-reason reply)))
        (is (not (contains? reply :value)) "a stale reply carries no :value (no app mutation)")
        (is (= :route (:rf.reply/work-kind reply)))
        (is (= [:rf.work/route :route/article "nav-1" :article/loaded] (:rf.reply/work-id reply))
            "the reply carries the route work-id")
        (is (= :rf/default (:rf.frame/id reply)) "the carried frame stamp rides the reply"))

      (testing "the trace facts are joined to :work/id and carry both correlation gates"
        (is (true? (:rf.reply/suppressed? trace)))
        (is (= [:rf.work/route :route/article "nav-1" :article/loaded] (:rf.reply/work-id trace))
            "EP-0011 §Route Loader Completion: the suppression trace is joined to :work/id")
        (is (= {:route/nav-token "nav-1"} (:rf.reply/carried trace)) "carried gate")
        (is (= {:route/nav-token "nav-2"} (:rf.reply/current trace)) "current gate")
        (is (= :rf.route/nav-token-stale (:rf.reply/stale-reason trace)))))))

(deftest suppress-carries-completed-at-on-stale-reply
  (testing "rf2-ux8sgg — a route loader that supplies the reply completion
            time (`:completed-at`, the recordable :rf/time-ms fact, EP-0017)
            carries it verbatim onto the stale reply; absence omits it. This
            pins the production lane the pure substrate exposes — the stale
            route reply is tied to the actual replayed completion token."
    (let [{:keys [reply]}
          (rf.routing.reply/suppress {:route-id     :route/article
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
            (rf.routing.reply/suppress {:route-id  :route/article
                                   :nav-token "nav-1"
                                   :loader-id :article/loaded}
                                  "nav-2")]
        (is (not (contains? reply :completed-at))
            ":completed-at is omitted when the caller supplies none")))))

(deftest suppress-is-universally-non-delivering
  (testing "rf2-j538f7.14 — a stale route completion is UNIVERSALLY non-delivering
            through rf.routing.reply/suppress: no reply target, app or otherwise,
            receives it (delegated to re-frame.reply/suppress)"
    (is (false? (:deliver? (rf.routing.reply/suppress {:nav-token "nav-1"} "nav-2" nil)))
        "no target → not delivered")
    (is (false? (:deliver? (rf.routing.reply/suppress {:nav-token "nav-1"} "nav-2"
                                                 {:event [:t]})))
        "a plain descriptor → not delivered")
    (is (false? (:deliver? (rf.routing.reply/suppress {:nav-token "nav-1"} "nav-2"
                                                 {:event [:t] :dispatch-stale? true})))
        "an inert :dispatch-stale? flag grants nothing → not delivered")
    (is (false? (:deliver? (rf.routing.reply/suppress {:nav-token "nav-1"} "nav-2"
                                                 {:event [:t] :dispatch-stale? true
                                                  :re-frame.reply/stale-authority true})))
        "a forged authority datum grants nothing → not delivered")))

;; ---- rf2-2avo53 — live completion through the shared substrate -------------

(deftest live-reply-builds-status-ok-with-route-work-id
  (testing "live-reply builds the :status :ok route-loader reply joined to the
            complete route :work/id, carrying :value / frame / completed-at"
    (let [reply (rf.routing.reply/live-reply {:route-id     :route/article
                                         :nav-token    "nav-1"
                                         :loader-id    :article/load-replied
                                         :frame        :rf/default
                                         :completed-at 1717000000000}
                                        {:title "Welcome"})]
      (is (rf.reply/valid-reply? reply) "conforms to the reply-map contract")
      (is (= :ok (:status reply)))
      (is (= :completed (:rf.reply/work-status reply)))
      (is (= :route (:rf.reply/work-kind reply)))
      (is (= {:title "Welcome"} (:value reply)) "the loader result is :value (EP-0007)")
      (is (= [:rf.work/route :route/article "nav-1" :article/load-replied] (:rf.reply/work-id reply))
          "the complete route work-id rides the live reply")
      (is (= :rf/default (:rf.frame/id reply)))
      (is (= 1717000000000 (:completed-at reply))))
    (testing "frame / completed-at are omitted when absent; :value rides as nil
              for a successful load with no payload (:ok REQUIRES :value present)"
      (let [reply (rf.routing.reply/live-reply {:route-id  :route/article
                                           :nav-token "nav-1"
                                           :loader-id :article/load-replied})]
        (is (rf.reply/valid-reply? reply) ":ok with :value nil is valid")
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
          ev     (rf.routing.reply/complete-live ctx target {:title "Welcome"})]
      (is (= :article/load-replied (first ev)) "the target event id leads")
      (is (= {:id "A"} (second ev)) "the target's leading args are intact")
      (let [reply (last ev)]
        (is (map? reply) "the reply map is the appended final argument")
        (is (= :ok (:status reply)))
        (is (= {:title "Welcome"} (:value reply))))
      (is (= ev (rf.reply/complete target (rf.routing.reply/live-reply ctx {:title "Welcome"})))
          "complete-live IS re-frame.reply/complete over live-reply — no bespoke path"))
    (testing "the EP-0011 functor law holds at the route surface: complete-live
              composes with re-frame.reply/map-completed-event"
      (let [ctx    {:route-id :r :nav-token "n" :loader-id :l}
            target [:article/load-replied {:id "A"}]
            f      (fn [event] [:wrapped event])
            reply  (rf.routing.reply/live-reply ctx {:title "Welcome"})]
        (is (= (rf.reply/complete (rf.reply/map-completed-event f target) reply)
               (f (rf.reply/complete target reply)))
            "complete(map-completed-event(f, t), reply) == f(complete(t, reply))")))
    (testing "a nil target yields nil — no continuation to complete"
      (is (nil? (rf.routing.reply/complete-live {:nav-token "n"} nil {:title "x"}))))))

;; ---- §Tracing -------------------------------------------------------------

(deftest trace-reply-routes-wire-slots-through-shared-walker
  (testing "trace-reply is the shared re-frame.reply/trace-summary — identity facts
            ride verbatim; wire slots elide through the one shared walker"
    (let [r {:status :ok :rf.reply/work-id [:rf.work/route :r "n" :l] :rf.reply/work-kind :route
             :rf.frame/id :rf/default :value {:title "Welcome"}}
          summary (rf.routing.reply/trace-reply r {:frame :rf/default})]
      (is (= (rf.reply/trace-summary r {:frame :rf/default}) summary)
          "delegates to the shared trace-summary — never a family-private elider")
      (is (= :ok (:status summary)) "identity facts ride verbatim")
      (is (= [:rf.work/route :r "n" :l] (:rf.reply/work-id summary))))))
