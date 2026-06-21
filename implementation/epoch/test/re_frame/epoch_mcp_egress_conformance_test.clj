(ns re-frame.epoch-mcp-egress-conformance-test
  "rf2-xrlyi — MCP-style egress conformance for `projected-record` +
  `projected-history`. Per Spec Security.md §Epoch privacy posture (line
  104): 'Any tool that egresses an epoch record over an MCP wire, an HTTP
  forwarder, a log shipper, or any process boundary MUST route the record
  through the projected-record helper before egress.'

  Coverage gap closed (rf2-kp835 Phase-1 audit): the `projected-record` and
  `projected-history` public-surface symbols had 0 callers in `tools/`. The
  MCP-side accessors (Xray-MCP `watch-epochs`, story / pair recorders)
  haven't shipped end-to-end calls yet — Mike's Phase-2 decision (sibling
  bead rf2-xrlyi) kept both symbols canonical and asked for a conformance
  test exercising them via a representative MCP-style call path.

  This file pins the contract from the **forwarder perspective**: the
  per-leaf redaction matrix lives in `epoch_privacy_test.clj`; the
  redact-fn composition matrix lives in `epoch_redact_fn_projection_test.clj`.
  Here we exercise the full off-box-forwarder pattern an MCP server runs:

    1. Build a realistic mixed ring (sensitive + large + bookkeeping-only
       records, halted-destroy records, the empty case).
    2. Run the ring through `projected-record` (per-record forwarder shape,
       e.g. `register-epoch-listener!` ship!) AND `projected-history` (bulk-egress
       shape, e.g. `watch-epochs` initial snapshot).
    3. Assert the off-box egress contract:
         - No raw sensitive bytes anywhere in the projected output (the
           security claim the MCP wire boundary depends on).
         - No raw large bytes anywhere in the projected output (the
           token-budget claim the MCP wire boundary depends on).
         - Bookkeeping slots (`:epoch-id`, `:frame`, `:committed-at`,
           `:event-id`, `:outcome`, `:halt-reason`, `:schema-digest`,
           `:rf.epoch/sensitive?`) are preserved byte-for-byte.
         - The two functions agree (projected-history is fn-equivalent to
           `(mapv projected-record (epoch-history fid))`).
         - The functions are pure + idempotent — calling them twice over
           the same input is structurally identical, so a forwarder that
           accidentally double-projects (e.g. middleware composition)
           does not corrupt the wire shape.
         - Ordering is deterministic (oldest-first), matching the raw
           ring; an MCP `watch-epochs` initial snapshot relies on this
           to set the resume-cursor's `:after-id`.
         - No side effects: `projected-record` and `projected-history`
           never mutate the underlying ring, the schemas registry, or
           the elision registry.

  Why this file lives in implementation/epoch/test rather than under an
  MCP-server test tree: MCP-server test runners are shadow-cljs + Node
  (`npm test` -> `out/server-test.js`), and the artefacts do not
  statically depend on `re-frame.epoch` — the runtime accessors get into
  the running app via the injected-runtime path
  (`day8.re-frame2-xray.runtime`), not the MCP-server bundle. The
  framework's epoch artefact owns the projection emission site (Spec
  Security.md §Epoch privacy posture line 104); pinning conformance from
  the artefact side keeps the test on the JVM next to the contract owner.
  An MCP-side end-to-end test is the job of the SDK-driven conformance
  `test/end-to-end-*.cjs` paths if and when MCP-server epoch tools ship."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.epoch :as epoch]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; Side-effect requires (mirror epoch_test.clj fixture).
            [re-frame.machines]))

;; ---- fixtures --------------------------------------------------------------
;;
;; rf2-yw1w1u — canonical capture/restore fixture. Snapshots the
;; registrar at ns-load + restores around each test, fires the epoch
;; reset-hook table (history / listeners / config-to-default), and the
;; `:init-fn` re-applies the suite's non-default `:trace-events-keep 5`
;; (NOT the shipped 50 = :depth; Mike pair-debug 2026-05-27) through the
;; public `configure!` boundary — no test ns reaches into the private
;; `state/config` var.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- helpers ---------------------------------------------------------------

(defn- big-string [n] (apply str (repeat n "X")))

(def ^:private secret-password "topsecret-do-not-leak")
(def ^:private payload-size    25000)

