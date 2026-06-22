(ns re-frame.epoch-egress-trace-events-test
  "Coverage for the `:trace-events` re-root in off-box egress projection
  (rf2-ynjts.7 testing-review gap-fill).

  Per rf2-ta0y7 (`re-frame.epoch.tool-pair/reroot-trace-event-db-slots` +
  `elide-trace-events-slot`): the `:rf.event/db-pending` (t1) and
  `:rf.event/db-pending-post-flow` (t2) trace events each carry the FULL
  pending app-db value under `:tags :rf.event/db`. The bulk
  `elide-wire-value` walk over `:trace-events` treats that nested db as
  rooted at `[<i> :tags :rf.event/db ...]`, so a frame-declared sensitive
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

  DEFENCE-IN-DEPTH note (verified against `re-frame.classification/project-db-tags`,
  rf2-6773q): when the frame HAS elision declarations, the t1/t2
  `:rf.event/db` tag is ALSO redacted at EMIT time, so the on-ring trace
  already carries `:rf/redacted` for frame-declared paths. The egress
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
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; Side-effect require (mirror epoch_test.clj fixture).
            [re-frame.machines]))

;; ---- fixture --------------------------------------------------------------
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

(def ^:private secret "topsecret-do-not-leak")

;; EP-0025: durable app-db classification rides the commit-plane
;; classification effects. Seed the sensitive / large declarations through
;; `elision/apply-classification-effects` (`:source :effect`) — the same
;; registry write a `reg-event` returning `:sensitive` / `:large` performs.
;; The frame container is reg-frame'd by each deftest before this runs.
(defn- install-sensitive-schema! [frame-id]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
  nil)

(defn- install-large-schema! [frame-id]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:large [[:blob :payload]]})))
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
            (re-frame.classification/project-db-tags, rf2-6773q), so the on-ring
            trace already shows :rf/redacted at [:auth :password]. The
            egress re-root then keeps it redacted (idempotent) and covers
            the not-emit-redacted shapes (pinned by the unit tests)"
    (rf/reg-frame :test/eg {})
    (install-sensitive-schema! :test/eg)
    (rf/reg-event :login (fn [{:keys [db]} _] {:db (assoc-in db [:auth :password] secret)}))
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
    (rf/reg-event :login (fn [{:keys [db]} _] {:db (assoc-in db [:auth :password] secret)}))
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
           frame-declared path")
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
    (rf/reg-event :seed  (fn [{:keys [db]} _] {:db {}}))
    (rf/reg-event :login (fn [{:keys [db]} _] {:db (assoc-in db [:auth :password] secret)}))
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
            frame-declared [:auth :password] does not match)"
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
    (rf/reg-event :login (fn [{:keys [db]} _] {:db (assoc-in db [:auth :password] secret)}))
    (rf/dispatch-sync [:login] {:frame :test/eg})

    (let [raw    (last (rf/epoch-history :test/eg))
          once   (epoch/projected-record raw)
          twice  (epoch/projected-record once)]
      (is (= once twice)
          "second projection pass is a no-op — the re-rooted :rf.event/db
           leaves are already :rf/redacted")
      (is (not (contains-secret? twice))
          "no secret re-leaks across the second pass"))))

;; ===========================================================================
;; 4. Off-box HTTP response-body fail-closed (rf2-t55hxg.6, EP-0015
;;    disposition 5). An UNSCHEMATIZED HTTP response body is whole-sensitive
;;    off-box and MUST be omitted; the HTTP emit site stamps the disposition
;;    forward under :tags :rf.http/off-box-body (:omit | :classify), and
;;    `omit-off-box-http-bodies` (in `elide-trace-events-slot`) enforces it.
;;    A schematized body rides as-is (its per-slot marks were applied on-box).
;;
;;    No HTTP-artefact dependency here — the records are hand-built carrying
;;    the same stamp the transport emits, isolating the off-box projector.
;; ===========================================================================

(def ^:private http-body-secret "raw-bearer-token-do-not-leak")

(defn- contains-http-secret?
  "Walk an arbitrary EDN value looking for the exact http-body-secret string."
  [x]
  (cond
    (string? x) (.contains ^String x ^String http-body-secret)
    (map? x)    (or (some contains-http-secret? (keys x))
                    (some contains-http-secret? (vals x)))
    (coll? x)   (some contains-http-secret? x)
    :else       false))

(defn- http-record
  "A synthetic epoch record whose :trace-events carries one :rf.http/*
  trace event with the decoded body at `body-slot` and the given off-box
  disposition stamp."
  [frame-id operation body-slot body disposition]
  {:epoch-id      1
   :frame         frame-id
   :committed-at  0
   :event-id      :http/done
   :trigger-event [:http/done]
   :db-before     {}
   :db-after      {}
   :outcome       :ok
   :rf.epoch/sensitive? false
   :trace-events  [{:op-type   :rf.trace
                    :operation operation
                    :tags      (cond-> {body-slot body}
                                 disposition (assoc :rf.http/off-box-body disposition))}]
   :sub-runs      []
   :renders       []
   :effects       []})

