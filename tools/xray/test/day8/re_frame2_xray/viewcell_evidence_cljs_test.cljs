(ns day8.re-frame2-xray.viewcell-evidence-cljs-test
  "rf2-vxgfnd.146/.238/.286 — the REAL Xray runtime owns the re-frame.ui
  ViewCell invalidation-evidence projection, EXACTLY (per span, not per
  reusable id), REACTIVELY (an ownership change invalidates the query), and
  LIFECYCLE-COMPLETELY (every transition — acquire, read, HMR re-arm,
  release, reclaim, canonical reset — keeps ownership correct).

  Requiring `day8.re-frame2-xray.preload` here runs the ACTUAL preload
  boot block (the same namespace shadow-cljs `:devtools/preloads` loads
  into every dev host), and `owner-at-load` snapshots the evidence
  tier's owner IMMEDIATELY after — before any fixture can reset it. If
  the preload's `viewcell-evidence/acquire!` wiring is removed, that
  snapshot is nil and the wiring test goes RED (the mutation probe the
  bead demands; the browser counterpart lives in the feature-matrix
  shell sweep, which checks the install sentinel + the rendered Views
  panel section against the real chrome runtime).

  ## The three .286 properties, each with a red-before-fix control

    - EXACT (rf2-vxgfnd.286 AC1) — `same-key-aba-*`: acquire span A, close
      it, reclaim the SAME owner-id as span B, publish a real B row. A's
      receipt reads NO B data and A's release cannot clear B. Reverting the
      receipt fence to owner equality makes it fail (owner-id matches, so a
      bare read would surface B and a bare release would clear B).
    - REACTIVE (rf2-vxgfnd.286 AC3/AC4) — `ownership-change-is-reactive-*`:
      the query DECLARES the ownership-revision axis as a `:<-` input, and
      every transition bumps that revision in `:rf/xray` app-db. Removing
      the listener/event or the `:<-` input makes it fail.
    - LIFECYCLE-COMPLETE (rf2-vxgfnd.286 AC5) — `canonical-reset-*`:
      `core/init!` then the clean-slate `reset-all!`/`reset-runtime!` tier
      leaves owner, rows, entries and sentinel at baseline (owner-checked),
      while a FOREIGN registration survives. Omitting the reset-tier
      cleanup makes it fail."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.subs.tooling :as subs-tooling]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.tool :as tool]
            [re-frame.ui.tool.evidence :as evidence]
            [day8.re-frame2-xray.core :as core]
            [day8.re-frame2-xray.epoch :as epoch]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.panels.reactive-panel-subs :as reactive-panel-subs]
            ;; The REAL preload — its debug-gated boot block runs at load,
            ;; acquiring the evidence projection under Xray's identity.
            [day8.re-frame2-xray.preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.viewcell-evidence :as viewcell-evidence]))

;; Snapshotted at NAMESPACE LOAD — i.e. right after the required preload's
;; boot block ran, before any fixture resets. THE wiring-removal probe.
(def ^:private owner-at-load (evidence/installed-owner))

(def ^:private sentinel-key "__day8_re_frame2_xray_viewcell_evidence")

(def ^:private sentinel-at-load (aget js/globalThis sentinel-key))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture {})
  (fn [f]
    (reactive/reset-scheduler!)
    ;; The Xray-level fixture-reset door: force-releases the tier AND
    ;; withdraws Xray's sentinel + clears the ledger receipt — so no
    ;; cross-test owner/sentinel leakage (rf2-vxgfnd.286: the prior bespoke
    ;; `evidence/force-release!` cleared the tier but LEAKED Xray's sentinel).
    (viewcell-evidence/reset-for-test!)
    (try (f) (finally
               (reactive/reset-scheduler!)
               (viewcell-evidence/reset-for-test!)))))

(def ^:private fid :rf/default)

(defn- boot-xray!
  "The production init path (mirrors `init_filter_reset_cljs_test`):
  register the Xray handlers, then `ensure-xray-frame!` — so the
  `:rf/xray` frame and the `:rf.xray/*` sub graph (incl. the ownership
  reactivity bridge) exist exactly as a real page load builds them."
  []
  (registry/register-xray-handlers!)
  (mount/ensure-xray-frame!))

(defn- rc!
  "Render (probe) `queries` under the host frame, then commit — the cell
  ends observing them through REAL subscription handles (the ui
  tool-evidence-test technique for driving genuine observation-port
  fan-out)."
  [cell queries]
  (let [[_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn [] (mapv (fn [i q]
                                          (reactive/sub-read [:vce/site i] q))
                                        (range) queries))))]
    (reactive/commit! cell capture))
  cell)

