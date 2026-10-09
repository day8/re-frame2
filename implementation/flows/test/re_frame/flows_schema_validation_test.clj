(ns re-frame.flows-schema-validation-test
  "Spec 013 §Flow output validation: a flow's computed value is validated
  against its optional `:schema` on every recompute, dev-only, through the
  pluggable validator seam. A failure emits `:rf.error/schema-validation-failure
  :where :flow-output` and the value is still written. When the frame's
  elision registry classifies the output, `:explain` (which re-ships the whole
  value) is redacted whole if sensitive, else replaced by a size marker if
  large.

  A predicate validator stands in for Malli (Spec 010 §Non-Malli validators)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(def ^:dynamic ^:private *captured* nil)

(defn- with-schema-violation-recorder
  "Reset the validator seam around the test, and record every
  schema-validation-failure trace into `*captured*`."
  [test-fn]
  (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
  (let [captured (atom [])]
    (binding [*captured* captured]
      (rf.trace.tooling/register-listener!
        ::schema-violation-recorder
        (fn [ev]
          (when (= :rf.error/schema-validation-failure (:operation ev))
            (swap! captured conj ev))))
      (try
        (test-fn)
        (finally
          (rf.trace.tooling/unregister-listener! ::schema-violation-recorder)
          (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns))))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  with-schema-violation-recorder)

(defn- violations [] @*captured*)

(defn- violation-for
  "The single recorded violation for `flow-id`."
  [flow-id]
  (let [vs (filter #(= flow-id (get-in % [:tags :rf.flow/id])) (violations))]
    (is (= 1 (count vs)) (str "exactly one violation for " flow-id))
    (first vs)))

(defn- area-flow!
  "`:area` = :w × :h at [:rect :area], seeded 3 × 4, with `extra` metadata."
  [extra]
  (rf/reg-event :seed (fn [_ _] {:db {:w 3 :h 4}}))
  (rf/reg-flow :area (merge {:inputs [[:w] [:h]] :output-path [:rect :area]} extra)
    (fn [w h] (* w h)))
  (rf/dispatch-sync [:seed]))

(defn- area [] (get-in (rf/app-db-value :rf/default) [:rect :area]))

(deftest non-conforming-output-emits-violation-but-still-writes
  (rf.schemas/set-schema-fns!
    {:validate (fn [schema value] (boolean (schema value)))
     :explain  (fn [schema value] (when-not (schema value) {:failed value}))})
  (rf/reg-event :seed (fn [_ _] {:db {:w 3 :h -4}}))
  (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area]
                      :schema (fn [v] (and (integer? v) (not (neg? v))))}
    (fn [w h] (* w h)))
  (rf/dispatch-sync [:seed])
  (is (= -12 (area)) "validation is observational, not a rollback")
  (let [ev (violation-for :area)]
    ;; A predicate-fn schema is opaque to the redaction walker, which fails
    ;; closed: every value-bearing slot is redacted and the event stamped.
    (is (= {:where :flow-output :rf.flow/id :area :failing-id :area :path [:rect :area]
            :value :rf/redacted :explain :rf/redacted :frame :rf/default}
           (select-keys (:tags ev) [:where :rf.flow/id :failing-id :path :value :explain :frame])))
    (is (= [:error true :no-recovery] ((juxt :op-type :sensitive? :recovery) ev)))
    (is (string? (get-in ev [:tags :reason])))))

(deftest present-nil-schema-is-delegated-not-skipped
  ;; Declaration presence is KEY presence: an explicit `{:schema nil}` reaches
  ;; the validator verbatim, and an absent key never consults it.
  (let [seen (atom [])]
    (rf.schemas/set-schema-fns! {:validate (fn [schema _] (swap! seen conj schema) false)})
    (rf/reg-flow :no-schema {:inputs [[:w]] :output-path [:plain]} identity)
    (area-flow! {:schema nil})
    (is (= [nil] @seen) "only the nil token reached the validator, once")
    (is (= [[:area :flow-output]] (mapv (comp (juxt :rf.flow/id :where) :tags) (violations))))
    (is (= 12 (area)))))

(deftest no-validator-soft-passes
  (rf.schemas/set-schema-fns! {:validate nil})
  (area-flow! {:schema (fn [_] false)})
  (is (= [12 []] [(area) (violations)])))

(deftest production-gate-elides-validation
  ;; Witnessed at the validator: the failure trace is itself debug-gated, so
  ;; its absence could not fail here.
  (let [consulted (atom 0)]
    (rf.schemas/set-schema-fns! {:validate (fn [_ _] (swap! consulted inc) false)})
    (with-redefs [rf.interop/debug-enabled? false]
      (area-flow! {:schema (fn [_] false)}))
    (is (= [12 0] [(area) @consulted]))))

;; The schemas below are walkable vector Malli forms with no `:sensitive?`
;; prop, so the seam's opaque-schema fail-closed arm cannot be what redacts
;; them: the frame's elision registry decides.

(deftest registry-sensitive-output-redacts-explain
  (rf/reg-event :seed (fn [_ _] {:db {:secret "hunter2-SECRET"}}))
  (rf/reg-flow :p2/token
               {:inputs [[:secret]] :output-path [:auth :token]
                :sensitive [[]] :schema [:map [:token :int]]}
               (fn [s] {:token s}))
  (rf/reg-flow :p2/token-leaf
               {:inputs [[:secret]] :output-path [:auth :token-leaf]
                :sensitive [[:token]] :schema [:map [:token :int]]}
               (fn [s] {:token s}))
  (rf/dispatch-sync [:seed])
  (is (= "hunter2-SECRET" (get-in (rf/app-db-value :rf/default) [:auth :token :token])))
  (doseq [[flow-id value] [[:p2/token      :rf/redacted]
                           [:p2/token-leaf {:token :rf/redacted}]]]
    (let [ev (violation-for flow-id)]
      (is (not (str/includes? (pr-str ev) "hunter2-SECRET")) (str flow-id))
      (is (= [true value :rf/redacted]
             [(:sensitive? ev) (get-in ev [:tags :value]) (get-in ev [:tags :explain])])
          (str flow-id ": stamped, :value path-precise, :explain redacted whole")))))

(deftest registry-sensitive-index-free-declaration-redacts-explain
  ;; `[:users :password]` governs `[:users <key> :password]`, so it reaches the
  ;; output `[:users :current]` without being a prefix or extension of it.
  (rf/reg-event :classify (fn [_ _] {:sensitive [[:users :password]]}))
  (rf/reg-event :seed (fn [_ _] {:db {:secret "hunter2-SECRET"}}))
  (rf/reg-flow :p2/current-user
               {:inputs [[:secret]] :output-path [:users :current]
                :schema [:map [:password :int]]}
               (fn [s] {:password s}))
  (rf/dispatch-sync [:classify])
  (reset! *captured* [])
  (rf/dispatch-sync [:seed])
  (let [ev (violation-for :p2/current-user)]
    (is (not (str/includes? (pr-str ev) "hunter2-SECRET")))
    (is (= [true {:password :rf/redacted} :rf/redacted]
           [(:sensitive? ev) (get-in ev [:tags :value]) (get-in ev [:tags :explain])]))))

(deftest unclassified-output-keeps-its-explanation
  ;; Declarations on unrelated paths leave an unclassified output's `:value`
  ;; and `:explain` raw and unstamped: neither redaction is blanket.
  (rf/reg-event :seed (fn [_ _] {:db {:plain "not-an-int"}}))
  (rf/reg-event :classify-elsewhere (fn [_ _] {:sensitive [[:elsewhere]]
                                               :large     [[:out-other]]}))
  (rf/reg-flow :p2/plain
               {:inputs [[:plain]] :output-path [:out]
                :schema [:map [:token :int]]}
               (fn [s] {:token s}))
  (rf/dispatch-sync [:classify-elsewhere])
  (reset! *captured* [])
  (rf/dispatch-sync [:seed])
  (let [ev (violation-for :p2/plain)]
    (is (= [nil nil {:token "not-an-int"} {:token "not-an-int"}]
           [(:sensitive? ev) (get-in ev [:tags :large?])
            (get-in ev [:tags :value]) (get-in ev [:tags :explain :value])]))))

(def ^:private blob
  "A distinctive payload, so a test can ask whether it rode the trace at all."
  (apply str (repeat 40 "BLOB-")))

(deftest registry-large-output-size-elides-explain
  ;; `:explain` is not path-anchored, so a large output's explanation is
  ;; replaced whole by a marker for the output at its `:output-path`.
  (rf/reg-event :seed (fn [_ _] {:db {:blob blob :n "not-an-int"}}))
  (rf/reg-event :classify (fn [_ _] {:large [[:reports :part :blob]]}))
  ;; The flow's own declaration over the whole output ...
  (rf/reg-flow :size/whole
               {:inputs [[:blob] [:n]] :output-path [:reports :whole]
                :large [[]] :schema [:map [:n :int]]}
               (fn [b n] {:blob b :n n}))
  ;; ... and a commit-plane `:large` effect over one slot of another output.
  (rf/reg-flow :size/part
               {:inputs [[:blob] [:n]] :output-path [:reports :part]
                :schema [:map [:n :int]]}
               (fn [b n] {:blob b :n n}))
  (rf/dispatch-sync [:classify])
  (reset! *captured* [])
  (rf/dispatch-sync [:seed])
  (is (= blob (get-in (rf/app-db-value :rf/default) [:reports :whole :blob])))
  (testing "a declaration over the whole output reuses the walker's marker"
    (let [ev (violation-for :size/whole) tags (:tags ev)]
      (is (not (str/includes? (pr-str ev) "BLOB-BLOB")))
      (is (= {:path [:reports :whole] :reason :flow}
             (select-keys (get-in tags [:value :rf.size/large-elided]) [:path :reason])))
      (is (= [(:value tags) true nil] [(:explain tags) (:large? tags) (:sensitive? ev)]))))
  (testing "a narrower declaration yields a marker for the whole output"
    (let [ev (violation-for :size/part) tags (:tags ev)]
      (is (not (str/includes? (pr-str ev) "BLOB-BLOB")))
      (is (= ["not-an-int" [:reports :part :blob]]
             [(get-in tags [:value :n]) (get-in tags [:value :blob :rf.size/large-elided :path])])
          ":value stays path-precise, marking only the declared slot")
      (is (= {:path   [:reports :part] :handle [:rf.elision/at [:reports :part]] :type :map
              :bytes  (count (pr-str {:blob blob :n "not-an-int"})) :reason :effect}
             (select-keys (get-in tags [:explain :rf.size/large-elided])
                          [:path :handle :type :bytes :reason])))
      (is (= [true nil] [(:large? tags) (:sensitive? ev)])))))

(deftest sensitive-and-large-output-still-redacts-explain
  ;; Sensitive wins over large, whether the registry or the schema says so.
  (rf/reg-event :seed (fn [_ _] {:db {:secret "hunter2-SECRET" :blob blob}}))
  (rf/reg-flow :both/whole
               {:inputs [[:secret] [:blob]] :output-path [:both :whole]
                :sensitive [[]] :large [[]] :schema [:map [:token :int]]}
               (fn [s b] {:token s :blob b}))
  (rf/reg-flow :both/slots
               {:inputs [[:secret] [:blob]] :output-path [:both :slots]
                :sensitive [[:token]] :large [[:blob]] :schema [:map [:token :int]]}
               (fn [s b] {:token s :blob b}))
  (rf/reg-flow :both/schema-sensitive
               {:inputs [[:secret] [:blob]] :output-path [:both :schema]
                :large [[]] :schema [:map [:token {:sensitive? true} :int]]}
               (fn [s b] {:token s :blob b}))
  (rf/dispatch-sync [:seed])
  (doseq [flow-id [:both/whole :both/slots :both/schema-sensitive]]
    (let [ev (violation-for flow-id)]
      (is (not (str/includes? (pr-str ev) "hunter2-SECRET")) (str flow-id))
      (is (= [true :rf/redacted nil]
             [(:sensitive? ev) (get-in ev [:tags :explain]) (get-in ev [:tags :large?])])
          (str flow-id ": stamped, :explain redacted, no size marker")))))
