(ns day8.re-frame2-xray.static.shell-dom-cljs-test
  "Browser-lane half of the Static-mode persistence tests.

  WHY A SEPARATE NAMESPACE. The sibling
  `day8.re-frame2-xray.static.shell-cljs-test` holds the pure mode
  algebra (`normalise-mode`, `->raw` / `<-raw`) and the hiccup render
  walk, neither of which needs host storage. The `save!` / `load`
  round-trip and the `:rf.xray.static/persist-mode` fx need a real
  `window.localStorage`, and only a namespace ending `-dom-cljs-test`
  is ever loaded by the `:browser-test` build, whose `:ns-regexp` is
  `.*-dom-cljs-test$`. Sitting in the sibling file these rows would
  execute in NEITHER lane: skipped under `:node-test` for want of
  storage, and never loaded by `:browser-test` at all. The file's
  LOCATION is what matters, not the guard.

  THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES. `:node-test`'s
  `:ns-regexp` is `cljs-test$` — a bare SUFFIX match, which
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does. So the node
  build loads this namespace too, and the overlap is deliberate:
  `implementation/shadow-cljs.edn` records above `:browser-test` that
  the DOM-tagged files run on BOTH targets so their cross-runtime
  asserts keep firing in Node. A row here therefore runs on the
  browser lane AND the node one.
  `ls/available?` is what keeps the node run inert.

  THE SKIP BRANCH ASSERTS RATHER THAN VANISHING. A bare `(when ...)`
  body would leave the node lane holding a deftest with ZERO
  assertions, which is the hollow shape this namespace exists to
  avoid. The marker row keeps the skip visible in the
  node summary. Same shape as
  `day8.re-frame2-xray.palette.recents-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; Mirrors the sibling's fixture. The storage slate matters more here
  ;; than on node: the browser lane runs every namespace on ONE page, so
  ;; a leftover mode value would be in storage when the next namespace
  ;; hydrates.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/reset-suppressed-count!)
                   (static-persistence/clear!))}))

;; ---- helpers (mirror the sibling's) -------------------------------------

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

;; -------------------------------------------------------------------------
;; localStorage persistence — round-trip
;; -------------------------------------------------------------------------

(deftest persistence-load-default-empty-slot
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (testing "a cleared localStorage slot falls back to :dynamic"
      ;; `save!` a NON-default first, so the fallback is read off a slot
      ;; `clear!` demonstrably emptied. Asserting `:dynamic` straight
      ;; after `clear!` on a never-written slot passes on a silently
      ;; no-op storage too — the same vacuity the recents dom sibling's
      ;; `save-caps-at-max` guards against.
      (static-persistence/save! :static)
      (is (= :static (static-persistence/load))
          "precondition: the non-default value really did persist")
      (static-persistence/clear!)
      (is (= :dynamic (static-persistence/load))
          "empty localStorage slot → :dynamic fallback"))))

(deftest persistence-save-and-load-round-trip
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (testing "save! + load round-trip through real browser storage"
      (static-persistence/clear!)
      (static-persistence/save! :static)
      (is (= :static (static-persistence/load)))
      (static-persistence/save! :dynamic)
      (is (= :dynamic (static-persistence/load))))))

(deftest persistence-fx-installed-by-set-mode
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (testing ":rf.xray/set-mode lands the value in localStorage via the fx"
      (xray-setup!)
      (static-persistence/clear!)
      (frame-dispatch [:rf.xray/set-mode :static])
      (is (= :static (static-persistence/load))
          ":static was persisted")
      (frame-dispatch [:rf.xray/toggle-mode])
      (is (= :dynamic (static-persistence/load))
          "toggle back to :dynamic was persisted"))))