(defn- install-mcp-style-schemas!
  "A realistic mixed classification set: one sensitive path
  (`[:auth :password]`) and one large path (`[:blob :payload]`) against
  `frame-id`. Matches the shape an app exercising both privacy defences
  would declare.

  EP-0025: durable app-db classification rides the commit-plane
  classification effects. Seeded through `elision/apply-classification-
  effects` (`:source :effect`) — the same registry write a `reg-event`
  returning `:sensitive` / `:large` performs. The frame container is
  reg-frame'd by each deftest before this runs. (Classification is
  value-independent: the cascade legitimately leaves each path absent in
  some steps, and a classified path is a harmless no-op over an absent
  value.)"
  [frame-id]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:auth :password]]
                :large     [[:blob :payload]]})))
  nil)

(defn- drive-mixed-ring!
  "Drive a deterministic, mixed cascade matrix against `frame-id`:
    - :seed — non-sensitive bookkeeping
    - :login — writes the sensitive path (secret value closed-over in the
              handler so this fixture exercises the APP-DB classification
              axis specifically; the frame-declared sensitive path is the
              leaf the projection's wire-elision walker matches against.
              The trigger-event event-args axis is exercised separately by
              the rf2-nm611o `forwarder-trigger-event-*` tests, which drive
              the secret IN the event vector and assert it fails closed —
              before rf2-nm611o the walker could not match arbitrary args,
              so this fixture kept them out; that constraint is now lifted)
    - :upload — writes the large path (large payload closed-over in the
                handler for the same reason)
    - :inc — non-sensitive again
  Returns the resulting `(epoch-history frame-id)` for direct comparison
  with `(projected-history frame-id)`."
  [frame-id]
  (rf/reg-event :seed   (fn [{:keys [db]} _] {:db {:n 0}}))
  (rf/reg-event :login  (fn [{:keys [db]} _] {:db (assoc-in db [:auth :password] secret-password)}))
  (rf/reg-event :upload (fn [{:keys [db]} _] {:db (assoc-in db [:blob :payload] (big-string payload-size))}))
  (rf/reg-event :inc    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf/dispatch-sync [:seed]   {:frame frame-id})
  (rf/dispatch-sync [:login]  {:frame frame-id})
  (rf/dispatch-sync [:upload] {:frame frame-id})
  (rf/dispatch-sync [:inc]    {:frame frame-id})
  (rf/epoch-history frame-id))

(defn- contains-secret?
  "Walk an arbitrary EDN value looking for the exact secret string. Used
  as the cross-cutting 'no raw sensitive bytes anywhere in the projected
  output' check — the MCP wire boundary's promise. Returns true when ANY
  leaf in the structure equals (or contains as a substring) the secret."
  [x]
  (cond
    (string? x) (.contains ^String x ^String secret-password)
    (map? x)    (or (some contains-secret? (keys x))
                    (some contains-secret? (vals x)))
    (coll? x)   (some contains-secret? x)
    :else       false))

(defn- count-leaf-strings-at-least
  "Walk `x` and count leaf strings whose length is `>= n`. Used to bound
  the 'no raw large bytes anywhere in the projected output' check — a
  projected record MUST NOT egress the full payload as a leaf."
  [n x]
  (let [counter (atom 0)
        walk (fn walk [v]
               (cond
                 (string? v) (when (>= (count v) n) (swap! counter inc))
                 (map? v)    (do (run! walk (keys v)) (run! walk (vals v)))
                 (coll? v)   (run! walk v)))]
    (walk x)
    @counter))

;; ============================================================================
;;  Forwarder-shape conformance — projected-record (per-record egress)
;; ============================================================================

