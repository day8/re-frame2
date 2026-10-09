(ns day8.re-frame2-machines-viz.mermaid-cljs-test
  "Mermaid `stateDiagram-v2` export: every transition form, the arrowless
  forms as notes, and every state declared inside its parent."
  (:require [clojure.test  :refer [deftest is testing]]
            [clojure.string :as str]
            ;; The SHARED node-id codec, to derive expected Mermaid ids from a
            ;; definition rather than from the emitter under test.
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.mermaid :as m]))

(defn- body [definition]
  (m/emit definition {:fenced? false :header-comment? false}))

(def logged-in-machine
  "A compound with hyphenated ids, so each label differs from its escaped id."
  {:initial :logged-out
   :states  {:logged-out {:on {:login :logged-in}}
             :logged-in  {:initial :browsing
                          :states  {:browsing {:on {:checkout :paying}}
                                    :paying   {:on {:paid :browsing}}}
                          :on      {:logout :logged-out}}}})

(def namespaced-ids-machine
  {:initial :auth/idle
   :states  {:auth/idle    {:on {:rf/load :auth/loading}}
             :auth/loading {:on {:done :auth/idle}}}})

(def parallel-region-machine
  {:type    :parallel
   :regions {:data {:initial :nothing
                    :states  {:nothing {:on {:fetch :loading}}
                              :loading {}}}
             :form {:initial :neutral
                    :states  {:neutral {:on {:submit :correct}}
                              :correct {:final? true}}}}})

(def compound-on-done-machine
  {:initial :flow
   :states  {:flow {:initial :collecting
                    :on-done :next
                    :states  {:collecting {:on {:submit :paid}}
                              :paid       {:final? true}}}
             :next {:on {:reset [:flow]}}}})

(def fn-shaped-machine
  "An internal `:on` beside a fn-shaped one, whose target only running it could tell."
  {:initial :a
   :states  {:a {:on {:go    {:action :record}
                      :reset (fn [_ _] {:state :a})}}
             :b {}}})

