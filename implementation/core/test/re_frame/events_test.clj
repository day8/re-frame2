(ns re-frame.events-test
  "Per rf2-bbea — `reg-event-*` warns when `:interceptors` appears inside
  the metadata-map (where it is silently ignored). The interceptor chain
  belongs in the third positional slot, not the metadata map.

  The warning is delivered via the trace stream
  (`:rf.warning/interceptors-in-metadata-map`, per Conventions §Reserved
  namespaces — `:rf.warning/*`). Hot-reload tools / 10x consume the
  warning; this test asserts it fires on the wrong shape and stays
  silent on the right shape."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-trace-cbs!)
  (rf/init!)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces!
  "Attach a recording listener and return its atom. Forgetting to remove
  the listener doesn't matter — the fixture clears all listeners between
  deftests."
  [listener-id]
  (let [a (atom [])]
    (rf/register-trace-cb! listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warning-events
  [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

(def ^:private noop-icpt
  ;; A no-op interceptor; just enough to populate a positional vector.
  {:id     :test/noop
   :before identity
   :after  identity})

;; ---- tests ----------------------------------------------------------------

(deftest interceptors-in-metadata-map-warns
  (testing "reg-event-db with :interceptors inside the metadata-map fires :rf.warning/interceptors-in-metadata-map"
    (let [recorded (record-traces! ::db-warn)]
      (rf/reg-event-db :test.bbea/db-bad
        {:doc "A wrongly-shaped registration." :interceptors [noop-icpt]}
        (fn [db _] db))
      (let [warns (warning-events recorded :rf.warning/interceptors-in-metadata-map)]
        (is (= 1 (count warns)) (str "expected exactly one warning, got " (count warns)))
        (let [t (:tags (first warns))]
          (is (= "reg-event-db" (:reg-fn t)))
          (is (= :test.bbea/db-bad (:id t)))
          (is (= [:interceptors] (:offending-keys t)))
          (is (string? (:reason t)))
          (is (re-find #"silently ignored" (:reason t))))
        (is (= :ignored (:recovery (first warns)))))))

  (testing "reg-event-fx with :interceptors inside the metadata-map fires the warning"
    (let [recorded (record-traces! ::fx-warn)]
      (rf/reg-event-fx :test.bbea/fx-bad
        {:doc "Wrong shape." :interceptors [noop-icpt]}
        (fn [_ _] {:db {}}))
      (let [warns (warning-events recorded :rf.warning/interceptors-in-metadata-map)]
        (is (= 1 (count warns)))
        (is (= "reg-event-fx" (:reg-fn (:tags (first warns))))))))

  (testing "reg-event-ctx with :interceptors inside the metadata-map fires the warning"
    (let [recorded (record-traces! ::ctx-warn)]
      (rf/reg-event-ctx :test.bbea/ctx-bad
        {:doc "Wrong shape." :interceptors [noop-icpt]}
        (fn [ctx] ctx))
      (let [warns (warning-events recorded :rf.warning/interceptors-in-metadata-map)]
        (is (= 1 (count warns)))
        (is (= "reg-event-ctx" (:reg-fn (:tags (first warns)))))))))

(deftest correct-positional-form-stays-silent
  (testing "reg-event-db with interceptors in the positional slot does NOT warn"
    (let [recorded (record-traces! ::db-quiet)]
      (rf/reg-event-db :test.bbea/db-good
        [noop-icpt]
        (fn [db _] db))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map)))))

  (testing "reg-event-db with metadata-map (no :interceptors) AND positional interceptors does NOT warn"
    (let [recorded (record-traces! ::db-good-2)]
      (rf/reg-event-db :test.bbea/db-good-2
        {:doc "Right shape — metadata for reflection, interceptors positional."}
        [noop-icpt]
        (fn [db _] db))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map)))))

  (testing "reg-event-db with metadata-map alone (no interceptors anywhere) does NOT warn"
    (let [recorded (record-traces! ::db-good-3)]
      (rf/reg-event-db :test.bbea/db-good-3
        {:doc "Plain metadata-only registration."}
        (fn [db _] db))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map)))))

  (testing "the wrongly-shaped form ALSO loses its interceptor chain (positional slot remains empty)"
    ;; Confirm the bug the warning describes: the silently-dropped
    ;; interceptors do NOT end up in the registered :interceptors. The
    ;; registered chain holds only the runtime's :rf/db-handler wrapper.
    (let [_recorded (record-traces! ::confirm-drop)
          marker    {:id :test.bbea/marker :before identity :after identity}]
      (rf/reg-event-db :test.bbea/dropped
        {:interceptors [marker]}
        (fn [db _] db))
      (let [{:keys [interceptors]} (rf/handler-meta :event :test.bbea/dropped)
            ids (set (map :id interceptors))]
        (is (not (contains? ids :test.bbea/marker))
            "the metadata-map :interceptors entry is dropped (this confirms the bug rf2-bbea documents)")))))
