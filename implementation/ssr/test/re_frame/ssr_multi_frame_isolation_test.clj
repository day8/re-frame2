(ns re-frame.ssr-multi-frame-isolation-test
  "Per rf2-pxb7t · Wave 3 of rf2-tglku (Migration-Audit §ssr_multi_frame).

  The pre-migration Playwright spec at
  `testbeds/ssr_multi_frame/spec.cjs` walked the per-frame hydration
  isolation contract: three frames (`:counter/a`, `:counter/b`,
  `:log`), one payload bundle carrying per-frame slices, three
  independent `:rf/hydrate` dispatches, three independent app-dbs,
  three independent `:rf/render-hash` values stashed on each
  frame's `:rf/hydration` metadata block.

  The contract surface is platform-neutral:

    - `rf/dispatch-sync [:rf/hydrate slice] {:frame fid}` routes the
      replace-app-db to the named frame (per Spec 002 §Routing the
      dispatch envelope).
    - The `:rf/hydration` metadata lands in THAT frame's app-db only
      (Spec 011 §Frames are per-request + Spec 002 §What lives in
      a frame).
    - `rf/subscribe-once fid query-v` resolves against the explicit
      frame's signal-graph cache (re-frame.subs/subscribe-once
      2-arg form — already exists at
      `implementation/core/src/re_frame/subs.cljc:365`).
    - Per-frame dispatches (`[::inc]` against `:counter/a` then
      against `:counter/b`) mutate their own frame only — no cross-
      frame bleed.

  ## Migration map (Migration-Audit.md §ssr_multi_frame)

    spec.cjs assertions #2 (n-A=10, n-B=99) + #3 (entries-count=2)
      → multi-frame-hydrate-seeds-each-frame-from-its-own-payload-slice
    spec.cjs #4 (hyd-A/B/log = true)
      → multi-frame-hydrate-stashes-per-frame-hydration-metadata
    spec.cjs #5 (hash-A/B/log = aaaa1111/bbbb2222/cccc3333)
      → multi-frame-hydrate-stashes-per-frame-server-hash
    spec.cjs #6 (summary-{a,b,log}-hash via cross-frame subscribe-once)
      → multi-frame-subscribe-once-resolves-against-explicit-frame-id
    spec.cjs #7 (summary-all-distinct = true)
      → multi-frame-subscribe-once-resolves-against-explicit-frame-id
    spec.cjs #8 (post-inc-A: n-A=11, n-B=99)
      → multi-frame-dispatch-isolation-per-frame
    spec.cjs #9 (post-2x inc-B: n-B=101, n-A=11)
      → multi-frame-dispatch-isolation-per-frame

  Assertion #1 (`expectVisible(panel-A/B/log)`) is a pure DOM-mount
  probe — the Migration-Audit classifies it (C); per the rf2-pxb7t
  bead the whole `spec.cjs` is dropped and the mount assertion
  retires alongside (substrate mount is covered by the 3 adapter
  smokes per the audit's §Drop-or-keep recommendation)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; Frame ids mirror testbeds/ssr_multi_frame/core.cljs lines 48-50.
(def ^:private frame-a   :counter/a)
(def ^:private frame-b   :counter/b)
(def ^:private frame-log :log)

;; The per-frame payload bundle the testbed's `<script id=\"__rf_payload\">`
;; bakes verbatim (testbeds/ssr_multi_frame/index.html lines 44-58).
(def ^:private per-frame-payload
  {frame-a   {:rf/version     1
              :rf/render-hash "aaaa1111"
              :rf/app-db      {:n 10}}
   frame-b   {:rf/version     1
              :rf/render-hash "bbbb2222"
              :rf/app-db      {:n 99}}
   frame-log {:rf/version     1
              :rf/render-hash "cccc3333"
              :rf/app-db      {:entries [{:from :ssr :note "hello"}
                                         {:from :ssr :note "world"}]}}})

;; ----------------------------------------------------------------------------
;; Shared registrations — mirrors testbeds/ssr_multi_frame/core.cljs
;; lines 56-78
;; ----------------------------------------------------------------------------

(defn- register-handlers! []
  (rf/reg-event-db ::counter-init (fn [_db _ev] {:n 0}))
  (rf/reg-event-db ::log-init     (fn [_db _ev] {:entries []}))
  (rf/reg-event-db ::inc          (fn [db _ev] (update db :n (fnil inc 0))))
  (rf/reg-sub :n         (fn [db _] (:n db)))
  (rf/reg-sub :entries   (fn [db _] (:entries db)))
  (rf/reg-sub :hydration (fn [db _] (:rf/hydration db))))

(defn- register-three-frames! []
  (rf/reg-frame frame-a   {:on-create [::counter-init]})
  (rf/reg-frame frame-b   {:on-create [::counter-init]})
  (rf/reg-frame frame-log {:on-create [::log-init]}))

(defn- hydrate-each-frame! [payload-map]
  ;; Mirror of testbeds/ssr_multi_frame/core.cljs `run` lines 198-199.
  (doseq [[fid slice] payload-map]
    (rf/dispatch-sync [:rf/hydrate slice] {:frame fid})))

(defn- bootstrap-and-hydrate! []
  (register-handlers!)
  (register-three-frames!)
  (hydrate-each-frame! per-frame-payload))

;; ===========================================================================
;; spec.cjs §(1) → three panels render the seeded per-frame values
;; ===========================================================================

(deftest multi-frame-hydrate-seeds-each-frame-from-its-own-payload-slice
  (testing "Migrated from testbeds/ssr_multi_frame/spec.cjs assertions
            #2-#3. Each frame's :rf/hydrate dispatch carries its own
            :rf/app-db slice; the replace-app-db policy lands the
            slice on that frame's app-db ONLY. Per-frame subs
            (subscribe-once 2-arg form) read the post-drain state."
    (bootstrap-and-hydrate!)
    (is (= 10 (rf/subscribe-once frame-a [:n]))
        ":counter/a's app-db carries :n 10 from its payload slice")
    (is (= 99 (rf/subscribe-once frame-b [:n]))
        ":counter/b's app-db carries :n 99 from its payload slice")
    (is (= 2 (count (rf/subscribe-once frame-log [:entries])))
        ":log's app-db carries the 2 seeded entries from its payload slice")))

;; ===========================================================================
;; spec.cjs §(2) → per-frame :rf/hydration metadata landed
;; ===========================================================================

(deftest multi-frame-hydrate-stashes-per-frame-hydration-metadata
  (testing "Migrated from testbeds/ssr_multi_frame/spec.cjs assertion #4.
            Each frame's :rf/hydrate stashes a :rf/hydration metadata
            block on that frame's app-db (not on the surrounding
            default frame, not on a global atom)."
    (bootstrap-and-hydrate!)
    (is (some? (rf/subscribe-once frame-a [:hydration]))
        ":counter/a carries :rf/hydration metadata")
    (is (some? (rf/subscribe-once frame-b [:hydration]))
        ":counter/b carries :rf/hydration metadata")
    (is (some? (rf/subscribe-once frame-log [:hydration]))
        ":log carries :rf/hydration metadata")
    ;; And the metadata didn't bleed onto the default frame
    ;; (which was never hydrated).
    (is (nil? (get-in (rf/get-frame-db :rf/default) [:rf/hydration]))
        "the default frame was never hydrated — no metadata block")))

