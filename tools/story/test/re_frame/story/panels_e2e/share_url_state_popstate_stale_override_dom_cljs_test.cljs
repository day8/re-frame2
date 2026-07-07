(ns re-frame.story.panels-e2e.share-url-state-popstate-stale-override-dom-cljs-test
  "Regression coverage for rf2-cmjly3 finding 8: a Back/Forward navigation
  used to skip the declared-key stale-override drop-and-report filter that
  mount hydration always runs.

  `re-frame.story.ui.shell/hydrate-url-state!` runs TWO passes on mount
  (see the sibling `share-url-state-hydration-e2e-cljs-test`, rf2-ovb1en):

      1. url-state/hydrate-from-url!  ← installs the RAW parsed
                                         `:cell-overrides` (pure, no
                                         registrar access)
      2. share/hydrate-from-url!     ← the declared-key DRIFT filter
                                         (`share/drop-stale-overrides`)
                                         narrows/clears that slice + records
                                         the share-import drift hint

  The popstate handler (`url-state/install-popstate-listener!`) used to
  wire ONLY pass 1 as its `apply-fn` — pass 2 never ran on Back/Forward, so
  a stale override (an arg-key the focused variant no longer declares,
  e.g. renamed/removed) installed as a live orphan arg with no drop/report,
  unlike the mount path. The fix threads `share/hydrate-from-url!` through
  as `install-popstate-listener!`'s new `post-apply-fn`, run inside the
  SAME hydration guard as the base apply (`shell.cljs`'s wiring).

  This needs a REAL `window`/`history`/`popstate` round-trip — jsdom is not
  part of this repo's node-test toolchain, so `js/window` does not exist
  under `:node-test` at all. ns ends in `-dom-cljs-test` so shadow-cljs's
  `:browser-test` build discovers it and runs against a real browser
  window; `:node-test` also loads it (regex matches the suffix too) where
  the body self-gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story :as story]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.ui.state :as ui-state]
            [re-frame.story.ui.share :as share]
            [re-frame.story.ui.url-state :as url-state]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- browser gate ---------------------------------------------------------

(defn- browser? []
  (and (exists? js/window)
       (some? (.-history js/window))
       (some? (.-location js/window))))

;; ---- production validators (mirrors shell's private `url-state-apply-fn`) -

(defn- production-apply-fn []
  (let [validators {:variant? (fn [vid]
                                (or (nil? vid)
                                    (registrar/registered? :variant vid)))}]
    (fn [state parsed]
      (url-state/apply-parsed-to-state state parsed validators))))

;; A variant whose declared arg surface is exactly #{:label}. An
;; `overrides=` entry for any OTHER key is stale (the variant no longer
;; declares it) and must be dropped + reported by the declared-key filter —
;; on the popstate path exactly as on mount.
(defn- register-variant! []
  (story/reg-story :story.sample {:doc "Parent for the popstate-hydration e2e."})
  (story/reg-variant :story.sample/card
    {:doc      "A card variant declaring a single :label arg."
     :argtypes {:label {:type :string}}
     :args     {:label "Hello"}}))

(deftest popstate-runs-declared-key-stale-override-filter
  (testing "rf2-cmjly3 finding 8: a Back/Forward pop to a URL whose
            `overrides=` carries arg-keys the focused variant no longer
            declares drops them (leaving NO live [:cell-overrides
            variant-id] slice) and records the share-import drift hint —
            the SAME outcome mount hydration produces, not the RAW
            unfiltered install pass 1 alone would leave."
    (if-not (browser?)
      (is true ":node-test — no real window/history; :browser-test runs the real assertion")
      (e2e/with-story-and-xray-frames
        {:register-stories register-variant!}
        (fn []
          (let [orig-url (str (.-pathname (.-location js/window))
                              (.-search   (.-location js/window))
                              (.-hash     (.-location js/window)))]
            (try
              (url-state/install-popstate-listener!
                ui-state/shell-state-atom
                (production-apply-fn)
                share/hydrate-from-url!)
              (let [qs (str "?variant=" (js/encodeURIComponent "story.sample/card")
                            "&overrides=" (js/encodeURIComponent
                                            (pr-str {:gone 1 :also-gone 2})))]
                (.pushState (.-history js/window) nil "" qs)
                (.dispatchEvent js/window (js/PopStateEvent. "popstate" #js {})))
              (let [s (ui-state/get-state)]
                (is (= :story.sample/card (:selected-variant s))
                    "the popstate applied the variant selection (pass 1)")
                (is (nil? (get-in s [:cell-overrides :story.sample/card]))
                    "rf2-cmjly3 finding 8: both stale overrides are dropped
                     on the POPSTATE path too — pre-fix this stayed the raw
                     unfiltered {:gone 1 :also-gone 2} pass 1 installs")
                (is (= 2 (get-in s [:rf.story/share-import-hint
                                    :story.sample/card :dropped-count]))
                    "the drift hint is recorded on the popstate path,
                     mirroring mount hydration (rf2-9jthx)"))
              (finally
                (url-state/remove-popstate-listener!)
                (try (.replaceState (.-history js/window) nil "" orig-url)
                     (catch :default _ nil))))))))))

(deftest popstate-with-declared-overrides-still-hydrates
  (testing "rf2-cmjly3 finding 8 — no regression: a declared override
            still hydrates on popstate (the fix narrows the filter, it
            does not break the happy path)"
    (if-not (browser?)
      (is true ":node-test — no real window/history; :browser-test runs the real assertion")
      (e2e/with-story-and-xray-frames
        {:register-stories register-variant!}
        (fn []
          (let [orig-url (str (.-pathname (.-location js/window))
                              (.-search   (.-location js/window))
                              (.-hash     (.-location js/window)))]
            (try
              (url-state/install-popstate-listener!
                ui-state/shell-state-atom
                (production-apply-fn)
                share/hydrate-from-url!)
              (let [qs (str "?variant=" (js/encodeURIComponent "story.sample/card")
                            "&overrides=" (js/encodeURIComponent
                                            (pr-str {:label "Shared"})))]
                (.pushState (.-history js/window) nil "" qs)
                (.dispatchEvent js/window (js/PopStateEvent. "popstate" #js {})))
              (let [s (ui-state/get-state)]
                (is (= {:label "Shared"} (get-in s [:cell-overrides :story.sample/card]))
                    "a declared override hydrates on popstate")
                (is (nil? (get-in s [:rf.story/share-import-hint :story.sample/card]))
                    "no drift hint when nothing was dropped"))
              (finally
                (url-state/remove-popstate-listener!)
                (try (.replaceState (.-history js/window) nil "" orig-url)
                     (catch :default _ nil))))))))))
