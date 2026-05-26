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

  - **Legacy `:xray :panel` nested form is honoured** — old story
    bodies written before rf2-v1ach used `{:xray {:panel :app-db}}`;
    we MUST still resolve them so existing testbeds keep working.

  - **Unknown slot falls back to default** — a typo doesn't blank
    the RHS; resolution returns `default-panel`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story :as story]
            [re-frame.story.ui.xray-embed :as xray-embed]
            [re-frame.story.ui.state :as ui-state]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.panels :as xray-panels]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- direct slot honoured ----------------------------------------------

(deftest variant-xray-panel-slot-resolves
  (testing "rf2-6qm77 — `:xray-panel :app-db` on a variant body
            resolves through `resolve-panel` to the :app-db panel id;
            `effective-panel` reads the variant body and beats the
            embed's default."
    (e2e/with-story-and-xray-frames
      {:register-stories
       (fn []
         (story/reg-story :story.counter {})
         (story/reg-variant :story.counter/app-db
           {:xray-panel :app-db
            :events []}))}
      (fn []
        (is (= :app-db (xray-embed/resolve-panel :story.counter/app-db))
            "resolve-panel returned :app-db for a variant with the slot")
        (e2e/select-variant! :story.counter/app-db)
        (is (= :app-db
               (xray-embed/effective-panel
                 (ui-state/get-state) :story.counter/app-db))
            "effective-panel routes the slot through with no user override")))))

(deftest mount-fn-for-app-db-maps-to-xray-mount-fn
  (testing "rf2-senbl class — the `:xray-panel :app-db` route MUST
            resolve to `xray-panels/mount-app-db-diff!`, not nil.
            A regression in the `case` dispatch would silently leave
            the panel-host empty (the original bug)."
    (let [mfn (xray-embed/mount-fn-for :app-db)]
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
    (e2e/with-story-and-xray-frames
      {:register-stories
       (fn []
         (story/reg-story :story.routing
           {:xray-panel :routing
            :doc "Story-level slot says :routing."})
         (story/reg-variant :story.routing/inherits
           {:events []})
         (story/reg-variant :story.routing/override
           {:xray-panel :machines
            :events []}))}
      (fn []
        (is (= :routing (xray-embed/resolve-panel :story.routing/inherits))
            "variant with no slot inherits the story's :routing")
        (is (= :machines (xray-embed/resolve-panel :story.routing/override))
            "variant slot beats story slot")))))

;; ---- legacy `:xray :panel` nested form --------------------------------

(deftest legacy-xray-panel-nested-form-honoured
  (testing "rf2-v1ach §Spec — the legacy `{:xray {:panel :app-db}}`
            nested form is honoured for back-compat. Story bodies
            already using this shape MUST keep resolving correctly."
    (e2e/with-story-and-xray-frames
      {:register-stories
       (fn []
         (story/reg-story :story.legacy {})
         (story/reg-variant :story.legacy/v
           {:xray {:panel :views}
            :events []}))}
      (fn []
        (is (= :views (xray-embed/resolve-panel :story.legacy/v))
            "legacy nested :xray :panel form resolves to :views")))))

;; ---- unknown slot falls back to default --------------------------------

(deftest unknown-slot-falls-back-to-default
  (testing "a typo / unknown panel-id in `:xray-panel` falls back to
            `default-panel` rather than blanking the RHS. Conservative
            failure mode."
    (e2e/with-story-and-xray-frames
      {:register-stories
       (fn []
         (story/reg-story :story.typo {})
         (story/reg-variant :story.typo/v
           {:xray-panel :not-a-real-panel
            :events []}))}
      (fn []
        (is (= xray-embed/default-panel
               (xray-embed/resolve-panel :story.typo/v))
            "unknown slot value → fallback to default-panel
             (:epoch)")))))

;; ---- user override beats variant slot ----------------------------------

(deftest user-override-beats-variant-slot
  (testing "after the user clicks a chip the override wins over the
            variant's `:xray-panel` slot. `effective-panel` honours
            the override; clearing it (e.g. via `:rf/auto`) returns
            to the slot's value."
    (e2e/with-story-and-xray-frames
      {:register-stories
       (fn []
         (story/reg-story :story.user-pick {})
         (story/reg-variant :story.user-pick/v
           {:xray-panel :views
            :events []}))}
      (fn []
        (e2e/select-variant! :story.user-pick/v)
        ;; Pre-override: variant slot wins.
        (is (= :views
               (xray-embed/effective-panel
                 (ui-state/get-state) :story.user-pick/v)))
        ;; User override: simulate the App-db chip click.
        (ui-state/swap-state! assoc :xray-panel :app-db)
        (is (= :app-db
               (xray-embed/effective-panel
                 (ui-state/get-state) :story.user-pick/v))
            "user's chip override beats the variant slot")
        ;; Clear override → back to the slot.
        (ui-state/swap-state! dissoc :xray-panel)
        (is (= :views
               (xray-embed/effective-panel
                 (ui-state/get-state) :story.user-pick/v))
            "clearing the override restores the variant slot's value")))))

;; ---- rendered embed reflects the routing -------------------------------

(deftest embed-data-active-panel-reflects-routed-slot
  (testing "the embed wrapper's `data-active-panel` carries the resolved
            slot value when the variant carries a `:xray-panel`. This
            is the end-to-end shape the Playwright spec asserted."
    (e2e/with-story-and-xray-frames
      {:register-stories
       (fn []
         (story/reg-story :story.routed {})
         (story/reg-variant :story.routed/v
           {:xray-panel :trace
            :events []}))}
      (fn []
        (e2e/select-variant! :story.routed/v)
        (let [tree    (xray-embed/xray-embed-panel)
              wrapper (e2e/find-by-test-id tree "story-xray-embed")]
          (is (some? wrapper) "embed wrapper renders")
          (is (= "trace" (get-in wrapper [1 :data-active-panel]))
              "data-active-panel reflects the variant's :xray-panel slot
               (end-to-end: registrar → resolve-panel → effective-panel →
               hiccup attr)"))))))