;; ===========================================================================
;; spec.cjs §(2 cont.) → per-frame :server-hash distinct
;; ===========================================================================

(deftest multi-frame-hydrate-stashes-per-frame-server-hash
  (testing "Migrated from testbeds/ssr_multi_frame/spec.cjs assertion
            #5. Each frame's :rf/hydration :server-hash equals its
            payload slice's :rf/render-hash verbatim — no cross-
            frame bleed (the runtime writes to one frame's app-db
            per dispatch, never to siblings)."
    (bootstrap-and-hydrate!)
    (is (= "aaaa1111" (:server-hash (rf/subscribe-once frame-a [:hydration])))
        ":counter/a's :server-hash = 'aaaa1111'")
    (is (= "bbbb2222" (:server-hash (rf/subscribe-once frame-b [:hydration])))
        ":counter/b's :server-hash = 'bbbb2222'")
    (is (= "cccc3333" (:server-hash (rf/subscribe-once frame-log [:hydration])))
        ":log's :server-hash = 'cccc3333'")
    (let [hashes #{(:server-hash (rf/subscribe-once frame-a [:hydration]))
                   (:server-hash (rf/subscribe-once frame-b [:hydration]))
                   (:server-hash (rf/subscribe-once frame-log [:hydration]))}]
      (is (= 3 (count hashes))
          (str "three frames hold three distinct server-hashes; saw: "
               (pr-str hashes))))))

