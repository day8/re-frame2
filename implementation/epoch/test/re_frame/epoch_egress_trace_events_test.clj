(ns re-frame.epoch-egress-trace-events-test
  "Coverage for the `:trace-events` re-root in off-box egress projection
  (rf2-ynjts.7 testing-review gap-fill).

  Per rf2-ta0y7 (`re-frame.epoch.write/reroot-trace-event-db-slots` +
  `elide-trace-events-slot`): the `:rf.event/db-pending` (t1) and
  `:rf.event/db-pending-post-flow` (t2) trace events each carry the FULL
  pending app-db value under `:tags :rf.event/db`. The bulk
  `elide-wire-value` walk over `:trace-events` treats that nested db as
  rooted at `[<i> :tags :rf.event/db ...]`, so a schema-declared sensitive
  path like `[:auth :password]` does NOT match (the walker expects it
  rooted at the frame's app-db). `reroot-trace-event-db-slots` re-roots the
  walk at the frame's app-db (`{:path []}`) so the sensitive / large
  declarations match natively.

  THE GAP this file closes: `epoch_privacy_test.clj` and
  `epoch_mcp_egress_conformance_test.clj` cover redaction of `:db-before`,
  `:db-after`, and `:trigger-event`, but NEVER exercise the `:trace-events`
  re-root for the t1/t2 trace events' nested `:rf.event/db` tag. Deleting
  `reroot-trace-event-db-slots` (collapsing `elide-trace-events-slot` to
  the bare bulk walk) would pass the entire prior suite green while failing
  to redact a sensitive leaf nested inside a t1/t2 trace's `:rf.event/db`
  tag — the bulk walk roots that nested db at
  `[<i> :tags :rf.event/db ...]`, so `[:auth :password]` never matches.

  DEFENCE-IN-DEPTH note (verified against `re-frame.marks/project-db-tags`,
  rf2-6773q): when the frame HAS elision declarations, the t1/t2
  `:rf.event/db` tag is ALSO redacted at EMIT time, so the on-ring trace
  already carries `:rf/redacted` for schema-declared paths. The egress
  re-root is therefore the redaction site for records whose tag was NOT
  emit-redacted — a raw record fed to `projected-record` directly, or a
  frame whose declarations were registered after the record was captured —
  plus the idempotency guarantee for already-redacted records. The unit
  tests below pin the source behaviour by feeding `projected-record` a
  hand-built record carrying the RAW secret nested in a t1 tag (the
  not-emit-redacted shape); the live tests pin the emit-time redaction +
  end-to-end no-leak + idempotency.

  Two angles:
    1. End-to-end via the live router (the t1/t2 traces are real
       `:rf.event/db-pending` emits captured into the epoch record's
       `:trace-events`); the on-ring tag is emit-redacted, and the
       projection keeps it redacted (no leak, idempotent).
    2. Direct unit test of `projected-record` against a hand-built record
       whose `:trace-events` carries a t1/t2 event with the RAW sensitive
       leaf — isolates the egress re-root as the redaction site."
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
            ;; Side-effect require (mirror epoch_test.clj fixture).
            [re-frame.machines]))

