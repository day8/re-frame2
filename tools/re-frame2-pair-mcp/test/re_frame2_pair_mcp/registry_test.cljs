(ns re-frame2-pair-mcp.registry-test
  "Structural completeness of the single tool registry (rf2-47g8l;
  ratchet added by rf2-wnrpi).

  The whole point of the single `registry/tools` source (rf2-47g8l) is
  that the three derived views — `tool-descriptors`, `handler-for`, and
  `cacheable?` — cannot drift, because each is generated from the one
  vector. `cache_test` already pins `cacheable?` for the named tools;
  this suite pins the OTHER derived view that the dispatcher relies on:
  every descriptor name resolves to a handler, and vice versa.

  Without this ratchet a tool added to the descriptor list (or renamed)
  without a matching handler entry would ship green from every unit
  suite and surface only as a `:reason :unknown-tool` at the post-
  compile stdio layer. This pins the contract at the source."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(deftest handler-for-keys-match-descriptor-names
  (testing "every descriptor name resolves to exactly one handler — no drift"
    (let [descriptor-names (set (map :name registry/tool-descriptors))
          handler-keys     (set (keys registry/handler-for))]
      (is (= descriptor-names handler-keys)
          "handler-for keys MUST equal descriptor names — the rf2-47g8l guarantee"))))

(deftest every-handler-is-a-fn
  (testing "each registered handler is callable (the 3-arity dispatch shape)"
    (doseq [[name handler] registry/handler-for]
      (is (fn? handler) (str "handler for " name " must be a fn")))))

(deftest registry-names-are-unique
  (testing "no duplicate :name in the catalogue — a dup would silently shadow"
    (let [names (map :name registry/tools)]
      (is (= (count names) (count (set names)))
          "duplicate tool name in registry/tools"))))

(deftest catalogue-size-matches-derived-views
  (testing "all three derived views enumerate the same tool count"
    (let [n (count registry/tools)]
      (is (= n (count registry/tool-descriptors)))
      (is (= n (count registry/handler-for))))))

(deftest cacheable-only-names-registered-tools
  (testing "cacheable? is false for any name not in the catalogue"
    (is (not (registry/cacheable? "definitely-not-a-tool")))
    (is (not (registry/cacheable? "")))))