;; ===========================================================================
;; spec.cjs §(3) → cross-frame readout via subscribe-once frame-id
;; ===========================================================================

(deftest multi-frame-subscribe-once-resolves-against-explicit-frame-id
  (testing "Migrated from testbeds/ssr_multi_frame/spec.cjs assertions
            #6-#7. The testbed's `hydration-summary` view called
            `rf/subscribe-once frame-id [:hydration]` against three
            different frames — same query-v, different frame-id —
            and each call resolved the matching frame's server-hash.
            This locks the subscribe-once 2-arg form's contract: the
            explicit frame-id selects the signal-graph cache to
            resolve against (the prereq verified by rf2-2mtl3 — see
            `re-frame.subs/subscribe-once` at
            implementation/core/src/re_frame/subs.cljc:365)."
    (bootstrap-and-hydrate!)
    ;; SAME query-v `[:hydration]`, THREE different frame-ids.
    (let [hyd-a (rf/subscribe-once frame-a   [:hydration])
          hyd-b (rf/subscribe-once frame-b   [:hydration])
          hyd-l (rf/subscribe-once frame-log [:hydration])]
      (is (= "aaaa1111" (:server-hash hyd-a))
          "subscribe-once :counter/a → that frame's server-hash")
      (is (= "bbbb2222" (:server-hash hyd-b))
          "subscribe-once :counter/b → that frame's server-hash")
      (is (= "cccc3333" (:server-hash hyd-l))
          "subscribe-once :log → that frame's server-hash")
      (is (= 3 (count (set [(:server-hash hyd-a)
                            (:server-hash hyd-b)
                            (:server-hash hyd-l)])))
          "all three resolved server-hashes are pairwise distinct —
           cross-frame `:hydration` reads do not share cache entries
           (per-frame signal-graph isolation per Spec 002)"))))

;; ===========================================================================
;; spec.cjs §(4) → per-frame post-hydration dispatch isolation
;; ===========================================================================

(deftest multi-frame-dispatch-isolation-per-frame
  (testing "Migrated from testbeds/ssr_multi_frame/spec.cjs assertions
            #8-#9. The post-hydrate dispatch path stays frame-
            isolated: `dispatch-sync [::inc] {:frame :counter/a}`
            bumps :counter/a's :n only; :counter/b's :n is
            untouched. Two `[::inc]` against :counter/b bump :n
            (99 → 100 → 101) without disturbing :counter/a (which
            stays at 11 after its single bump)."
    (bootstrap-and-hydrate!)
    ;; Single dispatch against :counter/a → A bumps, B doesn't.
    (rf/dispatch-sync [::inc] {:frame frame-a})
    (is (= 11 (rf/subscribe-once frame-a [:n]))
        ":counter/a's :n 10 → 11 (single ::inc)")
    (is (= 99 (rf/subscribe-once frame-b [:n]))
        ":counter/b's :n untouched (still 99) — no cross-frame bleed")

    ;; Two dispatches against :counter/b → B reaches 101, A untouched.
    (rf/dispatch-sync [::inc] {:frame frame-b})
    (rf/dispatch-sync [::inc] {:frame frame-b})
    (is (= 101 (rf/subscribe-once frame-b [:n]))
        ":counter/b's :n 99 → 100 → 101 (two ::inc)")
    (is (= 11 (rf/subscribe-once frame-a [:n]))
        ":counter/a's :n untouched (still 11) — no cross-frame bleed")))
