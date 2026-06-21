(ns re-frame.flows-destroy-frame-teardown-test
  "`destroy-frame!` cleans up the per-frame flow state (the per-frame `flows`
  registry entry and the frame's `last-inputs` rows). Symmetric with the
  machines `:machines/teardown-on-frame-destroy!` hook.

  SINGLE-STORE (rf2-en00bk): the per-frame `flows` atom is the SOLE store —
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
            [re-frame.elision :as elision]
            [re-frame.flows :as flows]
            ;; Loading `re-frame.flows` registers the late-bind hook
            ;; (`:flows/teardown-on-frame-destroy!`) the tests exercise —
            ;; keep the require even when the test ns doesn't reach
            ;; `flows/...` directly through a public fn.
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---- per-test reset ------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (flows/reset-last-inputs!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ;; EP-0002: reg-flow is context-required frame-local — an ambient call
  ;; under no scope raises :rf.error/no-frame-context. Pin :rf/default (an
  ;; ordinary frame) as the established scope for the body.
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- hook publication ----------------------------------------------------

(deftest flows-publishes-teardown-hook
  (testing ":flows/teardown-on-frame-destroy! is published when re-frame.flows is loaded"
    (is (fn? (late-bind/get-fn :flows/teardown-on-frame-destroy!))
        "the hook is callable after the flows artefact ns-loads")))

;; ---- per-frame registry slot cleared on destroy --------------------------

(deftest destroy-frame-clears-per-frame-flow-registry-slot
  (testing "destroying a frame drops its slot from re-frame.flows.registry/flows"
    (rf/reg-frame :fc/scratch {:doc "scratch frame for destroy teardown test"})
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* (or w 0) (or h 0)))
                  :output-path   [:rect :area]}
                 {:frame :fc/scratch})
    (is (contains? (flows/flows-snapshot) :fc/scratch)
        "precondition: the flow registered under the scratch frame's slot")
    (frame/destroy-frame! :fc/scratch)
    (is (not (contains? (flows/flows-snapshot) :fc/scratch))
        "post-destroy: the destroyed frame's slot is gone")))

;; ---- last-inputs rows cleared on destroy --------------------------------

(deftest destroy-frame-clears-last-inputs-rows-for-destroyed-frame
  (testing "destroying a frame removes the destroyed-frame entry from each flow's last-inputs row"
    (rf/reg-frame :fc/scratch {:doc "scratch frame for last-inputs teardown test"})
    (rf/reg-event :fc/seed (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* w h))
                  :output-path   [:rect :area]}
                 {:frame :fc/scratch})
    ;; Drive a drain on the scratch frame so the dirty-check populates
    ;; `last-inputs[:area][:fc/scratch]`.
    (rf/dispatch-sync [:fc/seed] {:frame :fc/scratch})
    (is (= [3 4]
           (get-in (flows/last-inputs-snapshot) [:area :fc/scratch]))
        "precondition: last-inputs recorded the scratch frame's inputs")
    (frame/destroy-frame! :fc/scratch)
    (is (not (contains? (get (flows/last-inputs-snapshot) :area) :fc/scratch))
        "post-destroy: the destroyed frame's last-inputs entry is gone")
    (is (not (contains? (flows/last-inputs-snapshot) :area))
        "and the whole flow-id row is dropped (no other frame still held an entry)")))

;; ---- last-inputs rows from sibling frames are preserved -----------------

(deftest destroy-frame-preserves-sibling-frames-last-inputs
  (testing "destroying frame A leaves frame B's last-inputs row for the same flow id intact"
    (rf/reg-frame :fc/a {:doc "frame A"})
    (rf/reg-frame :fc/b {:doc "frame B"})
    (rf/reg-event :fc/seed-a (fn [{:keys [db]} _] {:db {:w 2 :h 5}}))
    (rf/reg-event :fc/seed-b (fn [{:keys [db]} _] {:db {:w 7 :h 9}}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* w h))
                  :output-path   [:rect :area]}
                 {:frame :fc/a})
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* w h))
                  :output-path   [:rect :area]}
                 {:frame :fc/b})
    (rf/dispatch-sync [:fc/seed-a] {:frame :fc/a})
    (rf/dispatch-sync [:fc/seed-b] {:frame :fc/b})
    (is (= [2 5] (get-in (flows/last-inputs-snapshot) [:area :fc/a])))
    (is (= [7 9] (get-in (flows/last-inputs-snapshot) [:area :fc/b])))
    (frame/destroy-frame! :fc/a)
    (is (not (contains? (get (flows/last-inputs-snapshot) :area) :fc/a))
        "destroyed-frame A's last-inputs row is gone")
    (is (= [7 9] (get-in (flows/last-inputs-snapshot) [:area :fc/b]))
        "sibling frame B's last-inputs row is preserved")))

