(ns re-frame.mcp-base.egress-test
  "Canonical cross-host tests for the shared `:rf.egress/*` posture
  mapping. `mcp-tool-profile` is the ONE pure fn that maps an MCP tool
  server's already-permission-gated sensitive-read posture to its named
  boundary profile (EP-0015 §10). Both MCP servers (story-mcp,
  re-frame2-pair-mcp) previously duplicated this exact two-value `if`;
  the mapping now lives here once and is pinned here once.

  `.cljc` so it runs on BOTH hosts: the JVM `:test` alias
  (cognitect-labs test-runner) exercises the story-mcp runtime, and the
  shadow-cljs `cljs-test` build (its `:ns-regexp` selects `egress-test`)
  exercises the re-frame2-pair-mcp (Node) runtime — both consumers ship
  the same mapping on the exact runtimes they run on.

  The per-server permission GATE (`--allow-sensitive-reads` + per-call
  `:include-sensitive`) stays an INTEGRATION test in each consumer; only
  the pure posture→profile mapping is owned here.

  rf2-kuky.88 dropped the end-to-end posture→profile→`:rf.size/*` floor
  test along with the pure-data mirror it read. mcp-base no longer
  resolves a profile — every tool-side egress NAMES one and
  `re-frame.core/project-egress` resolves it app-side — so the floors are
  the framework's to pin, and `implementation/core` pins them. What
  mcp-base still owns, and what stays pinned here, is that the mapping
  returns a member of the closed enum."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.mcp-base.egress :as rf.mcp-base.egress]))

;; ---------------------------------------------------------------------------
;; mcp-tool-profile — the pure two-value posture→profile mapping.
;;
;; false ⇒ the off-box MCP/AI tool wire; true ⇒ the trusted-local raw
;; read. The permission decision that produces the boolean is the
;; consumer's job (and is tested there); this fn is pure.
;; ---------------------------------------------------------------------------

(deftest mcp-tool-profile-maps-posture-to-named-boundary
  (testing "not-opted-in ⇒ the MCP/AI tool wire boundary"
    (is (= :rf.egress/off-box-tool (rf.mcp-base.egress/mcp-tool-profile false))))
  (testing "trusted-local opt-in ⇒ the raw boundary"
    (is (= :rf.egress/local-raw (rf.mcp-base.egress/mcp-tool-profile true)))))

(deftest mcp-tool-profile-returns-members-of-the-closed-enum
  ;; Both values it can return MUST be members of the closed
  ;; `:rf.egress/profile` vocabulary, so a profile rename in the shared
  ;; table can never leave this mapping pointing at a phantom profile.
  (is (contains? rf.mcp-base.egress/profiles (rf.mcp-base.egress/mcp-tool-profile false)))
  (is (contains? rf.mcp-base.egress/profiles (rf.mcp-base.egress/mcp-tool-profile true))))