(defn- xray-query []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/viewcell-evidence])))

(defn- ownership-rev-sub []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/viewcell-evidence-ownership])))

;; ===========================================================================
;; The preload wiring — no test-only direct acquire stands in for it
;; ===========================================================================

(deftest preload-boot-acquires-the-projection-under-xrays-stable-identity
  (is (= viewcell-evidence/owner-id owner-at-load)
      "the REAL preload boot block acquired the evidence projection —
       removing the preload acquire wiring turns this nil (AC: a mutation
       removing the Xray acquire must fail)")
  (is (some? sentinel-at-load)
      "…and mirrored the acquire on the browser-probe sentinel")
  (is (= (str viewcell-evidence/owner-id)
         (aget sentinel-at-load "owner"))))

;; ===========================================================================
;; A real observation movement → ViewCell flush → the Xray query row
;; ===========================================================================

(deftest real-movement-surfaces-in-the-xray-query-row
  (boot-xray!)
  (is (some? (viewcell-evidence/acquire!)))
  (rf/reg-sub :vce/a (fn [db _] (:a db)))
  (frame/replace-app-db! fid {:a 1})
  (let [cell (rc! (reactive/make-cell ::v) [[:vce/a]])]
    ;; a REAL port fan-out: re-registering the sub fires each committed
    ;; site's live handle `on-change` with the rich `:hmr` payload
    (rf/reg-sub :vce/a (fn [db _] (:a db)))
    (reactive/flush-pending!)

    (testing "the consumer projection carries the bounded S3 row"
      (let [rows (viewcell-evidence/rows)
            row  (first rows)]
        (is (= 1 (count rows)))
        (is (= ::v (:view-id row)) "stable view identity")
        (is (number? (:occurrence row)) "OCCURRENCE identity — stable ordinal")
        (is (nil? (:root-id row)) "no live client root owns a graft cell")
        (is (= 1 (:count row)))
        (is (= 1 (:batches row)))
        (is (= #{:hmr} (:causes row)) "the real cause is projected")
        (is (= [[:sub fid [:vce/a]]] (:targets row))
            "…with the moving target key")
        (is (= 0 (:dropped-count row)))
        (is (true? (:dropped-exact? row)) "the honest loss account")
        ;; S3 fields sourced from the versioned public `explain-render`
        ;; projection (rf2-vxgfnd.95.7) — NOT the raw tier read.
        (is (= :connected (:connection row))
            "the committed incarnation reads its LIVE connection state")
        (is (true? (:targets-exact? row))
            "observation-identity fidelity — the shown targets kept exact identity")
        (is (contains? row :lifecycle)
            "the honest hide-vs-unmount lifecycle log rides the row")))

    (testing "the version-status read reports the supported public-tier version"
      (let [{:keys [version supported?]} (viewcell-evidence/version-status)]
        (is (= tool/schema-version version) "the producer's evidence-schema version")
        (is (true? supported?) "…recognised by this Xray build")))

    (testing "the SAME row surfaces through Xray's query path — the
              `:rf.xray/viewcell-evidence` sub the Views panel reads"
      (let [rows (xray-query)]
        (is (= (viewcell-evidence/rows) rows))
        (is (= ::v (:view-id (first rows))))))

    (testing "occurrence identity is stable across further batches"
      (let [id-before (:occurrence (first (viewcell-evidence/rows)))]
        ;; a real re-render re-acquires fresh handles on the re-registered
        ;; nodes before any further movement can fan out to this cell
        (rc! cell [[:vce/a]])
        (rf/reg-sub :vce/a (fn [db _] (:a db)))
        (reactive/flush-pending!)
        (let [row (first (viewcell-evidence/rows))]
          (is (= id-before (:occurrence row)))
          (is (= 2 (:count row)))
          (is (= 2 (:batches row))))))

    (testing "teardown prunes the row — released references, empty query"
      (reactive/teardown! cell)
      (is (= [] (viewcell-evidence/rows)))
      ;; nudge the sub's freshness input (the epoch-pump axis) so a
      ;; cached composite recomputes before the re-query
      (frame/replace-app-db! :rf/xray {:epoch-history [::pumped]})
      (is (= [] (xray-query))))))

;; ===========================================================================
;; S3 counterexamples (rf2-vxgfnd.95.7) — the projection honours the versioned
;; envelope, the honest loss account, and the hide-vs-unmount lifecycle labels;
;; nothing inferred is presented as exact
;; ===========================================================================

(deftest stale-evidence-version-degrades-honestly-never-mis-parses
  ;; A producer stamping an evidence-schema version this Xray build does not
  ;; understand must NOT be parsed as exact — `rows` degrades to empty and
  ;; `version-status` surfaces the mismatch (the panel renders the honest
  ;; banner, not a silent empty section).
  (is (some? (viewcell-evidence/acquire!)) "Xray owns the span")
  (with-redefs [tool/explain-render
                (fn ([] (tool/explain-render nil))
                  ([_view-id]
                   {:rf.ui.tool/version 9999
                    :view-id nil
                    :occurrences [{:occurrence 1 :view-id ::v :connection :connected
                                   :render-count 3 :batches 1}]}))]
    (is (= [] (viewcell-evidence/rows))
        "an unrecognised producer version suppresses rows (no mis-parse)")
    (is (= {:version 9999 :supported? false} (viewcell-evidence/version-status))
        "…and the version-status read reports the mismatch honestly")))

(deftest a-producer-bump-alone-does-not-widen-consumer-support
  ;; rf2-vxgfnd.98.1.1 — the version boundary must be REAL, not nominal. Xray
  ;; pins the evidence-schema version IT understands to a consumer-owned literal,
  ;; so a producer that bumps its OWN `re-frame.ui.tool/schema-version` (and thus
  ;; stamps envelopes with the new version) does NOT become auto-'supported': the
  ;; bump is a DETECTABLE mismatch until Xray is taught the shape. RED before the
  ;; fix, where `supported-version?` compared the envelope to the producer's own
  ;; `tool/schema-version` — moving BOTH here in lockstep, so the incompatible
  ;; shape was accepted as exact and `rows` mis-parsed it.
  (is (some? (viewcell-evidence/acquire!)) "Xray owns the span")
  (let [producer-ahead (inc tool/schema-version)] ; a version the consumer pin is not
    (with-redefs [tool/schema-version producer-ahead
                  tool/explain-render
                  (fn ([] (tool/explain-render nil))
                    ([_view-id]
                     {:rf.ui.tool/version producer-ahead
                      :view-id nil
                      :occurrences [{:occurrence 1 :view-id ::v :connection :connected
                                     :render-count 3 :batches 1}]}))]
      (is (= [] (viewcell-evidence/rows))
          "a producer-ahead version the consumer was not taught degrades to empty")
      (is (= {:version producer-ahead :supported? false}
             (viewcell-evidence/version-status))
          "…and version-status reports the mismatch against the CONSUMER pin, not the producer var"))))

(deftest disconnected-truncated-and-inexact-project-without-fabrication
  ;; Occurrence identity, the LIVE connection state, the honest hide-vs-unmount
  ;; lifecycle log, the two DISTINCT loss axes (observation-identity fidelity
  ;; vs omitted-count completeness) all pass through truthfully.
  (is (some? (viewcell-evidence/acquire!)))
  (with-redefs [tool/explain-render
                (fn ([] (tool/explain-render nil))
                  ([_view-id]
                   {:rf.ui.tool/version tool/schema-version
                    :view-id nil
                    :occurrences
                    [{:occurrence 7 :view-id ::hidden :root-id :page/shop
                      :connection :disconnected
                      :lifecycle {:intervals [{:state :disconnected
                                               :reason :activity-hidden
                                               :proof :reconnect}]
                                  :dropped 0 :exact? true}
                      :causes #{:subscription} :render-count 4 :batches 2
                      :first-epoch 10 :latest-epoch 14
                      :observations {:targets [[:sub :rf/app [:a]]]
                                     :identity-exact? false}
                      :loss {:dropped 70 :exact? false}}]}))]
    (let [row (first (viewcell-evidence/rows))]
      (is (= 7 (:occurrence row)) "occurrence identity preserved")
      (is (= :disconnected (:connection row)) "the LIVE connection state")
      (is (= :activity-hidden (:reason (last (:intervals (:lifecycle row)))))
          "the qualified retroactive Activity-hidden label rides the lifecycle log")
      (is (false? (:targets-exact? row))
          "observation-identity fidelity is honest — targets went opaque")
      (is (= 70 (:dropped-count row)) "the omitted-target count")
      (is (false? (:dropped-exact? row))
          "…flagged a FLOOR, never presented as a complete count"))))

(deftest view-sites-project-the-static-manifest-with-dynamic-and-opaque-honesty
  ;; The event-site provenance + dependency sites read from the versioned
  ;; public manifest projections: literal queries verbatim, `:dynamic` queries
  ;; and opaque handlers labelled — never fabricated.
  (registrar/register! :view ::compiled
    {:rf.ui/manifest
     {:view-id      ::compiled
      :display-name "Compiled"
      :source       {:file "src/cart.cljs" :line 12 :column 3}
      :capabilities #{:html}
      :prop-slots   []
      :props-schema nil
      :sites {:subs   [{:sid 1 :query [:cart/total] :path []}
                       {:sid 2 :query '[:cart/item qty] :path []}]
              :events [{:sid 3 :prop :on-click :classification :vector
                        :serializable? true :handler [:cart/add 3] :path []}
                       {:sid 4 :prop :on-blur :classification :dynamic
                        :serializable? false :handler :opaque :path []}]
              :slots [] :react [] :effects [] :dispatch-fns []
              :frame-ops [] :locals [] :htmls []}}})
  (let [[site] (viewcell-evidence/view-sites [::compiled ::compiled :legacy/unregistered])]
    (is (= ::compiled (:view-id site)) "de-duplicated + skips the unregistered id")
    (is (= {:file "src/cart.cljs" :line 12 :column 3} (:source site)))
    (is (= #{:html} (:capabilities site)))
    (let [subs (:subscriptions (:dependencies site))]
      (is (= 2 (count subs)))
      (is (false? (:dynamic? (first subs))) "literal query is projected verbatim")
      (is (= [:cart/total] (:query (first subs))))
      (is (true? (:dynamic? (second subs)))
          "a query carrying a captured local is honestly :dynamic"))
    (let [events (:event-sites site)]
      (is (= [:cart/add 3] (:handler (first events)))
          "a serializable literal handler vector IS its inspectable shape")
      (is (= :literal (:site-kind (first events))))
      (is (= :opaque (:handler (second events)))
          "an opaque handler is never claimed inspectable")
      (is (= :dynamic (:site-kind (second events))))))
  (is (= [] (viewcell-evidence/view-sites [:legacy/unregistered]))
      "an id with no compiled-view manifest yields NO fabricated row"))

;; ===========================================================================
;; EXACT — same-key ABA: a superseded span reads no successor and cannot
;; clear it (rf2-vxgfnd.286 AC1)
;; ===========================================================================

(deftest same-key-aba-a-superseded-span-reads-no-successor-and-cannot-clear-it
  ;; span A publishes a real row
  (let [receipt-a (viewcell-evidence/acquire!)
        cell-a    (reactive/make-cell ::a)]
    (is (some? receipt-a))
    (reactive/mark-dirty! cell-a 1)
    (reactive/flush-pending!)
    (is (= 1 (:count (first (viewcell-evidence/rows receipt-a))))
        "A sees A's own row through A's receipt")

    ;; close A, reclaim the SAME logical id as span B
    (is (true? (viewcell-evidence/release-current!)) "span A closed")
    (let [receipt-b (viewcell-evidence/acquire!)
          cell-b    (reactive/make-cell ::b)]
      (is (some? receipt-b))
      (is (not= receipt-a receipt-b)
          "B is a DISTINCT span even under the SAME reusable owner-id")
      (is (= viewcell-evidence/owner-id (evidence/installed-owner))
          "…reclaimed under the same tier id")
      (reactive/mark-dirty! cell-b 1)
      (reactive/flush-pending!)
      (is (= 1 (:count (first (viewcell-evidence/rows receipt-b))))
          "B publishes a real, distinct row")

      (testing "A reads NO B data — a superseded span's receipt authorizes nothing"
        ;; Fail-before: with owner-equality the read passes the id fence
        ;; (owner-id matches) and would surface B's row.
        (is (= [] (viewcell-evidence/rows receipt-a))))

      (testing "A's release cannot clear B's owner, sentinel, entries or delivery"
        ;; Fail-before: an owner-checked-only release would call the tier's
        ;; `uninstall!` for owner-id and release B.
        (is (false? (viewcell-evidence/release! receipt-a))
            "the stale receipt's release is refused")
        (is (= viewcell-evidence/owner-id (evidence/installed-owner))
            "B still owns the projection")
        (is (some? (aget js/globalThis sentinel-key))
            "B's install sentinel is intact")
        (is (= 1 (:count (first (viewcell-evidence/rows receipt-b))))
            "B's retained entries are intact")
        ;; a LATER delivery still lands into B's live sink
        (reactive/mark-dirty! cell-b 2)
        (reactive/flush-pending!)
        (is (= 2 (:count (first (viewcell-evidence/rows receipt-b))))
            "B's sink still delivers after the stale release")))))

;; ===========================================================================
;; REACTIVE — ownership change is a reactive input to the evidence query
;; (rf2-vxgfnd.286 AC3/AC4)
;; ===========================================================================

(deftest ownership-change-is-a-reactive-input-to-the-evidence-query
  (boot-xray!)

  (testing "the query DECLARES the ownership-revision axis as a `:<-` input"
    ;; Fail-before: the composite `:<-`'d only `:rf.xray/epoch-history`, so an
    ;; acquire/release invalidated nothing until an unrelated epoch pump.
    (let [inputs    (:inputs (get (subs-tooling/sub-topology)
                                  :rf.xray/viewcell-evidence))
          input-ids (map #(if (vector? %) (first %) %) (or inputs []))]
      (is (some #{:rf.xray/viewcell-evidence-ownership} input-ids)
          "so an acquire/release recomputes the query without an epoch pump")))

  (testing "each ownership transition bumps the reactive ownership revision"
    ;; Fail-before: no listener / event / ownership slot ⇒ the value never
    ;; changes on a release, so a live query keeps its stale rows.
    (let [receipt       (viewcell-evidence/acquire!)
          after-acquire (ownership-rev-sub)]
      (is (some? receipt))
      (is (true? (viewcell-evidence/release-current!)))
      (let [after-release (ownership-rev-sub)]
        (is (not= after-acquire after-release)
            "release bumped the reactive ownership input — a held query
             recomputes immediately, no epoch pump")
        (is (> after-release after-acquire) "revisions are monotonic"))))

  (testing "a held subscription reflects the release — receipt-fenced recompute"
    ;; On the plain-atom node adapter every deref recomputes, so this is a
    ;; live-surface smoke over the held-subscription path (the caching-adapter
    ;; gate is the feature-matrix shell sweep); the receipt fence keeps the
    ;; recompute honest.
    (let [receipt (viewcell-evidence/acquire!)
          cell    (reactive/make-cell ::live)
          sub     (rf/with-frame :rf/xray
                    (rf/subscribe [:rf.xray/viewcell-evidence]))]
      (is (some? receipt))
      (reactive/mark-dirty! cell 1)
      (reactive/flush-pending!)
      (is (seq @sub) "non-empty while Xray owns the span")
      (viewcell-evidence/release-current!)
      (is (= [] @sub)
          "empty after release — the ownership axis recomputed it, not an
           epoch pump"))))

;; ===========================================================================
;; Lifecycle — HMR re-arm keeps the span + evidence; exact-owner release;
;; never clobber a foreign owner
;; ===========================================================================

(deftest hmr-rearm-keeps-the-same-span-and-accumulated-evidence
  (let [receipt-1 (viewcell-evidence/acquire!)]
    (is (some? receipt-1))
    (let [cell (reactive/make-cell ::v)]
      (reactive/mark-dirty! cell 1)
      (reactive/flush-pending!)
      (is (= 1 (:count (first (viewcell-evidence/rows)))))
      ;; the `:after-load` path: the preload boot re-runs → same-span re-arm
      (let [receipt-2 (viewcell-evidence/acquire!)]
        (is (= receipt-1 receipt-2)
            "the HMR re-arm PRESERVES the span receipt (same continuous span)")
        (is (true? (viewcell-evidence/installed?)))
        (is (= 1 (:count (first (viewcell-evidence/rows))))
            "accumulated evidence survives the re-arm"))
      (reactive/mark-dirty! cell 2)
      (reactive/flush-pending!)
      (is (= 2 (:count (first (viewcell-evidence/rows))))
          "…and delivery continues into the SAME accumulator"))))

(deftest shutdown-releases-the-exact-owner-and-never-clobbers-a-foreign-one
  (testing "release-current! frees exactly Xray's registration"
    (is (some? (viewcell-evidence/acquire!)))
    (is (some? (aget js/globalThis sentinel-key)))
    (is (true? (viewcell-evidence/release-current!)))
    (is (nil? (evidence/installed-owner)))
    (is (nil? (viewcell-evidence/current-receipt)) "the span receipt is cleared")
    (is (= [] (viewcell-evidence/rows)) "closed retains zero")
    (is (nil? (aget js/globalThis sentinel-key))
        "the install sentinel is withdrawn with the registration"))

  (testing "a foreign owner is NEVER clobbered — Xray's acquire is refused"
    (is (true? (evidence/install! ::another-tool)))
    (is (nil? (viewcell-evidence/acquire!)) "acquire rejected → nil receipt")
    (is (= ::another-tool (evidence/installed-owner))
        "the foreign registration stands")
    (is (false? (viewcell-evidence/installed?)))
    (is (nil? (viewcell-evidence/current-receipt)))
    (is (false? (viewcell-evidence/release-current!))
        "…and Xray's release cannot free what it does not own")
    (is (= ::another-tool (evidence/installed-owner)))))

;; ===========================================================================
;; Ownership fence on the READ path (rf2-vxgfnd.238) — a foreign owner's
;; accumulated evidence is NEVER observable through Xray
;; ===========================================================================

(deftest foreign-owner-evidence-never-surfaces-through-any-xray-read
  ;; A foreign tool owns the shared projection AND has accumulated evidence
  ;; in it — the case the never-clobber test omits (it installs a foreign
  ;; owner but delivers nothing). Drive a real flush so the shared slot,
  ;; observed through the versioned public projection, is non-empty.
  (is (true? (evidence/install! ::another-tool)))
  (let [cell (reactive/make-cell ::foreign-v)]
    (reactive/mark-dirty! cell 1)
    (reactive/flush-pending!)
    (is (= 1 (count (:views (tool/mounted-views))))
        "the FOREIGN owner accumulated a row in the shared projection
         (visible through the public tier, which is owner-agnostic — hence
         the receipt fence)")
    ;; Xray's own claim is rejected — Xray does not own the projection.
    (is (nil? (viewcell-evidence/acquire!)))
    (is (false? (viewcell-evidence/installed?)))
    ;; AC: rows + every derived Xray query return NO evidence unless Xray
    ;; owns the installed projection.
    (is (= [] (viewcell-evidence/rows))
        "ownership rejection ⇒ zero observation through Xray's rows")
    (boot-xray!)
    (is (= [] (xray-query))
        "…and zero through the :rf.xray/viewcell-evidence query path")
    ;; Xray neither released nor read the foreign owner's projection.
    (is (= ::another-tool (evidence/installed-owner))
        "the foreign registration is untouched")
    (is (= 1 (count (:views (tool/mounted-views))))
        "…and its evidence still lives in the shared projection")))

;; ===========================================================================
;; core/init! is a full evidence-projection acquire path (rf2-vxgfnd.238)
;; ===========================================================================

(deftest core-init-acquires-the-projection-under-xrays-identity
  ;; A clean process that requires `core` and calls `init!` (the supported
  ;; manual alternative to the preload) must acquire the evidence projection,
  ;; or Xray is enabled WITHOUT ViewCell evidence.
  (is (nil? (evidence/installed-owner)) "clean slate — nobody owns it")
  (core/init!)
  (is (= viewcell-evidence/owner-id (evidence/installed-owner))
      "core/init! acquires the projection under Xray's stable owner id")
  (is (true? (viewcell-evidence/installed?)))
  (is (some? (viewcell-evidence/current-receipt)) "…opening an ownership span")
  (is (some? (aget js/globalThis sentinel-key))
      "…and mirrors the acquire on the browser-probe sentinel")
  (testing "idempotent — a second init! is the same-span re-arm, not a reject"
    (let [receipt-before (viewcell-evidence/current-receipt)]
      (core/init!)
      (is (= viewcell-evidence/owner-id (evidence/installed-owner)))
      (is (= receipt-before (viewcell-evidence/current-receipt))
          "the re-arm keeps the span receipt")
      (is (true? (viewcell-evidence/installed?))))))

(deftest core-init-does-not-clobber-a-foreign-owner
  ;; The manual path honours the same never-clobber contract as the preload.
  (is (true? (evidence/install! ::another-tool)))
  (core/init!)
  (is (= ::another-tool (evidence/installed-owner))
      "core/init! did not clobber the foreign registration")
  (is (false? (viewcell-evidence/installed?)))
  (is (= [] (viewcell-evidence/rows))
      "…and exposes no evidence while another owner holds the projection"))

(deftest release-then-core-init-reclaims-a-fresh-span
  ;; release then re-init: the ordinary tool-shutdown / test door, and a
  ;; re-init that reclaims a fresh, empty ownership span with a NEW receipt.
  (let [receipt-1 (viewcell-evidence/acquire!)]
    (is (some? receipt-1))
    (let [cell (reactive/make-cell ::v)]
      (reactive/mark-dirty! cell 1)
      (reactive/flush-pending!)
      (is (= 1 (:count (first (viewcell-evidence/rows))))))
    (is (true? (viewcell-evidence/release! receipt-1)))
    (is (nil? (evidence/installed-owner)))
    (is (= [] (viewcell-evidence/rows)) "closed retains zero")
    ;; re-init through the manual path reclaims the projection, empty
    (core/init!)
    (is (true? (viewcell-evidence/installed?)))
    (is (not= receipt-1 (viewcell-evidence/current-receipt))
        "the reclaim mints a NEW span receipt")
    (is (= [] (viewcell-evidence/rows))
        "re-init starts from a fresh, empty projection")))

;; ===========================================================================
;; LIFECYCLE-COMPLETE — the canonical clean-slate reset tier releases Xray's
;; evidence owner (owner-checked); a foreign owner survives (rf2-vxgfnd.286 AC5)
;; ===========================================================================

(deftest canonical-reset-tier-releases-xrays-evidence-owner-checked
  (testing "core/init! then reset-all! leaves owner, rows, entries + sentinel baseline"
    (core/init!)
    (is (= viewcell-evidence/owner-id (evidence/installed-owner)))
    (is (some? (aget js/globalThis sentinel-key)))
    (let [cell (reactive/make-cell ::v)]
      (reactive/mark-dirty! cell 1)
      (reactive/flush-pending!)
      (is (= 1 (count (viewcell-evidence/rows))) "a real row accumulated"))
    ;; Fail-before: reset-all! omitted the evidence release, leaking owner +
    ;; sentinel + entries across tests.
    (xray-test-support/reset-all!)
    (is (nil? (evidence/installed-owner))
        "the clean-slate reset released Xray's registration")
    (is (nil? (viewcell-evidence/current-receipt)) "…and its span receipt")
    (is (= [] (viewcell-evidence/rows)) "no retained rows/entries")
    (is (nil? (aget js/globalThis sentinel-key)) "the sentinel is withdrawn"))

  (testing "reset-runtime! is likewise a full owner-checked evidence teardown"
    (core/init!)
    (is (= viewcell-evidence/owner-id (evidence/installed-owner)))
    (xray-test-support/reset-runtime!)
    (is (nil? (evidence/installed-owner)))
    (is (nil? (aget js/globalThis sentinel-key))))

  (testing "a FOREIGN registration SURVIVES the owner-checked reset"
    ;; Fail-before/guard: an unconditional force-release in the reset tier
    ;; would clobber the foreign owner — the reset must be owner-checked.
    (is (true? (evidence/install! ::another-tool)))
    (xray-test-support/reset-all!)
    (is (= ::another-tool (evidence/installed-owner))
        "owner-checked reset never clobbers a foreign owner")))

;; ===========================================================================
;; LIVE UPGRADE — the reactivity bridge migrates into an ALREADY-registered
;; pre-#5915 process (rf2-ykaq4u)
;; ===========================================================================

(defn- evidence-sub-input-ids
  "The static `:<-` input sub-ids of `:rf.xray/viewcell-evidence`, read off
  the live `sub-topology`. `[]` when the sub is unregistered."
  []
  (let [inputs (:inputs (get (subs-tooling/sub-topology) :rf.xray/viewcell-evidence))]
    (map #(if (vector? %) (first %) %) (or inputs []))))

(deftest live-upgrade-from-a-pre-bridge-process-installs-the-reactivity-bridge
  ;; Model a pre-#5915 already-registered Xray process the way a live shadow
  ;; `:after-load` of new code onto an old process presents it:
  ;;   - the umbrella idempotency gate is SET (the OLD full install ran),
  ;;   - the OLD epoch-only `:rf.xray/viewcell-evidence` sub is cached (its
  ;;     `:rf.xray/epoch-history` input registered by the old full install),
  ;;   - the ownership event/sub/listener + two-input evidence sub are ABSENT,
  ;;   - NO schema stamp (schema-versioning did not exist in the old code).
  ;;
  ;; The runtime fixture restores a registrar SNAPSHOT that already carries
  ;; the current bridge (the preload boot registered it before the snapshot
  ;; was taken), so modelling the pre-bridge shape means actively
  ;; DOWNGRADING: re-register the epoch-only evidence sub (replacing the
  ;; two-input one) and remove the ownership event + sub. Both the
  ;; epoch-history input and the old evidence sub come from their CANONICAL
  ;; owner namespaces (never the test ns) so `make-frame`'s image assembly
  ;; sees a single source coordinate per id, not a duplicate.
  (epoch/install!)
  (reactive-panel-subs/install-legacy-viewcell-evidence-sub-for-test!)
  (registrar/unregister! :event :rf.xray/viewcell-evidence-ownership-changed)
  (registrar/unregister! :sub :rf.xray/viewcell-evidence-ownership)
  (viewcell-evidence/set-ownership-listener! nil)
  (registry/simulate-legacy-registration!)
  (rf/make-frame {:id :rf/xray})

  (testing "PRECONDITION — the legacy shape lacks the reactivity bridge"
    (is (nil? (registrar/handler :event :rf.xray/viewcell-evidence-ownership-changed))
        "no ownership-transition event")
    (is (nil? (registrar/handler :sub :rf.xray/viewcell-evidence-ownership))
        "no ownership-revision sub")
    (is (not (some #{:rf.xray/viewcell-evidence-ownership} (evidence-sub-input-ids)))
        "the cached evidence sub is epoch-only — one input"))

  ;; The live upgrade: the reloaded preload re-runs register-xray-handlers!.
  ;; Under the pre-fix boolean-only gate this no-ops entirely (umbrella set)
  ;; and the bridge stays absent; the schema-migration seam installs it.
  (registry/register-xray-handlers!)

  (testing "the migration installed the ownership event + revision sub, callable in :rf/xray"
    (is (some? (registrar/handler :event :rf.xray/viewcell-evidence-ownership-changed)))
    (is (some? (registrar/handler :sub :rf.xray/viewcell-evidence-ownership)))
    (is (= 0 (rf/with-frame :rf/xray
               @(rf/subscribe [:rf.xray/viewcell-evidence-ownership])))
        "the ownership-revision sub reads its default in the :rf/xray frame"))

  (testing "TOPOLOGY — :rf.xray/viewcell-evidence now depends on the ownership axis"
    ;; The bead's registration-topology proof: the re-registered two-input
    ;; sub replaced the cached epoch-only one.
    (is (some #{:rf.xray/viewcell-evidence-ownership} (evidence-sub-input-ids))
        "an acquire/release now recomputes the query without an epoch pump"))

  (testing "acquire then release IMMEDIATELY invalidate a held Views subscription"
    ;; The immediate-reactivity claim the PR made and the bug broke: on the
    ;; plain-atom node adapter every deref recomputes, so this is the live
    ;; held-subscription surface — the receipt fence keeps it honest.
    (let [receipt (viewcell-evidence/acquire!)
          cell    (reactive/make-cell ::live)
          sub     (rf/with-frame :rf/xray
                    (rf/subscribe [:rf.xray/viewcell-evidence]))]
      (is (some? receipt) "Xray claimed a fresh span on the upgraded process")
      (reactive/mark-dirty! cell 1)
      (reactive/flush-pending!)
      (is (seq @sub) "non-empty while Xray owns the span")
      (viewcell-evidence/release-current!)
      (is (= [] @sub)
          "empty immediately after release — the migrated ownership axis
           recomputed it, not a later epoch pump (no stale row)"))))

(deftest live-upgrade-migration-is-idempotent-and-installs-one-listener
  ;; A completed upgrade (or a fresh boot) must NOT re-migrate on the next
  ;; `:after-load`: repeated register-xray-handlers! calls leave exactly one
  ;; ownership listener and never re-replace the bridge in a flood.
  (boot-xray!)
  (is (some? (viewcell-evidence/acquire!)) "Xray owns a fresh span")
  (let [rev-after-acquire (viewcell-evidence/ownership-revision)]
    ;; Re-run register twice — the shadow `:after-load` shape on a
    ;; current-schema process. The umbrella no-ops AND the migration seam
    ;; no-ops (already at schema-version), so neither the ledger nor the
    ;; listener is touched: the revision does not move.
    (registry/register-xray-handlers!)
    (registry/register-xray-handlers!)
    (is (= rev-after-acquire (viewcell-evidence/ownership-revision))
        "no re-migration on a current-schema reload — the ledger is untouched")
    ;; The single wired listener drives exactly one revision bump per
    ;; ownership transition: releasing the owned span bumps once.
    (is (true? (viewcell-evidence/release-current!)))
    (is (= (inc rev-after-acquire) (viewcell-evidence/ownership-revision))
        "exactly one ownership listener — one transition, one revision bump")))
