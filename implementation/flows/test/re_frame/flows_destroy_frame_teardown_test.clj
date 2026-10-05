(ns re-frame.flows-destroy-frame-teardown-test
  "`destroy-frame!` cleans up the per-frame flow state (the per-frame `flows`
  registry entry and the frame's `last-inputs` rows). Symmetric with the
  machines `:machines/teardown-on-frame-destroy!` hook.

  The release itself (the destroyed frame's registry slot and dirty-check
  rows dropped, a sibling frame's rows kept) is pinned by the
  `flow-frame-destroy-teardown` conformance fixture, which
  `re-frame.flows-conformance-test` runs. This namespace pins what that
  fixture cannot observe: the frame-owned flow-output elision marks, and the
  refusal of a registration against a destroyed frame.

  SINGLE-STORE: the per-frame `flows` atom is the SOLE store —
  there is no frame-blind registrar `:flow` slot to prune / realign. Teardown
  is purely dropping the destroyed frame's per-frame entries; a surviving frame
  registering the same id keeps its OWN authoritative entry in place.

  Without this teardown, `flows[frame-id]` and `last-inputs[flow-id][frame-id]`
  would retain references — a memory leak class for the long-running SSR JVM
  (per-request frame churn), pair-tool time-travel, and `make-frame` ephemeral
  usage.

  These JVM-side tests run on the plain-atom substrate against the
  late-bound `:flows/teardown-on-frame-destroy!` hook the flows
  artefact publishes for `frame/destroy-frame!`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows :as rf.flows]
            ;; Loading `re-frame.flows` registers the late-bind hook
            ;; (`:flows/teardown-on-frame-destroy!`) the tests exercise —
            ;; keep the require even when the test ns doesn't reach
            ;; `flows/...` directly through a public fn.
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; ---- per-test reset ------------------------------------------------------
;;
;; The standard runtime reset (registrar baseline + frames + flows/schemas +
;; plain-atom adapter + ambient `:rf/default` scope) is owned by
;; `make-reset-runtime-fixture`; EP-0002 — `:rf/default` is bound so the
;; ambient `reg-flow` calls in the bodies below carry a frame stamp.

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- flow-output elision marks ride the frame-record drop ----------------
;;
;; `clear-flow` scrubs ONE flow's `:source :flow` elision declarations via
;; `clear-flow-output-marks!` while its frame lives on. Frame-destroy does NOT
;; call that scrub — and deliberately so: the elision registry lives in the
;; frame's runtime-db partition (`[:rf.runtime/elision]`) INSIDE the one
;; physical `:frame-state` container held under the frame record, and
;; `destroy-frame!` step 9 (`dissoc-frame!`) drops that whole record. These
;; tests pin the chosen contract: a destroyed frame cannot observe its
;; flow-sourced declarations, and a reused frame-id starts with NONE.

(deftest destroy-frame-drops-flow-output-marks
  (testing "Destroying a frame makes its flow-output elision
            marks unobservable — both :sensitive (sensitive-declarations) and
            :large (declarations) flow-sourced entries vanish with the frame
            record, with no explicit teardown scrub"
    (rf/make-frame {:id :fc/scratch :doc "scratch frame for flow-output-mark teardown"})
    (rf/reg-flow :creds {:frame :fc/scratch :inputs [[:n]] :output-path [:derived :creds] :sensitive [[:secret]]} (fn [n] {:secret n}))
    (rf/reg-flow :blob {:frame :fc/scratch :inputs [[:n]] :output-path [:derived :blob] :large? true} (fn [n] {:bytes n}))
    ;; Precondition: the flow-sourced declarations are installed in the LIVE
    ;; frame's elision registry (the same surface flows_output_marks_test
    ;; reads). The sensitive subpath roots at :output-path ++ [:secret]; the large
    ;; whole-output mark roots at :output-path.
    (is (contains? (rf.elision/sensitive-declarations :fc/scratch) [:derived :creds :secret])
        "precondition: the :sensitive flow declaration is installed")
    (is (contains? (rf.elision/declarations :fc/scratch) [:derived :blob])
        "precondition: the :large flow declaration is installed")
    (rf.frame/destroy-frame! :fc/scratch)
    ;; Post-destroy: `registry-of` reads the destroyed frame's container,
    ;; which `dissoc-frame!` removed, so both reader fns return {} — the
    ;; flow-sourced declarations are unobservable, no explicit scrub needed.
    (is (nil? (rf.frame/frame :fc/scratch))
        "the frame record is gone after destroy-frame!")
    (is (= {} (rf.elision/sensitive-declarations :fc/scratch))
        "the destroyed frame exposes no flow-sourced sensitive declarations")
    (is (= {} (rf.elision/declarations :fc/scratch))
        "the destroyed frame exposes no flow-sourced large declarations")))

