(ns re-frame.epoch-jvm-prod-gate-test
  "rf2-sk5hf — READ THIS FIRST. Despite the namespace's name, this suite is
  NOT THE LOAD-TIME GATE. Every test below rebinds
  `re-frame.interop/debug-enabled?` with `with-redefs`, which happens after the
  framework has loaded; the gate itself is read ONCE at `re-frame.interop` load
  time from `-Dre-frame.debug` / `RE_FRAME_DEBUG`, long before any of this
  runs. What is pinned here is that the epoch surfaces honour a REBOUND flag —
  a real contract, and not the production posture.

  rf2-9c2jf is why the distinction is worth a paragraph: a TOTAL
  `dispatch-sync` failure under the documented gate, handler run ZERO times,
  green for as long as it existed, camouflaged by a roster of suites whose
  NAMES said `prod_gate` while not one of them ran under it.

  The lanes that DO reach the load-time gate:

    * `jvm-core-prod-gate` / `sh scripts/test-core-prod-gate.sh` — the core
      suite with the `re-frame.debug` property genuinely set false on the JVM
      command line, and `test-routing-prod-gate.sh` /
      `test-ssr-prod-gate.sh` for those artefacts.

      (Spelled without the literal `-D...=false` on purpose: that literal is
      itself one of the three honesty markers the drift ratchet greps for, and
      a file that mentions it only in PROSE ABOUT OTHER LANES would pass for
      the wrong reason. This file's honesty rests on the disclaimer sentence
      above, and on nothing else.)
    * `re-frame.prod-gate-lane-pin-test` and its per-artefact siblings — each
      asserts the property really arrived in that lane's JVM and that the
      framework honoured it.
    * `re-frame.prod-gate-dispatch-jvm-test` — the child-JVM pattern for a
      defect that only reproduces at load time.

  THE EPOCH ARTEFACT NOW HAS SUCH A LANE (rf2-bo8lq). `:prod-gate` in
  `implementation/epoch/deps.edn`, `scripts/test-epoch-prod-gate.sh`, the
  `jvm-epoch-prod-gate` job and `re-frame.epoch-prod-gate-lane-pin-test` did not
  exist when the paragraph above was written; the surfaces below had then never
  executed under the posture they are about, which bit harder here than
  elsewhere, the whole security rationale (rf2-vnjfg / rf2-0la4f) being that the
  ring must not retain `:db-before` / `:db-after` / raw `:trace-events` in a
  production heap.

  THIS FILE'S OWN DISCLAIMER STILL STANDS, and the distinction is worth keeping
  straight now that both things are true at once. Every deftest below still
  rebinds the Var, so this file still does not reach the load-time gate on its
  own. What changed is that ten of its twelve deftests are now ALSO executed by
  the lane above, in a JVM where the property is genuinely on the command line —
  so the same assertions are made twice, once against a rebound flag and once
  against a real one. The two remaining deftests are `^:requires-debug`: they
  assert epoch's DEV parity, which the production lane by definition cannot.

  Stated precisely, since the loose version of this sentence caused the original
  confusion: epoch reads the gate only as `(when interop/debug-enabled? …)`
  inside fn bodies, which is a runtime Var deref, so the rebinds below do reach
  epoch's own gated branches — this suite was never vacuous. What a rebind
  cannot reach is what the framework decided while it LOADED under the dev
  default: top-level registrations, `defonce` initialisation, interceptor chains
  composed once at load. That is the half the lane adds, and the half rf2-9c2jf
  lived in.

  HOW THIS WENT UNSAID FOR SO LONG, worth recording because the mechanism is
  general. rf2-f7qj4 re-docstringed the three core suites in exactly this shape
  and wrote a ratchet — `re-frame.prod-gate-naming-drift-test` — to stop the
  next one. That ratchet enumerated core's test tree only, so it never saw this
  file; `re-frame.interop-debug-gate-test`'s docstring meanwhile asserted that
  the epoch suite \"carries the same caveat\", which was simply not true. A
  cross-reference is not a check. rf2-sk5hf widened the walk to every
  artefact's `test/` tree, and this file is the one thing it found.

  ## What this suite pins

  The epoch artefact's dev-only surfaces — `register-epoch-listener!`,
  `restore-epoch!`, `replace-frame-state!`,
  the per-frame ring buffer carrying `:db-before` / `:db-after` /
  raw `:trace-events` — MUST honour the JVM-side production gate
  `re-frame.interop/debug-enabled?`. When the gate reads `false`
  in production, the epoch
  surface drops to its no-op floor: no record lands in the ring, no
  callback fires, and `restore-epoch!` / `replace-frame-state!` return `false`.

  The companion core gate vocabulary suite is
  `re-frame.interop-debug-gate-test`; the core integration suite is
  `re-frame.jvm-prod-gate-integration-test`. This file is the epoch
  artefact's contribution.

  ## Why every negative assertion below is paired with a WITNESS (rf2-t7qh8)

  Almost everything this suite claims is an ABSENCE — an empty ring, a silent
  listener, an uninvoked `:redact-fn`, a warning that never fired. An absence is
  satisfied by two different worlds: the gate elided the recording (the claim),
  or the dispatch never happened at all (a defect). rf2-9c2jf was the second
  world — `dispatch-sync` running its handler ZERO times under the documented
  gate — and a bare `(is (empty? …))` cannot tell them apart. It reports green
  for both, which is what let rf2-9c2jf live.

  So each of these deftests asserts, beside the absence, that the dispatch it is
  reasoning about actually landed: the handler's app-db write is read back
  through `app-db-of`. That converts \"nothing was recorded\" from an
  unfalsifiable statement into a conditional one — the run did the work AND
  epoch kept none of it. Do not delete a witness to \"simplify\" a test; the
  witness is the half that makes the other half mean something."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Side-effect require: machines publishes the late-bind hook
            ;; (see epoch_test.clj for the same dance).
            [re-frame.machines]))

