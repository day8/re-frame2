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

(deftest y8doi25-summarize-bounds-the-serialisation-not-just-the-string
  (testing "rf2-y8doi.25 — the Graph tab re-summarises every cached value-
            bearing field on every coalesced tick, so the COST is the
            serialisation, not the 80 characters kept from it. Truncating a
            string after the fact buys nothing: `summarize` has to bound the
            PRINT. Measured with a realisation counter, which only a bounded
            print can leave low."
    (let [realised (atom 0)
          lazy     (map (fn [i] (swap! realised inc) i) (range 20000))
          s        (h/summarize lazy)]
      (is (= :seq (:type s)))
      (is (<= (count (:preview s)) 81)
          "the operator still gets the same bounded preview")
      (is (< @realised 1000)
          (str "realised " @realised
               " of 20000 elements to keep 80 characters"))))

  (testing "`:size` on an UNCOUNTED value is the same walk by another door —
            `count` over a lazy seq realises the lot. A counted collection
            keeps its size; an uncounted one reports none rather than pay for
            one nobody asked for."
    (let [realised (atom 0)
          lazy     (map (fn [i] (swap! realised inc) i) (range 20000))
          s        (h/summarize lazy)]
      (is (nil? (:size s)))
      (is (< @realised 1000)))
    (is (= 3 (:size (h/summarize [1 2 3]))) "a vector still reports its size")
    (is (= 2 (:size (h/summarize {:a 1 :b 2}))) "and so does a map")
    (is (= 2 (:size (h/summarize #{:a :b})))    "and a set")
    (is (= 2 (:size (h/summarize (list :a :b)))) "and a list"))

  (testing "a deeply NESTED value is bounded by depth rather than walked to
            the bottom — 400 levels of nesting print a `#` marker, not 400
            brackets"
    (let [deep (nth (iterate vector :leaf) 400)
          s    (h/summarize deep)]
      (is (= :vector (:type s)))
      (is (<= (count (:preview s)) 81))
      (is (re-find #"#" (:preview s))
          "the depth marker, not 80 characters of opening brackets")))

  (testing "bounding the print does not change what the operator sees — the
            preview's kept characters are the ones the unbounded print gave"
    (let [v (vec (range 1000))]
      (is (= (subs (pr-str v) 0 80)
             (subs (:preview (h/summarize v)) 0 80))))
    (let [long-string (apply str (repeat 500 "x"))]
      (is (= (subs (pr-str long-string) 0 80)
             (subs (:preview (h/summarize long-string)) 0 80))))))

(deftest rf2-3hnvn-bounded-pr-str-bounds-every-string-the-print-reaches
  ;; THE DISCRIMINATOR IS THE PRINT, NOT THE PREVIEW. `summarize` keeps 80
  ;; characters, and the bounded and the unbounded print agree on every one
  ;; of them — so an assertion about `:preview` passes AGAINST the bug (the
  ;; merged code printed 200010 characters here and previewed 81, exactly as
  ;; the fixed code previews 81). What the coalesced tick actually pays is
  ;; the LENGTH OF THE PRINT, which is what these pin.
  (let [huge   (apply str (repeat 200000 "x"))
        nested (h/bounded-pr-str {:body huge})
        root   (h/bounded-pr-str huge)
        deeper (h/bounded-pr-str {:a {:b [huge]}})
        in-set (h/bounded-pr-str #{huge})
        as-key (h/bounded-pr-str {huge 1})]

    (testing "rf2-3hnvn — `*print-length*` does not reach inside a string, so
              bounding only a string at the ROOT left an ordinary nested one
              serialising in full on every tick"
      (is (< (count nested) 1000)
          (str "printed " (count nested)
               " characters for a 200000-character string nested one level"))
      (is (< (count root) 1000)
          (str "printed " (count root)
               " characters for the scalar root — the half that already"
               " worked, pinned so a later change cannot regress it")))

    (testing "every string the print walk can REACH is bounded, not just a
              map value one level down"
      (is (< (count deeper) 1000) "nested under two maps and a vector")
      (is (< (count in-set) 1000) "an element of a set")
      (is (< (count as-key) 1000) "a map KEY"))

    (testing "and the operator sees exactly what they saw before: bounding
              SOURCE characters cannot change the printed prefix, because
              escapes only lengthen"
      (let [s (h/summarize {:body huge})]
        (is (= (subs (pr-str {:body huge}) 0 80)
               (subs (:preview s) 0 80))
            "the preview's kept characters are the unbounded print's")
        (is (<= (count (:preview s)) 81))
        (is (= :map (:type s)) "`:type` is unchanged")
        (is (= 1 (:size s))    "and so is `:size` — summarize is untouched")))))

(deftest rf2-kbo64-a-shortened-key-or-member-is-never-put-back
  ;; TWO DIFFERENT PROPERTIES, AND NEITHER TEST CATCHES THE OTHER'S DEFECT —
  ;; which is why a length-only regression is not enough here.
  ;;
  ;; Shortening a map KEY with `dissoc` + `assoc` (or a set member with
  ;; `disj` + `conj`) does two separable things:
  ;;   1. it MOVES the entry — on an array-map to the END — so the preview
  ;;      stops opening where the real print opens. Caught by PREFIX
  ;;      FIDELITY; the print stays short, so a length assertion sees nothing.
  ;;   2. it COLLAPSES keys sharing a `preview-limit`-character prefix, which
  ;;      SHRINKS the collection and pulls an entry the walk never visited
  ;;      inside the print window with its value still unbounded. Caught by
  ;;      the PRINT LENGTH; the surviving prefix is unchanged, so a prefix
  ;;      assertion sees nothing.
  (let [huge (apply str (repeat 200000 "x"))]

    (testing "PREFIX FIDELITY — a long KEY keeps its place, so the operator
              still reads the characters the unbounded print would have given"
      (let [v (array-map huge 1 :small 2)]
        (is (= (subs (pr-str v) 0 80)
               (subs (h/bounded-pr-str v) 0 80))
            "the bounded print must OPEN where the real print opens — a
             `dissoc`/`assoc` moves the long key to the END, so this reads
             `{:small 2, ...` against the defect")
        (is (= (subs (pr-str v) 0 80)
               (subs (:preview (h/summarize v)) 0 80))
            "and so must the preview, which is what the panel renders")
        (is (< (count (h/bounded-pr-str v)) 1000)
            (str "printed " (count (h/bounded-pr-str v))
                 " characters — this one stays SHORT against the defect,"
                 " which is precisely why length cannot be the only test"))))

    (testing "COLLIDING KEY PREFIXES — 81 keys sharing their first
              `preview-limit` characters must not collapse the map and drag
              the unwalked 81st entry, value still unbounded, into the print"
      (let [pre  (apply str (repeat 80 "k"))
            ks   (mapv #(str pre "-" %) (range 81))
            ;; ONE shared value string, so the fixture costs 200k characters
            ;; in total rather than 81 copies of it.
            v    (into (sorted-map) (map (fn [k] [k huge])) ks)
            out  (h/bounded-pr-str v)]
        (is (< (count out) (count huge))
            (str "printed " (count out) " characters to preview a map whose"
                 " values are " (count huge) " characters — the collapse"
                 " leaves the unwalked entry's value to serialise in full"))
        ;; THE THRESHOLD-FREE FORM OF THE SAME PROPERTY: what is printed must
        ;; not depend on how large the bounded values were.
        (is (= (count out)
               (count (h/bounded-pr-str
                        (into (sorted-map)
                              (map (fn [k] [k (apply str (repeat 400000 "y"))]))
                              ks))))
            "doubling every value must not change the bounded print by one
             character")
        (is (= (str "{" (subs (pr-str (first (keys v))) 0 79))
               (subs out 0 80))
            "and the print still opens on the map's own first key")))

    (testing "SETS — a member IS its own key, so there is no slot to put a
              shortened one back into and the same correction applies"
      (let [pre     (apply str (repeat 80 "s"))
            ;; 80 members colliding on their first `preview-limit`
            ;; characters, plus one member that sorts AFTER all of them and
            ;; so is never walked. Collapsing the 80 leaves that last one
            ;; inside the print window, in full.
            colliding (mapv #(str pre "-" %) (range 80))
            unwalked  (str "t" (apply str (repeat 200000 "z")))
            v         (into (sorted-set) (conj colliding unwalked))
            out       (h/bounded-pr-str v)]
        (is (= 81 (count v)) "fixture: the unwalked member is the 81st")
        (is (< (count out) (count unwalked))
            (str "printed " (count out) " characters for a set whose 81st,"
                 " never-walked member is " (count unwalked) " characters"))
        (is (= (str "#{" (subs (pr-str (first v)) 0 78))
               (subs out 0 80))
            "and the print opens on the set's own first member")
        (is (< (count (h/bounded-pr-str #{huge})) 1000)
            "a single long member is still bounded")))))

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