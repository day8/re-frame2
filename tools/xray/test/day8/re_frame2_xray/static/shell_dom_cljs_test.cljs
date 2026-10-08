(ns day8.re-frame2-xray.static.shell-dom-cljs-test
  "The Static-mode localStorage round-trip. It needs a real
  `window.localStorage`, so it lives in a `-dom-cljs-test` namespace —
  the only kind the `:browser-test` build loads. The `:node-test` build
  loads it too, where `ls/available?` is false and each row asserts its
  skip rather than running empty."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  ;; The browser lane runs every namespace on one page, so a leftover mode
  ;; would still be in storage when the next namespace hydrates.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/reset-suppressed-count!)
                   (static-persistence/clear!))}))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

(deftest persistence-load-default-empty-slot
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane)")
    (testing "a cleared slot falls back to :dynamic"
      ;; A non-default value first, so the fallback is read off a slot
      ;; `clear!` demonstrably emptied — a no-op storage passes otherwise.
      (static-persistence/save! :static)
      (is (= :static (static-persistence/load)))
      (static-persistence/clear!)
      (is (= :dynamic (static-persistence/load))))))

(deftest persistence-fx-installed-by-set-mode
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane)")
    (testing "set-mode and toggle-mode each land the new mode in localStorage"
      (registry/register-xray-handlers!)
      (rf/make-frame {:id :rf/xray})
      (frame-dispatch [:rf.xray/set-mode :static])
      (is (= :static (static-persistence/load)))
      (frame-dispatch [:rf.xray/toggle-mode])
      (is (= :dynamic (static-persistence/load))))))