;; rf2-yw1w1u — canonical capture/restore fixture. Snapshots the
;; registrar at ns-load + restores around each test, fires the epoch
;; reset-hook table (history / listeners / config-to-default), and the
;; `:init-fn` re-applies the suite's non-default `:trace-events-keep 5`
;; (NOT the shipped 50 = :depth; Mike pair-debug 2026-05-27) through the
;; public `configure!` boundary — no test ns reaches into the private
;; `state/config` var. The `:init-fn` runs OUTSIDE each test's
;; `(with-redefs [interop/debug-enabled? false] ...)`, so config lands at
;; the normal gate value.
;;
;; EP-0002 (rf2-9o48ih / rf2-nn0jqa): `init!` no longer synthesises
;; `:rf/default`. The canonical fixture, when handed an `:adapter`, ALSO
;; ensures the conventional `:rf/default` frame and binds it as the body's
;; ambient scope — the carried-invariant equivalent of wrapping every test
;; in `(with-frame :rf/default …)`. So the bare framework-operation surfaces
;; this suite drives (dispatch / epoch / restore / frame-state replacement)
;; resolve a carried frame stamp without a hand-rolled `make-frame` + `with-
;; frame` dance here. Explicit `{:frame …}` opts in the bodies still win.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; rf2-t7qh8 — the witness read. See the docstring section above: it is what
;; separates "epoch recorded nothing" from "nothing happened".
(defn- app-db-of [frame-id]
  (:rf.db/app (rf/frame-state-value frame-id)))

(deftest epoch-history-inert-when-debug-disabled
  (testing "Per rf2-0la4f: when the JVM debug gate reads false, the
            per-frame epoch ring stays empty regardless of how many
            events drain. No `:db-before` / `:db-after` /
            `:trace-events` payloads land in heap memory — the
            primary motivating concern of the audit (tokens / PII /
            secrets retained in SSR process memory) is addressed."
    (with-redefs [rf.interop/debug-enabled? false]
      (rf/reg-event :prod-gate.epoch/inc
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate.epoch/inc])
      (rf/dispatch-sync [:prod-gate.epoch/inc])
      (rf/dispatch-sync [:prod-gate.epoch/inc])
      (is (= 3 (:n (app-db-of :rf/default)))
          "WITNESS: all three dispatches ran their handler and committed —
           so the empty ring below is elision, not a dead dispatch loop")
      (is (empty? (rf.epoch/epoch-history :rf/default))
          "epoch ring is empty under disabled debug gate"))))

