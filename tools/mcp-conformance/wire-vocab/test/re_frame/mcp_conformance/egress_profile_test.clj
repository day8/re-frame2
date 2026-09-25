(ns re-frame.mcp-conformance.egress-profile-test
  "Cross-MCP `:rf.egress/*` profile NAME-SET conformance (EP-0015 §10).

  EP-0015 graduates the named-egress model: an off-box MCP surface
  chooses *which boundary is this?* — a named `:rf.egress/*` profile —
  and the framework resolves the profile to its `:rf.egress/*` floor.

  ## What this gate guards

  Every tool-side egress NAMES a profile and
  `re-frame.core/project-egress` resolves it APP-SIDE, where the framework
  graph is loaded. `mcp-base` carries no copy of the framework's
  profile→`:rf.egress/*` table, so there is no table to drift and no
  per-profile floor to pin here — the floors are the framework's own to
  pin, and `implementation/core` pins them.

  The half that IS two copies is the profile NAME SET. `mcp-base` must
  enumerate exactly the framework's six names,
  because `mcp-tool-profile` returns one of those names and
  `project-egress` throws `:rf.error/unknown-egress-profile` on anything
  outside its own closed enum. A profile added or renamed in the framework
  that does not land in mcp-base would ship a name the door refuses — at
  runtime, on an off-box read. This gate turns that into a build failure.

  This is the cross-MCP sibling of `redacted-sentinel-test` and the
  `canonical-markers` schema pins — it guards a wire-vocabulary contract
  rather than a marker shape."
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