;; ---- fixture (mirrors epoch_test.clj's reset-runtime) ---------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (reset! schemas/schemas-by-frame {})
  (flows/reset-last-inputs!)
  (trace/clear-listeners!)
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (reset! @#'state/config {:depth 50 :trace-events-keep 5 :redact-fn nil})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(def ^:private secret "topsecret-do-not-leak")

(defn- install-sensitive-schema! [frame-id]
  (rf/reg-app-schema [:auth]
                     [:map [:password {:sensitive? true} :string]]
                     {:frame frame-id})
  (rf/populate-sensitive-from-schemas! frame-id)
  nil)

(defn- install-large-schema! [frame-id]
  (rf/reg-app-schema [:blob]
                     [:map [:payload {:large? true :hint "big"} :string]]
                     {:frame frame-id})
  (rf/populate-elision-from-schemas! frame-id)
  nil)

(defn- big-string [n] (apply str (repeat n "X")))

(defn- contains-secret?
  "Walk an arbitrary EDN value looking for the exact secret string."
  [x]
  (cond
    (string? x) (.contains ^String x ^String secret)
    (map? x)    (or (some contains-secret? (keys x))
                    (some contains-secret? (vals x)))
    (coll? x)   (some contains-secret? x)
    :else       false))

(defn- db-pending-events
  "The t1 / t2 trace events (`:rf.event/db-pending` /
  `:rf.event/db-pending-post-flow`) in a record's :trace-events."
  [record]
  (filter (fn [ev]
            (contains? #{:rf.event/db-pending :rf.event/db-pending-post-flow}
                       (:operation ev)))
          (:trace-events record)))

;; ===========================================================================
;; 1. End-to-end: the live router's t1 :rf.event/db-pending trace carries
;;    the sensitive leaf; the projection must redact it.
;; ===========================================================================

(deftest live-db-pending-trace-tag-emit-redacted-on-ring
  (testing "the live router's t1/t2 :rf.event/db-pending trace carries the
            FULL pending db under :rf.event/db — and for a frame WITH
            elision declarations, that nested tag is redacted at EMIT time
            (re-frame.marks/project-db-tags, rf2-6773q), so the on-ring
            trace already shows :rf/redacted at [:auth :password]. The
            egress re-root then keeps it redacted (idempotent) and covers
            the not-emit-redacted shapes (pinned by the unit tests)"
    (rf/reg-frame :test/eg {})
    (install-sensitive-schema! :test/eg)
    (rf/reg-event-db :login (fn [db _] (assoc-in db [:auth :password] secret)))
    (rf/dispatch-sync [:login] {:frame :test/eg})

    (let [raw    (last (rf/epoch-history :test/eg))
          t-evts (db-pending-events raw)]
      (is (seq t-evts)
          "the cascade emitted at least one t1/t2 :rf.event/db-pending trace")
      (is (every? (fn [ev]
                    (let [leaf (get-in ev [:tags :rf.event/db :auth :password])]
                      (or (nil? leaf) (= :rf/redacted leaf))))
                  t-evts)
          "the nested :rf.event/db sensitive leaf is :rf/redacted on the
           ring — emit-time redaction (marks/project-db-tags) fired for the
           declared path before the trace reached the epoch-capture sink")
      ;; NOTE: the ring record's :db-before / :db-after ARE raw on-box (the
      ;; privacy posture: ring is raw; off-box egress is the redaction
      ;; boundary). Only the TRACE TAG is emit-redacted, because the trace
      ;; stream fans out to listeners directly, bypassing projected-record.
      (is (= secret (get-in raw [:db-after :auth :password]))
          ":db-after carries the RAW secret on the ring — on-box records are
           unredacted by design; the trace tag is the lone emit-redacted
           slot (it has a separate, listener-facing fan-out path)"))))

(deftest projection-keeps-db-pending-trace-leaf-redacted-end-to-end
  (testing "rf2-ta0y7 — projected-record over a live record (whose t1/t2
            :rf.event/db tag was emit-redacted) keeps the nested sensitive
            leaf :rf/redacted and leaks nothing. The re-root re-walks the
            tag at the app-db root; an already-:rf/redacted scalar passes
            through unchanged (idempotent), and a raw leaf would be
            redacted (pinned directly by the unit tests below)"
    (rf/reg-frame :test/eg {})
    (install-sensitive-schema! :test/eg)
    (rf/reg-event-db :login (fn [db _] (assoc-in db [:auth :password] secret)))
    (rf/dispatch-sync [:login] {:frame :test/eg})

    (let [raw       (last (rf/epoch-history :test/eg))
          projected (epoch/projected-record raw)
          t-evts    (db-pending-events projected)]
      (is (seq t-evts)
          "the projected record still carries the t1/t2 trace events")
      (is (every? (fn [ev]
                    (let [leaf (get-in ev [:tags :rf.event/db :auth :password])]
                      (or (nil? leaf) (= :rf/redacted leaf))))
                  t-evts)
          "every projected t1/t2 trace's nested :rf.event/db sensitive leaf
           is :rf/redacted (or absent) — the re-root matched the
           schema-declared path")
      (is (not (contains-secret? projected))
          "the raw secret appears NOWHERE in the projected record — not in
           :db-after, not nested inside any :trace-events :rf.event/db tag"))))

(deftest projected-history-redacts-sensitive-leaf-inside-db-pending-trace
  (testing "rf2-ta0y7 — the bulk-egress path (projected-history) applies
            the same per-event re-root: no projected record in the ring
            leaks the sensitive leaf nested inside a t1/t2 trace's
            :rf.event/db tag"
    (rf/reg-frame :test/eg {})
    (install-sensitive-schema! :test/eg)
    (rf/reg-event-db :seed  (fn [_ _] {}))
    (rf/reg-event-db :login (fn [db _] (assoc-in db [:auth :password] secret)))
    (rf/dispatch-sync [:seed]  {:frame :test/eg})
    (rf/dispatch-sync [:login] {:frame :test/eg})

    (let [snapshot (epoch/projected-history :test/eg)]
      (is (pos? (count snapshot)))
      (is (not-any? contains-secret? snapshot)
          "no record in the bulk snapshot leaks the secret anywhere —
           including nested inside any :rf.event/db trace tag"))))

;; ===========================================================================
;; 2. Direct unit: projected-record over a hand-built record whose
;;    :trace-events carries a t1/t2 event with a sensitive leaf. Isolates
;;    the re-root from whichever traces the live router happens to emit.
;; ===========================================================================

(deftest unit-projection-reroots-db-pending-tag-and-redacts
  (testing "rf2-ta0y7 — direct unit: a synthetic record whose :trace-events
            holds a t1/t2 event with the RAW sensitive leaf nested at
            [:tags :rf.event/db :auth :password] (the not-emit-redacted
            shape) is redacted by projected-record's re-root. This is the
            source-behaviour pin: deleting reroot-trace-event-db-slots
            leaves the raw secret nested in the projected trace"
    (rf/reg-frame :test/eg {})
    (install-sensitive-schema! :test/eg)
    (let [t1-event    {:op-type   :rf.event
                       :operation :rf.event/db-pending
                       :tags      {:rf.event/db {:auth {:password secret}}}}
          t2-event    {:op-type   :rf.event
                       :operation :rf.event/db-pending-post-flow
                       :tags      {:rf.event/db {:auth {:password secret}}}}
          record      {:epoch-id      1
                       :frame         :test/eg
                       :committed-at  0
                       :event-id      :login
                       :trigger-event [:login]
                       :db-before     {}
                       :db-after      {:auth {:password secret}}
                       :outcome       :ok
                       :rf.epoch/sensitive? true
                       :trace-events  [t1-event t2-event]
                       :sub-runs      []
                       :renders       []
                       :effects       []}
          projected   (epoch/projected-record record)
          [p-t1 p-t2] (:trace-events projected)]
      (is (= :rf/redacted (get-in p-t1 [:tags :rf.event/db :auth :password]))
          "t1 :rf.event/db-pending: nested sensitive leaf re-rooted + redacted")
      (is (= :rf/redacted (get-in p-t2 [:tags :rf.event/db :auth :password]))
          "t2 :rf.event/db-pending-post-flow: nested sensitive leaf re-rooted + redacted")
      (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
          ":db-after sensitive leaf redacted by the regular slot walk")
      (is (not (contains-secret? projected))
          "no raw secret survives anywhere in the projected record")))

  (testing "the re-root is SCOPED to the t1/t2 ops: a non-t1/t2 trace event
            whose tags carry a value at a NON-app-db-rooted path is NOT
            re-rooted (the bulk walk handles it at its real root, where the
            schema-declared [:auth :password] does not match)"
    (rf/reg-frame :test/eg2 {})
    (install-sensitive-schema! :test/eg2)
    (let [;; A non-t1/t2 op carrying a nested map at a slot that is NOT the
          ;; frame's app-db root — the re-root must skip it (op not in the
          ;; t1/t2 set), and the bulk walk does not match it at this root.
          other-event {:op-type   :rf.event
                       :operation :rf.event/db-changed
                       :tags      {:some-other-slot {:auth {:password "scoped-marker"}}}}
          record      {:epoch-id      2
                       :frame         :test/eg2
                       :committed-at  0
                       :event-id      :ev
                       :trigger-event [:ev]
                       :db-before     {}
                       :db-after      {}
                       :outcome       :ok
                       :rf.epoch/sensitive? false
                       :trace-events  [other-event]
                       :sub-runs      []
                       :renders       []
                       :effects       []}
          projected   (epoch/projected-record record)
          p-other     (first (:trace-events projected))]
      (is (= "scoped-marker" (get-in p-other [:tags :some-other-slot :auth :password]))
          "the non-t1/t2 event's nested value is untouched — the re-root is
           scoped to the t1/t2 ops and the bulk walk does not match it at
           this non-app-db-rooted path"))))

(deftest unit-projection-reroots-large-leaf-inside-db-pending-trace
  (testing "rf2-ta0y7 — the re-root also surfaces a :large?-declared leaf
            nested inside a t1 trace's :rf.event/db tag: the projection
            substitutes an elision marker, not the raw bytes"
    (rf/reg-frame :test/eg {})
    (install-large-schema! :test/eg)
    (let [payload   (big-string 50000)
          t1-event  {:op-type   :rf.event
                     :operation :rf.event/db-pending
                     :tags      {:rf.event/db {:blob {:payload payload}}}}
          record    {:epoch-id      1
                     :frame         :test/eg
                     :committed-at  0
                     :event-id      :upload
                     :trigger-event [:upload]
                     :db-before     {}
                     :db-after      {:blob {:payload payload}}
                     :outcome       :ok
                     :rf.epoch/sensitive? false
                     :trace-events  [t1-event]
                     :sub-runs      []
                     :renders       []
                     :effects       []}
          projected (epoch/projected-record record)
          p-t1      (first (:trace-events projected))
          leaf      (get-in p-t1 [:tags :rf.event/db :blob :payload])]
      (is (elision/marker? leaf)
          "the large leaf nested in the t1 :rf.event/db tag is substituted
           with a :rf.size/large-elided marker — re-root reached it")
      (is (not= payload leaf)
          "the raw 50K payload does not survive into the projected trace"))))

;; ===========================================================================
;; 3. Edge cases the re-root must handle without throwing
;; ===========================================================================

(deftest reroot-passes-through-non-db-pending-events-untouched
  (testing "rf2-ta0y7 — a t1/t2 event LACKING the :rf.event/db tag, and a
            non-map :trace-events entry, pass through the re-root untouched
            (no throw, no fabrication)"
    (rf/reg-frame :test/eg {})
    (install-sensitive-schema! :test/eg)
    (let [no-db-tag {:op-type :rf.event :operation :rf.event/db-pending :tags {}}
          record    {:epoch-id      1
                     :frame         :test/eg
                     :committed-at  0
                     :event-id      :ev
                     :trigger-event [:ev]
                     :db-before     {}
                     :db-after      {}
                     :outcome       :ok
                     :rf.epoch/sensitive? false
                     ;; A non-map entry alongside the tag-less t1 event.
                     :trace-events  [no-db-tag :not-a-map]
                     :sub-runs      []
                     :renders       []
                     :effects       []}
          projected (epoch/projected-record record)]
      (is (some? projected) "projection did not throw on the edge shapes")
      (is (= {} (get-in (first (:trace-events projected)) [:tags]))
          "the t1 event lacking :rf.event/db passes through with empty tags")
      (is (= :not-a-map (second (:trace-events projected)))
          "the non-map :trace-events entry passes through untouched"))))

(deftest reroot-handles-scalar-sentinel-trace-events
  (testing "rf2-ta0y7 — when an upstream :redact-fn has already replaced the
            whole :trace-events slot with the scalar :rf/redacted sentinel,
            the re-root returns it untouched (no descent into a non-vector)"
    (let [record    {:epoch-id      1
                     :frame         :test/eg
                     :committed-at  0
                     :event-id      :ev
                     :trigger-event [:ev]
                     :db-before     {}
                     :db-after      {}
                     :outcome       :ok
                     :rf.epoch/sensitive? false
                     :trace-events  :rf/redacted
                     :sub-runs      []
                     :renders       []
                     :effects       []}
          projected (epoch/projected-record record)]
      (is (= :rf/redacted (:trace-events projected))
          "scalar-sentinel :trace-events passes through the re-root chain"))))

(deftest projection-trace-events-reroot-is-idempotent
  (testing "rf2-ta0y7 — re-projecting an already-projected record leaves the
            nested t1 :rf.event/db sensitive leaf as the :rf/redacted
            sentinel (the re-root's own target); forwarder pipelines that
            double-project do not corrupt or re-leak the nested slot"
    (rf/reg-frame :test/eg {})
    (install-sensitive-schema! :test/eg)
    (rf/reg-event-db :login (fn [db _] (assoc-in db [:auth :password] secret)))
    (rf/dispatch-sync [:login] {:frame :test/eg})

    (let [raw    (last (rf/epoch-history :test/eg))
          once   (epoch/projected-record raw)
          twice  (epoch/projected-record once)]
      (is (= once twice)
          "second projection pass is a no-op — the re-rooted :rf.event/db
           leaves are already :rf/redacted")
      (is (not (contains-secret? twice))
          "no secret re-leaks across the second pass"))))
