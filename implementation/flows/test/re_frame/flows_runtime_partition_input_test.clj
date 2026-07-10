(ns re-frame.flows-runtime-partition-input-test
  "RUNTIME behavior of the `[:rf.db/runtime …]` partition-qualified flow INPUT
  (EP-0001 §535-551) — the branch of `re-frame.flows/resolve-input` /
  `read-inputs` / `elide-inputs` that only fires when `run-flows-on-db` is
  driven with a NON-nil `runtime-db`.

  Coverage gap this file closes (rf2-826l3c): the runtime-qualified input was
  tested only STATICALLY — the algebra-view projection lowering
  (`flow_algebra_view_test.clj` §runtime-qualified-input-lowers-to-a-runtime-read)
  and the negative output-path reservation (`flows_test.clj`
  §reg-flow-rejects-runtime-partition-rooted-output-path). Every DIRECT drive of
  `run-flows-on-db` in the suite passed `nil` for `runtime-db`
  (`flows_per_frame_last_inputs_test.clj`, `flows_trace_test.clj`), so the
  RUNTIME partition branch never executed under test. A regression that read a
  runtime input from app-db, failed to strip the `:rf.db/runtime` partition key,
  or omitted the resolved runtime value from the dirty-check vector would ship
  GREEN.

  These tests drive `run-flows-on-db` with a non-nil `runtime-db` and pin, all
  adversarially:

    1. `resolve-input`'s runtime branch reads the input VALUE from `runtime-db`
       at the STRIPPED path (never app-db, and never the un-stripped path);
    2. the EP-0001 §542-544 dirty-check-on-BOTH-partitions contract — a
       runtime-only change (app-db VALUE-IDENTICAL between drains) forces a
       recompute, and a runtime value-equal re-drive skips;
    3. mixed app-db + runtime inputs resolve in DECLARATION ORDER, each against
       its own partition;
    4. `elide-inputs` seeds the STRIPPED declaration path, so a runtime-qualified
       input elides on the `:rf.flow/computed` `:input-values` trace against the
       runtime-db slot's declaration (a raw `[:rf.db/runtime …]` seed would miss
       the declaration and surface the value RAW).

  JVM-only (`.clj`) — mirrors the sibling flows JVM tests; `run-flows-on-db` is
  driven directly (as `flows_per_frame_last_inputs_test` does) rather than
  through a full dispatch, so the runtime partition value is supplied
  explicitly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

;; ---- per-test reset / trace recorder -------------------------------------
;;
;; The standard runtime reset (registrar baseline + frames + flows/schemas +
;; plain-atom adapter + ambient `:rf/default` scope) is owned by
;; `make-reset-runtime-fixture`. Layered AROUND it, a small concern-specific
;; fixture installs a flow-op-type trace recorder for the body to assert
;; against — the standard reset already cleared every trace listener, so the
;; recorder starts clean and is torn down in its own `finally`.

(def ^:dynamic ^:private *captured* nil)

(defn- with-flow-trace-recorder
  "Bind `*captured*` and record every `:flow`-op-type trace event for the
  duration of one test; unregister the recorder afterwards."
  [test-fn]
  (let [captured (atom [])]
    (binding [*captured* captured]
      (trace/register-listener!
        ::flow-trace-recorder
        (fn [ev]
          (when (= :flow (:op-type ev))
            (swap! captured conj ev))))
      (try
        (test-fn)
        (finally
          (trace/unregister-listener! ::flow-trace-recorder))))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  with-flow-trace-recorder)

(defn- by-op
  "Captured flow-trace events with `:operation` = `op`, in capture order."
  [op]
  (filterv #(= op (:operation %)) @*captured*))

(defn- install-large!
  "Seed the frame's large app-db/runtime-slot classification via the commit-plane
  effect path (`:source :effect`) — the same seam `flows_trace_test.clj` uses.
  The declared paths are stored in the frame's runtime-db elision registry, so
  `elide-wire-value` (frame-scoped) matches a value at the declared path."
  [frame-id & paths]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:large (mapv vec paths)}))))

;; ---------------------------------------------------------------------------
;; 1. resolve-input's runtime branch — reads the VALUE from runtime-db at the
;;    STRIPPED path, never app-db, never the un-stripped path.
;;
;; Adversarial decoys are seeded at BOTH the stripped path AND the raw
;; `[:rf.db/runtime …]` path INSIDE app-db, so each regression lands on a
;; distinct wrong value:
;;   - reading app-db at the stripped path         → :APP-DECOY-stripped
;;   - reading app-db at the raw partition path     → :APP-DECOY-raw
;;   - reading runtime-db WITHOUT stripping         → nil (no such key there)
;;   - CORRECT: runtime-db at the stripped path     → :the-real-runtime-value
;; ---------------------------------------------------------------------------

