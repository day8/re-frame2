(ns re-frame.routing-root-base-path-test
  "A root mount must keep local hrefs local (Spec 012 URL strategies)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.routing.strategy :as rf.routing.strategy]))

(deftest a-base-joins-a-path-with-one-slash
  (doseq [[base path href] [["/"     "/active" "/active"]
                            ["/"     "/"       "/"]
                            ["/"     "active"  "active"]
                            [""      "/active" "/active"]
                            [""      "active"  "active"]
                            ["/app"  "/active" "/app/active"]
                            ["/app/" "/active" "/app/active"]]]
    (is (= href ((:encode (rf.routing.strategy/with-base-path
                            rf.routing.strategy/history-url-strategy base))
                 path))
        (str (pr-str base) " " (pr-str path)))))