;; ---- per-frame entry dropped when destroyed frame was last owner --------

(deftest destroy-frame-drops-per-frame-entry-when-last-owner
  (testing "destroying the only frame that owned a flow id drops its per-frame entry (rf2-en00bk: no registrar slot to prune)"
    (rf/reg-frame :fc/scratch {:doc "scratch frame"})
    (rf/reg-flow {:id     :sole-area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* (or w 0) (or h 0)))
                  :output-path   [:rect :area]}
                 {:frame :fc/scratch})
    (is (some? (flows/flow-meta-at :sole-area {:frame :fc/scratch}))
        "precondition: the per-frame store carries the flow")
    (is (nil? (registrar/lookup :flow :sole-area))
        "the :flow registrar slot is RESERVED-but-empty even while the flow is live")
    (frame/destroy-frame! :fc/scratch)
    (is (nil? (flows/flow-meta-at :sole-area {:frame :fc/scratch}))
        "post-destroy: the per-frame entry is gone — no leaked entry")
    (is (nil? (registrar/lookup :flow :sole-area))
        "registrar slot stays empty (rf2-en00bk)")))

;; ---- sibling frame keeps its OWN authoritative entry on destroy ---------

(deftest destroy-frame-leaves-sibling-entry-authoritative-in-place
  (testing "destroying frame A leaves frame B's per-frame entry intact and authoritative in place (rf2-en00bk: no slot to realign)"
    (rf/reg-frame :fc/a {:doc "frame A"})
    (rf/reg-frame :fc/b {:doc "frame B"})
    (let [f-a (fn [w h] (* (or w 0) (or h 0)))
          f-b (fn [w h] (+ (or w 0) (or h 0)))]
      (rf/reg-flow {:id :shared :inputs [[:w] [:h]] :derive f-a :output-path [:rect :area]}
                   {:frame :fc/a})
      (rf/reg-flow {:id :shared :inputs [[:w] [:h]] :derive f-b :output-path [:rect :area]}
                   {:frame :fc/b})
      ;; Destroy :fc/a. :fc/b still holds :shared with its OWN divergent body.
      (frame/destroy-frame! :fc/a)
      (is (nil? (flows/flow-meta-at :shared {:frame :fc/a}))
          ":fc/a's entry is gone")
      (is (= f-b (:derive (flows/flow-meta-at :shared {:frame :fc/b})))
          ":fc/b's entry is intact and authoritative IN PLACE — no realignment needed (rf2-en00bk)")
      (is (nil? (registrar/lookup :flow :shared))
          "registrar :flow slot stays empty throughout (rf2-en00bk)"))))

(deftest destroy-frame-non-owner-leaves-owner-entry-intact
  (testing "destroying a frame that does NOT register the id leaves the registering frame's entry untouched (rf2-en00bk)"
    (rf/reg-frame :fc/a {:doc "frame A"})
    (rf/reg-frame :fc/b {:doc "frame B"})
    (rf/reg-flow {:id :shared :inputs [[:w] [:h]] :derive (fn [w h] h) :output-path [:rect :area]}
                 {:frame :fc/b})
    ;; Destroy :fc/a — it never registered :shared.
    (frame/destroy-frame! :fc/a)
    (is (some? (flows/flow-meta-at :shared {:frame :fc/b}))
        ":fc/b's entry survives — destroying :fc/a could not touch it")))

;; ---- SSR-style per-request frame churn stays bounded --------------------

