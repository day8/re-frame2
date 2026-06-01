(ns re-frame.security.mcp-egress-security-cljs-test
  "Adversarial-property security tier - AI/MCP-boundary egress redaction
  (rf2-3cfvt, surface 3; the rf2-6wvh5 / rf2-j90sb / rf2-f1ose class).

  ## The boundary

  `re-frame.mcp-base.sensitive` is the framework-published default-suppress
  filter every MCP forwarder (re-frame2-pair-mcp, story-mcp, Sentry/
  Honeybadger forwarders) MUST route trace-like data through before it
  crosses the trust boundary into the agent surface. Per Spec 009 Privacy:
  a trace event stamped top-level `:sensitive? true` MUST be DROPPED when
  the caller has not opted in (`include? = false`, the published default -
  the `--allow-sensitive-reads` boot gate OFF). The pull-mode epoch tools
  (`trace-window`, `watch-epochs`) and the snapshot tool feed their
  trace / epoch slices through `strip-sensitive` / `scrub-snapshot`; a
  miss here ships a declared-sensitive event off-box.

  rf2-6wvh5/j90sb/f1ose were exactly this leak class at the egress seam.

  ## Two complementary egress checks

  1. **`strip-sensitive`** - the trace-event vector filter. With the gate
     OFF, every `:sensitive?`-stamped event (including the fail-closed
     malformed-truthy variants per rf2-ih7g4) MUST be removed.
  2. **`scrub-snapshot`** - the per-frame snapshot walker. With the gate
     OFF, no sensitive event may survive in any frame's `:traces` /
     `:epochs` slice, at any frame-map shape.

  ## Why property-style

  The pin-and-assert tests cover a fixed event list. This tier GENERATES
  event vectors that interleave sensitive / non-sensitive / malformed-
  stamp events at arbitrary positions and counts, and snapshot maps with
  arbitrary frame counts + arbitrary sensitive/clean mixes - then asserts
  the SENTINEL-bearing sensitive events never survive the gate-OFF egress.
  A hostile corpus pins the fail-closed malformed-stamp variants
  (string `\"true\"`, keyword, number, non-empty coll) that the rf2-ih7g4
  fail-CLOSED posture must drop.

  ## Net property (verify-by-revert)

  Reverting `sensitive-event?` to match only the literal `true`
  (the pre-rf2-ih7g4 fail-OPEN check) makes the malformed-stamp property
  go RED - a `:sensitive? \"true\"` event survives the gate-OFF egress.
  Reverting the `scrub-snapshot` `:traces`/`:epochs` scrub makes the
  snapshot property go RED. Confirmed by temporary local revert + restore
  (see PR Quality gates)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.mcp-base.sensitive :as sens]
            [re-frame.security.gen :as gen]))

(def ^:private sentinel "S3CR3T-rf2-3cfvt-EGRESS-DO-NOT-SHIP")

;; ---------------------------------------------------------------------------
;; Event generators.
;; ---------------------------------------------------------------------------

(def ^:private gen-clean-event
  "A non-sensitive trace event - no :sensitive? stamp (or explicit false)."
  (gen/gen-fmap
    (fn [[op stamp]]
      (cond-> {:operation op :tags {:k "public-data"}}
        (= stamp :false) (assoc :sensitive? false)))
    (fn [rng]
      (let [[op rng1]    (gen/rand-nth rng [:event/run-start :event/db-changed
                                            :sub/recompute :fx/run])
            [stamp rng2] (gen/rand-nth rng1 [:absent :false])]
        [[op stamp] rng2]))))

(def ^:private gen-sensitive-event
  "A sensitive event carrying the sentinel in a value slot AND the literal
  top-level :sensitive? true stamp."
  (gen/gen-fmap
    (fn [op]
      {:operation op
       :sensitive? true
       :tags {:value sentinel :received [sentinel]}})
    (gen/gen-elem [:event/run-start :sub/recompute :fx/run])))

(def ^:private malformed-stamps
  ;; rf2-ih7g4 fail-CLOSED: any truthy non-boolean stamp must DROP.
  ["true" :yes 1 [:non-empty] {:a 1} "1"])

(def ^:private gen-malformed-sensitive-event
  "A sensitive event whose :sensitive? stamp is a truthy NON-boolean
  (contract drift). The fail-closed posture must still drop it."
  (gen/gen-fmap
    (fn [[op stamp]]
      {:operation op :sensitive? stamp :tags {:value sentinel}})
    (fn [rng]
      (let [[op rng1]    (gen/rand-nth rng [:event/run-start :sub/recompute])
            [stamp rng2] (gen/rand-nth rng1 malformed-stamps)]
        [[op stamp] rng2]))))

(def ^:private gen-event
  (gen/gen-one-of gen-clean-event gen-sensitive-event gen-malformed-sensitive-event))

(def ^:private gen-event-vec
  "A vector of 0..12 mixed events."
  (gen/gen-vec (gen/gen-int 0 13) gen-event))

;; ---------------------------------------------------------------------------
;; PROPERTY 1 - gate OFF: strip-sensitive removes every sensitive +
;; malformed-stamp event; the sentinel never survives.
;; ---------------------------------------------------------------------------

