(ns re-frame.classification-effect-shape-record-cljs-test
  "rf2-eg61l — what a PRODUCTION build learns when a malformed commit-plane
  classification effect aborts an event.

  `router/emit-classification-effect-shape!` fans the EP-0025 rejection through
  `error-emit/emit-error-both!`, and axis 1 of that helper —
  `dispatch-on-error!` — is NOT gated on `interop/debug-enabled?`. So the record
  really does reach an off-box shipper (Sentry / Datadog) from a `:advanced` +
  `goog.DEBUG=false` build. Until this bead it said only that SOME
  classification payload was malformed: `:offending-key` — the one slot
  rf2-mz582u added so a malformed `:clear-large` could be told from a malformed
  `:sensitive` — rode the DCE'd dev trace alone, so the operator holding the
  error had no route to the cause.

  ## What this namespace pins

    1. The record NAMES THE KEY, for each of the four independently-malformable
       axes (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`).
    2. The key set is CLOSED. This record egresses; a slot added later reaches a
       shipper whether or not anyone reviewed it, and that assertion is the
       review.
    3. The record carries NO payload-derived value — not the rejected payload
       under `:value`, not the prose `:reason` that interpolates it, and not the
       payload's content by any other route.

  ## Why the KEY is safe to ship where the VALUE is not

  `:offending-key` is PROGRAM STRUCTURE with a CLOSED domain.
  `elision/classification-effect-defect` stamps it by iterating
  `elision/classification-effect-keys`, so it is always one of four
  framework-owned keywords. It is not application-authored, not lifted out of
  the payload, and cannot be widened by a caller — it carries \"which of four
  axes\", nothing more. `the-offending-key-domain-is-the-closed-framework-set`
  pins that domain so the argument stays true.

  `:value` is the rejected payload itself — handler- or `:after`-interceptor-
  authored, and by definition not what the framework expected — so it is
  attacker-controlled or user-private on any path fed from a boundary. It is
  omitted OUTRIGHT rather than scrubbed, the same discipline
  `re-frame.always-on-validation-production-test` applies to the at-boundary
  record. `:reason` goes with it: `classification-effect-defect` builds that
  sentence by `pr-str`-ing the payload into it, so shipping the prose ships the
  value by the back door.

  ## Posture

  Every assertion here is POSTURE-INDEPENDENT and reads the ALWAYS-ON `:errors`
  registry, never the dev `:trace` stream. That is the whole subject: the claim
  is about what survives `-Dre-frame.debug=false`, so the namespace joins
  `scripts/test-core-prod-gate.sh` (that lane's roster is an EXCLUSION list — a
  new namespace joins by default) and must be green there. Deliberately NOT
  used: `with-redefs` on `interop/debug-enabled?` — the flag is read once at
  namespace-load time and a rebind cannot reach it (rf2-f7qj4).

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up.
  Plain CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.set :as set]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; The CLOSED key set of the always-on record.
;; ---------------------------------------------------------------------------

(def ^:private record-keys
  "Every slot `:rf.error/classification-effect-shape` puts on the ALWAYS-ON
  record — pinned rather than sampled, because widening it is an egress
  decision and this assertion is where that decision gets made.

  `:error` / `:recovery`-free framework fields plus the observability spine
  `dispatch-on-error!` builds (`:event` — wire-elided there, `:event-id`,
  `:frame`, `:time`, `:exception` (nil: an in-band rejection is not a throw),
  `:elapsed-ms`, `:source-coord` from the always-on parallel coord registry),
  plus the one lifted attribution slot `:offending-key` (rf2-eg61l)."
  #{:error :event :event-id :frame :time :exception :elapsed-ms :source-coord
    :offending-key})

(def ^:private payload-bearing-keys
  "Slots the DEV TRACE carries that the always-on record must NEVER.

  `:value` is the rejected classification payload verbatim; `:reason` is prose
  that `pr-str`s it into a sentence; `:rf.event/v` is the whole event vector
  unelided. All three stay on the axis that DCEs."
  #{:value :reason :rf.event/v :rf.trace/event-id})

;; ---------------------------------------------------------------------------
;; Helpers — read the ALWAYS-ON axis, which is what a production build has.
;; ---------------------------------------------------------------------------

(defn- record-always-on-errors
  "Capture through the always-on `:errors` registry — NOT `register-listener!
  :trace`, which is dev-only and sees nothing under the production gate. These
  records are what an off-box shipper receives from a production build."
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :errors ::rec (fn [r] (swap! seen conj r)))
    (try (body-fn)
         (finally (rf/unregister-listener! :errors ::rec)))
    @seen))

(defn- shape-records [records]
  (filterv #(= :rf.error/classification-effect-shape (:error %)) records))

(defn- reject!
  "Seed `:n` to 1, then dispatch an event whose ONLY classification key is
  `effect-key` carrying the malformed `payload`. Returns the always-on records
  the rejection fanned. Asserts the abort actually happened (no `:db` commit),
  so a green key-set assertion can never be green over a record that was never
  produced."
  [effect-key payload ev-id]
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
  (rf/dispatch-sync [:seed])
  (rf/reg-event ev-id
    (fn [{:keys [db]} _]
      {:db (assoc db :n 2) effect-key payload}))
  (let [records (record-always-on-errors #(rf/dispatch-sync [ev-id]))]
    (is (= 1 (:n (frame/frame-app-db-value :rf/default)))
        "precondition: the event aborted pre-commit (no :db write landed)")
    (shape-records records)))

;; ===========================================================================
;; 1. The record NAMES THE KEY — on the axis that survives production.
;; ===========================================================================

(deftest the-always-on-record-names-the-offending-key
  (testing "rf2-eg61l — a malformed classification effect fans exactly ONE
            always-on record, and that record says WHICH of the four axes was
            at fault. Before this the operator learned only that an event had
            been aborted for a classification defect: `:offending-key` is the
            sole discriminator between the four (rf2-mz582u) and it rode the
            DCE'd dev trace alone."
    (doseq [[effect-key payload ev-id]
            [[:sensitive       :not-a-vector       :bad/sensitive]
             [:large           :not-a-vector       :bad/large]
             [:clear-sensitive :not-a-vector       :bad/clear-sensitive]
             [:clear-large     :not-a-vector       :bad/clear-large]]]
      (let [records (reject! effect-key payload ev-id)
            rec     (first records)]
        (is (= 1 (count records))
            (str "exactly ONE always-on record for a malformed " effect-key))
        (is (= effect-key (:offending-key rec))
            (str "the always-on record names " effect-key " as the offending "
                 "key. Red here means production is back to knowing only THAT "
                 "a classification effect was malformed."))
        (is (= ev-id (:event-id rec))
            "and attributes it to the dispatch, so a shipper can count per-event")
        (is (= :rf/default (:frame rec))
            "and to the owning frame")))))

(deftest a-malformed-path-entry-names-its-key-too
  (testing "rf2-eg61l — the OTHER defect arm. A payload that IS a vector but
            whose entry is not a path vector (or carries a non-EDN-identity
            segment, which `path/normalize-concrete` throws on and
            `classification-effect-defect` re-reports as the same defect) also
            reaches the always-on record with its key named — the two arms of
            `classification-effect-defect` must not disagree about attribution."
    (let [rec (first (reject! :large [:not-a-path-vector] :bad/large-entry))]
      (is (= :large (:offending-key rec))
          "the bad-path-entry arm names its key on the always-on record too"))
    (let [rec (first (reject! :sensitive [[(fn [] :nope)]] :bad/segment))]
      (is (= :sensitive (:offending-key rec))
          "and so does the caught `:rf.error/bad-path` arm"))))

;; ===========================================================================
;; 2. The KEY SET is CLOSED.
;; ===========================================================================

(deftest the-always-on-record-key-set-is-closed
  (testing "rf2-eg61l — whatever this record carries ships to Sentry / Datadog.
            The key set is pinned CLOSED, not sampled: a slot added later
            reaches a shipper whether or not anyone reviewed it, and this
            assertion is the review. Widening it is an EGRESS decision — read
            `re-frame.router/emit-classification-effect-shape!`'s §Why the KEY
            egresses and the VALUE does not first."
    (let [rec (first (reject! :sensitive :not-a-vector :bad/closed))]
      (is (some? rec) "precondition: the rejection fanned its record")
      (is (= record-keys (set (keys rec)))
          (str "the always-on record's key set is CLOSED. Extra keys are an "
               "unreviewed egress widening; a MISSING `:offending-key` is "
               "rf2-eg61l reopening.")))))

(deftest the-offending-key-domain-is-the-closed-framework-set
  (testing "rf2-eg61l — the safety argument for shipping this slot depends on
            its DOMAIN being closed. `classification-effect-defect` stamps
            `:offending-key` by iterating its own private four-key literal, so
            every value the slot can take is a framework-owned keyword: never
            application-authored, never lifted out of the payload. Pinned
            STRUCTURALLY (drive the surface) rather than by reading that private
            def — an app key that reached the slot would be an unbounded egress
            widening, and the assertion below is what catches it."
    (testing "the framework key — and only a framework key — is ever named"
      (is (= :sensitive (:offending-key (first (reject! :sensitive :not-a-vector
                                                        :bad/framework-key))))
          "a malformed classification payload names its own axis"))
    (testing "an APPLICATION-authored effect key never becomes the offending key"
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
      (rf/dispatch-sync [:seed])
      ;; Both keys carry a malformed payload; only ONE of them is a
      ;; classification effect. An `:offending-key` naming the app key would
      ;; mean the domain had opened up to caller-authored keywords.
      (rf/reg-event :bad/mixed
        (fn [{:keys [db]} _]
          {:db                        (assoc db :n 2)
           :sensitive                 :not-a-vector
           :acme.billing/card-numbers :also-not-a-vector}))
      (let [records (record-always-on-errors #(rf/dispatch-sync [:bad/mixed]))]
        ;; Since rf2-04tx the two keys cannot even reach the classification
        ;; check together: the ENVELOPE is validated first, and a foreign
        ;; top-level key refuses the whole event. So the app key is not merely
        ;; ineligible for this slot — it never gets as far as the category.
        (is (empty? (shape-records records))
            "the classification category does not fire at all — the envelope
             refusal (:rf.error/effect-map-shape) came first")
        (is (= [:acme.billing/card-numbers]
               (mapv :offending-key
                     (filterv #(= :rf.error/effect-map-shape (:error %)) records)))
            "the app key is refused under its OWN category, whose
             `:offending-key` domain is app-authored by design — so the
             classification record's framework-owned domain stays closed")
        (is (not (str/includes? (pr-str (shape-records records)) "acme.billing"))
            "and the caller's key appears nowhere on this category at all")))
    (testing "a malformed value under a NON-classification key raises no
              classification-effect-shape record at all"
      (rf/reg-event :bad/app-only
        (fn [_ _] {:acme.billing/card-numbers :not-a-vector}))
      (let [recs (->> (record-always-on-errors #(rf/dispatch-sync [:bad/app-only]))
                      shape-records)]
        (is (empty? recs)
            "the four-key set is the whole surface — an unknown effect key is
             not a classification effect and cannot reach this category")))))

;; ===========================================================================
;; 3. The record carries NO payload-derived value.
;; ===========================================================================

(deftest the-always-on-record-carries-no-payload-derived-value
  (testing "rf2-eg61l — the key is program structure; the VALUE is not. A
            rejected classification payload is handler- or `:after`-interceptor-
            authored and, on any path fed from a system boundary,
            attacker-controlled or user-private. It is omitted OUTRIGHT rather
            than scrubbed — no redactor can be trusted to have seen a key the
            framework never named."
    (let [secret   "sentinel-secret-value"
          rec      (first (reject! :sensitive [secret] :bad/secret-carrier))]
      (is (some? rec) "precondition: the rejection fanned its record")
      (is (empty? (set/intersection payload-bearing-keys (set (keys rec))))
          (str "no payload-bearing slot rides the always-on record — `:value`, "
               "the interpolating `:reason` and the raw `:rf.event/v` are "
               "DEV-TRACE ONLY."))
      (is (not (str/includes? (pr-str rec) secret))
          (str "and the rejected payload's own content appears NOWHERE in the "
               "record, by any route — not stringified into a `:reason` "
               "sentence, not smuggled through an identifier.")))))

(deftest the-reason-lift-stays-shut-for-this-category
  (testing "rf2-eg61l — `emit-error-both!` lifts `:reason` out of the dev-trace
            tags ONLY alongside a `:failing-id` that differs from `:event-id`.
            This category deliberately passes NO `:failing-id`, so that shared
            rule stays shut here — which matters, because this category's
            `:reason` is prose built by `pr-str`-ing the rejected payload into
            it. Adding a `:failing-id` to the tags would ship the value.

            Guarding the mechanism rather than the outcome: the assertion above
            would still pass if the lift fired with a `:reason` that happened
            not to quote THIS payload."
    (let [rec (first (reject! :clear-sensitive :not-a-vector :bad/no-lift))]
      (is (not (contains? rec :reason))
          "no `:reason` on the always-on record")
      (is (not (contains? rec :failing-id))
          "and no `:failing-id`, which is what keeps the shared lift shut"))))
