(ns re-frame.fx-args-trace-egress-cljs-test
  "rf2-6h3c02 — an fx registration's `:sensitive` classification must reach
  EVERY trace slot that carries the fx's args, not only the per-effect
  `:rf.fx/handled` slot.

  Before this fix `re-frame.classification/project-trace-event` applied the fx
  registration's `:sensitive` only inside `project-fx-tags`, gated on op
  `:rf.fx/handled`. Two OTHER slots carried the SAME fx args RAW, unreachable by
  any app-side classification:

    1. `:rf.event/fx` on the `:rf.fx/do-fx` trace — the handler's WHOLE returned
       effect vector, stamped raw. (The projector walked the sibling
       `:rf.event/db` slot but not this one.)
    2. `:rf.fx/args` on the ALWAYS-ON fx error traces
       (`:rf.error/fx-handler-exception` + siblings). The more serious of the
       two: that trace is production-survivable — it fans out through the
       always-on error-emit listener, not just the dev trace.

  This suite is the adversarial regression: it drives a `reg-fx` declaring
  `{:sensitive [[:token]]}` through a SUCCESS arm and an ERROR arm and pins the
  sentinel token absent from every fx-arg-bearing slot, PLUS a non-sensitive
  control fx that must ride RAW (no over-redaction). Each sensitive assertion
  FAILS before the fix and PASSES after.

  Dual-runtime `.cljc` (`*-cljs-test` ns): the shadow-cljs `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up —
  traces fire in both runtimes (`goog.DEBUG` / JVM `debug-enabled?` default on)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as classification]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; A UNIQUE sentinel — appears nowhere else, so a whole-stream scan that finds
;; it can only be hitting THIS fx's token arg.
(def ^:private sentinel "FX-ARGS-EGRESS-SENTINEL-6h3c02")

(def ^:private frame-id :fx-args-egress/frame)

(defn- register! []
  (rf/reg-frame frame-id {})
  ;; A CLASSIFIED fx — its own registration declares [:token] sensitive.
  (rf/reg-fx :fx-args/store {:sensitive [[:token]]}
    (fn [_ _] nil))
  ;; A CLASSIFIED fx that THROWS — to exercise the error-arm :rf.fx/args slot
  ;; carried by :rf.error/fx-handler-exception (production-survivable).
  (rf/reg-fx :fx-args/store-throwing {:sensitive [[:token]]}
    (fn [_ _] (throw (ex-info "fx blew up" {}))))
  ;; A NON-sensitive control fx — its args must ride RAW (guard against
  ;; over-redaction of unclassified fx).
  (rf/reg-fx :fx-args/audit
    (fn [_ _] nil))
  ;; SUCCESS event: returns both the classified store fx and the control fx.
  (rf/reg-event :fx-args/succeed
    (fn [_ _]
      {:fx [[:fx-args/store {:token sentinel}]
            [:fx-args/audit {:msg "a benign audit line"}]]}))
  ;; ERROR event: returns the throwing classified fx.
  (rf/reg-event :fx-args/fail
    (fn [_ _]
      {:fx [[:fx-args/store-throwing {:token sentinel}]]})))

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- contains-sentinel?
  "True when the sentinel string appears ANYWHERE in a nested data structure —
  the recursive scan an off-box shipper / dev tool applies."
  [x]
  (cond
    (string? x) #?(:clj (.contains ^String x sentinel)
                   :cljs (not= -1 (.indexOf x sentinel)))
    (map? x)    (boolean (some contains-sentinel? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some contains-sentinel? x))
    :else       false))

;; ---------------------------------------------------------------------------
;; SUCCESS arm — :rf.event/fx (on :rf.fx/do-fx) + :rf.fx/handled both redact;
;; the control fx rides raw.
;; ---------------------------------------------------------------------------

(deftest success-arm-redacts-classified-fx-args-in-every-slot
  (testing "a classified fx's token is redacted in BOTH the :rf.event/fx
            aggregate (on :rf.fx/do-fx) AND the per-effect :rf.fx/handled slot,
            while the non-sensitive control fx rides raw"
    (register!)
    (let [acc (collect-traces! ::success)]
      (rf/dispatch-sync [:fx-args/succeed] {:frame frame-id})
      (rf/unregister-listener! :trace ::success)

      ;; --- :rf.event/fx on :rf.fx/do-fx: the classified entry redacts ---
      (let [do-fx (->> @acc (filterv #(= :rf.fx/do-fx (:operation %))))]
        (is (seq do-fx) ":rf.fx/do-fx was emitted with the effect vector")
        (doseq [ev do-fx]
          (let [fx-vec (get-in ev [:tags :rf.event/fx])
                store  (first (filter #(= :fx-args/store (first %)) fx-vec))
                audit  (first (filter #(= :fx-args/audit (first %)) fx-vec))]
            (is (= privacy/redacted-sentinel (get-in store [1 :token]))
                "the classified fx's :token reads :rf/redacted in :rf.event/fx")
            (is (= :fx-args/store (first store))
                "shape retained — the fx-id survives")
            (is (= {:msg "a benign audit line"} (second audit))
                "the NON-sensitive control fx rides RAW (no over-redaction)"))))

      ;; --- per-effect :rf.fx/handled: unchanged redaction (control) ---
      (let [handled (->> @acc
                         (filterv #(= :fx-args/store (get-in % [:tags :rf.fx/id]))))]
        (is (seq handled) "the classified fx emitted a :rf.fx/handled trace")
        (doseq [ev handled]
          (is (= privacy/redacted-sentinel (get-in ev [:tags :rf.fx/args :token]))
              "the :token arg reads :rf/redacted in :rf.fx/handled (unchanged)")))

      (let [audit-handled (->> @acc
                               (filterv #(= :fx-args/audit (get-in % [:tags :rf.fx/id]))))]
        (is (seq audit-handled) "the control fx also emitted a :rf.fx/handled trace")
        (doseq [ev audit-handled]
          (is (= {:msg "a benign audit line"} (get-in ev [:tags :rf.fx/args]))
              "the control fx's args ride RAW in :rf.fx/handled (no over-redaction)")))

      ;; --- whole-stream sweep: the sentinel appears in NO trace tag ---
      (let [checked (atom 0)]
        (doseq [ev @acc
                [_ v] (:tags ev)]
          (swap! checked inc)
          (is (not (contains-sentinel? v))
              (str "the token must not appear raw in " (:operation ev))))
        (is (pos? @checked) "the sweep actually inspected trace tags")))))

;; ---------------------------------------------------------------------------
;; ERROR arm — the production-survivable :rf.error/fx-handler-exception carries
;; :rf.fx/args; it must redact the classified token.
;; ---------------------------------------------------------------------------

(deftest error-arm-redacts-fx-args-on-fx-handler-exception
  (testing "when a classified fx throws, :rf.error/fx-handler-exception redacts
            its :rf.fx/args :token (the always-on, production-survivable slot)"
    (register!)
    (let [acc (collect-traces! ::error)]
      (rf/dispatch-sync [:fx-args/fail] {:frame frame-id})
      (rf/unregister-listener! :trace ::error)

      (let [errs (->> @acc
                      (filterv #(= :rf.error/fx-handler-exception (:operation %))))]
        (is (seq errs)
            "the throwing classified fx emitted an :rf.error/fx-handler-exception trace")
        (doseq [ev errs]
          (is (= privacy/redacted-sentinel (get-in ev [:tags :rf.fx/args :token]))
              "the :rf.fx/args :token reads :rf/redacted on the error trace")
          (is (= :fx-args/store-throwing (get-in ev [:tags :rf.fx/id]))
              "shape retained — the fx-id survives")
          (is (not (contains-sentinel? (:tags ev)))
              "the token appears nowhere raw in the error trace tags"))))))

;; ---------------------------------------------------------------------------
;; The registration owns its classification (what the projector consumes).
;; ---------------------------------------------------------------------------

(deftest classified-fx-registration-declares-its-path
  (testing "the fx registration owns the [:token] sensitive path the projector
            reads at egress"
    (register!)
    (is (= {:sensitive [[:token]]}
           (classification/registration-classification :fx :fx-args/store))
        ":fx-args/store owns [:token]")))