(deftest resolve-input-runtime-branch-reads-stripped-path-from-runtime-db
  (testing "a [:rf.db/runtime …] input resolves its VALUE against runtime-db at the stripped path"
    (rf/reg-flow :route-flow
      {:inputs      [[:rf.db/runtime :rf.runtime/routing :current :route-id]]
       :output-path [:out]}
      (fn [route-id] route-id))
    (let [app-db     {;; decoy at the STRIPPED path inside app-db — a resolver
                      ;; that forgot the runtime branch would read this
                      :rf.runtime/routing {:current {:route-id :APP-DECOY-stripped}}
                      ;; decoy at the RAW partition-qualified path inside app-db —
                      ;; a resolver that read app-db verbatim would read this
                      :rf.db/runtime      {:rf.runtime/routing {:current {:route-id :APP-DECOY-raw}}}}
          runtime-db {:rf.runtime/routing {:current {:route-id :the-real-runtime-value}}}
          out        (flows/run-flows-on-db :rf/default app-db runtime-db)]
      (is (= :the-real-runtime-value (get-in out [:out]))
          (str "resolve-input must read runtime-db at the STRIPPED path "
               "[:rf.runtime/routing :current :route-id]; got "
               (pr-str (get-in out [:out])) " — :APP-DECOY-stripped means it read "
               "app-db, :APP-DECOY-raw means it read app-db verbatim (no routing "
               "to runtime), nil means it forgot to strip the partition key")))))

;; ---------------------------------------------------------------------------
;; 2. EP-0001 §542-544 — the dirty-check keys on BOTH partitions.
;;
;; app-db is passed VALUE-IDENTICAL (literally the same map) across all three
;; drains; ONLY runtime-db changes. A runtime-only change MUST force a recompute
;; (drain 2), and a runtime value-equal re-drive MUST skip (drain 3). If the
;; resolved runtime value were absent from the dirty-check `:input-values`
;; vector, drain 2 would wrongly skip (app-db value-identical) — the bug the
;; §542-544 contract forbids.
;; ---------------------------------------------------------------------------

(deftest runtime-only-change-recomputes-while-app-db-value-identical
  (testing "runtime-db change with app-db value-identical recomputes; runtime value-equal re-drive skips"
    (let [calls (atom [])]
      (rf/reg-flow :route-slug
        {:inputs      [[:rf.db/runtime :rf.runtime/routing :current :route-id]]
         :output-path [:derived :slug]}
        (fn [route-id] (swap! calls conj route-id) route-id))
      (let [;; The SAME app-db value handed to every drain — value-identical, so
            ;; the app-db partition can never be what triggers a recompute.
            app-db {:unrelated 1}
            rt-v1  {:rf.runtime/routing {:current {:route-id :home}}}
            rt-v2  {:rf.runtime/routing {:current {:route-id :about}}}
            db1    (flows/run-flows-on-db :rf/default app-db rt-v1)
            _      (reset! *captured* [])
            db2    (flows/run-flows-on-db :rf/default app-db rt-v2)
            computed-2 (by-op :rf.flow/computed)
            _      (reset! *captured* [])
            db3    (flows/run-flows-on-db :rf/default app-db rt-v2)
            skip-3 (by-op :rf.flow/skip)]
        (is (= :home (get-in db1 [:derived :slug]))
            "drain 1 computed the flow onto the runtime-db value V1")
        (is (= :about (get-in db2 [:derived :slug]))
            "drain 2 recomputed onto the CHANGED runtime-db value V2 — app-db was value-identical")
        (is (= [:home :about] @calls)
            (str "the :derive fn fired on BOTH drains — a runtime-only change "
                 "forced the recompute the EP-0001 §542-544 both-partitions "
                 "dirty-check requires. [:home] alone means the resolved runtime "
                 "value is missing from the dirty vector (regression ships green)"))
        (is (= 1 (count computed-2))
            "drain 2 emitted :rf.flow/computed — the recompute is observable on the trace bus")
        ;; drain 3: runtime-db value-equal to drain 2 (and app-db still identical)
        ;; → the dirty-check MUST skip (proves it is genuinely keying, not
        ;; unconditionally recomputing).
        (is (= [:home :about] @calls)
            "the value-equal runtime re-drive did NOT recompute (:derive not re-invoked)")
        (is (= 1 (count skip-3))
            "drain 3 emitted :rf.flow/skip — value-equal inputs across BOTH partitions suppress recompute")
        ;; A skip returns the passed db UNCHANGED (no re-derive, no re-write) —
        ;; run-flows-on-db does not re-materialise the prior output. Output
        ;; PERSISTENCE across events is the router/commit concern, not this
        ;; drain's; here we only assert the skip left the passed db untouched.
        (is (= app-db db3)
            "drain 3 returned the passed app-db value unchanged (skip does not re-write)")))))

