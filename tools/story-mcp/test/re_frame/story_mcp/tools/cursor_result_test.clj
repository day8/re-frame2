(ns re-frame.story-mcp.tools.cursor-result-test
  "The result-shaping helpers the Docs `list-*` handlers compose on, at the
  points no handler call reaches: the default and ceiling story-mcp bakes
  into `parse-limit-arg`, the payload range gate on `decode-cursor`
  (exercised with forged cursors the encoder never mints), and
  `error-result`'s envelope. Paging itself is pinned end to end through the
  `list-*` handlers in tools_test.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.cursor :as rf.mcp-base.cursor]
            [re-frame.story-mcp.tools.cursor :as rf.story-mcp.tools.cursor]
            [re-frame.story-mcp.tools.result :as rf.story-mcp.tools.result]))

(deftest parse-limit-arg-clamps-and-defaults
  (is (= [rf.story-mcp.tools.cursor/default-limit rf.story-mcp.tools.cursor/max-limit]
         (map rf.story-mcp.tools.cursor/parse-limit-arg [nil 99999]))))

(defn- forge-cursor
  "A cursor token minted from a raw payload without `encode-cursor`, as an
  agent hand-editing the opaque token would produce."
  [payload]
  (rf.mcp-base.cursor/b64-encode (pr-str payload)))

(defn- live-sig-for
  "The fingerprint `page` computes for `entries`, read off a real cursor, so
  a forged payload isolates the range gate from the fingerprint gate."
  [entries]
  (let [[_ _ m] (rf.story-mcp.tools.cursor/page entries entries {:limit 1} "t")]
    (:sig (rf.story-mcp.tools.cursor/decode-cursor (:next-cursor m)))))

(deftest page-out-of-range-cursor-recovers-through-cursor-stale
  ;; Unchecked, a negative offset throws out of `subvec` and an over-total
  ;; one silently returns an empty page that loses the tail.
  (let [entries (vec (range 5))]
    (doseq [offset [-1 99]]
      (testing (str "offset " offset)
        (let [forged           (forge-cursor {:v 1 :offset offset :total 5 :sig (live-sig-for entries)})
              [res err-result] (rf.story-mcp.tools.cursor/page entries entries {:cursor forged} "list-things")]
          (is (= [:err true {:reason :rf.mcp/cursor-stale :tool "list-things"}]
                 [res (:isError err-result)
                  (select-keys (:structuredContent err-result) [:reason :tool])])))))))

(deftest error-result-shapes-iserror-envelope
  (is (= {:content [{:type "text" :text "boom"}] :isError true :structuredContent {:rf.error :x/y}}
         (rf.story-mcp.tools.result/error-result "boom" {:rf.error :x/y})))
  (is (= {:content [{:type "text" :text "boom"}] :isError true}
         (rf.story-mcp.tools.result/error-result "boom"))
      "the single arity omits :structuredContent"))
