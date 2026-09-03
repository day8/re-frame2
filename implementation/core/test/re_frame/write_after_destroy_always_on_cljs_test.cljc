(ns re-frame.write-after-destroy-always-on-cljs-test
  "EP-0008 / rf2-500ech — `:rf.warning/write-after-destroy` promoted to
  `:rf.error/write-after-destroy` on the ALWAYS-ON axis.

  The `substrate/adapter.cljc` `replace-container!` choke point is the
  single place every frame `:db` write flows through (router `:db` commit,
  drain rollback, flows, epoch restore, SSR write paths). When a scheduled
  drain races frame destruction the container goes nil and the write is
  silently dropped. Previously that surfaced only as a DCE'd
  `:rf.warning/write-after-destroy` dev trace — invisible in production —
  even though the SAME destroy-race surfaces production-survivably as
  `:rf.error/frame-destroyed` on the dispatch / subscribe paths (the calls
  disagreed).

  This test pins the promotion's acceptance leg (per EP §Conformance —
  every always-on category is exercised through `register-error-listener!`,
  proving production survival):

    (a) a nil-container `replace-container!` fans a
        `:rf.error/write-after-destroy` record out through the corpus-wide
        `register-error-listener!` substrate — the ALWAYS-ON axis, NOT
        gated by `rf.interop/debug-enabled?`, so it survives `:advanced` +
        `goog.DEBUG=false`.
    (b) the record carries `:recovery :ignored` (the write is dropped, the
        frame is gone — mirroring `:rf.error/frame-destroyed`'s posture)
        and a structured-only `:reason` (no raw values).
    (c) the underlying adapter `replace-container!` is NOT invoked (the
        write is dropped, not forwarded).

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build AND the JVM `clojure -M:test` runner both pick it up. The choke
  point is plain CLJC; no DOM dependency.

  ## Posture split (rf2-d2841)

  Legs (a) and (c) are the promotion's whole point and are ALREADY posture-
  independent — they read the corpus-wide `:errors` registry and an adapter
  tripwire, neither of which is gated. They run under
  `scripts/test-core-prod-gate.sh` unchanged.

  Leg (b) is the DEV COMPANION by construction: the ns docstring above calls
  it \"DCE'd in prod\". Its assertions are kept verbatim inside a
  `(when rf.interop/debug-enabled? …)` arm marked `rf2-d2841`, with an always-on
  witness for the same call kept outside — this file's thesis is precisely
  that the dev trace and the always-on record fire TOGETHER off one choke
  point, so a deftest that had nothing to say in production posture would be
  the wrong shape for it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; ---------------------------------------------------------------------------
;; Fixture — fresh registrar + plain-atom adapter per test; the always-on
;; error-listener registry (a `defonce` atom) cleared so a listener from one
;; test cannot leak into the next.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.error-emit/clear-error-listeners!))}))

;; ===========================================================================
;; (a) + (b) The dropped write rides the ALWAYS-ON axis with the promoted
;; category, recovery, and structured-only payload.
;; ===========================================================================

