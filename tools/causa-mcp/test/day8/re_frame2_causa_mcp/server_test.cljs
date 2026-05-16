(ns day8.re-frame2-causa-mcp.server-test
  "Placeholder test for the F-1 substrate scaffold (rf2-8xzoe.1).

  At F-1 the only meaningful assertion is *the build substrate
  compiles and exercises code in both `src/` and `test/`*. Real unit
  tests — bencode framing, MCP envelope shape, tool dispatch,
  dedup round-trips — land in subsequent F-tranche beads of
  rf2-8xzoe, mirroring `tools/pair2-mcp/test/re_frame_pair2_mcp/`
  one-for-one.

  This file is a real `deftest` (not a TODO comment) so the
  `:server-test` build has a non-trivial entry-point to compile and
  `npm test` exits 0 on the green path."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa-mcp.server :as server]))

(deftest server-ns-loads
  (testing "the placeholder server ns is loadable and exposes `main`"
    (is (some? (resolve 'day8.re-frame2-causa-mcp.server/main))
        "server/main must be defined so shadow-cljs's :node-script
         `:main` slot can point at a real var")
    (is (fn? server/main)
        "server/main must be a callable fn (not a value)")))

(deftest server-main-is-callable
  (testing "calling `main` with no args runs without throwing"
    (is (nil? (server/main))
        "F-1 scaffold `main` returns nil after printing its banner")))
