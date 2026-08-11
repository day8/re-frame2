(ns re-frame.effect-map-shape-record-cljs-test
  "rf2-04tx — what a PRODUCTION build does, and learns, when a malformed
  effect-map envelope refuses an event.

  Two claims live here and they are different in kind.

  ## 1. The REFUSAL is posture-independent

  A foreign top-level effect key, or a non-sequential `:fx` value, aborts the
  event with NO commit — in every build. This namespace joins
  `scripts/test-core-prod-gate.sh` (that lane's roster is an EXCLUSION list, so
  a new namespace joins by default) and its abort assertions must be green
  there with `-Dre-frame.debug=false`. That is the no-build-fork witness: if
  the refusal were dev-only, dev would abort what production commits, and the
  same source would produce two different app-dbs depending on how it was
  compiled. What DOES elide in production is the narration — axis 2, the dev
  trace — and nothing else.

  ## 2. The always-on RECORD is an egress surface

  `router/emit-effect-map-shape!` fans through `error-emit/emit-error-both!`,
  and axis 1 of that helper (`dispatch-on-error!`) is NOT gated on
  `interop/debug-enabled?`, so the record really does reach an off-box shipper
  (Sentry / Datadog) from an `:advanced` + `goog.DEBUG=false` build. Its key
  set is therefore pinned, not sampled: a slot added later ships whether or not
  anyone reviewed it, and the assertion below is that review.

  ## Why the KEY ships where the VALUE does not

  `:offending-key` is PROGRAM STRUCTURE — a keyword the programmer typed in
  their own source, carrying \"which key\" and nothing more. It is NOT
  framework-owned here (that is the difference from the sibling
  `:rf.error/classification-effect-shape`, whose four-keyword domain is closed
  by construction): an app can spell it `:acme.billing/charge-card`. The
  precedent for shipping an app-authored id is `:rf.error/override-fallthrough`,
  which egresses fx-ids for the same reason — without it a production build
  hears only that SOME effect key aborted an event, with no route to the cause,
  which is precisely the blindness this category was promoted to end.

  `:value` is the REJECTED PAYLOAD the handler built, so on any path fed from a
  system boundary it is attacker-controlled or user-private. It is omitted
  OUTRIGHT rather than scrubbed. `:reason` goes with it: the carrier builds that
  sentence by interpolating the key and event id, and it is the slot
  `emit-error-both!`'s component-attribution lift would drag onto the record if
  `:failing-id` ever diverged from `:event-id` (rf2-eg61l). It does not diverge
  here, and `the-record-carries-no-payload-derived-value` is what keeps that
  true.

  Deliberately NOT used: `with-redefs` on `interop/debug-enabled?` — the flag is
  read once at namespace-load time and a rebind cannot reach it (rf2-f7qj4).

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
  "Every slot `:rf.error/effect-map-shape` puts on the ALWAYS-ON record —
  pinned rather than sampled, because widening it is an egress decision and
  this assertion is where that decision gets made.

  The observability spine `dispatch-on-error!` builds (`:event` — wire-elided
  there, `:event-id`, `:frame`, `:time`, `:exception` (nil: an in-band refusal
  is not a throw), `:elapsed-ms`, `:source-coord` from the always-on parallel
  coord registry), plus `:error` and the one lifted attribution slot
  `:offending-key`."
  #{:error :event :event-id :frame :time :exception :elapsed-ms :source-coord
    :offending-key})

