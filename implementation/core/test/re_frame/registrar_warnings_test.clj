(ns re-frame.registrar-warnings-test
  "Per rf2-45kaz / Spec 001 §`:doc` is dev-warned when absent +
  §Re-registration of a different function — collision warning.

  Two warnings the registrar emits on the trace bus:

    1. `:rf.warning/missing-doc` — every reg-* call whose final
       metadata-map carries no usable `:doc` slot (absent, nil, or
       empty string). Suppressed per `(kind, id)` within a runtime
       process so the dev stream stays readable across hot-reload.
       Emitted from the public macro path only — programmatic
       internal helpers that bypass coord capture are out of scope.

    2. `:rf.warning/registration-collision` — re-registration with a
       different `:handler-fn`. Sits alongside the existing
       `:rf.registry/handler-replaced` trace (which fires on EVERY
       re-registration with a `:different-fn?` tag); the warning is
       the separate dev-nudge surface that lifts the collision out
       of the steady-state hot-reload stream. Same per-(kind, id)
       suppression discipline.

  Both warnings sit inside the registrar's outer
  `(when interop/debug-enabled? ...)` gate so `:advanced +
  goog.DEBUG=false` constant-folds the consult+emit branch to nil
  (Spec 009 §Production builds). The CLJS elision-probe sentinels
  pin the absence of `rf.warning/missing-doc` and
  `rf.warning/registration-collision` in the production bundle."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.core :as rf]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-trace-cbs!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces!
  "Attach a recording listener and return its atom."
  [listener-id]
  (let [a (atom [])]
    (rf/register-trace-cb! listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warnings-of
  [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

(defn- with-stamped-coords
  "Invoke `f` with `*pending-coords*` bound to a synthetic
  macro-path coord map. Mirrors what every reg-* macro does at
  expansion time — every entry that reaches `register!` via the
  user-facing macro path carries the `:ns/:line/:file` envelope.

  Tests use this to exercise the `:rf.warning/missing-doc` emission
  gate, which only fires for metadata that came through the macro
  path (Spec 001 §`:doc` obligation 4 carves out programmatic /
  internal-helper paths)."
  [f]
  (binding [source-coords/*pending-coords*
            {:ns 're-frame.registrar-warnings-test
             :file "registrar_warnings_test.clj"
             :line 1
             :column 1}]
    (f)))

;; =============================================================================
;; F1 — `:rf.warning/missing-doc`
;; =============================================================================

;; Obligation 1: emit on every reg-* whose final metadata-map carries no
;; usable :doc (absent, nil, or empty string).

(deftest missing-doc-fires-when-doc-absent
  (testing "reg-* via the macro path with no :doc key emits :rf.warning/missing-doc"
    (let [recorded (record-traces! ::missing-absent)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :ev/no-doc (fn [db _] db))))
      (let [warns (warnings-of recorded :rf.warning/missing-doc)]
        (is (= 1 (count warns))
            (str "expected exactly one missing-doc warning, got " (count warns)))
        (let [t (:tags (first warns))]
          (is (= :event (:kind t))
              ":tags carries the registry :kind")
          (is (= :ev/no-doc (:id t))
              ":tags carries the registered :id")
          (is (map? (:source-coords t))
              ":tags carries the captured :source-coords envelope")
          (is (= 're-frame.registrar-warnings-test (:ns (:source-coords t)))))))))

(deftest missing-doc-fires-when-doc-nil
  (testing ":doc explicitly nil is treated as missing"
    (let [recorded (record-traces! ::missing-nil)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :ev/nil-doc {:doc nil} (fn [db _] db))))
      (is (= 1 (count (warnings-of recorded :rf.warning/missing-doc)))))))

(deftest missing-doc-fires-when-doc-empty-string
  (testing ":doc as the empty string is treated as missing"
    (let [recorded (record-traces! ::missing-empty)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :ev/empty-doc {:doc ""} (fn [db _] db))))
      (is (= 1 (count (warnings-of recorded :rf.warning/missing-doc)))))))

(deftest missing-doc-suppressed-when-doc-present
  (testing "well-documented registration emits no warning"
    (let [recorded (record-traces! ::doc-present)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :ev/well-doc'd
                           {:doc "a real description"}
                           (fn [db _] db))))
      (is (empty? (warnings-of recorded :rf.warning/missing-doc))))))

;; Obligation 2: suppress per (kind, id) within a runtime process.

(deftest missing-doc-suppressed-on-re-registration-same-id
  (testing "re-registering the same (kind, id) with still-missing :doc does NOT re-emit"
    (let [recorded (record-traces! ::suppress-rereg)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :ev/same-id (fn [db _] db))
          ;; Save-triggered re-eval — same id, still no :doc; warning is silent.
          (rf/reg-event-db :ev/same-id (fn [db _] (assoc db :touched? true)))
          (rf/reg-event-db :ev/same-id (fn [db _] db))))
      (is (= 1 (count (warnings-of recorded :rf.warning/missing-doc)))
          "exactly one warning across three registrations of the same id"))))

(deftest missing-doc-fires-once-per-id-within-kind
  (testing "different ids under the same kind each get their own warning"
    (let [recorded (record-traces! ::per-id-within-kind)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :ev/alpha (fn [db _] db))
          (rf/reg-event-db :ev/beta  (fn [db _] db))
          (rf/reg-event-db :ev/gamma (fn [db _] db))))
      (let [warns (warnings-of recorded :rf.warning/missing-doc)]
        (is (= 3 (count warns)))
        (is (= #{:ev/alpha :ev/beta :ev/gamma}
               (into #{} (map #(get-in % [:tags :id])) warns)))))))

(deftest missing-doc-fires-once-per-kind-for-same-id
  (testing "the same id under different kinds each get their own warning"
    (let [recorded (record-traces! ::per-kind-same-id)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :alias/shared (fn [db _] db))
          (rf/reg-sub       :alias/shared (fn [db _] (:x db)))))
      (let [warns (warnings-of recorded :rf.warning/missing-doc)]
        (is (= 2 (count warns)))
        (is (= #{:event :sub}
               (into #{} (map #(get-in % [:tags :kind])) warns)))))))

;; Obligation 4: kind coverage. Spot-check a representative slice across
;; the canonical kinds (:event :sub :fx :cofx). The :doc check sits in
;; the registrar chokepoint so every kind flows through the same gate;
;; per-kind enumeration confirms the gate is independent of kind.

(deftest missing-doc-fires-across-multiple-kinds
  (testing "the gate is kind-agnostic — fires on :event, :sub, :fx, :cofx"
    (let [recorded (record-traces! ::multi-kind)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :k/event-doc-missing (fn [db _] db))
          (rf/reg-sub       :k/sub-doc-missing   (fn [db _] (:x db)))
          (rf/reg-fx        :k/fx-doc-missing    (fn [_ _] nil))
          (rf/reg-cofx      :k/cofx-doc-missing  (fn [cofx _] cofx))))
      (let [warns (warnings-of recorded :rf.warning/missing-doc)
            kinds (into #{} (map #(get-in % [:tags :kind])) warns)]
        (is (= 4 (count warns))
            "one warning per (kind, id) across four kinds")
        (is (= #{:event :sub :fx :cofx} kinds))))))

;; Obligation 4: programmatic registrations that bypass the macro path
;; (no source coords merged in) are out of scope.

(deftest missing-doc-silent-on-programmatic-path
  (testing "register! called without macro-path source coords does NOT emit"
    (let [recorded (record-traces! ::programmatic)]
      ;; Note: NO with-stamped-coords wrapper — *pending-coords* is nil,
      ;; mirroring an internal helper / REPL register! call.
      (registrar/register! :event :internal/no-coords
                           {:handler-fn (fn [db _] db)})
      (is (empty? (warnings-of recorded :rf.warning/missing-doc))
          "programmatic / internal-helper path is out of scope (Spec 001 obligation 4)"))))

;; =============================================================================
;; F2 — `:rf.warning/registration-collision`
;; =============================================================================

(deftest collision-fires-on-different-fn-re-registration
  (testing "re-registering an id with a different handler-fn emits the warning"
    (let [recorded (record-traces! ::collision-different)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :collide/id {:doc "first"}  (fn [db _] (assoc db :v 1)))
          (rf/reg-event-db :collide/id {:doc "second"} (fn [db _] (assoc db :v 2)))))
      (let [warns (warnings-of recorded :rf.warning/registration-collision)]
        (is (= 1 (count warns))
            "exactly one collision warning fires on the second registration")
        (let [t (:tags (first warns))]
          (is (= :event (:kind t)))
          (is (= :collide/id (:id t)))
          (is (map? (:source-coords t))
              ":source-coords carries the NEW registration's coords")
          ;; :previous-coords is the captured envelope of the prior reg;
          ;; both registrations were stamped with the same coords here.
          (is (map? (:previous-coords t))))))))

(deftest collision-suppressed-on-third-re-registration
  (testing "subsequent re-registrations of the same (kind, id) do NOT re-emit"
    (let [recorded (record-traces! ::collision-suppressed)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :churn/id {:doc "a"} (fn [db _] (assoc db :v 1)))
          (rf/reg-event-db :churn/id {:doc "b"} (fn [db _] (assoc db :v 2)))
          (rf/reg-event-db :churn/id {:doc "c"} (fn [db _] (assoc db :v 3)))
          (rf/reg-event-db :churn/id {:doc "d"} (fn [db _] (assoc db :v 4)))))
      (is (= 1 (count (warnings-of recorded :rf.warning/registration-collision)))
          "warn-once: only the first different-fn re-registration emits"))))

;; The existing `:rf.registry/handler-replaced` trace must keep firing on
;; EVERY re-registration (per Spec 001 §Hot-reload trace surface, rf2-6w7zn)
;; — the collision warning is a SEPARATE surface, not a replacement.

(deftest collision-warning-coexists-with-handler-replaced
  (testing "handler-replaced still fires unconditionally; collision is the dev-nudge addition"
    (let [recorded (record-traces! ::coexist)]
      (with-stamped-coords
        (fn []
          (rf/reg-event-db :coex/id {:doc "1"} (fn [db _] (assoc db :v 1)))
          (rf/reg-event-db :coex/id {:doc "2"} (fn [db _] (assoc db :v 2)))
          (rf/reg-event-db :coex/id {:doc "3"} (fn [db _] (assoc db :v 3)))))
      (let [replaced (filterv (fn [ev]
                                (and (= :registry (:op-type ev))
                                     (= :rf.registry/handler-replaced
                                        (:operation ev))))
                              @recorded)
            collisions (warnings-of recorded :rf.warning/registration-collision)]
        (is (= 2 (count replaced))
            "handler-replaced fires on each of the two re-registrations")
        (is (= 1 (count collisions))
            "collision warning fires once and is then suppressed")))))
