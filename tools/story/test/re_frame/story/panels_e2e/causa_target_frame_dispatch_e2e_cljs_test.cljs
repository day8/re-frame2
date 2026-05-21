(ns re-frame.story.panels-e2e.causa-target-frame-dispatch-e2e-cljs-test
  "Story selection-watcher → Causa `set-target-frame` dispatch.

  When the user picks a variant in Story's sidebar the shell's
  selection-watcher fires on the `:selected-variant` edge. Pre-fix the
  watcher pre-allocated the variant's frame + ran the Causa cross-host
  bridges but never told Causa which frame to OBSERVE. So Causa stayed
  anchored on whatever its first-mount seed picked (commonly the boot
  `:rf/default` or the previously-focused variant) — the App-DB diff
  + Event tab rendered against a frame the user was no longer looking
  at, producing the empty-Causa-on-Story-RHS class of bug surfaced in
  rf2-fj332. The post-fix watcher dispatches
  `[:rf.causa/set-target-frame <variant-id>]` into `:rf/causa`, which
  writes both `:target-frame` AND re-seeds `:epoch-history` from
  `(rf/epoch-history variant-id)` in lockstep per the rf2-boyc2
  contract.

  Pin: the variant-id IS the frame-id (Story `reg-frame`s each variant
  under its variant-id; see `re-frame.story.frames`).

  Sub-second per surface; no DOM / no React mount / no Playwright."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story :as story]
            [re-frame.story.ui.shell :as shell]
            [re-frame.story.ui.state :as ui-state]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers -------------------------------------------------------------

(defn- install-selection-watcher!
  "Call the shell's private `selection-watcher` fn via `var` so the
  test can drive variant-selection edges without bringing up the full
  React mount. `selection-watcher` is the production code path under
  test — calling it directly exercises exactly the path
  `mount-shell!` invokes during boot."
  []
  ((deref #'shell/selection-watcher)))

(defn- remove-selection-watcher!
  "Symmetric teardown: drop the watch + clear the cross-mount tracker."
  []
  ((deref #'shell/remove-selection-watcher!)))

;; ---- the contract --------------------------------------------------------

(deftest selection-watcher-dispatches-set-target-frame-on-variant-select
  (testing "Picking a variant fires `:rf.causa/set-target-frame
            <variant-id>` into `:rf/causa` — so Causa re-orients its
            `:target-frame` + `:epoch-history` slots in lockstep on
            every selection edge. Pre-fix the watcher pre-allocated
            the variant's frame but never told Causa to OBSERVE it,
            so the App-DB / Event panels rendered against the prior
            target-frame (commonly the boot default)."
    (e2e/with-story-and-causa-frames
      {:register-stories
       (fn []
         (story/reg-story :story.cart {})
         (story/reg-variant :story.cart/empty
           {:doc    "Empty cart variant — exercises the target-frame dispatch."
            :events []}))}
      (fn []
        (install-selection-watcher!)
        (try
          ;; Variant-id IS the frame-id per `re-frame.story.frames`.
          (e2e/select-variant! :story.cart/empty)
          ;; Inspect the slot Causa's `:rf.causa/set-target-frame`
          ;; reducer writes to. If the watcher's dispatch fired, the
          ;; slot reflects the freshly-selected variant.
          (rf/with-frame :rf/causa
            (is (= :story.cart/empty
                   @(rf/subscribe [:rf.causa/target-frame]))
                "Causa's `:target-frame` slot reflects the selected
                 variant-id after the selection-watcher dispatch."))
          (finally
            (remove-selection-watcher!)))))))

(deftest selection-watcher-re-dispatches-on-each-variant-change
  (testing "Switching variants re-fires the dispatch — pre-fix the
            watcher dispatched zero times so a switch left Causa
            stuck on the prior frame. The post-fix watcher fires on
            every edge."
    (e2e/with-story-and-causa-frames
      {:register-stories
       (fn []
         (story/reg-story :story.cart {})
         (story/reg-variant :story.cart/empty {:events []})
         (story/reg-variant :story.cart/with-items {:events []}))}
      (fn []
        (install-selection-watcher!)
        (try
          (e2e/select-variant! :story.cart/empty)
          (rf/with-frame :rf/causa
            (is (= :story.cart/empty
                   @(rf/subscribe [:rf.causa/target-frame]))
                "first selection lands"))
          (e2e/select-variant! :story.cart/with-items)
          (rf/with-frame :rf/causa
            (is (= :story.cart/with-items
                   @(rf/subscribe [:rf.causa/target-frame]))
                "second selection re-orients Causa to the new frame"))
          (finally
            (remove-selection-watcher!)))))))

(deftest selection-watcher-no-dispatch-when-deselecting-to-nil
  (testing "Setting `:selected-variant` back to nil short-circuits the
            inner branch (the watcher only re-orients on a non-nil
            edge). Causa's `:target-frame` retains the last selected
            value rather than being reset to nil — consistent with the
            picker contract (a nil dispatch resets to the boot
            default, which would clobber the user's last context)."
    (e2e/with-story-and-causa-frames
      {:register-stories
       (fn []
         (story/reg-story :story.cart {})
         (story/reg-variant :story.cart/empty {:events []}))}
      (fn []
        (install-selection-watcher!)
        (try
          (e2e/select-variant! :story.cart/empty)
          (rf/with-frame :rf/causa
            (is (= :story.cart/empty
                   @(rf/subscribe [:rf.causa/target-frame]))
                "selection landed"))
          ;; Now clear the selection. The watcher's `(when now ...)`
          ;; guard short-circuits, so no dispatch fires; the slot
          ;; retains the last variant-id.
          (e2e/select-variant! nil)
          (rf/with-frame :rf/causa
            (is (= :story.cart/empty
                   @(rf/subscribe [:rf.causa/target-frame]))
                "Causa's target-frame is retained — the watcher does
                 not clobber it on a deselect edge."))
          (finally
            (remove-selection-watcher!)))))))