(deftest make-frame-after-destroy-observes-no-stale-flow-output-marks
  (testing "Adversarial: a frame-id reused after a destroy
            that left flow-output marks behind starts with a FRESH empty
            container — the second incarnation observes NONE of the first
            incarnation's flow-sourced elision declarations. This is the
            regression guard for the teardown contract: the marks must not
            leak across a destroy→reuse cycle even though teardown runs no
            explicit elision scrub"
    ;; First incarnation: register a flow whose output is whole-sensitive.
    ;; EP-0025: classify the whole output explicitly with `:sensitive [[]]` (the
    ;; whole-value convention).
    (rf/make-frame {:id :fc/scratch :doc "first incarnation"})
    (rf/reg-flow :token {:frame :fc/scratch :inputs [[:n]] :output-path [:auth :token] :sensitive [[]]} (fn [n] {:jwt n}))
    (is (contains? (rf.elision/sensitive-declarations :fc/scratch) [:auth :token])
        "precondition: the first incarnation installed a whole-output sensitive mark")
    (rf.frame/destroy-frame! :fc/scratch)
    ;; Second incarnation under the SAME id — a brand-new frame-state container.
    (rf/make-frame {:id :fc/scratch :doc "second incarnation"})
    (is (= {} (rf.elision/sensitive-declarations :fc/scratch))
        "the reused frame inherited no sensitive flow-sourced declaration")
    (is (= {} (rf.elision/declarations :fc/scratch))
        "the reused frame inherited no large flow-sourced declaration")))

;; ---- reg-flow must not resurrect stale flows on a dead frame -------------
;;
;; `reg-flow` against an absent/destroyed frame is rejected BEFORE any state
;; mutates. `call-serialized-with-drain!` runs the registration thunk in-line
;; for a non-live frame, so without the guard the registration would install a
;; `flows` row and an elision declaration stamped with the dead frame-id — and
;; a later `make-frame` reusing that id would inherit the resurrected flow.
;; SINGLE-STORE: there is no registrar `:flow` slot to install,
;; so the `registrar/lookup :flow` checks below are nil throughout (the slot is
;; RESERVED-but-empty) — the per-frame `flows`-snapshot equality checks are the
;; load-bearing "mutated nothing" assertions.

(deftest reg-flow-against-destroyed-frame-rejects-and-mutates-nothing
  (testing "reg-flow on a DESTROYED frame throws a stable
            structured error and leaves flows / last-inputs / the :flow
            registrar untouched (no dormant state for the dead frame)"
    (rf/make-frame {:id :fc/scratch :doc "scratch frame, then destroyed"})
    (rf.frame/destroy-frame! :fc/scratch)
    (is (nil? (rf.frame/frame :fc/scratch))
        "precondition: the frame is non-live after destroy-frame!")
    ;; Snapshot the three mutation surfaces BEFORE the rejected call.
    (let [flows-before    (rf.flows/flows-snapshot)
          inputs-before   (rf.flows/last-inputs-snapshot)
          registrar-before (rf.registrar/lookup :flow :leak/probe)
          thrown          (atom nil)]
      (try
        (rf/reg-flow :leak/probe {:frame :fc/scratch :inputs [[:n]] :output-path [:out]} (fn [n] (* (or n 0) 10)))
        (catch clojure.lang.ExceptionInfo e
          (reset! thrown (ex-data e))))
      (is (= :rf.error/flow-frame-not-live (:rf.error/id @thrown))
          "rejected with the stable :rf.error/flow-frame-not-live discriminator")
      (is (= :fc/scratch (:frame @thrown))
          "the error names the offending frame id")
      (is (= flows-before (rf.flows/flows-snapshot))
          "flows registry is unchanged — no resurrected flow row")
      (is (= inputs-before (rf.flows/last-inputs-snapshot))
          "last-inputs is unchanged")
      (is (= registrar-before (rf.registrar/lookup :flow :leak/probe))
          "the shared :flow registrar slot is unchanged (no dead-frame stamp)")
      (is (nil? (rf.registrar/lookup :flow :leak/probe))
          "specifically: no :flow registrar slot was installed"))))