(deftest ssr-style-frame-churn-stays-bounded
  (testing "creating + destroying N ephemeral frames each with a flow leaves the registry empty"
    (let [N 20]
      (dotimes [i N]
        (let [frame-id (keyword "fc" (str "ephemeral-" i))]
          (rf/reg-frame frame-id {:doc (str "ephemeral frame " i)})
          (rf/reg-flow {:id     :churn
                        :inputs [[:n]]
                        :derive (fn [n] (or n 0))
                        :output-path   [:result]}
                       {:frame frame-id})
          (rf/reg-event :fc/seed-churn (fn [{:keys [db]} [_ v]] {:db {:n v}}))
          (rf/dispatch-sync [:fc/seed-churn i] {:frame frame-id})
          (frame/destroy-frame! frame-id)))
      (is (empty? (flows/flows-snapshot))
          "per-frame flow registry is empty after N destroy cycles")
      (is (empty? (flows/last-inputs-snapshot))
          "last-inputs is empty after N destroy cycles")
      (is (nil? (registrar/lookup :flow :churn))
          "registrar :flow slot is RESERVED-but-empty throughout (rf2-en00bk single-store)"))))

;; ---- frame-id reuse: new reg-frame starts clean -------------------------

(deftest reg-frame-after-destroy-starts-clean
  (testing "registering a frame under a reused id after destroy starts with no leftover flow state"
    (rf/reg-frame :fc/scratch {:doc "first incarnation"})
    (rf/reg-event :fc/seed (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* (or w 0) (or h 0)))
                  :output-path   [:rect :area]}
                 {:frame :fc/scratch})
    (rf/dispatch-sync [:fc/seed] {:frame :fc/scratch})
    (frame/destroy-frame! :fc/scratch)
    (rf/reg-frame :fc/scratch {:doc "second incarnation"})
    (is (not (contains? (flows/flows-snapshot) :fc/scratch))
        "the new frame has no inherited flow-registry slot")
    (is (not (contains? (get (flows/last-inputs-snapshot) :area) :fc/scratch))
        "the new frame has no inherited last-inputs row")
    (is (nil? (flows/flow-meta-at :area {:frame :fc/scratch}))
        "the new frame has no inherited per-frame flow entry")
    (is (nil? (registrar/lookup :flow :area))
        "registrar :flow slot is RESERVED-but-empty throughout (rf2-en00bk)")))

;; ---- flow-output elision marks ride the frame-record drop ----------------
;;
;; `clear-flow` scrubs ONE flow's `:source :flow` elision declarations via
;; `clear-flow-output-marks!` while its frame lives on. Frame-destroy does NOT
;; call that scrub — and deliberately so: the elision registry lives in the
;; frame's runtime-db partition (`[:rf.runtime/elision]`) INSIDE the one
;; physical `:frame-state` container held under the frame record, and
;; `destroy-frame!` step 6 (`dissoc-frame!`) drops that whole record. These
;; tests pin the chosen contract: a destroyed frame cannot observe its
;; flow-sourced declarations, and a reused frame-id starts with NONE.

(deftest destroy-frame-drops-flow-output-marks
  (testing "Per rf2-yt5bbl: destroying a frame makes its flow-output elision
            marks unobservable — both :sensitive (sensitive-declarations) and
            :large (declarations) flow-sourced entries vanish with the frame
            record, with no explicit teardown scrub"
    (rf/reg-frame :fc/scratch {:doc "scratch frame for flow-output-mark teardown"})
    (rf/reg-flow {:id        :creds
                  :inputs    [[:n]]
                  :derive    (fn [n] {:secret n})
                  :output-path      [:derived :creds]
                  :sensitive [[:secret]]}
                 {:frame :fc/scratch})
    (rf/reg-flow {:id     :blob
                  :inputs [[:n]]
                  :derive (fn [n] {:bytes n})
                  :output-path   [:derived :blob]
                  :large? true}
                 {:frame :fc/scratch})
    ;; Precondition: the flow-sourced declarations are installed in the LIVE
    ;; frame's elision registry (the same surface flows_output_marks_test
    ;; reads). The sensitive subpath roots at :output-path ++ [:secret]; the large
    ;; whole-output mark roots at :output-path.
    (is (contains? (elision/sensitive-declarations :fc/scratch) [:derived :creds :secret])
        "precondition: the :sensitive flow declaration is installed")
    (is (contains? (elision/declarations :fc/scratch) [:derived :blob])
        "precondition: the :large flow declaration is installed")
    (frame/destroy-frame! :fc/scratch)
    ;; Post-destroy: `registry-of` reads the destroyed frame's container,
    ;; which `dissoc-frame!` removed, so both reader fns return {} — the
    ;; flow-sourced declarations are unobservable, no explicit scrub needed.
    (is (nil? (frame/frame :fc/scratch))
        "the frame record is gone after destroy-frame!")
    (is (= {} (elision/sensitive-declarations :fc/scratch))
        "the destroyed frame exposes no flow-sourced sensitive declarations")
    (is (= {} (elision/declarations :fc/scratch))
        "the destroyed frame exposes no flow-sourced large declarations")))

