(ns re-frame.ssr-multi-frame-isolation-test
  "The per-frame hydration isolation contract, on the
  `testbeds/ssr_multi_frame` shape: three frames (`:counter/a`,
  `:counter/b`, `:log`), one payload bundle carrying per-frame slices,
  three independent `:rf/hydrate` dispatches, three independent app-dbs,
  three independent `:rf/render-hash` values stashed on each
  frame's `[:rf.runtime/ssr :hydration]` metadata block.

  The contract surface is platform-neutral:

    - `rf/dispatch-sync [:rf/hydrate slice] {:frame fid}` routes the
      replace-app-db to the named frame (per Spec 002 §Routing the
      dispatch envelope).
    - The [:rf.runtime/ssr :hydration] metadata lands in THAT frame's app-db only
      (Spec 011 §Frames are per-request + Spec 002 §What lives in
      a frame).
    - `rf/subscribe-once query-v {:frame fid}` resolves against the
      explicit frame's signal-graph cache (re-frame.subs/subscribe-once
      opts-map form — see `implementation/core/src/re_frame/subs.cljc`).
    - Per-frame dispatches (`[::inc]` against `:counter/a` then
      against `:counter/b`) mutate their own frame only — no cross-
      frame bleed.

  There is no DOM-mount assertion here: substrate mount is covered by
  the adapter smokes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as rf.subs]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; Frame ids mirror testbeds/ssr_multi_frame/core.cljs.
(def ^:private frame-a   :counter/a)
(def ^:private frame-b   :counter/b)
(def ^:private frame-log :log)

;; The per-frame payload bundle the testbed's `<script id=\"__rf_payload\">`
;; bakes verbatim (testbeds/ssr_multi_frame/index.html).
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
;; ----------------------------------------------------------------------------

(defn- register-handlers! []
  (rf/reg-event ::counter-init (fn [_coeffects _event] {:db {:n 0}}))
  (rf/reg-event ::log-init     (fn [_coeffects _event] {:db {:entries []}}))
  (rf/reg-event ::inc          (fn [{:keys [db]} _ev] {:db (update db :n (fnil inc 0))}))
  (rf/reg-sub :n         (fn [db _] (:n db)))
  (rf/reg-sub :entries   (fn [db _] (:entries db)))
  ;; EP-0001: the SSR hydration metadata is durable runtime-db
  ;; state, so :hydration is a runtime-db sub (reads the runtime-db projection).
  (rf.subs/reg-runtime-sub :hydration (fn [rt _] (get-in rt [:rf.runtime/ssr :hydration]))))

(defn- register-three-frames! []
  (rf/make-frame {:id frame-a :initial-events [[::counter-init]]})
  (rf/make-frame {:id frame-b :initial-events [[::counter-init]]})
  (rf/make-frame {:id frame-log :initial-events [[::log-init]]}))

(defn- hydrate-each-frame! [payload-map]
  ;; Mirror of testbeds/ssr_multi_frame/core.cljs `run`.
  (doseq [[fid slice] payload-map]
    (rf/dispatch-sync [:rf/hydrate slice] {:frame fid})))

(defn- bootstrap-and-hydrate! []
  (register-handlers!)
  (register-three-frames!)
  (hydrate-each-frame! per-frame-payload))

;; ===========================================================================
;; each frame seeds from its own payload slice
;; ===========================================================================

(deftest multi-frame-hydrate-seeds-each-frame-from-its-own-payload-slice
  (testing "Each frame's :rf/hydrate dispatch carries its own
            :rf/app-db slice; the replace-app-db policy lands the
            slice on that frame's app-db ONLY. Per-frame subs
            (subscribe-once 2-arg form) read the post-drain state."
    (bootstrap-and-hydrate!)
    (is (= 10 (rf/subscribe-once [:n] {:frame frame-a}))
        ":counter/a's app-db carries :n 10 from its payload slice")
    (is (= 99 (rf/subscribe-once [:n] {:frame frame-b}))
        ":counter/b's app-db carries :n 99 from its payload slice")
    (is (= 2 (count (rf/subscribe-once [:entries] {:frame frame-log})))
        ":log's app-db carries the 2 seeded entries from its payload slice")))

;; ===========================================================================
;; per-frame [:rf.runtime/ssr :hydration] metadata lands
;; ===========================================================================

