(ns re-frame.live-frame-reprojection-failed-test
  "A live frame whose reprojection fails reports it on the always-on error axis
  (Spec 009 `:rf.error/reprojection-failed`), in every build.

  The repro registers through the real `rf/reg-*` macros from real namespaces,
  so each registration carries the provenance a hot-reload refactor produces:
  an event handler moved from one namespace to another while the old
  namespace's slot survives. The default-image frame then cannot resolve that
  id, keeps its prior generation for every id, and must say so once per failed
  refresh.

  JVM-only: it creates namespaces and `eval`s registration forms in them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private probe-namespaces '[probe.a probe.b probe.c])

(defn- eval-in-ns
  "Evaluate `form` with `*ns*` bound to the namespace `ns-sym`, so a `rf/reg-*`
  macro in it captures that namespace as its provenance — exactly as
  `(ns ns-sym) (rf/reg-… …)` does at a REPL."
  [ns-sym form]
  (binding [*ns* (create-ns ns-sym)]
    (refer-clojure)
    (alias 'rf 're-frame.core)
    (eval form)))

(defn- reprojection-failures [records]
  (filterv #(= :rf.error/reprojection-failed (:error %)) records))

(defn- sweep!
  "One resilient refresh of every live frame, as the deferred flush and the
  read-time consult run it."
  []
  (#'rf.live-frame/reproject-live-frames-resiliently!))

(deftest moved-handler-reports-the-frozen-frame-once-per-failed-refresh
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (try
      (eval-in-ns 'probe.c '(rf/reg-event :probe/count
                              (fn [{:keys [db]} _] {:db (assoc db :count-version 1)})))
      (eval-in-ns 'probe.a '(rf/reg-event :probe/ev {:doc "a"}
                              (fn [{:keys [db]} _] {:db (assoc db :who :a)})))
      (rf/make-frame {:id :probe/frame})
      (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})
      (is (= :a (:who (rf/app-db-value :probe/frame))))

      (testing "a refresh that changes nothing reports nothing, and the sweep says unchanged"
        (is (= {:moved {} :failed {}} (sweep!)))
        (is (empty? (reprojection-failures @seen))))

      ;; The refactor: probe.b now registers :probe/ev and adds a handler.
      ;; probe.a's slot for :probe/ev survives.
      (eval-in-ns 'probe.b '(do (rf/reg-event :probe/ev {:doc "b"}
                                  (fn [{:keys [db]} _] {:db (assoc db :who :b)}))
                                (rf/reg-event :probe/other
                                  (fn [{:keys [db]} _] {:db (assoc db :other true)}))))
      (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})

      (testing "exactly one always-on record names the frame, the [kind id] and both namespaces"
        (let [[record :as records] (reprojection-failures @seen)]
          (is (= 1 (count records)))
          (is (= {:frame        :probe/frame
                  :registration [:event :probe/ev]
                  :namespaces   ["probe.a" "probe.b"]}
                 (select-keys record [:frame :registration :namespaces])))
          (is (str/includes? (str (:reason record)) "(rf/clear :event :probe/ev)")
              "the record names the recovery")))

      (testing "later plain dispatches to the frozen frame do not repeat it"
        (rf/dispatch-sync [:probe/other] {:frame :probe/frame})
        (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})
        (is (= 1 (count (reprojection-failures @seen)))))

      (testing "the sweep tells a failed refresh from an unchanged one"
        (let [{:keys [moved failed]} (sweep!)]
          (is (= {} moved))
          (is (= [:probe/frame] (keys failed)))
          (is (= [:event :probe/ev] (get-in failed [:probe/frame :registration]))))
        (is (= 2 (count (reprojection-failures @seen)))
            "each failed refresh reports once"))

      (testing "the named recovery unfreezes the frame"
        (rf/clear :event :probe/ev)
        (eval-in-ns 'probe.b '(rf/reg-event :probe/ev {:doc "b"}
                                (fn [{:keys [db]} _] {:db (assoc db :who :b)})))
        (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})
        (rf/dispatch-sync [:probe/other] {:frame :probe/frame})
        (is (= {:who :b :other true}
               (select-keys (rf/app-db-value :probe/frame) [:who :other])))
        (is (= 2 (count (reprojection-failures @seen)))
            "a successful refresh reports nothing"))
      (finally
        (rf.error-emit/unregister-error-listener! ::recorder)
        (doseq [ns-sym probe-namespaces] (remove-ns ns-sym))))))
