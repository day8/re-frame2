(ns re-frame.security.mcp-egress-security-cljs-test
  "Adversarial tests for AI/MCP trace egress.

  With sensitive reads disabled, `strip-sensitive` removes every trace event
  carrying a truthy `:sensitive?` stamp and `scrub-snapshot` applies that rule
  to each frame's trace and epoch slices. Truthy non-boolean stamps are treated
  as sensitive because malformed metadata must fail closed. Enabling sensitive
  reads is the operator's explicit raw-egress opt-in."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [re-frame.mcp-base.sensitive :as rf.mcp-base.sensitive]
            [re-frame.security.gen :as rf.security.gen]))

;; The scrub property intentionally drives thousands of malformed
;; `:sensitive?` stamps through `scrub-snapshot`.
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

(def ^:private gen-frame
  "A per-frame snapshot map with mixed-sensitivity :traces + :epochs slices
  and an :app-db (which scrub-snapshot leaves alone by design)."
  (fn [rng]
    (let [[traces rng1] (gen-event-vec rng)
          [epochs rng2] (gen-event-vec rng1)]
      [{:traces traces
        :epochs epochs
        :app-db {:public "kept"}}
       rng2])))

(def ^:private gen-snapshot
  "A snapshot map: 1..4 frame-keyed entries."
  (fn [rng]
    (let [[n rng1] ((rf.security.gen/gen-int 1 5) rng)]
      (loop [i 0, rng rng1, acc {}]
        (if (< i n)
          (let [[frame rng'] (gen-frame rng)]
            (recur (inc i) rng' (assoc acc (keyword (str "frame-" i)) frame)))
          [acc rng])))))

(defn- snapshot-leaks-sentinel?
  "True when a frame's scrubbed trace or epoch slices still contain the
  sentinel or an event classified sensitive. `:app-db` is deliberately outside
  this low-level trace/epoch scrub."
  [scrubbed]
  (some (fn [[_frame fm]]
          (when (map? fm)
            (let [slices (concat (:traces fm) (:epochs fm))]
              (or (some contains-sentinel? slices)
                  (some rf.mcp-base.sensitive/sensitive-event? slices)))))
        scrubbed))

(defn- clean-event?
  "The oracle's classifier, independent of the code under test: every
  generated clean event carries `:sensitive? false` or no stamp, and every
  other generated event a truthy one."
  [ev]
  (not (:sensitive? ev)))

(defn- expected-scrub
  "What `scrub-snapshot` must return with sensitive reads disabled: every
  frame keeps exactly its clean `:traces` and `:epochs` events, in order,
  and `:app-db` untouched (the scrub is trace/epoch-only by design), beside
  the count of every event dropped, which feeds the `:dropped-sensitive`
  indicator (spec/Security.md §Behavioural MUSTs across the privacy surface)."
  [snap]
  [(into {}
         (map (fn [[frame fm]]
                [frame (-> fm
                           (update :traces #(filterv clean-event? %))
                           (update :epochs #(filterv clean-event? %)))]))
         snap)
   (count (remove clean-event? (mapcat #(concat (:traces %) (:epochs %)) (vals snap))))])

(deftest allow-sensitive-disabled-scrub-snapshot-drops-exactly-the-sensitive-events
  ;; The sentinel scan guards the oracle; equality with the oracle catches
  ;; an over-strip and a miscount as well as a leak.
  (let [result (rf.security.gen/for-all
                 gen-snapshot 300 31
                 (fn [snap]
                   (let [actual (rf.mcp-base.sensitive/scrub-snapshot snap false)]
                     (and (not (snapshot-leaks-sentinel? (first actual)))
                          (= (expected-scrub snap) actual)))))]
    (is (nil? result)
        (str "scrub-snapshot leaked, over-stripped or miscounted: "
             (pr-str (when result (dissoc result :threw)))))))
