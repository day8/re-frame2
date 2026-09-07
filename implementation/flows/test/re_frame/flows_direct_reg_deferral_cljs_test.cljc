(ns re-frame.flows-direct-reg-deferral-cljs-test
  "Cross-host coverage for Spec 013 §Why a direct `reg-flow` does not settle.

  This file pins an ASYMMETRY, and it exists because that asymmetry looks like
  an oversight and has already been mistaken for one (rf2-f3yl, filed against
  the fixed rf2-g1zb). The plain function call `(rf/clear :flow id)` settles
  before it returns; the plain `reg-flow` deliberately does NOT. The tests
  below fix the deferral as intended behaviour and, more importantly, make its
  REASON executable — so an attempt to \"restore symmetry\" fails here, loudly,
  with a name that says why, rather than being discovered by the 69 errors it
  causes elsewhere in this suite.

  ## The asymmetry, and why it is principled

  `clear-flow` REMOVES a value that is already in `app-db`. What it leaves
  behind is ORPHANED: no live flow owns the slot, and no future drain has any
  reason to repair it beyond the dirty check happening to notice. Settling is
  always safe there — the dependents it recomputes are flows that were already
  running against inputs that are already present.

  `reg-flow` ADDS a flow that has NEVER run. Settling it would force a FIRST
  evaluation at a moment the caller did not choose. The direct form is
  documented for boot code, tests, and per-tenant setup — code that runs
  BEFORE `app-db` is seeded — so that first evaluation would land on absent
  inputs, and a `:derive` is written to be a pure function of its declared
  inputs, not a function that must also be total on `nil`. Registration is a
  DECLARATION; evaluation is the drain's job. That is what keeps registration
  order-independent with respect to seeding.

  The replacement case is the same rule seen from the other side, and it is
  the one most likely to be mistaken for the `clear-flow` defect: after a cold
  re-registration the slot still holds the PREVIOUS derive's value, which
  looks stale. It is not orphaned, though — a live, registered flow owns that
  slot and the next drain refreshes it. `clear-flow`'s staleness had no owner
  at all, which is precisely the difference that made it incoherent rather
  than merely late (Spec 013 §Sequencing).

  The `:rf.fx/reg-flow` route settles, and must — but NOT because a drain
  makes the hazard above impossible. It does not: a drain evaluates against
  the EVENT'S OWN pending `app-db`, which may be empty. The last two tests in
  this file are that measurement, and they are why this paragraph no longer
  reads the way it once did (rf2-f3yl post-merge audit of PR #9222). The real
  difference is RESPONSIBILITY: an effect registration is issued from inside
  an event, so that event is the place to establish the inputs, and it
  REQUESTS evaluation now — a failure there is an ordinary event failure at
  the effect's explicit settle boundary. A direct registration has no such
  event to be responsible for, so it declares and lets the next drain
  evaluate. The boundary is a property of WHEN the call runs, not of the
  operation — which is the same reasoning `clear-flow` used to reach the
  opposite answer for itself.

  This file is `*-cljs-test.cljc` so the shadow-cljs `:node-test` build
  (ns-regexp `cljs-test$`) discovers it AND the cognitect JVM runner runs it
  (the `-test` suffix), matching the sibling direct-clear witness.

  `re-frame.core/app-db-value` is a pure deref of the frame's app-db
  projection through the substrate adapter, so the observation itself cannot
  trigger a pass."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows :as rf.flows]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace :as rf.trace]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

;; ---- whole-stream trace recorder -----------------------------------------
;;
;; Same shape as the direct-clear witness: keep EVERY op-type, because the
;; claim being tested is the ABSENCE of `:rf.event` traffic, which a
;; pre-filtered recorder could not distinguish from a filter that never
;; matched.

(defn- call-with-recorder
  "Run `(f captured)` with a whole-stream trace recorder installed."
  [f]
  (let [captured (atom [])]
    (rf.trace/register-listener!
      ::direct-reg-deferral-recorder
      (fn [ev] (swap! captured conj ev)))
    (try
      (f captured)
      (finally
        (rf.trace/unregister-listener! ::direct-reg-deferral-recorder)))))

(defn- event-ops
  "Every recorded `:rf.event` op-type operation, in capture order. Non-empty
  iff an event was dispatched or a drain ran while the recorder was armed."
  [captured]
  (into [] (comp (filter #(= :rf.event (:op-type %)))
                 (map :operation))
        @captured))

;; ---------------------------------------------------------------------------
;; The deferral itself
;; ---------------------------------------------------------------------------

(deftest direct-cold-reg-flow-defers-initial-output-to-the-next-drain
  (testing "a flow registered by the plain function OUTSIDE a drain has not
            evaluated when the call returns — even though its declared inputs
            are already present — and materialises on the next drain"
    (call-with-recorder
      (fn [captured]
        (let [derives (atom 0)]
          (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
          ;; SEED FIRST. Registering against an empty db would prove nothing:
          ;; a flow whose inputs are absent legitimately produces no output, so
          ;; the observation below would look identical whether or not the
          ;; registration evaluated. `[:x]` is present and non-nil before
          ;; `reg-flow` is called, so "no output" can only mean "did not run".
          (rf/dispatch-sync [:seed])
          (is (= {:x 2} (rf/app-db-value :rf/default))
              "precondition — the input the flow declares is already seeded")

          (reset! captured [])
          ;; ---- THE WINDOW: reg-flow is the SOLE call ------------------
          (rf.flows/reg-flow :probe/a
            {:inputs [[:x]] :output-path [:a]}
            (fn [x] (swap! derives inc) x))
          (let [observed   (rf/app-db-value :rf/default)
                window-ops (event-ops captured)]
            ;; -------------------------------------------------------------

            ;; Nothing drained in the window, so the observation below is of
            ;; the registration alone. (Positive control at the end.)
            (is (= [] window-ops)
                (str "no event ran between the registration and the "
                     "observation; saw " (pr-str window-ops)))

            (is (contains? (get (rf.flows/flows-snapshot) :rf/default) :probe/a)
                "the flow IS registered — the deferral is of evaluation, not of registration")
            (is (zero? @derives)
                "the :derive has not been called: registration declares, it does not evaluate")
            (is (= {:x 2} observed)
                (str "the output slot is absent when the call returns; app-db was "
                     (pr-str observed))))

          ;; The next ordinary drain materialises it, once.
          (rf/reg-event :unrelated-no-op (fn [_ _] {}))
          (rf/dispatch-sync [:unrelated-no-op])
          (is (= {:x 2 :a 2} (rf/app-db-value :rf/default))
              "the next drain materialises the initial output")
          (is (= 1 @derives)
              "and derives exactly once — one pass, not repeated iteration")

          ;; POSITIVE CONTROL for the window assertion, sharing its exact
          ;; shape. A dead recorder reports the empty window slice that means
          ;; "nothing drained" in exactly the words a working one does.
          (is (seq (event-ops captured))
              (str "control — the recorder does capture :rf.event traffic, so "
                   "the empty window slice above is a real absence and not a "
                   "dead instrument")))))))

;; ---------------------------------------------------------------------------
;; THE REASON, made executable
;; ---------------------------------------------------------------------------

(deftest direct-cold-reg-flow-does-not-evaluate-derive-against-an-unseeded-db
  (testing "registering at boot — before app-db is seeded — does not run the
            :derive, so a derive that is undefined on absent inputs is not
            forced to be total on nil"
    ;; THIS is why the deferral exists, and the assertion a symmetry fix
    ;; breaks first. The direct form is documented for boot code, tests and
    ;; per-tenant setup, which run BEFORE the seeding events. A settle at
    ;; registration would evaluate this `:derive` against an absent `[:n]` and
    ;; throw :rf.error/flow-eval-exception out of `reg-flow` itself.
    (let [derives (atom 0)]
      (is (= :boot/doubled
             (rf.flows/reg-flow :boot/doubled
               {:inputs [[:n]] :output-path [:doubled]}
               (fn [n] (swap! derives inc) (* 2 n))))
          "registration against an unseeded app-db returns the flow-id and does not throw")
      (is (zero? @derives)
          "the :derive — which would throw on the absent input — was never called")

      ;; Seeding drains, and only now does the flow evaluate, against real
      ;; inputs, exactly as the registrant intended.
      (rf/reg-event :seed (fn [_ _] {:db {:n 21}}))
      (rf/dispatch-sync [:seed])
      (is (= {:n 21 :doubled 42} (rf/app-db-value :rf/default))
          "the first evaluation happens on the seeding drain, against present inputs")
      (is (= 1 @derives)
          "and it evaluated exactly once"))))

;; ---------------------------------------------------------------------------
;; The replacement case — stale but OWNED, which is why it may wait
;; ---------------------------------------------------------------------------

(deftest direct-reg-flow-replacement-defers-recompute-to-the-next-drain
  (testing "a cold re-registration leaves the previous derive's value in the
            slot until the next drain — stale, but owned by a live flow, which
            is the distinction from the orphaned value clear-flow must settle"
    (call-with-recorder
      (fn [captured]
        (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
        (rf.flows/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]} (fn [x] x))
        (rf/dispatch-sync [:seed])
        (is (= {:x 2 :a 2} (rf/app-db-value :rf/default))
            "precondition — the original definition materialised")

        (reset! captured [])
        ;; ---- THE WINDOW: the replacing reg-flow is the SOLE call ------
        (rf.flows/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]} (fn [x] (* 10 x)))
        (let [observed   (rf/app-db-value :rf/default)
              window-ops (event-ops captured)]
          ;; ---------------------------------------------------------------
          (is (= [] window-ops)
              (str "no event ran in the window; saw " (pr-str window-ops)))
          (is (= {:x 2 :a 2} observed)
              (str "the slot still holds the PREVIOUS derive's value when the "
                   "call returns — deferred per Spec 013 §Re-registration, not "
                   "orphaned: :probe/a is still registered and owns [:a]. "
                   "app-db was " (pr-str observed))))

        (rf/reg-event :unrelated-no-op (fn [_ _] {}))
        (rf/dispatch-sync [:unrelated-no-op])
        (is (= {:x 2 :a 20} (rf/app-db-value :rf/default))
            "the next drain re-evaluates with the new derive, regardless of input change")

        (is (seq (event-ops captured))
            (str "control — the recorder does capture :rf.event traffic, so the "
                 "empty window slice above is a real absence"))))))

;; ---------------------------------------------------------------------------
;; The caveat on the effect route — what the asymmetry does NOT rest on
;; ---------------------------------------------------------------------------

(deftest effect-reg-flow-evaluates-immediately-even-against-an-unseeded-db
  (testing "an event whose only effect registers a flow settles it on that
            event, against whatever app-db that event leaves — so an effect
            registration whose declared inputs are not yet present evaluates
            the :derive on their absence and can fail normally"
    ;; This is a CAVEAT on the section above, and it is here because the
    ;; rationale it guards was once written the other way round. An earlier
    ;; revision of Spec 013 §Why a direct `reg-flow` does not settle explained
    ;; the effect route's safety by saying it runs inside a drain, "where
    ;; app-db is by construction the application's live seeded state, so the
    ;; hazard cannot arise". A drain guarantees no such thing: it is simply
    ;; the event's own pending db, which may be empty, and this test is that
    ;; sentence refuted (rf2-f3yl post-merge audit of PR #9222).
    ;;
    ;; Nothing about the ASYMMETRY changes. The real distinction is one of
    ;; timing that the caller controls: an effect registration REQUESTS
    ;; immediate evaluation, and its event is responsible for establishing or
    ;; preceding the inputs — a failure there is an ordinary event failure,
    ;; reported at the effect's explicit settle boundary. A direct
    ;; registration deliberately waits for the caller's next drain, because
    ;; boot code has no such event to be responsible for.
    (let [derives (atom [])
          errors  (atom [])]
      (rf/register-listener! :errors ::effect-reg-error-recorder
                             (fn [record] (swap! errors conj record)))
      ;; The `:derive` is written the way a real one is: a pure function of an
      ;; input it expects to be there. It is spelled with an explicit refusal
      ;; rather than left to arithmetic on nil, because that is the only form
      ;; that fails identically on both hosts — `(* 2 nil)` throws on the JVM
      ;; but evaluates to 0 in CLJS, which would make this witness green on
      ;; one host for the wrong reason.
      (rf/reg-event :register-only
        (fn [_ _]
          {:fx [[:rf.fx/reg-flow
                 [:audit/double
                  {:inputs [[:n]] :output-path [:out]}
                  (fn [n]
                    (swap! derives conj n)
                    (if (nil? n)
                      (throw (ex-info "derive is not total on an absent input"
                                      {:input n}))
                      (* 2 n)))]]]}))
      (is (= {} (rf/app-db-value :rf/default))
          "precondition — app-db is empty; the event below seeds nothing")

      (rf/dispatch-sync [:register-only])

      (is (= [nil] @derives)
          "the effect route DID evaluate, exactly once, and against the absent
           input — the drain offered no seeded state to protect it")
      (is (= {} (rf/app-db-value :rf/default))
          "the failing settle installed nothing")
      (is (contains? (get (rf.flows/flows-snapshot) :rf/default) :audit/double)
          "the registration itself stands — it is the settle that failed, and
           the flow will re-attempt on the next drain")

      (let [record (first (filterv #(= :rf.error/flow-eval-exception (:error %))
                                   @errors))]
        (is (some? record)
            "the failure surfaces as the ordinary flow-eval error, on the
             always-on axis — an ordinary event failure, not a new category")
        (is (= :audit/double (:flow-id record))
            "attributed to the flow whose derive refused")
        (is (= :derive (:phase record))
            "and to the authored callback, which is where the reader should
             look")))))

(deftest effect-reg-flow-succeeds-when-its-own-event-seeds-first
  (testing "the same effect registration on an event that establishes the
            inputs materialises the initial output on that event — which is
            what makes the caveat above a caveat rather than a defect"
    ;; The other half of the caveat, and the reason it implies no lifecycle
    ;; change: the effect route's immediacy is the feature. An event that
    ;; seeds and registers in one go gets the initial output committed by the
    ;; time the dispatch returns, exactly as Spec 013 §Sequencing requires.
    (rf/reg-event :seed-and-register
      (fn [_ _]
        {:db {:n 21}
         :fx [[:rf.fx/reg-flow
               [:audit/double
                {:inputs [[:n]] :output-path [:out]}
                (fn [n]
                  (if (nil? n)
                    (throw (ex-info "derive is not total on an absent input"
                                    {:input n}))
                    (* 2 n)))]]]}))
    (rf/dispatch-sync [:seed-and-register])
    (is (= {:n 21 :out 42} (rf/app-db-value :rf/default))
        "the initial output is materialised by the time the dispatching event
         settles — the caller establishes the inputs, and the effect route
         evaluates against them")))
