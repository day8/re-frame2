(ns day8.re-frame2-machines-viz.engine-grammar-parity-test
  "ENGINE-GRAMMAR PARITY tests.

  machines-viz hand-mirrors the engine's machine-definition grammar — target
  normalisation and resolution, the `:timeout` / `:choice` desugars, and the
  recursive definition validator — because its source is bundle-isolated from
  the runtime `machines` artefact and `grammar.cljc` requires nothing. Each
  test feeds representative definitions through BOTH the viz copy and the
  engine and asserts the same output. A failure means the viz drifted from the
  engine: re-sync the copy, do not delete the test.

  Only this test requires the engine: `day8/re-frame2-machines` is a
  test-alias dependency, so the shipped jar carries none of it. Private engine
  fns are reached through their vars."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.grammar :as g]
            [re-frame.machines.choice :as rf.machines.choice]
            [re-frame.machines.lifecycle-fx.validation :as rf.machines.lifecycle-fx.validation]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.timeout :as rf.machines.timeout]
            [re-frame.machines.transition :as rf.machines.transition]))

(def engine-normalise-root-targets @#'rf.machines.parallel/normalise-root-targets)
(def engine-normalise-candidates   @#'rf.machines.transition/normalise-candidates)
(def engine-target-path            @#'rf.machines.transition/target-path)
(def viz-resolve-target-path       @#'layout/resolve-target-path)

;; ---------------------------------------------------------------------------
;; Target normalisation and resolution

(deftest normalise-root-targets-parity
  (testing "a parallel-root :target normalises to the same region-qualified
            targets on both sides"
    (doseq [target [nil                          ; targetless → []
                    [:a :two]                    ; keywords → one target, wrapped
                    [:region :nested :leaf]
                    [:r]
                    [[:a :x] [:b :y]]            ; vectors → several targets, as-is
                    [[:r1 :s] [:r2 :t] [:r3 :u]]
                    :not-a-vector
                    []]]
      (is (= (engine-normalise-root-targets target) (g/normalise-root-targets target))
          (pr-str target)))))

(deftest reenter?-parity
  (testing "only a candidate map carrying exactly `:reenter? true` restarts, as
            the engine's `(true? (:reenter? transition))` reads it"
    (is (= [true false false false false false false false false false]
           (map g/reenter? [{:target :a :reenter? true}
                            {:target :a :reenter? false}
                            {:target :a}
                            {:target :a :reenter? nil}
                            {:target :a :reenter? :truthy}
                            {:action :log}
                            {}
                            :a
                            [:a :b]
                            nil])))))

;; The viz walker and the engine normaliser agree on the transition grammar a
;; definition carries — `nil` included, since Spec 005 §Forbidden transitions
;; makes it `{}`'s runtime equivalent, a blocking chip. They differ only on a
;; mixed vector, which the engine refuses at registration and the viz explodes
;; per element; `transition-candidates-documented-divergence` pins that so a
;; change aligning or widening it is seen.

(deftest transition-candidates-parity
  (doseq [spec [:authenticated
                [:outer :inner :leaf]
                [{:guard :g1 :target :a} {:target :b :action :log}]
                [{:target :a} {:target :b} {:target :c}]
                {:target :a :guard :g}
                {:action :log}
                nil]]
    (is (= (engine-normalise-candidates spec :rf.error/test-bad-value)
           (g/transition-candidates spec))
        (pr-str spec))))

(deftest transition-candidates-documented-divergence
  (testing "mixed vector: viz mapcat-explodes, engine treats as one vec-target"
    (is (= [{:target :a} {:target [:x :y]}]
           (g/transition-candidates [:a [:x :y]])))
    (is (= [{:target [:a [:x :y]]}]
           (engine-normalise-candidates [:a [:x :y]] :rf.error/test-bad-value)))))

(def ^:private target-path-fixtures
  "[<declaring path> <target>]: `:same-state`, a keyword sibling, an absolute
  vector path, and nil (internal)."
  [[[:idle]              :same-state]
   [[:outer :inner]      :same-state]
   [[:idle]              :running]            ;; keyword sibling at top level
   [[:outer :inner]      :sibling]            ;; keyword sibling, nested
   [[:outer :inner :a]   :b]                  ;; keyword sibling, deeper
   [[:a]                 [:other :leaf]]      ;; absolute keyword vector-path
   [[:outer :inner]      [:x :y :z]]          ;; absolute keyword vector-path
   [[:idle]              nil]                 ;; internal (no :target) → nil
   [[:outer :inner]      nil]])

(deftest resolve-target-path-parity
  (testing "a chart edge lands on the node the engine transitions to"
    (doseq [[decl-path target] target-path-fixtures]
      (is (= (engine-target-path decl-path target)
             (viz-resolve-target-path decl-path target))
          (str (pr-str decl-path) " " (pr-str target))))))

;; ---------------------------------------------------------------------------
;; The `:timeout` duration and the EP-0029 desugars. Every emitter renders the
;; lowered `:after`, so a drift here re-times or re-routes what it draws
;; relative to what the engine fires.

(def ^:private duration-fixtures
  "Every resolver arm: integer ms, each ISO-8601 component (365-day year,
  30-day month), fractional seconds, case, and every reject-to-nil shape."
  [;; positive-integer literal ms
   5000 1 999999
   ;; ISO-8601 single components
   "PT5S" "PT2M" "PT1H" "P1D" "P1W" "P1M" "P1Y"
   ;; combined
   "PT1H30M" "P1DT1H1M1S"
   ;; fractional seconds (round to nearest ms)
   "PT0.5S" "PT1.5S" "PT0.25S" "PT0.001S"
   ;; case-insensitive
   "pt5s" "p1d"
   ;; non-positive / non-integer numbers → nil
   0 -5 1.5
   ;; XState shorthand → nil (a documented divergence from XState)
   "5s" "10ms" "2m"
   ;; degenerate / malformed ISO → nil
   "P" "PT" "PT0S" "P0D" "soon" ""
   ;; wrong types → nil
   nil [1000] :pt5s])

(deftest resolve-timeout-ms-parity
  (doseq [d duration-fixtures]
    (is (= (rf.machines.timeout/resolve-duration-ms d) (g/resolve-timeout-ms d))
        (pr-str d))))

(def ^:private timeout-machine-fixtures
  "A definition per `:timeout` desugar arm."
  [;; state-level timeout coexisting with :on
   {:initial :a
    :states  {:a {:timeout 1000 :on-timeout :b :on {:x :c}} :b {} :c {}}}
   ;; ISO-8601 state timeout
   {:initial :a :states {:a {:timeout "PT2S" :on-timeout :done} :done {}}}
   ;; synthetic entry merges into an existing :after (collision → explicit wins)
   {:initial :a
    :states  {:a {:after {2000 :explicit} :timeout "PT2S" :on-timeout :from-timeout}}}
   ;; spawn-level timeout anchored on the state :after
   {:initial :a
    :states  {:a {:spawn {:machine :child :timeout 500 :on-timeout :fallback}
                  :on    {:x :b}}
              :b {}}}
   ;; root-level (whole-machine) timeout
   {:initial :a :timeout 3000 :on-timeout :expired :states {:a {}}}
   ;; the root :spawn's own timeout, lowered onto the root :after
   {:type    :parallel
    :spawn   {:machine-id :child :timeout 500 :on-timeout {:target [:r1 :y]}}
    :regions {:r1 {:initial :x :states {:x {} :y {}}}}}
   ;; nested compound
   {:initial :outer
    :states  {:outer {:initial :inner
                      :states  {:inner {:timeout 1000 :on-timeout :done} :done {}}}}}
   ;; parallel regions
   {:type    :parallel
    :regions {:r1 {:initial :x :states {:x {:timeout 1000 :on-timeout :y} :y {}}}
              :r2 {:initial :p :states {:p {} :q {}}}}}
   ;; timeout-free control
   {:initial :a :states {:a {:on {:x :b}} :b {:after {500 :a}}}}])

(deftest desugar-timeouts-parity
  (doseq [m timeout-machine-fixtures]
    (is (= (rf.machines.timeout/desugar-timeouts m) (g/desugar-timeouts m))
        (pr-str m))))

(def ^:private choice-machine-fixtures
  "A definition per `:type :choice` desugar arm."
  [;; flat choice state with guarded candidates
   {:initial :gate
    :states  {:gate {:type :choice :choice [{:guard :g1 :target :a} {:target :b}]}
              :a {} :b {}}}
   ;; nested-compound + region-nested choice states together
   {:initial :outer
    :states  {:outer {:initial :gate
                      :states  {:gate {:type :choice :choice [{:target :a}]} :a {}}}}
    :regions {:r1 {:initial :rgate
                   :states  {:rgate {:type :choice :choice [{:target :a}]} :a {}}}}}
   ;; choice-free control
   {:initial :a :states {:a {:always [{:target :b}]} :b {}}}])

(deftest desugar-choices-parity
  (doseq [m choice-machine-fixtures]
    (is (= (rf.machines.choice/desugar-choices m) (g/desugar-choices m))
        (pr-str m))))

;; ---------------------------------------------------------------------------
;; Definition validation: `grammar/definition-defect` accepts and refuses what
;; the engine's `validate-machine!` does, so a definition `reg-machine` refuses
;; is refused at every viz ingestion and export boundary too. The corpus is the
;; shared structural grammar; the documented divergences are pinned in
;; `definition-validation-documented-divergences`.

(defn- engine-answer
  "`validate-machine!`'s answer for `m`: `:accept`, `:reject` (an ex-info
  carrying an `:rf.error/id`), or `:host-throw` for anything else — a third
  value, because a two-valued probe would record a host crash as a clean
  rejection."
  [m]
  (try (rf.machines.lifecycle-fx.validation/validate-machine! m) :accept
       (catch #?(:clj Throwable :cljs :default) t
         (if (:rf.error/id (ex-data t)) :reject :host-throw))))

(defn- viz-answer
  "`definition-defect`'s answer for `m` on the same scale; the viz returns its
  defect, so any throw is a `:host-throw`."
  [m]
  (try (if (nil? (g/definition-defect m)) :accept :reject)
       (catch #?(:clj Throwable :cljs :default) _t :host-throw)))

(def ^:private validation-parity-corpus
  "Definitions spanning the shared structural grammar, projectable and
  malformed."
  {;; ---- valid (both accept) ----
   :valid-flat       {:initial :idle :states {:idle {:on {:go :done}} :done {:final? true}}}
   :valid-compound   {:initial :o :states {:o {:initial :i :states {:i {:on {:up :sib}} :sib {}}} :top {}}}
   :valid-vec-target {:initial :o :states {:o {:initial :i :states {:i {:on {:esc [:top]}}}} :top {}}}
   :valid-parallel   {:type :parallel :regions {:r1 {:initial :a :states {:a {:on {:x :b}} :b {}}}
                                                :r2 {:initial :p :states {:p {}}}}}
   :valid-history    {:initial :o :states {:o {:initial :s :states {:s {:on {:g :s2}} :s2 {} :h {:type :history :deep? true}}}}}
   :valid-timeout    {:initial :a :states {:a {:timeout 1000 :on-timeout :b} :b {}}}
   :valid-choice     {:initial :g :states {:g {:type :choice :choice [{:target :a} {:target :b}]} :a {} :b {}}}
   :valid-spawn      {:initial :a :states {:a {:spawn {:machine-id :child} :on {:go :b}} :b {}}}
   ;; An inline :definition is addressed by :id-prefix or
   ;; :fixed-actor-id (the controls for `:spawn-inline-unaddressed` below).
   :valid-spawn-inline-prefix {:initial :a :states {:a {:spawn {:definition {:initial :x :states {:x {}}}
                                                                 :id-prefix  :kid}}}}
   :valid-spawn-inline-fixed  {:initial :a :states {:a {:spawn {:definition     {:initial :x :states {:x {}}}
                                                                 :fixed-actor-id :kid-1}}}}
   :valid-spawn-all  {:initial :a :states {:a {:spawn-all {:children [{:id :c1 :machine-id :m}]
                                                           :on-all-complete [:done]}
                                               :on {:go :b}} :b {}}}
   ;; A single spawn may carry a namespaced extension key and the source
   ;; metadata the `reg-machine` macro stamps.
   :valid-spawn-namespaced-source-meta {:initial :a :states {:a {:spawn {:machine-id    :m
                                                                          :my.app/note   "x"
                                                                          :source-coords {:line 1 :column 1}
                                                                          :source-code   "(reg-machine …)"}}}}
   :valid-namespaced {:initial :a :states {:a {:my.app/note "x"}}}
   :valid-tags       {:initial :a :states {:a {:tags #{:busy}}}}
   ;; A transition map may carry the closed transition keys, a namespaced
   ;; extension, and the source metadata the `reg-machine` macro stamps.
   :valid-transition-keys        {:initial :a :states {:a {:on {:go {:target :b :reenter? true :meta {:note "x"}}}} :b {}}}
   :valid-transition-namespaced  {:initial :a :states {:a {:on {:go {:target :b :my.app/note "x"}}} :b {}}}
   :valid-transition-source-meta {:initial :a :states {:a {:on {:go {:target :b :source-coords {:line 1 :column 1}
                                                                     :source-code "(reg-machine …)"}}}
                                                       :b {}}}
   ;; The machine's own blocks are legal on the root, flat or parallel.
   :valid-root-only-keys          {:initial :a :data {:n 0} :guards {} :actions {} :internal-events #{:tick}
                                   :states {:a {:on {:tick :a}}}}
   :valid-parallel-root-only-keys {:type :parallel :region-order [:r] :data {:n 0}
                                   :regions {:r {:initial :a :states {:a {}}}}}
   ;; The root runs its `:entry` at birth and its `:exit` at teardown, and
   ;; joins its `:tags` to the tag union; a parallel root also schedules its
   ;; own `:after`.
   :valid-root-entry-exit        {:initial :a :entry :hello :exit :bye
                                  :actions {:hello (fn [_ctx] nil) :bye (fn [_ctx] nil)}
                                  :states  {:a {}}}
   :valid-root-tags              {:initial :a :tags #{:busy} :states {:a {}}}
   :valid-parallel-root-lifecycle {:type    :parallel :entry (fn [_ctx] nil) :exit (fn [_ctx] nil)
                                   :tags    #{:busy}
                                   :regions {:r {:initial :a :states {:a {}}}}}
   :valid-parallel-root-after    {:type    :parallel :after {1000 {:target [:r :b]}}
                                  :regions {:r {:initial :a :states {:a {} :b {}}}}}
   ;; An `:after` delay key may be an ISO-8601 duration, as a `:timeout` may.
   :valid-after-iso      {:initial :a :states {:a {:after {"PT1S" :b}} :b {}}}
   :valid-after-iso-frac {:initial :a :states {:a {:after {"PT0.5S" :b}} :b {}}}
   ;; A spawn deadline is the spawn-level `:timeout` / `:on-timeout`.
   :valid-spawn-timeout  {:initial :a :states {:a {:spawn {:machine-id :m :timeout 500 :on-timeout :b}} :b {}}}
   :valid-timeout-iso    {:initial :a :states {:a {:timeout "PT2S" :on-timeout :b} :b {}}}
   ;; `:on-done` completes a compound, a parallel region or the parallel root.
   :valid-compound-on-done      {:initial :o :states {:o {:initial :i :on-done :b
                                                          :states  {:i {:on {:f :fin}} :fin {:final? true}}}
                                                      :b {}}}
   :valid-region-on-done        {:type :parallel :regions {:r {:initial :a :on-done :a :states {:a {}}}}}
   ;; A region's `:on-done` resolves within its region, so a keyword naming
   ;; the region's own state is valid even beside a sibling region of the
   ;; same name.
   :valid-region-on-done-vector {:type :parallel :regions {:r {:initial :a :on-done [:p :q]
                                                                :states  {:a {} :p {:initial :q :states {:q {}}}}}}}
   :valid-region-on-done-shadowing {:type    :parallel
                                    :regions {:a {:initial :a1 :on-done :b :states {:a1 {} :b {}}}
                                              :b {:initial :b1 :states {:b1 {}}}}}
   ;; A region body runs its `:entry` / `:exit` and joins its `:tags`.
   :valid-region-lifecycle      {:type    :parallel
                                 :regions {:r {:initial :a :entry (fn [_ctx] nil) :exit (fn [_ctx] nil)
                                               :tags #{:busy} :states {:a {}}}}}
   ;; A region body's `:after` that declares no delay schedules nothing.
   :valid-region-empty-after    {:type :parallel :regions {:r {:initial :a :after {} :states {:a {}}}}}
   ;; A choice state inside a region is an ordinary choice state.
   :valid-region-nested-choice  {:type    :parallel
                                 :regions {:r {:initial :g :states {:g {:type :choice :choice [{:target :a}]}
                                                                    :a {}}}}}
   :valid-parallel-root-on-done {:type    :parallel :actions {:announce (fn [_ctx] nil)} :on-done {:action :announce}
                                 :regions {:r {:initial :a :states {:a {:final? true}}}}}
   ;; A single spawn's `:on-done` is a fn folding `:data`, or a transition.
   :valid-spawn-on-done-fn      {:initial :a :states {:a {:spawn {:machine-id :m :on-done (fn [{:keys [data]}] data)}} :b {}}}
   :valid-spawn-on-done-keyword {:initial :a :states {:a {:spawn {:machine-id :m :on-done :b}} :b {}}}
   :valid-spawn-on-done-path    {:initial :a :states {:a {:spawn {:machine-id :m :on-done [:b]}} :b {}}}
   :valid-spawn-on-done-map     {:initial :a :states {:a {:spawn {:machine-id :m :on-done {:target :b :reenter? true}}} :b {}}}
   :valid-spawn-on-done-cands   {:initial :a :states {:a {:spawn {:machine-id :m :on-done [{:target :b} {:target :c}]}} :b {} :c {}}}
   ;; A single spawn's `:on-error` is a transition.
   :valid-spawn-on-error-keyword {:initial :a :states {:a {:spawn {:machine-id :m :on-error :b}} :b {}}}
   :valid-spawn-on-error-map     {:initial :a :states {:a {:spawn {:machine-id :m :on-error {:target :b}}} :b {}}}
   ;; A choice state routes to its first passing guard, ending in a default,
   ;; at any depth.
   :valid-choice-guarded         {:initial :g :guards {:ok? (fn [_ctx] true)}
                                  :states  {:g {:type :choice :choice [{:guard :ok? :target :a} {:target :b}]}
                                            :a {} :b {}}}
   :valid-compound-nested-choice {:initial :o :states {:o {:initial :g :states {:g {:type :choice :choice [{:target :a}]}
                                                                               :a {}}}}}
   ;; An `:after` holding nil declares no delay, at every position it can take.
   :valid-state-after-nil          {:initial :a :states {:a {:after nil :on {:go :b}} :b {}}}
   :valid-compound-after-nil       {:initial :o :states {:o {:initial :a :after nil :states {:a {:after nil}}}}}
   :valid-region-state-after-nil   {:type :parallel :regions {:r {:initial :a :states {:a {:after nil}}}}}
   :valid-region-body-after-nil    {:type :parallel :regions {:r {:initial :a :after nil :states {:a {}}}}}
   :valid-flat-root-after-nil      {:initial :a :after nil :states {:a {}}}
   :valid-parallel-root-after-nil  {:type :parallel :after nil :regions {:r {:initial :a :states {:a {}}}}}
   :valid-after-nil-beside-timeout {:initial :a :states {:a {:after nil :timeout 1000 :on-timeout :b} :b {}}}
   ;; ---- invalid (both reject) ----
   :nested-no-init   {:initial :outer :states {:outer {:states {:inner {}}}}}
   :unresolved-kw    {:initial :idle :states {:idle {:on {:go :missing}}}}
   :unresolved-vec   {:initial :a :states {:a {:on {:go [:nope]}}}}
   :bad-target-map   {:initial :a :states {:a {:on {:go {:target 42}}}}}
   :empty-vec-target {:initial :a :states {:a {:on {:go []}}}}
   :unknown-node-key {:initial :idle :states {:idle {:on-entry :oops}}}
   :hist-misplaced   {:initial :a :states {:a {} :hist {:type :history}}}
   :hist-extra-keys  {:initial :o :states {:o {:initial :s :states {:s {} :h {:type :history :on {:x :s}}}}}}
   :hist-duplicate   {:initial :o :states {:o {:initial :s :states {:s {} :h1 {:type :history} :h2 {:type :history}}}}}
   :hist-bad-default {:initial :o :states {:o {:initial :s :states {:s {} :h {:type :history :default-target :nope}}}}}
   :final-compound   {:initial :a :states {:a {:final? true :states {:b {}}}}}
   :final-trans      {:initial :a :states {:a {:final? true :on {:x :b}} :b {}}}
   :output-no-final  {:initial :a :states {:a {:output-key :foo}}}
   :error-no-final   {:initial :a :states {:a {:error? true}}}
   :bad-tags         {:initial :a :states {:a {:tags [:x]}}}
   :bad-after-delay  {:initial :a :states {:a {:after {0 :b}} :b {}}}
   ;; The XState shorthand and a zero-length ISO duration are not delays.
   :after-shorthand  {:initial :a :states {:a {:after {"5s" :b}} :b {}}}
   :after-iso-zero   {:initial :a :states {:a {:after {"PT0S" :b}} :b {}}}
   :spawn-neither    {:initial :a :states {:a {:spawn {}}}}
   :spawn-both       {:initial :a :states {:a {:spawn {:machine-id :m :definition {:initial :x :states {:x {}}}}}}}
   :spawn-unknown    {:initial :a :states {:a {:spawn {:machine-id :m :bogus 1}}}}
   ;; The engine refuses an UNADDRESSED inline :definition (neither
   ;; :id-prefix nor :fixed-actor-id); the viz must too.
   :spawn-inline-unaddressed {:initial :a :states {:a {:spawn {:definition {:initial :x :states {:x {}}}}}}}
   ;; A state spawns ONE child: a vector of specs, or any other non-map, is
   ;; refused. N children is `:spawn-all`.
   :spawn-vector     {:initial :a :states {:a {:spawn [{:machine-id :child}]}}}
   :spawn-keyword    {:initial :a :states {:a {:spawn :child}}}
   ;; A bare `:id` addresses a `:spawn-all` child, never a single spawn.
   :spawn-bare-id    {:initial :a :states {:a {:spawn {:machine-id :child :id :x}}}}
   :par-nested       {:type :parallel :regions {:r {:initial :a :states {:a {:type :parallel :regions {:x {:initial :y :states {:y {}}}}}}}}}
   :par-no-init      {:type :parallel :regions {:r {:states {:a {}}}}}
   :par-mutex        {:type :parallel :initial :a :regions {:r {:initial :a :states {:a {}}}}}
   :par-empty        {:type :parallel :regions {}}
   ;; ---- unknown BARE transition-map keys, in every slot and scope ----
   :transition-unknown-key         {:initial :a :states {:a {:on {:go {:target :b :targt :c}}} :b {} :c {}}}
   :transition-xstate-cond         {:initial :a :states {:a {:on {:go {:target :b :cond :ok?}}} :b {}}}
   :transition-unknown-always      {:initial :a :states {:a {:always [{:target :b :bogus 1}]} :b {}}}
   :transition-unknown-on-timeout  {:initial :a :states {:a {:timeout 1000 :on-timeout {:target :b :bogus 1}} :b {}}}
   :transition-unknown-root-on     {:initial :a :on {:x {:target :a :bogus 1}} :states {:a {}}}
   :transition-unknown-region-root {:type :parallel :regions {:r {:initial :a :on {:x {:target :a :bogus 1}} :states {:a {}}}}}
   ;; ---- root-only keys below the root ----
   :nested-root-only-data         {:initial :a :states {:a {:data {:n 0}}}}
   :nested-root-only-guards       {:initial :o :states {:o {:initial :i :states {:i {:guards {}}}}}}
   :nested-root-only-region-order {:initial :a :states {:a {:region-order [:x]}}}
   :region-root-only-data         {:type :parallel :regions {:r {:initial :a :data {:n 0} :states {:a {}}}}}
   ;; ---- `:timeout-ms` is not a spawn key ----
   :spawn-timeout-ms     {:initial :a :states {:a {:spawn {:machine-id :m :timeout-ms 500}}}}
   :spawn-all-timeout-ms {:initial :a :states {:a {:spawn-all {:children [{:id :c1 :machine-id :m}]
                                                               :on-all-complete [:done]
                                                               :timeout-ms 500}}}}
   ;; ---- a `:timeout` the desugar cannot lower without dropping it ----
   :timeout-shorthand          {:initial :a :states {:a {:timeout "5s" :on-timeout :b} :b {}}}
   :spawn-timeout-shorthand    {:initial :a :states {:a {:spawn {:machine-id :m :timeout "5s" :on-timeout :b}} :b {}}}
   :region-timeout-shorthand   {:type :parallel :regions {:r {:initial :a :timeout "5s" :on-timeout :a :states {:a {}}}}}
   :timeout-without-on-timeout {:initial :a :states {:a {:timeout 1000} :b {}}}
   :on-timeout-without-timeout {:initial :a :states {:a {:on-timeout :b} :b {}}}
   :timeout-after-collision    {:initial :a :states {:a {:after {2000 :b} :timeout "PT2S" :on-timeout :b} :b {}}}
   ;; ---- `:on-done` on a LEAF, which has no children to complete ----
   :leaf-on-done          {:initial :a :states {:a {:on-done :b} :b {}}}
   :leaf-on-done-final    {:initial :a :states {:a {:final? true :on-done :b} :b {}}}
   :leaf-on-done-spawning {:initial :a :states {:a {:spawn {:machine-id :m} :on-done :b} :b {}}}
   :leaf-on-done-timeout  {:initial :a :states {:a {:timeout 1000 :on-timeout :b :on-done :b} :b {}}}
   :leaf-on-done-region   {:type :parallel :regions {:r {:initial :a :states {:a {:on-done :b} :b {}}}}}
   ;; ---- a choice state is a leaf, held to the state-node key vocabulary ----
   :choice-on-done     {:initial :g :states {:g {:type :choice :choice [{:target :a}] :on-done :a} :a {}}}
   :choice-unknown-key {:initial :g :states {:g {:type :choice :choice [{:target :a}] :bogus 1} :a {}}}
   ;; ---- a transition-shaped `:spawn :on-done` that does not resolve ----
   :spawn-on-done-unresolved     {:initial :a :states {:a {:spawn {:machine-id :m :on-done :nope}} :b {}}}
   :spawn-on-done-unresolved-map {:initial :a :states {:a {:spawn {:machine-id :m :on-done {:target [:nope]}}} :b {}}}
   :spawn-on-done-bad-target     {:initial :a :states {:a {:spawn {:machine-id :m :on-done {:target 42}}} :b {}}}
   :spawn-on-done-unknown-key    {:initial :a :states {:a {:spawn {:machine-id :m :on-done {:target :b :cond :ok?}}} :b {}}}
   ;; ---- a `:spawn :on-error` / `:on-done` value that is neither a transition
   ;; nor, for `:on-done`, a fn ----
   :spawn-on-error-nil          {:initial :a :states {:a {:spawn {:machine-id :m :on-error nil}} :b {}}}
   :spawn-on-error-number       {:initial :a :states {:a {:spawn {:machine-id :m :on-error 42}} :b {}}}
   :spawn-on-error-string       {:initial :a :states {:a {:spawn {:machine-id :m :on-error "b"}} :b {}}}
   :spawn-on-error-fn           {:initial :a :states {:a {:spawn {:machine-id :m :on-error (fn [_ctx] nil)}} :b {}}}
   :spawn-on-error-empty-vector {:initial :a :states {:a {:spawn {:machine-id :m :on-error []}} :b {}}}
   :spawn-on-done-nil           {:initial :a :states {:a {:spawn {:machine-id :m :on-done nil}} :b {}}}
   :spawn-on-done-number        {:initial :a :states {:a {:spawn {:machine-id :m :on-done 42}} :b {}}}
   :spawn-on-done-string        {:initial :a :states {:a {:spawn {:machine-id :m :on-done "b"}} :b {}}}
   :spawn-on-done-empty-vector  {:initial :a :states {:a {:spawn {:machine-id :m :on-done []}} :b {}}}
   ;; ---- a machine-root key the runtime never reads on the root ----
   :root-final          {:initial :a :final? true :states {:a {}}}
   :root-flat-on-done   {:initial :a :on-done :a :states {:a {}}}
   :root-two-slots      {:initial :a :always {:target :a} :final? true :states {:a {}}}
   ;; ---- the machine root's own `:spawn`, held to a state's spawn grammar ----
   :root-spawn                    {:initial :a :spawn {:machine-id :m} :states {:a {}}}
   :root-spawn-completions        {:initial :a :spawn {:machine-id :m :on-done :b :on-error [:b]} :states {:a {} :b {}}}
   :parallel-root-spawn           {:type :parallel :spawn {:machine-id :m} :regions {:r {:initial :a :states {:a {}}}}}
   :parallel-root-spawn-on-error  {:type :parallel :spawn {:machine-id :m :on-error {:target [:r :b]}}
                                   :regions {:r {:initial :a :states {:a {} :b {}}}}}
   :parallel-root-spawn-timeout   {:type :parallel :spawn {:machine-id :m :timeout 1000 :on-timeout {:target [:r :b]}}
                                   :regions {:r {:initial :a :states {:a {} :b {}}}}}
   :root-spawn-vector             {:initial :a :spawn [{:machine-id :m}] :states {:a {}}}
   :parallel-root-spawn-bare-id   {:type :parallel :spawn {:machine-id :m :id :x} :regions {:r {:initial :a :states {:a {}}}}}
   :root-spawn-on-error-number    {:initial :a :spawn {:machine-id :m :on-error 42} :states {:a {}}}
   :parallel-root-spawn-on-done-number {:type :parallel :spawn {:machine-id :m :on-done 42}
                                        :regions {:r {:initial :a :states {:a {}}}}}
   :root-spawn-on-done-unresolved {:initial :a :spawn {:machine-id :m :on-done :nope} :states {:a {}}}
   :root-spawn-timeout-ms         {:initial :a :spawn {:machine-id :m :timeout-ms 1000} :states {:a {}}}
   ;; A parallel root's `:tags` is a set of keywords, as a flat root's is.
   :parallel-root-bad-tags {:type :parallel :tags [:busy] :regions {:r {:initial :a :states {:a {}}}}}
   ;; ---- a region body's own `:on-done` / `:on` resolve within its region ----
   :region-on-done-sibling        {:type    :parallel
                                   :regions {:a {:initial :x :on-done :b :states {:x {} :done {:final? true}}}
                                             :b {:initial :y :states {:y {}}}}}
   :region-on-done-sibling-vector {:type    :parallel
                                   :regions {:a {:initial :x :on-done [:b :y] :states {:x {} :done {:final? true}}}
                                             :b {:initial :y :states {:y {}}}}}
   :region-on-done-missing        {:type :parallel :regions {:r {:initial :a :on-done :nowhere
                                                                  :states  {:a {} :done {:final? true}}}}}
   :region-on-done-bad-target     {:type :parallel :regions {:r {:initial :a :on-done {:target 42}
                                                                  :states  {:a {} :done {:final? true}}}}}
   :region-root-on-sibling        {:type    :parallel
                                   :regions {:a {:initial :x :on {:go :b} :states {:x {}}}
                                             :b {:initial :y :states {:y {}}}}}
   ;; ---- a region-body key the runtime never reads on a region body ----
   :region-body-final     {:type :parallel :regions {:r {:initial :a :final? true :states {:a {}}}}}
   :region-body-spawn     {:type :parallel :regions {:r {:initial :a :spawn {:machine-id :m} :states {:a {}}}}}
   :region-body-two-slots {:type :parallel :regions {:r {:initial :a :final? true :spawn {:machine-id :m}
                                                         :states  {:a {}}}}}
   ;; ---- a region body's own `:after`, and a region body as a choice state ----
   :region-body-after              {:type :parallel :regions {:r {:initial :a :after {1000 :a} :states {:a {}}}}}
   :region-body-timeout            {:type :parallel :regions {:r {:initial :a :timeout 1000 :on-timeout :a
                                                                  :states  {:a {}}}}}
   :region-body-after-and-final    {:type :parallel :regions {:r {:initial :a :after {1000 :a} :final? true
                                                                  :states  {:a {}}}}}
   :region-body-choice-without-type {:type :parallel :regions {:r {:initial :a :choice [{:target :a}]
                                                                   :states  {:a {}}}}}
   :region-body-choice-missing     {:type :parallel :regions {:r {:initial :a :type :choice :states {:a {}}}}}
   :region-body-choice             {:type :parallel :regions {:r {:initial :a :type :choice :choice [{:target :a}]
                                                                  :states  {:a {}}}}}
   :region-body-choice-malformed   {:type :parallel :regions {:r {:initial :a :type :choice :choice :a
                                                                  :states  {:a {}}}}}
   :region-body-choice-no-default  {:type :parallel :regions {:r {:type :choice :choice [{:guard :g :target :a}]}}}
   :region-body-choice-self-loop   {:type :parallel :regions {:r {:type :choice :choice [{:target :r}]}}}
   ;; ---- an ordinary state's `:type :choice` / `:choice`, at any depth ----
   :choice-without-type              {:initial :a :states {:a {:choice [{:target :b}]} :b {}}}
   :choice-missing-choice            {:initial :g :states {:g {:type :choice} :a {}}}
   :choice-malformed                 {:initial :g :states {:g {:type :choice :choice :a} :a {}}}
   :choice-empty                     {:initial :g :states {:g {:type :choice :choice []} :a {}}}
   :choice-extra-keys                {:initial :g :states {:g {:type :choice :choice [{:target :a}] :entry :announce} :a {}}}
   :choice-no-default                {:initial :g :guards {:ok? (fn [_ctx] true)}
                                      :states  {:g {:type :choice :choice [{:guard :ok? :target :a}]} :a {}}}
   :choice-self-loop                 {:initial :g :states {:g {:type :choice :choice [{:target :g}]} :a {}}}
   :choice-self-loop-path            {:initial :o :states {:o {:initial :g :states {:g {:type :choice :choice [{:target [:o :g]}]}
                                                                                  :a {}}}}}
   :compound-choice-without-type     {:initial :o :states {:o {:initial :a :states {:a {:choice [{:target :b}]} :b {}}}}}
   :compound-choice-no-default       {:initial :o :guards {:ok? (fn [_ctx] true)}
                                      :states  {:o {:initial :g :states {:g {:type :choice :choice [{:guard :ok? :target :a}]}
                                                                         :a {}}}}}
   :compound-as-choice               {:initial :o :states {:o {:type :choice :choice [{:target :b}] :initial :a :states {:a {}}}
                                                           :b {}}}
   :region-state-choice-without-type {:type :parallel :regions {:r {:initial :a :states {:a {:choice [{:target :b}]} :b {}}}}}
   :region-state-choice-self-loop    {:type :parallel :regions {:r {:initial :g :states {:g {:type :choice :choice [{:target [:g]}]}
                                                                                         :a {}}}}}
   ;; ---- an `:after` holding nil is still a declared `:after` key ----
   :final-after-nil  {:initial :a :states {:a {:final? true :after nil}}}
   :choice-after-nil {:initial :g :states {:g {:type :choice :choice [{:target :a}] :after nil} :a {}}}
   ;; ---- non-Named KEYS, which a definition decoded from transit or a share
   ;; URL carries as readily as keywords; `namespace` on one would THROW ----
   :root-string-key  {:initial :a :states {:a {}} "x" 1}
   :root-number-key  {:initial :a :states {:a {}} 7 1}
   :node-string-key  {:initial :a :states {:a {"x" 1}}}
   :node-vector-key  {:initial :a :states {:a {[1 2] 1}}}
   :nested-string-key {:initial :o :states {:o {:initial :i :states {:i {"x" 1}}}}}})

;; Each corpus definition gets the engine's answer from the viz on the raw
;; definition AND on the desugared one every boundary validates — a desugar
;; that dropped what it could not lower would turn a refusal into an
;; acceptance there. Neither side may answer with a host throw: two validators
;; that both explode on a non-`Named` key would otherwise agree.

(deftest definition-validation-parity
  (doseq [[label m] validation-parity-corpus
          :let [engine (engine-answer m)]]
    (is (not= :host-throw engine)
        (str label ": the engine threw a host exception, not a structured rejection"))
    (is (= engine (viz-answer m) (viz-answer (g/desugar-grammar m)))
        (str label ": the viz answer, raw then desugared, drifted from the engine's"))))

;; A refusal also carries the engine's own CATEGORY, which every export surface
;; reports in its value-free summary, so these rows pin it on both sides.

(defn- engine-refusal
  "The ex-data `validate-machine!` refuses `m` with, or nil."
  [m]
  (try (rf.machines.lifecycle-fx.validation/validate-machine! m) nil
       (catch #?(:clj Throwable :cljs :default) t (ex-data t))))

(defn- categories
  "The engine's refusal category for `m`, then the viz's on `m` and on the
  desugared `m` every boundary validates."
  [m]
  [(:rf.error/id (engine-refusal m))
   (:category (g/definition-defect m))
   (:category (g/definition-defect (g/desugar-grammar m)))])

(def ^:private refusal-categories
  "Corpus labels → the category the engine refuses each with."
  {;; a non-map `:spawn`, and a bare `:id`, which addresses a `:spawn-all` child
   :spawn-vector  :rf.error/machine-spawn-bad-shape
   :spawn-keyword :rf.error/machine-spawn-bad-shape
   :spawn-bare-id :rf.error/machine-unknown-spawn-key
   ;; a leaf's `:on-done`, and a transition-shaped `:spawn :on-done`
   :leaf-on-done                  :rf.error/machine-unknown-node-key
   :leaf-on-done-final            :rf.error/machine-unknown-node-key
   :leaf-on-done-spawning         :rf.error/machine-unknown-node-key
   :leaf-on-done-timeout          :rf.error/machine-unknown-node-key
   :leaf-on-done-region           :rf.error/machine-unknown-node-key
   :spawn-on-done-unresolved      :rf.error/machine-unresolved-target
   :spawn-on-done-unresolved-map  :rf.error/machine-unresolved-target
   :spawn-on-done-bad-target      :rf.error/machine-bad-target
   :spawn-on-done-unknown-key     :rf.error/machine-unknown-node-key
   ;; a choice state is a leaf held to the state-node key vocabulary
   :choice-on-done     :rf.error/machine-unknown-node-key
   :choice-unknown-key :rf.error/machine-unknown-node-key
   ;; a `:spawn :on-error` / `:on-done` that is not a transition (or a fn)
   :spawn-on-error-nil          :rf.error/machine-bad-on-error-clause
   :spawn-on-error-number       :rf.error/machine-bad-on-error-clause
   :spawn-on-error-string       :rf.error/machine-bad-on-error-clause
   :spawn-on-error-fn           :rf.error/machine-bad-on-error-clause
   :spawn-on-error-empty-vector :rf.error/machine-bad-on-error-clause
   :spawn-on-done-nil           :rf.error/machine-bad-on-done-clause
   :spawn-on-done-number        :rf.error/machine-bad-on-done-clause
   :spawn-on-done-string        :rf.error/machine-bad-on-done-clause
   :spawn-on-done-empty-vector  :rf.error/machine-bad-on-done-clause
   ;; a machine-root key the runtime never reads there
   :root-final             :rf.error/machine-root-slot-not-supported
   :root-flat-on-done      :rf.error/machine-root-slot-not-supported
   :root-two-slots         :rf.error/machine-root-slot-not-supported
   :parallel-root-bad-tags :rf.error/machine-bad-tags
   ;; the machine root's own `:spawn`, held to a state's spawn grammar
   :root-spawn-vector                  :rf.error/machine-spawn-bad-shape
   :parallel-root-spawn-bare-id        :rf.error/machine-unknown-spawn-key
   :root-spawn-on-error-number         :rf.error/machine-bad-on-error-clause
   :parallel-root-spawn-on-done-number :rf.error/machine-bad-on-done-clause
   :root-spawn-on-done-unresolved      :rf.error/machine-unresolved-target
   :root-spawn-timeout-ms              :rf.error/spawn-timeout-ms-removed
   ;; `:after nil` is absent, but still a declared key on a final or choice state
   :final-after-nil  :rf.error/machine-final-state-has-transitions
   :choice-after-nil :rf.error/machine-choice-extra-keys})

(deftest refusal-category-parity
  (doseq [[label category] refusal-categories]
    (is (= [category category category]
           (categories (get validation-parity-corpus label)))
        (str label))))

;; The refused root and region-body keys are read off the engine, so a key it
;; starts refusing there is a red row until the viz refuses it too.

(def ^:private engine-root-unread-keys      @#'rf.machines.lifecycle-fx.validation/root-unread-keys)
(def ^:private engine-flat-root-unread-keys @#'rf.machines.lifecycle-fx.validation/flat-root-unread-keys)
(def ^:private engine-region-unread-keys    @#'rf.machines.lifecycle-fx.validation/region-unread-keys)

(defn- flat-root-with [k] {:initial :a k nil :states {:a {}}})
;; `:regions` is itself one of the keys only a flat root refuses, so the
;; parallel root's own regions are merged over the probed key.
(defn- parallel-root-with [k] (merge {k nil} {:type :parallel :regions {:r {:initial :a :states {:a {}}}}}))
(defn- region-body-with [k] {:type :parallel :regions {:r {:initial :a k nil :states {:a {}}}}})

(def ^:private slot-not-supported
  (vec (repeat 3 :rf.error/machine-root-slot-not-supported)))

(deftest root-slot-refusal-parity
  (testing "every key the engine refuses on a root, the viz refuses there,
            naming the same keys"
    (doseq [[root-kind k m] (concat
                              (for [k (sort (into engine-root-unread-keys engine-flat-root-unread-keys))]
                                [:flat k (flat-root-with k)])
                              (for [k (sort engine-root-unread-keys)]
                                [:parallel k (parallel-root-with k)])
                              [[:flat :two-keys (get validation-parity-corpus :root-two-slots)]])]
      (is (= slot-not-supported (categories m)) (str root-kind " root " k))
      (is (= (:offending-keys (engine-refusal m)) (:keys (g/definition-defect m)))
          (str root-kind " root " k ": the offending keys"))))
  (testing "a parallel root reads the keys only a flat root refuses"
    (doseq [k (sort engine-flat-root-unread-keys)
            :let [m (parallel-root-with k)]]
      (is (= :accept (engine-answer m) (viz-answer m)) (str "parallel root " k)))))

(deftest region-slot-refusal-parity
  (testing "every key the engine refuses on a region body, the viz refuses there,
            naming the same keys under the same path"
    (doseq [[k m] (concat (for [k (sort engine-region-unread-keys)]
                            [k (region-body-with k)])
                          [[:two-keys (get validation-parity-corpus :region-body-two-slots)]])
            :let [refusal (engine-refusal m)
                  defect  (g/definition-defect m)]]
      (is (= slot-not-supported (categories m)) (str "region body " k))
      (is (= [(:offending-keys refusal) (:path refusal)] [(:keys defect) (:path defect)])
          (str "region body " k ": the offending keys and path")))))

;; A region body's own `:on` and `:on-done` resolve within its region, and the
;; viz names the slot the engine names.

(def ^:private region-refusal-rows
  "Corpus labels → the category the engine refuses each region body with."
  {:region-on-done-sibling        :rf.error/machine-unresolved-target
   :region-on-done-sibling-vector :rf.error/machine-unresolved-target
   :region-on-done-missing        :rf.error/machine-unresolved-target
   :region-on-done-bad-target     :rf.error/machine-bad-target
   :region-root-on-sibling        :rf.error/machine-unresolved-target
   :region-body-final             :rf.error/machine-root-slot-not-supported
   :region-body-spawn             :rf.error/machine-root-slot-not-supported})

(deftest region-refusal-category-parity
  (doseq [[label category] region-refusal-rows
          :let [m (get validation-parity-corpus label)]]
    (is (= [category category category] (categories m)) (str label))
    (is (= (:slot (engine-refusal m)) (:slot (g/definition-defect m)))
        (str label ": the slot"))))

;; A region body's own `:after` never fires, and a region body is never a
;; choice state, so the engine refuses both. It names the region under
;; `:region` / `:state` rather than a `:path`; the viz names the same region at
;; the region-body path its unread-key refusal carries.

(def ^:private region-body-after-choice-rows
  "Corpus labels → the category the engine refuses each region body with."
  {:region-body-after               :rf.error/machine-non-parallel-root-after-not-supported
   :region-body-timeout             :rf.error/machine-non-parallel-root-after-not-supported
   :region-body-after-and-final     :rf.error/machine-non-parallel-root-after-not-supported
   :region-body-choice-without-type :rf.error/machine-choice-without-type
   :region-body-choice-missing      :rf.error/machine-choice-missing-choice
   :region-body-choice              :rf.error/machine-choice-extra-keys
   :region-body-choice-malformed    :rf.error/machine-bad-choice
   :region-body-choice-no-default   :rf.error/machine-choice-no-default
   :region-body-choice-self-loop    :rf.error/machine-choice-self-loop})

(deftest region-body-after-and-choice-refusal-parity
  (doseq [[label category] region-body-after-choice-rows
          :let [m       (get validation-parity-corpus label)
                refusal (engine-refusal m)]]
    (is (= [category category category] (categories m)) (str label))
    (is (= [:regions (or (:region refusal) (:state refusal))] (:path (g/definition-defect m)))
        (str label ": the region"))))

;; Every state node is held to the choice grammar, at any depth, before
;; anything is lowered. The engine names the declaring state under `:state`;
;; the viz names its path, whose last key is that state.

(def ^:private state-choice-refusal-rows
  "Corpus labels → the category the engine refuses each state's choice with."
  {:choice-without-type              :rf.error/machine-choice-without-type
   :choice-missing-choice            :rf.error/machine-choice-missing-choice
   :choice-malformed                 :rf.error/machine-bad-choice
   :choice-empty                     :rf.error/machine-bad-choice
   :choice-extra-keys                :rf.error/machine-choice-extra-keys
   :choice-no-default                :rf.error/machine-choice-no-default
   :choice-self-loop                 :rf.error/machine-choice-self-loop
   :choice-self-loop-path            :rf.error/machine-choice-self-loop
   :compound-choice-without-type     :rf.error/machine-choice-without-type
   :compound-choice-no-default       :rf.error/machine-choice-no-default
   :compound-as-choice               :rf.error/machine-choice-extra-keys
   :region-state-choice-without-type :rf.error/machine-choice-without-type
   :region-state-choice-self-loop    :rf.error/machine-choice-self-loop})

(deftest state-choice-refusal-parity
  (doseq [[label category] state-choice-refusal-rows
          :let [m (get validation-parity-corpus label)]]
    (is (= [category category category] (categories m)) (str label))
    (is (= (:state (engine-refusal m)) (peek (:path (g/definition-defect m))))
        (str label ": the state"))))

;; An `:on` / `:after` clause is a map or nil at every position it can take. nil
;; is absent on both sides; any other value is refused on both with the slot's
;; category before anything iterates it, a `:timeout` beside a malformed
;; `:after` included.

(def ^:private clause-positions
  "Position → a machine declaring `clause` in `slot` there, and the map clause
  that registers in that slot there. A flat root's and a region body's `:after`
  never fire, so the only map they take is the empty one."
  {:leaf          [(fn [slot clause] {:initial :a :states {:a {slot clause} :b {}}})
                   {:on {:go :b} :after {1000 :b}}]
   :compound      [(fn [slot clause] {:initial :o
                                      :states  {:o {:initial :a slot clause :states {:a {}}} :b {}}})
                   {:on {:go :b} :after {1000 :b}}]
   :region-state  [(fn [slot clause] {:type    :parallel
                                      :regions {:r {:initial :a :states {:a {slot clause} :b {}}}}})
                   {:on {:go :b} :after {1000 :b}}]
   :region-body   [(fn [slot clause] {:type    :parallel
                                      :regions {:r {:initial :a slot clause :states {:a {} :b {}}}}})
                   {:on {:go :b} :after {}}]
   :flat-root     [(fn [slot clause] {:initial :a slot clause :states {:a {} :b {}}})
                   {:on {:go :b} :after {}}]
   :parallel-root [(fn [slot clause] {:type    :parallel slot clause
                                      :regions {:r {:initial :a :states {:a {} :b {}}}}})
                   {:on {:go [:r :b]} :after {1000 {:target [:r :b]}}}]})

(def ^:private clause-categories
  {:on    :rf.error/machine-bad-on-clause
   :after :rf.error/machine-bad-after-spec})

(def ^:private malformed-after-beside-timeout
  "Label → a definition whose `:after` is malformed beside a `:timeout` that
  would lower into it."
  {:state-timeout       {:initial :a :states {:a {:after :x :timeout 100 :on-timeout :b} :b {}}}
   :state-timeout-pair  {:initial :a :states {:a {:after [1000 :b] :timeout 100 :on-timeout :b} :b {}}}
   :spawn-timeout       {:initial :a :states {:a {:after :x :spawn {:machine-id :m :timeout 100 :on-timeout :b}}
                                              :b {}}}
   :parallel-root-timeout {:type    :parallel :after #{1} :timeout 100 :on-timeout {:target [:r :b]}
                           :regions {:r {:initial :a :states {:a {} :b {}}}}}})

(deftest clause-slot-parity
  (doseq [slot                        [:on :after]
          [position [make map-clause]] clause-positions]
    (testing (str slot " on the " position)
      (doseq [clause [nil (get map-clause slot)]
              :let [m (make slot clause)]]
        (is (= :accept (engine-answer m) (viz-answer m) (viz-answer (g/desugar-grammar m)))
            (str (pr-str clause) ": registers on both sides, raw and desugared")))
      (doseq [clause [:b [:b] [1000 :b] 42 "b" #{:b} (fn [_] nil)]
              :let [category (clause-categories slot)]]
        (is (= [category category category] (categories (make slot clause)))
            (str (pr-str clause) ": refused with the slot's category")))))
  (testing "a malformed :after beside a :timeout is refused as malformed, not lowered"
    (doseq [[label m] malformed-after-beside-timeout]
      (is (= (vec (repeat 3 :rf.error/machine-bad-after-spec)) (categories m))
          (str label)))))

(deftest definition-validation-documented-divergences
  (doseq [[why m answers]
          [["the engine resolves guard / action refs against its registry; the viz
             has none, and refs are runtime wiring rather than topology"
            {:initial :a :states {:a {:on {:go {:target :b :guard :missing?}}} :b {}}}
            [:reject :accept]]
           ["the engine cannot schedule a NON-parallel root :after; the viz
             projects it as a machine-root anchor"
            {:initial :a :after {1000 :b} :states {:a {} :b {}}}
            [:reject :accept]]
           ["the engine refuses a bare-keyword parallel-root :spawn target; the viz
             does not validate the parallel-root target grammar"
            {:type :parallel :spawn {:machine-id :m :on-error :b}
             :regions {:r {:initial :a :states {:a {} :b {}}}}}
            [:reject :accept]]
           ["the engine resolves a missing root :initial lazily; the viz needs one
             to draw an initial marker"
            {:states {:idle {}}}
            [:accept :reject]]]]
    (is (= answers [(engine-answer m) (viz-answer m)]) why)))
