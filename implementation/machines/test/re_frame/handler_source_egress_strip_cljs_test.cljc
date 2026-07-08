(ns re-frame.handler-source-egress-strip-cljs-test
  "rf2-tlf4if — handler source-as-data does NOT leak across the frame-state
  egress boundary.

  The DEBUG-gated handler source-as-data (`:rf.handler/source` on an event's
  registry meta; the machine spec's internal `:source-code` / `:source-coords`
  co-located slots) lives ONLY on the REGISTRY, never in a frame's durable
  state. A frame-state snapshot — `(rf/frame-state-value fid)` =
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}` — is the value that flows
  off-box (SSR hydration, epoch/time-travel, Xray/pair-MCP snapshots,
  bug-report captures). This test PINS that no source-as-data key rides that
  snapshot at any depth, even after a machine has been started (so its runtime
  state, incl. its `:snapshots` tree, is live in the runtime-db partition).

  Complements:
    - `re-frame.handler-source-test` / `-cljs-test` — the positive-presence
      side (source IS captured on registry meta in dev);
    - `implementation/scripts/check-elision.cjs` — CLJS production DCE (the
      source bytes never reach the `:advanced` + `goog.DEBUG=false` bundle);
    - `day8.re-frame2-machines-viz.share` share-URL encoder + its test — the
      off-box SHARE boundary that additionally strips the machine spec's
      `:source-code` / `:source-coords` when a spec is embedded in a share.

  Spec contract: [Conventions §Reserved registration metadata — `:rf.handler/source`],
  [009 §`:rf.handler/source`], [002 §The two-partition frame contract]."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Boot the machines artefact's late-bind hooks so `reg-machine`
   ;; resolves through the Spec-005 implementation rather than throwing
   ;; `:rf.error/machines-artefact-missing` (the hooks install on ns load).
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; The three source-as-data keys the boundary must never carry. `:source-code`
;; / `:source-coords` are the machine spec's INTERNAL co-location slots;
;; `:rf.handler/source` is the unified public source key both event + machine
;; handler-meta surface. None of them is frame-state.
(def ^:private source-as-data-keys
  #{:rf.handler/source :source-code :source-coords})

(defn- source-keys-anywhere
  "Deep-walk `v`, returning the set of `source-as-data-keys` found as a map key
  at ANY depth (across maps, vectors, sets, seqs). Empty set = clean."
  [v]
  (letfn [(walk [acc x]
            (cond
              (map? x)  (reduce-kv
                          (fn [a k vv]
                            (walk (if (contains? source-as-data-keys k)
                                    (conj a k)
                                    a)
                                  vv))
                          acc x)
              (coll? x) (reduce walk acc x)
              :else     acc))]
    (walk #{} v)))

;; The machine spec is written INLINE in each deftest below (never via a helper
;; fn): the `reg-machine` macro walks its literal argument form at expansion
;; time, so `:source-code` / `:source-coords` co-location only happens when the
;; spec is a literal at the call site. The guard + action carry literal
;; fn-forms; starting the machine installs its snapshot into runtime-db.

(deftest source-as-data-is-captured-on-registry-meta
  (testing "precondition (dev): the source IS on registry meta — so the strip
  below is a genuine BOUNDARY property, not a never-captured accident"
    (rf/reg-event :tlf4if/seed (fn [{:keys [db]} [_ n]] {:db {:seeded n}}))
    (rf/reg-machine :tlf4if/probed
      {:initial :idle
       :data    {:n 0}
       :guards  {:ready? (fn [_] true)}
       :actions {:bump   (fn [{data :data}] {:data (update data :n inc)})}
       :states  {:idle    {:on {:go {:target :running :guard :ready? :action :bump}}}
                 :running {}}})
    (is (string? (:rf.handler/source (rf/handler-meta :event :tlf4if/seed)))
        "reg-event stamps :rf.handler/source on the :event registry slot (dev)")
    (is (string? (:rf.handler/source
                  (rf/handler-meta :machine-guard [:tlf4if/probed :ready?])))
        "a machine guard derives :rf.handler/source from the spec's :source-code (dev)")
    (is (string? (:rf.handler/source
                  (rf/handler-meta :machine-action [:tlf4if/probed :bump])))
        "a machine action derives :rf.handler/source from the spec's :source-code (dev)")))

(deftest frame-state-snapshot-carries-no-source-as-data
  (testing "rf2-tlf4if: the frame-state egress snapshot strips ALL source-as-data
  keys — even after seeding app-db and starting a machine (runtime-db live)"
    (rf/reg-event :tlf4if/seed (fn [{:keys [db]} [_ n]] {:db {:seeded n}}))
    (rf/reg-machine :tlf4if/probed
      {:initial :idle
       :data    {:n 0}
       :guards  {:ready? (fn [_] true)}
       :actions {:bump   (fn [{data :data}] {:data (update data :n inc)})}
       :states  {:idle    {:on {:go {:target :running :guard :ready? :action :bump}}}
                 :running {}}})
    ;; Seed the app-db partition (the fixture's frame is `:rf/default`).
    (rf/dispatch-sync [:tlf4if/seed 7])
    ;; Start the machine → its snapshot lands in the runtime-db partition,
    ;; then drive one transition so the live runtime state is non-trivial.
    (rf/dispatch-sync [:tlf4if/probed [:rf.machine/start]])
    (rf/dispatch-sync [:tlf4if/probed [:go]])
    (let [state (rf/frame-state-value :rf/default)]
      ;; Sanity: the snapshot is the two-partition projection and carries the
      ;; state we seeded (so we know we're walking a real, populated value).
      (is (= 7 (get-in state [:rf.db/app :seeded]))
          "the snapshot carries the seeded app-db value")
      (is (contains? state :rf.db/runtime)
          "the snapshot carries the runtime-db partition")
      (is (some? (mtest/snapshot :tlf4if/probed))
          "the started machine's snapshot is live in runtime-db")
      ;; The invariant: no source-as-data key anywhere in the egress snapshot —
      ;; neither the event's `:rf.handler/source` nor the machine spec's
      ;; internal `:source-code` / `:source-coords` (the spec never enters
      ;; frame-state; only the machine's runtime snapshot does).
      (is (= #{} (source-keys-anywhere state))
          (str "frame-state egress snapshot must carry NO source-as-data key; "
               "found: " (source-keys-anywhere state)))
      ;; Each partition individually clean (the app-db partition is the SSR /
      ;; app-schema egress contract; the runtime-db partition is the epoch /
      ;; time-travel contract).
      (is (= #{} (source-keys-anywhere (:rf.db/app state)))
          "app-db partition carries no source-as-data key")
      (is (= #{} (source-keys-anywhere (:rf.db/runtime state)))
          "runtime-db partition carries no source-as-data key"))))
