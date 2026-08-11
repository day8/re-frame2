(ns re-frame.hicasso.rejection-attribution-control-dom-cljs-test
  "TEMPORARY REPRODUCTION CONTROL for rf2-d3tc — DELETE BEFORE COMMIT.

  A new `-dom-cljs-test` namespace sorting strictly between
  `re-frame.hicasso.reincarnation-paint-dom-cljs-test` and
  `re-frame.hicasso.revision-dom-cljs-test`, shaped exactly like the one
  PR #7936 adds: a FN-FORM `:each` fixture paired with an `async` row
  that is only reached in a browser.

  Under `cljs.test/execution-strategy` a fn-form fixture selects `:sync`,
  which wraps every test fn in `disable-async`; an `async` object coming
  back from one throws `::async-disabled`, and `test-var-block*` rethrows
  that as the STRING \"Async tests require fixtures to be specified as
  maps.  Testing aborted.\" — synchronously, inside whichever `done` call
  cljs.test happened to be running this namespace's block from."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

(deftest a-perfectly-ordinary-row
  (is (= 2 (+ 1 1))))

(deftest a-delayed-row-that-needs-async
  (if-not (mount/browser?)
    (is true "no DOM here")
    (async done
      (js/setTimeout (fn [] (is true "the delayed row ran") (done)) 0))))
