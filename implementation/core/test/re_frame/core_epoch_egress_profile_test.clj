(ns re-frame.core-epoch-egress-profile-test
  "rf2-ylvp4m — the CORE epoch projection WRAPPER (`rf/projected-record` /
  `rf/projected-history`, `re-frame.core-epoch`) honors the EP-0015 §10 named
  `:rf.egress/profile` boundary selector, not just the legacy unqualified
  `:include-*` opts.

  The epoch artefact (`re-frame.epoch.tool-pair`) already implements the
  EP-0015 model (rf2-1afn7q); this suite pins it END-TO-END THROUGH THE CORE
  FACADE WRAPPER — `rf/projected-record` is the public surface consumers reach
  for, and the bead's finding was that the wrapper's documented vocabulary
  lagged the EP. The test drives the named selector through the wrapper and
  asserts the profile is honored (the off-box-tool boundary adds the structural
  `:digest`; the default observability boundary omits it), proving the wrapper
  passes `:rf.egress/profile` through rather than only the legacy booleans.

  JVM-only (`.clj`): it requires the epoch + machines artefacts and declares
  the frame's durable `:large` path via the EP-0025 commit-plane
  classification effect (`rf.elision/apply-classification-effects`, the same
  registry write a `reg-event` returning `:large` performs) — the same setup
  the epoch artefact's own privacy suite uses. Lives in core's test tree
  because the surface under test is the CORE facade wrapper, not the artefact
  internals.

  ## Posture split (rf2-d2841)

  Two halves that look like one. The epoch RING is fed from the dev trace
  stream and `epoch.capture/observe-trace-event!` opens with
  `(when rf.interop/debug-enabled? ...)`, so under
  `scripts/test-core-prod-gate.sh` `rf/epoch-history` is empty by construction.
  The PROJECTION under test is a different animal: `projected-record` is a pure
  function of a record map plus the frame's DURABLE elision registry, and both
  of those exist in production.

  Reading the profile claims off the live ring conflated the two, and the
  conflation was expensive. With the ring empty, `raw` is nil,
  `large-marker-body` is nil, and SIX assertions passed for that reason alone:
  `(= default-body obs-body)` (nil = nil), `(not (contains? obs-body :digest))`
  (nil contains nothing), `(= (dissoc tool-body :digest) obs-body)`, the raw-
  bytes-never-egress row over an empty string, `(not-any? ... obs-hist)` over an
  empty history, and the human-sentence check over a nil message. An egress-
  PRIVACY suite certifying that no raw bytes escaped, having projected nothing.

  So the profile rows now drive a SYNTHETIC record — the same shape the ring
  holds — and run in both postures. The live-ring rows are kept verbatim inside
  `(when rf.interop/debug-enabled? ...)` arms as what they always were: the CAPTURE
  half."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.projection :as rf.projection]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Side-effect requires: loading the epoch artefact publishes the
            ;; `:epoch/*` late-bind hooks the core wrappers delegate to (without
            ;; them `rf/epoch-history` degrades to []); machines mirrors the
            ;; epoch privacy suite's load shape.
            [re-frame.epoch]
            [re-frame.machines]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

(defn- install-large-path! [frame-id]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {:large [[:blob :payload]]})))
  nil)

(defn- big-string [n] (apply str (repeat n "X")))

(defn- synthetic-record
  "A hand-built epoch record of the shape the ring holds (rf2-d2841). The ring
  is fed from the dev trace stream and is empty under -Dre-frame.debug=false;
  the PROJECTION being tested is a pure function of the record plus the frame's
  durable elision registry, so driving it directly exercises the same wrapper
  code path in BOTH postures."
  [frame-id payload]
  {:frame     frame-id
   :db-before {:blob {:payload nil}}
   :db-after  {:blob {:payload payload}}})

(defn- large-marker-body
  "The `:rf.size/large-elided` marker body at `[:db-after :blob :payload]` of a
  projected record, or nil if the slot is not a marker."
  [record]
  (let [slot (get-in record [:db-after :blob :payload])]
    (when (rf.elision/marker? slot)
      (:rf.size/large-elided slot))))

