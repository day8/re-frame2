(ns re-frame.epoch-mcp-egress-conformance-test
  "rf2-xrlyi — MCP-style egress conformance for `projected-record` +
  `projected-history`. Per Spec Security.md §Epoch privacy posture (line
  104): 'Any tool that egresses an epoch record over an MCP wire, an HTTP
  forwarder, a log shipper, or any process boundary MUST route the record
  through the projected-record helper before egress.'

  Coverage gap closed (rf2-kp835 Phase-1 audit): the `projected-record` and
  `projected-history` public-surface symbols had 0 callers in `tools/`. The
  MCP-side accessors (Causa-MCP `watch-epochs`, story / pair recorders)
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
  (`day8.re-frame2-causa.runtime`), not the MCP-server bundle. The
  framework's epoch artefact owns the projection emission site (Spec
  Security.md §Epoch privacy posture line 104); pinning conformance from
  the artefact side keeps the test on the JVM next to the contract owner.
  An MCP-side end-to-end test is the job of the SDK-driven conformance
  `test/end-to-end-*.cjs` paths if and when MCP-server epoch tools ship."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.state :as state]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; Side-effect requires (mirror epoch_test.clj fixture).
            [re-frame.machines]))

;; ---- fixtures --------------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (trace/clear-listeners!)
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (reset! @#'state/config {:depth 50 :trace-events-keep 5 :redact-fn nil})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(defn- big-string [n] (apply str (repeat n "X")))

(def ^:private secret-password "topsecret-do-not-leak")
(def ^:private payload-size    25000)

(defn- install-mcp-style-schemas!
  "A realistic mixed schema set: one `:sensitive?` path
  (`[:auth :password]`) and one `:large?` path (`[:blob :payload]`)
  against `frame-id`. Matches the shape an app exercising both privacy
  defences would register."
  [frame-id]
  (rf/reg-app-schema [:auth]
                     [:map [:password {:sensitive? true} :string]]
                     {:frame frame-id})
  (rf/reg-app-schema [:blob]
                     [:map [:payload {:large? true :hint "image"} :string]]
                     {:frame frame-id})
  (rf/populate-sensitive-from-schemas! frame-id)
  (rf/populate-elision-from-schemas!   frame-id)
  nil)

(defn- drive-mixed-ring!
  "Drive a deterministic, mixed cascade matrix against `frame-id`:
    - :seed — non-sensitive bookkeeping
    - :login — writes the sensitive path (secret value closed-over in the
              handler so it never appears in the trigger-event vector;
              the schema-declared sensitive path is the only sensitive
              leaf the projection's wire-elision walker can match
              against, not arbitrary positional event args)
    - :upload — writes the large path (large payload closed-over in the
                handler for the same reason)
    - :inc — non-sensitive again
  Returns the resulting `(epoch-history frame-id)` for direct comparison
  with `(projected-history frame-id)`."
  [frame-id]
  (rf/reg-event-db :seed   (fn [_ _] {:n 0}))
  (rf/reg-event-db :login  (fn [db _] (assoc-in db [:auth :password] secret-password)))
  (rf/reg-event-db :upload (fn [db _] (assoc-in db [:blob :payload] (big-string payload-size))))
  (rf/reg-event-db :inc    (fn [db _] (update db :n (fnil inc 0))))
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
          schemas-before     @schemas/schemas-by-frame
          ;; Hit projected-record many times — a real forwarder might
          ;; project the same record multiple times (re-trigger, replay).
          _                  (dotimes [_ 25]
                               (mapv epoch/projected-record ring-before))
          ring-after         (rf/epoch-history :test/mcp)
          schemas-after      @schemas/schemas-by-frame]
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
          ;; payload at the schema-declared `[:blob :payload]` slot.
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
          schemas-before @schemas/schemas-by-frame
          _              (dotimes [_ 25] (epoch/projected-history :test/mcp))
          ring-after     (rf/epoch-history :test/mcp)
          schemas-after  @schemas/schemas-by-frame]
      (is (= ring-before ring-after)
          "the ring is unchanged after 25 bulk-projection calls")
      (is (= schemas-before schemas-after)
          "the schemas registry is unchanged"))))

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
