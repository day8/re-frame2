(ns day8.re-frame2-causa-mcp.path-slice-test
  "Unit tests for the W-2 path-slice wire-pipeline mechanism at the
  Causa-MCP boundary (rf2-8xzoe.6). Pins:

    - MUST 8 — direct-read tools (`get-app-db`, `get-machine-state`,
      `get-epoch-history`) MUST accept the `:path` arg
      (spec/004 §2 L260-262).
    - MUST 9 (path-arg `nil` half) — absent `:path` parses to `nil`,
      signalling the dispatcher to take the W-4 summary branch
      (spec/004 §2 L264-266). This file pins the `nil` return; the
      W-4 summary-mode default landing in W-4 (rf2-8xzoe.8) pins
      the dispatcher behaviour.
    - Out-of-range — `:path-not-found` shape with
      `:deepest-valid-prefix` (spec/004 §2 L267-270).
    - Cross-MCP path-arg vocabulary — accept-shape parity with
      pair2-mcp's `tools.args/parse-path-arg`.
    - Composition with W-1 (token-cap) + W-6 (elision) + B-1
      (privacy) — wrappers are additive, share envelope shape.

  These tests are the load-bearing pin for the downstream tree-typed
  direct-read tools — every dispatcher that returns a tree-typed
  payload calls `path-slice/apply-to-result` once at the boundary
  when the `:path` arg is non-nil."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.path-slice :as path-slice]
            [day8.re-frame2-causa-mcp.privacy :as privacy]))

;; The canonical large-elided marker, as the framework walker would
;; emit. Reused across composition tests so the W-6 surface stays
;; visible inside W-2's sliced subtrees.
(def ^:private elided-marker
  {:rf.size/large-elided
   {:path   [:user :uploaded-pdf]
    :bytes  102400
    :type   :string
    :reason :declared
    :handle [:rf.elision/at [:user :uploaded-pdf]]}})

;; ---------------------------------------------------------------------------
;; Public surface — vars exist and are callable.
;; ---------------------------------------------------------------------------

(deftest public-surface-resolvable
  (testing "W-2 lands the public surface downstream direct-read tool
            dispatchers will require"
    (is (fn? path-slice/parse-path-arg))
    (is (fn? path-slice/path-arg))
    (is (fn? path-slice/deepest-valid-prefix))
    (is (fn? path-slice/path-not-found))
    (is (fn? path-slice/resolve-path))
    (is (fn? path-slice/apply-to-result))
    (is (fn? path-slice/coerce-path-segment))))

;; ---------------------------------------------------------------------------
;; parse-path-arg — the cross-MCP accept-shape contract.
;; ---------------------------------------------------------------------------

(deftest parse-path-arg-nil-is-nil
  ;; MUST 9 half: absent path parses to nil, signalling W-4
  ;; default-summary on the dispatcher side.
  (is (nil? (path-slice/parse-path-arg nil))))

(deftest parse-path-arg-blank-string-is-nil
  (is (nil? (path-slice/parse-path-arg "")))
  (is (nil? (path-slice/parse-path-arg "   ")))
  (is (nil? (path-slice/parse-path-arg "\t\n"))))

(deftest parse-path-arg-edn-vector-string
  (is (= [:cart :items 0] (path-slice/parse-path-arg "[:cart :items 0]")))
  (is (= [:a :b :c] (path-slice/parse-path-arg "[:a :b :c]")))
  (is (= [:cart :items 3 :sku]
         (path-slice/parse-path-arg "[:cart :items 3 :sku]"))
      "spec/004 §2 L261 example path"))

(deftest parse-path-arg-edn-empty-vector-is-root
  (is (= [] (path-slice/parse-path-arg "[]"))
      "explicit empty path addresses the root"))

(deftest parse-path-arg-cljs-vector-passes-through
  (is (= [:a :b :c] (path-slice/parse-path-arg [:a :b :c])))
  (is (= [] (path-slice/parse-path-arg []))))

