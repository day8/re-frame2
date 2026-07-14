(ns re-frame.ui.test-outcome-cljs-test
  "Node-runnable presence check for the `with-root` outcome policy
  (rf2-vxgfnd.201): a mount/render/body/flush/cleanup failure is tracked by
  PRESENCE, never by JS truthiness, so a legitimately falsy reason (nil / false)
  is a real failure — and a falsy body value is a real success, not a failure.

  `with-root-outcome` is the ONE place that decision lives; pinning it here
  exercises the fix off the DOM (the mounted end-to-end behaviour — real React
  ownership + total teardown — is covered by the browser suite
  `re-frame.ui.test-tier3-dom-cljs-test`). A mutation restoring a truthiness
  check (e.g. `(cond primary [:reject primary] ...)` over the values instead of
  the presence flags) turns these `is` assertions red."
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame.ui.test :as uit]))

(deftest with-root-outcome-tracks-failures-by-presence
  (testing "a present PRIMARY failure rejects even when its reason is falsy"
    (is (= [:reject false]
           (uit/with-root-outcome true false false nil ::body-result))
        "Promise.reject(false) from mount/render/body/flush is a real failure")
    (is (= [:reject nil]
           (uit/with-root-outcome true nil false nil ::body-result))
        "Promise.reject(nil) is a real failure, not a swallowed success"))

  (testing "a present CLEANUP-only failure rejects even when its reason is falsy"
    (is (= [:reject false]
           (uit/with-root-outcome false nil true false ::body-result)))
    (is (= [:reject nil]
           (uit/with-root-outcome false nil true nil ::body-result))))

  (testing "an ABSENT failure resolves the awaited body value — falsy ones too"
    (is (= [:resolve false]
           (uit/with-root-outcome false nil false nil false))
        "a body that returns false succeeds with false")
    (is (= [:resolve nil]
           (uit/with-root-outcome false nil false nil nil))
        "a body that returns nil succeeds with nil")
    (is (= [:resolve ::ok]
           (uit/with-root-outcome false nil false nil ::ok))))

  (testing "first-failure ordering: a present primary wins over a present cleanup"
    (let [primary (js/Error. "primary")
          cleanup (js/Error. "cleanup")
          [k v]   (uit/with-root-outcome true primary true cleanup nil)]
      (is (= :reject k))
      (is (identical? primary v) "the primary reason is the rejection")
      (is (= "cleanup" (.-message (unchecked-get v "rfUiTestCleanupError")))
          "the secondary cleanup failure rides the object primary as a diagnostic"))))