;; ---------------------------------------------------------------------------
;; 3. Mixed app-db + runtime inputs resolve in DECLARATION ORDER, each against
;;    its own partition. `read-inputs` maps `resolve-input` over `:inputs` in
;;    order; the resolver picks the partition per path. An out-of-order or
;;    partition-swapped resolution changes the vector handed to `:derive`.
;; ---------------------------------------------------------------------------

(deftest mixed-app-db-and-runtime-inputs-resolve-in-declaration-order
  (testing "interleaved app-db / runtime-db / app-db inputs resolve in order, each from its partition"
    (rf/reg-flow :mixed
      {:inputs      [[:app-first]
                     [:rf.db/runtime :rt :mid]
                     [:app-last]]
       :output-path [:combined]}
      (fn [a rt b] [a rt b]))
    (let [app-db     {:app-first :A :app-last :B}
          runtime-db {:rt {:mid :R}}
          out        (flows/run-flows-on-db :rf/default app-db runtime-db)]
      (is (= [:A :R :B] (get-in out [:combined]))
          (str "read-inputs resolves each input against its declared partition "
               "IN DECLARATION ORDER: app-db :app-first (:A), runtime-db :rt/:mid "
               "(:R), app-db :app-last (:B). Got " (pr-str (get-in out [:combined])))))))

;; ---------------------------------------------------------------------------
;; 4. elide-inputs seeds the STRIPPED declaration path.
;;
;; A runtime-qualified input `[:rf.db/runtime :rt :val]` reads its value from
;; runtime-db at `[:rt :val]`, and the frame's elision registry keys its
;; declaration at that STRIPPED `[:rt :val]` path (the registry is
;; partition-blind). `elide-inputs` normalizes the per-input trace `:path`
;; through `registry/input-resolve-path`, so the `:rf.flow/computed`
;; `:input-values` entry for the runtime input elides against the runtime-db
;; slot's declaration. Seeding the raw `[:rf.db/runtime :rt :val]` path would
;; miss the declaration and surface the value RAW — this test fails loudly if
;; that regresses.
;; ---------------------------------------------------------------------------

(deftest runtime-input-trace-value-elides-at-stripped-declaration-path
  (testing ":rf.flow/computed :input-values elides a runtime-qualified input against its STRIPPED declaration path"
    (rf/reg-flow :route-blob
      {:inputs      [[:rf.db/runtime :rt :val]]
       :output-path [:out]}
      (fn [v] v))
    ;; Declare the STRIPPED runtime-db slot large in the frame's elision
    ;; registry (frame-scoped, lives in the frame's runtime-db partition).
    (install-large! :rf/default [:rt :val])
    ;; Drive with a runtime-db that carries BOTH the elision registry (from
    ;; install-large!) and the actual value at the stripped path — the realistic
    ;; single-partition shape a real drain would present.
    (let [runtime-db (assoc-in (frame/frame-runtime-db-value :rf/default)
                               [:rt :val] {:big "payload"})]
      (reset! *captured* [])
      (flows/run-flows-on-db :rf/default {} runtime-db)
      (let [ev            (last (by-op :rf.flow/computed))
            input-values  (:input-values (:tags ev))
            [first-input] input-values]
        (is (some? ev) ":rf.flow/computed fired")
        (is (vector? input-values)
            ":input-values preserves the per-input slot shape")
        (is (elision/marker? first-input)
            (str "the runtime-qualified input value is replaced by the wire "
                 "marker — proving the declaration at the stripped path matched. "
                 "Got " (pr-str first-input) " (a raw {:big \"payload\"} means "
                 "elide-inputs seeded the un-stripped [:rf.db/runtime …] path and "
                 "surfaced the value RAW)"))
        (let [marker (:rf.size/large-elided first-input)]
          (is (= [:rt :val] (:path marker))
              "the marker carries the STRIPPED declaration path [:rt :val], not the raw [:rf.db/runtime :rt :val]"))))))
