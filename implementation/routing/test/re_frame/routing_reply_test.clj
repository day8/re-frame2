(ns re-frame.routing-reply-test
  "Unit tests for `re-frame.routing.reply` — route-loader async work
  lowered onto the uniform reply envelope (EP-0011 §Route Loader
  Completion).

  Pins the route work-id tuple, the verdicts of the nav-token `:suppress`
  gate (computed by the shared `re-frame.reply` correctness boundary), and the
  data-only stale reply / trace facts joined to `:work/id`. These are
  pure-fn tests over the lowering substrate — no runtime stand-up; the
  end-to-end gate behaviour is exercised by
  `re-frame.routing-nav-token-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.reply :as rf.reply]
            [re-frame.routing.reply :as rf.routing.reply]))

;; ---- §Stale suppression — the nav-token is the ONE :suppress gate ---------

(deftest suppress?-is-true-exactly-when-the-carried-token-is-not-current
  (testing "suppress? over the :route/nav-token gate: stale when the epoch
            advanced, live when it matches"
    (doseq [[carried current expected why]
            [["nav-1" "nav-2" true  "superseded → stale"]
             ["nav-2" "nav-2" false "current → live"]
             ;; A nil CAPTURED token while a navigation is live is suppressed:
             ;; the gate is present (`{:route/nav-token nil}`) with a nil value,
             ;; which never equals the active token. This matches plain
             ;; `(= nav-token current)` semantics: a completion that captured
             ;; no token (a cofx that threaded nil) is suppressed, never committed.
             [nil     "nav-2" true  "a nil captured token under a live navigation is stale (never matches)"]
             [nil     nil     false "nil captured against a nil current (no navigation) matches — both gates equal"]
             ["nav-1" nil     true  "a captured token with no live navigation is stale"]]]
      (is (= expected (rf.routing.reply/suppress? carried current))
          (str why " — carried=" (pr-str carried) " current=" (pr-str current))))))

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

;; ---- live completion through the shared substrate -------------------------

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
