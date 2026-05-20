(ns re-frame.machines.mermaid-cljs-test
  "Pure-data tests for the Mermaid `stateDiagram-v2` exporter
  (rf2-deo2i, relocated per rf2-yamkm). The `_cljs_test` naming
  follows the convention from implementation/machines/test/ —
  Cognitect's test-runner picks the ns up via the default `.*-test$`
  regex, and Shadow's `:node-test` build picks it up via
  `cljs-test$`."
  (:require [clojure.test  :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.machines.mermaid :as m]))

;; -----------------------------------------------------------------------------
;; Fixtures — small, hand-curated machine definitions per Spec 005
;; §Transition table grammar.

(def idle-loading-success-error
  "The canonical small machine from the bead description: idle →
  loading → success / error. Two final states."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(def compound-machine
  "A compound machine with one nested region — exercises Mermaid's
  `state X { ... }` block."
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(def duplicate-nested-leaves-machine
  "Two compound parents both contain an :idle leaf. Mermaid ids must
  use the full path so those leaves do not collapse."
  {:initial :left
   :states  {:left  {:initial :idle
                     :states  {:idle {:on {:swap [:right :idle]}}}}
             :right {:initial :idle
                     :states  {:idle {}}}}})

(def vector-path-target-machine
  "Compound transitions may target absolute vector paths."
  {:initial :unauth
   :states  {:unauth {:on {:login [:authenticated :browsing]}}
             :authenticated
             {:initial :browsing
              :states  {:browsing {:on {:logout [:unauth]}}}}}})

(def timed-and-eventless-machine
  "State-node :after and :always transitions are static topology and
  should render as lossy labelled edges."
  {:initial :loading
   :states  {:loading {:after {5000  :timeout
                               30000 {:target :hard-error
                                      :guard  :still-loading?}}
                       :on    {:loaded :checking}}
             :checking {:always [{:guard :ready? :target :ready}
                                 {:target :blocked}]}
             :timeout {}
             :hard-error {}
             :ready {}
             :blocked {}}})

(def parallel-region-machine
  "A :type :parallel root with two independent region state trees."
  {:type    :parallel
   :regions {:data {:initial :nothing
                    :states  {:nothing {:on {:fetch :loading}}
                              :loading {}}}
             :form {:initial :neutral
                    :states  {:neutral {:on {:submit :correct}}
                              :correct {:final? true}}}}})

(def namespaced-ids-machine
  "Machine using namespaced and hyphenated ids — exercises
  sanitise-id."
  {:initial :auth/idle
   :states  {:auth/idle      {:on {:rf/load :auth/loading}}
             :auth/loading   {:on {:done :auth/idle}}}})

;; -----------------------------------------------------------------------------
;; Tests

(deftest emit-returns-fenced-block-by-default
  (testing "default output is wrapped in a ```mermaid fence"
    (let [out (m/emit idle-loading-success-error)]
      (is (str/starts-with? out "```mermaid\n"))
      (is (str/ends-with?   out "\n```")))))

(deftest emit-unfenced-omits-the-fence
  (testing ":fenced? false strips the markdown fence"
    (let [out (m/emit idle-loading-success-error {:fenced? false})]
      (is (not (str/includes? out "```")))
      (is (str/includes? out "stateDiagram-v2")))))

(deftest emit-includes-stateDiagram-v2-header
  (testing "every emit starts the diagram with `stateDiagram-v2`"
    (is (str/includes? (m/emit idle-loading-success-error)
                       "stateDiagram-v2"))))

(deftest emit-renders-initial-state-edge
  (testing "[*] --> initial-state appears at the top of the diagram"
    (is (str/includes? (m/emit idle-loading-success-error)
                       "[*] --> idle"))))

(deftest emit-renders-transitions
  (testing "every statically-resolvable transition appears as an edge"
    (let [out (m/emit idle-loading-success-error)]
      (is (str/includes? out "idle --> loading : start"))
      (is (str/includes? out "loading --> success : ok"))
      (is (str/includes? out "loading --> failed : err")))))

(deftest emit-renders-final-state-edges
  (testing ":final? true states render `state --> [*]`"
    (let [out (m/emit idle-loading-success-error)]
      (is (str/includes? out "success --> [*]"))
      (is (str/includes? out "failed --> [*]")))))

(deftest emit-includes-omission-caveat-by-default
  (testing "the `:after` + `:spawn-all` omission caveat lands at the top"
    (let [out (m/emit idle-loading-success-error)]
      (is (str/includes? out "%% Generated by re-frame.machines.mermaid"))
      (is (str/includes? out ":after rings + :spawn-all rows omitted")))))

(deftest emit-can-suppress-header-comment
  (testing ":header-comment? false drops the %% caveat lines"
    (let [out (m/emit idle-loading-success-error {:header-comment? false})]
      (is (not (str/includes? out "%%"))))))

(deftest emit-renders-compound-state-block
  (testing "compound states render as `state X { ... }` with inner [*] --> initial"
    (let [out (m/emit compound-machine)]
      (is (str/includes? out "state authenticated {"))
      (is (str/includes? out "[*] --> authenticated__browsing"))
      (is (str/includes? out "}")))))

(deftest emit-renders-cross-region-transitions
  (testing "transitions across compound boundaries render as flat edges"
    (let [out (m/emit compound-machine)]
      ;; unauth --> authenticated (top-level edge)
      (is (str/includes? out "unauth --> authenticated : login"))
      ;; authenticated --> unauth (compound-state-out edge, on the
      ;; compound state's `:on` map)
      (is (str/includes? out "authenticated --> unauth : logout"))
      ;; nested edges still emit
      (is (str/includes? out "authenticated__browsing --> authenticated__paying : checkout"))
      (is (str/includes? out "authenticated__paying --> authenticated__browsing : done")))))

(deftest emit-path-qualifies-duplicate-nested-leaves
  (testing "duplicate child names under different parents get distinct Mermaid ids"
    (let [out (m/emit duplicate-nested-leaves-machine)]
      (is (str/includes? out "[*] --> left__idle"))
      (is (str/includes? out "[*] --> right__idle"))
      (is (str/includes? out "left__idle --> right__idle : swap")))))

(deftest emit-renders-vector-path-targets
  (testing "absolute vector-path targets render without crashing"
    (let [out (m/emit vector-path-target-machine)]
      (is (str/includes? out "unauth --> authenticated__browsing : login"))
      (is (str/includes? out "authenticated__browsing --> unauth : logout")))))

(deftest emit-sanitises-namespaced-and-hyphenated-ids
  (testing "namespaced + hyphenated keywords map to underscore-joined ids"
    (let [out (m/emit namespaced-ids-machine)]
      ;; :auth/idle → auth_idle, :auth/loading → auth_loading
      (is (str/includes? out "auth_idle"))
      (is (str/includes? out "auth_loading"))
      ;; :rf/load → "rf/load" as the edge label (sanitise-label keeps
      ;; the slash; only sanitise-id strips it)
      (is (str/includes? out "auth_idle --> auth_loading : rf/load")))))

(deftest emit-validates-definition
  (testing "definitions missing :initial or :states throw :invalid-definition"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo)
                 (m/emit {})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo)
                 (m/emit {:initial :idle})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo)
                 (m/emit {:initial :idle :states {}})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo)
                 (m/emit {:type :parallel :regions {}})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo)
                 (m/emit {:type :parallel
                          :regions {:data {:initial :idle
                                           :states  {}}}})))))