;; ---------------------------------------------------------------------------
;; rf2-ylvp4m — the CORE wrapper honors :rf.egress/profile.
;; ---------------------------------------------------------------------------

(deftest core-projected-record-honors-egress-profile
  (testing "rf2-ylvp4m — `rf/projected-record` (the core facade wrapper)
            honors the named EP-0015 §10 :rf.egress/profile selector: the
            :rf.egress/off-box-tool boundary adds the structural :digest a tool
            consumer needs, while the default :rf.egress/off-box-observability
            boundary omits it. Both elide the large value (no raw bytes egress)."
    (rf/make-frame {:id :ep/main})
    (install-large-path! :ep/main)
    (rf/reg-event :store
                  (fn [{:keys [db]} [_ payload]]
                    {:db (assoc-in db [:blob :payload] payload)}))
    (rf/dispatch-sync [:store (big-string 50000)] {:frame :ep/main})

    ;; ---- ALWAYS-ON (rf2-d2841): the profile selector, driven on a record
    ;;      whose existence does not depend on the dev trace stream.
    (let [synth        (synthetic-record :ep/main (big-string 50000))
          default-body (large-marker-body (rf/projected-record synth))
          obs-body     (large-marker-body
                         (rf/projected-record
                           synth {:rf.egress/profile :rf.egress/off-box-observability}))
          tool-body    (large-marker-body
                         (rf/projected-record
                           synth {:rf.egress/profile :rf.egress/off-box-tool}))]
      (is (some? default-body) "the default boundary elides the large slot")
      (is (some? obs-body)     "the named observability boundary elides it too")
      (is (some? tool-body)    "the tool boundary elides the large slot")
      (is (not= 50000 (count (str (get-in (rf/projected-record synth)
                                          [:db-after :blob :payload]))))
          "the raw 50KB string never egresses under either off-box boundary")
      (is (= default-body obs-body)
          "the bare 1-arity default == :rf.egress/off-box-observability")
      (is (not (contains? obs-body :digest))
          ":rf.egress/off-box-observability (through the wrapper) omits :digest")
      (is (contains? tool-body :digest)
          ":rf.egress/off-box-tool (through the wrapper) includes the structural
           :digest — the named selector is honored end-to-end")
      (is (= (dissoc tool-body :digest) obs-body)
          "tool boundary == observability marker PLUS the structural digest"))

    ;; ---- rf2-d2841 dev arm: the CAPTURE half — that a real dispatch put a
    ;;      record of exactly that shape into the ring. The ring is fed from the
    ;;      dev trace stream (`epoch.capture/observe-trace-event!` is gated), so
    ;;      it is empty under -Dre-frame.debug=false.
    (when rf.interop/debug-enabled?
    (let [raw       (last-record :ep/main)
          ;; The bare 1-arity (default observability boundary), through the
          ;; CORE wrapper.
          default-body (large-marker-body (rf/projected-record raw))
          ;; The named off-box-observability boundary, explicitly.
          obs-body  (large-marker-body
                      (rf/projected-record
                        raw {:rf.egress/profile :rf.egress/off-box-observability}))
          ;; The named off-box-tool boundary — the EP-0015 selector under test.
          tool-body (large-marker-body
                      (rf/projected-record
                        raw {:rf.egress/profile :rf.egress/off-box-tool}))]
      (is (some? raw) "an epoch record was captured")
      (is (some? default-body) "the default boundary elides the large slot")
      (is (some? tool-body) "the tool boundary elides the large slot")
      (is (not= 50000 (count (str (get-in (rf/projected-record raw)
                                          [:db-after :blob :payload]))))
          "the raw 50KB string never egresses under either off-box boundary")
      ;; The bare 1-arity default == the named observability boundary.
      (is (= default-body obs-body)
          "the bare 1-arity default == :rf.egress/off-box-observability")
      ;; THE PROFILE IS HONORED THROUGH THE WRAPPER: observability omits the
      ;; structural :digest; the tool boundary includes it. Pre-finding, the
      ;; wrapper documented only the legacy :include-* booleans, masking the
      ;; named selector as a public surface.
      (is (not (contains? obs-body :digest))
          ":rf.egress/off-box-observability (through the wrapper) omits :digest")
      (is (contains? tool-body :digest)
          ":rf.egress/off-box-tool (through the wrapper) includes the structural
           :digest — the named selector is honored end-to-end")
      (is (= (dissoc tool-body :digest) obs-body)
          "tool boundary == observability marker PLUS the structural digest")))))

(deftest core-projected-record-rejects-unknown-profile
  (testing "rf2-ylvp4m — an unknown :rf.egress/profile through the core wrapper
            is rejected against the shared closed enum (a typo is a loud error,
            never a silent permissive walk)."
    (rf/make-frame {:id :ep/main})
    (rf/reg-event :store (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
    (rf/dispatch-sync [:store 1] {:frame :ep/main})
    ;; ALWAYS-ON (rf2-d2841): a closed-enum rejection is a property of the
    ;; wrapper, not of the ring. Driven on a synthetic record so a typo stays
    ;; loud in the posture that ships — reading it off the live ring meant that
    ;; under the gate `raw` was nil, `projected-record` returned nil for a
    ;; non-map, and NOTHING was rejected at all.
    (let [raw  (synthetic-record :ep/main "v")
          ex   (try (rf/projected-record raw {:rf.egress/profile :rf.egress/not-real})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))
          data (ex-data ex)
          msg  (ex-message ex)]
      (is (some? ex) "an unknown profile throws through the wrapper")
      (is (= :rf.error/unknown-egress-profile (:rf.error/id data))
          "the throw carries the closed-enum rejection id")
      ;; rf2-krrv87: the epoch-boundary guard routes through the SAME shared
      ;; `re-frame.projection/unknown-egress-profile-ex` builder as the in-file
      ;; guard, so the message carries the [:rf.error/unknown-egress-profile]
      ;; greppability token and the canonical :where / :recovery slots — only
      ;; :where differs (it names the epoch boundary helper).
      (is (rf.error/message-has-id-token? msg)
          "the message carries the trailing greppability token (rule 4)")
      (is (not (rf.error/keyword-only-message? msg))
          "the message is a human sentence, not a bare keyword (rule 1)")
      (is (= 'epoch/projected-record (:where data))
          ":where names the epoch boundary helper")
      (is (= :use-a-known-profile (:recovery data)))
      ;; The epoch site's thrown shape is IDENTICAL (but for :where) to the
      ;; shared builder's — proving the dedup: one reason, two call sites.
      (let [canonical (rf.projection/unknown-egress-profile-ex
                        'epoch/projected-record :rf.egress/not-real)]
        (is (= (ex-message canonical) msg)
            "the epoch throw's message == the shared builder's")
        (is (= (ex-data canonical) data)
            "the epoch throw's ex-data == the shared builder's")))))

(deftest core-projected-history-threads-egress-profile
  ;; rf2-d2841 — `projected-history` maps `projected-record` over the RING, so
  ;; it has nothing to thread a profile to under -Dre-frame.debug=false. There
  ;; is no synthetic stand-in: the ring is the subject. The per-record profile
  ;; threading it delegates to is covered always-on above. Kept verbatim.
  (when rf.interop/debug-enabled?
  (testing "rf2-ylvp4m — `rf/projected-history` threads the named
            :rf.egress/profile boundary to every record (the whole-ring
            convenience over `projected-record`)."
    (rf/make-frame {:id :ep/main})
    (install-large-path! :ep/main)
    (rf/reg-event :store
                  (fn [{:keys [db]} [_ payload]]
                    {:db (assoc-in db [:blob :payload] payload)}))
    (rf/dispatch-sync [:store (big-string 50000)] {:frame :ep/main})
    (let [tool-hist (rf/projected-history
                      :ep/main {:rf.egress/profile :rf.egress/off-box-tool})
          obs-hist  (rf/projected-history :ep/main)]
      (is (seq tool-hist) "projected-history returns the ring")
      (is (every? #(contains? (large-marker-body %) :digest)
                  (filter large-marker-body tool-hist))
          "every large marker in the tool-profile history carries the :digest")
      (is (not-any? #(contains? (large-marker-body %) :digest)
                    (filter large-marker-body obs-hist))
          "the default observability history omits the :digest on every record")))))
