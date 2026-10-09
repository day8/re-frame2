(ns re-frame.mcp-base.egress-test
  "Cross-host test of the posture -> `:rf.egress/*` profile mapping both
  MCP servers call. The permission gate that computes the posture is each
  consumer's integration test; the framework pins what each profile
  resolves to; the mcp-conformance wire-vocab gate pins `profiles` to the
  framework's set. `.cljc` so it also runs in the shadow-cljs `cljs-test`
  build."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [re-frame.mcp-base.egress :as rf.mcp-base.egress]))

(deftest mcp-tool-profile-maps-posture-to-named-boundary
  ;; Not opted in => the MCP/AI tool wire; the trusted-local opt-in => raw.
  (is (= :rf.egress/off-box-tool (rf.mcp-base.egress/mcp-tool-profile false)))
  (is (= :rf.egress/local-raw (rf.mcp-base.egress/mcp-tool-profile true))))
