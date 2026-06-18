(ns re-frame.ssr-hydration-mismatch-test
  "Per rf2-pxb7t · Wave 3 of rf2-tglku (Migration-Audit §ssr_hydration_mismatch).

  The pre-migration Playwright spec at
  `testbeds/ssr_hydration_mismatch/spec.cjs` walked the deliberate-
  mismatch path: bake a known-wrong `:rf/render-hash` (`\"deadbeef\"`)
  into the payload, hydrate, call `verify-hydration!` post-render,
  observe the captured `:rf.ssr/hydration-mismatch` trace's tag
  payload (`:server-hash`, `:client-hash`, `:failing-id`,
  `:recovery`), confirm the page stays interactive post-mismatch.

  Every load-bearing assertion is platform-neutral — the trace
  emission lives in `re-frame.ssr.hydrate/verify-hydration!`, which
  is `.cljc`. The DOM mirror (mismatch-banner) is observation-only.
  Migrated to JVM following the existing SSR-test conventions.

  ## Migration map (Migration-Audit.md §ssr_hydration_mismatch)

    spec.cjs assertion #2 (hydrated text = 'hydrated')
      → mismatch-hydrate-still-stashes-metadata-when-server-hash-set
    spec.cjs #4 (mismatch-server-hash = 'deadbeef')
      → mismatch-trace-carries-server-hash-failing-id-recovery
    spec.cjs #5 (mismatch-client-hash matches /^[0-9a-f]{8}$/)
      → mismatch-trace-client-hash-is-8-char-lowercase-hex
    spec.cjs #6 (client-hash != 'deadbeef')
      → mismatch-trace-client-hash-is-8-char-lowercase-hex
    spec.cjs #7 (failing-id = ':rf/hydrate')
      → mismatch-trace-carries-server-hash-failing-id-recovery
    spec.cjs #8 (recovery = ':warned-and-replaced')
      → mismatch-trace-carries-server-hash-failing-id-recovery
    spec.cjs #9 (window.__rf_trace_events has the mismatch with op_type :error)
      → mismatch-trace-is-an-error-op-type-event
    spec.cjs #10 (post-mismatch ::inc click works → count = '1')
      → mismatch-page-stays-interactive-post-mismatch

  Assertions #1 (`expectVisible(hydrated)`) and #3
  (`expectVisible(mismatch-banner)`) are pure DOM-mount probes — the
  Migration-Audit classifies them (C); per the rf2-pxb7t bead the
  whole `spec.cjs` is dropped and those two assertions retire
  alongside (substrate mount is covered by the 3 adapter smokes per
  the audit's §Drop-or-keep recommendation)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.subs :as subs]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; The payload the testbed's `<script id=\"__rf_payload\">` bakes verbatim
;; (testbeds/ssr_hydration_mismatch/index.html lines 49-54). The
;; "deadbeef" string is the known-wrong server-hash that will not
;; equal whatever the client tree's actual FNV-1a hash resolves to.
;; rf2-nv3mua: NO `:rf/frame-id` key — these tests dispatch the payload into a
;; freshly-`make-frame`'d `client-frame` (NOT `:rf/default`), and the
;; `:rf/hydrate` handler now fails CLOSED on a present-and-different
;; `:rf/frame-id` (the bug fix). The pre-fix literal `:rf/frame-id :rf/default`
;; would (correctly) be rejected as a frame-id mismatch against the synthetic
;; frame, short-circuiting the hash-mismatch path these tests exercise. An
;; absent frame-id is the documented no-conflict shape — the dispatch target
;; stands — which is what these render-hash-mismatch tests intend.
(def ^:private mismatch-payload
  {:rf/version     1
   :rf/render-hash "deadbeef"
   :rf/app-db      {:count 0}})

(defn- register-handlers! []
  (rf/reg-event ::inc
    (fn [{:keys [db]} _ev] {:db (update db :count (fnil inc 0))}))
  (rf/reg-sub :count     (fn [db _] (or (:count db) 0)))
  ;; EP-0001 (rf2-vzld77): the SSR hydration metadata is durable runtime-db state.
  (subs/reg-runtime-sub :hydrated? (fn [rt _] (boolean (get-in rt [:rf.runtime/ssr :hydration])))))

(defn- capture-traces!
  [f]
  (let [traces (atom [])
        cb-id  (gensym "::ssr-hydration-mismatch-capture-")]
    (rf/register-listener! :trace cb-id (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (rf/unregister-listener! :trace cb-id)))
    @traces))

(def ^:private hex-8-pattern #"^[0-9a-f]{8}$")

;; ===========================================================================
;; spec.cjs §(1) → hydration completes (metadata lands) even with the
;;                  deliberately-wrong server-hash
;; ===========================================================================

(deftest mismatch-hydrate-still-stashes-metadata-when-server-hash-set
  (testing "Migrated from testbeds/ssr_hydration_mismatch/spec.cjs
            assertion #2. :rf/hydrate is independent of
            verify-hydration!: the handler always replaces app-db
            and stashes the metadata, regardless of whether the
            payload's hash matches the client's eventual render.
            The mismatch is a downstream trace, not a hydration
            blocker (Spec 011 — degraded-but-running posture)."
    (register-handlers!)
    (let [client-frame (frame/make-frame {:doc "ssr-mismatch client frame"
                                       :platform :client})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})

      (is (true? (rf/subscribe-once client-frame [:hydrated?]))
          "post-hydrate :hydrated? reads true even though the baked
           hash will mismatch the (future) client render")
      (is (= "deadbeef"
             (get-in (rf/runtime-db-value client-frame)
                     [:rf.runtime/ssr :hydration :server-hash]))
          "the deliberately-wrong :rf/render-hash is stashed verbatim
           for verify-hydration! to pick up"))))

;; ===========================================================================
;; spec.cjs §(2) → the mismatch trace's tag payload
;; ===========================================================================

(deftest mismatch-trace-carries-server-hash-failing-id-recovery
  (testing "Migrated from testbeds/ssr_hydration_mismatch/spec.cjs
            assertions #4 (server-hash), #7 (failing-id), #8
            (recovery). Per Spec 011 §Hydration-mismatch detection
            the trace's `:tags` carry the structured shape: server-
            hash + client-hash + failing-id; the `:recovery` slot is
            hoisted to the trace envelope's top level (per Spec 009
            §Error event shape's recovery-hoist branch)."
    (register-handlers!)
    (let [client-frame   (frame/make-frame {:doc "ssr-mismatch client frame"
                                         :platform :client})
          ;; A second 8-hex string — anything other than \"deadbeef\".
          client-hash    "0badf00d"
          _              (rf/dispatch-sync [:rf/hydrate mismatch-payload]
                                           {:frame client-frame})
          traces         (capture-traces!
                           (fn []
                             (ssr/verify-hydration! client-frame
                                                    client-hash)))
          mismatches     (filter #(= :rf.ssr/hydration-mismatch (:operation %))
                                 traces)]
      (is (= 1 (count mismatches))
          (str "expected exactly one :rf.ssr/hydration-mismatch trace; saw: "
               (pr-str (mapv :operation traces))))
      (when (seq mismatches)
        (let [ev (first mismatches)]
          (is (= "deadbeef" (-> ev :tags :server-hash))
              ":tags :server-hash echoes the payload's known-wrong literal")
          (is (= client-hash (-> ev :tags :client-hash))
              ":tags :client-hash echoes the value we passed to
               verify-hydration!")
          (is (= :rf/hydrate (-> ev :tags :failing-id))
              ":tags :failing-id discriminator per Spec 011 v1
               (body-mismatch; head-mismatch reserved post-v1)")
          (is (= :warned-and-replaced (:recovery ev))
              ":recovery hoisted onto the envelope top-level
               (Spec 009 §Error event shape)"))))))

(deftest mismatch-trace-client-hash-is-8-char-lowercase-hex
  (testing "Migrated from testbeds/ssr_hydration_mismatch/spec.cjs
            assertions #5 (shape: 8-char lowercase hex) and #6
            (client-hash != server-hash). The trace's :client-hash
            tag echoes whatever verify-hydration! was given — for a
            real client tree we'd pass `(render-tree-hash tree)`
            which always emits 8-char lowercase hex per Spec 011
            §Hydration-mismatch detection. Here we hash a concrete
            input and lock the shape + the not-equal-deadbeef
            invariant."
    (register-handlers!)
    (let [client-frame (frame/make-frame {:doc "ssr-mismatch client frame"
                                       :platform :client})
          ;; A non-trivial hiccup tree — its computed hash is whatever
          ;; FNV-1a resolves to; we lock the shape and the not-equal
          ;; invariant, not the literal.
          render-tree  [:div {:data-testid "counter-panel"}
                        [:p "count=" [:span {:data-testid "count"} 0]]]
          client-hash  (rf/render-tree-hash render-tree)
          _            (rf/dispatch-sync [:rf/hydrate mismatch-payload]
                                         {:frame client-frame})
          traces       (capture-traces!
                         (fn []
                           (ssr/verify-hydration! client-frame
                                                  render-tree)))
          mismatch     (first (filter #(= :rf.ssr/hydration-mismatch
                                          (:operation %))
                                      traces))]
      (is (some? mismatch)
          ":rf.ssr/hydration-mismatch fires when the resolved tree
           hashes to anything other than 'deadbeef'")
      (when mismatch
        (let [observed (-> mismatch :tags :client-hash)]
          (is (and (string? observed) (= 8 (count observed)))
              (str "computed client-hash is exactly 8 chars; got "
                   (pr-str observed)))
          (is (re-matches hex-8-pattern observed)
              (str "computed client-hash is 8-char lowercase hex; got "
                   (pr-str observed)))
          (is (= client-hash observed)
              "the trace echoes the same hash render-tree-hash
               computes when called directly on the input")
          (is (not= "deadbeef" observed)
              "client-hash never equals the (deliberately wrong)
               server-hash — that would be a hash-collision spec
               violation"))))))

;; ===========================================================================
;; spec.cjs §(3) → the trace's :op-type is :error
;; ===========================================================================

(deftest mismatch-trace-is-an-error-op-type-event
  (testing "Migrated from testbeds/ssr_hydration_mismatch/spec.cjs
            assertion #9. Per Spec 009 §Error event shape +
            Spec 011 §Hydration-mismatch detection the mismatch
            event is a structured :error (the trace bus's
            error-emit path is the producer site — see
            `re-frame.ssr.hydrate/verify-hydration!`)."
    (register-handlers!)
    (let [client-frame (frame/make-frame {:doc "ssr-mismatch client frame"
                                       :platform :client})
          _            (rf/dispatch-sync [:rf/hydrate mismatch-payload]
                                         {:frame client-frame})
          traces       (capture-traces!
                         (fn []
                           (ssr/verify-hydration! client-frame "0badf00d")))
          mismatch     (first (filter #(= :rf.ssr/hydration-mismatch
                                          (:operation %))
                                      traces))]
      (is (some? mismatch))
      (when mismatch
        (is (= :error (:op-type mismatch))
            ":op-type is :error — Spec 009 categorisation")))))

;; ===========================================================================
;; spec.cjs §(4) → page is still interactive post-mismatch
;; ===========================================================================

(deftest mismatch-page-stays-interactive-post-mismatch
  (testing "Migrated from testbeds/ssr_hydration_mismatch/spec.cjs
            assertion #10. Per Spec 011 §Mismatch recovery and
            configuration the default recovery is :warned-and-
            replaced — the client renders against the seeded state
            and the dispatch pipeline stays live. The browser-side
            observation was a click-and-readback; the equivalent
            assertion here drives ::inc through `dispatch-sync`
            and reads :count via `subscribe-once`."
    (register-handlers!)
    (let [client-frame (frame/make-frame {:doc "ssr-mismatch client frame"
                                       :platform :client})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})

      (is (= 0 (rf/subscribe-once client-frame [:count]))
          "seeded :count post-hydrate matches the payload's :rf/app-db
           (= 0 on this surface — the payload didn't seed a higher
           value)")

      ;; Fire the mismatch trace (otherwise this test would pass
      ;; vacuously — we want to assert the dispatch survives
      ;; the recovery, not just that it works without one).
      (capture-traces!
        (fn [] (ssr/verify-hydration! client-frame "0badf00d")))

      (rf/dispatch-sync [::inc] {:frame client-frame})
      (is (= 1 (rf/subscribe-once client-frame [:count]))
          "post-mismatch ::inc dispatches through the live event-
           handler → db-update → sub-recompute pipeline; the
           warn-and-replace recovery is degraded-but-running, not
           crash"))))

;; ===========================================================================
;; rf2-ee38b.10 — frame `:ssr` hydration-mismatch config knobs
;; (:on-mismatch :hard-error strict mode + :detect-mismatch? false)
;; ===========================================================================

(deftest mismatch-strict-mode-throws-with-structured-payload
  (testing "rf2-ee38b.10 — a frame with :ssr {:on-mismatch :hard-error}
            escalates a detected mismatch to a thrown structured
            exception (Spec 011 §Mismatch recovery and configuration
            item 2). The thrown ex-info carries the same server/client
            hash + failing-id payload as the trace, and :recovery is
            :hard-error."
    (register-handlers!)
    (let [client-frame (frame/make-frame {:doc "ssr strict-mode frame"
                                       :platform :client
                                       :ssr {:on-mismatch :hard-error}})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})
      (let [thrown (try (ssr/verify-hydration! client-frame "0badf00d")
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown)
            "strict mode throws on a detected mismatch")
        (let [data (ex-data thrown)]
          (is (= :rf.ssr/hydration-mismatch (:rf.error/id data))
              "the thrown exception is the structured hydration-mismatch")
          (is (= "deadbeef" (:server-hash data)))
          (is (= "0badf00d" (:client-hash data)))
          (is (= :rf/hydrate (:failing-id data)))
          (is (= :hard-error (:recovery data))
              ":recovery reflects the strict-mode escalation"))))))

(deftest mismatch-strict-mode-still-emits-trace-before-throwing
  (testing "rf2-ee38b.10 — strict mode emits the :rf.ssr/hydration-mismatch
            trace (monitoring integrations rely on it) AND throws — the
            two are not mutually exclusive."
    (register-handlers!)
    (let [client-frame (frame/make-frame {:doc "ssr strict-mode frame"
                                       :platform :client
                                       :ssr {:on-mismatch :hard-error}})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})
      (let [traces (capture-traces!
                     (fn []
                       (try (ssr/verify-hydration! client-frame "0badf00d")
                            (catch clojure.lang.ExceptionInfo _ nil))))
            mismatch (first (filter #(= :rf.ssr/hydration-mismatch (:operation %))
                                    traces))]
        (is (some? mismatch)
            "the mismatch trace fires even in strict mode")
        (is (= :hard-error (:recovery mismatch))
            "the trace's :recovery reflects strict mode")))))

(deftest mismatch-detection-disabled-skips-comparison
  (testing "rf2-ee38b.10 — a frame with :ssr {:detect-mismatch? false}
            short-circuits the hash comparison entirely (Spec 011 item 4):
            no trace, no throw, even when the hashes diverge."
    (register-handlers!)
    (let [client-frame (frame/make-frame {:doc "ssr detection-off frame"
                                       :platform :client
                                       :ssr {:detect-mismatch? false}})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})
      (let [traces (capture-traces!
                     (fn []
                       (is (nil? (ssr/verify-hydration! client-frame "0badf00d"))
                           "verify-hydration! is a no-op when detection is off")))
            mismatches (filter #(= :rf.ssr/hydration-mismatch (:operation %))
                               traces)]
        (is (empty? mismatches)
            "no mismatch trace fires when :detect-mismatch? is false")))))

(deftest mismatch-detection-defaults-on-when-knob-absent
  (testing "rf2-ee38b.10 — absence of the :detect-mismatch? knob (the
            common case) leaves detection ON; a divergent hash still
            warns. Pins the default so a future refactor can't silently
            flip detection off."
    (register-handlers!)
    (let [client-frame (frame/make-frame {:doc "ssr default frame"
                                       :platform :client})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})
      (let [traces (capture-traces!
                     (fn [] (ssr/verify-hydration! client-frame "0badf00d")))
            mismatch (first (filter #(= :rf.ssr/hydration-mismatch (:operation %))
                                    traces))]
        (is (some? mismatch) "detection defaults on")
        (is (= :warned-and-replaced (:recovery mismatch))
            "the default recovery is warn-and-replace (not hard-error)")))))
