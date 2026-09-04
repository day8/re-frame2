(ns re-frame.mcp-conformance.egress-profile-test
  "Cross-MCP `:rf.egress/*` profile-table conformance (EP-0015 §10,
  rf2-qus09h).

  EP-0015 graduates the named-egress model: an off-box MCP surface
  chooses *which boundary is this?* — a named `:rf.egress/*` profile —
  and the framework resolves the profile to its `:rf.size/*` floor. The
  authoritative table is `re-frame.projection/profile-size-opts`
  (`implementation/core`), but the MCP servers must not pull the
  framework runtime graph into their bundles, so `mcp-base/egress.cljc`
  carries a pure-data MIRROR (`re-frame.mcp-base.egress/profile->size-opts`)
  the servers resolve against server-side.

  ## What this gate guards

  Two copies of a closed enum drift silently unless something pins them.
  This test loads BOTH — the framework table and the mcp-base mirror —
  and asserts:

    1. The profile NAME sets are identical (a profile added / renamed in
       the framework that does not land in the mirror trips here).
    2. Every profile resolves to the SAME `:rf.size/*` floor in both
       (an opt-set change — e.g. flipping `off-box-tool`'s digests — that
       does not land in the mirror trips here).

  So the off-box-tool default the MCP servers ship is provably the
  framework's off-box-tool floor, and the graduation-gate evidence (the
  pair-MCP wire exercising `:rf.egress/off-box-tool`) is honest.

  This is the cross-MCP sibling of `redacted-sentinel-test` and the
  `canonical-markers` schema pins — it guards a wire-vocabulary contract
  rather than a marker shape."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.egress :as rf.mcp-base.egress]
            [re-frame.projection :as rf.projection]))

(defn- framework-table
  "The framework's profile→`:rf.size/*` resolution as a plain map, built
  via the PUBLIC `re-frame.projection/profile-size-opts` introspection
  fn over the public `profiles` set (Spec 015 §The graduation gate:
  `profile-size-opts` is public so a tool can introspect the resolution
  without re-deriving the private table)."
  []
  (into {}
        (map (fn [p] [p (rf.projection/profile-size-opts p)]))
        rf.projection/profiles))

(deftest mcp-base-mirror-profile-names-match-framework
  (testing "the mcp-base egress mirror enumerates exactly the framework's six profiles"
    (is (= rf.projection/profiles rf.mcp-base.egress/profiles)
        (str "mcp-base/egress.cljc `profiles` set has drifted from "
             "`re-frame.projection/profiles` — a profile was added / "
             "renamed in the framework without updating the cross-MCP "
             "mirror. EP-0015 §10 closed enum."))))

(deftest mcp-base-mirror-resolves-identically-to-framework
  (testing "every profile resolves to the SAME :rf.size/* floor in both tables"
    (is (= (framework-table) rf.mcp-base.egress/profile->size-opts)
        (str "mcp-base/egress.cljc `profile->size-opts` has drifted from "
             "`re-frame.projection/profile-size-opts` — a profile's "
             ":rf.size/* floor differs between the framework (authoritative) "
             "and the MCP-server mirror. The MCP wire would then resolve a "
             "named profile to a DIFFERENT opt-set than the framework's "
             "`project-egress` would, breaking the EP-0015 §10 "
             "single-source-of-truth contract."))))

(deftest off-box-tool-floor-is-the-mcp-default
  (testing "off-box-tool — the MCP servers' default boundary — redacts sensitive, elides large, keeps structural digests"
    (let [opts (rf.mcp-base.egress/profile-size-opts :rf.egress/off-box-tool)]
      (is (false? (:rf.size/include-sensitive? opts))
          "off-box-tool MUST redact sensitive (the off-box default)")
      (is (false? (:rf.size/include-large? opts))
          "off-box-tool MUST elide large (the off-box default)")
      (is (true? (:rf.size/include-digests? opts))
          (str "off-box-tool MUST include structural digests — the §10 "
               "\"include structural indicators / counters so the tool can "
               "reason about shape without seeing content\" clause.")))))

(deftest local-raw-floor-is-the-trusted-local-opt-in
  (testing "local-raw — the --allow-sensitive-reads opt-in boundary — passes sensitive AND large through"
    (let [opts (rf.mcp-base.egress/profile-size-opts :rf.egress/local-raw)]
      (is (true? (:rf.size/include-sensitive? opts))
          "local-raw passes declared-sensitive slots through (operator opt-in)")
      (is (true? (:rf.size/include-large? opts))
          "local-raw passes large slots through (operator opt-in)"))))

(deftest unknown-profile-resolves-nil
  (testing "an unknown profile resolves nil (closed enum — the caller never silently gets a permissive walk)"
    (is (nil? (rf.mcp-base.egress/profile-size-opts :rf.egress/not-a-profile)))
    (is (nil? (rf.mcp-base.egress/profile-size-opts nil)))))