(deftest off-box-omits-unschematized-replied-body
  (testing "rf2-t55hxg.6 — an UNSCHEMATIZED :rf.http/replied body (stamped
            :rf.http/off-box-body :omit) is OMITTED off-box: the :value tag
            is replaced with :rf/redacted, leaking nothing"
    (rf/reg-frame :test/http {})
    (let [record    (http-record :test/http :rf.http/replied :value
                                  {:token http-body-secret :user-id 42} :omit)
          projected (epoch/projected-record record)
          ev        (first (:trace-events projected))]
      (is (= :rf/redacted (get-in ev [:tags :value]))
          "the unschematized body slot is omitted off-box (fail-closed)")
      (is (not (contains-http-secret? projected))
          "the raw body token appears nowhere in the projected record"))))

(deftest off-box-omits-unschematized-accept-failure-body
  (testing "rf2-t55hxg.6 — an UNSCHEMATIZED :rf.http/accept-failure body rides
            at :decoded; stamped :omit, it is omitted off-box"
    (rf/reg-frame :test/http {})
    (let [record    (http-record :test/http :rf.http/accept-failure :decoded
                                  {:token http-body-secret} :omit)
          projected (epoch/projected-record record)
          ev        (first (:trace-events projected))]
      (is (= :rf/redacted (get-in ev [:tags :decoded]))
          "the unschematized :decoded body slot is omitted off-box"))))

