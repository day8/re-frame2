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
  `--allow-sensitive-reads` disabled). NOTE the polarity: the restrictive
  default is the *disabled* opt-in, which is the most protective posture
  (sensitive events are dropped); enabling `--allow-sensitive-reads` is the
  operator's deliberate raw-egress choice. The pull-mode epoch tools
  (`trace-window`, `watch-epochs`) and the snapshot tool feed their
  trace / epoch slices through `strip-sensitive` / `scrub-snapshot`; a
  miss here ships a declared-sensitive event off-box.

  rf2-6wvh5/j90sb/f1ose were exactly this leak class at the egress seam.

  ## Two complementary egress checks

  1. **`strip-sensitive`** - the trace-event vector filter. With
     `--allow-sensitive-reads` disabled (`include? false`), every
     `:sensitive?`-stamped event (including the fail-closed malformed-truthy
     variants per rf2-ih7g4) MUST be removed.
  2. **`scrub-snapshot`** - the per-frame snapshot walker. With
     `--allow-sensitive-reads` disabled, no sensitive event may survive in
     any frame's `:traces` / `:epochs` slice, at any frame-map shape.

  ## Why property-style

  The pin-and-assert tests cover a fixed event list. This tier GENERATES
  event vectors that interleave sensitive / non-sensitive / malformed-
  stamp events at arbitrary positions and counts, and snapshot maps with
  arbitrary frame counts + arbitrary sensitive/clean mixes - then asserts
  the SENTINEL-bearing sensitive events never survive the
  allow-sensitive-disabled egress.
  A hostile corpus pins the fail-closed malformed-stamp variants
  (string `\"true\"`, keyword, number, non-empty coll) that the rf2-ih7g4
  fail-CLOSED posture must drop.

  ## Net property (verify-by-revert)

  Reverting `sensitive-event?` to match only the literal `true`
  (the pre-rf2-ih7g4 fail-OPEN check) makes the malformed-stamp property
  go RED - a `:sensitive? \"true\"` event survives the
  allow-sensitive-disabled egress.
  Reverting the `scrub-snapshot` `:traces`/`:epochs` scrub makes the
  snapshot property go RED. Confirmed by temporary local revert + restore
  (see PR Quality gates)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.mcp-base.sensitive :as sens]
            [re-frame.security.gen :as gen]))

;; ---------------------------------------------------------------------------
;; Expected-warning quieting (rf2-80jyfk → rf2-nrk066)
;; ---------------------------------------------------------------------------
;;
;; These property tests INTENTIONALLY drive thousands of malformed
;; `:sensitive?` stamps through `sens/strip-sensitive` / `scrub-snapshot` /
;; `sensitive-event?` (the rf2-ih7g4 fail-closed corpus). Each malformed
;; stamp fires `re-frame.mcp-base.sensitive`'s contract-drift warning to
;; stderr (JVM `*err*`) / `console.warn` (CLJS).
;;
;; Quieting these EXPECTED warnings on a green run is now the SHARED
;; responsibility of the quiet runners on BOTH runtimes, so this namespace
;; needs NO local `*err*` sink (rf2-nrk066): the CLJS node runner buffers
;; `console.warn` and the JVM runner (`re-frame.test-quiet.runner`) buffers
;; `*err*`/`System/err` into a bounded ring, dropping it on green and
;; replaying it only on red. Pre-rf2-nrk066 the JVM runner had no central
;; stderr handling, so this suite wrapped its whole run in a private `*err*`
;; sink (the rf2-80jyfk workaround) — that ad-hoc sink is removed now that
;; the runner owns the policy symmetrically.
;;
;; The warning's having FIRED is still asserted structurally — the
;; `malformed-count` counter is the framework's observability hook, pinned by
;; `allow-sensitive-disabled-malformed-stamp-counts-as-dropped` — so the
;; fail-closed log path
;; stays exercised + verified. A RED run keeps these diagnostics (the runner
;; replays the buffer), and clojure.test routes FAIL/ERROR/summary output
;; through `*test-out*` (the real `*out*`), so failure reporting is untouched.

(def ^:private sentinel "S3CR3T-rf2-3cfvt-EGRESS-DO-NOT-SHIP")

