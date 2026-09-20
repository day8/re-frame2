(ns day8.re-frame2-xray.panels.machine-snapshots-frame-scope-cljs-test
  "The `:rf.xray/machine-snapshots` sub must classify a live snapshot against
  the frame WHOSE DATA IT IS (rf2-6ev6j).

  ## The defect these rows pin

  The sub took TWO INPUTS FROM DIFFERENT FRAME SLOTS:

      {:inputs [[:rf.xray/target-frame] [:rf.xray/target-frame-runtime-db]]}

  The CLASSIFICATION frame came from `:rf.xray/target-frame` — the collector
  target, which `defaults/default-target-frame` leaves `nil` until something
  selects it (EP-0002). The DATA came from `:rf.xray/target-frame-runtime-db`,
  which pivots on `:rf.xray/observed-frame` — `(or (:frame focus) target)`, the
  FOCUS-selected frame first. Nothing kept the two equal.

  In the posture the panel OPENS in, the composed focus resolves a real host
  frame (`compose-focus` takes `:frame` from the head event-bundle's own record
  in LIVE mode) while the picker is untouched, so `:target-frame` is still nil.
  `frame-snapshot-classification` returns nil for a nil frame, and with no
  author classification `project-machine-tags` returns the tags UNCHANGED — so a
  `:data` path the frame had EXPLICITLY DECLARED sensitive was surfaced RAW.

  That is a missed EXPLICIT declaration, not a blanket security-boundary claim,
  and nothing here scrubs anything the author did not declare —
  `leaves-an-undeclared-frames-snapshot-untouched…` is the row that pins that.

  ## Why these rows drive the real sub

  The pre-existing coverage
  (`machine_inspector_view_cljs_test/live-snapshots-sub-redacts-sensitive-data-rf2-kq8nac`)
  calls `#'machine-inspector/redact-live-snapshots` DIRECTLY with a
  hand-supplied frame-id. That fn was always correct — it redacts against
  whatever frame it is handed. The defect lived one level up, in WHICH frame
  the sub handed it, so a helper-level row cannot see it and the chain was
  never exercised end to end. Every row here reads
  `@(rf/subscribe [:rf.xray/machine-snapshots])` so the whole production chain
  (`:rf.xray/focus` -> `:rf.xray/observed-frame` ->
  `:rf.xray/target-frame-runtime-db` -> the sub) runs for real.

  `install-test-overrides!` does NOT re-register `:rf.xray/machine-snapshots`
  (it adds `:rf.xray/machine-snapshots-override` and re-registers only the
  COMPOSITE), so the sub read here is the production registration.

  ## Why the classification is real

  The frame's elision registry is written through
  `rf.elision/swap-elision-slot!` + `add-claims` — the substrate the EP-0025
  commit-plane `:sensitive` effect writes through. A hand-redacted snapshot
  injected through the override seam would carry no registry at all and would
  pass against the unfixed sub."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

;; ---- fixture values ------------------------------------------------------

(def ^:private host-b
  "The OBSERVED frame — the one the composed focus resolves, and the one whose
  runtime-db actually supplies the snapshot."
  :rf2-6ev6j/host-b)

(def ^:private host-a
  "A DIFFERENT host frame, used as the collector target in the differing-frame
  rows. Its declarations govern ITS data, never host-b's."
  :rf2-6ev6j/host-a)

(def ^:private machine-id :rf2-6ev6j/auth)

(def ^:private secret "secret-6ev6j-live-token")
(def ^:private sibling "retries-6ev6j-plain")

(def ^:private snapshot
  {:state :authed
   :data  {:secret secret
           :plain  sibling}})

;; ---- host-side seeding ---------------------------------------------------

(defn- seed-host-runtime-db!
  "Install a live machine snapshot into `frame-id`'s RUNTIME-DB partition
  (EP-0001) — the raw `[:rf.runtime/machines :snapshots]` slot
  `machine-snapshots-value` reads. A framework-authority `reg-event` returning
  the reserved `:rf.db/runtime` effect is the same path the machines
  lifecycle-fx write through; `:rf/machine? true` marks it framework-authority
  so the runtime-write diagnostic stays quiet.

  Seeded BEFORE any classification is declared: this REPLACES the partition, so
  declaring first would clobber `[:rf.runtime/elision]`."
  [frame-id]
  (rf/make-frame {:id frame-id})
  (rf/reg-event :rf2-6ev6j/seed-runtime-db
    {:rf/machine? true}
    (fn [_ [_ v]] {:rf.db/runtime v}))
  (rf/with-frame frame-id
    (rf/dispatch-sync [:rf2-6ev6j/seed-runtime-db
                       {:rf.runtime/machines {:snapshots {machine-id snapshot}}}])))

(defn- declare-secret-sensitive!
  "Declare `[:data :secret]` of `machine-id`'s snapshot SENSITIVE on `frame-id`
  — the frame-owned classification (EP-0025), keyed by the ABSOLUTE runtime-db
  snapshot path `frame-snapshot-classification` re-roots against.
  `add-claims` is the multi-owner-safe spelling."
  [frame-id]
  (rf.elision/swap-elision-slot! frame-id
    (fn [reg]
      (rf.elision/add-claims (or reg {})
                             :sensitive-declarations
                             {:source :effect}
                             [[:rf.runtime/machines :snapshots machine-id
                               :data :secret]]))))

(defn- host-snapshot
  "The snapshot as it stands in `frame-id`'s own runtime-db — RAW. In-process
  reads are never redacted; redaction is an EGRESS read."
  [frame-id]
  (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
          [:rf.runtime/machines :snapshots machine-id]))

;; ---- Xray-side seeding ---------------------------------------------------

(defn- setup-xray! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  ;; A pure SEEDING event writing the REAL `:focus` slot — the same slot
  ;; `focus-event-bundle-reducer` writes `[:focus :frame]` into when the
  ;; operator clicks an L2 row, and the same shape `compose-focus` reads
  ;; (`:frame (or (:frame event-bundle) (:frame focus))`). Modelled on the
  ;; in-tree `:rf.xray/set-focus-epoch-id-for-test` seam, which writes
  ;; `[:focus :epoch-id]` exactly this way.
  ;;
  ;; This is the ONLY seam: `:target-frame` is deliberately left alone so it
  ;; reports `defaults/default-target-frame` (nil) unless a row sets it
  ;; through the production `:rf.xray/set-target-frame` event.
  (rf/reg-event :rf2-6ev6j/focus-frame-for-test
    (fn [{:keys [db]} [_ frame-id]]
      {:db (update db :focus (fnil assoc {}) :frame frame-id)})))

(defn- focus-frame! [frame-id]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf2-6ev6j/focus-frame-for-test frame-id] {:frame :rf/xray})))