(deftest reg-frame-after-destroy-observes-no-stale-flow-output-marks
  (testing "Per rf2-yt5bbl (adversarial): a frame-id reused after a destroy
            that left flow-output marks behind starts with a FRESH empty
            container — the second incarnation observes NONE of the first
            incarnation's flow-sourced elision declarations. This is the
            regression guard for the teardown contract: the marks must not
            leak across a destroy→reuse cycle even though teardown runs no
            explicit elision scrub"
    ;; First incarnation: register a flow whose output is whole-sensitive.
    ;; EP-0025: classify the whole output explicitly with `:sensitive [[]]` (the
    ;; whole-value convention) — the removed `:rf.egress/output-sensitivity`
    ;; propagation claim's replacement.
    (rf/reg-frame :fc/scratch {:doc "first incarnation"})
    (rf/reg-flow {:id          :token
                  :inputs      [[:n]]
                  :derive      (fn [n] {:jwt n})
                  :output-path [:auth :token]
                  :sensitive   [[]]}
                 {:frame :fc/scratch})
    (is (contains? (elision/sensitive-declarations :fc/scratch) [:auth :token])
        "precondition: the first incarnation installed a whole-output sensitive mark")
    (frame/destroy-frame! :fc/scratch)
    ;; Second incarnation under the SAME id — a brand-new frame-state container.
    (rf/reg-frame :fc/scratch {:doc "second incarnation"})
    (is (= {} (elision/sensitive-declarations :fc/scratch))
        "the reused frame inherited no sensitive flow-sourced declaration")
    (is (= {} (elision/declarations :fc/scratch))
        "the reused frame inherited no large flow-sourced declaration")
    (is (not (contains? (elision/sensitive-declarations :fc/scratch) [:auth :token]))
        "specifically: the first incarnation's [:auth :token] mark did not survive")))

;; ---- reg-flow must not resurrect stale flows on a dead frame -------------
;;
;; `reg-flow` against an absent/destroyed frame is rejected BEFORE any state
;; mutates. `call-serialized-with-drain!` runs the registration thunk in-line
;; for a non-live frame, so without the guard the registration would install a
;; `flows` row and an elision declaration stamped with the dead frame-id — and
;; a later `reg-frame` reusing that id would inherit the resurrected flow.
;; SINGLE-STORE (rf2-en00bk): there is no registrar `:flow` slot to install,
;; so the `registrar/lookup :flow` checks below are nil throughout (the slot is
;; RESERVED-but-empty) — the per-frame `flows`-snapshot equality checks are the
;; load-bearing "mutated nothing" assertions.

