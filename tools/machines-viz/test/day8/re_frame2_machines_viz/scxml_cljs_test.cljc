(ns day8.re-frame2-machines-viz.scxml-cljs-test
  "SCXML export / import: the W3C forms the export writes, the exact round
  trip over the supported subset, and the importer's refusals."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.scxml :as scxml]))

(def ^:private scxml-format-marker
  "Spelled out so a change to the marker is a visible format change here too."
  "<!-- re-frame2 machines-viz SCXML v1 -->")

(defn- marked
  "A hand-written import input, led by the format marker."
  [& parts]
  (apply str scxml-format-marker "\n" parts))

;; ---------------------------------------------------------------------------
;; Fixtures

(def idle-loading-success-error
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(def compound-machine
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(def namespaced-machine
  {:initial :auth/idle
   :states  {:auth/idle    {:on {:rf/load :auth/loading}}
             :auth/loading {:on {:done :auth/idle}}}})

(def always-machine
  {:initial :checking
   :states  {:checking {:always [{:target :ready :guard :ready?}
                                 {:target :blocked}]}
             :ready    {}
             :blocked  {}}})

(def iso-after-machine
  {:initial :loading
   :states  {:loading {:after {"PT1S" :timeout "PT0.5S" :done}}
             :timeout {}
             :done    {}}})

(def machine-level-on-machine
  {:initial :a
   :on      {:logout :a}
   :states  {:a {:on {:go :b}}
             :b {}}})

(def parallel-machine
  {:type    :parallel
   :regions {:data {:initial :nothing
                    :states  {:nothing {:on {:fetch :loading}}
                              :loading {}}}
             :form {:initial :neutral
                    :states  {:neutral {:on {:submit :correct}}
                              :correct {:final? true}}}}})

(def vector-path-target-machine
  {:initial :idle
   :states  {:idle          {:on {:login [:authenticated :browsing]}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {}}}}})

(def nested-same-name-machine
  "Two `:idle` states under different parents: bare ids would collide."
  {:initial :a
   :states  {:a {:initial :idle :states {:idle {:on {:go [:b :idle]}}}}
             :b {:initial :idle :states {:idle {:on {:back [:a :idle]}}}}}})

(def compound-on-done-machine
  {:initial :flow
   :states  {:flow {:initial :collecting
                    :on-done :next
                    :states  {:collecting {:on {:submit :submitting}}
                              :submitting {:on {:ok :paid}}
                              :paid       {:final? true}}}
             :next {:on {:reset :flow}}}})

(def parallel-on-done-machine
  "A parallel-root `:on-done` is action-only and its action rides a comment,
  so the round-trip fixture carries the empty completion `{}`."
  {:type    :parallel
   :on-done {}
   :regions {:fetch    {:initial :loading :states {:loading {:on {:loaded :done}} :done {:final? true}}}
             :validate {:initial :checking :states {:checking {:on {:ok :done}} :done {:final? true}}}}})

(def parallel-root-on-multi-machine
  {:type    :parallel
   :on      {:advance [[:a :x] [:b :y]]}
   :regions {:a {:initial :one :states {:one {} :x {}}}
             :b {:initial :one :states {:one {} :y {}}}}})

(def shallow-history-machine
  {:initial :off
   :states  {:off    {:on {:resume [:player :hist]}}
             :player {:initial :stopped
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}
                                :hist    {:type :history :deep? false}}
                      :on      {:power-off :off}}}})

(def absent-deep-history-machine
  (update-in shallow-history-machine [:states :player :states :hist] dissoc :deep?))

(def internal-self-machine
  {:initial :a :states {:a {:on {:ping :same-state}}}})

(def reenter-self-machine
  {:initial :a :states {:a {:on {:ping {:target :same-state :reenter? true}}}}})

(def reenter-descendant-machine
  {:initial :outer
   :states  {:outer {:initial :inner1
                     :on      {:restart {:target [:outer :inner2] :reenter? true}}
                     :states  {:inner1 {}
                               :inner2 {}}}}})