(defn- select-target-frame! [frame-id]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-target-frame frame-id] {:frame :rf/xray})))

(defn- observed-frame []
  (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/observed-frame])))

(defn- target-frame []
  (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/target-frame])))

(defn- machine-snapshots
  "The PRODUCTION sub under test."
  []
  (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/machine-snapshots])))

;; ---- (0) controls: the rig is live before anything is asserted ----------

(deftest control-the-two-slots-genuinely-diverge-rf2-6ev6j
  (testing "rf2-6ev6j CONTROL — the shipping posture really does put a REAL
            frame on the observed axis while the collector target is nil. A
            redaction row below could otherwise pass for the wrong reason: if
            the two slots happened to agree, the defect could not manifest and
            a green row would say nothing."
    (seed-host-runtime-db! host-b)
    (declare-secret-sensitive! host-b)
    (setup-xray!)
    (focus-frame! host-b)
    (is (nil? (target-frame))
        "the collector target is UNSELECTED (nil) — EP-0002's default, the
         posture the panel opens in")
    (is (= host-b (observed-frame))
        "the observed frame resolves host-b off the focus slot")
    (is (not= (target-frame) (observed-frame))
        "the two axes diverge — which is the precondition this item is about")))

(deftest control-the-host-snapshot-is-raw-in-process-rf2-6ev6j
  (testing "rf2-6ev6j CONTROL — the seeded snapshot really carries the secret
            in the host frame's own runtime-db, and the declaration really
            landed. Without this a redaction assertion could pass because the
            seed was empty or the registry write no-opped (swap-elision-slot!
            is a NO-OP when the frame container does not exist)."
    (seed-host-runtime-db! host-b)
    (declare-secret-sensitive! host-b)
    (is (= secret (get-in (host-snapshot host-b) [:data :secret]))
        "the host frame's runtime-db carries the RAW secret — in-process reads
         stay raw; redaction is read only at egress")
    (is (contains? (rf.elision/sensitive-declarations host-b)
                   [:rf.runtime/machines :snapshots machine-id :data :secret])
        "the frame's sensitive declaration landed on the absolute snapshot
         path")))

;; ---- (1) PROPERTY ONE: correct classification --------------------------

(deftest redacts-against-the-observed-frame-when-the-target-is-unselected-rf2-6ev6j
  (testing "rf2-6ev6j — target nil / focus host-b. The snapshot is host-b's, so
            host-b's declaration governs it. RED against the unfixed sub, which
            classified against the nil collector target and surfaced the
            declared-sensitive slot RAW."
    (seed-host-runtime-db! host-b)
    (declare-secret-sensitive! host-b)
    (setup-xray!)
    (focus-frame! host-b)
    (let [snaps (machine-snapshots)]
      (is (some? (get snaps machine-id))
          "the sub returned the machine's snapshot at all — a nil here would
           pass the leak assertion vacuously")
      (is (= :rf/redacted (get-in snaps [machine-id :data :secret]))
          (str "the declared-sensitive slot was not redacted against the frame
                whose data it is: " (pr-str snaps)))
      (is (not (str/includes? (pr-str snaps) secret))
          (str "the declared-sensitive value LEAKED out of the sub: "
               (pr-str snaps)))
      ;; The unclassified sibling is what proves this is the FRAME's
      ;; path-precise declaration matching, and not a fail-closed whole-value
      ;; redaction that would have hidden the leak by accident.
      (is (= sibling (get-in snaps [machine-id :data :plain]))
          (str "the UNDECLARED sibling slot was scrubbed too — that is a
                blanket redaction, not the declaration: " (pr-str snaps))))))

;; ---- (2) PROPERTY TWO: no collateral change ----------------------------

(deftest leaves-an-undeclared-frames-snapshot-untouched-rf2-6ev6j
  (testing "rf2-6ev6j — a frame declaring NO matching `:data` path leaves the
            snapshot UNTOUCHED and REFERENCE-PRESERVING (the fast path inside
            `project-machine-tags` that the sub's own comment names). This is
            the invariant the fix must not disturb; it is green either side of
            the change BY DESIGN, and it is the half that says the fix did not
            buy its correctness with a blanket scrub."
    (seed-host-runtime-db! host-b)
    ;; deliberately NO declare-secret-sensitive!
    (setup-xray!)
    (focus-frame! host-b)
    (let [snaps (machine-snapshots)]
      (is (= snapshot (get snaps machine-id))
          (str "an undeclared frame's snapshot was altered: " (pr-str snaps)))
      (is (identical? snapshot (get snaps machine-id))
          "the reference-preserving fast path was lost — an undeclared
           snapshot was rebuilt rather than passed through")
      (is (= secret (get-in snaps [machine-id :data :secret]))
          "an UNDECLARED slot was withheld — over-scrub; this item is
           explicitly not a claim that undeclared carriers are a boundary"))))

;; ---- (3) the differing-frame controls ----------------------------------

(deftest redacts-against-the-observed-frame-not-the-selected-target-rf2-6ev6j
  (testing "rf2-6ev6j — collector target host-a (declaring NOTHING), focus
            host-b (declaring the slot sensitive). The data is host-b's, so
            host-b's declaration must govern. RED against the unfixed sub,
            which asked host-a and got no declaration, leaking the slot."
    (seed-host-runtime-db! host-b)
    (declare-secret-sensitive! host-b)
    (rf/make-frame {:id host-a})
    (setup-xray!)
    (select-target-frame! host-a)
    (focus-frame! host-b)
    (is (= host-a (target-frame)) "the collector target is host-a")
    (is (= host-b (observed-frame)) "the observed frame is still host-b")
    (let [snaps (machine-snapshots)]
      (is (= :rf/redacted (get-in snaps [machine-id :data :secret]))
          (str "classified against the COLLECTOR TARGET instead of the frame
                whose data it is: " (pr-str snaps)))
      (is (not (str/includes? (pr-str snaps) secret))
          (str "the declared-sensitive value leaked: " (pr-str snaps))))))

(deftest stops-redacting-against-the-collector-target-rf2-6ev6j
  (testing "rf2-6ev6j, the item's own words — collector target host-a DECLARES
            the slot sensitive, focus host-b does NOT, and the snapshot is
            host-b's. host-a's declaration governs host-a's data and nothing
            else, so the value must ride verbatim. RED against the unfixed sub,
            which applied host-a's policy to host-b's value — the borrowed-
            policy failure `panels/routing.cljs`'s own `current-route-slice`
            comment names, reached from the other direction."
    (seed-host-runtime-db! host-b)
    (seed-host-runtime-db! host-a)
    (declare-secret-sensitive! host-a)
    (setup-xray!)
    (select-target-frame! host-a)
    (focus-frame! host-b)
    (is (= host-a (target-frame)))
    (is (= host-b (observed-frame)))
    (let [snaps (machine-snapshots)]
      (is (= secret (get-in snaps [machine-id :data :secret]))
          (str "host-b's value was redacted under host-a's BORROWED policy: "
               (pr-str snaps)))
      (is (= sibling (get-in snaps [machine-id :data :plain]))
          "the plain sibling rides verbatim"))))
