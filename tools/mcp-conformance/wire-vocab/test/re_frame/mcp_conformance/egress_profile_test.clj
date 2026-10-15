(ns re-frame.mcp-conformance.egress-profile-test
  "Cross-MCP profile-name compatibility.
  Both servers name framework-owned profiles; only the framework resolves floors."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.egress :as rf.mcp-base.egress]
            [re-frame.projection :as rf.projection]))

(deftest mcp-base-mirror-profile-names-match-framework
  (testing "the mcp-base egress vocabulary enumerates exactly the framework's six profiles"
    (is (= rf.projection/profiles rf.mcp-base.egress/profiles)
        (str "mcp-base/egress.cljc `profiles` set has drifted from "
             "`re-frame.projection/profiles` — a profile was added / "
             "renamed in the framework without updating the cross-MCP "
             "vocabulary. EP-0015 §10 closed enum."))))

(deftest mcp-tool-profile-returns-a-name-the-framework-door-accepts
  (testing (str "the posture mapping the MCP servers share must "
                "return a name `project-egress` will resolve, on BOTH postures. "
                "This is the whole reason the name sets must agree: the servers "
                "do not resolve a floor, they ship a keyword the door reads.")
    (doseq [posture [false true]]
      (let [profile (rf.mcp-base.egress/mcp-tool-profile posture)]
        (is (contains? rf.projection/profiles profile)
            (str "mcp-tool-profile returned " profile " for posture " posture
                 ", which is not in the framework's closed `profiles` enum — "
                 "`project-egress` would throw :rf.error/unknown-egress-profile "
                 "on a live off-box read."))
        (is (some? (rf.projection/profile-size-opts profile))
            (str "the framework resolves " profile " to no floor at all"))))))
