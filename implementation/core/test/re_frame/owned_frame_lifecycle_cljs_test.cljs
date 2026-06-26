(ns re-frame.owned-frame-lifecycle-cljs-test
  "Node-runnable unit tests for the substrate-agnostic core of the MERGED
  config-shaped `rf/frame-provider` (EP-0024 amended) and the fail-loud guards
  it shares — `re-frame.views.owned-frame`.

  EP-0024 amendment (provider collapse). The owned create-on-mount /
  destroy-on-unmount lifecycle is RETIRED (it had zero product consumers). The
  merged `rf/frame-provider` serves two non-owning jobs, dispatched on the prop
  map: `{:frame existing-id}` SCOPE-ONLY (fail loud if absent) and
  `{:id …}` ENSURE (create-if-absent, reuse-if-present NO re-seed, NO
  destroy-on-unmount). These tests exercise the parts that need NO React mount:

    - the ENSURE create / reuse-no-reseed algebra (`acquire-ensure-frame!`);
    - the SCOPE-only fail-loud-if-absent guard (`require-live-frame-for-scope!`);
    - the ENSURE `:id` guard (`require-ensure-frame-id!`);
    - the retained legacy `frame-provider-existing` lifecycle-opt guard
      (`reject-lifecycle-opts!`).

  The React function component (`ensure-frame-fc`) + the hot-reload
  reuse-no-reseed behaviour under a real remount are exercised under a real DOM
  in the adapter DOM-test target (the HOT-RELOAD GATE,
  `frame_provider_context_dom_cljs_test`); this suite locks the lifecycle
  ALGEBRA the component drives."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.views.owned-frame :as owned-frame]))

;; ---- app image (EP-0026 §Default Image) ----------------------------------
;;
;; These tests are direct `make-frame` / `acquire-ensure-frame!` callers (NOT
;; Story). Co-loaded with the rest of the `node-test` build, an image-less frame
;; would resolve the EP-0026 default image (the whole co-loaded store), whose
;; cross-app same-`[kind id]` registrations collide. Each frame is created with
;; an explicit app image instead (`:select-ns` over THIS test namespace), so its
;; generation is scoped to this suite's own registrations.
;;
;; A NS-LOAD marker event (`::image-anchor`) anchors the `:select-ns` glob so it
;; is NEVER zero-match: some tests create a frame using only the `:rf/set-db`
;; framework standard and register nothing else from this ns, so a bare
;; `:select-ns` over this ns would be empty and fail loud with
;; `:rf.error/image-zero-match`. The anchor MUST register BEFORE the
;; `use-fixtures` form so the reset-runtime fixture captures it in the ns-load
;; baseline it folds back over the registrar + source store before each test —
;; otherwise it would be wiped and the glob would zero-match. The tests' own
;; test-time handlers (`:owned/bump`, `:owned/needs-missing-cofx`, …) carry this
;; ns's provenance too, so the SAME glob picks them up when they are registered
;; BEFORE the acquire.
;;
;; The frame still resolves `:rf/set-db` and the other framework standards: the
;; standards are added to EVERY image generation unconditionally by image
;; assembly (they are not part of `:select-ns`).

;; NS-load anchor for the app image's `:select-ns` (see above). Registered
;; BEFORE `use-fixtures` so it is in the fixture's captured ns-load baseline.
(rf/reg-event ::image-anchor (fn [{:keys [db]} _] {:db db}))

(def ^:private app-image
  (rf/image
    {:id        :owned-frame-lifecycle/app
     :select-ns {:include ["re-frame.owned-frame-lifecycle-cljs-test"]}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- ENSURE: create-if-absent + reuse-no-reseed ---------------------------

(deftest acquire-ensure-creates-and-returns-id
  (testing "acquire-ensure-frame! runs make-frame and returns the resolved id"
    (let [id (owned-frame/acquire-ensure-frame! {:id :owned/alpha :images [app-image] :initial-events [[:rf/set-db {:n 1}]]})]
      (is (= :owned/alpha id) "returns the frame id off the constructed frame value")
      (is (some? (frame/frame :owned/alpha)) "the frame is live in the registry")
      (is (= {:n 1} (rf/app-db-value :owned/alpha)) ":rf/set-db seeded app-db"))))

(deftest re-acquire-ensure-reuses-without-reseed
  (testing "re-acquiring the same id REUSES the live frame WITHOUT re-seeding
            (EP-0024 amended ENSURE: idempotent replacement + reg-frame
            re-records-but-does-not-replay :initial-events). This is the
            reuse-no-reseed contract the hot-reload gate pins under a real DOM."
    ;; Register the dispatch handler BEFORE the acquire so it is in the
    ;; frame's app-image-scoped generation (the generation is sealed at
    ;; make-frame time; its `:select-ns` over this ns picks the handler up).
    (rf/reg-event :owned/bump (fn [{:keys [db]} _] {:db (update db :count inc)}))
    (owned-frame/acquire-ensure-frame! {:id :owned/beta :images [app-image] :initial-events [[:rf/set-db {:count 0}]]})
    ;; Mutate durable state through a dispatch path.
    (rf/dispatch-sync [:owned/bump] {:frame :owned/beta})
    (is (= {:count 1} (rf/app-db-value :owned/beta)) "durable state advanced")
    ;; A re-acquire under the SAME id (the hot-reload / StrictMode remount
    ;; shape) must NOT reset durable state and must NOT re-run :initial-events.
    (let [id2 (owned-frame/acquire-ensure-frame! {:id :owned/beta :images [app-image] :initial-events [[:rf/set-db {:count 99}]]})]
      (is (= :owned/beta id2))
      (is (= {:count 1} (rf/app-db-value :owned/beta))
          "re-acquire preserved durable state — NOT re-seeded to {:count 99} (reuse-no-reseed)"))))

(deftest ensure-passes-url-bound-through-as-record-config
  (testing "an ensure-provider opt like :url-bound? flows verbatim through
            make-frame onto the frame record-config (it is not a provider-only
            opt; the ensure provider just hands the whole opts map to make-frame)."
    (owned-frame/acquire-ensure-frame!
      {:id :owned/urlbound :images [app-image] :url-bound? true
       :initial-events [[:rf/set-db {:n 0}]]})
    (is (true? (:url-bound? (frame/frame-meta :owned/urlbound)))
        ":url-bound? landed on the frame meta (record-config passthrough)")))

(deftest acquire-ensure-setup-escaping-throw-rethrows-and-leaves-no-frame
  (testing "rf2-83fwld: an ensure-provider acquire whose :initial-events step
            THROWS out of dispatch-sync rethrows out of acquire-ensure-frame! AND
            leaves NO frame registered (a setup throw destroys the just-created
            frame, then rethrows). Transitively: acquire -> make-frame ->
            reg-frame -> run-setup-events! tears down the partial frame and
            rethrows :rf.error/initial-events-step-failed. This is the
            ESCAPING-throw detection route."
    ;; A step that requires an UNREGISTERED cofx: cofx resolution throws OUT of
    ;; dispatch-sync (:rf.error/unregistered-cofx via throw-error!), so this is
    ;; the ESCAPING-throw case — run-setup-events! tears down the partial frame
    ;; (via its try/catch) and rethrows :rf.error/initial-events-step-failed.
    (rf/reg-event :owned/needs-missing-cofx
      {:rf.cofx/requires [:owned/unregistered-cofx]}
      (fn [{:keys [db]} _] {:db (assoc db :ran true)}))
    (let [thrown (atom nil)]
      (try
        (owned-frame/acquire-ensure-frame!
          {:id :owned/boom
           :images [app-image]
           :initial-events [[:rf/set-db {:seeded true}]
                            [:owned/needs-missing-cofx]]})
        (catch :default e (reset! thrown e)))
      (is (some? @thrown)
          "the setup throw RETHROWS out of acquire-ensure-frame! (not swallowed)")
      (is (= :rf.error/initial-events-step-failed
             (:rf.error/id (ex-data @thrown)))
          "the rethrown error is the setup-step failure naming the throwing step")
      (is (nil? (frame/frame :owned/boom))
          "no half-created frame is left registered — the partial frame was torn down"))))

(deftest acquire-ensure-setup-in-band-handler-throw-rethrows-and-leaves-no-frame
  (testing "rf2-vw5h1r: a throwing acquire destroys the just-created frame, then
            rethrows — holds for an IN-BAND handler-body throw too, not just an
            escaping throw. A [:rf/set-db :not-a-map] bad-arg step raises
            :rf.error/set-db-bad-value from inside the handler; the chain catches
            it (in-band :rf.error/handler-exception) and dispatch-sync returns nil
            NORMALLY. Under strict construction run-setup-events! detects that
            captured in-band failure, tears the partial frame down, and rethrows
            :rf.error/initial-events-step-failed — which propagates out of
            acquire-ensure-frame!."
    (let [thrown (atom nil)]
      (try
        (owned-frame/acquire-ensure-frame!
          {:id :owned/setdb-boom
           :images [app-image]
           :initial-events [[:rf/set-db {:seeded true}]
                            [:rf/set-db :not-a-map]]})
        (catch :default e (reset! thrown e)))
      (is (some? @thrown)
          "the in-band handler-body throw RETHROWS out of acquire-ensure-frame!")
      (is (= :rf.error/initial-events-step-failed
             (:rf.error/id (ex-data @thrown)))
          "the rethrown error is the setup-step failure naming the failing step")
      (is (nil? (frame/frame :owned/setdb-boom))
          "no half-created frame is left registered — the partial frame was torn down"))))

;; ---- fail-loud: ENSURE provider needs a keyword :id ----------------------

(deftest require-ensure-frame-id-fails-loud-on-missing-id
  (testing "the ensure provider requires a keyword :id"
    (is (= :owned/ok (owned-frame/require-ensure-frame-id! :owned/ok 'rf/frame-provider))
        "a keyword :id passes through unchanged")
    (is (thrown-with-msg? :default #":rf.error/ensure-frame-provider-missing-id"
          (owned-frame/require-ensure-frame-id! nil 'rf/frame-provider))
        "a missing/nil :id fails loud")
    (is (thrown-with-msg? :default #":rf.error/ensure-frame-provider-missing-id"
          (owned-frame/require-ensure-frame-id! "owned/str" 'rf/frame-provider))
        "a non-keyword :id fails loud")))

;; ---- fail-loud: SCOPE-only shape needs a LIVE frame ----------------------

(deftest require-live-frame-for-scope-passes-a-live-frame
  (testing "require-live-frame-for-scope! returns the keyword for a LIVE frame"
    (owned-frame/acquire-ensure-frame! {:id :scope/live :images [app-image]})
    (is (= :scope/live
           (owned-frame/require-live-frame-for-scope! :scope/live 'rf/frame-provider))
        "a live frame passes through unchanged")))

(deftest require-live-frame-for-scope-fails-loud-on-absent-frame
  (testing "require-live-frame-for-scope! fails loud when the named frame is absent"
    (is (thrown-with-msg? :default #":rf.error/frame-provider-frame-absent"
          (owned-frame/require-live-frame-for-scope! :scope/never-created 'rf/frame-provider))
        "scoping a never-created frame fails loud")
    ;; A created-then-destroyed frame is also absent.
    (owned-frame/acquire-ensure-frame! {:id :scope/gone :images [app-image]})
    (rf/destroy-frame! :scope/gone)
    (is (thrown-with-msg? :default #":rf.error/frame-provider-frame-absent"
          (owned-frame/require-live-frame-for-scope! :scope/gone 'rf/frame-provider))
        "scoping a destroyed frame fails loud")))

;; ---- fail-loud: legacy frame-provider-existing rejects lifecycle opts -----

(deftest reject-lifecycle-opts-passes-frame-only
  (testing "frame-provider-existing accepts a :frame-only prop map"
    (is (nil? (owned-frame/reject-lifecycle-opts!
                {:frame :scope/x}
                'rf/frame-provider-existing))
        "a clean {:frame …} map is accepted (returns nil)")))

(deftest reject-lifecycle-opts-fails-loud-on-construction-opts
  (testing "frame-provider-existing rejects each frame-construction / lifecycle opt"
    (doseq [bad [{:frame :scope/x :id :scope/x}
                 {:frame :scope/x :images []}
                 {:frame :scope/x :initial-events [[:rf/set-db {}]]}
                 ;; the retired construction keys are still rejected here (kept in
                 ;; lifecycle-opt-keys) so a stale caller fails loud, not silently:
                 {:frame :scope/x :initial-db {}}
                 {:frame :scope/x :on-create (fn [_])}]]
      (is (thrown-with-msg? :default #":rf.error/frame-provider-existing-lifecycle-opt"
            (owned-frame/reject-lifecycle-opts! bad 'rf/frame-provider-existing))
          (str "rejects lifecycle opt in " (pr-str (dissoc bad :frame)))))))

;; ---- end-to-end: the Reagent user-facing surfaces validate ----------------
;;
;; These don't mount (node has no DOM); they call the public Reagent
;; component fns directly to confirm the surface-level validation wiring
;; (the hiccup-emission level, like runtime_cljs_test does).

(deftest merged-frame-provider-ensure-validates-id-at-the-surface
  (testing "rf/frame-provider {:id …} ENSURE shape fails loud on a bad :id"
    (is (thrown-with-msg? :default #":rf.error/ensure-frame-provider-missing-id"
          (rf/frame-provider {:id "not-a-keyword"} [:span]))
        "a non-keyword :id on the ensure shape fails loud")))

(deftest merged-frame-provider-scope-fails-loud-on-absent-frame-at-the-surface
  (testing "rf/frame-provider {:frame absent} SCOPE shape fails loud when the frame is absent"
    (is (thrown-with-msg? :default #":rf.error/frame-provider-frame-absent"
          (rf/frame-provider {:frame :scope/surface-absent} [:span]))
        "scoping an absent frame at the surface fails loud")))

(deftest merged-frame-provider-scope-scopes-a-live-frame-at-the-surface
  (testing "rf/frame-provider {:frame live} SCOPE shape composes to a Provider"
    (owned-frame/acquire-ensure-frame! {:id :scope/surface-live :images [app-image]})
    (let [tree (rf/frame-provider {:frame :scope/surface-live} [:span "child"])]
      (is (vector? tree) "produces a hiccup vector")
      (is (= :scope/surface-live (second tree))
          "the frame keyword threads through to the scope tier"))))

(deftest frame-provider-existing-rejects-lifecycle-opt-at-the-surface
  (testing "rf/frame-provider-existing fails loud when handed an owned-style :id"
    (is (thrown-with-msg? :default #":rf.error/frame-provider-existing-lifecycle-opt"
          (rf/frame-provider-existing {:frame :scope/y :id :scope/y} [:span]))
        "a lifecycle opt on the scope-only surface fails loud")))

(deftest frame-provider-existing-scopes-frame-only
  (testing "rf/frame-provider-existing with a clean :frame composes to a Provider"
    (let [tree (rf/frame-provider-existing {:frame :scope/z} [:span "child"])]
      (is (vector? tree) "produces a hiccup vector")
      (is (= :scope/z (second tree)) "the frame keyword threads through to the scope tier"))))