(deftest gate-off-strips-every-sensitive-event
  (testing "rf2-6wvh5 - with include? false (gate OFF), strip-sensitive drops
            every :sensitive?-stamped (and malformed-truthy) event across 400
            generated mixed event vectors; the sentinel never survives"
    (let [result (gen/for-all
                   gen-event-vec 400 23
                   (fn [events]
                     (sens/reset-malformed-count!)
                     (let [[kept _dropped] (sens/strip-sensitive events false)]
                       ;; No surviving event may carry the sentinel, and no
                       ;; survivor may itself be classified sensitive.
                       (and (not-any? #(= sentinel (-> % :tags :value)) kept)
                            (not-any? sens/sensitive-event? kept)))))]
      (is (nil? result)
          (str "a sensitive event survived the gate-OFF egress: "
               (pr-str (when result (dissoc result :threw))))))))

(deftest gate-off-malformed-stamp-counts-as-dropped
  (testing "rf2-ih7g4 fail-closed - a malformed truthy stamp is dropped AND
            bumps the observability counter (not silently passed)"
    (doseq [stamp malformed-stamps]
      (sens/reset-malformed-count!)
      (let [ev {:operation :x :sensitive? stamp :tags {:value sentinel}}
            [kept dropped] (sens/strip-sensitive [ev] false)]
        (is (= [] kept) (str "malformed stamp " (pr-str stamp) " was NOT dropped"))
        (is (= 1 dropped))
        ;; strip-sensitive scans the event with `some` (fast-path) then
        ;; `filterv` (drop-path) - each calls `sensitive-event?`, so a single
        ;; malformed event bumps the counter once per scan. Assert it fired
        ;; (>= 1), not an exact count tied to the scan strategy.
        (is (pos? (sens/malformed-count))
            (str "malformed stamp " (pr-str stamp) " did not bump the counter"))))))

;; ---------------------------------------------------------------------------
;; PROPERTY 2 - gate ON + opt-in: include? true ships everything verbatim
;; (the operator's deliberate raw-egress choice). Confirms the gate is the
;; single control point - not an unconditional scrub.
;; ---------------------------------------------------------------------------

(deftest gate-on-opt-in-passes-through-verbatim
  (testing "with include? true (operator opted in via --allow-sensitive-reads),
            strip-sensitive is identity - the gate is the sole control point"
    (let [result (gen/for-all
                   gen-event-vec 200 29
                   (fn [events]
                     (let [[kept dropped] (sens/strip-sensitive events true)]
                       (and (= events kept) (zero? dropped)))))]
      (is (nil? result) (str "opt-in egress altered the events: " (pr-str result))))))

;; ---------------------------------------------------------------------------
;; Snapshot generators + PROPERTY 3 - scrub-snapshot leaves no sensitive
;; event in any frame's :traces / :epochs slice with the gate OFF.
;; ---------------------------------------------------------------------------

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
    (let [[n rng1] ((gen/gen-int 1 5) rng)]
      (loop [i 0, rng rng1, acc {}]
        (if (< i n)
          (let [[frame rng'] (gen-frame rng)]
            (recur (inc i) rng' (assoc acc (keyword (str "frame-" i)) frame)))
          [acc rng])))))

(defn- snapshot-leaks-sentinel?
  "True when any frame's scrubbed :traces / :epochs slice still carries a
  sensitive event (the sentinel)."
  [scrubbed]
  (some (fn [[_frame fm]]
          (when (map? fm)
            (some sens/sensitive-event? (concat (:traces fm) (:epochs fm)))))
        scrubbed))

(deftest gate-off-scrub-snapshot-leaves-no-sensitive-event
  (testing "rf2-6wvh5 - with include? false, scrub-snapshot strips sensitive
            events from EVERY frame's :traces/:epochs across 300 generated
            multi-frame snapshots; :app-db is left untouched"
    (let [result (gen/for-all
                   gen-snapshot 300 31
                   (fn [snap]
                     (sens/reset-malformed-count!)
                     (let [[scrubbed _dropped] (sens/scrub-snapshot snap false)]
                       (and (not (snapshot-leaks-sentinel? scrubbed))
                            ;; :app-db must survive verbatim (read-time
                            ;; scrubbing is trace/epoch-only by design).
                            (every? (fn [[_f fm]]
                                      (= {:public "kept"} (:app-db fm)))
                                    scrubbed)))))]
      (is (nil? result)
          (str "scrub-snapshot left a sensitive event in a frame slice: "
               (pr-str (when result (dissoc result :threw))))))))

;; ---------------------------------------------------------------------------
;; HOSTILE CORPUS - the named fail-closed stamp variants, pinned.
;; ---------------------------------------------------------------------------

(deftest malformed-stamp-corpus-fail-closed
  (testing "rf2-ih7g4 - each named truthy non-boolean stamp drops (fail-CLOSED)"
    (doseq [stamp malformed-stamps]
      (is (true? (sens/sensitive-event? {:sensitive? stamp}))
          (str "stamp " (pr-str stamp) " must classify as sensitive (drop)")))
    (testing "explicit false / nil / absent pass (non-sensitive)"
      (is (false? (sens/sensitive-event? {:sensitive? false})))
      (is (false? (sens/sensitive-event? {:sensitive? nil})))
      (is (false? (sens/sensitive-event? {:operation :x}))))))