(deftest off-box-keeps-classified-schema-body
  (testing "rf2-t55hxg.6 — a SCHEMATIZED body (stamped :classify) rides off-box
            as the classified projection emitted on-box (its sensitive slots
            already :rf/redacted, non-sensitive structure intact); the off-box
            projector does NOT omit it"
    (rf/reg-frame :test/http {})
    ;; The on-box emit already redacted the sensitive [:token] slot; the
    ;; non-sensitive [:user-id] structure rides classified.
    (let [classified {:token :rf/redacted :user-id 42}
          record     (http-record :test/http :rf.http/replied :value
                                   classified :classify)
          projected  (epoch/projected-record record)
          ev         (first (:trace-events projected))]
      (is (= classified (get-in ev [:tags :value]))
          "the classified schema body rides off-box untouched (sensitive
           already redacted on-box, non-sensitive structure preserved)"))))

(deftest off-box-include-sensitive-lifts-omission
  (testing "rf2-t55hxg.6 — a trusted-local :include-sensitive? opt-in lifts
            the off-box omission (the local-raw boundary): the unschematized
            body rides for the trusted operator who opted sensitive back in"
    (rf/reg-frame :test/http {})
    (let [body      {:token http-body-secret :user-id 42}
          record    (http-record :test/http :rf.http/replied :value body :omit)
          projected (epoch/projected-record record {:include-sensitive? true})
          ev        (first (:trace-events projected))]
      (is (= body (get-in ev [:tags :value]))
          "with :include-sensitive? true the body is NOT omitted (lifted)"))))

(deftest on-box-raw-body-preserved-on-ring
  (testing "rf2-t55hxg.6 — the ON-BOX ring record is NOT projected: the raw
            unschematized body rides verbatim on the ring (the local operator
            sees their own process). The omission is the OFF-BOX boundary, not
            an on-ring mutation"
    (rf/reg-frame :test/http {})
    (let [body   {:token http-body-secret :user-id 42}
          record (http-record :test/http :rf.http/replied :value body :omit)
          ev     (first (:trace-events record))]
      (is (= body (get-in ev [:tags :value]))
          "the hand-built ring record carries the raw body (no projection ran)
           — projected-record is the boundary, the ring stays raw"))))

(deftest off-box-passes-through-http-events-without-stamp
  (testing "rf2-t55hxg.6 — an :rf.http/replied event with NO :rf.http/off-box-body
            stamp (a body slot but no disposition) passes through the omission
            pass untouched (the omission gates strictly on the :omit stamp)"
    (rf/reg-frame :test/http {})
    (let [body      {:k "v"}
          record    (http-record :test/http :rf.http/replied :value body nil)
          projected (epoch/projected-record record)
          ev        (first (:trace-events projected))]
      (is (= body (get-in ev [:tags :value]))
          "no stamp ⇒ no omission (the projector only omits an explicit :omit)"))))

;; ---------------------------------------------------------------------------
;; rf2-t55hxg.10 — the RAW error-response body axis. The same disposition-5
;; fail-closed rule, but for the failure-category trace events that carry the
;; RAW (unschematized-by-construction) error body: `:rf.http/http-4xx` /
;; `:rf.http/http-5xx` at `:body`, `:rf.http/decode-failure` at `:body-text`,
;; and the `:rf.http/retry-attempt` trace whose intermediate failure body
;; nests at `[:failure :body]`. The emit site always stamps `:omit` for these
;; (the raw body is unschematized) so the off-box projector omits the slot,
;; lifted only by the trusted-local `:include-sensitive?` opt-in. On-box stays
;; raw.
;; ---------------------------------------------------------------------------

(deftest off-box-omits-raw-http-5xx-body
  (testing "rf2-t55hxg.10 — a raw :rf.http/http-5xx body (stamped :omit) is
            OMITTED off-box: the :body tag is replaced with :rf/redacted"
    (rf/reg-frame :test/http {})
    (let [record    (http-record :test/http :rf.http/http-5xx :body
                                  (str "error echoing " http-body-secret) :omit)
          projected (epoch/projected-record record)
          ev        (first (:trace-events projected))]
      (is (= :rf/redacted (get-in ev [:tags :body]))
          "the raw 5xx body slot is omitted off-box (fail-closed)")
      (is (not (contains-http-secret? projected))
          "the raw error-body token appears nowhere in the projected record"))))

(deftest off-box-omits-raw-http-4xx-body
  (testing "rf2-t55hxg.10 — a raw :rf.http/http-4xx body is omitted off-box"
    (rf/reg-frame :test/http {})
    (let [record    (http-record :test/http :rf.http/http-4xx :body
                                  (str "forbidden " http-body-secret) :omit)
          projected (epoch/projected-record record)
          ev        (first (:trace-events projected))]
      (is (= :rf/redacted (get-in ev [:tags :body])))
      (is (not (contains-http-secret? projected))))))

(deftest off-box-omits-raw-decode-failure-body-text
  (testing "rf2-t55hxg.10 — a raw :rf.http/decode-failure body-text is omitted
            off-box (the decode is what failed, so the body is unschematized)"
    (rf/reg-frame :test/http {})
    (let [record    (http-record :test/http :rf.http/decode-failure :body-text
                                  (str "not-json " http-body-secret) :omit)
          projected (epoch/projected-record record)
          ev        (first (:trace-events projected))]
      (is (= :rf/redacted (get-in ev [:tags :body-text]))
          "the raw decode-failure body-text is omitted off-box")
      (is (not (contains-http-secret? projected))))))

(deftest off-box-omits-nested-retry-attempt-failure-body
  (testing "rf2-t55hxg.10 — a :rf.http/retry-attempt nests the intermediate
            failure's raw body at [:failure :body]; stamped :omit it is omitted
            off-box (a retry-eligible 4xx/5xx echoing a token)"
    (rf/reg-frame :test/http {})
    (let [record    {:epoch-id      1
                     :frame         :test/http
                     :committed-at  0
                     :event-id      :http/retry
                     :trigger-event [:http/retry]
                     :db-before     {}
                     :db-after      {}
                     :outcome       :ok
                     :rf.epoch/sensitive? false
                     :trace-events  [{:op-type   :rf.trace
                                      :operation :rf.http/retry-attempt
                                      :tags      {:request-id :rid
                                                  :attempt    1
                                                  :failure    {:kind :rf.http/http-5xx
                                                               :status 500
                                                               :body (str "retry-body " http-body-secret)}
                                                  :rf.http/off-box-body :omit}}]
                     :sub-runs      []
                     :renders       []
                     :effects       []}
          projected (epoch/projected-record record)
          ev        (first (:trace-events projected))]
      (is (= :rf/redacted (get-in ev [:tags :failure :body]))
          "the nested intermediate-failure raw body is omitted off-box")
      (is (= 500 (get-in ev [:tags :failure :status]))
          "non-body failure metadata (:status) rides verbatim")
      (is (not (contains-http-secret? projected))
          "no token re-leaks via the nested retry-attempt failure body"))))

(deftest off-box-include-sensitive-lifts-raw-error-body-omission
  (testing "rf2-t55hxg.10 — a trusted-local :include-sensitive? opt-in lifts
            the off-box omission of a raw error body (the local-raw boundary)"
    (rf/reg-frame :test/http {})
    (let [body      (str "raw error " http-body-secret)
          record    (http-record :test/http :rf.http/http-5xx :body body :omit)
          projected (epoch/projected-record record {:include-sensitive? true})
          ev        (first (:trace-events projected))]
      (is (= body (get-in ev [:tags :body]))
          "with :include-sensitive? true the raw error body is NOT omitted"))))

(deftest on-box-raw-error-body-preserved-on-ring
  (testing "rf2-t55hxg.10 — the ON-BOX ring record is NOT projected: the raw
            error body rides verbatim on the ring (the local operator sees
            their own process). The omission is the OFF-BOX boundary."
    (rf/reg-frame :test/http {})
    (let [body   (str "raw error " http-body-secret)
          record (http-record :test/http :rf.http/http-5xx :body body :omit)
          ev     (first (:trace-events record))]
      (is (= body (get-in ev [:tags :body]))
          "the hand-built ring record carries the raw error body (no projection
           ran) — projected-record is the boundary, the ring stays raw"))))
