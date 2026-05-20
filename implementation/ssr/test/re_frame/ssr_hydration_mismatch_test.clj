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
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; The payload the testbed's `<script id=\"__rf_payload\">` bakes verbatim
;; (testbeds/ssr_hydration_mismatch/index.html lines 49-54). The
;; "deadbeef" string is the known-wrong server-hash that will not
;; equal whatever the client tree's actual FNV-1a hash resolves to.
(def ^:private mismatch-payload
  {:rf/version     1
   :rf/frame-id    :rf/default
   :rf/render-hash "deadbeef"
   :rf/app-db      {:count 0}})

(defn- register-handlers! []
  (rf/reg-event-db ::inc
    (fn [db _ev] (update db :count (fnil inc 0))))
  (rf/reg-sub :count     (fn [db _] (or (:count db) 0)))
  (rf/reg-sub :hydrated? (fn [db _] (boolean (:rf/hydration db)))))

(defn- capture-traces!
  [f]
  (let [traces (atom [])
        cb-id  (gensym "::ssr-hydration-mismatch-capture-")]
    (rf/register-trace-listener! cb-id (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (rf/unregister-trace-listener! cb-id)))
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
    (let [client-frame (rf/make-frame {:doc "ssr-mismatch client frame"
                                       :platform :client})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})

      (is (true? (rf/subscribe-once client-frame [:hydrated?]))
          "post-hydrate :hydrated? reads true even though the baked
           hash will mismatch the (future) client render")
      (is (= "deadbeef"
             (get-in (rf/get-frame-db client-frame)
                     [:rf/hydration :server-hash]))
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
    (let [client-frame   (rf/make-frame {:doc "ssr-mismatch client frame"
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
    (let [client-frame (rf/make-frame {:doc "ssr-mismatch client frame"
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
    (let [client-frame (rf/make-frame {:doc "ssr-mismatch client frame"
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
    (let [client-frame (rf/make-frame {:doc "ssr-mismatch client frame"
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