(deftest multi-frame-hydrate-stashes-per-frame-hydration-metadata
  (testing "Each frame's :rf/hydrate stashes a [:rf.runtime/ssr :hydration] metadata
            block on that frame's app-db (not on the surrounding
            default frame, not on a global atom)."
    (bootstrap-and-hydrate!)
    (is (some? (rf/subscribe-once [:hydration] {:frame frame-a}))
        ":counter/a carries [:rf.runtime/ssr :hydration] metadata")
    (is (some? (rf/subscribe-once [:hydration] {:frame frame-b}))
        ":counter/b carries [:rf.runtime/ssr :hydration] metadata")
    (is (some? (rf/subscribe-once [:hydration] {:frame frame-log}))
        ":log carries [:rf.runtime/ssr :hydration] metadata")
    ;; And the metadata didn't bleed onto the default frame
    ;; (which was never hydrated).
    (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/ssr :hydration]))
        "the default frame was never hydrated — no metadata block")))

;; ===========================================================================
;; per-frame :server-hash is distinct
;; ===========================================================================

(deftest multi-frame-hydrate-stashes-per-frame-server-hash
  (testing "Each frame's [:rf.runtime/ssr :hydration :server-hash] equals its
            payload slice's :rf/render-hash verbatim — no cross-
            frame bleed (the runtime writes to one frame's app-db
            per dispatch, never to siblings)."
    (bootstrap-and-hydrate!)
    (is (= "aaaa1111" (:server-hash (rf/subscribe-once [:hydration] {:frame frame-a})))
        ":counter/a's :server-hash = 'aaaa1111'")
    (is (= "bbbb2222" (:server-hash (rf/subscribe-once [:hydration] {:frame frame-b})))
        ":counter/b's :server-hash = 'bbbb2222'")
    (is (= "cccc3333" (:server-hash (rf/subscribe-once [:hydration] {:frame frame-log})))
        ":log's :server-hash = 'cccc3333'")
    (let [hashes #{(:server-hash (rf/subscribe-once [:hydration] {:frame frame-a}))
                   (:server-hash (rf/subscribe-once [:hydration] {:frame frame-b}))
                   (:server-hash (rf/subscribe-once [:hydration] {:frame frame-log}))}]
      (is (= 3 (count hashes))
          (str "three frames hold three distinct server-hashes; saw: "
               (pr-str hashes))))))

;; ===========================================================================
;; cross-frame readout via subscribe-once {:frame fid}
;; ===========================================================================

(deftest multi-frame-subscribe-once-resolves-against-explicit-frame-id
  (testing "`rf/subscribe-once [:hydration] {:frame frame-id}` against
            three different frames — same query-v, different frame-id —
            resolves each frame's own server-hash.
            This locks the subscribe-once opts-map form's contract: the
            explicit frame-id selects the signal-graph cache to
            resolve against (see `re-frame.subs/subscribe-once` at
            implementation/core/src/re_frame/subs.cljc)."
    (bootstrap-and-hydrate!)
    ;; SAME query-v `[:hydration]`, THREE different frame-ids.
    (let [hyd-a (rf/subscribe-once [:hydration] {:frame frame-a})
          hyd-b (rf/subscribe-once [:hydration] {:frame frame-b})
          hyd-l (rf/subscribe-once [:hydration] {:frame frame-log})]
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
;; per-frame post-hydration dispatch isolation
;; ===========================================================================

(deftest multi-frame-dispatch-isolation-per-frame
  (testing "The post-hydrate dispatch path stays frame-
            isolated: `dispatch-sync [::inc] {:frame :counter/a}`
            bumps :counter/a's :n only; :counter/b's :n is
            untouched. Two `[::inc]` against :counter/b bump :n
            (99 → 100 → 101) without disturbing :counter/a (which
            stays at 11 after its single bump)."
    (bootstrap-and-hydrate!)
    ;; Single dispatch against :counter/a → A bumps, B doesn't.
    (rf/dispatch-sync [::inc] {:frame frame-a})
    (is (= 11 (rf/subscribe-once [:n] {:frame frame-a}))
        ":counter/a's :n 10 → 11 (single ::inc)")
    (is (= 99 (rf/subscribe-once [:n] {:frame frame-b}))
        ":counter/b's :n untouched (still 99) — no cross-frame bleed")

    ;; Two dispatches against :counter/b → B reaches 101, A untouched.
    (rf/dispatch-sync [::inc] {:frame frame-b})
    (rf/dispatch-sync [::inc] {:frame frame-b})
    (is (= 101 (rf/subscribe-once [:n] {:frame frame-b}))
        ":counter/b's :n 99 → 100 → 101 (two ::inc)")
    (is (= 11 (rf/subscribe-once [:n] {:frame frame-a}))
        ":counter/a's :n untouched (still 11) — no cross-frame bleed")))