(deftest emit-renders-wildcard-transitions
  (testing ":* wildcard transitions render as real topology"
    (let [m   {:initial :a
               :states  {:a {:on {:* :b
                                  :go :b}}
                         :b {}}}
          out (m/emit m)]
      (is (str/includes? out "a --> b : go"))
      (is (str/includes? out "a --> b : *")))))

(deftest emit-handles-map-shaped-transition-spec
  (testing "map-shaped transition specs with :target render their target and guard"
    (let [m   {:initial :a
               :states  {:a {:on {:go {:target :b
                                       :action :record
                                       :guard  :ready?}}}
                         :b {}}}
          out (m/emit m)]
      (is (str/includes? out "a --> b : go [ready?]")))))

(deftest emit-renders-multiple-candidate-transition-vectors
  (testing "candidate vectors render every target-bearing branch"
    (let [m   {:initial :editing
               :states  {:editing {:on {:submit [{:target :rate-limited
                                                  :guard  :over-limit?}
                                                 {:target :validating
                                                  :guard  :email-valid?}
                                                 {:target :rejected}]}}
                         :rate-limited {}
                         :validating {}
                         :rejected {}}}
          out (m/emit m)]
      (is (str/includes? out "editing --> rate_limited : submit [over-limit?]"))
      (is (str/includes? out "editing --> validating : submit [email-valid?]"))
      (is (str/includes? out "editing --> rejected : submit")))))

