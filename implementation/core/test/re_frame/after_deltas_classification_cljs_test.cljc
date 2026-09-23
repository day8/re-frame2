(ns re-frame.after-deltas-classification-cljs-test
  "rf2-3x7nj.4.2 — the `:rf.event/after-deltas` slot on `:rf.event/run-end`
  carries one ctx diff per user `:after` interceptor, and each diff carries its
  `:before` / `:after` VALUES. The standard `[:rf.interceptor/path …]` `:after`
  always rewrites `[:coeffects :db]` and widens `[:effects :db]` back to the
  WHOLE app-db, so every path-focused handler stamped the whole db into that
  slot — and `re-frame.classification/project-trace-event` had no arm for it, so
  the frame's classified paths shipped RAW past the emit-time chokepoint (trace
  listeners, the ring, the epoch record, and the off-box epoch projection).
  An `:after` that adds `:fx` leaked that fx's registration-classified args the
  same way.

  Producer-derived: the live legs drive the REAL path interceptor and a REAL
  user `:after`, and each first proves the slot is populated by that producer —
  so a secret's absence is a fact about a slot that exists, not a vacuum.

  Posture: the live legs read the dev trace, which emits nothing under
  `-Dre-frame.debug=false`, so they are `^:requires-debug` (rf2-d2841). The
  projector leg drives `project-trace-event` on a hand-built shape and runs in
  both postures.

  Dual-runtime `.cljc` (`*-cljs-test` ns): the JVM `clojure -M:test` runner and
  the shadow-cljs `:node-test` build both pick it up."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private secret "AFTER-DELTAS-SENTINEL-3x7nj42")

(def ^:private frame-id :after-deltas/frame)

