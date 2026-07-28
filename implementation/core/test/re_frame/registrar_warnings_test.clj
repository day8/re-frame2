(ns re-frame.registrar-warnings-test
  "Per rf2-45kaz / Spec 001 §`:doc` is dev-warned when absent +
  §Re-registration of a different function — collision warning.

  Two warnings the registrar emits on the trace bus:

    1. `:rf.warning/missing-doc` — every reg-* call whose final
       metadata-map carries no usable `:doc` slot (absent, nil, or
       empty string). Suppressed per `(kind, id)` within a runtime
       process so the dev stream stays readable across hot-reload.
       Emitted from the public macro path only — programmatic
       internal helpers that bypass coord capture are out of scope.

    2. `:rf.warning/registration-collision` — re-registration with a
       different `:handler-fn`. Sits alongside the existing
       `:rf.registry/handler-replaced` trace (which fires on EVERY
       re-registration with a `:different-fn?` tag); the warning is
       the separate dev-nudge surface that lifts the collision out
       of the steady-state hot-reload stream. Same per-(kind, id)
       suppression discipline.

  Both warnings sit inside the registrar's outer
  `(when interop/debug-enabled? ...)` gate so `:advanced +
  goog.DEBUG=false` constant-folds the consult+emit branch to nil
  (Spec 009 §Production builds). The CLJS elision-probe sentinels
  pin the absence of `rf.warning/missing-doc` and
  `rf.warning/registration-collision` in the production bundle.

  ## Posture split (rf2-d2841)

  The paragraph above is the whole story for the WARNINGS: both sit inside the
  registrar's `(when interop/debug-enabled? ...)` gate, so under
  `-Dre-frame.debug=false` neither is emitted and every warning assertion here
  failed under `scripts/test-core-prod-gate.sh`. They are guarded verbatim.

  A WHOLESALE GUARD WOULD HAVE MADE THIS A CLASS-2 FILE — a namespace reported
  green having executed nothing — so each case also witnesses the REGISTRATION
  the warning is a commentary on, and that half is production behaviour:

    * the missing-doc cases assert the registration nevertheless SUCCEEDED,
      which is the §No-silent-swallow contract's other half — the cascade
      continues — and the only part of it a production build honours;
    * the collision cases assert the LAST registration won — read back off
      `handler-meta`'s provenance, which `register!` stores verbatim (only
      `:doc` is stripped there, and `merge-coords` is not on this path). That
      is the fact the collision warning exists to draw attention to, and it is
      posture-independent;
    * the rf2-3az1vn `:route` case additionally asserts the no-`:handler-fn`
      slot was replaced at all — the shape-dedup that used to HIDE the
      collision suppresses only the TRACE, never the replacement.

  SEVEN VACUOUS PASSES CAME OFF (rf2-d2841 class 1 — a negative over an empty
  trace ring). `missing-doc-suppressed-when-doc-present`,
  `missing-doc-silent-on-programmatic-path`, `collision-silent-on-same-source-
  re-eval`, `collision-silent-on-programmatic-path`, `collision-still-silent-
  on-same-source-for-no-handler-fn-kind`, `handler-replaced-fires-on-silent-
  hot-reload`'s `(empty? collisions)` and — the one worth naming twice —
  `collision-fires-for-no-handler-fn-kind-despite-shape-dedup`'s
  `(is (empty? replaced) ...)`, which certified that the dedup gate suppressed
  the `handler-replaced` trace over a stream that carries no traces at all.
  Five of those seven deftests were GREEN under the gate on nothing else."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.interop :as interop]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.core :as rf]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces!
  "Attach a recording listener and return its atom."
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warnings-of
  [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

(defn- assert-registered
  "ALWAYS-ON (rf2-d2841): the registration LANDED. A dev warning is a nudge,
  not a rejection — Spec 001 §No silent swallow requires the cascade to
  continue — and that continuation is the only half of the contract a
  production build can be asked about."
  [kind id]
  (is (some? (registrar/lookup kind id))
      (str "the " kind " registration for " id " succeeded in this posture")))

(defn- assert-live-provenance
  "ALWAYS-ON (rf2-d2841): which registration is LIVE for `[kind id]`, read off
  the stored provenance. `register!` keeps the metadata it was handed verbatim
  (only `:doc` is stripped under the production gate, and `merge-coords` is
  not on this path), so the `:ns` of the winning registration is readable in
  both postures. This is the fact the collision warning narrates."
  [kind id expected-ns]
  (is (= expected-ns (:ns (registrar/lookup kind id)))
      (str "the live " kind " registration for " id " came from " expected-ns)))

(defn- with-stamped-coords
  "Invoke `f` with `*pending-coords*` bound to a synthetic
  macro-path coord map. Mirrors what every reg-* macro does at
  expansion time — every entry that reaches `register!` via the
  user-facing macro path carries the `:ns/:line/:file` envelope.

  Tests use this to exercise the `:rf.warning/missing-doc` emission
  gate, which only fires for metadata that came through the macro
  path (Spec 001 §`:doc` obligation 4 carves out programmatic /
  internal-helper paths)."
  [f]
  (binding [source-coords/*pending-coords*
            {:ns 're-frame.registrar-warnings-test
             :file "registrar_warnings_test.clj"
             :line 1
             :column 1}]
    (f)))

(defn- reg-at
  "Register an `:event` for `id` with an explicit source-coord `provenance`
  envelope (`{:ns :file :line ...}`) and a freshly-allocated handler-fn.

  The collision tests drive `registrar/register!` directly rather than the
  `reg-event` MACRO because the macro re-captures coords from its own call
  site `(meta &form)` — two macro calls in the test file are always on
  different LINES, so they could never share the same provenance, which is
  exactly the hot-reload re-eval case the rf2-skxd6x fix must keep SILENT.
  A real hot reload lands at `register!` with the SAME `(ns, file, line)`
  but a fresh fn instance; passing the provenance metadata explicitly here
  reproduces that faithfully. A `nil` `provenance` reproduces the
  programmatic / REPL path (no captured coords)."
  ([id] (reg-at id nil))
  ([id provenance]
   (registrar/register! :event id
                        (merge (or provenance {})
                               {:handler-fn (fn [{:keys [db]} _] {:db db})}))))

;; =============================================================================
;; F1 — `:rf.warning/missing-doc`
;; =============================================================================

;; Obligation 1: emit on every reg-* whose final metadata-map carries no
;; usable :doc (absent, nil, or empty string).

(deftest missing-doc-fires-when-doc-absent
  (testing "reg-* via the macro path with no :doc key emits :rf.warning/missing-doc"
    (let [recorded (record-traces! ::missing-absent)]
      (with-stamped-coords
        (fn []
          (rf/reg-event :ev/no-doc (fn [{:keys [db]} _] {:db db}))))
      (assert-registered :event :ev/no-doc)
      (when interop/debug-enabled?
        (let [warns (warnings-of recorded :rf.warning/missing-doc)]
          (is (= 1 (count warns))
              (str "expected exactly one missing-doc warning, got " (count warns)))
          (let [t (:tags (first warns))]
            (is (= :event (:kind t))
                ":tags carries the registry :kind")
            (is (= :ev/no-doc (:id t))
                ":tags carries the registered :id")
            (is (map? (:source-coords t))
                ":tags carries the captured :source-coords envelope")
            (is (= 're-frame.registrar-warnings-test (:ns (:source-coords t))))))))))

(deftest missing-doc-fires-when-doc-nil
  (testing ":doc explicitly nil is treated as missing"
    (let [recorded (record-traces! ::missing-nil)]
      (with-stamped-coords
        (fn []
          (rf/reg-event :ev/nil-doc {:doc nil} (fn [{:keys [db]} _] {:db db}))))
      (assert-registered :event :ev/nil-doc)
      (when interop/debug-enabled?
        (is (= 1 (count (warnings-of recorded :rf.warning/missing-doc))))))))

(deftest missing-doc-fires-when-doc-empty-string
  (testing ":doc as the empty string is treated as missing"
    (let [recorded (record-traces! ::missing-empty)]
      (with-stamped-coords
        (fn []
          (rf/reg-event :ev/empty-doc {:doc ""} (fn [{:keys [db]} _] {:db db}))))
      (assert-registered :event :ev/empty-doc)
      (when interop/debug-enabled?
        (is (= 1 (count (warnings-of recorded :rf.warning/missing-doc))))))))

(deftest missing-doc-suppressed-when-doc-present
  (testing "well-documented registration emits no warning"
    (let [recorded (record-traces! ::doc-present)]
      (with-stamped-coords
        (fn []
          (rf/reg-event :ev/well-doc'd
                           {:doc "a real description"}
                           (fn [{:keys [db]} _] {:db db}))))
      (assert-registered :event :ev/well-doc'd)
      ;; rf2-d2841 — class-1 vacuous under the gate: the warning stream is
      ;; empty for EVERY registration there, documented or not.
      (when interop/debug-enabled?
        (is (empty? (warnings-of recorded :rf.warning/missing-doc)))))))

;; Obligation 2: suppress per (kind, id) within a runtime process.

(deftest missing-doc-suppressed-on-re-registration-same-id
  (testing "re-registering the same (kind, id) with still-missing :doc does NOT re-emit"
    (let [recorded (record-traces! ::suppress-rereg)]
      (with-stamped-coords
        (fn []
          (rf/reg-event :ev/same-id (fn [{:keys [db]} _] {:db db}))
          ;; Save-triggered re-eval — same id, still no :doc; warning is silent.
          (rf/reg-event :ev/same-id (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
          (rf/reg-event :ev/same-id (fn [{:keys [db]} _] {:db db}))))
      (assert-registered :event :ev/same-id)
      (when interop/debug-enabled?
        (is (= 1 (count (warnings-of recorded :rf.warning/missing-doc)))
            "exactly one warning across three registrations of the same id")))))

(deftest missing-doc-fires-once-per-id-within-kind
  (testing "different ids under the same kind each get their own warning"
    (let [recorded (record-traces! ::per-id-within-kind)]
      (with-stamped-coords
        (fn []
          (rf/reg-event :ev/alpha (fn [{:keys [db]} _] {:db db}))
          (rf/reg-event :ev/beta  (fn [{:keys [db]} _] {:db db}))
          (rf/reg-event :ev/gamma (fn [{:keys [db]} _] {:db db}))))
      (doseq [id [:ev/alpha :ev/beta :ev/gamma]]
        (assert-registered :event id))
      (when interop/debug-enabled?
        (let [warns (warnings-of recorded :rf.warning/missing-doc)]
          (is (= 3 (count warns)))
          (is (= #{:ev/alpha :ev/beta :ev/gamma}
                 (into #{} (map #(get-in % [:tags :id])) warns))))))))

(deftest missing-doc-fires-once-per-kind-for-same-id
  (testing "the same id under different kinds each get their own warning"
    (let [recorded (record-traces! ::per-kind-same-id)]
      (with-stamped-coords
        (fn []
          (rf/reg-event :alias/shared (fn [{:keys [db]} _] {:db db}))
          (rf/reg-sub       :alias/shared (fn [db _] (:x db)))))
      (assert-registered :event :alias/shared)
      (assert-registered :sub   :alias/shared)
      (when interop/debug-enabled?
        (let [warns (warnings-of recorded :rf.warning/missing-doc)]
          (is (= 2 (count warns)))
          (is (= #{:event :sub}
                 (into #{} (map #(get-in % [:tags :kind])) warns))))))))

;; Obligation 4: kind coverage. Spot-check a representative slice across
;; the canonical kinds (:event :sub :fx :cofx). The :doc check sits in
;; the registrar chokepoint so every kind flows through the same gate;
;; per-kind enumeration confirms the gate is independent of kind.

(deftest missing-doc-fires-across-multiple-kinds
  (testing "the gate is kind-agnostic — fires on :event, :sub, :fx, :cofx"
    (let [recorded (record-traces! ::multi-kind)]
      (with-stamped-coords
        (fn []
          (rf/reg-event :k/event-doc-missing (fn [{:keys [db]} _] {:db db}))
          (rf/reg-sub       :k/sub-doc-missing   (fn [db _] (:x db)))
          (rf/reg-fx        :k/fx-doc-missing    (fn [_ _] nil))
          (rf/reg-cofx      :k/cofx-doc-missing  (fn [] :stub))))
      (assert-registered :event :k/event-doc-missing)
      (assert-registered :sub   :k/sub-doc-missing)
      (assert-registered :fx    :k/fx-doc-missing)
      (assert-registered :cofx  :k/cofx-doc-missing)
      (when interop/debug-enabled?
        (let [warns (warnings-of recorded :rf.warning/missing-doc)
              kinds (into #{} (map #(get-in % [:tags :kind])) warns)]
          (is (= 4 (count warns))
              "one warning per (kind, id) across four kinds")
          (is (= #{:event :sub :fx :cofx} kinds)))))))

;; Obligation 4: programmatic registrations that bypass the macro path
;; (no source coords merged in) are out of scope.

(deftest missing-doc-silent-on-programmatic-path
  (testing "register! called without macro-path source coords does NOT emit"
    (let [recorded (record-traces! ::programmatic)]
      ;; Note: NO with-stamped-coords wrapper — *pending-coords* is nil,
      ;; mirroring an internal helper / REPL register! call.
      (registrar/register! :event :internal/no-coords
                           {:handler-fn (fn [db _] db)})
      (assert-registered :event :internal/no-coords)
      ;; rf2-d2841 — class-1 vacuous under the gate.
      (when interop/debug-enabled?
        (is (empty? (warnings-of recorded :rf.warning/missing-doc))
            "programmatic / internal-helper path is out of scope (Spec 001 obligation 4)")))))

;; =============================================================================
;; F2 — `:rf.warning/registration-collision`
;;
;; Per Spec 001 §Re-registration of a different function — collision warning:
;; the detection keys on the registration's source-coord PROVENANCE pair
;; (`:ns` / `:file` / `:line`), NOT fn identity. "A re-eval of the same source
;; file produces the same `(file, line)` pair and is silent; a different file
;; or line reassigning the id surfaces `:rf.warning/registration-collision`."
;;
;; rf2-skxd6x fix: the prior implementation compared `:handler-fn` identity, so
;; a same-file save-and-re-eval (which yields a FRESH fn instance) false-fired
;; the warning on every hot reload — exactly the false positive the spec says
;; MUST be silent. These tests pin the corrected provenance boundary.
;; =============================================================================

;; The CORE regression for rf2-skxd6x: a same-source re-eval (same coords,
;; different fn instance) is a hot reload and MUST be silent. Driven through
;; `registrar/register!` so the provenance can be held CONSTANT across the two
;; registrations (the `reg-event` macro re-captures its own call-site coords,
;; so two macro calls are always on different lines — see `reg-at`).

(deftest collision-silent-on-same-source-re-eval
  (testing "re-registering from the same (ns,file,line) with a fresh fn is a hot reload — silent"
    (let [recorded (record-traces! ::collision-same-source)
          coords   {:ns 're-frame.registrar-warnings-test
                    :file "registrar_warnings_test.clj"
                    :line 42 :column 3}]
      ;; Two registrations from the IDENTICAL source location, each with a
      ;; freshly-allocated handler-fn (what a save-triggered re-eval produces).
      ;; The prior fn-identity implementation false-fired here; the provenance
      ;; boundary keeps it silent (rf2-skxd6x).
      (reg-at :hot/reload coords)
      (reg-at :hot/reload coords)
      ;; ALWAYS-ON (rf2-d2841): the re-eval REPLACED the slot — that is the
      ;; production behaviour the silent-warning rule is a policy about.
      (assert-live-provenance :event :hot/reload 're-frame.registrar-warnings-test)
      ;; rf2-d2841 — class-1 vacuous under the gate.
      (when interop/debug-enabled?
        (is (empty? (warnings-of recorded :rf.warning/registration-collision))
            "same-source re-eval (fresh fn, same coords) must NOT warn — it's a hot reload")))))

(deftest collision-fires-on-cross-provenance-reassignment
  (testing "re-registering the id from a DIFFERENT provenance (file/line/ns) warns"
    (let [recorded (record-traces! ::collision-cross)]
      ;; Feature A registers :collide/id; feature B (a different file) clobbers it.
      (reg-at :collide/id {:ns 'feature.a :file "feature/a.cljc" :line 10 :column 1})
      (reg-at :collide/id {:ns 'feature.b :file "feature/b.cljc" :line 20 :column 1})
      ;; ALWAYS-ON (rf2-d2841): feature B CLOBBERED feature A. The warning is a
      ;; dev nudge about a production fact, and the fact is readable in both
      ;; postures off the stored provenance.
      (assert-live-provenance :event :collide/id 'feature.b)
      (when interop/debug-enabled?
        (let [warns (warnings-of recorded :rf.warning/registration-collision)]
          (is (= 1 (count warns))
              "exactly one collision warning fires on the cross-provenance reassignment")
          (let [t (:tags (first warns))]
            (is (= :event (:kind t)))
            (is (= :collide/id (:id t)))
            (is (map? (:source-coords t))
                ":source-coords carries the NEW (feature B) registration's coords")
            (is (= 'feature.b (:ns (:source-coords t))))
            (is (map? (:previous-coords t))
                ":previous-coords carries the prior (feature A) registration's coords")
            (is (= 'feature.a (:ns (:previous-coords t))))))))))

(deftest collision-fires-on-same-file-different-line
  (testing "two registrations of the same id at different lines in one file collide"
    (let [recorded (record-traces! ::collision-same-file-diff-line)]
      ;; Same ns + file but a DIFFERENT line — two distinct authoring sites in
      ;; one file accidentally reusing an id. Per the spec's `(file, line)` rule.
      (reg-at :dup/id {:ns 're-frame.registrar-warnings-test
                       :file "registrar_warnings_test.clj" :line 10 :column 1})
      (reg-at :dup/id {:ns 're-frame.registrar-warnings-test
                       :file "registrar_warnings_test.clj" :line 99 :column 1})
      (assert-live-provenance :event :dup/id 're-frame.registrar-warnings-test)
      (is (= 99 (:line (registrar/lookup :event :dup/id)))
          "the second authoring site is the live one in this posture")
      (when interop/debug-enabled?
        (is (= 1 (count (warnings-of recorded :rf.warning/registration-collision)))
            "different line in the same file is a collision (Spec 001))")))))

(deftest collision-silent-on-programmatic-path
  (testing "programmatic register! (no captured coords) does not warn"
    (let [recorded (record-traces! ::collision-programmatic)]
      ;; No coords on either registration — there is no source identity to clash.
      (reg-at :prog/id nil)
      (reg-at :prog/id nil)
      (assert-registered :event :prog/id)
      (is (nil? (:ns (registrar/lookup :event :prog/id)))
          "the programmatic path stored no provenance to collide on")
      ;; rf2-d2841 — class-1 vacuous under the gate.
      (when interop/debug-enabled?
        (is (empty? (warnings-of recorded :rf.warning/registration-collision))
            "programmatic / REPL path has no provenance — no collision to detect")))))

(deftest collision-suppressed-on-third-re-registration
  (testing "subsequent cross-provenance re-registrations of the same (kind, id) do NOT re-emit"
    (let [recorded (record-traces! ::collision-suppressed)]
      ;; Four registrations, each from a distinct provenance — only the first
      ;; cross-provenance reassignment warns; warn-once suppresses the rest.
      (doseq [[ns line] [['feat.a 1] ['feat.b 2] ['feat.c 3] ['feat.d 4]]]
        (reg-at :churn/id {:ns ns :file (str ns ".cljc") :line line :column 1}))
      (assert-live-provenance :event :churn/id 'feat.d)
      (when interop/debug-enabled?
        (is (= 1 (count (warnings-of recorded :rf.warning/registration-collision)))
            "warn-once: only the first cross-provenance re-registration emits")))))

;; The existing `:rf.registry/handler-replaced` trace must keep firing on
;; EVERY re-registration (per Spec 001 §Hot-reload trace surface, rf2-6w7zn)
;; — the collision warning is a SEPARATE surface, not a replacement. Crucially,
;; handler-replaced fires even on the SILENT same-source hot-reload path.

(deftest handler-replaced-fires-on-silent-hot-reload
  (testing "handler-replaced fires on a same-source re-eval even though collision is silent"
    (let [recorded (record-traces! ::replaced-on-hot-reload)
          coords   {:ns 're-frame.registrar-warnings-test
                    :file "registrar_warnings_test.clj" :line 7 :column 1}]
      (reg-at :hr/id coords)
      (reg-at :hr/id coords)
      (reg-at :hr/id coords)
      (assert-live-provenance :event :hr/id 're-frame.registrar-warnings-test)
      (when interop/debug-enabled?
        (let [replaced (filterv (fn [ev]
                                  (and (= :rf.registry (:op-type ev))
                                       (= :rf.registry/handler-replaced
                                          (:operation ev))))
                                @recorded)
              collisions (warnings-of recorded :rf.warning/registration-collision)]
          (is (= 2 (count replaced))
              "handler-replaced fires on each of the two re-registrations")
          ;; rf2-d2841 — class-1 vacuous under the gate.
          (is (empty? collisions)
              "no collision on a same-source hot reload (the rf2-skxd6x fix)"))))))

(deftest collision-warning-coexists-with-handler-replaced
  (testing "handler-replaced still fires unconditionally; collision is the dev-nudge addition"
    (let [recorded (record-traces! ::coexist)]
      ;; Three registrations from three distinct provenances.
      (doseq [[ns line] [['c.a 1] ['c.b 2] ['c.c 3]]]
        (reg-at :coex/id {:ns ns :file (str ns ".cljc") :line line :column 1}))
      (assert-live-provenance :event :coex/id 'c.c)
      (when interop/debug-enabled?
        (let [replaced (filterv (fn [ev]
                                  (and (= :rf.registry (:op-type ev))
                                       (= :rf.registry/handler-replaced
                                          (:operation ev))))
                                @recorded)
              collisions (warnings-of recorded :rf.warning/registration-collision)]
          (is (= 2 (count replaced))
              "handler-replaced fires on each of the two re-registrations")
          (is (= 1 (count collisions))
              "collision warning fires once and is then suppressed"))))))

;; =============================================================================
;; F3 — collision warning is DECOUPLED from the handler-replaced dedup gate
;;      (rf2-3az1vn P2)
;;
;; A kind with NO `:handler-fn` — `:route` / `:head` — replaces its
;; slot WITHOUT rotating a handler fn. The B4 hot-reload dedup-by-shape table
;; (`trace.tooling/dedup-allow?`) strips source-coords (`:ns`/`:file`/`:line`/
;; `:column`) from the shape, so two cross-source registrations of such a kind
;; with otherwise-identical metadata hash to the IDENTICAL shape →
;; `dedup-allow? :rf.registry/handler-replaced` returns FALSE (an idempotent
;; hot-reload re-emit). The prior code NESTED `maybe-emit-collision!` inside
;; that `dedup-allow?` gate, so a GENUINE cross-source clash for these kinds
;; never warned. The fix calls the collision check independently. The collision
;; warning keys on PROVENANCE (not shape), so it must still fire.
;; =============================================================================

(defn- reg-no-handler-fn!
  "Register a `kind` id carrying NO `:handler-fn` (a `:route` /
  `:head`-shaped slot) with an explicit source-coord `provenance` envelope. The
  residual metadata is identical across calls except for provenance, so the B4
  shape table sees the SAME shape on re-register — exactly the case the old
  nested-collision code dedup-suppressed."
  [kind id provenance]
  (registrar/register! kind id (merge (or provenance {}) {:doc "slot"})))

(deftest collision-fires-for-no-handler-fn-kind-despite-shape-dedup
  (testing "a :route-kind cross-source reassignment WARNS even though its shape
            is dedup-identical (no rotating :handler-fn) — collision is decoupled
            from the handler-replaced dedup gate (rf2-3az1vn P2)"
    (let [recorded (record-traces! ::no-fn-collision)]
      ;; Two DIFFERENT authoring sites register the SAME :route id. No handler-fn
      ;; on either, identical residual metadata → identical dedup shape.
      (reg-no-handler-fn! :route :surface/main
                          {:ns 'feature.a :file "feature/a.cljc" :line 10 :column 1})
      (reg-no-handler-fn! :route :surface/main
                          {:ns 'feature.b :file "feature/b.cljc" :line 20 :column 1})
      ;; ALWAYS-ON (rf2-d2841): the SHAPE-dedup suppresses only the TRACE. The
      ;; slot itself was replaced — feature B's route is the live one — which
      ;; is precisely why the hidden collision mattered.
      (assert-live-provenance :route :surface/main 'feature.b)
      (when interop/debug-enabled?
        (let [replaced (filterv (fn [ev]
                                  (and (= :rf.registry (:op-type ev))
                                       (= :rf.registry/handler-replaced (:operation ev))))
                                @recorded)
              collisions (warnings-of recorded :rf.warning/registration-collision)]
          ;; rf2-d2841 — class-1 vacuous under the gate, and the sharpest example
          ;; in this file: it certifies that a DEDUP GATE suppressed an emit,
          ;; over a stream that carries no emits at all.
          (is (empty? replaced)
              "handler-replaced is dedup-SUPPRESSED — the shape is identical (no
               handler-fn rotation); this is the gate that used to hide the collision")
          (is (= 1 (count collisions))
              "the collision warning STILL fires — it is decoupled from the dedup gate")
          (let [t (:tags (first collisions))]
            (is (= :route (:kind t)))
            (is (= :surface/main (:id t)))
            (is (= 'feature.b (:ns (:source-coords t))))
            (is (= 'feature.a (:ns (:previous-coords t))))))))))

(deftest collision-still-silent-on-same-source-for-no-handler-fn-kind
  (testing "a same-source re-eval of a no-handler-fn kind stays SILENT — the
            decoupled collision check still keys on provenance, not shape"
    (let [recorded (record-traces! ::no-fn-same-source)
          coords   {:ns 'feature.a :file "feature/a.cljc" :line 10 :column 1}]
      (reg-no-handler-fn! :route :surface/hot coords)
      (reg-no-handler-fn! :route :surface/hot coords)
      (assert-live-provenance :route :surface/hot 'feature.a)
      ;; rf2-d2841 — class-1 vacuous under the gate.
      (when interop/debug-enabled?
        (is (empty? (warnings-of recorded :rf.warning/registration-collision))
            "same (ns,file,line) re-eval is a hot reload — no collision even though
             the collision check now runs unconditionally")))))