(deftest emit-renders-each-transition-form
  (doseq [[label machine & forms]
          [["a compound's own :on, and its path-qualified nested edges" logged-in-machine
            "logged_2din --> logged_2dout : logout"
            "logged_2din__browsing --> logged_2din__paying : checkout"]
           ["same-named leaves stay distinct; a vector-path target crosses compounds"
            {:initial :left
             :states  {:left  {:initial :idle
                               :states  {:idle {:on {:swap [:right :idle]}}}}
                       :right {:initial :idle
                               :states  {:idle {}}}}}
            "[*] --> left__idle"
            "left__idle --> right__idle : swap"]
           ["namespaced ids escape the `/`; their labels keep it" namespaced-ids-machine
            "auth_2fidle --> auth_2floading : rf/load"]
           ["a named inline-fn guard reads as its :name"
            {:initial :a
             :states  {:a {:on {:go {:target :b :guard (with-meta (fn [_] true) {:name 'ready?})}}}
                       :b {}}}
            "a --> b : go [ready?]"]
           ["an anonymous inline-fn guard reads as fn, never the host object"
            {:initial :a
             :states  {:a {:on {:go {:target :b :guard (fn [_] true)}}}
                       :b {}}}
            "a --> b : go [fn]"]
           ["every target-bearing branch of a candidate vector"
            {:initial :editing
             :states  {:editing      {:on {:submit [{:target :rate-limited :guard :over-limit?}
                                                    {:target :rejected}]}}
                       :rate-limited {}
                       :rejected     {}}}
            "editing --> rate_2dlimited : submit [over-limit?]"
            "editing --> rejected : submit"]
           [":after and :always as labelled edges"
            {:initial :loading
             :states  {:loading    {:after {30000 {:target :hard-error :guard :still-loading?}}
                                    :on    {:loaded :checking}}
                       :checking   {:always [{:guard :ready? :target :ready}]}
                       :hard-error {}
                       :ready      {}}}
            "loading --> hard_2derror : after(30000) [still-loading?]"
            "checking --> ready : always [ready?]"]
           ["a top-level :on leaves a labelled root fallback node"
            {:initial :a :states {:a {} :b {}} :on {:reset :b}}
            "state \"root fallback\" as rf_2emachines_2dviz_2emermaid_2froot_2dfallback"
            "rf_2emachines_2dviz_2emermaid_2froot_2dfallback --> b : reset (root fallback)"]
           ["each region renders inside a synthetic parallel root" parallel-region-machine
            "[*] --> rf_2emachines_2dviz_2emermaid_2fparallel_2droot"
            "state \"parallel root\" as rf_2emachines_2dviz_2emermaid_2fparallel_2droot {"
            "[*] --> data__nothing"
            "data__nothing --> data__loading : fetch"
            "form__correct --> [*]"]
           ["a target-bearing compound :on-done" compound-on-done-machine
            "flow --> next : ✓ done"]
           ["a parallel root's :on leaves its root fallback into a region substate"
            {:type    :parallel
             :on      {:one {:target [:a :two]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {}}}}}
            "state \"root fallback\" as "
            "--> a__two : one (root fallback)"]
           ["a multi-region root :after draws one edge per region"
            {:type    :parallel
             :after   {1000 {:target [[:a :two] [:b :two]]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
            "--> a__two : after(1000) (root fallback)"
            "--> b__two : after(1000) (root fallback)"]
           ;; Arrowless forms: an internal or action-only transition is a note.
           ["an internal transition's note carries its guard"
            {:initial :a :states {:a {:on {:tick {:action :log :guard :ready?}}}}}
            "tick [ready?] / log"]
           ["an internal :on beside a fn-shaped one is still noted" fn-shaped-machine
            "go / record"]
           ["a compound's action-only :on-done"
            {:initial :flow
             :states  {:flow {:initial :collecting
                              :on-done {:action :announce}
                              :states  {:collecting {:on {:submit :paid}}
                                        :paid       {:final? true}}}}}
            "  note right of flow\n    on-done: ✓ done / announce\n  end note"]
           ["the action-only :on-done of a compound inside a region"
            {:type    :parallel
             :regions {:audio {:initial :session
                               :states  {:session {:initial :idle
                                                   :on-done {:action :chime}
                                                   :states  {:idle {:on {:go :busy}}
                                                             :busy {:final? true}}}}}
                       :video {:initial :hidden :states {:hidden {}}}}}
            "  note right of audio__session\n    on-done: ✓ done / chime\n  end note"]
           ["a parallel root's action-only :on-done"
            {:type    :parallel
             :on-done {:action :announce}
             :regions {:fetch {:initial :loading
                               :states  {:loading {:on {:loaded :done}} :done {:final? true}}}}}
            (str "  note right of rf_2emachines_2dviz_2emermaid_2fparallel_2droot\n"
                 "    on-done: ✓ done / announce\n  end note")]
           ["a region's action-only top-level :on, on the region's own root fallback"
            {:type    :parallel
             :regions {:fetch    {:initial :loading
                                  :on      {:abort {:action :log}}
                                  :states  {:loading {}}}
                       :validate {:initial :checking :states {:checking {}}}}}
            "state \"root fallback\" as fetch__rf_2emachines_2dviz_2emermaid_2froot_2dfallback"
            (str "  note right of fetch__rf_2emachines_2dviz_2emermaid_2froot_2dfallback\n"
                 "    abort / log\n  end note")]
           ["a shallow history marker and its default target"
            {:initial :off
             :states  {:off    {:on {:resume [:player :hist]}}
                       :player {:initial :stopped
                                :states  {:stopped {:on {:play :playing}}
                                          :playing {}
                                          :hist    {:type :history :deep? false :default-target :playing}}}}}
            "state \"H\" as player__hist"
            "%% history default-target: playing"]]
          form forms]
    (is (str/includes? (body machine) form) label)))

(deftest emit-draws-nothing-for-what-has-no-arrow
  (doseq [[label machine absent]
          [["no :on-done, no completion" logged-in-machine "✓ done"]
           ["a target-bearing :on-done gets its edge, not also a note" compound-on-done-machine
            "note right of flow"]
           ["a fn-shaped transition is dropped" fn-shaped-machine "reset"]]]
    (is (not (str/includes? (body machine) absent)) label)))

(deftest mermaid-omits-cofx-requires-vocabulary
  (testing "the guard NAME survives; the consumer-attachment :rf.cofx/requires
            diet is a documented omission (spec/API.md)"
    (let [out (body {:initial :idle
                     :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms] :fn (fn [_] true)}}
                     :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms] :fn (fn [_] nil)}}
                     :states  {:idle {:on {:go {:target :busy :guard :within-window? :action :schedule-retry}}}
                               :busy {}}})]
      (is (str/includes? out "within-window?"))
      (is (not (re-find #"rf\.cofx|requires|time-ms|retry-jitter-ms" out))))))

;; ---------------------------------------------------------------------------
;; Mermaid scopes a state to the block it is FIRST mentioned in, and labels it
;; with its id unless an alias declares a label. So every state must be
;; declared, labelled with its name, inside its parent block — or it is drawn
;; outside its compound / region, reading as a hex-escaped id.

(defn- line-ids
  "The Mermaid ids a single (trimmed) body line mentions, in order. Note
  bodies are free text and never reach here."
  [t]
  (cond
    (or (= "" t) (= "}" t) (= "--" t)
        (str/starts-with? t "%%") (str/starts-with? t "stateDiagram"))
    []
    (str/starts-with? t "state ")
    [(second (or (re-find #"^state \"[^\"]*\" as ([A-Za-z0-9_]+)" t)
                 (re-find #"^state ([A-Za-z0-9_]+)" t)))]
    (str/starts-with? t "note ")
    [(second (re-find #"^note \w+ of ([A-Za-z0-9_]+)" t))]
    (str/includes? t "-->")
    (->> (str/split (first (str/split t #" : " 2)) #"-->")
         (map str/trim)
         (remove #(= "[*]" %)))
    :else []))

(defn- first-mention-scopes
  "Map every Mermaid id in `body` to the composite block open at its FIRST
  mention (`nil` = the diagram root) — Mermaid's scoping rule."
  [body]
  (loop [lines (map str/trim (str/split-lines body)) stack [] in-note? false seen {}]
    (if-let [t (first lines)]
      (cond
        in-note?                        (recur (rest lines) stack (not= "end note" t) seen)
        (str/starts-with? t "note ")    (recur (rest lines) stack true
                                               (reduce #(if (contains? %1 %2) %1 (assoc %1 %2 (peek stack)))
                                                       seen (line-ids t)))
        :else
        (let [seen' (reduce #(if (contains? %1 %2) %1 (assoc %1 %2 (peek stack)))
                            seen (line-ids t))
              stack' (cond (and (str/starts-with? t "state ") (str/ends-with? t "{"))
                           (conj stack (first (line-ids t)))
                           (= "}" t) (pop stack)
                           :else stack)]
          (recur (rest lines) stack' false seen')))
      seen)))

(defn- expected-declarations
  "`[id label parent-id]` for every state of `definition`, from its own tree:
  the id via the shared node-id codec, the label the state's `ns/name`, the
  parent the enclosing compound / region (or the synthetic parallel root)."
  [definition]
  (letfn [(walk [states parent-path parent-id]
            (mapcat (fn [[k node]]
                      (let [path (conj parent-path k)
                            id   (layout/node-id path)]
                        (cons [id (m/keyword-label k) parent-id]
                              (walk (:states node) path id))))
                    states))]
    (if (= :parallel (:type definition))
      (let [root-id "rf_2emachines_2dviz_2emermaid_2fparallel_2droot"]
        (mapcat (fn [[r region]]
                  (let [rid (layout/node-id [r])]
                    (cons [rid (m/keyword-label r) root-id]
                          (walk (:states region) [r] rid))))
                (:regions definition)))
      (walk (:states definition) [] nil))))

(deftest emit-declares-every-state-labelled-inside-its-parent
  (doseq [[label definition] [["compound" logged-in-machine]
                              ["parallel" parallel-region-machine]
                              ["namespaced" namespaced-ids-machine]]]
    (let [out    (body definition)
          scopes (first-mention-scopes out)]
      (doseq [[id state-label parent] (expected-declarations definition)]
        (is (re-find (re-pattern (str "(?m)^\\s*state \"" state-label "\" as " id "( \\{)?$")) out)
            (str label ": " id " is declared with its name as the label"))
        (is (= parent (get scopes id ::never-mentioned))
            (str label ": " id " is first mentioned inside "
                 (or parent "the root")))))))
