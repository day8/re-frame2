(ns re-frame.security.mcp-egress-security-cljs-test
  "Adversarial tests for AI/MCP trace egress.

  With sensitive reads disabled, `strip-sensitive` removes every trace event
  carrying a truthy `:sensitive?` stamp and `scrub-snapshot` applies that rule
  to each frame's trace and epoch slices. Truthy non-boolean stamps are treated
  as sensitive because malformed metadata must fail closed. Enabling sensitive
  reads is the operator's explicit raw-egress opt-in."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.mcp-base.sensitive :as rf.mcp-base.sensitive]
            [re-frame.security.gen :as rf.security.gen]))

;; These property tests intentionally drive thousands of malformed
;; `:sensitive?` stamps through `sens/strip-sensitive` / `scrub-snapshot` /
;; `sensitive-event?`. The quiet JVM and CLJS runners buffer the expected
;; contract-drift warnings and replay them on failure. The malformed counter
;; below independently proves that the warning path ran.

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

(deftest allow-sensitive-disabled-strips-every-sensitive-event
  (testing "with sensitive reads disabled, strip-sensitive drops every
            :sensitive?-stamped (and malformed-truthy) event across 400
            generated mixed event vectors; the sentinel never survives"
    (let [result (rf.security.gen/for-all
                   gen-event-vec 400 23
                   (fn [events]
                     (rf.mcp-base.sensitive/reset-malformed-count!)
                     (let [[kept _dropped] (rf.mcp-base.sensitive/strip-sensitive events false)]
                       ;; Check both content and classification: stripping a
                       ;; stamp alone must not hide a secondary-slot leak.
                       (and (not-any? contains-sentinel? kept)
                            (not-any? rf.mcp-base.sensitive/sensitive-event? kept)))))]
      (is (nil? result)
          (str "a sensitive event survived the allow-sensitive-disabled egress: "
               (pr-str (when result (dissoc result :threw))))))))

(deftest deep-scan-catches-secondary-slot-sentinel-survivor
  (testing "the deep scan catches a sentinel in :tags :received even when the
            surviving event is not classified sensitive"
    (let [survivor       {:operation :sub/recompute
                          :tags {:value "public-data"
                                 :received [sentinel]}}
          [kept dropped] (rf.mcp-base.sensitive/strip-sensitive [survivor] false)]
      (is (= [survivor] kept))
      (is (zero? dropped))
      (is (not-any? #(= sentinel (-> % :tags :value)) kept)
          "a primary-slot check is blind to :received")
      (is (not-any? rf.mcp-base.sensitive/sensitive-event? kept)
          "classification alone is blind because the survivor is not stamped")
      (is (some contains-sentinel? kept)
          "the deep scan catches the sentinel in :tags :received")
      (is (contains-sentinel? survivor)))))

(deftest allow-sensitive-disabled-malformed-stamp-counts-as-dropped
  (testing "a malformed truthy stamp is dropped and increments the
            observability counter exactly once"
    (doseq [stamp malformed-stamps]
      (rf.mcp-base.sensitive/reset-malformed-count!)
      (let [ev {:operation :x :sensitive? stamp :tags {:value sentinel}}
            [kept dropped] (rf.mcp-base.sensitive/strip-sensitive [ev] false)]
        (is (= [] kept) (str "malformed stamp " (pr-str stamp) " was NOT dropped"))
        (is (= 1 dropped))
        (is (= 1 (rf.mcp-base.sensitive/malformed-count))
            (str "malformed stamp " (pr-str stamp)
                 " must bump the counter exactly once per event"))))))

(deftest allow-sensitive-enabled-opt-in-passes-through-verbatim
  (testing "with include? true (operator opted in via --allow-sensitive-reads),
            strip-sensitive is identity - the opt-in is the sole control point"
    (let [result (rf.security.gen/for-all
                   gen-event-vec 200 29
                   (fn [events]
                     (let [[kept dropped] (rf.mcp-base.sensitive/strip-sensitive events true)]
                       (and (= events kept) (zero? dropped)))))]
      (is (nil? result) (str "opt-in egress altered the events: " (pr-str result))))))

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

(deftest allow-sensitive-disabled-scrub-snapshot-leaves-no-sensitive-event
  (testing "with sensitive reads disabled, scrub-snapshot strips sensitive
            events from every frame's
            :traces/:epochs across 300 generated multi-frame snapshots;
            :app-db is left untouched"
    (let [result (rf.security.gen/for-all
                   gen-snapshot 300 31
                   (fn [snap]
                     (rf.mcp-base.sensitive/reset-malformed-count!)
                     (let [[scrubbed _dropped] (rf.mcp-base.sensitive/scrub-snapshot snap false)]
                       (and (not (snapshot-leaks-sentinel? scrubbed))
                            ;; :app-db must survive verbatim (read-time
                            ;; scrubbing is trace/epoch-only by design).
                            (every? (fn [[_f fm]]
                                      (= {:public "kept"} (:app-db fm)))
                                    scrubbed)))))]
      (is (nil? result)
          (str "scrub-snapshot left a sensitive event in a frame slice: "
               (pr-str (when result (dissoc result :threw))))))))

(deftest snapshot-deep-scan-catches-secondary-slot-survivor
  (testing "the snapshot deep scan catches a non-sensitive trace carrying the
            sentinel in :tags :received without inspecting :app-db"
    (let [survivor   {:operation :sub/recompute
                      :tags {:value "public-data" :received [sentinel]}}
          leaked     {:frame-0 {:traces [survivor]
                                :epochs []
                                :app-db {:public "kept"}}}
          clean      {:frame-0 {:traces [{:operation :fx/run
                                          :tags {:value "public-data"}}]
                                :epochs []
                                :app-db {:public "kept"}}}]
      (is (not (some rf.mcp-base.sensitive/sensitive-event? (-> leaked :frame-0 :traces)))
          "classification alone is blind because the survivor is not stamped")
      (is (snapshot-leaks-sentinel? leaked)
          "deep scan flags the sentinel in a frame's :tags :received")
      (is (not (snapshot-leaks-sentinel? clean))
          "deep scan must not flag a sentinel-free snapshot"))))

(deftest malformed-stamp-corpus-fail-closed
  (testing "each truthy non-boolean stamp classifies as sensitive"
    (doseq [stamp malformed-stamps]
      (is (true? (rf.mcp-base.sensitive/sensitive-event? {:sensitive? stamp}))
          (str "stamp " (pr-str stamp) " must classify as sensitive (drop)")))
    (testing "explicit false / nil / absent pass (non-sensitive)"
      (is (false? (rf.mcp-base.sensitive/sensitive-event? {:sensitive? false})))
      (is (false? (rf.mcp-base.sensitive/sensitive-event? {:sensitive? nil})))
      (is (false? (rf.mcp-base.sensitive/sensitive-event? {:operation :x}))))))
