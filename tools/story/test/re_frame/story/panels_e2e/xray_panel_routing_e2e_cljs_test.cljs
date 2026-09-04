(ns re-frame.story.panels-e2e.xray-panel-routing-e2e-cljs-test
  "Multi-frame e2e coverage for the `:xray-panel` schema-slot routing
  (rf2-piucm; rf2-6qm77 + rf2-sgwor + rf2-v1ach).

  A variant body may declare a `:xray-panel <kw>` slot — the Story
  RHS resolves which Xray panel to mount for that variant from this
  slot, beating the embed's `default-panel` (`:epoch`).

  ## Bugs this catches

  - **rf2-6qm77 / rf2-sgwor + rf2-senbl** — `mount-fn-for` lookup
    correctness. A variant with `:xray-panel :app-db` MUST resolve
    to `xray-panels/mount-app-db-diff!`. A regression in the `case`
    dispatch in `mount-fn-for` would map the slot to nil and the
    panel-host would never paint.

  - **Variant slot beats story slot** — the resolution chain per
    `resolve-panel` is variant > story > default. Pinning the chain
    here catches a regression where `resolve-panel` drops the variant
    body lookup or inverts the precedence.

  - **Unknown slot falls back to default** — a typo doesn't blank
    the RHS; resolution returns `default-panel`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.xray-embed :as rf.story.ui.xray-embed]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [day8.re-frame2-xray.panels :as xray-panels]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- direct slot honoured ----------------------------------------------

(deftest variant-xray-panel-slot-resolves
  (testing "rf2-6qm77 — `:xray-panel :app-db` on a variant body
            resolves through `resolve-panel` to the :app-db panel id;
            `effective-panel` reads the variant body and beats the
            embed's default."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories
       (fn []
         (rf.story/reg-story :story.counter {})
         (rf.story/reg-variant :story.counter/app-db
           {:xray-panel :app-db
            :setup []}))}
      (fn []
        (is (= :app-db (rf.story.ui.xray-embed/resolve-panel :story.counter/app-db))
            "resolve-panel returned :app-db for a variant with the slot")
        (rf.story.test-helpers.e2e-multi-frame/select-variant! :story.counter/app-db)
        (is (= :app-db
               (rf.story.ui.xray-embed/effective-panel
                 (rf.story.ui.state/get-state) :story.counter/app-db))
            "effective-panel routes the slot through with no user override")))))

(deftest mount-fn-for-app-db-maps-to-xray-mount-fn
  (testing "rf2-senbl class — the `:xray-panel :app-db` route MUST
            resolve to `xray-panels/mount-app-db-diff!`, not nil.
            A regression in the `case` dispatch would silently leave
            the panel-host empty (the original bug)."
    (let [mfn (rf.story.ui.xray-embed/mount-fn-for :app-db)]
      (is (some? mfn)
          ":app-db panel id resolves to a non-nil mount-fn")
      (is (= mfn xray-panels/mount-app-db-diff!)
          "mount-fn-for :app-db is the canonical Xray app-db-diff
           mount fn (compile-time symbol — not a runtime walk)"))))

;; ---- precedence: variant slot beats story slot -------------------------

(deftest variant-slot-beats-story-slot
  (testing "the resolution chain is variant > story > default. A
            variant with no `:xray-panel` inherits from its story;
            a variant with `:xray-panel` declared beats the story's
            value."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories
       (fn []
         (rf.story/reg-story :story.routing
           {:xray-panel :routing
            :doc "Story-level slot says :routing."})
         (rf.story/reg-variant :story.routing/inherits
           {:setup []})
         (rf.story/reg-variant :story.routing/override
           {:xray-panel :machines
            :setup []}))}
      (fn []
        (is (= :routing (rf.story.ui.xray-embed/resolve-panel :story.routing/inherits))
            "variant with no slot inherits the story's :routing")
        (is (= :machines (rf.story.ui.xray-embed/resolve-panel :story.routing/override))
            "variant slot beats story slot")))))

;; ---- unknown slot falls back to default --------------------------------

(deftest unknown-slot-falls-back-to-default
  (testing "a typo / unknown panel-id in `:xray-panel` falls back to
            `default-panel` rather than blanking the RHS. Conservative
            failure mode."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories
       (fn []
         (rf.story/reg-story :story.typo {})
         (rf.story/reg-variant :story.typo/v
           {:xray-panel :not-a-real-panel
            :setup []}))}
      (fn []
        (is (= rf.story.ui.xray-embed/default-panel
               (rf.story.ui.xray-embed/resolve-panel :story.typo/v))
            "unknown slot value → fallback to default-panel
             (:epoch)")))))

;; ---- user override beats variant slot ----------------------------------

(deftest user-override-beats-variant-slot
  (testing "after the user clicks a chip the override wins over the
            variant's `:xray-panel` slot. `effective-panel` honours
            the override; clearing it (e.g. via `:rf/auto`) returns
            to the slot's value."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories
       (fn []
         (rf.story/reg-story :story.user-pick {})
         (rf.story/reg-variant :story.user-pick/v
           {:xray-panel :views
            :setup []}))}
      (fn []
        (rf.story.test-helpers.e2e-multi-frame/select-variant! :story.user-pick/v)
        ;; Pre-override: variant slot wins.
        (is (= :views
               (rf.story.ui.xray-embed/effective-panel
                 (rf.story.ui.state/get-state) :story.user-pick/v)))
        ;; User override: simulate the App-db chip click.
        (rf.story.ui.state/swap-state! assoc :xray-panel :app-db)
        (is (= :app-db
               (rf.story.ui.xray-embed/effective-panel
                 (rf.story.ui.state/get-state) :story.user-pick/v))
            "user's chip override beats the variant slot")
        ;; Clear override → back to the slot.
        (rf.story.ui.state/swap-state! dissoc :xray-panel)
        (is (= :views
               (rf.story.ui.xray-embed/effective-panel
                 (rf.story.ui.state/get-state) :story.user-pick/v))
            "clearing the override restores the variant slot's value")))))

;; ---- rendered embed reflects the routing -------------------------------

(deftest embed-data-active-panel-reflects-routed-slot
  (testing "the embed wrapper's `data-active-panel` carries the resolved
            slot value when the variant carries a `:xray-panel`. This
            is the end-to-end shape the Playwright spec asserted."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories
       (fn []
         (rf.story/reg-story :story.routed {})
         (rf.story/reg-variant :story.routed/v
           {:xray-panel :trace
            :setup []}))}
      (fn []
        (rf.story.test-helpers.e2e-multi-frame/select-variant! :story.routed/v)
        (let [tree    (rf.story.ui.xray-embed/xray-embed-panel)
              wrapper (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "story-xray-embed")]
          (is (some? wrapper) "embed wrapper renders")
          (is (= "trace" (get-in wrapper [1 :data-active-panel]))
              "data-active-panel reflects the variant's :xray-panel slot
               (end-to-end: registrar → resolve-panel → effective-panel →
               hiccup attr)"))))))