(deftest epoch-cb-silent-when-debug-disabled
  (testing "Per rf2-0la4f: a registered epoch listener does NOT
            fire under the disabled debug gate. No record fan-out
            means no tool/plugin callback in-process can observe
            `:db-before` / `:db-after` / raw trace vectors."
    (with-redefs [rf.interop/debug-enabled? false]
      (let [seen (atom [])]
        (rf.epoch/register-epoch-listener!
          :prod-gate.epoch/recorder
          (fn [record] (swap! seen conj record)))
        (rf/reg-event :prod-gate.epoch/silent
                         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
        (rf/dispatch-sync [:prod-gate.epoch/silent])
        (is (= 1 (:n (app-db-of :rf/default)))
            "WITNESS: the dispatch ran — the silent listener below had a real
             cascade to miss")
        (is (empty? @seen)
            "epoch listener silent under disabled debug gate")))))

(deftest restore-epoch-refuses-when-debug-disabled
  (testing "Per rf2-0la4f: `restore-epoch!` MUST refuse to operate
            when the JVM debug gate is off. The state-rewrite admin
            surface is dev-only; SSR production processes do NOT
            give arbitrary in-process code the ability to mutate
            `app-db` out of band."
    (with-redefs [rf.interop/debug-enabled? false]
      (is (false? (rf/restore-epoch! :rf/default :some-epoch-id))
          "restore-epoch! returns false (refuses to operate)"))))

(deftest replay-epoch-refuses-when-debug-disabled
  (testing "rf2-ov144: `replay-epoch!` is gated exactly like `restore-epoch!`
            — under the disabled gate it returns `false`, resolves no record
            and dispatches nothing. The dispatch-shaped time-travel surface
            is dev-only for the same reason the state-rewrite one is."
    (rf/reg-event :prod-gate.epoch/replay-probe
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    ;; WITNESS: the probe ran once. Under the dev gate that also retains a
    ;; record (so the refusal below is exercised against a RETAINED id);
    ;; under the real production-gate lane the ring stays empty by design
    ;; and the id falls back to an unknown one — both must refuse.
    (rf/dispatch-sync [:prod-gate.epoch/replay-probe])
    (is (= 1 (:n (app-db-of :rf/default)))
        "WITNESS: the probe handler ran — there is a real dispatch to refuse to replay")
    (let [recorded-id (or (:epoch-id (last (rf/epoch-history :rf/default)))
                          :some-epoch-id)]
      (with-redefs [rf.interop/debug-enabled? false]
        (is (false? (rf/replay-epoch! :rf/default recorded-id))
            "replay-epoch! returns false (refuses to operate)")
        (is (false? (rf/replay-epoch! :rf/default :some-epoch-id {:origin :pair}))
            "replay-epoch! returns false for the opts arity too — no envelope leaks"))
      (is (= 1 (:n (app-db-of :rf/default)))
          "the probe handler did NOT run again — nothing was dispatched"))))

(deftest replace-app-db-refuses-when-debug-disabled
  (testing "`replace-frame-state!` must refuse to operate
            when the JVM debug gate is off. Same admin-surface
            concern as `restore-epoch!` — pair-tool writes (Tool-Pair
            §Pair-tool writes) are a dev-only surface."
    (with-redefs [rf.interop/debug-enabled? false]
      (is (false? (rf/replace-frame-state! :rf/default {:rf.db/app {:any "db"}}))
          "replace-frame-state! returns false (refuses to operate)"))))

;; rf2-bo8lq — `^:requires-debug` (core's existing dev-only declaration,
;; rf2-d2841). This deftest is a statement about the DEV posture, so the epoch
;; production-gate lane excludes it (`-e :requires-debug` in the `:prod-gate`
;; alias). Measured, not guessed: under a real load-time `-Dre-frame.debug=false`
;; it is one of exactly two reds in this namespace, and both are these dev-parity
;; sanity tests. A `(when interop/debug-enabled? …)` posture arm would be the
;; WRONG repair — it would leave a deftest with no assertion at all under the
;; gate, reporting green for a run that executed nothing, which is the false
;; green this programme exists to close. It still runs, exactly once, in
;; `clojure -M:test`.
(deftest ^:requires-debug epoch-still-records-with-default-gate
  (testing "Sanity: with the gate at its default `true` reading
            (dev parity), epoch recording continues to work. This
            test fails fast if a future refactor accidentally
            disables the surface in dev."
    (rf/reg-event :prod-gate.epoch/dev-inc
                     (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:prod-gate.epoch/dev-inc])
    (is (pos? (count (rf.epoch/epoch-history :rf/default)))
        "epoch ring has at least one record under default gate")))

;; ---- rf2-vq5o0 privacy-surface JVM false-path coverage ------------------

(deftest projected-history-empty-under-disabled-gate
  (testing "Per rf2-mrsck / rf2-vq5o0: with the JVM debug gate off,
            no records land in the ring, so projected-history reads
            the empty vector. The projection surface composes with the
            production-elision gate at the upstream (record assembly)
            seam; the projection itself is a pure data transform that
            no consumer can reach a record through under the disabled
            gate."
    (with-redefs [rf.interop/debug-enabled? false]
      (rf/reg-event :prod-gate.priv/silent
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate.priv/silent])
      (is (= 1 (:n (app-db-of :rf/default)))
          "WITNESS: the dispatch ran — nothing reached the ring for the
           projection to read")
      (is (= [] (rf.epoch/projected-history :rf/default))
          "empty projected-history under the disabled gate"))))

(deftest sensitive-rollup-not-computed-under-disabled-gate
  (testing "Per rf2-mrsck: the sensitive rollup is computed once per
            assembled record (in build-record). The gate-disabled
            path elides record assembly entirely; the rollup never
            runs. We verify by asserting the ring stays empty — no
            record means no rollup compute path was reached."
    (with-redefs [rf.interop/debug-enabled? false]
      (rf/reg-event :prod-gate.priv/sensitive
                       {:sensitive? true}
                       (fn [{:keys [db]} _] {:db (assoc db :token "shh")}))
      (rf/dispatch-sync [:prod-gate.priv/sensitive])
      (is (= "shh" (:token (app-db-of :rf/default)))
          "WITNESS: the `:sensitive?`-flagged handler ran and wrote the token —
           so the absent rollup below is elision, not an absent event")
      (is (empty? (rf.epoch/epoch-history :rf/default))
          "no record assembled — rollup never reached"))))

;; ---- EP-0015 §15 / open-issue 6 :redact-fn surface JVM coverage ----------
;;
;; The `:redact-fn` is the PROJECTION-SIDE advanced override, never a
;; storage-side mutation. It is NEVER invoked by `settle!` /
;; frame-state replacement / back-fill (regardless of gate); it runs only
;; inside `projected-record`, applied to the projected egress copy. The
;; ring is causal replay material, delivered raw.

(deftest redact-fn-never-invoked-at-storage-under-disabled-gate
  (testing "Per EP-0015 §15 + open-issue 6: with the JVM debug gate off,
            `settle!` elides record assembly entirely AND the :redact-fn
            is a projection-side hook anyway — so an installed :redact-fn
            is NEVER invoked along the dispatch/storage path. An app that
            ships a `:redact-fn` and flips the gate to false in production
            pays zero invocation cost."
    (with-redefs [rf.interop/debug-enabled? false]
      (let [invocations (atom 0)]
        (rf/configure! {:epoch-history {:redact-fn (fn [r]
                                    (swap! invocations inc)
                                    r)}})
        (rf/reg-event :prod-gate.redact/inc
                         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
        (rf/dispatch-sync [:prod-gate.redact/inc])
        (rf/dispatch-sync [:prod-gate.redact/inc])
        (rf/dispatch-sync [:prod-gate.redact/inc])
        (is (= 3 (:n (app-db-of :rf/default)))
            "WITNESS: all three dispatches ran — the zero invocation count
             below is a hook that was never on the path, not a path never taken")
        (is (zero? @invocations)
            ":redact-fn was never called along the storage path — it is a
             projection-side hook, and record assembly is elided anyway")
        (is (empty? (rf.epoch/epoch-history :rf/default))
            "no record assembled under the disabled gate")
        (rf/configure! {:epoch-history {:redact-fn nil}})))))

;; rf2-bo8lq — `^:requires-debug`, for the reason recorded at
;; `epoch-still-records-with-default-gate` above: its second assertion needs the
;; ring to hold a record for `projected-record` to fire the override, and under
;; the real load-time gate the ring is never filled.
(deftest ^:requires-debug redact-fn-not-invoked-at-storage-under-default-gate
  (testing "Per EP-0015 §15 + open-issue 6: even under the DEFAULT-TRUE
            gate, dispatching does NOT invoke the :redact-fn — it is no
            longer a storage-side hook. The ring record is RAW; the
            override fires only when `projected-record` is called."
    (let [invocations (atom 0)]
      (rf/make-frame {:id :prod-gate.dev/frame})
      (rf/configure! {:epoch-history {:redact-fn (fn [r] (swap! invocations inc) r)}})
      (rf/reg-event :prod-gate.redact/dev-inc
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate.redact/dev-inc] {:frame :prod-gate.dev/frame})
      (is (zero? @invocations)
          ":redact-fn NOT invoked by settle — it is projection-side only")
      ;; Projecting the recorded record IS where the override fires.
      (rf.epoch/projected-record (last (rf.epoch/epoch-history :prod-gate.dev/frame)))
      (is (pos? @invocations)
          ":redact-fn fires when projected-record is called (the egress
           override), under the default gate")
      (rf/configure! {:epoch-history {:redact-fn nil}}))))

(deftest redact-fn-not-invoked-on-replace-app-db-under-disabled-gate
  (testing "`replace-frame-state!` returns false under the disabled gate — the
            gated arm that would record the synthetic epoch is elided. The
            :redact-fn (projection-side) is never reached on this path
            regardless; the early-return false is preserved."
    (with-redefs [rf.interop/debug-enabled? false]
      (let [invocations (atom 0)]
        (rf/configure! {:epoch-history {:redact-fn (fn [r]
                                    (swap! invocations inc)
                                    r)}})
        (is (false? (rf/replace-frame-state! :rf/default {:rf.db/app {:any "db"}}))
            "replace-frame-state! refuses under the disabled gate")
        (is (zero? @invocations)
            ":redact-fn was never reached — no synthetic record recorded
             (and the fn is projection-side anyway)")
        (rf/configure! {:epoch-history {:redact-fn nil}})))))

(deftest redact-fn-warning-not-emitted-on-storage-path
  (testing "Per EP-0015 §15 + open-issue 6: a throwing :redact-fn cannot
            emit `:rf.warning/epoch-redact-fn-exception` along the
            dispatch/storage path — the warning is sourced inside
            `apply-redact-fn`'s try/catch, which runs ONLY at projection
            time, never at settle. Pinned so a future refactor that
            re-attaches redaction to the storage seam would break visibly."
    (let [warnings (atom [])]
      (rf/make-frame {:id :prod-gate.throw/frame})
      (rf/register-listener! :trace ::warn-watch
                             (fn [ev]
                               (when (= :warning (:op-type ev))
                                 (swap! warnings conj ev))))
      (rf/configure! {:epoch-history {:redact-fn (fn [_r] (throw (ex-info "boom" {})))}})
      (rf/reg-event :prod-gate.redact/throw
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate.redact/throw] {:frame :prod-gate.throw/frame})
      (rf/dispatch-sync [:prod-gate.redact/throw] {:frame :prod-gate.throw/frame})
      (is (= 2 (:n (app-db-of :prod-gate.throw/frame)))
          "WITNESS: both dispatches ran with a THROWING :redact-fn installed —
           the absent warning below is a hook never reached at settle, not a
           dispatch that never happened")
      (let [redact-warns (filter (fn [ev]
                                   (= :rf.warning/epoch-redact-fn-exception
                                      (:operation ev)))
                                 @warnings)]
        (is (empty? redact-warns)
            ":rf.warning/epoch-redact-fn-exception never fires on the
             storage path — apply-redact-fn runs only at projection time"))
      (rf/unregister-listener! :trace ::warn-watch)
      (rf/configure! {:epoch-history {:redact-fn nil}}))))

(deftest projected-record-pure-transform-survives-disabled-gate
  (testing "Per rf2-vq5o0: projected-record is a pure data transform
            — it does NOT consult interop/debug-enabled?. A consumer
            that already holds a record (replayed in a JVM test
            fixture, or surfaced from a recorded session) can still
            project it. The gate elides record ASSEMBLY, not record
            PROJECTION."
    (let [synthetic-record
          {:epoch-id      42
           :frame         :test/main
           :committed-at  0
           :event-id      :synthetic
           :trigger-event [:synthetic]
           :db-before     {:n 0}
           :db-after      {:n 1}
           :outcome       :ok
           :rf.epoch/sensitive? false
           :trace-events  []
           :sub-runs      []
           :renders       []
           :effects       []}]
      (with-redefs [rf.interop/debug-enabled? false]
        (let [projected (rf.epoch/projected-record synthetic-record)]
          (is (some? projected)
              "projection runs even under the disabled gate")
          (is (= 42 (:epoch-id projected))
              "bookkeeping preserved"))))))