(defn- contains-sentinel?
  "Deep-walk `x`; true when the sentinel string appears ANYWHERE — as a
  value, inside a collection, in a secondary slot (`:tags :received`), or
  inside a stringified form. Thin wrapper over the shared
  `gen/contains-string?` (rf2-n5bkm7).

  Why a deep scan and not just `:tags :value` + `sensitive-event?`
  (rf2-h2yvs finding 1): the allow-sensitive-disabled contract is \"the
  sentinel never survives,\" not merely \"no survivor is still classified
  sensitive in its
  primary slot.\" The generated sensitive event also plants the sentinel in
  `:tags :received`; a survivor with its `:sensitive?` stamp stripped (so it
  no longer classifies sensitive) and the sentinel only in `:received` would
  slip past the narrow checks while this property stayed green. A deep scan
  catches that secondary-slot leak class. The sentinel is matched as an EXACT
  string leaf (`exact? true`); the shared `pr-str` fallback still substring-
  scans the stringified form."
  [x]
  (gen/contains-string? x sentinel true))

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
;; PROPERTY 1 - allow-sensitive-reads DISABLED: strip-sensitive removes every
;; sensitive + malformed-stamp event; the sentinel never survives.
;; ---------------------------------------------------------------------------

(deftest allow-sensitive-disabled-strips-every-sensitive-event
  (testing "rf2-6wvh5 - with include? false (--allow-sensitive-reads disabled,
            the restrictive default), strip-sensitive drops every
            :sensitive?-stamped (and malformed-truthy) event across 400
            generated mixed event vectors; the sentinel never survives"
    (let [result (gen/for-all
                   gen-event-vec 400 23
                   (fn [events]
                     (sens/reset-malformed-count!)
                     (let [[kept _dropped] (sens/strip-sensitive events false)]
                       ;; Deep-scan EVERY kept event for the sentinel in ANY
                       ;; slot (`:tags :value` AND the secondary `:tags
                       ;; :received` slot) — the contract is "the sentinel
                       ;; never survives," not "no primary-slot survivor is
                       ;; still classified sensitive" (rf2-h2yvs finding 1).
                       ;; The `not-any? sensitive-event?` check is retained as
                       ;; the complementary classification assertion.
                       (and (not-any? contains-sentinel? kept)
                            (not-any? sens/sensitive-event? kept)))))]
      (is (nil? result)
          (str "a sensitive event survived the allow-sensitive-disabled egress: "
               (pr-str (when result (dissoc result :threw))))))))