(defn- contains-secret?
  [x]
  (cond
    (string? x) #?(:clj  (.contains ^String x ^String secret)
                   :cljs (not= -1 (.indexOf x secret)))
    (map? x)    (boolean (some contains-secret? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some contains-secret? x))
    :else       false))

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- run-end [traces]
  (first (filter #(= :rf.event/run-end (:operation %)) traces)))

(defn- seed-classified-secret!
  "Make the frame and classify `[:auth :token]` sensitive in the SAME event that
  writes the secret there (the canonical EP-0025 pattern)."
  []
  (rf/make-frame {:id frame-id})
  (rf/reg-event :after-deltas/seed
    (fn [{:keys [db]} _]
      {:db        (assoc db :auth {:token secret} :counter 0)
       :sensitive [[:auth :token]]}))
  (rf/dispatch-sync [:after-deltas/seed] {:frame frame-id}))

;; ---------------------------------------------------------------------------
;; Live: the standard path interceptor's whole-db diff
;; ---------------------------------------------------------------------------

(deftest ^:requires-debug path-interceptor-after-delta-redacts-classified-db-paths
  (testing "a handler focused on [:counter] never reads :auth, yet the path
            interceptor's :after diff carries the WHOLE db before and after —
            the classified [:auth :token] must be :rf/redacted there, while the
            focused change itself survives for the diff's reader"
    (seed-classified-secret!)
    (rf/reg-event :after-deltas/bump
      {:interceptors [[:rf.interceptor/path [:counter]]]}
      (fn [{:keys [db]} _] {:db (inc db)}))
    (let [acc (collect-traces! ::path)]
      (try
        (rf/dispatch-sync [:after-deltas/bump] {:frame frame-id})
        (let [ev     (run-end @acc)
              deltas (get-in ev [:tags :rf.event/after-deltas])
              diff   (:rf.interceptor.delta/ctx-delta (first deltas))]
          ;; Producer control: the slot exists and the path interceptor wrote it.
          (is (= [:rf.interceptor/path] (mapv :rf.interceptor.delta/id deltas))
              "the run-end trace carries the path interceptor's after-delta")
          (is (= 1 (get-in diff [:effects :changed :db :after :counter]))
              "the focused change survives the projection — the diff still reads")
          (is (= rf.privacy/redacted-sentinel
                 (get-in diff [:coeffects :changed :db :after :auth :token]))
              "the restored whole-db coeffect has the classified path redacted")
          (is (= rf.privacy/redacted-sentinel
                 (get-in diff [:effects :changed :db :after :auth :token]))
              "the widened whole-db effect has the classified path redacted")
          (is (not (contains-secret? ev))
              "the secret appears nowhere in the run-end trace"))
        (finally
          (rf/unregister-listener! :trace ::path))))))

;; ---------------------------------------------------------------------------
;; Live: a user :after that adds :fx
;; ---------------------------------------------------------------------------

(deftest ^:requires-debug user-after-added-fx-args-take-the-fx-registration-classification
  (testing "an :after interceptor that ADDS an :fx entry puts that fx's args in
            the diff; a classified fx's args redact there exactly as on
            :rf.event/fx, and an unclassified control fx rides raw"
    (rf/make-frame {:id frame-id})
    (rf/reg-fx :after-deltas/store {:sensitive [[:token]]} (fn [_ _] nil))
    (rf/reg-fx :after-deltas/audit (fn [_ _] nil))
    (rf/reg-interceptor :after-deltas/add-fx
      {:after (fn [ctx]
                (update-in ctx [:effects :fx] (fnil into [])
                           [[:after-deltas/store {:token secret}]
                            [:after-deltas/audit {:msg "benign"}]]))})
    (rf/reg-event :after-deltas/write
      {:interceptors [:after-deltas/add-fx]}
      (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (let [acc (collect-traces! ::fx)]
      (try
        (rf/dispatch-sync [:after-deltas/write] {:frame frame-id})
        (let [ev     (run-end @acc)
              deltas (get-in ev [:tags :rf.event/after-deltas])
              fx     (get-in (first deltas)
                             [:rf.interceptor.delta/ctx-delta :effects :added :fx])]
          (is (= [:after-deltas/add-fx] (mapv :rf.interceptor.delta/id deltas))
              "the user :after interceptor wrote the after-delta")
          (is (= [[:after-deltas/store {:token rf.privacy/redacted-sentinel}]
                  [:after-deltas/audit {:msg "benign"}]]
                 fx)
              "the classified fx's token is redacted; the control fx rides raw")
          (is (not (contains-secret? ev))
              "the secret appears nowhere in the run-end trace"))
        (finally
          (rf/unregister-listener! :trace ::fx))))))

;; ---------------------------------------------------------------------------
;; Projector: every value position, and the frameless fail-closed arm
;; ---------------------------------------------------------------------------

(deftest project-trace-event-walks-every-after-delta-value-position
  (testing "the chokepoint projects :db under :added / :removed and both sides
            of :changed, in both segments, and fails CLOSED with no frame"
    (seed-classified-secret!)
    (let [db     {:auth {:token secret} :n 1}
          seg    {:added   {:db db}
                  :removed {:db db}
                  :changed {:db {:before db :after db}}}
          ev     (fn [frame]
                   {:op-type   :rf.event
                    :operation :rf.event/run-end
                    :tags      {:frame                 frame
                                :rf.event/after-deltas
                                [{:rf.interceptor.delta/id        :x/after
                                  :rf.interceptor.delta/ctx-delta {:coeffects seg
                                                                   :effects   seg}}]}})
          framed (rf.classification/project-trace-event (ev frame-id))
          bare   (rf.classification/project-trace-event (ev nil))
          diff   (fn [e] (get-in e [:tags :rf.event/after-deltas 0
                                     :rf.interceptor.delta/ctx-delta]))]
      (is (contains-secret? (ev frame-id)) "control: the input carries the secret")
      (doseq [segment [:coeffects :effects]
              path    [[:added :db] [:removed :db]
                       [:changed :db :before] [:changed :db :after]]]
        (is (= rf.privacy/redacted-sentinel
               (get-in (diff framed) (into [segment] (conj path :auth :token))))
            (str "framed: " segment " " path " redacts the classified path"))
        (is (= 1 (get-in (diff framed) (into [segment] (conj path :n))))
            (str "framed: " segment " " path " keeps the unclassified value"))
        (is (= rf.privacy/redacted-sentinel (get-in (diff bare) (into [segment] path)))
            (str "frameless: " segment " " path " fails closed to the sentinel")))
      (is (not (contains-secret? framed)))
      (is (not (contains-secret? bare))))))
