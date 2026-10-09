(ns day8.re-frame2-machines-viz.cross-emitter-agreement-cljs-test
  "Cross-emitter agreement (001-Topology-Parity.md §3.1 G9: 'faithful across
  all three emitters'). The chart, Mermaid and SCXML must project one machine
  the same way, and the export surfaces must agree on which definitions they
  refuse and what a refusal discloses: a diagram that drops or inverts a
  transition in ONE emitter mis-teaches the user relative to the others."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.ai-generate :as ai]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]
            [day8.re-frame2-machines-viz.scxml :as scxml]))

(defn- mermaid-body [definition]
  (mermaid/emit definition {:fenced? false :header-comment? false}))

(defn- scxml-internal-transition-count
  "The number of target-less `<transition>` elements SCXML emits."
  [definition]
  (->> (re-seq #"<transition\b[^>]*>" (scxml/spec->scxml definition))
       (remove #(str/includes? % "target="))
       count))

(defn- round-trip [definition]
  (-> definition scxml/spec->scxml scxml/scxml->spec))

;; ---------------------------------------------------------------------------
;; An INTERNAL (action-only, no-`:target`) `:on` / `:after` / `:always`
;; candidate is the transition an emitter most easily drops: a
;; `resolve-target-path` inside `keep` returns nil for it. Every emitter
;; surfaces each one — the chart self-anchors it, Mermaid notes it, SCXML emits
;; a target-less `<transition>` — and none draws a cross-state arrow.

(def all-three-internal-machine
  {:initial :a
   :states  {:a {:on     {:tick {:action :log}}
                 :after  {1000 {:action :timeout-log}}
                 :always [{:action :poll}]}}})

(deftest internal-transitions-agree-across-emitters
  (testing "chart: three self-anchored internal edges, one per kind"
    (is (= [{:event :after-1000 :after 1000 :source "a" :target "a"}
            {:event :always :always? true :source "a" :target "a"}
            {:event :tick :source "a" :target "a"}]
           (->> (layout/project-definition all-three-internal-machine)
                :edges
                (filter :internal?)
                (map #(select-keys % [:event :after :always? :source :target]))
                (sort-by :event)))))
  (testing "mermaid: a note carrying each action, and no arrow"
    (let [out (mermaid-body all-three-internal-machine)]
      (is (every? #(str/includes? out %)
                  ["note right of a" "tick / log" "after(1000) / timeout-log" "always / poll"])
          out)
      (is (not (str/includes? out "a --> ")))))
  (testing "scxml: three target-less transitions, which round-trip exactly"
    (is (= 3 (scxml-internal-transition-count all-three-internal-machine)))
    (is (not (str/includes? (scxml/spec->scxml all-three-internal-machine) "target=")))
    (is (= all-three-internal-machine (round-trip all-three-internal-machine)))))

(deftest chart-and-mermaid-mint-the-same-injective-ids
  (testing "Mermaid addresses each state by the chart's `node-id`, and the
            collision-class forms `:a/b` / `:a-b` / `:a_b` stay distinct"
    (let [out (mermaid-body {:initial :start
                             :states  {:start {:on {:one :a/b :two :a-b :three :a_b}}
                                       :a/b {} :a-b {} :a_b {}}})]
      (is (= 3 (count (distinct (map #(layout/node-id [%]) [:a/b :a-b :a_b])))))
      (doseq [[k event] [[:a/b "one"] [:a-b "two"] [:a_b "three"]]]
        (is (str/includes? out (str "start --> " (layout/node-id [k]) " : " event)))))))

;; A `:type :history` node is a history pseudo-state in every emitter — chart
;; `history-marker`, Mermaid `H`/`H*`, SCXML `<history>` — never an ordinary
;; occupiable state.

(def deep-history-machine
  {:initial :off
   :states  {:off    {:on {:resume [:player :hist]}}
             :player {:initial :stopped
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}
                                :hist    {:type :history :deep? true}}
                      :on      {:power-off :off}}}})

(deftest history-pseudo-state-agrees-across-emitters
  (is (= {:history? true :deep? true :final? false :compound? false}
         (->> (layout/project-definition deep-history-machine)
              :nodes
              (filter #(= [:player :hist] (:path %)))
              first
              (#(select-keys % [:history? :deep? :final? :compound?]))))
      "chart: a deep history pseudo-state, neither final nor compound")
  (let [out (mermaid-body deep-history-machine)]
    (is (str/includes? out "state \"H*\" as player__hist") "mermaid: the deep-history marker")
    (is (not (str/includes? out "player__hist --> [*]")) "mermaid: not a final state"))
  (let [out (scxml/spec->scxml deep-history-machine)]
    (is (re-find #"<history [^>]*type=\"deep\"" out) "scxml: a deep <history> element")
    (is (not (re-find #"<state id=\"player___hist\"" out)) "scxml: not an occupiable <state>")
    (is (= deep-history-machine (round-trip deep-history-machine)) "scxml: round-trips")))

;; `:reenter? true` is the external-restart opt-in (Spec 005 §Self-transitions).
;; An emitter ignoring the axis would project two runtime-distinct machines
;; identically.

(def reenter-self-machine
  {:initial :a :states {:a {:on {:ping {:target :same-state :reenter? true}}}}})

(def internal-default-self-machine
  {:initial :a :states {:a {:on {:ping {:target :same-state}}}}})

(deftest reenter-axis-distinct-across-all-three-emitters
  (testing "chart: the external edge carries :reenter? and a distinct edge id"
    (let [ping (fn [m] (first (filter #(= :ping (:event %)) (:edges (layout/project-definition m)))))
          r    (ping reenter-self-machine)
          i    (ping internal-default-self-machine)]
      (is (true? (:reenter? r)))
      (is (not (contains? i :reenter?)))
      (is (not= (:id r) (:id i)))))
  (testing "mermaid: only the external edge carries the ↻ marker"
    (let [r-out (mermaid-body reenter-self-machine)
          i-out (mermaid-body internal-default-self-machine)]
      (is (str/includes? r-out "ping ↻"))
      (is (str/includes? i-out ": ping"))
      (is (not (str/includes? i-out "↻")))))
  (testing "scxml: only the external transition exports type=external, and the
            axis survives import"
    (is (str/includes? (scxml/spec->scxml reenter-self-machine) "type=\"external\""))
    (is (not (str/includes? (scxml/spec->scxml internal-default-self-machine) "type=\"external\"")))
    (is (= reenter-self-machine (round-trip reenter-self-machine)))
    (is (= {:initial :a :states {:a {:on {:ping :same-state}}}}
           (round-trip internal-default-self-machine))
        "the internal default imports as the target-only shorthand")))

;; ---------------------------------------------------------------------------
;; Machine-level fallbacks: a parallel root's `:on` / `:after` (Spec 005 §Root
;; parallel :on / §Root-level :after) and a flat machine's targetless `:on`.

(def root-on-after-machine
  {:type    :parallel
   :on      {:go [:a :two]}
   :after   {1000 [:b :two]}
   :regions {:a {:initial :one :states {:one {} :two {}}}
             :b {:initial :one :states {:one {} :two {}}}}})

(deftest parallel-root-on-after-surfaced-by-all-three-emitters
  (is (= [[[:a :two] false] [[:b :two] true]]
         (->> (:edges (layout/project-definition root-on-after-machine))
              (filter :parallel-root-on?)
              (map (juxt :to-path (comp boolean :parallel-root-after?)))
              sort))
      "chart: both root transitions, one flagged as the :after")
  (let [out (mermaid-body root-on-after-machine)]
    (is (str/includes? out "--> a__two : go (root fallback)"))
    (is (str/includes? out "--> b__two : after(1000) (root fallback)")))
  (is (= root-on-after-machine (round-trip root-on-after-machine))
      "scxml round-trips both root transitions"))

(deftest parallel-root-action-only-on-after-surfaced-by-all-three
  (let [m {:type    :parallel
           :on      {:ping {:action :log-ping}}
           :after   {2000 {:action :timeout-log}}
           :regions {:a {:initial :one :states {:one {}}}
                     :b {:initial :one :states {:one {}}}}}]
    (is (= [true true]
           (->> (layout/project-definition m) :edges (filter :parallel-root-on?) (map (comp boolean :internal?))))
        "chart: both self-anchor on the machine-root chip")
    (let [out (mermaid-body m)]
      (is (str/includes? out "ping / log-ping"))
      (is (str/includes? out "after(2000) / timeout-log")))
    (is (= m (round-trip m)) "scxml round-trips both")))

(deftest targetless-machine-level-on-surfaced-by-all-three
  (let [m {:initial :a
           :on      {:ping {:action :log-ping}}
           :states  {:a {} :b {}}}]
    (is (= [{:internal? true :source layout/machine-root-id :target layout/machine-root-id}]
           (->> (layout/project-definition m)
                :edges
                (filter #(and (:machine-level? %) (= :ping (:event %))))
                (map #(select-keys % [:internal? :source :target]))))
        "chart: self-anchored on the machine-root chip")
    (let [out (mermaid-body m)]
      (is (str/includes? out "state \"root fallback\" as rf_2emachines_2dviz_2emermaid_2froot_2dfallback"))
      (is (str/includes? out "note right of rf_2emachines_2dviz_2emermaid_2froot_2dfallback"))
      (is (str/includes? out "ping / log-ping"))
      (is (not (str/includes? out "--> a : ping")) "mermaid: no phantom arrow"))
    (is (= 1 (scxml-internal-transition-count m)))))

;; A region's own action-only `:on-done` (Spec 005 §Parallel `:on-done`): a
;; Mermaid region block reading only `:initial` / `:states` / `:on` would lose
;; it while SCXML carries it. The chart's projection is pinned in
;; `layout_cljs_test`.

(def region-on-done-action-only-machine
  {:type    :parallel
   :regions {:a {:initial :a1
                 :on-done {:action :log}
                 :states  {:a1 {:on {:go :a2}}
                           :a2 {:final? true}}}
             :b {:initial :b1
                 :states  {:b1 {:on {:go :b2}}
                           :b2 {:final? true}}}}})

(deftest region-on-done-action-only-surfaced-by-mermaid-and-scxml
  (let [out (mermaid-body region-on-done-action-only-machine)]
    (is (str/includes? out "note right of a"))
    (is (str/includes? out "on-done: ✓ done / log"))
    (is (not (str/includes? out "a --> b")) "mermaid: no phantom completion arrow"))
  (is (str/includes? (scxml/spec->scxml region-on-done-action-only-machine)
                     "event=\"done.state.a\"><!-- action: log --></transition>")))

;; EP-0011 — an `:error?` final (Spec 005 §:final?) routes the parent's
;; `:spawn :on-error` rather than `:on-done`, so its KIND must not collapse on
;; any surface.

(def success-and-error-finals-machine
  {:initial :running
   :states  {:running {:on {:ok :ok :boom :boom}}
             :ok      {:final? true}
             :boom    {:final? true :error? true}}})

(deftest error-final-kind-surfaced-by-all-three-emitters
  (is (= {"running" false "ok" false "boom" true}
         (-> (into {} (map (juxt :label :error?))
                   (:nodes (layout/project-definition success-and-error-finals-machine)))
             (select-keys ["running" "ok" "boom"])))
      "chart: only the error final is flagged")
  (let [out (mermaid-body success-and-error-finals-machine)]
    (is (str/includes? out "boom --> [*]") "the error final is still a terminal")
    (is (str/includes? out "note right of boom"))
    (is (str/includes? out "error terminal"))
    (is (not (str/includes? out "note right of ok"))))
  (is (str/includes? (scxml/spec->scxml success-and-error-finals-machine)
                     "<final id=\"boom\" data_rf_error_final=\"true\""))
  (is (= success-and-error-finals-machine (round-trip success-and-error-finals-machine))))

;; ---------------------------------------------------------------------------
;; The three emitters agree on DEFINITION-SHAPE VALIDATION: all route through
;; `grammar/valid-definition?`, so a definition is accepted by all or refused
;; by all — including a malformed parallel region body and a non-keyword
;; `:initial`, which per-emitter shallow checks let through.

(defn- refused? [f]
  (try (f) false
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ true)))

(defn- refusals
  "Whether AI generation (its resolver returns `definition` verbatim, so only
  shape validation can refuse), Mermaid and SCXML each refuse `definition`."
  [definition]
  [(refused? #(ai/generate-machine "x" {:resolver (constantly (pr-str definition))}))
   (refused? #(mermaid/emit definition))
   (refused? #(scxml/spec->scxml definition))])

(def invalid-definitions
  [nil
   42
   [:a :b]
   {:not-a-machine 42}
   {:initial :a :states {}}
   {:initial "a" :states {:a {}}}
   {:type :parallel}
   {:type :parallel :regions {}}
   {:type :parallel :regions {:a {}}}
   {:type :parallel :regions {:a {:initial :x}}}
   {:type :parallel :regions {:a {:initial "x" :states {:x {}}}}}])

(def valid-definitions
  [{:initial :a :states {:a {}}}
   {:initial :a :states {:a {:on {:go :b}} :b {:final? true}}}
   {:type :parallel :regions {:a {:initial :x :states {:x {}}}}}
   {:type :parallel :regions {:a {:initial :x :states {:x {}}}
                              :b {:initial :y :states {:y {}}}}}])

(deftest all-three-emitters-agree-on-which-definitions-project
  (doseq [[d refused] (concat (for [d invalid-definitions] [d true])
                              (for [d valid-definitions] [d false]))]
    (is (= [refused refused refused] (refusals d)) (pr-str d))))

;; An export refusal's message names the defect the definition actually
;; carries, and carries nothing from the definition.

(defn- throws-ex [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e)))

(deftest export-messages-name-the-actual-defect
  (let [secret  "export-message-secret-42"
        refusal (fn [f d]
                  (when-let [e (throws-ex #(f d))]
                    (assoc (ex-data e) :message (ex-message e))))
        exports {:mermaid mermaid/emit :scxml scxml/spec->scxml}]
    (testing "a malformed :timeout on a sound root shape is named as a timeout
              defect, not as a missing :initial / :states"
      (doseq [[label f] exports
              d [{:initial :a :data {:token secret}
                  :states  {:a {:timeout secret :on-timeout :b} :b {}}}
                 {:type    :parallel
                  :regions {:r {:initial :a :timeout secret :on-timeout :a :states {:a {}}}}}]]
        (let [message (str (:message (refusal f d)))]
          (is (str/includes? message ":rf.error/machine-bad-timeout-duration at depth 1")
              (str label ": the message names the timeout defect"))
          (is (not (str/includes? message ":initial"))
              (str label ": the message prescribes no root-shape repair"))
          (is (not (str/includes? message secret))
              (str label ": the message carries no definition value")))))
    (testing "a definition missing its root shape keeps the shape explanation"
      (doseq [[label f] exports
              d [{:initial :a :states {} :data {:token secret}}
                 {:type :parallel :regions {}}]]
        (let [message (str (:message (refusal f d)))]
          (is (str/includes? message ":states")
              (str label ": the message names the missing shape"))
          (is (not (str/includes? message secret))
              (str label ": the message carries no definition value")))))
    (testing "SCXML recommends regions only for a region-shape defect"
      (is (= :supply-a-valid-machine-spec
             (:recovery (refusal scxml/spec->scxml
                                 {:type    :parallel
                                  :regions {:r {:initial :a :timeout "5s" :on-timeout :a
                                                :states {:a {}}}}}))))
      (is (= :supply-non-empty-regions
             (:recovery (refusal scxml/spec->scxml {:type :parallel :regions {}})))))))

;; ---------------------------------------------------------------------------
;; Every ingestion / export surface refuses a RECURSIVELY-invalid definition (a
;; shallow gate would bless all three), keeping its own error id while its
;; value-free summary carries the canonical `:rf.error/machine-*` category.

(def ^:private recursively-invalid-definitions
  {:nested-compound-no-initial
   {:def      {:initial :outer :states {:outer {:states {:inner {}}}}}
    :category :rf.error/machine-compound-state-missing-initial}
   :dangling-target
   {:def      {:initial :idle :states {:idle {:on {:go :missing}}}}
    :category :rf.error/machine-unresolved-target}
   :unknown-node-key
   {:def      {:initial :idle :states {:idle {:on-entry :oops}}}
    :category :rf.error/machine-unknown-node-key}})

(deftest recursively-invalid-rejected-by-every-surface
  (doseq [[label {:keys [def category]}] recursively-invalid-definitions]
    (let [{:keys [nodes edges definition-error]} (layout/project-definition def)]
      (is (= [[] [] category] [nodes edges (get-in definition-error [:defect :category])])
          (str label ": the chart refuses before ELK, with no orphan nodes or edges")))
    (doseq [[surface throw-it error-id summary-key]
            [["mermaid" #(mermaid/emit def) :mermaid/invalid-definition :definition-summary]
             ["scxml" #(scxml/spec->scxml def) :scxml/invalid-spec :spec-summary]
             ["ai" #(ai/generate-machine "x" {:resolver (fn [_] (pr-str def))})
              :ai-generate/invalid-spec :spec-summary]]
            :let [data (ex-data (throws-ex throw-it))]]
      (is (= [error-id category] [(:rf.error/id data) (get-in data [summary-key :defect :category])])
          (str label ": " surface " refuses with its own id and the canonical category")))))

;; ---------------------------------------------------------------------------
;; The summary is content-free by construction (`grammar-validation-cljs-test`
;; pins its grammar), and that is only worth anything WHERE IT LANDS: no
;; emitter may re-introduce the definition beside it, and every thrown
;; diagnostic stays a fixed size however large the forged definition. Each
;; surface takes untrusted input — an LLM response, an imported document, a
;; share-URL host prop.

(def ^:private disclosure-sentinel "hunter2-swordfish-SENTINEL")

(def ^:private disclosure-fragments
  "Every 8-character window of the sentinel: a bounded prefix of attacker
  material is a leak too."
  (into #{} (map #(subs disclosure-sentinel % (+ % 8)))
        (range (- (count disclosure-sentinel) 7))))

(defn- disclosing? [x]
  (let [s (pr-str x)]
    (boolean (some #(str/includes? s %) disclosure-fragments))))

(def ^:private forged-definition
  "Sentinel state ids, a sentinel-bearing unknown root key and a live `:data`
  slot: KEY-position material a root-key listing or a defect path would
  disclose."
  {:initial                                        (keyword disclosure-sentinel)
   (keyword (str disclosure-sentinel "-root-key")) 1
   :data                                           {:token disclosure-sentinel}
   :states  {(keyword disclosure-sentinel)
             {:on {:go (keyword (str disclosure-sentinel "-gone"))}}}})

(def ^:private forged-definition-huge
  "The same shape with 2000 sentinel-named states: any per-state growth blows
  the bound."
  (assoc forged-definition
         :states (into {} (map (fn [i] [(keyword (str disclosure-sentinel "-" i)) {}]))
                       (range 2000))))

(def ^:private ex-data-serialized-bound
  "The whole thrown ex-data, prose included, against ~2000 states: the bound
  proves size-independence, not brevity."
  700)

(deftest thrown-diagnostics-disclose-nothing-they-were-given
  (doseq [[surface throw-it] [["mermaid" #(mermaid/emit %)]
                              ["scxml"   #(scxml/spec->scxml %)]
                              ["ai"      #(ai/generate-machine "x" {:resolver (constantly (pr-str %))})]]]
    (doseq [[size d] [["small" forged-definition] ["huge" forged-definition-huge]]]
      (let [ex (throws-ex #(throw-it d))
            dt (ex-data ex)]
        (is (some? ex) (str surface "/" size ": must reject"))
        (is (not (disclosing? dt))
            (str surface "/" size ": no sentinel fragment anywhere in ex-data — " (pr-str dt)))
        (is (not (disclosing? (ex-message ex)))
            (str surface "/" size ": no sentinel fragment in the thrown message"))
        (is (< (count (pr-str dt)) ex-data-serialized-bound)
            (str surface "/" size ": ex-data serialized " (count (pr-str dt)) " chars")))))
  (testing "the chart projection result is the fourth stash of the same summary"
    (doseq [[size d] [["small" forged-definition] ["huge" forged-definition-huge]]]
      (let [result (layout/project-definition d)]
        (is (some? (:definition-error result)) (str "chart/" size ": rejects"))
        (is (not (disclosing? (:definition-error result)))
            (str "chart/" size ": no sentinel fragment in :definition-error — "
                 (pr-str (:definition-error result))))))))

;; ---------------------------------------------------------------------------
;; A single `:spawn`'s `:on-error` and transition-shaped `:on-done` (Spec 005
;; §Spawn-spec keys, §Final states D2) are transitions the engine takes,
;; resolved at the spawning state's level, so the chart and Mermaid draw them;
;; SCXML omits `:spawn` as documented. A fn `:on-done` folds `:data` and moves
;; nothing.

(defn- spawn-edge-keys [e]
  (select-keys e [:from :to :event :event-label :on-error? :on-done? :internal?]))

(def spawn-on-error-machine
  {:initial :idle
   :states  {:idle    {:on {:go :working}}
             :working {:spawn {:machine-id :child :on-error :failed}}
             :failed  {:final? true}}})

(deftest spawn-on-error-drawn-by-chart-and-mermaid-dropped-by-scxml
  (testing "chart and Mermaid draw working -> failed; SCXML omits it"
    (let [edges (:edges (layout/project-definition spawn-on-error-machine))]
      (is (= [{:from [:working] :to [:failed] :event :rf.machine.spawn/error
               :event-label "✗ error" :on-error? true}]
             (map spawn-edge-keys (filter :on-error? edges))))
      (is (= 2 (count edges)) "the :go edge and the :on-error edge, nothing else"))
    (is (str/includes? (mermaid-body spawn-on-error-machine) "working --> failed : ✗ error"))
    (let [out (scxml/spec->scxml spawn-on-error-machine)]
      (is (not (re-find #"on-error|child" out)) "scxml: nothing of the spawn, comments included")
      (is (= {:initial :idle
              :states  {:idle    {:on {:go :working}}
                        :working {}
                        :failed  {:final? true}}}
             (scxml/scxml->spec out)))))
  (testing "an ACTION-ONLY :on-error self-anchors in the chart and is a Mermaid note"
    (let [m   {:initial :working
               :states  {:working {:spawn {:machine-id :child
                                           :on-error {:action :log-failure}}}}}
          oe  (first (filter :on-error? (:edges (layout/project-definition m))))
          out (mermaid-body m)]
      (is (true? (:internal? oe)))
      (is (= (:source oe) (:target oe)))
      (is (str/includes? out "note right of working"))
      (is (str/includes? out "✗ error / log-failure")))))

(def spawn-on-done-machine
  {:initial :idle
   :states  {:idle    {:on {:go :working}}
             :working {:spawn {:machine-id :child :on-done {:target :loaded}}}
             :loaded  {:final? true}}})

(defn- chart-spawn-done-arrows
  "The `[from to]` pairs of the chart's `:spawn :on-done` edges."
  [definition]
  (->> (:edges (layout/project-definition definition))
       (filter #(= :rf.machine.spawn/done (:event %)))
       (map (juxt :from :to))
       set))

(defn- mermaid-done-arrows
  "The `[from to]` pairs of the `✓ done` arrows in a flat machine's Mermaid."
  [definition]
  (->> (re-seq #"  (\w+) --> (\w+) : ✓ done" (mermaid-body definition))
       (map (fn [[_ from to]] [[(keyword from)] [(keyword to)]]))
       set))

(deftest spawn-on-done-drawn-by-chart-and-mermaid-dropped-by-scxml
  (testing "chart and Mermaid draw working -> loaded; SCXML omits it"
    (let [edges (:edges (layout/project-definition spawn-on-done-machine))]
      (is (= [{:from [:working] :to [:loaded] :event :rf.machine.spawn/done
               :event-label "✓ done" :on-done? true}]
             (map spawn-edge-keys (filter #(= :rf.machine.spawn/done (:event %)) edges))))
      (is (= 2 (count edges)) "the :go edge and the :on-done edge, nothing else"))
    (is (str/includes? (mermaid-body spawn-on-done-machine) "working --> loaded : ✓ done"))
    (let [out (scxml/spec->scxml spawn-on-done-machine)]
      (is (not (str/includes? out "child")))
      (is (= {:initial :idle
              :states  {:idle    {:on {:go :working}}
                        :working {}
                        :loaded  {:final? true}}}
             (scxml/scxml->spec out)))))
  (testing "chart and Mermaid draw the same arrows for every transition shape"
    (doseq [[on-done arrows] {:b                                         #{[[:a] [:b]]}
                              [:b]                                       #{[[:a] [:b]]}
                              {:target :b}                               #{[[:a] [:b]]}
                              [{:target :b :guard :ok?} {:target :c}]    #{[[:a] [:b]] [[:a] [:c]]}}
            :let [m {:initial :a
                     :states  {:a {:spawn {:machine-id :child :on-done on-done}}
                               :b {}
                               :c {}}}]]
      (is (= arrows (chart-spawn-done-arrows m) (mermaid-done-arrows m)) (pr-str on-done))))
  (testing "an ACTION-ONLY :on-done self-anchors in the chart and is a Mermaid note"
    (let [m   {:initial :working
               :states  {:working {:spawn {:machine-id :child
                                           :on-done    {:action :store-result}}}}}
          od  (first (filter :on-done? (:edges (layout/project-definition m))))
          out (mermaid-body m)]
      (is (true? (:internal? od)))
      (is (= (:source od) (:target od)))
      (is (str/includes? out "note right of working"))
      (is (str/includes? out "✓ done / store-result"))))
  (testing "a fn :on-done, on a :spawn or a :spawn-all child, draws nothing in
            either emitter"
    (doseq [[label m] {:spawn     {:initial :working
                                   :states  {:working {:spawn {:machine-id :child
                                                               :on-done    (fn [{:keys [data]}] data)}}}}
                       :spawn-all {:initial :working
                                   :states  {:working {:spawn-all {:children [{:id :c1 :machine-id :child
                                                                               :on-done (fn [{:keys [data]}] data)}]
                                                                   :on-all-complete [:done]}}}}}]
      (is (empty? (:edges (layout/project-definition m))) (str label ": no chart edge"))
      (is (not (str/includes? (mermaid-body m) "✓ done")) (str label ": no Mermaid arrow or note")))))
