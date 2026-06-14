(ns day8.re-frame2-xray.panels.derivation-graph-helpers-cljs-test
  "Pure-data tests for the Derivation-Graph panel helpers (EP-0014 prop-3,
  rf2-9ett2d).

  Dual-target naming (`.cljc` + `_cljs_test`):
    - Cognitect's test-runner (CLJ) picks it up via the default `.*-test$`
      regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$` regex.

  ## What's under test (no runtime — pure data over fixture graphs)

    1. **superkind classification** — every node classified by `:kind`
       ALONE into the two closed superkinds; a refined kind in `:kind`
       would be a violation (the contract axis); a node carrying only a
       refinement is still classified off its superkind; a malformed node
       degrades to `:unknown`.
    2. **family grouping** — nodes bucketed by the composer-stamped
       `:rf/family` tag (authoritative) with id-tag fallback; deterministic
       sort.
    3. **edges** — role grouping, node degree, the empty-edge case.
    4. **ON-BOX summarize** — bounded preview, size, the raw-permitting
       posture (a non-sensitive value is summarized verbatim, NOT redacted);
       the `:rf/redacted` sentinel flags `:redacted?`.
    5. **summarize-graph / summarize-node** — value-bearing fields summarized
       for display; node STRUCTURE (kind / inputs / output / classifications)
       rides through untouched.
    6. **graph-summary** — counts + superkind / family / role tallies.

  The OFF-BOX egress redaction (rf2-yjarv6) needs a live frame elision
  policy, so it lives in the runtime test
  `derivation_graph_redaction_cljs_test.cljc`."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.panels.derivation-graph-helpers :as h]))

;; ---------------------------------------------------------------------------
;; A representative cross-family graph fixture — one node of each family,
;; each carrying its composer `:rf/family` tag + the closed-superkind `:kind`
;; + the refined `:refinement` (colour axis) the siblings emit.
;; ---------------------------------------------------------------------------

(def ^:private sub-node
  {:id :cart/total :kind :derivation :rf/family :subs
   :inputs [[:sub [:cart/items]]] :output [:fact :cart/total]
   :storage :ephemeral :evaluation :on-demand :lifecycle :subscription-cache-entry})

(def ^:private flow-node
  {:id :cart/materialized-total :kind :derivation :rf/family :flows
   :inputs [[:db [:cart :items]]] :output [:db [:cart :total]]
   :storage :app-db :evaluation :after-event :lifecycle :frame})

(def ^:private resource-node
  {:id :article/by-slug :kind :process :refinement :resource-process :rf/family :resources
   :inputs :parametric :output [:runtime [:rf.runtime/resources :entries]]
   :storage :runtime-db :authority {:kind :remote :system :server}
   :evaluation #{:on-route :on-reply} :lifecycle :scoped-resource-key})

(def ^:private route-node
  {:id :rf/route :kind :process :refinement :route-fact :rf/family :routes
   :output [:runtime [:rf.runtime/routing :current]]
   :storage :runtime-db :evaluation :on-route :lifecycle :frame})

(def ^:private machine-node
  {:id :upload/main :kind :process :refinement :machine-process :rf/family :machines
   :output [:runtime [:rf.runtime/machines :snapshots :upload/main]]
   :storage :runtime-db :evaluation #{:on-transition} :lifecycle :machine-instance})

(def ^:private selector-node
  {:id :upload/progress :kind :derivation :refinement :machine-selector :rf/family :subs
   :inputs [[:machine :upload/main [:data :progress]]] :output [:fact :upload/progress]
   :storage :ephemeral :evaluation :on-demand :lifecycle :subscription-cache-entry})

(def ^:private fixture-graph
  {:mode :static
   :nodes {[:sub :cart/total]               sub-node
           [:flow :cart/materialized-total] flow-node
           [:resource :article/by-slug]     resource-node
           [:rf/route :route/article]       route-node
           [:machine :upload/main]          machine-node
           [:sub :upload/progress]          selector-node}
   :edges [{:from [:sub :cart/items] :to [:sub :cart/total] :role :input}
           {:from [:rf/route :route/article] :to [:resource :article/by-slug] :role :param}
           {:from [:machine :upload/main] :to [:sub :upload/progress] :role :selector}]})

;; ---------------------------------------------------------------------------
;; 1. superkind classification — the CONTRACT axis (classify by :kind alone).
;; ---------------------------------------------------------------------------

(deftest superkind-classifies-by-kind-alone
  (testing "every node reduces to one of the two closed superkinds via :kind"
    (is (= :derivation (h/superkind sub-node)))
    (is (= :derivation (h/superkind flow-node)))
    (is (= :process    (h/superkind resource-node)))
    (is (= :process    (h/superkind route-node)))
    (is (= :process    (h/superkind machine-node)))
    (is (= :derivation (h/superkind selector-node))))
  (testing "classification reads :kind, NOT :refinement — a node carrying only
            a refinement still classifies off its superkind"
    (is (h/process? {:kind :process :refinement :some-future-refinement}))
    (is (h/derivation? {:kind :derivation :refinement :another-future-kind})))
  (testing "a malformed node missing :kind degrades to :unknown (no throw)"
    (is (= :unknown (h/superkind {:id :broken})))
    (is (not (h/process? {:id :broken})))
    (is (not (h/derivation? {:id :broken})))))

;; ---------------------------------------------------------------------------
;; 2. family grouping.
;; ---------------------------------------------------------------------------

(deftest group-by-family-uses-stamped-tag
  (let [grouped (h/group-by-family fixture-graph)]
    (testing "nodes bucket by their composer :rf/family tag"
      (is (= 2 (count (:subs grouped))))      ; cart/total + the selector
      (is (= 1 (count (:flows grouped))))
      (is (= 1 (count (:resources grouped))))
      (is (= 1 (count (:routes grouped))))
      (is (= 1 (count (:machines grouped)))))
    (testing "entries are sorted deterministically by node id"
      (is (= (h/group-by-family fixture-graph)
             (h/group-by-family fixture-graph))))))

(deftest node-family-falls-back-to-id-tag
  (testing "a node without an :rf/family tag infers from the id tag"
    (is (= :subs      (h/node-family [:sub :x] {:kind :derivation})))
    (is (= :flows     (h/node-family [:flow :x] {:kind :derivation})))
    (is (= :resources (h/node-family [:resource :x] {:kind :process})))
    (is (= :machines  (h/node-family [:machine :x] {:kind :process})))
    (is (= :routes    (h/node-family :rf/route {:kind :process})))
    (is (= :routes    (h/node-family [:rf/route :route/x] {:kind :process})))))

;; ---------------------------------------------------------------------------
;; 3. edges.
;; ---------------------------------------------------------------------------

(deftest edges-by-role-groups-the-three-roles
  (let [by-role (h/edges-by-role fixture-graph)]
    (is (= 1 (count (:input by-role))))
    (is (= 1 (count (:param by-role))))
    (is (= 1 (count (:selector by-role))))))

(deftest node-degree-counts-in-and-out
  (let [deg (h/node-degree fixture-graph)]
    (is (= {:in 0 :out 1} (get deg [:rf/route :route/article])))
    (is (= {:in 1 :out 0} (get deg [:resource :article/by-slug])))
    (is (= {:in 1 :out 0} (get deg [:sub :upload/progress])))
    (testing "a node absent from any edge has zero degree"
      (is (= {:in 0 :out 0} (get deg [:flow :cart/materialized-total]))))))

(deftest empty-edges-is-handled
  (let [g {:mode :static :nodes {[:sub :a] {:kind :derivation}} :edges []}]
    (is (= {} (h/edges-by-role g)))
    (is (= {:in 0 :out 0} (get (h/node-degree g) [:sub :a])))))

;; ---------------------------------------------------------------------------
;; 4. ON-BOX summarize — raw-permitting (NOT an egress boundary).
;; ---------------------------------------------------------------------------

(deftest summarize-is-raw-permitting
  (testing "a non-sensitive value is summarized VERBATIM, not redacted —
            on-box display is entitled to the raw value (Security.md)"
    (let [s (h/summarize {:slug "welcome" :secret "shhh"})]
      (is (= :map (:type s)))
      (is (= 2 (:size s)))
      (is (false? (:redacted? s)))
      (is (re-find #"welcome" (:preview s)))))
  (testing "a long value's preview is bounded for display ergonomics"
    (let [s (h/summarize (apply str (repeat 500 "x")))]
      (is (= :string (:type s)))
      (is (<= (count (:preview s)) 81))
      (is (re-find #"…$" (:preview s)))))
  (testing "the :rf/redacted sentinel (a value that arrived already redacted)
            flags :redacted? so the row renders muted"
    (let [s (h/summarize :rf/redacted)]
      (is (true? (:redacted? s))))))

(deftest summarize-node-attaches-summaries-leaves-structure
  (let [node {:id [:sub [:article/page "welcome"]] :kind :derivation
              :inputs [[:sub [:article/by-slug "welcome"]]]
              :output [:fact [:article/page "welcome"]]
              :storage :ephemeral :evaluation :on-demand
              :value {:slug "welcome"}}
        summarized (h/summarize-node node)]
    (testing "the value-bearing :value field gains an on-box summary"
      (is (= :map (get-in summarized [:summaries :value :type]))))
    (testing "structure rides through verbatim — kind / inputs / output /
              classifications untouched"
      (is (= :derivation (:kind summarized)))
      (is (= [[:sub [:article/by-slug "welcome"]]] (:inputs summarized)))
      (is (= [:fact [:article/page "welcome"]] (:output summarized)))
      (is (= :ephemeral (:storage summarized)))))
  (testing "a node with no value-bearing field is returned unchanged"
    (is (= sub-node (h/summarize-node sub-node)))))

;; ---------------------------------------------------------------------------
;; 5. graph-summary.
;; ---------------------------------------------------------------------------

(deftest graph-summary-tallies
  (let [s (h/graph-summary fixture-graph)]
    (is (= :static (:mode s)))
    (is (= 6 (:node-count s)))
    (is (= 3 (:edge-count s)))
    (is (= {:derivation 3 :process 3} (:by-superkind s)))
    (is (= 2 (get-in s [:by-family :subs])))
    (is (= {:input 1 :param 1 :selector 1} (:by-role s)))))

(deftest empty-graph-detection
  (is (h/empty-graph? {:mode :static :nodes {} :edges []}))
  (is (not (h/empty-graph? fixture-graph))))

;; ---------------------------------------------------------------------------
;; labels.
;; ---------------------------------------------------------------------------

(deftest node-label-strips-family-tag
  (is (= ":cart/total" (h/node-label [:sub :cart/total])))
  (is (= ":rf/route"   (h/node-label :rf/route)))
  (is (= "Subscriptions" (h/family-label :subs)))
  (is (= "Machines"      (h/family-label :machines))))