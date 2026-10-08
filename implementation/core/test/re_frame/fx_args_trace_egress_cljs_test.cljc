(ns re-frame.fx-args-trace-egress-cljs-test
  "An fx registration's `:sensitive` classification reaches EVERY trace slot
  that carries the fx's args: the `:rf.event/fx` aggregate on `:rf.fx/do-fx`,
  the per-effect `:rf.fx/handled`, and the fx error traces
  (`:rf.error/fx-handler-exception` and siblings) — not only `:rf.fx/handled`.
  An unclassified control fx rides raw.

  The live arms read the dev trace, so every trace read sits inside a
  `(when rf.interop/debug-enabled? …)` arm (a no-leak sweep over an empty
  stream passes for free). What stays always-on is that the fx BODIES receive
  the RAW token — redaction is egress-only — and the projector table, which
  drives `project-trace-event` on hand-built slot shapes."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.interop :as rf.interop]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private sentinel "FX-ARGS-EGRESS-SENTINEL-6h3c02")

(def ^:private frame-id :fx-args-egress/frame)

;; What the fx bodies received: the token must be in flight for its absence
;; from the trace slots to mean anything.
(def ^:private body-args (atom {}))

(defn- register! []
  (reset! body-args {})
  (rf/make-frame {:id frame-id})
  (rf/reg-fx :fx-args/store {:sensitive [[:token]]}
    (fn [_ args] (swap! body-args assoc :fx-args/store args) nil))
  (rf/reg-fx :fx-args/store-throwing {:sensitive [[:token]]}
    (fn [_ args]
      (swap! body-args assoc :fx-args/store-throwing args)
      (throw (ex-info "fx blew up" {}))))
  (rf/reg-fx :fx-args/audit
    (fn [_ args] (swap! body-args assoc :fx-args/audit args) nil))
  (rf/reg-event :fx-args/succeed
    (fn [_ _]
      {:fx [[:fx-args/store {:token sentinel}]
            [:fx-args/audit {:msg "a benign audit line"}]]}))
  (rf/reg-event :fx-args/fail
    (fn [_ _]
      {:fx [[:fx-args/store-throwing {:token sentinel}]]})))

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- contains-sentinel?
  "True when the sentinel string appears anywhere in a nested structure."
  [x]
  (cond
    (string? x) #?(:clj (.contains ^String x sentinel)
                   :cljs (not= -1 (.indexOf x sentinel)))
    (map? x)    (boolean (some contains-sentinel? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some contains-sentinel? x))
    :else       false))

(deftest success-arm-redacts-classified-fx-args-in-every-slot
  (testing "a classified fx's token is redacted in BOTH the :rf.event/fx
            aggregate AND :rf.fx/handled, while the control fx rides raw"
    (register!)
    (let [acc (collect-traces! ::success)]
      (rf/dispatch-sync [:fx-args/succeed] {:frame frame-id})
      (rf/unregister-listener! :trace ::success)
      (is (= {:fx-args/store {:token sentinel}
              :fx-args/audit {:msg "a benign audit line"}}
             @body-args)
          "both fx bodies received their RAW args")
      (when rf.interop/debug-enabled?
        (let [do-fx (filterv #(= :rf.fx/do-fx (:operation %)) @acc)]
          (is (seq do-fx) ":rf.fx/do-fx was emitted with the effect vector")
          (doseq [ev do-fx]
            (is (= [[:fx-args/store {:token rf.privacy/redacted-sentinel}]
                    [:fx-args/audit {:msg "a benign audit line"}]]
                   (get-in ev [:tags :rf.event/fx]))
                "the classified entry redacts in :rf.event/fx; the control rides raw")))
        (let [handled (filterv #(= :fx-args/store (get-in % [:tags :rf.fx/id])) @acc)]
          (is (seq handled) "the classified fx emitted a :rf.fx/handled trace")
          (doseq [ev handled]
            (is (= {:token rf.privacy/redacted-sentinel} (get-in ev [:tags :rf.fx/args])))))
        (let [audit-handled (filterv #(= :fx-args/audit (get-in % [:tags :rf.fx/id])) @acc)]
          (is (seq audit-handled) "the control fx emitted a :rf.fx/handled trace")
          (doseq [ev audit-handled]
            (is (= {:msg "a benign audit line"} (get-in ev [:tags :rf.fx/args]))
                "the control fx's args ride raw (no over-redaction)")))
        (is (not-any? contains-sentinel? (map :tags @acc))
            "the token appears raw in no trace tag")))))

(deftest error-arm-redacts-fx-args-on-fx-handler-exception
  (testing "when a classified fx throws, :rf.error/fx-handler-exception redacts
            its :rf.fx/args :token"
    (register!)
    (let [acc (collect-traces! ::error)]
      (rf/dispatch-sync [:fx-args/fail] {:frame frame-id})
      (rf/unregister-listener! :trace ::error)
      (is (= {:token sentinel} (:fx-args/store-throwing @body-args))
          "the throwing fx body received the RAW token")
      (when rf.interop/debug-enabled?
        (let [errs (filterv #(= :rf.error/fx-handler-exception (:operation %)) @acc)]
          (is (seq errs) "the throwing fx emitted an :rf.error/fx-handler-exception trace")
          (doseq [ev errs]
            (is (= :fx-args/store-throwing (get-in ev [:tags :rf.fx/id])))
            (is (= {:token rf.privacy/redacted-sentinel} (get-in ev [:tags :rf.fx/args])))
            (is (not (contains-sentinel? (:tags ev)))
                "the token appears nowhere raw in the error trace tags")))))))

;; The projector keys off the SLOT SHAPE, not the operation, so every op that
;; stamps `[:rf.fx/id :rf.fx/args]` redacts — including the error and skip ops.
(deftest projector-redacts-the-per-effect-args-slot-on-every-op
  (register!)
  (doseq [op [:rf.fx/handled :rf.error/fx-handler-exception :rf.fx/skipped-on-platform]]
    (testing (str op)
      (is (= {:token rf.privacy/redacted-sentinel :note "plain"}
             (:rf.fx/args (:tags (rf.classification/project-trace-event
                                   {:operation op
                                    :tags {:frame      frame-id
                                           :rf.fx/id   :fx-args/store
                                           :rf.fx/args {:token sentinel :note "plain"}}}))))))))