(deftest nil-container-write-fans-out-on-always-on-axis
  (testing "Per rf2-500ech / Spec 009 §Error event catalogue: a
            `replace-container!` against a nil container (the scheduled-
            drain-vs-frame-destruction race) fans ONE
            `:rf.error/write-after-destroy` record out through the
            corpus-wide `register-error-listener!` substrate — the always-on
            axis that survives `goog.DEBUG=false` — NOT the DCE'd dev trace."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; The real production path: every frame :db write flows through this
      ;; choke point; a destroyed frame's container has gone nil.
      (rf.substrate.adapter/replace-container! nil {:dropped :write})
      (let [reports (filter #(= :rf.error/write-after-destroy (:error %)) @seen)]
        (is (= 1 (count reports))
            "exactly ONE always-on record for the dropped write")
        (let [r (first reports)]
          (is (= :rf.error/write-after-destroy (:error r))
              "the promoted error category — not the retired warning")
          (is (nil? (:event r))
              "no event vector — a dropped write, not a throw on a dispatch")
          (is (nil? (:frame r))
              "no frame — it was destroyed (the whole point of the race)")
          (is (nil? (:exception r))
              "no exception — a suppressed write, not a throw")
          (is (number? (:time r)) ":time is a wall-clock millis number"))))))

;; ===========================================================================
;; (b) The dev error trace KEEPS the structured-only payload (recovery,
;; reason) — visible in dev, DCE'd in prod, complementary to the always-on
;; record above.
;; ===========================================================================

(deftest nil-container-write-keeps-structured-dev-trace
  (testing "Per rf2-500ech: the dev error trace stays at the choke point
            (in-process tooling surface, DCE'd in prod) carrying the promoted
            category, `:recovery :ignored`, and a structured-only `:reason`
            (no raw values)."
    (let [traces (atom [])
          always (atom [])]
      (rf/register-listener! :trace  ::rec        (fn [ev]  (swap! traces conj ev)))
      (rf/register-listener! :errors ::rec-errors (fn [rec] (swap! always conj rec)))
      (try
        (rf.substrate.adapter/replace-container! nil {:dropped :write})
        (finally
          (rf/unregister-listener! :trace  ::rec)
          (rf/unregister-listener! :errors ::rec-errors)))
      ;; ALWAYS-ON WITNESS (rf2-d2841). This deftest's thesis is that the dev
      ;; trace is the COMPANION of the always-on record off ONE choke point,
      ;; so the companion claim needs the always-on half present in the same
      ;; body. Under the production gate this is all that remains, and it is
      ;; the half that matters: one record, one call.
      (is (= 1 (count (filter #(= :rf.error/write-after-destroy (:error %)) @always)))
          "the same choke-point call fans exactly one always-on record")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
      ;; The trace surface is live in dev (this runner); under :advanced +
      ;; goog.DEBUG=false — and under -Dre-frame.debug=false — it DCEs.
      (when rf.interop/debug-enabled?
        (let [errs (filter #(= :rf.error/write-after-destroy (:operation %)) @traces)]
          (is (= 1 (count errs))
              "one dev error trace for the dropped write")
          (let [ev (first errs)]
            ;; `:recovery` is HOISTED to the envelope top-level by
            ;; `build-event`; the structured `:reason` stays under `:tags`.
            (is (= :ignored (:recovery ev))
                ":recovery :ignored — write dropped, frame gone (mirrors frame-destroyed)")
            (is (string? (get-in ev [:tags :reason]))
                ":reason is a structured human-readable sentence (no raw values)")))))))

;; ===========================================================================
;; (c) The underlying adapter replace-container! is NOT invoked — the write
;; is dropped, not forwarded to a (nil) container.
;; ===========================================================================

(deftest nil-container-write-does-not-forward-to-adapter
  (testing "Per rf2-500ech / 006 §replace-container!: the nil guard runs
            BEFORE the adapter lookup, so the underlying adapter's
            `replace-container!` is never invoked (a nil-container write
            would otherwise NPE on a background thread — the rf2-ft2b
            reproducer)."
    (let [forwarded   (atom false)
          ;; A tripwire adapter: its replace-container! flips the flag if it
          ;; is ever reached. The nil guard runs BEFORE the adapter lookup,
          ;; so a correct choke point never invokes it on a nil container.
          tripwire     (assoc rf.substrate.plain-atom/adapter
                              :replace-container!
                              (fn [_ _] (reset! forwarded true)))]
      ;; Swap the fixture-installed adapter for the tripwire (the fixture's
      ;; teardown will dispose whatever is installed at end-of-test).
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! tripwire)
      (rf.substrate.adapter/replace-container! nil {:dropped :write})
      (is (false? @forwarded)
          "the underlying adapter replace-container! was NOT invoked"))))

;; ===========================================================================
;; The late-bind hook the substrate reaches error-emit through is published.
;; ===========================================================================

(deftest dispatch-on-error-late-bind-hook-is-published
  (testing "Per rf2-500ech: the substrate cannot static-require
            `re-frame.error-emit` (load order), so it reaches
            `dispatch-on-error!` via the `:error-emit/dispatch-on-error`
            late-bind hook — which error-emit publishes at ns-load, so the
            lookup never misses in production."
    (is (some? (rf.late-bind/get-fn :error-emit/dispatch-on-error))
        "the hook is registered at error-emit ns-load")))