;; ---------------------------------------------------------------------------
;; Export

(deftest export-leads-with-prolog-marker-and-w3c-root
  (testing "line 1 is the XML prolog, line 2 the format marker `scxml->spec`
            requires, line 3 the W3C-namespaced root — flat and parallel alike"
    (doseq [machine [idle-loading-success-error parallel-machine]]
      (let [[prolog marker root] (str/split-lines (scxml/spec->scxml machine))]
        (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" prolog))
        (is (= scxml-format-marker marker))
        (is (str/starts-with? root "<scxml xmlns=\"http://www.w3.org/2005/07/scxml\""))))))

(deftest export-writes-the-v1-w3c-forms
  (testing "the markup external SCXML tools read — which the round trip cannot
            see, since the decoder inverts whatever the encoder does"
    (let [fn-refs {:initial :a
                   :states  {:a {:on {:go   {:target :b :guard (with-meta (fn [_] true) {:name 'ready?})}
                                      :tick {:action (fn [_] {})}}}
                             :b {}}}]
      (doseq [[label machine form]
              [["ns/name marker" namespaced-machine
                "<transition event=\"rf-load\" target=\"auth-loading\" type=\"internal\"/>"]
               ["vector-path marker" vector-path-target-machine
                "<transition event=\"login\" target=\"authenticated___browsing\" type=\"internal\"/>"]
               ["nested ids are path-qualified, so unique" nested-same-name-machine
                "<state id=\"a___idle\">"]
               ["parallel root element" parallel-machine
                "<parallel id=\"rf2_parallel_root\">"]
               ["region initial is qualified" parallel-machine
                "<state id=\"data\" initial=\"data___nothing\">"]
               ["eventless :always, guard cond, explicit internal type" always-machine
                "<transition target=\"ready\" type=\"internal\" cond=\"ready_3f\"/>"]
               ["ISO-8601 :after rides verbatim" iso-after-machine
                "event=\"after.PT1S\""]
               ["multi-region root target is a space-separated id list" parallel-root-on-multi-machine
                "target=\"a___x b___y\""]
               ["compound :on-done" compound-on-done-machine
                "<transition event=\"done.state.flow\" target=\"next\" type=\"internal\"/>"]
               ["parallel-root :on-done" parallel-on-done-machine
                "<transition event=\"done.state.rf2_parallel_root\"/>"]
               ["self-target names the source's declared id, internal default" internal-self-machine
                "<transition event=\"ping\" target=\"a\" type=\"internal\"/>"]
               ["self-target with :reenter? true is external" reenter-self-machine
                "<transition event=\"ping\" target=\"a\" type=\"external\"/>"]
               ["history is a <history>, never an occupiable <state>; absent :deep? is shallow"
                absent-deep-history-machine
                "<history id=\"player___hist\" type=\"shallow\"/>"]
               ["machine-level :on is exported, not dropped" machine-level-on-machine
                "<transition event=\"logout\" target=\"a\" type=\"internal\"/>"]
               ["a named inline-fn guard exports its name" fn-refs
                "cond=\"ready?\""]
               ["an anonymous inline-fn action exports the stable fn label" fn-refs
                "<!-- action: fn -->"]]]
        (is (str/includes? (scxml/spec->scxml machine) form) label)))))

;; Every target names a state the document declares. A scope mistake made
;; symmetrically in the encoder and the decoder still round-trips, so only the
;; exported document can show it.

(defn- declared-state-ids [xml]
  (->> (re-seq #"<(?:state|final|parallel|history)\b[^>]*\bid=\"([^\"]+)\"" xml)
       (map second)
       set))

(defn- transition-target-ids
  "Every id a `<transition target=…>` names; a W3C target is a space-separated list."
  [xml]
  (->> (re-seq #"<transition\b[^>]*\btarget=\"([^\"]+)\"" xml)
       (map second)
       (mapcat #(str/split % #"\s+"))))

(deftest exported-targets-reference-declared-ids
  (doseq [machine [compound-machine reenter-descendant-machine]]
    (let [xml (scxml/spec->scxml machine)]
      (is (empty? (remove (declared-state-ids xml) (transition-target-ids xml)))))))

(def ^:private region-root-on-machine
  {:type    :parallel
   :regions {:a {:initial :idle
                 :on      {:reset :idle}
                 :states  {:idle {:on {:go :b}} :b {}}}
             :b {:initial :x :states {:x {}}}}})

(deftest region-targets-are-region-scoped
  (testing "a target declared inside a region resolves within it (Spec 005
            §Cross-region coordination), so it carries the region prefix every
            region state id carries, and the import strips it back off. A
            machine-root reading emits dangling ids that still round-trip,
            hence the exact ids"
    (doseq [[label machine form]
            [["in-region vector target"
              {:type    :parallel
               :regions {:r {:initial :a
                             :states  {:a {:on {:go [:c :d]}}
                                       :c {:initial :d :states {:d {} :e {}}}}}
                         :q {:initial :x :states {:x {}}}}}
              "<transition event=\"go\" target=\"r___c___d\" type=\"internal\"/>"]
             ["a region body's own :on keyword target" region-root-on-machine
              "<transition event=\"reset\" target=\"a___idle\" type=\"internal\"/>"]
             ["an in-region sibling keyword" region-root-on-machine
              "<transition event=\"go\" target=\"a___b\" type=\"internal\"/>"]
             ["a head shadowing a sibling region's name stays in-region"
              {:type    :parallel
               :regions {:r {:initial :a
                             :states  {:a {:on {:go [:q :y]}}
                                       :q {:initial :y :states {:y {}}}}}
                         :q {:initial :y :states {:y {}}}}}
              "<transition event=\"go\" target=\"r___q___y\" type=\"internal\"/>"]
             ["a vector history :default-target"
              {:type    :parallel
               :regions {:r {:initial :c
                             :states  {:c {:initial :d
                                           :states  {:d {}
                                                     :f {:initial :g :states {:g {}}}
                                                     :h {:type           :history
                                                         :deep?          true
                                                         :default-target [:c :f :g]}}}}}
                         :q {:initial :x :states {:x {}}}}}
              "<transition target=\"r___c___f___g\"/>"]]]
      (let [xml (scxml/spec->scxml machine)]
        (is (str/includes? xml form) label)
        (is (= machine (scxml/scxml->spec xml)) label)))))

(deftest scxml-omits-cofx-requires-vocabulary
  (testing "guard and action NAMES survive; the consumer-attachment
            :rf.cofx/requires diet is a documented omission (spec/API.md)"
    (let [out (scxml/spec->scxml
                {:initial :idle
                 :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms] :fn (fn [_] true)}}
                 :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms] :fn (fn [_] nil)}}
                 :states  {:idle {:on {:go {:target :busy :guard :within-window? :action :schedule-retry}}}
                           :busy {}}})]
      (is (str/includes? out "cond="))
      (is (str/includes? out "action:"))
      (is (not (re-find #"rf\.cofx|requires|time-ms|retry-jitter-ms" out))))))

;; ---------------------------------------------------------------------------
;; Round trip

(deftest round-trips-exactly
  (testing "`(= spec (-> spec spec->scxml scxml->spec))` across the supported
            subset; the id-codec rows are each a past mis-decode"
    (doseq [[label spec]
            [["flat, finals" idle-loading-success-error]
             ["compound" compound-machine]
             ["guard" {:initial :checking
                       :states  {:checking {:on {:check {:target :ready :guard :ready?}}}
                                 :ready    {:final? true}}}]
             ["numeric :after" {:initial :loading
                                :states  {:loading {:after {5000 :timeout} :on {:loaded :done}}
                                          :timeout {}
                                          :done    {}}}]
             ["ISO-8601 :after" iso-after-machine]
             ["eventless :always" always-machine]
             ["parallel" parallel-machine]
             ["a mixed candidate vector keeps its {:target …} map" {:initial :idle
                                                                    :states  {:idle {:after {1000 [{:target :a :guard :g1} {:target :b}]}}
                                                                              :a    {}
                                                                              :b    {}}}]
             ["namespaced ids" namespaced-machine]
             ["a vector-path target stays a vector" vector-path-target-machine]
             ["a namespaced vector segment" {:initial :a
                                             :states  {:a           {:on {:go [:auth/region :browsing]}}
                                                       :auth/region {:initial :browsing
                                                                     :states  {:browsing {}}}}}]
             ["same-named nested states" nested-same-name-machine]
             ["a multi-dot namespace" {:initial :idle
                                       :states  {:idle   {:on {:my.app.auth/login :active}}
                                                 :active {:final? true}}}]
             ["its dot-joined twin" {:initial :idle
                                     :states  {:idle   {:on {:my/app.auth.login :active}}
                                               :active {:final? true}}}]
             ["a dotted name stays un-namespaced" {:initial :a.b.c
                                                   :states  {:a.b.c {:on {:go :d}}
                                                             :d     {}}}]
             ["CJK ids" {:initial (keyword "开始")
                         :states  {(keyword "开始") {:on {:go (keyword "结束")}}
                                   (keyword "结束") {}}}]
             ["a namespaced CJK id and event" {:initial (keyword "开始" "名")
                                               :states  {(keyword "开始" "名") {:on {(keyword "登录" "成功") :done}}
                                                         :done                {:final? true}}}]
             ["a name leading with an escaped char: state id" {:initial :a/-b
                                                               :states  {:a/-b {:on {:go :done}}
                                                                         :done {:final? true}}}]
             ["a name leading with an escaped char: vector segment" {:initial :a
                                                                     :states  {:a            {:on {:go [:auth/-region :browsing]}}
                                                                               :auth/-region {:initial :browsing
                                                                                              :states  {:browsing {}}}}}]
             ["a name leading with an escaped char: namespaced guard" {:initial :s0
                                                                       :states  {:s0 {:on {:go {:target :s1 :guard :x/?ready}}}
                                                                                 :s1 {}}}]
             ["user event :after.foo is not a timer" {:initial :idle
                                                      :states  {:idle {:on {:after.foo :done}}
                                                                :done {:final? true}}}]
             ["user event :done.state.flow is not an :on-done" {:initial :idle
                                                                :states  {:idle {:on {:done.state.flow :done}}
                                                                          :done {:final? true}}}]
             ;; An action rides a comment; it comes back, never as the `{}` forbidden block.
             ["internal action :on" {:initial :a :states {:a {:on {:tick {:action :log}}}}}]
             ["internal action :after" {:initial :a :states {:a {:after {1000 {:action :timeout-log}}}}}]
             ["internal action :always" {:initial :a :states {:a {:always [{:action :poll}]}}}]
             ["guarded internal action" {:initial :a :states {:a {:on {:tick {:action :log :guard :ready?}}}}}]
             ["namespaced action" {:initial :a :states {:a {:on {:tick {:action :log/append}}}}}]
             ["a genuine forbidden block stays {}" {:initial :a :states {:a {:on {:tick {}}}}}]
             ["shallow history" shallow-history-machine]
             ["deep history" (assoc-in shallow-history-machine [:states :player :states :hist :deep?] true)]
             ["history :default-target" (assoc-in shallow-history-machine
                                                  [:states :player :states :hist :default-target] :playing)]
             ["internal self-target" internal-self-machine]
             ["self-target with :reenter? true" reenter-self-machine]
             ["descendant target with :reenter? true" reenter-descendant-machine]
             ["compound :on-done" compound-on-done-machine]
             ["parallel-root :on-done" parallel-on-done-machine]
             ["root :on, multi-region" parallel-root-on-multi-machine]
             ["root :after, one region" {:type    :parallel
                                         :after   {500 [:a :two]}
                                         :regions {:a {:initial :one :states {:one {} :two {}}}
                                                   :b {:initial :one :states {:one {} :two {}}}}}]
             ["guarded root :on" {:type    :parallel
                                  :on      {:fire {:target [[:a :two] [:b :two]] :guard :armed?}}
                                  :regions {:a {:initial :one :states {:one {} :two {}}}
                                            :b {:initial :one :states {:one {} :two {}}}}}]
             ["action-only root :on" {:type    :parallel
                                      :on      {:ping {:action :log-ping}}
                                      :regions {:a {:initial :one :states {:one {}}}
                                                :b {:initial :one :states {:one {}}}}}]]]
      (is (= spec (-> spec scxml/spec->scxml scxml/scxml->spec)) label))))

(deftest import-canonicalises-or-drops-what-scxml-cannot-carry
  (let [root-on (fn [target]
                  {:type    :parallel
                   :on      {:one target}
                   :regions {:a {:initial :one :states {:one {} :two {}}}
                             :b {:initial :one :states {:one {} :two {}}}}})]
    (doseq [[label spec expected]
            [["a sole {:target …} root candidate comes back as the bare target"
              (root-on {:target [:a :two]}) (root-on [:a :two])]
             ["an absent :deep? comes back as the normalised :deep? false"
              absent-deep-history-machine shallow-history-machine]
             ["a machine-level :on has no slot to come back in"
              machine-level-on-machine (dissoc machine-level-on-machine :on)]]]
      (is (= expected (-> spec scxml/spec->scxml scxml/scxml->spec)) label))))

;; ---------------------------------------------------------------------------
;; Import: unsupported content is ignored, never read as topology

(deftest import-ignores-executable-and-datamodel-subtrees-wholesale
  (is (= {:initial :go
          :states  {:go   {:on {:next :wait}}
                    :wait {:on {:finish :done}}
                    :done {:final? true}}}
         (scxml/scxml->spec
           (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='go'>"
                   "<datamodel><data id='counter' expr='0'/></datamodel>"
                   "<state id='go'>"
                   "<onentry><log expr='entering'/></onentry>"
                   "<invoke type='http://www.w3.org/TR/scxml/'><param name='p' expr='1'/></invoke>"
                   "<transition event='next' target='wait'/>"
                   "</state>"
                   "<state id='wait'>"
                   "<onexit><assign location='counter' expr='counter'/></onexit>"
                   "<transition event='finish' target='done'/>"
                   "</state>"
                   "<final id='done'/>"
                   "</scxml>")))))

(deftest import-does-not-promote-a-state-nested-in-an-unsupported-element
  (is (= {:initial :a
          :states  {:a {:on {:go :b}}
                    :b {}}}
         (scxml/scxml->spec
           (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='a'>"
                   "<state id='a'>"
                   "<onentry><state id='sneaky'/><final id='sneakier'/></onentry>"
                   "<transition event='go' target='b'/>"
                   "</state>"
                   "<state id='b'/>"
                   "</scxml>")))))

(deftest import-does-not-adopt-a-nested-parallel-root
  (testing "a <parallel> that is not a direct child of <scxml> — a W3C §6.4
            <invoke><content> document, or one nested in a <state> — never
            replaces the outer machine"
    (let [outer   {:initial :idle
                   :states  {:idle {:on {:go :done}}
                             :done {:final? true}}}
          invoked (-> (scxml/spec->scxml {:type    :parallel
                                          :regions {:left  {:initial :a :states {:a {}}}
                                                    :right {:initial :b :states {:b {}}}}})
                      (str/replace #"(?s)^\s*<\?xml[^?]*\?>\s*" ""))]
      (doseq [[label nested]
              [["invoked document"
                (str "<invoke type='http://www.w3.org/TR/scxml/'><content>" invoked "</content></invoke>")]
               ["nested in a state"
                (str "<parallel id='nested'>"
                     "<state id='nested___left' initial='nested___left___a'>"
                     "<state id='nested___left___a'/></state>"
                     "<state id='nested___right' initial='nested___right___b'>"
                     "<state id='nested___right___b'/></state>"
                     "</parallel>")]]]
        (is (= outer (scxml/scxml->spec
                       (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='idle'>"
                               "<state id='idle'>"
                               "<transition event='go' target='done'/>"
                               nested
                               "</state>"
                               "<final id='done'/>"
                               "</scxml>")))
            label)))))

;; ---------------------------------------------------------------------------
;; Import: refusals. `scxml->spec` reads only this library's own marked
;; exports — its codec would silently mis-decode a foreign document's ids.

(def ^:private foreign-scxml
  (str "<scxml xmlns=\"http://www.w3.org/2005/07/scxml\" version=\"1.0\" initial=\"logged-out\">"
       "<state id=\"logged-out\"><transition event=\"login-ok\" target=\"step_1a\"/></state>"
       "<state id=\"step_1a\"><transition event=\"logout session.expired\" target=\"logged-out\"/></state>"
       "</scxml>"))

(defn- refusal
  "The ex-data `scxml->spec` throws for `input`, or `::returned`."
  [input]
  (try (scxml/scxml->spec input)
       ::returned
       (catch #?(:clj Exception :cljs :default) e (ex-data e))))

(deftest scxml->spec-refuses-with-its-documented-id-and-recovery
  (doseq [[label input expected]
          [["no <scxml> root" (marked "<not-scxml/>")
            {:rf.error/id :scxml/parse-error}]
           ["an unclosed root" (marked "<scxml initial=\"a\"><state id=\"a\"/>")
            {:rf.error/id :scxml/parse-error :recovery :close-the-scxml-root}]
           ["no states" (marked "<scxml></scxml>")
            {:rf.error/id :scxml/invalid-spec :recovery :add-a-state-or-final-element}]
           ["an unmarked foreign document" foreign-scxml
            {:rf.error/id :scxml/unsupported-format :recovery :re-export-from-the-source-definition}]
           ["a different marker version" (str "<!-- re-frame2 machines-viz SCXML v2 -->\n" foreign-scxml)
            {:rf.error/id :scxml/unsupported-format}]
           ["the marker anywhere but first" (str "<!-- a comment first -->\n" scxml-format-marker "\n" foreign-scxml)
            {:rf.error/id :scxml/unsupported-format}]]]
    (is (= expected (select-keys (refusal input) (keys expected))) label)))

(deftest import-accepts-the-same-bytes-once-marked
  (testing "the marker is the whole difference, and it may follow an XML prolog
            with whitespace around it"
    (is (map? (scxml/scxml->spec (str scxml-format-marker "\n" foreign-scxml))))
    (is (map? (scxml/scxml->spec (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n  "
                                      scxml-format-marker "\n\n" foreign-scxml))))))

(deftest import-errors-carry-a-summary-never-the-input
  (testing "an imported document is untrusted: each refusal keeps a value-free
            summary, and nothing it was given rides the error"
    (doseq [[input secret id summary-key]
            [[{:leak "session-id-cafef00d"} "session-id-cafef00d" :scxml/parse-error :input-summary]
             [foreign-scxml "logged-out" :scxml/unsupported-format :input-summary]
             [(marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0'>"
                      "<state id='patientrecordsecret42'/></scxml>")
              "patientrecordsecret42" :scxml/invalid-spec :spec-summary]]]
      (let [d (refusal input)]
        (is (= id (:rf.error/id d)))
        (is (some? (get d summary-key)) (str id))
        (is (not (str/includes? (pr-str d) secret)) (str id))))))
