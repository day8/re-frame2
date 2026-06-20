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

  JVM-only (`.clj`): it requires the epoch + machines artefacts and uses
  `frame-classification/install!` to declare the frame's EP-0015 §8 durable
  `:large` path — the same setup the epoch artefact's own privacy suite uses.
  Lives in core's test tree because the surface under test is the CORE facade
  wrapper, not the artefact internals."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.frame-classification :as frame-class]
            [re-frame.projection :as projection]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; Side-effect requires: loading the epoch artefact publishes the
            ;; `:epoch/*` late-bind hooks the core wrappers delegate to (without
            ;; them `rf/epoch-history` degrades to []); machines mirrors the
            ;; epoch privacy suite's load shape.
            [re-frame.epoch]
            [re-frame.machines]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

(defn- install-large-path! [frame-id]
  (frame-class/install! frame-id
    (frame-class/validate+extract frame-id
      {:large {:app-db [[:blob :payload]]}}))
  nil)

(defn- big-string [n] (apply str (repeat n "X")))

(defn- large-marker-body
  "The `:rf.size/large-elided` marker body at `[:db-after :blob :payload]` of a
  projected record, or nil if the slot is not a marker."
  [record]
  (let [slot (get-in record [:db-after :blob :payload])]
    (when (elision/marker? slot)
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
    (rf/reg-frame :ep/main {})
    (install-large-path! :ep/main)
    (rf/reg-event :store
                  (fn [{:keys [db]} [_ payload]]
                    {:db (assoc-in db [:blob :payload] payload)}))
    (rf/dispatch-sync [:store (big-string 50000)] {:frame :ep/main})

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
          "tool boundary == observability marker PLUS the structural digest"))))

(deftest core-projected-record-rejects-unknown-profile
  (testing "rf2-ylvp4m — an unknown :rf.egress/profile through the core wrapper
            is rejected against the shared closed enum (a typo is a loud error,
            never a silent permissive walk)."
    (rf/reg-frame :ep/main {})
    (rf/reg-event :store (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
    (rf/dispatch-sync [:store 1] {:frame :ep/main})
    (let [raw  (last-record :ep/main)
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
      (is (error/message-has-id-token? msg)
          "the message carries the trailing greppability token (rule 4)")
      (is (not (error/keyword-only-message? msg))
          "the message is a human sentence, not a bare keyword (rule 1)")
      (is (= 'epoch/projected-record (:where data))
          ":where names the epoch boundary helper")
      (is (= :use-a-known-profile (:recovery data)))
      ;; The epoch site's thrown shape is IDENTICAL (but for :where) to the
      ;; shared builder's — proving the dedup: one reason, two call sites.
      (let [canonical (projection/unknown-egress-profile-ex
                        'epoch/projected-record :rf.egress/not-real)]
        (is (= (ex-message canonical) msg)
            "the epoch throw's message == the shared builder's")
        (is (= (ex-data canonical) data)
            "the epoch throw's ex-data == the shared builder's")))))

(deftest core-projected-history-threads-egress-profile
  (testing "rf2-ylvp4m — `rf/projected-history` threads the named
            :rf.egress/profile boundary to every record (the whole-ring
            convenience over `projected-record`)."
    (rf/reg-frame :ep/main {})
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
          "the default observability history omits the :digest on every record"))))
