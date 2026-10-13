(ns re-frame.redact-path-vector-parent-cljs-test
  "`re-frame.privacy/redact-event` writes only through a parent that can TAKE
  the leaf segment. The path-overlap arm derives payload paths from the DB's
  classification, not the payload's shape, and the router computes that
  redaction in `prepare-handler-ctx`, outside the chain's capture, so a throw
  from `assoc-in` (a keyword segment on a vector, say) would escape
  `dispatch-sync` and lose the event. Always-on: no posture tag."
  (:require #?(:clj  [clojure.test :refer [are deftest testing]]
               :cljs [cljs.test :refer-macros [are deftest testing]])
            [re-frame.privacy :as rf.privacy]))

(deftest redact-event-takes-only-a-parent-that-can-take-the-segment
  (testing "a map parent redacts; a vector parent redacts only under an
            in-range integer segment; anything else is a no-op, never a throw;
            an empty path scrubs the whole payload"
    (are [payload path expected] (= [:e expected] (rf.privacy/redact-event [:e payload] [path]))
      {:cards {:number "4111"}}               [:cards :number] {:cards {:number :rf/redacted}}
      {:cards [{:number "4111"}]}             [:cards :number] {:cards [{:number "4111"}]}
      {:cards [{:number "1"} {:number "2"}]}  [:cards 0]       {:cards [:rf/redacted {:number "2"}]}
      {:cards [{:number "1"}]}                [:cards 1]       {:cards [{:number "1"}]}
      {:auth "tok"}                           [:auth :password] {:auth "tok"}
      {:any "thing"}                          []               :rf/redacted)))
