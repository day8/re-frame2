(ns re-frame.security.mcp-egress-security-cljs-test
  "Adversarial tests for AI/MCP trace egress.

  With sensitive reads disabled, `strip-sensitive` removes every record
  carrying a truthy `:sensitive?` stamp before it crosses the MCP boundary —
  the filter story-mcp's egress runs over assertion records. Truthy
  non-boolean stamps are treated as sensitive because malformed metadata must
  fail closed. Enabling sensitive reads is the operator's explicit raw-egress
  opt-in."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [re-frame.mcp-base.sensitive :as rf.mcp-base.sensitive]
            [re-frame.security.gen :as rf.security.gen]))

;; The strip property intentionally drives thousands of malformed
;; `:sensitive?` stamps through `strip-sensitive`.
;; The quiet JVM and CLJS runners buffer the expected contract-drift warnings
;; and replay them on failure.

(def ^:private sentinel "S3CR3T-rf2-3cfvt-EGRESS-DO-NOT-SHIP")

(defn- contains-sentinel?
  "True when the sentinel survives anywhere in `x`, including secondary or
  stringified slots. The sentinel is a unique long literal, so a substring
  scan cannot be satisfied by an unrelated value."
  [x]
  (rf.security.gen/contains-string? x sentinel))

(def ^:private gen-clean-event
  "A non-sensitive trace event - no :sensitive? stamp (or explicit false)."
  (rf.security.gen/gen-fmap
    (fn [[op stamp]]
      (cond-> {:operation op :tags {:k "public-data"}}
        (= stamp :false) (assoc :sensitive? false)))
    (fn [rng]
      (let [[op rng1]    (rf.security.gen/rand-nth rng [:event/run-start :event/db-changed
                                            :sub/recompute :fx/run])
            [stamp rng2] (rf.security.gen/rand-nth rng1 [:absent :false])]
        [[op stamp] rng2]))))

(def ^:private gen-sensitive-event
  "A sensitive event carrying the sentinel in a value slot AND the literal
  top-level :sensitive? true stamp."
  (rf.security.gen/gen-fmap
    (fn [op]
      {:operation op
       :sensitive? true
       :tags {:value sentinel :received [sentinel]}})
    (rf.security.gen/gen-elem [:event/run-start :sub/recompute :fx/run])))

(def ^:private malformed-stamps
  ;; Truthy non-booleans indicate contract drift and fail closed.
  ["true" :yes 1 [:non-empty] {:a 1} "1"])

(def ^:private gen-malformed-sensitive-event
  "A sensitive event whose :sensitive? stamp is a truthy NON-boolean
  (contract drift). The fail-closed posture must still drop it."
  (rf.security.gen/gen-fmap
    (fn [[op stamp]]
      {:operation op :sensitive? stamp :tags {:value sentinel}})
    (fn [rng]
      (let [[op rng1]    (rf.security.gen/rand-nth rng [:event/run-start :sub/recompute])
            [stamp rng2] (rf.security.gen/rand-nth rng1 malformed-stamps)]
        [[op stamp] rng2]))))

(def ^:private gen-event
  (rf.security.gen/gen-one-of gen-clean-event gen-sensitive-event gen-malformed-sensitive-event))

(def ^:private gen-event-vec
  "A vector of 0..12 mixed events."
  (rf.security.gen/gen-vec (rf.security.gen/gen-int 0 13) gen-event))

(defn- clean-event?
  "The oracle's classifier, independent of the code under test: every
  generated clean event carries `:sensitive? false` or no stamp, and every
  other generated event a truthy one."
  [ev]
  (not (:sensitive? ev)))

(defn- expected-strip
  "What `strip-sensitive` must return with sensitive reads disabled: exactly
  the clean events, in order, beside the count of every event dropped, which
  feeds the `:dropped-sensitive` indicator (spec/Security.md §Behavioural
  MUSTs across the privacy surface)."
  [events]
  [(filterv clean-event? events)
   (count (remove clean-event? events))])

(deftest allow-sensitive-disabled-strip-sensitive-drops-exactly-the-sensitive-events
  ;; The sentinel scan guards the oracle; equality with the oracle catches
  ;; an over-strip and a miscount as well as a leak.
  (let [result (rf.security.gen/for-all
                 gen-event-vec 300 31
                 (fn [events]
                   (let [[kept :as actual] (rf.mcp-base.sensitive/strip-sensitive events false)]
                     (and (not-any? contains-sentinel? kept)
                          (not-any? rf.mcp-base.sensitive/sensitive-event? kept)
                          (= (expected-strip events) actual)))))]
    (is (nil? result)
        (str "strip-sensitive leaked, over-stripped or miscounted: "
             (pr-str (when result (dissoc result :threw)))))))

(deftest allow-sensitive-enabled-strip-sensitive-is-the-raw-opt-in
  ;; The operator's opt-in forwards every event, sensitive ones included.
  (let [events [{:operation :fx/run :sensitive? true :tags {:value sentinel}}
                {:operation :sub/recompute}]]
    (is (= [events 0] (rf.mcp-base.sensitive/strip-sensitive events true)))))