(deftest forwarder-projected-record-leaks-no-raw-secret-bytes
  (testing "MCP `register-epoch-listener!` forwarder pattern: ship! body runs
            `projected-record` on each record before egress. The projected
            shape MUST NOT carry the raw secret string anywhere — the
            promise the MCP wire boundary makes to Security.md §Epoch
            privacy posture (line 104)."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (let [shipped (atom [])
          ship!   (fn [record]
                    ;; Tool-side forwarder body — project at egress.
                    (swap! shipped conj (epoch/projected-record record)))]
      (rf/register-epoch-listener! ::forwarder ship!)
      (drive-mixed-ring! :test/mcp)
      (is (pos? (count @shipped))
          "the forwarder saw at least one cascade")
      (is (not-any? contains-secret? @shipped)
          "no projected record carries the raw secret string anywhere
           in its structure — every leaf at the sensitive path is the
           :rf/redacted scalar sentinel"))))

(deftest forwarder-projected-record-bounds-large-leaf-bytes
  (testing "MCP `register-epoch-listener!` forwarder pattern: the projected
            record MUST NOT egress the full large payload as a leaf
            string — the wire-elision walker substitutes a
            :rf.size/large-elided marker (a map containing :path, :bytes,
            :digest), not the raw bytes. An MCP forwarder downstream of
            the token-cap walker depends on this."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (let [shipped (atom [])]
      (rf/register-epoch-listener! ::forwarder
                             (fn [record]
                               (swap! shipped conj (epoch/projected-record record))))
      (drive-mixed-ring! :test/mcp)
      (let [raw-leaf-count       (count-leaf-strings-at-least payload-size
                                                              (rf/epoch-history :test/mcp))
            projected-leaf-count (count-leaf-strings-at-least payload-size @shipped)]
        (is (pos? raw-leaf-count)
            "sanity: the raw ring contains at least one large leaf")
        (is (zero? projected-leaf-count)
            "the projected output contains zero leaf strings of the
             large payload's size — the large path landed as a marker,
             not as raw bytes")))))

(deftest forwarder-projected-record-preserves-bookkeeping-slots
  (testing "MCP forwarder pattern: the projected record's bookkeeping
            slots are byte-identical to the raw record's. An MCP tool
            uses :epoch-id for the resume cursor, :frame for the scoped
            tool routing, :rf.epoch/sensitive? to display the
            sensitivity badge — all MUST survive the projection
            verbatim (Spec Security.md §Epoch privacy posture line 103)."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw       (rf/epoch-history :test/mcp)
          projected (mapv epoch/projected-record raw)
          bookkeeping-keys [:epoch-id :frame :committed-at :event-id
                            :outcome :halt-reason :schema-digest
                            :rf.epoch/sensitive?]]
      (doseq [k bookkeeping-keys
              [r p] (map vector raw projected)]
        (is (= (get r k) (get p k))
            (str "bookkeeping slot " k
                 " is preserved byte-identically by projected-record"))))))

(deftest forwarder-projected-record-is-pure-no-side-effects
  (testing "MCP forwarder pattern: projected-record is a pure data
            transform — it MUST NOT mutate the underlying ring, the
            schemas registry, or the elision registry. A forwarder
            running on every cascade would compound any side effect."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [ring-before        (rf/epoch-history :test/mcp)
          schemas-before     (schemas/snapshot-schemas-by-frame)
          ;; Hit projected-record many times — a real forwarder might
          ;; project the same record multiple times (re-trigger, replay).
          _                  (dotimes [_ 25]
                               (mapv epoch/projected-record ring-before))
          ring-after         (rf/epoch-history :test/mcp)
          schemas-after      (schemas/snapshot-schemas-by-frame)]
      (is (= ring-before ring-after)
          "the epoch ring is unchanged — projected-record does not mutate")
      (is (= schemas-before schemas-after)
          "the schemas registry is unchanged"))))

(deftest forwarder-projected-record-is-sensitive-idempotent
  (testing "MCP forwarder pattern: under :sensitive? substitutions
            `projected-record` is idempotent — re-projecting an
            already-projected record returns a structurally-equal value
            at the sensitive slot. The :sensitive? sentinel
            (`:rf/redacted`) is a scalar keyword, so the walker has no
            larger structure to descend into on a re-projection pass;
            a forwarder pipeline that accidentally double-projects (e.g.
            middleware composition, tool-then-watcher fan-out) MUST NOT
            re-leak a sensitive value across passes.

            Sibling test `forwarder-projected-record-is-large-idempotent`
            pins the parallel guarantee for the :large? marker: the
            wire-elision walker is now marker-aware (per rf2-fq8ep), so
            both the sensitive and large substitutions are uniformly
            idempotent under repeated projection. The sensitive case
            holds because `:rf/redacted` is a non-matchable scalar; the
            large case holds because the walker recognises its own
            `:rf.size/large-elided` marker shape at the declared path
            and passes it through unchanged.

            What an MCP forwarder relies on: BOTH substitutions are
            irreversible across passes. Once a record has been projected,
            re-projecting it yields the same shape, byte-for-byte at
            the substitution points."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw    (rf/epoch-history :test/mcp)
          once   (mapv epoch/projected-record raw)
          twice  (mapv epoch/projected-record once)
          thrice (mapv epoch/projected-record twice)]
      ;; Sensitive substitution holds across all three passes.
      (is (every? (fn [r] (= :rf/redacted (get-in r [:db-after :auth :password])))
                  (rest once))
          "every record past :seed carries :rf/redacted at the sensitive
           leaf after one projection pass")
      (is (every? (fn [r] (= :rf/redacted (get-in r [:db-after :auth :password])))
                  (rest twice))
          ":rf/redacted survives a second projection pass — scalar
           sentinel is the walker's substitution target, not re-matchable")
      (is (every? (fn [r] (= :rf/redacted (get-in r [:db-after :auth :password])))
                  (rest thrice))
          ":rf/redacted survives a third projection pass — sensitive
           substitution is irreversible")
      (is (not-any? contains-secret? thrice)
          "the secret is still absent after three projection passes —
           the MCP forwarder's no-leak guarantee holds even under
           accidental double-projection"))))

(deftest forwarder-projected-record-is-large-idempotent
  (testing "MCP forwarder pattern: under :large? substitutions
            `projected-record` is idempotent — re-projecting a record
            whose `:large?`-declared path already carries the
            `:rf.size/large-elided` marker MUST return a structurally-
            equal value at that slot. Per rf2-fq8ep, the wire-elision
            walker is marker-aware: when it encounters a value at a
            `:large?`-declared path that already satisfies
            `elision/marker?`, it passes the value through unchanged
            rather than re-marking it.

            Why this matters: without the guard, a second projection
            pass produced a new marker map whose `:bytes` reflected the
            printed length of the previous marker (not the original
            payload), and the `:digest` rotated similarly. Forwarder
            pipelines that accidentally double-project (middleware
            composition, tool-then-watcher fan-out) would have shipped
            drifting `:bytes` / `:digest` slots across passes — a
            recordkeeping wobble, not a leak, but it broke fingerprint-
            based dedup and confused consumers.

            With the marker-aware walker, the large marker is now
            irreversible across passes: once `:rf.size/large-elided`,
            always `:rf.size/large-elided` with the SAME `:bytes` /
            `:digest` slots. Parallel to the sensitive-case guarantee."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw    (rf/epoch-history :test/mcp)
          once   (mapv epoch/projected-record raw)
          twice  (mapv epoch/projected-record once)
          thrice (mapv epoch/projected-record twice)
          ;; The :upload cascade is the third one driven (index 2):
          ;; that record is the one whose :db-after carries the large
          ;; payload at the frame-declared `[:blob :payload]` slot.
          large-slot (fn [r] (get-in r [:db-after :blob :payload]))]
      (is (elision/marker? (large-slot (nth once 2)))
          "first projection pass substitutes a marker at the large slot")
      (is (= (large-slot (nth once 2))
             (large-slot (nth twice 2)))
          "second projection pass returns the SAME marker (byte-identical
           :bytes / :digest / :path) — the walker passed it through
           unchanged rather than re-marking the marker map")
      (is (= (large-slot (nth once 2))
             (large-slot (nth thrice 2)))
          "third projection pass remains byte-identical — the large
           marker is irreversible across passes, matching the
           sensitive-case guarantee")
      (is (= once twice thrice)
          "across the full record vector, every slot is byte-identical
           across N>=2 projection passes — projected-record is now
           uniformly idempotent under both :sensitive? and :large?
           substitutions"))))

(deftest forwarder-projected-record-handles-mixed-nil-and-real-records
  (testing "MCP forwarder pattern: projected-record returns nil for nil
            input (a missed-epoch lookup MUST NOT throw); it returns a
            projected map for a real record. A forwarder that mixes
            optional / present records (cursor mid-stream, an epoch-id
            lookup that lost the race) MUST be able to call uniformly."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw    (rf/epoch-history :test/mcp)
          mixed  (concat [nil] raw [nil] raw [nil])
          shaped (mapv epoch/projected-record mixed)]
      (is (= (count mixed) (count shaped))
          "every input slot produced an output slot")
      (is (= 3 (count (filter nil? shaped)))
          "the three nil slots project to nil (no throw, no fabrication)")
      (is (= (* 2 (count raw))
             (count (filter some? shaped)))
          "every real record projected to a real (non-nil) record")
      (is (not-any? contains-secret? (filter some? shaped))
          "no projected slot leaks the secret"))))

;; ============================================================================
;;  Bulk-egress conformance — projected-history (full ring snapshot)
;; ============================================================================

(deftest watch-epochs-projected-history-leaks-no-raw-bytes
  (testing "MCP `watch-epochs` initial snapshot pattern: the server
            calls `projected-history` once to emit the full ring. The
            bulk output MUST NOT leak the raw secret OR the raw large
            payload anywhere in its structure — the same per-record
            guarantee, lifted to the bulk surface."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [snapshot (epoch/projected-history :test/mcp)]
      (is (pos? (count snapshot))
          "sanity: the snapshot is non-empty")
      (is (not-any? contains-secret? snapshot)
          "the bulk snapshot does not leak the raw secret")
      (is (zero? (count-leaf-strings-at-least payload-size snapshot))
          "the bulk snapshot does not leak any raw large-payload bytes"))))

(deftest watch-epochs-projected-history-equals-mapv-projected-record
  (testing "MCP `watch-epochs` initial snapshot pattern: the docstring
            promises `projected-history` is equivalent to
            `(mapv projected-record (epoch-history fid))`. Pin that
            equivalence so the MCP server can use either entry without
            shape drift; the bulk-egress path MUST NOT diverge from
            the per-record path."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw            (rf/epoch-history :test/mcp)
          bulk-projection (epoch/projected-history :test/mcp)
          per-record      (mapv epoch/projected-record raw)]
      (is (= per-record bulk-projection)
          "projected-history is the bulk-shape equivalent of
           (mapv projected-record (epoch-history fid))"))))

(deftest watch-epochs-projected-history-preserves-oldest-first-order
  (testing "MCP `watch-epochs` initial snapshot pattern: the snapshot
            MUST preserve the raw ring's oldest-first ordering so the
            server's resume-cursor (`:after-id` keyed off the last
            epoch-id) addresses a stable point in the projected stream.
            A reordering would break cursor pagination."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw      (rf/epoch-history :test/mcp)
          snapshot (epoch/projected-history :test/mcp)]
      (is (= (mapv :epoch-id raw)
             (mapv :epoch-id snapshot))
          "ordering matches the raw ring epoch-id-by-epoch-id"))))

(deftest watch-epochs-projected-history-empty-on-fresh-frame
  (testing "MCP `watch-epochs` initial snapshot pattern: an MCP server
            attached to a frame with no recorded epochs (a freshly-
            booted app, a just-cleared session) MUST receive the empty
            vector — not a missing-frame error. The snapshot path is
            shape-stable across the empty case."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (is (= [] (epoch/projected-history :test/mcp))
        "empty-ring snapshot is the empty vector")
    (is (= [] (epoch/projected-history :rf/no-such-frame))
        "missing-frame snapshot is also the empty vector — uniform shape")))

(deftest watch-epochs-projected-history-is-pure-no-side-effects
  (testing "MCP `watch-epochs` initial snapshot pattern: projected-history
            MUST be pure — repeat calls (the initial snapshot, a
            resync-after-reconnect, a debug print) MUST NOT mutate the
            ring or any registry."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [ring-before    (rf/epoch-history :test/mcp)
          schemas-before (schemas/snapshot-schemas-by-frame)
          _              (dotimes [_ 25] (epoch/projected-history :test/mcp))
          ring-after     (rf/epoch-history :test/mcp)
          schemas-after  (schemas/snapshot-schemas-by-frame)]
      (is (= ring-before ring-after)
          "the ring is unchanged after 25 bulk-projection calls")
      (is (= schemas-before schemas-after)
          "the schemas registry is unchanged"))))

;; ============================================================================
;;  rf2-m9duxl — `:include-sensitive?` routes THROUGH projection, per-axis.
;;
;; The Pair-MCP epoch-egress tools used to treat the operator's
;; `:include-sensitive true` opt-in as a FULL raw epoch bypass — they
;; disabled `projected-record` wholesale. That conflated the app-db
;; sensitive axis with EVERY other independent projection axis and shipped
;; the raw fx-args payload, the raw runtime-db partition, and an
;; un-`:redact-fn`'d record off-box. The fix routes `:include-sensitive`
;; THROUGH the projection as `{:include-sensitive? true}`, lifting ONLY the
;; app-db sensitive axis. These framework-side tests pin the per-axis
;; contract the tool-side form-shape tests depend on: with
;; `{:include-sensitive? true}` the app-db sensitive leaf is REVEALED while
;; `:effects[*].args` / the `:rf.db/runtime` partition / large slots / the
;; `:redact-fn` override all stay at their fail-closed defaults.
;; ============================================================================

(defn- install-fx-and-runtime-schemas!
  "Like `install-mcp-style-schemas!` but ALSO arranges for a record that
  carries (a) a payload-bearing `:effects[*].args` row and (b) a populated
  `:rf.db/runtime` frame-state partition — the two axes orthogonal to the
  app-db sensitive axis. Declares the same `[:auth :password]` sensitive +
  `[:blob :payload]` large app-db paths."
  [frame-id]
  (install-mcp-style-schemas! frame-id))

(deftest include-sensitive-reveals-app-db-but-keeps-fx-args-redacted
  (testing "rf2-m9duxl — `{:include-sensitive? true}` reveals the app-db
            sensitive leaf YET keeps the orthogonal `:effects[*].args`
            redacted (a different keyspace, governed by `:include-fx-args?`).
            This is the exact conflation the include-sensitive bypass
            introduced: asking for sensitive APP-DB values must NOT lift the
            fx-arg payload."
    (rf/reg-frame :test/mcp {})
    (install-fx-and-runtime-schemas! :test/mcp)
    (let [creds {:password secret-password :token "tok-abc"}]
      (rf/reg-fx :fxp/login (fn [_ _] nil))
      ;; Write the app-db sensitive path AND fire a payload-bearing fx whose
      ;; :args carry the same secret bytes.
      (rf/reg-event :do-login
                       (fn [_ [_ c]]
                         {:db {:auth {:password (:password c)}}
                          :fx [[:fxp/login c]]}))
      (rf/dispatch-sync [:do-login creds] {:frame :test/mcp})
      (let [raw      (last (rf/epoch-history :test/mcp))
            proj     (epoch/projected-record raw {:include-sensitive? true})
            fx-row   (some #(when (= :fxp/login (:fx-id %)) %) (:effects proj))]
        ;; App-db sensitive axis: REVEALED by include-sensitive.
        (is (= secret-password (get-in proj [:db-after :auth :password]))
            "`:include-sensitive? true` reveals the app-db sensitive leaf")
        ;; fx-args axis: STILL redacted (orthogonal — needs :include-fx-args?).
        (is (some? fx-row) "the fixture produced a payload-bearing fx row")
        (is (= :rf/redacted (:args fx-row))
            "`:effects[*].args` STAY redacted under include-sensitive alone —
             the fx-arg keyspace is orthogonal to the app-db sensitive axis")
        (is (= :fxp/login (:fx-id fx-row)) "value-free :fx-id preserved")))))

(deftest include-sensitive-keeps-runtime-db-partition-redacted
  (testing "rf2-m9duxl — `{:include-sensitive? true}` keeps the
            `:rf.db/runtime` frame-state partition REDACTED. The runtime-db
            boundary is governed by the orthogonal `:include-runtime-db?`
            opt; asking for sensitive APP-DB values must not lift the
            machine snapshots / route slice / SSR metadata."
    (rf/reg-frame :test/mcp {})
    (install-fx-and-runtime-schemas! :test/mcp)
    ;; Write BOTH the app-db sensitive leaf and a runtime-db partition value
    ;; (via the reserved :rf.db/runtime effect) in one cascade.
    (rf/reg-event :seed-both
                     (fn [{rt :rf.db/runtime} _]
                       {:db            {:auth {:password secret-password}}
                        :rf.db/runtime (assoc-in (or rt {})
                                                 [:rf.runtime/machines :snapshots :m/x]
                                                 {:state :live})}))
    (rf/dispatch-sync [:seed-both] {:frame :test/mcp})
    (let [raw  (last (rf/epoch-history :test/mcp))
          proj (epoch/projected-record raw {:include-sensitive? true})]
      ;; Sanity: the raw record DOES carry a populated runtime-db partition
      ;; (the machine snapshot we wrote, alongside the frame's elision
      ;; registry which also lives in the runtime-db partition).
      (is (= {:state :live}
             (get-in raw [:frame-state-after :rf.db/runtime
                          :rf.runtime/machines :snapshots :m/x]))
          "fixture: raw record carries a populated runtime-db partition")
      ;; App-db sensitive axis: REVEALED.
      (is (= secret-password
             (get-in proj [:frame-state-after :rf.db/app :auth :password]))
          "`:include-sensitive? true` reveals the app-db partition's sensitive leaf")
      ;; runtime-db axis: STILL redacted (orthogonal — needs :include-runtime-db?).
      (is (= :rf/redacted (get-in proj [:frame-state-after :rf.db/runtime]))
          "the `:rf.db/runtime` partition STAYS :rf/redacted under
           include-sensitive alone — runtime-db is orthogonal to the app-db
           sensitive axis")
      ;; And the explicit runtime-db opt DOES lift it (negative control).
      (let [proj+rt (epoch/projected-record raw {:include-sensitive?  true
                                                 :include-runtime-db? true})]
        (is (not= :rf/redacted (get-in proj+rt [:frame-state-after :rf.db/runtime]))
            "the explicit `:include-runtime-db? true` opt lifts the partition —
             proving the axis is independently governed")))))

(deftest include-sensitive-keeps-large-elision-independent
  (testing "rf2-m9duxl — `{:include-sensitive? true}` keeps the app-db
            `:large?` slot elided to the `:rf.size/large-elided` marker.
            Large is governed by the independent `:include-large?` opt;
            the sensitive opt-in must not pull the full payload off-box."
    (rf/reg-frame :test/mcp {})
    (install-fx-and-runtime-schemas! :test/mcp)
    (rf/reg-event :seed-large
                     (fn [{:keys [db]} _] {:db {:auth {:password secret-password}
                                :blob {:payload (big-string payload-size)}}}))
    (rf/dispatch-sync [:seed-large] {:frame :test/mcp})
    (let [raw  (last (rf/epoch-history :test/mcp))
          proj (epoch/projected-record raw {:include-sensitive? true})]
      ;; Sensitive REVEALED; large STILL elided.
      (is (= secret-password (get-in proj [:db-after :auth :password]))
          "`:include-sensitive? true` reveals the app-db sensitive leaf")
      (is (elision/marker? (get-in proj [:db-after :blob :payload]))
          "the app-db large slot STAYS a `:rf.size/large-elided` marker —
           large elision is orthogonal to the sensitive axis")
      (is (zero? (count-leaf-strings-at-least payload-size proj))
          "no raw large-payload bytes egress under include-sensitive alone")
      ;; Negative control: :include-large? true lifts it.
      (let [proj+lg (epoch/projected-record raw {:include-sensitive? true
                                                 :include-large?     true})]
        (is (not (elision/marker? (get-in proj+lg [:db-after :blob :payload])))
            "the explicit `:include-large? true` opt lifts the large slot —
             proving the axis is independently governed")))))

(deftest include-sensitive-still-applies-redact-fn-override
  (testing "rf2-m9duxl — the app-installed `:redact-fn` advanced override
            STILL runs over the projected record under
            `{:include-sensitive? true}`. The override is the post-projection
            stage of the two-stage projection; a raw bypass would skip it
            entirely. We install a `:redact-fn` that stamps a sentinel slot
            and assert it lands even with the sensitive opt-in on."
    (rf/reg-frame :test/mcp {})
    (install-fx-and-runtime-schemas! :test/mcp)
    (rf/configure! {:epoch-history {:redact-fn (fn [record]
                                 (assoc record :rf.test/redact-fn-ran true))}})
    (rf/reg-event :seed-sensitive
                     (fn [{:keys [db]} _] {:db {:auth {:password secret-password}}}))
    (rf/dispatch-sync [:seed-sensitive] {:frame :test/mcp})
    (let [raw  (last (rf/epoch-history :test/mcp))
          proj (epoch/projected-record raw {:include-sensitive? true})]
      (is (= secret-password (get-in proj [:db-after :auth :password]))
          "`:include-sensitive? true` reveals the app-db sensitive leaf")
      (is (true? (:rf.test/redact-fn-ran proj))
          "the app `:redact-fn` override STILL runs under include-sensitive —
           the projection's post-stage is never skipped (no raw bypass)")
      ;; Negative control: the RAW ring record is untouched by the redact-fn
      ;; (projection-side only) — restore fidelity preserved.
      (is (not (contains? raw :rf.test/redact-fn-ran))
          "the raw ring record is untouched — redact-fn is projection-side"))))

;; ============================================================================
;;  Cross-function sentinel uniformity
;; ============================================================================

(deftest projected-record-and-history-share-redaction-vocabulary
  (testing "Both functions substitute the SAME sentinel vocabulary
            (`:rf/redacted` for sensitive, `:rf.size/large-elided` marker
            map for large). An MCP client that branches on the marker
            vocabulary MUST see uniform shapes across the per-record and
            bulk-egress paths — divergence would force per-path branching
            client-side. Pinned per-record-AND-bulk against the same ring."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw        (rf/epoch-history :test/mcp)
          bulk       (epoch/projected-history :test/mcp)
          per-record (mapv epoch/projected-record raw)]
      ;; The :login cascade is the second one driven; pull both shapes'
      ;; corresponding record and compare leaf-by-leaf.
      (let [login-bulk (nth bulk       1)
            login-per  (nth per-record 1)]
        (is (= (get-in login-bulk [:db-after :auth :password])
               (get-in login-per  [:db-after :auth :password]))
            "the sensitive leaf substitution matches between bulk and per-record")
        (is (= :rf/redacted
               (get-in login-bulk [:db-after :auth :password]))
            "the bulk-shape sensitive leaf is the :rf/redacted scalar sentinel")
        (is (= :rf/redacted
               (get-in login-per  [:db-after :auth :password]))
            "the per-record-shape sensitive leaf is the :rf/redacted scalar sentinel"))
      ;; The :upload cascade is the third one driven.
      (let [upload-bulk (nth bulk       2)
            upload-per  (nth per-record 2)
            bulk-slot   (get-in upload-bulk [:db-after :blob :payload])
            per-slot    (get-in upload-per  [:db-after :blob :payload])]
        (is (= bulk-slot per-slot)
            "the large-payload slot substitution matches between bulk and per-record")
        (is (elision/marker? bulk-slot)
            "the bulk-shape large slot is an elision marker (`:rf.size/large-elided`)")
        (is (elision/marker? per-slot)
            "the per-record-shape large slot is an elision marker")))))

;; ============================================================================
;;  rf2-nm611o — :trigger-event event-args fail-closed off-box egress.
;;
;;  The dispatched event vector's args are registration-owned transient
;;  payloads (Spec 015 §151), not app-db-rooted, so the app-db classification
;;  walker cannot prove them safe. A secret carried IN the event vector (e.g.
;;  [:login "topsecret"]) previously egressed RAW through the generic
;;  app-db-rooted payload-slot projection. The fix fails closed: args
;;  redacted, head event-id retained; trusted-local :include-event-args?
;;  opts back in. (drive-mixed-ring! keeps secrets OUT of the trigger-event
;;  on purpose — see its :login comment — so these tests drive the secret IN.)
;; ============================================================================

(deftest forwarder-trigger-event-positional-secret-fails-closed
  (testing "rf2-nm611o — an MCP forwarder shipping a record whose dispatched
            event vector carried a secret POSITIONALLY ([:login secret])
            MUST NOT egress the secret. projected-record fails closed: the
            head event-id is retained, the positional arg is :rf/redacted."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (rf/reg-event :login (fn [{:keys [db]} [_ pw]]
                           {:db (assoc-in db [:auth :password] pw)}))
    (let [shipped (atom [])]
      (rf/register-epoch-listener! ::forwarder
                                   (fn [r] (swap! shipped conj (epoch/projected-record r))))
      (rf/dispatch-sync [:login secret-password] {:frame :test/mcp})
      (is (pos? (count @shipped)) "the forwarder saw the cascade")
      (is (not-any? contains-secret? @shipped)
          "no projected record leaks the positional secret anywhere")
      (let [proj (last @shipped)]
        (is (= [:login :rf/redacted] (:trigger-event proj))
            "trigger-event egresses with the head id retained, arg redacted")
        (is (= :login (:event-id proj))
            "the event-id summary slot is intact")))))

(deftest forwarder-trigger-event-map-secret-fails-closed
  (testing "rf2-nm611o — a secret nested in a MAP arg of the dispatched
            event vector ([:auth/login {:password secret}]) also fails
            closed off-box: the whole arg redacts to :rf/redacted."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (rf/reg-event :auth/login (fn [{:keys [db]} [_ {:keys [password]}]]
                                {:db (assoc-in db [:auth :password] password)}))
    (rf/dispatch-sync [:auth/login {:password secret-password}] {:frame :test/mcp})
    (let [proj (epoch/projected-record (last (rf/epoch-history :test/mcp)))]
      (is (= [:auth/login :rf/redacted] (:trigger-event proj)))
      (is (not (contains-secret? (:trigger-event proj)))
          "the map-arg secret is absent from the projected trigger-event"))))

(deftest include-event-args-reveals-trigger-event-but-keeps-app-db-axes
  (testing "rf2-nm611o — `{:include-event-args? true}` reveals the raw
            trigger-event args YET is ORTHOGONAL to the app-db
            sensitive/large axes (and vice-versa). Asking for event args
            must not lift the app-db sensitive leaf, and asking for app-db
            sensitive values must not lift the event args."
    (rf/reg-frame :test/mcp {})
    (install-mcp-style-schemas! :test/mcp)
    (rf/reg-event :login (fn [{:keys [db]} [_ pw]]
                           {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login secret-password] {:frame :test/mcp})
    (let [raw (last (rf/epoch-history :test/mcp))]
      ;; include-event-args reveals the args but keeps app-db sensitive redacted.
      (let [proj (epoch/projected-record raw {:include-event-args? true})]
        (is (= [:login secret-password] (:trigger-event proj))
            "`:include-event-args? true` reveals the raw trigger-event args")
        (is (= :rf/redacted (get-in proj [:db-after :auth :password]))
            "the app-db sensitive leaf STAYS redacted — orthogonal axis"))
      ;; include-sensitive reveals the app-db leaf but keeps event args redacted.
      (let [proj (epoch/projected-record raw {:include-sensitive? true})]
        (is (= secret-password (get-in proj [:db-after :auth :password]))
            "`:include-sensitive? true` reveals the app-db sensitive leaf")
        (is (= [:login :rf/redacted] (:trigger-event proj))
            "the trigger-event args STAY redacted — event-args axis is orthogonal")))))