(deftest reg-flow-against-destroyed-frame-rejects-and-mutates-nothing
  (testing "Per rf2-zbxvqj: reg-flow on a DESTROYED frame throws a stable
            structured error and leaves flows / last-inputs / the :flow
            registrar untouched (no dormant state for the dead frame)"
    (rf/reg-frame :fc/scratch {:doc "scratch frame, then destroyed"})
    (frame/destroy-frame! :fc/scratch)
    (is (nil? (frame/frame :fc/scratch))
        "precondition: the frame is non-live after destroy-frame!")
    ;; Snapshot the three mutation surfaces BEFORE the rejected call.
    (let [flows-before    (flows/flows-snapshot)
          inputs-before   (flows/last-inputs-snapshot)
          registrar-before (registrar/lookup :flow :leak/probe)
          thrown          (atom nil)]
      (try
        (rf/reg-flow {:id     :leak/probe
                      :inputs [[:n]]
                      :derive (fn [n] (* (or n 0) 10))
                      :output-path   [:out]}
                     {:frame :fc/scratch})
        (catch clojure.lang.ExceptionInfo e
          (reset! thrown (ex-data e))))
      (is (= :rf.error/flow-frame-not-live (:rf.error/id @thrown))
          "rejected with the stable :rf.error/flow-frame-not-live discriminator")
      (is (= :fc/scratch (:frame @thrown))
          "the error names the offending frame id")
      (is (= flows-before (flows/flows-snapshot))
          "flows registry is unchanged — no resurrected flow row")
      (is (= inputs-before (flows/last-inputs-snapshot))
          "last-inputs is unchanged")
      (is (= registrar-before (registrar/lookup :flow :leak/probe))
          "the shared :flow registrar slot is unchanged (no dead-frame stamp)")
      (is (nil? (registrar/lookup :flow :leak/probe))
          "specifically: no :flow registrar slot was installed"))))

(deftest reg-flow-against-never-registered-frame-rejects
  (testing "Per rf2-zbxvqj: reg-flow against a NEVER-registered (typo'd) frame
            id is rejected the same way as a destroyed one — no dormant state"
    (is (nil? (frame/frame :fc/never))
        "precondition: the frame id was never registered")
    (let [flows-before     (flows/flows-snapshot)
          registrar-before (registrar/lookup :flow :typo/flow)
          thrown           (atom nil)]
      (try
        (rf/reg-flow {:id     :typo/flow
                      :inputs [[:n]]
                      :derive (fn [n] (or n 0))
                      :output-path   [:out]}
                     {:frame :fc/never})
        (catch clojure.lang.ExceptionInfo e
          (reset! thrown (ex-data e))))
      (is (= :rf.error/flow-frame-not-live (:rf.error/id @thrown))
          "rejected with the same stable discriminator as the destroyed-frame case")
      (is (= flows-before (flows/flows-snapshot))
          "flows registry is unchanged")
      (is (nil? (registrar/lookup :flow :typo/flow))
          "no :flow registrar slot was installed")
      (is (= registrar-before (registrar/lookup :flow :typo/flow))
          "the :flow registrar slot is unchanged"))))

(deftest reg-flow-after-destroy-then-reg-frame-reuse-starts-without-resurrected-flow
  (testing "Per rf2-zbxvqj: a reg-flow against a frame in its destroyed window,
            followed by re-registering that frame id, leaves the fresh frame
            with NO inherited flow — the resurrection path is closed"
    (rf/reg-frame :fc/scratch {:doc "first incarnation"})
    (frame/destroy-frame! :fc/scratch)
    ;; The resurrection attempt — rejected, mutates nothing.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"rf.error/flow-frame-not-live"
          (rf/reg-flow {:id     :leak/probe
                        :inputs [[:n]]
                        :derive (fn [n] (* (or n 0) 10))
                        :output-path   [:out]}
                       {:frame :fc/scratch}))
        "reg-flow on the dead frame is rejected")
    ;; Re-register the same id and drive a drain — the stale flow must NOT run.
    (rf/reg-frame :fc/scratch {:doc "second incarnation"})
    (rf/reg-event :fc/set-n (fn [{:keys [db]} [_ v]] {:db {:n v}}))
    (rf/dispatch-sync [:fc/set-n 7] {:frame :fc/scratch})
    (is (not (contains? (flows/flows-snapshot) :fc/scratch))
        "the re-registered frame inherited no flow-registry slot")
    (is (nil? (registrar/lookup :flow :leak/probe))
        "no resurrected :flow registrar slot survived into the new frame")
    (is (nil? (get (frame/frame-app-db-value :fc/scratch) :out))
        "the stale flow did not run — no [:out] write in the fresh frame's app-db")))
