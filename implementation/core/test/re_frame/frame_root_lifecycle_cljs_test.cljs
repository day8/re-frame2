(ns re-frame.frame-root-lifecycle-cljs-test
  "Node-runnable unit tests for the substrate-agnostic cores of the SPLIT
  frame-boundary components (rf2-nyea0r) and the fail-loud guards they share —
  `re-frame.views.frame-boundary`.

  rf2-nyea0r split. The pre-split merged `frame-provider` had TWO shapes
  dispatched on the prop map; it is now TWO components, one verb each:
  `rf/frame-root` ENSURE (create-if-absent, reuse-if-present NO re-seed, NO
  destroy-on-unmount, COMMIT-OWNED two-pass) and `rf/frame-provider` SCOPE-only
  (fail loud if absent). These tests exercise the parts that need NO React
  mount:

    - the ENSURE create / reuse-no-reseed algebra (`acquire-frame-root!`);
    - the SCOPE-only fail-loud-if-absent guard (`require-live-frame-for-scope!`);
    - the frame-root `:id` guard (`require-frame-root-id!`);
    - the did-you-mean rejectors both ways (`reject-frame-provider-id!` /
      `reject-frame-root-frame!`).

  The React function component (`frame-root-fc`) two-pass commit-owned lifecycle
  under a real DOM (first render emits no subtree; ENSURE in useLayoutEffect;
  StrictMode-once; keyed-remount preserves durable state; mounted change fails
  loud) is exercised under a real DOM in the adapter DOM-test target
  (`frame_provider_context_dom_cljs_test`); this suite locks the lifecycle
  ALGEBRA the component drives."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views.frame-boundary :as rf.views.frame-boundary]))

;; ---- app image (EP-0026 §Default Image) ----------------------------------
;;
;; These tests are direct `make-frame` / `acquire-frame-root!` callers (NOT
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
;; test-time handlers (`:root/bump`, `:root/needs-missing-cofx`, …) carry this
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
    {:id        :frame-root-lifecycle/app
     :select-ns {:include ["re-frame.frame-root-lifecycle-cljs-test"]}}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- ENSURE: create-if-absent + reuse-no-reseed ---------------------------

(deftest acquire-frame-root-creates-and-returns-id
  (testing "acquire-frame-root! runs make-frame and returns the resolved id"
    (let [id (rf.views.frame-boundary/acquire-frame-root! {:id :root/alpha :images [app-image] :initial-events [[:rf/set-db {:n 1}]]})]
      (is (= :root/alpha id) "returns the frame id off the constructed frame value")
      (is (some? (rf.frame/frame :root/alpha)) "the frame is live in the registry")
      (is (= {:n 1} (rf/app-db-value :root/alpha)) ":rf/set-db seeded app-db"))))

(deftest re-acquire-frame-root-reuses-without-reseed
  (testing "re-acquiring the same id REUSES the live frame WITHOUT re-seeding
            (frame-root ENSURE: idempotent replacement + make-frame
            re-records-but-does-not-replay :initial-events). This is the
            reuse-no-reseed contract the keyed-remount gate pins under a real DOM."
    ;; Register the dispatch handler BEFORE the acquire so it is in the
    ;; frame's app-image-scoped generation (the generation is sealed at
    ;; make-frame time; its `:select-ns` over this ns picks the handler up).
    (rf/reg-event :root/bump (fn [{:keys [db]} _] {:db (update db :count inc)}))
    (rf.views.frame-boundary/acquire-frame-root! {:id :root/beta :images [app-image] :initial-events [[:rf/set-db {:count 0}]]})
    ;; Mutate durable state through a dispatch path.
    (rf/dispatch-sync [:root/bump] {:frame :root/beta})
    (is (= {:count 1} (rf/app-db-value :root/beta)) "durable state advanced")
    ;; A re-acquire under the SAME id (the hot-reload / StrictMode remount
    ;; shape) must NOT reset durable state and must NOT re-run :initial-events.
    (let [id2 (rf.views.frame-boundary/acquire-frame-root! {:id :root/beta :images [app-image] :initial-events [[:rf/set-db {:count 99}]]})]
      (is (= :root/beta id2))
      (is (= {:count 1} (rf/app-db-value :root/beta))
          "re-acquire preserved durable state — NOT re-seeded to {:count 99} (reuse-no-reseed)"))))

(deftest frame-root-passes-url-bound-through-as-record-config
  (testing "a frame-root opt like :url-bound? flows verbatim through make-frame
            onto the frame record-config (it is not a component-only opt; the
            frame-root just hands the whole opts map to make-frame)."
    (rf.views.frame-boundary/acquire-frame-root!
      {:id :root/urlbound :images [app-image] :url-bound? true
       :initial-events [[:rf/set-db {:n 0}]]})
    (is (true? (:url-bound? (rf.frame/frame-meta :root/urlbound)))
        ":url-bound? landed on the frame meta (record-config passthrough)")))

(deftest frame-root-opts-strips-children-and-fallback
  (testing "frame-root-opts strips the substrate carriers :children / :fallback
            and passes every make-frame key through"
    (is (= {:id :root/x :images [:i] :initial-events []}
           (rf.views.frame-boundary/frame-root-opts
             {:id :root/x :images [:i] :initial-events []
              :children [:span] :fallback [:div "loading"]}))
        ":children and :fallback are stripped; frame opts pass through")))

(deftest acquire-frame-root-setup-escaping-throw-rethrows-and-leaves-no-frame
  (testing "rf2-83fwld: a frame-root acquire whose :initial-events step THROWS
            out of dispatch-sync rethrows out of acquire-frame-root! AND leaves
            NO frame registered (a setup throw destroys the just-created frame,
            then rethrows). Transitively: acquire -> make-frame -> make-frame ->
            run-setup-events! tears down the partial frame and rethrows
            :rf.error/initial-events-step-failed. This is the ESCAPING-throw
            detection route."
    ;; A step that requires an UNREGISTERED cofx: cofx resolution throws OUT of
    ;; dispatch-sync (:rf.error/unregistered-cofx via throw-error!), so this is
    ;; the ESCAPING-throw case — run-setup-events! tears down the partial frame
    ;; (via its try/catch) and rethrows :rf.error/initial-events-step-failed.
    (rf/reg-event :root/needs-missing-cofx
      {:rf.cofx/requires [:root/unregistered-cofx]}
      (fn [{:keys [db]} _] {:db (assoc db :ran true)}))
    (let [thrown (atom nil)]
      (try
        (rf.views.frame-boundary/acquire-frame-root!
          {:id :root/boom
           :images [app-image]
           :initial-events [[:rf/set-db {:seeded true}]
                            [:root/needs-missing-cofx]]})
        (catch :default e (reset! thrown e)))
      (is (some? @thrown)
          "the setup throw RETHROWS out of acquire-frame-root! (not swallowed)")
      (is (= :rf.error/initial-events-step-failed
             (:rf.error/id (ex-data @thrown)))
          "the rethrown error is the setup-step failure naming the throwing step")
      (is (nil? (rf.frame/frame :root/boom))
          "no half-created frame is left registered — the partial frame was torn down"))))

(deftest acquire-frame-root-setup-in-band-handler-throw-rethrows-and-leaves-no-frame
  (testing "rf2-vw5h1r: a throwing acquire destroys the just-created frame, then
            rethrows — holds for an IN-BAND handler-body throw too, not just an
            escaping throw. A [:rf/set-db :not-a-map] bad-arg step raises
            :rf.error/set-db-bad-value from inside the handler; the chain catches
            it (in-band :rf.error/handler-exception) and dispatch-sync returns nil
            NORMALLY. Under strict construction run-setup-events! detects that
            captured in-band failure, tears the partial frame down, and rethrows
            :rf.error/initial-events-step-failed — which propagates out of
            acquire-frame-root!."
    (let [thrown (atom nil)]
      (try
        (rf.views.frame-boundary/acquire-frame-root!
          {:id :root/setdb-boom
           :images [app-image]
           :initial-events [[:rf/set-db {:seeded true}]
                            [:rf/set-db :not-a-map]]})
        (catch :default e (reset! thrown e)))
      (is (some? @thrown)
          "the in-band handler-body throw RETHROWS out of acquire-frame-root!")
      (is (= :rf.error/initial-events-step-failed
             (:rf.error/id (ex-data @thrown)))
          "the rethrown error is the setup-step failure naming the failing step")
      (is (nil? (rf.frame/frame :root/setdb-boom))
          "no half-created frame is left registered — the partial frame was torn down"))))

;; ---- fail-loud: ENSURE frame-root needs a keyword :id --------------------

(deftest require-frame-root-id-fails-loud-on-missing-id
  (testing "the frame-root requires a keyword :id"
    (is (= :root/ok (rf.views.frame-boundary/require-frame-root-id! :root/ok 'rf/frame-root))
        "a keyword :id passes through unchanged")
    (is (thrown-with-msg? :default #":rf.error/frame-root-missing-id"
          (rf.views.frame-boundary/require-frame-root-id! nil 'rf/frame-root))
        "a missing/nil :id fails loud")
    (is (thrown-with-msg? :default #":rf.error/frame-root-missing-id"
          (rf.views.frame-boundary/require-frame-root-id! "root/str" 'rf/frame-root))
        "a non-keyword :id fails loud")))

;; ---- fail-loud: did-you-mean both ways (rf2-nyea0r) ----------------------

(deftest reject-frame-provider-id-names-frame-root
  (testing "frame-provider given :id fails loud naming frame-root"
    (is (thrown-with-msg? :default #":rf.error/frame-provider-given-id"
          (rf.views.frame-boundary/reject-frame-provider-id! :some/frame 'rf/frame-provider))
        "an :id on a SCOPE provider is a configuration error")))

(deftest reject-frame-root-frame-names-frame-provider
  (testing "frame-root given :frame fails loud naming frame-provider"
    (is (thrown-with-msg? :default #":rf.error/frame-root-given-frame"
          (rf.views.frame-boundary/reject-frame-root-frame! :some/frame 'rf/frame-root))
        "a :frame on an ENSURE root is a configuration error")))

;; ---- fail-loud: SCOPE-only shape needs a LIVE frame ----------------------

(deftest require-live-frame-for-scope-passes-a-live-frame
  (testing "require-live-frame-for-scope! returns the keyword for a LIVE frame"
    (rf.views.frame-boundary/acquire-frame-root! {:id :scope/live :images [app-image]})
    (is (= :scope/live
           (rf.views.frame-boundary/require-live-frame-for-scope! :scope/live 'rf/frame-provider))
        "a live frame passes through unchanged")))

(deftest require-live-frame-for-scope-fails-loud-on-absent-frame
  (testing "require-live-frame-for-scope! fails loud when the named frame is absent"
    (is (thrown-with-msg? :default #":rf.error/frame-provider-frame-absent"
          (rf.views.frame-boundary/require-live-frame-for-scope! :scope/never-created 'rf/frame-provider))
        "scoping a never-created frame fails loud")
    ;; A created-then-destroyed frame is also absent.
    (rf.views.frame-boundary/acquire-frame-root! {:id :scope/gone :images [app-image]})
    (rf/destroy-frame! :scope/gone)
    (is (thrown-with-msg? :default #":rf.error/frame-provider-frame-absent"
          (rf.views.frame-boundary/require-live-frame-for-scope! :scope/gone 'rf/frame-provider))
        "scoping a destroyed frame fails loud")))

;; ---- end-to-end: the Reagent user-facing surfaces validate ----------------
;;
;; These don't mount (node has no DOM); they call the public Reagent
;; component fns directly to confirm the surface-level validation wiring
;; (the hiccup-emission level, like runtime_cljs_test does).

(deftest frame-root-validates-id-at-the-surface
  (testing "rf/frame-root {:id …} fails loud on a bad :id"
    (is (thrown-with-msg? :default #":rf.error/frame-root-missing-id"
          (rf/frame-root {:id "not-a-keyword"} [:span]))
        "a non-keyword :id on frame-root fails loud")))

(deftest frame-root-rejects-frame-key-at-the-surface
  (testing "rf/frame-root {:frame …} fails loud naming frame-provider"
    (is (thrown-with-msg? :default #":rf.error/frame-root-given-frame"
          (rf/frame-root {:frame :scope/x} [:span]))
        "a :frame on frame-root fails loud")))

(deftest frame-provider-rejects-id-key-at-the-surface
  (testing "rf/frame-provider {:id …} fails loud naming frame-root"
    (is (thrown-with-msg? :default #":rf.error/frame-provider-given-id"
          (rf/frame-provider {:id :scope/x} [:span]))
        "an :id on frame-provider fails loud")))

(deftest frame-provider-scope-fails-loud-on-absent-frame-at-the-surface
  (testing "rf/frame-provider {:frame absent} SCOPE shape fails loud when the frame is absent"
    (is (thrown-with-msg? :default #":rf.error/frame-provider-frame-absent"
          (rf/frame-provider {:frame :scope/surface-absent} [:span]))
        "scoping an absent frame at the surface fails loud")))

(deftest frame-provider-scope-scopes-a-live-frame-at-the-surface
  (testing "rf/frame-provider {:frame live} SCOPE shape composes to a Provider"
    (rf.views.frame-boundary/acquire-frame-root! {:id :scope/surface-live :images [app-image]})
    (let [tree (rf/frame-provider {:frame :scope/surface-live} [:span "child"])]
      (is (vector? tree) "produces a hiccup vector")
      (is (= :scope/surface-live (second tree))
          "the frame keyword threads through to the scope tier"))))