(def ^:private payload-bearing-keys
  "Slots the DEV TRACE carries that the always-on record must NEVER.

  `:value` is the rejected effect payload verbatim; `:reason` is prose that
  interpolates the offending key into a sentence; `:rf.event/v` is the whole
  event vector unelided. All three stay on the axis that DCEs."
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
  (filterv #(= :rf.error/effect-map-shape (:error %)) records))

(defn- refuse!
  "Seed `:n` to 1, then dispatch an event whose returned effect map carries
  `extra` alongside a `:db` write. Returns the always-on records the refusal
  fanned. Asserts the abort actually happened (no `:db` commit), so a green
  key-set assertion can never be green over a record that was never produced —
  and so this doubles as the posture-independent no-partial-commit witness."
  [extra ev-id]
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
  (rf/dispatch-sync [:seed])
  (rf/reg-event ev-id
    (fn [{:keys [db]} _] (merge {:db (assoc db :n 2)} extra)))
  (let [records (record-always-on-errors #(rf/dispatch-sync [ev-id]))]
    (is (= 1 (:n (frame/frame-app-db-value :rf/default)))
        "precondition: the event aborted pre-commit (no :db write landed)")
    (shape-records records)))

;; ===========================================================================
;; 1. The refusal is real, in every build, by both routes into the boundary.
;; ===========================================================================

(deftest a-foreign-top-level-key-refuses-the-event-in-every-build
  (testing "case (a) — a foreign top-level key aborts pre-commit and says so"
    (let [recs (refuse! {:acme.billing/charge-card {:cents 100}} :bad/foreign)]
      (is (= 1 (count recs))
          "exactly ONE always-on record — one refusal, one report")
      (is (= :acme.billing/charge-card (:offending-key (first recs)))
          "and it names the offending key, which is the only fact that routes
           an off-box error back to the source line")))
  (testing "case (b) — a non-sequential :fx value is the same refusal"
    (let [recs (refuse! {:fx :oops} :bad/fx-value)]
      (is (= 1 (count recs)))
      (is (= :fx (:offending-key (first recs)))))))

(deftest the-refusal-does-not-halt-the-drain
  (testing "the abort is IN-BAND, not a throw — a throw at this boundary would
            escape into drain-emergency-release! and abandon the rest of the
            queue, so the next event proves it did not"
    (rf/reg-event :bad/refused (fn [_ _] {:db {:n 2} :legacy/dispatch [:x]}))
    (rf/reg-event :good/after (fn [{:keys [db]} _] {:db (assoc db :after true)}))
    (rf/dispatch-sync [:bad/refused])
    (rf/dispatch-sync [:good/after])
    (is (true? (:after (frame/frame-app-db-value :rf/default)))
        "the downstream event still ran")))

(deftest the-refused-event-settles-error-on-the-events-stream
  (testing "a refused dispatch must NOT be reported as a clean :ok — the
            always-on :events record is what an operator counts, and a
            silently-:ok refusal is the fail-open shape this bead exists to end"
    (let [seen (atom [])]
      (rf/register-listener! :events ::outcome (fn [r] (swap! seen conj r)))
      (rf/reg-event :bad/outcome (fn [_ _] {:db {:n 2} :legacy/dispatch [:x]}))
      (rf/dispatch-sync [:bad/outcome])
      (rf/unregister-listener! :events ::outcome)
      (is (= [:error] (mapv :outcome @seen))
          "the dispatch settles :error"))))

;; ===========================================================================
;; 2. The record's key set is CLOSED.
;; ===========================================================================

(deftest the-always-on-record-key-set-is-closed
  (testing "every slot on this record reaches an off-box shipper. Adding one is
            an egress decision; this assertion is where it gets reviewed. Read
            `router/emit-effect-map-shape!`'s §Egress before widening it."
    (let [rec (first (refuse! {:acme/foreign 1} :bad/closed))]
      (is (some? rec) "precondition: the refusal fanned its record")
      (is (= record-keys (set (keys rec)))
          (str "the always-on record's key set is CLOSED. Extra keys are an "
               "unreviewed egress widening; a MISSING `:offending-key` is the "
               "blindness the promotion was for.")))))

(deftest the-record-carries-no-payload-derived-value
  (testing "the key is program structure; the VALUE the handler built is not.
            A refused payload is handler-authored and, on any path fed from a
            system boundary, attacker-controlled or user-private."
    (let [secret "sentinel-secret-value"
          rec    (first (refuse! {:acme/foreign {:token secret}} :bad/carrier))]
      (is (some? rec) "precondition: the refusal fanned its record")
      (is (empty? (set/intersection payload-bearing-keys (set (keys rec))))
          "no payload-bearing slot is present")
      (is (not (str/includes? (pr-str rec) secret))
          "and the payload's content does not reach the record by ANY route —
           not under :value, not interpolated into a :reason"))))

;; ===========================================================================
;; 3. Case (c) rides the same channel — and keeps its own recovery.
;; ===========================================================================

(deftest a-malformed-fx-entry-fans-the-same-always-on-category
  (testing "rf2-04tx promoted the WHOLE category's channel, not just the
            envelope arm: a malformed ENTRY inside a well-shaped :fx vector is
            reported on the always-on axis too, so a production build hears
            about a dropped fx row."
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (rf/dispatch-sync [:seed])
    (let [ran  (atom [])
          _    (rf/reg-fx :entry/good (fn [_ args] (swap! ran conj args)))
          _    (rf/reg-event :bad/entry
                 (fn [{:keys [db]} _]
                   {:db (assoc db :n 2)
                    :fx [[:entry/good :first] :oops [:entry/good :second]]}))
          recs (shape-records
                 (record-always-on-errors #(rf/dispatch-sync [:bad/entry])))]
      (is (= 1 (count recs))
          "the malformed entry fans exactly one always-on record")
      (is (= :fx (:offending-key (first recs)))
          "reported under :fx, the slot the entry sits in")
      ;; THE CONTROL. Case (c) is post-commit on the best-effort do-fx plane,
      ;; so it must still RECOVER where the envelope cases refuse. If the
      ;; refusal ever swallowed this distinction, both assertions below flip.
      (is (= 2 (:n (frame/frame-app-db-value :rf/default)))
          "the :db DID commit — a bad :fx ENTRY is not an envelope violation")
      (is (= [:first :second] @ran)
          "and BOTH sibling entries still ran — per-entry skip, not an abort"))))
