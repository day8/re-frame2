(ns re-frame.frame-root-lifecycle-cljs-test
  "Node-runnable unit tests for the substrate-agnostic cores of the
  frame-boundary components and the fail-loud guards they share —
  `re-frame.views.frame-boundary`.

  There are TWO frame-boundary components, one verb each:
  `rf/frame-root` ENSURE (create-if-absent, reuse-if-present NO re-seed, NO
  destroy-on-unmount, COMMIT-OWNED two-pass) and `rf/frame-provider` SCOPE-only
  (fail loud if absent). These tests exercise the parts that need NO React
  mount:

    - the ENSURE create (`acquire-frame-root!`), one `make-frame` call whose
      reuse-no-reseed and setup-failure teardown are `make-frame`'s own
      (`frame_initial_events_cljs_test`);
    - `frame-root-opts`, which strips the substrate carriers;
    - the frame-root `:id` guard, the SCOPE-only fail-loud-if-absent guard and
      the did-you-mean rejectors both ways, through the public
      `rf/frame-root` / `rf/frame-provider` surfaces.

  The React function component (`frame-root-fc`) two-pass commit-owned lifecycle
  under a real DOM (first render emits no subtree; ENSURE in useLayoutEffect;
  StrictMode-once; keyed-remount preserves durable state; mounted change fails
  loud) is exercised under a real DOM in the adapter DOM-test target
  (`frame_provider_context_dom_cljs_test`); this suite locks the lifecycle
  ALGEBRA the component drives."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
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
;; is NEVER zero-match: the tests create frames using only the `:rf/set-db`
;; framework standard and register nothing else from this ns, so a bare
;; `:select-ns` over this ns would be empty and fail loud with
;; `:rf.error/image-zero-match`. The anchor MUST register BEFORE the
;; `use-fixtures` form so the reset-runtime fixture captures it in the ns-load
;; baseline it folds back over the registrar + source store before each test —
;; otherwise it would be wiped and the glob would zero-match.
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

;; ---- ENSURE: create-if-absent ---------------------------------------------

(deftest acquire-frame-root-creates-and-returns-id
  (testing "acquire-frame-root! runs make-frame and returns the resolved id"
    (let [id (rf.views.frame-boundary/acquire-frame-root! {:id :root/alpha :images [app-image] :initial-events [[:rf/set-db {:n 1}]]})]
      (is (= :root/alpha id) "returns the frame id off the constructed frame value")
      (is (= {:n 1} (rf/app-db-value :root/alpha))
          "the frame is live in the registry and :rf/set-db seeded its app-db"))))

(deftest frame-root-opts-strips-children-and-fallback
  (testing "frame-root-opts strips the substrate carriers :children / :fallback
            and passes every make-frame key through"
    (is (= {:id :root/x :images [:i] :initial-events []}
           (rf.views.frame-boundary/frame-root-opts
             {:id :root/x :images [:i] :initial-events []
              :children [:span] :fallback [:div "loading"]}))
        ":children and :fallback are stripped; frame opts pass through")))

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