(deftest deep-scan-catches-secondary-slot-sentinel-survivor
  (testing "rf2-h2yvs finding 1 (non-vacuity) — a SURVIVING non-sensitive
            event that nonetheless carries the sentinel in the SECONDARY
            `:tags :received` slot is caught by the deep `contains-sentinel?`
            scan, even though it is NOT classified sensitive and has no
            sentinel in `:tags :value`. This is the leak class the prior
            (`:tags :value` + `sensitive-event?`) assertion missed; the
            negative fixture proves the hardened assertion is non-vacuous."
    ;; Construct the exact survivor: no :sensitive? stamp (so strip-sensitive
    ;; KEEPS it), sentinel ONLY in :tags :received (not :value).
    (let [survivor       {:operation :sub/recompute
                          :tags {:value "public-data"
                                 :received [sentinel]}}
          [kept dropped] (sens/strip-sensitive [survivor] false)]
      ;; strip-sensitive keeps it (it is not classified sensitive) ...
      (is (= [survivor] kept))
      (is (zero? dropped))
      ;; ... so the OLD narrow checks would BOTH read clean (vacuous green):
      (is (not-any? #(= sentinel (-> % :tags :value)) kept)
          "old `:tags :value` check is blind to the :received slot")
      (is (not-any? sens/sensitive-event? kept)
          "old classification check is blind: the survivor is non-sensitive")
      ;; ... but the NEW deep scan catches the secondary-slot leak:
      (is (some contains-sentinel? kept)
          "the deep scan MUST catch the sentinel hiding in :tags :received")
      (is (contains-sentinel? survivor)))))

(deftest allow-sensitive-disabled-malformed-stamp-counts-as-dropped
  (testing "rf2-ih7g4 fail-closed - a malformed truthy stamp is dropped AND
            bumps the observability counter (not silently passed)"
    (doseq [stamp malformed-stamps]
      (sens/reset-malformed-count!)
      (let [ev {:operation :x :sensitive? stamp :tags {:value sentinel}}
            [kept dropped] (sens/strip-sensitive [ev] false)]
        (is (= [] kept) (str "malformed stamp " (pr-str stamp) " was NOT dropped"))
        (is (= 1 dropped))
        ;; rf2-el9sw: `strip-sensitive` now classifies each event EXACTLY
        ;; ONCE (single-pass), so the malformed counter is a faithful
        ;; per-event metric. (Pre-fix it ran `sensitive-event?` twice —
        ;; `some` pre-scan + `filterv` drop-scan — so one event bumped the
        ;; counter ~2×; the assertion then could only check `(pos? ...)`.)
        (is (= 1 (sens/malformed-count))
            (str "malformed stamp " (pr-str stamp)
                 " must bump the counter exactly once per event"))))))

;; ---------------------------------------------------------------------------
;; PROPERTY 2 - allow-sensitive-reads ENABLED (opt-in): include? true ships
;; everything verbatim (the operator's deliberate raw-egress choice). Confirms
;; the opt-in is the single control point - not an unconditional scrub.
;; ---------------------------------------------------------------------------

(deftest allow-sensitive-enabled-opt-in-passes-through-verbatim
  (testing "with include? true (operator opted in via --allow-sensitive-reads),
            strip-sensitive is identity - the opt-in is the sole control point"
    (let [result (gen/for-all
                   gen-event-vec 200 29
                   (fn [events]
                     (let [[kept dropped] (sens/strip-sensitive events true)]
                       (and (= events kept) (zero? dropped)))))]
      (is (nil? result) (str "opt-in egress altered the events: " (pr-str result))))))

;; ---------------------------------------------------------------------------
;; Snapshot generators + PROPERTY 3 - scrub-snapshot leaves no sensitive
;; event in any frame's :traces / :epochs slice with --allow-sensitive-reads
;; disabled.
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
  "True when any frame's scrubbed :traces / :epochs slice still carries the
  sentinel ANYWHERE (deep scan), or still carries an event classified
  sensitive. Deep-scans ONLY the `:traces` / `:epochs` slices — `:app-db` is
  left alone by design (read-time scrubbing is trace/epoch-only), so a deep
  scan of the whole frame map would over-reach into the deliberately-kept
  app-db.

  rf2-h2yvs finding 1: the prior helper only re-ran `sensitive-event?` over
  the slices, so a survivor whose `:sensitive?` stamp was stripped but whose
  `:tags :received` still carried the sentinel would read as clean. The deep
  `contains-sentinel?` scan closes that secondary-slot gap."
  [scrubbed]
  (some (fn [[_frame fm]]
          (when (map? fm)
            (let [slices (concat (:traces fm) (:epochs fm))]
              (or (some contains-sentinel? slices)
                  (some sens/sensitive-event? slices)))))
        scrubbed))

(deftest allow-sensitive-disabled-scrub-snapshot-leaves-no-sensitive-event
  (testing "rf2-6wvh5 - with include? false (--allow-sensitive-reads disabled),
            scrub-snapshot strips sensitive events from EVERY frame's
            :traces/:epochs across 300 generated multi-frame snapshots;
            :app-db is left untouched"
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

(deftest snapshot-deep-scan-catches-secondary-slot-survivor
  (testing "rf2-h2yvs finding 1 (non-vacuity, snapshot path) — a frame whose
            scrubbed `:traces` carries a NON-sensitive event with the sentinel
            in `:tags :received` is flagged by the hardened
            `snapshot-leaks-sentinel?` deep scan, while `:app-db` (which carries
            no sentinel) is left alone by design."
    (let [survivor   {:operation :sub/recompute
                      :tags {:value "public-data" :received [sentinel]}}
          ;; Model a post-scrub snapshot where a secondary-slot survivor
          ;; remained in a frame slice (the leak class the old check missed).
          leaked     {:frame-0 {:traces [survivor]
                                :epochs []
                                :app-db {:public "kept"}}}
          clean      {:frame-0 {:traces [{:operation :fx/run
                                          :tags {:value "public-data"}}]
                                :epochs []
                                :app-db {:public "kept"}}}]
      ;; The old check (sensitive-event? over the slices) is blind here —
      ;; the survivor is non-sensitive — so it would read this as clean.
      (is (not (some sens/sensitive-event? (-> leaked :frame-0 :traces)))
          "old classification check is blind: the survivor is non-sensitive")
      ;; The hardened deep scan flags the secondary-slot leak ...
      (is (snapshot-leaks-sentinel? leaked)
          "deep scan MUST flag the sentinel hiding in a frame's :tags :received")
      ;; ... and does NOT false-positive on a genuinely clean snapshot.
      (is (not (snapshot-leaks-sentinel? clean))
          "deep scan must not flag a sentinel-free snapshot"))))

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
