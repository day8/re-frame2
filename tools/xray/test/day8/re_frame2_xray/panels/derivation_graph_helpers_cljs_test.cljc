(ns day8.re-frame2-xray.panels.derivation-graph-helpers-cljs-test
  "Pure-data tests for the Derivation-Graph panel helpers (EP-0014 prop-3).

  Dual-target naming (`.cljc` + `_cljs_test`):
    - Cognitect's test-runner (CLJ) picks it up via the default `.*-test$`
      regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$` regex.

  ## What's under test (no runtime — pure data over fixture graphs)

    1. **superkind classification** — every node classified by `:kind`
       ALONE into the two closed superkinds; a malformed node degrades to
       `:unknown`.
    2. **ON-BOX summarize** — bounded print, size, the raw-permitting
       posture (a non-sensitive value is summarized verbatim, NOT redacted);
       the `:rf/redacted` sentinel flags `:redacted?`.
    3. **summarize-node** — value-bearing fields summarized for display;
       node STRUCTURE rides through untouched.
    4. **graph-summary** — counts + superkind / family / role tallies.

  The OFF-BOX egress redaction needs a live frame elision
  policy, so it lives in the runtime test
  `derivation_graph_redaction_cljs_test.cljc`."
  (:require [clojure.test :refer [are deftest is testing]]
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
  (testing "a node reduces to one of the two closed superkinds via :kind,
            whatever its :refinement; a malformed node degrades to :unknown"
    (is (= [:derivation :process :unknown]
           (mapv h/superkind [sub-node resource-node {:id :broken}])))))

;; ---------------------------------------------------------------------------
;; 2. ON-BOX summarize — raw-permitting (NOT an egress boundary).
;; ---------------------------------------------------------------------------

(deftest summarize-is-raw-permitting
  (testing "a non-sensitive value is summarized VERBATIM, not redacted —
            on-box display is entitled to the raw value (Security.md)"
    (is (= {:type :map :size 2 :redacted? false
            :preview "{:slug \"welcome\", :secret \"shhh\"}"}
           (h/summarize {:slug "welcome" :secret "shhh"}))))
  (testing "the :rf/redacted sentinel (a value that arrived already redacted)
            flags :redacted? so the row renders muted"
    (is (true? (:redacted? (h/summarize :rf/redacted))))))

(deftest y8doi25-summarize-bounds-the-serialisation-not-just-the-string
  (testing "the Graph tab re-summarises every cached value-bearing field on
            every coalesced tick, so `summarize` has to bound the PRINT, and
            `:size` must not `count` an uncounted value either. Measured with
            a realisation counter, which only a bounded walk can leave low."
    (let [realised (atom 0)
          lazy     (map (fn [i] (swap! realised inc) i) (range 20000))
          s        (h/summarize lazy)]
      (is (nil? (:size s)) "an uncounted value reports no size")
      (is (< @realised 1000)
          (str "realised " @realised
               " of 20000 elements to keep 80 characters"))))

  (testing "a deeply NESTED value is bounded by depth rather than walked to
            the bottom — 400 levels of nesting print a `#` marker, not 400
            brackets"
    (is (re-find #"#" (:preview (h/summarize (nth (iterate vector :leaf) 400))))))

  (testing "bounding the print does not change what the operator sees — the
            preview's kept characters are the ones the unbounded print gave"
    (let [v (vec (range 1000))]
      (is (= (subs (pr-str v) 0 80)
             (subs (:preview (h/summarize v)) 0 80))))))

(deftest rf2-3hnvn-bounded-pr-str-bounds-every-string-the-print-reaches
  ;; THE DISCRIMINATOR IS THE PRINT, NOT THE PREVIEW. `summarize` keeps 80
  ;; characters, and the bounded and the unbounded print agree on every one
  ;; of them — so an assertion about `:preview` would pass against an
  ;; unbounded nested print (200010 characters printed here, 81 previewed,
  ;; exactly as a bounded print previews 81). What the coalesced tick
  ;; actually pays is the LENGTH OF THE PRINT, which is what these pin.
  (let [huge (apply str (repeat 200000 "x"))]

    (testing "`*print-length*` does not reach inside a string, so every
              string the print walk can REACH is bounded: the root, nested
              under maps and a vector, a set member, a map KEY"
      (are [v] (< (count (h/bounded-pr-str v)) 1000)
        huge
        {:a {:b [huge]}}
        #{huge}
        {huge 1}))

    (testing "and the operator sees what the unbounded print shows: bounding
              SOURCE characters cannot change the printed prefix, because
              escapes only lengthen"
      (let [s (h/summarize {:body huge})]
        (is (= (subs (pr-str {:body huge}) 0 80)
               (subs (:preview s) 0 80))
            "the preview's kept characters are the unbounded print's")
        (is (<= (count (:preview s)) 81))))))

(deftest rf2-kbo64-a-shortened-key-or-member-is-never-put-back
  ;; Putting a shortened KEY (or set member) back with `dissoc`/`assoc`
  ;; MOVES the entry (caught by prefix fidelity) and COLLAPSES keys sharing a
  ;; `preview-limit` prefix, dragging an unwalked entry's unbounded value into
  ;; the print (caught by print length). Neither assertion sees the other's
  ;; defect.
  (let [huge (apply str (repeat 200000 "x"))]

    (testing "PREFIX FIDELITY — a long KEY keeps its place, so the operator
              still reads the characters the unbounded print would have given"
      (let [v (array-map huge 1 :small 2)]
        (is (= (subs (pr-str v) 0 80)
               (subs (h/bounded-pr-str v) 0 80))
            "the bounded print must OPEN where the real print opens — a
             `dissoc`/`assoc` moves the long key to the END, so this reads
             `{:small 2, ...` against the defect")))

    (testing "COLLIDING KEY PREFIXES — 81 keys sharing their first
              `preview-limit` characters must not collapse the map and drag
              the unwalked 81st entry, value still unbounded, into the print"
      (let [pre  (apply str (repeat 80 "k"))
            ks   (mapv #(str pre "-" %) (range 81))
            v    (into (sorted-map) (map (fn [k] [k huge])) ks)
            out  (h/bounded-pr-str v)]
        (is (< (count out) (count huge))
            (str "printed " (count out) " characters to preview a map whose"
                 " values are " (count huge) " characters — the collapse"
                 " leaves the unwalked entry's value to serialise in full"))))

    (testing "SETS — a member IS its own key, so the same correction applies"
      (let [pre     (apply str (repeat 80 "s"))
            ;; 80 members colliding on their first `preview-limit`
            ;; characters, plus one member that sorts AFTER all of them and
            ;; so is never walked.
            colliding (mapv #(str pre "-" %) (range 80))
            unwalked  (str "t" (apply str (repeat 200000 "z")))
            v         (into (sorted-set) (conj colliding unwalked))
            out       (h/bounded-pr-str v)]
        (is (< (count out) (count unwalked))
            (str "printed " (count out) " characters for a set whose 81st,"
                 " never-walked member is " (count unwalked) " characters"))
        (is (= (str "#{" (subs (pr-str (first v)) 0 78))
               (subs out 0 80))
            "and the print opens on the set's own first member")))))

;; A record for the preview tests. RECORDS SATISFY `map?`, so a record takes
;; the map branch of the preview walk — but its printed form opens with a
;; type tag the plain-map branch knows nothing about, and the tag is spelled
;; differently on each runtime (the host class name on the JVM, the
;; `defrecord`-generated `pr-open` in ClojureScript). So the tests below
;; compare against `pr-str` of the SAME value on the SAME runtime and never
;; against a hard-coded tag.
(defrecord PreviewRec [body])

(deftest rf2-xpitj-a-rendered-key-window-keeps-the-record-type-tag
  ;; The key/member pair above, one level on through the RECORD branch: the
  ;; rendered window must open with the record's type tag (prefix fidelity),
  ;; and the tag must not be bought back by putting the shortened key back
  ;; (bounded work).
  (let [huge (apply str (repeat 200000 "x"))]

    (testing "PREFIX FIDELITY — the record's printed type tag survives into
              the characters the operator actually reads"
      (let [v (assoc (->PreviewRec "short") huge 1)]
        (is (= (subs (pr-str v) 0 80) (subs (h/bounded-pr-str v) 0 80))
            "the bounded print must open exactly where the real print opens —
             against the defect this reads the plain-map `{`")))

    (testing "BOUNDED WORK — colliding extension keys must not collapse the
              record and drag an unwalked entry's value into the print"
      (let [pre (apply str (repeat 80 "k"))
            ks  (mapv #(str pre "-" %) (range 81))
            v   (into (->PreviewRec "short") (map (fn [k] [k huge])) ks)
            out (h/bounded-pr-str v)]
        (is (< (count out) (count huge))
            (str "printed " (count out) " characters to preview a record"
                 " whose extension values are " (count huge) " characters —"
                 " a collapse leaves an unwalked entry to serialise in full"))))))

(deftest summarize-node-attaches-summaries-leaves-structure
  (let [node {:id [:sub [:article/page "welcome"]] :kind :derivation
              :inputs [[:sub [:article/by-slug "welcome"]]]
              :output [:fact [:article/page "welcome"]]
              :storage :ephemeral :evaluation :on-demand
              :value {:slug "welcome"}}
        summarized (h/summarize-node node)]
    (is (= :map (get-in summarized [:summaries :value :type]))
        "the value-bearing :value field gains an on-box summary")
    (is (= node (dissoc summarized :summaries))
        "structure rides through verbatim")))

;; ---------------------------------------------------------------------------
;; 3. graph-summary.
;; ---------------------------------------------------------------------------

(deftest graph-summary-tallies
  (is (= {:mode         :static
          :node-count   6
          :edge-count   3
          :by-superkind {:derivation 3 :process 3}
          :by-family    {:subs 2 :flows 1 :resources 1 :routes 1 :machines 1}
          :by-role      {:input 1 :param 1 :selector 1}}
         (h/graph-summary fixture-graph))))

;; ---------------------------------------------------------------------------
;; labels.
;; ---------------------------------------------------------------------------

(deftest node-label-strips-family-tag
  (is (= ":cart/total" (h/node-label [:sub :cart/total])))
  (is (= ":rf/route"   (h/node-label :rf/route))))