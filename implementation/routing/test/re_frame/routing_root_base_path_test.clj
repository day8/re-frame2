(ns re-frame.routing-root-base-path-test
  "`with-base-path` treats the site root as no base. Its `:encode` prefixes the
  normalised base onto the wrapped strategy's href, so a base left as `\"/\"`
  would encode `/active` as `//active` — a protocol-relative reference, which a
  browser resolves against the HOST `active`, sending every `route-link` href
  and pushed URL off-site. Per Spec 012 §URL strategies."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.routing.strategy :as rf.routing.strategy]))

(deftest a-root-base-is-no-base
  (doseq [strategy [rf.routing.strategy/history-url-strategy
                    rf.routing.strategy/hash-url-strategy]
          base     ["/" "//"]]
    (is (identical? strategy (rf.routing.strategy/with-base-path strategy base))
        (pr-str base))))

(deftest a-base-joins-a-path-with-one-slash
  (testing "the root encodes exactly as no base; a sub-path base, with or
            without its trailing slash, joins a path-form URL with one `/`"
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
          (str (pr-str base) " " (pr-str path))))))
