(ns re-frame.story.ui.shell-error-ownership-cljs-test
  "rf2-8yyd — the mounted Story shell OWNS the `:errors` stream, so
  `re-frame.error-emit`'s untooled-dev console fallback stays quiet while
  Story runs its deliberately-failing variants.

  The fallback (rf2-fu75) prints a promoted `:rf.error/*` record to
  `console.error` when — and only when — the corpus-wide `:errors`
  listener registry is EMPTY. Story's testbeds run failing scenarios on
  purpose (`failing-event-throws`, `failing-play`, `failing-fx-stub-miss`,
  `deliberately-failing`), so every one of those printed a console line;
  227 of them redded the Story feature-load browser gate, which treats a
  console error as fatal.

  The assertions below pin the EXACT registry the fallback keys on
  (`re-frame.error-emit/listeners`) rather than a proxy, so narrowing the
  claim to a different stream, a different id, or a `goog.DEBUG` branch
  the fallback does not consult would fail here rather than in a
  twelve-minute browser gate.

  What this namespace does NOT do is assert that a console error stops
  appearing — that is the browser gate's job
  (`npm run test:story-feature-load`), which counts console errors for
  real."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.story.ui.shell :as rf.story.ui.shell]))

(def ^:private claim!   @#'rf.story.ui.shell/claim-error-ownership!)
(def ^:private release! @#'rf.story.ui.shell/release-error-ownership!)

;; The registry the fallback's `(empty? @listeners)` guard reads. Private
;; in `error-emit` because it is not an app-facing surface; read here
;; because it is the precise thing this fix has to move.
(def ^:private listeners @#'rf.error-emit/listeners)

(use-fixtures :each
  {:before #(rf.error-emit/clear-error-listeners!)
   :after  #(rf.error-emit/clear-error-listeners!)})

(deftest claim-makes-the-fallback-registry-non-empty
  (testing "with no shell mounted the registry is empty — the fallback fires"
    (is (empty? @listeners)))
  (testing "claiming ownership leaves the registry non-empty, which is the
            whole off-switch: the fallback goes quiet for EVERY category"
    (claim!)
    (is (seq @listeners))))

(deftest release-restores-the-fallback
  (testing "releasing on unmount hands the untooled-dev console fallback
            back to whatever runs on the page next"
    (claim!)
    (is (seq @listeners))
    (release!)
    (is (empty? @listeners))))

(deftest claim-is-idempotent
  (testing "re-mounting a shell replaces the same id rather than stacking
            claims, so ONE release still frees the registry"
    (claim!)
    (claim!)
    (is (= 1 (count @listeners)))
    (release!)
    (is (empty? @listeners))))

(deftest release-never-drops-a-host-app-listener
  (testing "a consuming app's own `:errors` listener is registered under its
            own id — the shell's release drops only the shell's claim, and
            the app keeps ownership (and its records) across a Story unmount"
    (rf.error-emit/register-error-listener! ::host-app (fn [_record] nil))
    (claim!)
    (is (= 2 (count @listeners)))
    (release!)
    (is (= [::host-app] (vec (keys @listeners))))))
