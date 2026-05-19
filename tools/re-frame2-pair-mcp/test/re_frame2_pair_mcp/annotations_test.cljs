(ns re-frame2-pair-mcp.annotations-test
  "Pin rf2-94p8q — every MCP tool descriptor MUST carry an
  `:annotations` map advertising the MCP tool-annotation hints
  (`readOnlyHint`, `destructiveHint`, `idempotentHint`,
  `openWorldHint`). Agent hosts use these to auto-approve read-only
  ops and gate destructive ops behind explicit confirmation.

  At least one of `:readOnlyHint` or `:destructiveHint` MUST be
  present on every tool — that's the load-bearing classification.
  Other slots (`:idempotentHint`, `:openWorldHint`) are optional
  refinements."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools :as tools]))

(defn- has-read-or-destructive? [a]
  (or (true? (:readOnlyHint a))
      (true? (:destructiveHint a))
      (true? (:openWorldHint a))))

(deftest every-descriptor-carries-annotations
  (testing "tool-descriptors: every entry has a map :annotations"
    (doseq [d tools/tool-descriptors]
      (is (map? (:annotations d))
          (str "missing :annotations on " (:name d)))
      (is (has-read-or-destructive? (:annotations d))
          (str "annotations on " (:name d)
               " carries no classification hint — at least one of "
               "readOnlyHint / destructiveHint / openWorldHint must be set")))))

(deftest annotations-surface-on-tools-list
  (testing "tool-descriptors-js projects :annotations to the wire"
    (let [arr (tools/tool-descriptors-js)
          n   (alength arr)]
      (is (pos? n) "at least one descriptor exists")
      (doseq [i (range n)]
        (let [desc (aget arr i)
              an   (j/get desc :annotations)
              name (j/get desc :name)]
          (is (some? an)
              (str "missing annotations on tool " name))
          (is (object? an)
              (str "annotations on tool " name " is not a JS object")))))))

(deftest classification-matrix-matches-bead
  (testing "annotation matrix matches the rf2-94p8q matrix"
    ;; Spot-check the key classifications from the bead. A future
    ;; rebalance is a behaviour change, not an accident — this test
    ;; surfaces it.
    (let [by-name (into {} (map (juxt :name :annotations)) tools/tool-descriptors)]
      ;; Read-only tools
      (doseq [n ["discover-app" "snapshot" "get-path" "trace-window"
                 "watch-epochs" "list-subscriptions" "handler-meta"
                 "list-handlers" "get-re-frame2-pair-instructions"
                 "tail-build"]]
        (is (true? (:readOnlyHint (by-name n)))
            (str n " should have readOnlyHint true")))
      ;; Destructive tools
      (doseq [n ["dispatch" "eval-cljs"]]
        (is (true? (:destructiveHint (by-name n)))
            (str n " should have destructiveHint true")))
      ;; Streaming tools — neither readOnly nor destructive by default;
      ;; openWorldHint true because they touch the runtime's
      ;; streaming bus.
      (doseq [n ["subscribe" "unsubscribe"]]
        (is (true? (:openWorldHint (by-name n)))
            (str n " should have openWorldHint true"))))))
