(ns day8.re-frame2-causa.panels.shared.sub-input-paths-cljs-test
  "Tests for the registry-side sub input-paths walk (rf2-gblq6).

  The helper is pure data — these tests inject synthetic registry
  projections (`{sub-id {:input-signal-ids [...] :layer-1? bool}}`)
  rather than seeding the real registrar. That keeps the suite
  hermetic and lets us exercise edge cases (cycles, missing upstream)
  without polluting any per-test registrar state.

  CLJC — runs on JVM and CLJS sides; no platform-specific branches in
  the helper."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.shared.sub-input-paths :as sip]))

;; ---- resolve-input-paths --------------------------------------------------

(deftest layer-1-sub-resolves-to-itself
  ;; A layer-1 sub IS its own input-path proxy. The walk returns a
  ;; singleton vector containing the sub-id.
  (let [registry {:cart/state {:input-signal-ids [] :layer-1? true}}]
    (is (= [:cart/state]
           (sip/resolve-input-paths :cart/state registry))
        "layer-1 leaf resolves to a vector containing itself")))

(deftest layer-2-sub-resolves-to-its-upstream-layer-1-leaves
  ;; Layer-2 sub `:cart/total` reads from layer-1 `:cart/items`.
  ;; Walk should yield `[:cart/items]` — the layer-1 leaf, not the
  ;; layer-2 id itself.
  (let [registry {:cart/items {:input-signal-ids [] :layer-1? true}
                  :cart/total {:input-signal-ids [:cart/items]
                               :layer-1? false}}]
    (is (= [:cart/items]
           (sip/resolve-input-paths :cart/total registry))
        "layer-2 sub resolves to its layer-1 upstream")))

(deftest layer-3-sub-resolves-transitively
  ;; Layer-3 chain — `:c` reads `:b` reads `:a` (layer-1). The walk
  ;; should reach `:a` transitively.
  (let [registry {:a {:input-signal-ids [] :layer-1? true}
                  :b {:input-signal-ids [:a] :layer-1? false}
                  :c {:input-signal-ids [:b] :layer-1? false}}]
    (is (= [:a] (sip/resolve-input-paths :c registry))
        "layer-3 sub reaches its layer-1 leaf transitively")))

(deftest layer-2-multi-input-sub-unions-leaves
  ;; Layer-2 sub with two layer-1 upstreams should union the leaves.
  ;; Ordering deterministic via sort-by pr-str.
  (let [registry {:cart/items   {:input-signal-ids [] :layer-1? true}
                  :user/profile {:input-signal-ids [] :layer-1? true}
                  :checkout/ready?
                  {:input-signal-ids [:cart/items :user/profile]
                   :layer-1? false}}]
    (is (= [:cart/items :user/profile]
           (sip/resolve-input-paths :checkout/ready? registry))
        "multi-input layer-2 sub unions its layer-1 leaves")))

(deftest diamond-dependency-collapses-shared-leaf
  ;; Diamond — `:d` reads `:b` and `:c`; both read layer-1 `:root`.
  ;; The shared leaf should appear once.
  (let [registry {:root {:input-signal-ids [] :layer-1? true}
                  :b    {:input-signal-ids [:root] :layer-1? false}
                  :c    {:input-signal-ids [:root] :layer-1? false}
                  :d    {:input-signal-ids [:b :c] :layer-1? false}}]
    (is (= [:root] (sip/resolve-input-paths :d registry))
        "diamond dependency collapses shared layer-1 leaf")))

(deftest cycle-returns-nil
  ;; Cycle — `:a` reads `:b`; `:b` reads `:a`. The walk must
  ;; short-circuit to nil rather than recurse forever.
  (let [registry {:a {:input-signal-ids [:b] :layer-1? false}
                  :b {:input-signal-ids [:a] :layer-1? false}}]
    (is (nil? (sip/resolve-input-paths :a registry))
        "cycle resolves to nil (unknown)")
    (is (nil? (sip/resolve-input-paths :b registry))
        "either side of the cycle is unknown")))

(deftest self-cycle-returns-nil
  ;; Self-cycle — `:a` reads itself.
  (let [registry {:a {:input-signal-ids [:a] :layer-1? false}}]
    (is (nil? (sip/resolve-input-paths :a registry))
        "self-cycle resolves to nil")))

(deftest missing-upstream-returns-nil
  ;; Layer-2 sub references an upstream that isn't registered.
  ;; Walk must return nil (unknown) — the missing leaf could be
  ;; anything.
  (let [registry {:b {:input-signal-ids [:missing] :layer-1? false}}]
    (is (nil? (sip/resolve-input-paths :b registry))
        "missing upstream resolves to nil")))

(deftest partial-unknown-upstream-poisons-result
  ;; Diamond where one branch reaches a known leaf and the other is
  ;; cyclic. The whole result must be nil — we can't claim
  ;; partial knowledge.
  (let [registry {:leaf {:input-signal-ids [] :layer-1? true}
                  :good {:input-signal-ids [:leaf] :layer-1? false}
                  :bad  {:input-signal-ids [:cycle] :layer-1? false}
                  :cycle {:input-signal-ids [:bad] :layer-1? false}
                  :composite
                  {:input-signal-ids [:good :bad] :layer-1? false}}]
    (is (nil? (sip/resolve-input-paths :composite registry))
        "any unknown upstream poisons the composite result")))

(deftest missing-sub-itself-returns-nil
  ;; Walking a sub-id that isn't in the registry returns nil.
  (is (nil? (sip/resolve-input-paths :not-registered {}))
      "missing target sub resolves to nil"))

(deftest empty-registry-returns-empty-resolve-many
  (is (= {} (sip/resolve-many {}))
      "empty registry yields empty resolve-many"))

(deftest resolve-many-projects-every-sub
  ;; Batch form returns the {sub-id input-paths} map for every
  ;; registered sub.
  (let [registry {:cart/items {:input-signal-ids [] :layer-1? true}
                  :cart/total {:input-signal-ids [:cart/items]
                               :layer-1? false}}]
    (is (= {:cart/items [:cart/items]
            :cart/total [:cart/items]}
           (sip/resolve-many registry))
        "resolve-many returns one entry per registered sub")))

;; ---- sub-id-touches-path? ------------------------------------------------

(deftest sub-id-touches-path-positive-cases
  (testing "namespaced sub matches segment-overlap path"
    (is (sip/sub-id-touches-path? :cart/state [:cart :state]))
    (is (sip/sub-id-touches-path? :cart/state [:cart])
        "namespace alone is a match")
    (is (sip/sub-id-touches-path? :cart/state [:state])
        "name alone is a match"))
  (testing "single-segment sub matches single-segment path"
    (is (sip/sub-id-touches-path? :counter [:counter]))))

(deftest sub-id-touches-path-negative-cases
  (testing "no segment overlap → no match"
    (is (not (sip/sub-id-touches-path? :cart/state [:user :profile])))
    (is (not (sip/sub-id-touches-path? :foo [:bar :baz]))))
  (testing "empty path → no match"
    (is (not (sip/sub-id-touches-path? :cart/state []))))
  (testing "non-keyword sub-id → no match"
    (is (not (sip/sub-id-touches-path? "string-id" [:cart :state])))))

(deftest sub-id-touches-path-ignores-non-keyword-path-segments
  ;; Path with indices or strings — only keyword segments
  ;; participate in the overlap test.
  (is (sip/sub-id-touches-path? :cart/items [:cart :items 0])
      "keyword segments still match around an integer index")
  (is (not (sip/sub-id-touches-path? :checkout/state [:cart "items" 0]))
      "no keyword segments overlap"))

;; ---- sub-touches-path? ---------------------------------------------------

(deftest sub-touches-path-rule-1-unknown-includes
  ;; Rule 1 — nil input-paths means unknown; include conservatively.
  (is (sip/sub-touches-path? nil [:cart :state])
      "nil input-paths ⇒ include")
  (is (sip/sub-touches-path? nil [])
      "nil input-paths ⇒ include even at root"))

(deftest sub-touches-path-rule-2-empty-excludes
  ;; Rule 2 — empty input-paths means the sub composes no app-db
  ;; reads; exclude.
  (is (not (sip/sub-touches-path? [] [:cart :state]))
      "empty input-paths ⇒ exclude"))

(deftest sub-touches-path-rule-3-keyword-overlap
  ;; Rule 3 — keyword overlap of any leaf id against the path.
  (testing "single layer-1 leaf overlaps path"
    (is (sip/sub-touches-path? [:cart/state] [:cart :state]))
    (is (sip/sub-touches-path? [:cart/state] [:cart]))
    (is (not (sip/sub-touches-path? [:cart/state] [:user :profile]))))
  (testing "any leaf in a multi-leaf set is enough"
    (is (sip/sub-touches-path? [:cart/state :user/profile]
                               [:user :profile]))
    (is (not (sip/sub-touches-path? [:cart/state :user/profile]
                                     [:session :token])))))

;; ---- sub-meta-map (registrar integration smoke) -------------------------

#?(:cljs
   (deftest sub-meta-map-empty-registry-returns-empty-map
     ;; A side-effecting test fixture-free smoke that sub-meta-map
     ;; reads the live registrar. We can't reliably assert specific
     ;; sub-ids without a fixture, but the result MUST be a map.
     (is (map? (sip/sub-meta-map))
         "sub-meta-map returns a map (registry projection shape)")))