(deftest emit-renders-after-and-always-transitions
  (testing ":after and :always targets render as lossy labelled edges"
    (let [out (m/emit timed-and-eventless-machine)]
      (is (str/includes? out "loading --> timeout : after(5000)"))
      (is (str/includes? out "loading --> hard_error : after(30000) [still-loading?]"))
      (is (str/includes? out "checking --> ready : always [ready?]"))
      (is (str/includes? out "checking --> blocked : always")))))

(deftest emit-renders-top-level-fallback-on
  (testing "top-level :on is explicit via a synthetic root fallback node"
    (let [m   {:initial :a
               :states  {:a {}
                         :b {}}
               :on      {:reset :b
                         :*     :a}}
          out (m/emit m)]
      (is (str/includes? out "state \"root fallback\" as rf_machines_mermaid_root_fallback"))
      (is (str/includes? out "rf_machines_mermaid_root_fallback --> b : reset (root fallback)"))
      (is (str/includes? out "rf_machines_mermaid_root_fallback --> a : * (root fallback)")))))

(deftest emit-renders-parallel-region-machines
  (testing ":type :parallel renders each region inside a synthetic parallel root"
    (let [out (m/emit parallel-region-machine)]
      (is (str/includes? out "[*] --> rf_machines_mermaid_parallel_root"))
      (is (str/includes? out "state rf_machines_mermaid_parallel_root {"))
      (is (str/includes? out "state data {"))
      (is (str/includes? out "[*] --> data__nothing"))
      (is (str/includes? out "data__nothing --> data__loading : fetch"))
      (is (str/includes? out "state form {"))
      (is (str/includes? out "[*] --> form__neutral"))
      (is (str/includes? out "form__neutral --> form__correct : submit"))
      (is (str/includes? out "form__correct --> [*]"))
      (is (str/includes? out "broadcast macrostep semantics are lossy")))))

(deftest emit-drops-target-less-transitions
  (testing "internal / fn-shaped transitions without a resolvable :target are dropped"
    (let [m   {:initial :a
               :states  {:a {:on {:go     {:action :record}        ;; no :target
                                  :reset  (fn [_ _] {:state :a})}} ;; fn-shaped
                         :b {}}}
          out (m/emit m)]
      ;; Neither :go nor :reset can be rendered without a known target;
      ;; both should be silently dropped (the topology stays valid).
      (is (not (str/includes? out "a --> "))))))

;; -----------------------------------------------------------------------------
;; Smoke: round-trip the bead's example through emit + verify the
;; shape a GitHub reader would see.

(deftest emit-smoke-round-trip
  (testing "the canonical bead fixture emits a plausible Mermaid block"
    (let [out (m/emit idle-loading-success-error)]
      ;; Has the fence
      (is (str/starts-with? out "```mermaid"))
      ;; Has the header
      (is (str/includes? out "stateDiagram-v2"))
      ;; Has the initial-edge
      (is (str/includes? out "[*] --> idle"))
      ;; Has every transition
      (is (str/includes? out "idle --> loading : start"))
      (is (str/includes? out "loading --> success : ok"))
      (is (str/includes? out "loading --> failed : err"))
      ;; Has both terminal edges
      (is (str/includes? out "success --> [*]"))
      (is (str/includes? out "failed --> [*]"))
      ;; Mentions the omission caveat
      (is (str/includes? out ":after rings + :spawn-all rows omitted"))
      ;; Closes the fence
      (is (str/ends-with? out "```")))))