(deftest parse-path-arg-cljs-sequential-coerces-to-vector
  (is (= [:a :b :c] (path-slice/parse-path-arg (list :a :b :c)))))

(deftest parse-path-arg-js-array-of-edn-strings
  ;; Each segment is parsed as EDN: keywords, integers.
  (is (= [:cart :items 0]
         (path-slice/parse-path-arg #js [":cart" ":items" "0"]))))

(deftest parse-path-arg-js-array-with-bare-strings-stays-strings
  ;; Non-EDN segments pass through as map keys (the agent may have
  ;; an app-db keyed by strings rather than keywords).
  (is (= [:a "bare-key" :b]
         (path-slice/parse-path-arg #js [":a" "bare-key" ":b"]))))

(deftest parse-path-arg-non-vector-edn-wraps-as-single-segment
  ;; A lone keyword string becomes a 1-segment path.
  (is (= [:foo] (path-slice/parse-path-arg ":foo")))
  ;; A bare integer string also becomes a 1-segment path.
  (is (= [42] (path-slice/parse-path-arg "42"))))

(deftest parse-path-arg-unparseable-string-is-single-string-segment
  ;; Pathological EDN — fall back to treating the whole string as one
  ;; map-key segment rather than raising. Permissive on agent input.
  (is (= ["((("] (path-slice/parse-path-arg "((("))))

;; ---------------------------------------------------------------------------
;; path-arg — the MCP-args object slot resolver.
;; ---------------------------------------------------------------------------

(deftest path-arg-absent-returns-nil
  ;; Default for the missing slot — same nil-signal the parser uses.
  (is (nil? (path-slice/path-arg nil)))
  (is (nil? (path-slice/path-arg js/undefined)))
  (is (nil? (path-slice/path-arg #js {})))
  (is (nil? (path-slice/path-arg #js {:other 1}))))

(deftest path-arg-from-js-object
  ;; The MCP SDK hands the dispatcher a JS args object; the helper
  ;; reads `path` off it and routes through the parser.
  (is (= [:cart :items 0]
         (path-slice/path-arg #js {"path" "[:cart :items 0]"})))
  (is (= [:foo]
         (path-slice/path-arg #js {"path" ":foo"}))))

(deftest path-arg-from-cljs-map
  ;; Some upstream paths hand the helper a CLJS map (already coerced).
  ;; Both keyword and stringified-key entry points resolve.
  (is (= [:a :b] (path-slice/path-arg {:path "[:a :b]"})))
  (is (= [:a :b] (path-slice/path-arg {"path" "[:a :b]"})))
  (is (= [:a :b] (path-slice/path-arg {:path [:a :b]}))))

(deftest path-arg-empty-vector-roundtrips-as-root
  (is (= [] (path-slice/path-arg #js {"path" "[]"}))))

;; ---------------------------------------------------------------------------
;; deepest-valid-prefix — re-aim hint.
;; ---------------------------------------------------------------------------

(deftest deepest-valid-prefix-empty-path-returns-empty
  (is (= [] (path-slice/deepest-valid-prefix {:a 1} []))))

(deftest deepest-valid-prefix-full-resolution-returns-full-path
  (is (= [:a :b]
         (path-slice/deepest-valid-prefix {:a {:b 42}} [:a :b]))))

(deftest deepest-valid-prefix-truncates-at-first-miss
  (is (= [:a]
         (path-slice/deepest-valid-prefix {:a {:b 42}} [:a :c :d]))))

(deftest deepest-valid-prefix-empty-on-first-segment-miss
  (is (= []
         (path-slice/deepest-valid-prefix {:a 1} [:no-such-key :b :c]))))

(deftest deepest-valid-prefix-handles-vector-indices
  ;; Full path resolves — prefix IS the full path.
  (is (= [:items 0 :sku]
         (path-slice/deepest-valid-prefix {:items [{:sku "abc"}]} [:items 0 :sku])))
  ;; Path that ends one segment past a vector resolution stops at the
  ;; deepest valid prefix.
  (is (= [:items 0]
         (path-slice/deepest-valid-prefix {:items [{:sku "abc"}]} [:items 0 :nope]))))

(deftest deepest-valid-prefix-stops-at-out-of-range-index
  (is (= [:items]
         (path-slice/deepest-valid-prefix {:items [{:sku "abc"}]} [:items 5 :sku]))))

(deftest deepest-valid-prefix-stops-at-scalar-leaf
  ;; Path tries to descend INTO a scalar leaf — the walker halts.
  (is (= [:a :b]
         (path-slice/deepest-valid-prefix {:a {:b 42}} [:a :b :nope]))))

(deftest deepest-valid-prefix-handles-empty-tree
  (is (= []
         (path-slice/deepest-valid-prefix {} [:any :path]))))

;; ---------------------------------------------------------------------------
;; path-not-found — structured error shape.
;; ---------------------------------------------------------------------------

(deftest path-not-found-shape
  (let [tree {:a {:b 42}}
        result (path-slice/path-not-found tree [:a :c :d])]
    (is (= {:ok?                  false
            :reason               :path-not-found
            :path                 [:a :c :d]
            :deepest-valid-prefix [:a]}
           result))))

(deftest path-not-found-reason-keyword-pin
  ;; Pin the cross-MCP convention: `:reason :path-not-found` is the
  ;; sentinel agents pattern-match on for re-aim. Sibling to
  ;; `:rf.mcp/cursor-stale` for cursor pagination.
  (is (= :path-not-found
         (:reason (path-slice/path-not-found {} [:any])))))

(deftest path-not-found-on-empty-tree
  (is (= [] (:deepest-valid-prefix (path-slice/path-not-found {} [:nope])))))

(deftest path-not-found-deep-prefix
  (let [tree {:cart {:items [{:sku "abc" :qty 3}]}}
        result (path-slice/path-not-found tree [:cart :items 0 :no-such])]
    (is (= [:cart :items 0] (:deepest-valid-prefix result)))))

;; ---------------------------------------------------------------------------
;; resolve-path — sentinel-aware get-in.
;; ---------------------------------------------------------------------------

(deftest resolve-path-root-returns-tree
  (let [tree {:a 1 :b 2}]
    (is (= tree (path-slice/resolve-path tree [])))))

(deftest resolve-path-deep-resolution
  (is (= 42
         (path-slice/resolve-path {:cart {:items [{:sku "abc" :qty 42}]}}
                                  [:cart :items 0 :qty]))))

(deftest resolve-path-not-found-returns-sentinel
  (is (= ::path-slice/not-found
         (path-slice/resolve-path {:a 1} [:b]))))

(deftest resolve-path-nil-value-resolves-not-not-found
  ;; The load-bearing disambiguator: a path that legitimately
  ;; resolves to `nil` MUST NOT report `:path-not-found`.
  (is (= nil
         (path-slice/resolve-path {:a nil} [:a]))
      "nil-at-path resolves; the sentinel disambiguates from missing"))

(deftest resolve-path-false-value-resolves
  (is (= false
         (path-slice/resolve-path {:flag false} [:flag]))))

(deftest resolve-path-vector-out-of-range-is-not-found
  ;; `get-in` returns the not-found sentinel on out-of-range indices.
  (is (= ::path-slice/not-found
         (path-slice/resolve-path {:items [{:sku "abc"}]} [:items 5 :sku]))))

;; ---------------------------------------------------------------------------
;; apply-to-result — per-tool boundary wrapper.
;; ---------------------------------------------------------------------------

(deftest apply-to-result-writes-resolved-subtree
  ;; The happy path: path resolves; the addressed subtree rides
  ;; under `value-key`.
  (let [tree {:cart {:items [{:sku "abc"} {:sku "def"}]}}
        out  (path-slice/apply-to-result {} :db tree [:cart :items])]
    (is (= {:db [{:sku "abc"} {:sku "def"}]} out))))

(deftest apply-to-result-empty-path-writes-full-tree
  ;; `[]` (root) writes the full tree under `value-key`.
  (let [tree {:a 1 :b 2}
        out  (path-slice/apply-to-result {} :db tree [])]
    (is (= {:db tree} out))))

(deftest apply-to-result-out-of-range-splices-path-not-found
  ;; Out-of-range path: the canonical `:path-not-found` slots ride
  ;; on the envelope; `value-key` is NOT written (no slot to write).
  (let [tree {:a {:b 42}}
        out  (path-slice/apply-to-result {} :db tree [:a :c :d])]
    (is (false? (:ok? out)))
    (is (= :path-not-found (:reason out)))
    (is (= [:a :c :d] (:path out)))
    (is (= [:a] (:deepest-valid-prefix out)))
    (is (not (contains? out :db))
        ":db slot MUST NOT be written on a :path-not-found result")))

(deftest apply-to-result-preserves-existing-envelope-keys
  ;; The wrapper is additive — pre-existing slots on the envelope
  ;; (tool, mode, cache, etc.) survive. Same shape `privacy/apply-
  ;; to-result` and `elision/apply-to-result` provide.
  (let [tree {:a 1}
        out  (path-slice/apply-to-result
               {:tool "get-app-db" :mode :full} :db tree [:a])]
    (is (= 1 (:db out)))
    (is (= "get-app-db" (:tool out)))
    (is (= :full (:mode out)))))

(deftest apply-to-result-out-of-range-preserves-existing-envelope-keys
  ;; The path-not-found splice MUST also preserve upstream context.
  (let [tree {:a 1}
        out  (path-slice/apply-to-result
               {:tool "get-app-db" :mode :full} :db tree [:no-such])]
    (is (false? (:ok? out)))
    (is (= "get-app-db" (:tool out)))
    (is (= :full (:mode out)))))

(deftest apply-to-result-nil-value-resolves-not-not-found
  ;; A path that legitimately resolves to `nil` MUST write `nil`
  ;; under `value-key` rather than reporting `:path-not-found`.
  (let [tree {:flag nil}
        out  (path-slice/apply-to-result {} :db tree [:flag])]
    (is (nil? (:db out)))
    (is (contains? out :db)
        ":db slot present with nil value when path resolves")
    (is (not (contains? out :reason))
        ":reason slot MUST NOT be stamped when path resolves to nil")))

(deftest apply-to-result-false-value-resolves
  ;; Same disambiguator for `false`: a path resolving to `false` is
  ;; a successful resolution, not a not-found.
  (let [tree {:flag false}
        out  (path-slice/apply-to-result {} :db tree [:flag])]
    (is (= false (:db out)))
    (is (contains? out :db))))

(deftest apply-to-result-shape-for-tree-typed-tools
  ;; Pins the canonical shape every direct-read tool uses:
  ;;   `:db`    for `get-app-db`
  ;;   `:state` for `get-machine-state`
  ;;   `:epochs` for `get-epoch-history`
  ;; Same wrapper services all — uniform boundary site.
  (testing "get-app-db-shape (:db)"
    (let [out (path-slice/apply-to-result
                {} :db {:cart {:items []}} [:cart])]
      (is (= {:items []} (:db out)))))
  (testing "get-machine-state-shape (:state)"
    (let [out (path-slice/apply-to-result
                {} :state {:current :running :context {:k :v}}
                [:context :k])]
      (is (= :v (:state out)))))
  (testing "get-epoch-history-shape (:epochs)"
    (let [out (path-slice/apply-to-result
                {} :epochs
                {:epochs [{:id "ep-1"} {:id "ep-2"}]}
                [:epochs 0])]
      (is (= {:id "ep-1"} (:epochs out))))))

;; ---------------------------------------------------------------------------
;; Composition with W-6 (elision) — the canonical wire-pipeline cascade.
;; ---------------------------------------------------------------------------

(deftest composes-with-w6-elision-on-sliced-subtree
  ;; The dispatcher walked the tree server-side (W-6); the addressed
  ;; subtree carries an `:rf.size/large-elided` marker in place of
  ;; the large leaf. After W-2 slices, W-6's `apply-to-result`
  ;; counts the marker on the sliced value and stamps the
  ;; `:elided-large` counter. Both axes ride the same envelope.
  (let [tree    {:user {:uploaded-pdf elided-marker :name "alice"}}
        sliced  (-> {}
                    (path-slice/apply-to-result :db tree [:user]))
        result  (elision/apply-to-result sliced :db (:db sliced))]
    (is (= 1 (:elided-large result))
        ":elided-large counter rides on the sliced subtree")
    (is (= {:uploaded-pdf elided-marker :name "alice"}
           (:db result)))))

(deftest composes-with-w6-elision-on-path-not-found
  ;; Edge case: out-of-range path; the W-6 wrapper sees no `:db`
  ;; slot to walk. The composition stays safe — W-6 walks whatever
  ;; lives under `:db` (here, nothing), counts zero, and stamps
  ;; nothing.
  (let [tree   {:user {:name "alice"}}
        sliced (path-slice/apply-to-result {} :db tree [:no-such])
        ;; W-6 walks the absent :db slot (nil); zero markers, no
        ;; counter — clean path-not-found envelope.
        result (elision/apply-to-result sliced :db (:db sliced))]
    (is (false? (:ok? result)))
    (is (= :path-not-found (:reason result)))
    (is (not (contains? result :elided-large))
        "no markers to count on an absent :db slot ⇒ no counter")))

(deftest composes-with-b1-privacy-orthogonally
  ;; B-1 (privacy) and W-2 (path-slice) live on orthogonal surfaces
  ;; — trace-stream vs direct-read — but the envelope shapes compose
  ;; freely because both wrappers are additive. The combined
  ;; envelope can carry both axes' counters when an exotic tool
  ;; surfaces both.
  (let [events   [{:op :a} {:op :b :sensitive? true}]
        tree     {:cart {:items [{:sku "abc"}]}}
        envelope (-> {}
                     (privacy/apply-to-result :events events false)
                     (path-slice/apply-to-result :db tree [:cart :items]))]
    (is (= 1 (:dropped-sensitive envelope))
        "B-1 counter rides on the envelope")
    (is (= [{:op :a}] (:events envelope))
        "B-1 strips the sensitive event")
    (is (= [{:sku "abc"}] (:db envelope))
        "W-2 writes the addressed subtree alongside")))

;; ---------------------------------------------------------------------------
;; Load-bearing spec/004 §2 assertion — path slicing end-to-end.
;; ---------------------------------------------------------------------------

(deftest spec-004-path-arg-vocabulary-end-to-end
  (testing "MUST 8 — direct-read tools accept the `:path` arg and the
            parser handles every shape in the cross-MCP vocabulary"
    (is (= [:cart :items 3 :sku]
           (path-slice/path-arg
             #js {"path" "[:cart :items 3 :sku]"}))
        "spec/004 §2 L261 example path round-trips through the JS args
         object → parser → CLJS vector chain")))

(deftest spec-004-default-without-path-is-nil
  (testing "MUST 9 half — absent `:path` parses to `nil`, signalling
            the dispatcher to take the W-4 summary branch (the W-4
            mode resolver in `summary.cljs` consumes the nil)"
    (is (nil? (path-slice/path-arg #js {})))
    (is (nil? (path-slice/path-arg nil)))))

(deftest spec-004-out-of-range-returns-path-not-found
  (testing "spec/004 §2 L267-270 — out-of-range path returns
            `:ok? false :reason :path-not-found` with the deepest
            valid prefix attached"
    (let [tree {:cart {:items [{:sku "abc"}]}}
          out  (path-slice/apply-to-result {} :db tree [:cart :items 5 :sku])]
      (is (false? (:ok? out)))
      (is (= :path-not-found (:reason out)))
      (is (= [:cart :items 5 :sku] (:path out)))
      (is (= [:cart :items] (:deepest-valid-prefix out))))))
