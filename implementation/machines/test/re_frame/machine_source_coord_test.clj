(ns re-frame.machine-source-coord-test
  "Per-element source-coord stamping for machine specs. Per Spec 005
  §Source-coord stamping (rf2-8bp3) — the `reg-machine` macro walks the
  literal spec form at expansion time and attaches a flat coord index
  under `:rf.machine/source-coords`, keyed by spec-path tuples.

  Definition sites: each fn literal under `:guards` / `:actions` /
  `:on-spawn-actions` is keyed by `[:guards :id]` / `[:actions :id]` /
  `[:on-spawn-actions :id]`.

  Reference sites: each transition / state-node inside the `:states`
  tree is keyed by its full spec path, e.g.
  `[:states :idle :on :submit :guard]`.

  Per the bead's exemption case (keyword reference vs definition for
  `:guard :form-valid?`-style indirection): the rule is **definition-site
  only** for keyword references, **reference-site only** for inline-fn
  literals. Rationale: a keyword (`:form-valid?`) is a name, not a
  source form — it carries no reader metadata of its own. The closest
  meaningful coord is the enclosing transition map's coord, which is
  *already stamped* under the transition's path
  (`[:states :idle :on :submit]`). Synthesising a duplicate slot entry
  (`[:states :idle :on :submit :guard]`) at the same coord adds no
  information for tools — they walk the path tree to find the closest
  ancestor coord. Inline-fn references (`:guard (fn [_ _] ...)`) carry
  their own reader metadata, so the reference site gets a distinct
  coord and IS stamped.

  This test runs on JVM only because the source-coord-walking macro is
  Clojure-side. CLJS tests in machine_source_coord_cljs_test.cljs cover
  the same surface end-to-end through the macroexpansion the cljs
  compiler performs on .clj/.cljc macros.

  Reader-meta limitation on JVM: the standard Clojure `LispReader` only
  attaches `:line` / `:column` metadata to *list* forms (fn-bodies) —
  not to map or vector literals. So on JVM, the walker stamps
  definition-site fn literals (under `:guards` / `:actions` /
  `:on-spawn-actions`) reliably; state-node and transition-map coords
  are not available on JVM because the source forms don't carry the
  reader meta the walker reads. The CLJS reader (cljs.tools.reader)
  enriches maps/vectors, so the CLJS counterpart test exercises the
  full path-tuple surface."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

;; Helper: read the per-element index off a registered machine.
(defn- per-element-coords [machine-id]
  (-> (rf/machine-meta machine-id)
      :rf.machine/source-coords))

;; ---- top-level call-site coords (smoke; covered also in core/source-coords-test) ----

(deftest reg-machine-stamps-call-site-coords
  (testing "the reg-machine macro stamps :ns / :line / :file / :column on the registry slot
  so handler-meta carries the call-site coords (rf2-k84s + rf2-8bp3)"
    (rf/reg-machine :rf2-8bp3/call-site-sample
      {:initial :a :states {:a {} :b {}}})
    (let [meta (rf/handler-meta :event :rf2-8bp3/call-site-sample)]
      (is (some? meta))
      (is (= 're-frame.machine-source-coord-test (:ns meta)))
      (is (integer? (:line meta)))
      (is (integer? (:column meta)))
      (is (string? (:file meta))))))

;; ---- definition-site stamping for :guards / :actions / :on-spawn-actions --

(deftest reg-machine-stamps-guard-definitions
  (testing "each fn literal under :guards is stamped with the source-coord at the
  fn-form's reader position; key in the index is [:guards <id>]"
    (rf/reg-machine :rf2-8bp3/guard-defs
      {:initial :idle
       :data    {}
       :guards  {:always-true (fn [_ _] true)
                 :n-positive? (fn [data _] (pos? (or (:n data) 0)))}
       :states  {:idle {}}})
    (let [idx (per-element-coords :rf2-8bp3/guard-defs)]
      (is (some? idx) "the spec carries :rf.machine/source-coords")
      (is (some? (get idx [:guards :always-true]))
          "the :always-true guard fn-form is keyed by [:guards :always-true]")
      (is (some? (get idx [:guards :n-positive?]))
          "the :n-positive? guard fn-form is keyed by [:guards :n-positive?]")
      (let [c (get idx [:guards :always-true])]
        (is (= 're-frame.machine-source-coord-test (:ns c)))
        (is (integer? (:line c)))
        (is (integer? (:column c)))))))

(deftest reg-machine-stamps-action-definitions
  (testing "each fn literal under :actions is stamped, keyed by [:actions <id>]"
    (rf/reg-machine :rf2-8bp3/action-defs
      {:initial :idle
       :data    {}
       :actions {:bump   (fn [data _] {:data (update data :n (fnil inc 0))})
                 :reset  (fn [data _] {:data (assoc data :n 0)})}
       :states  {:idle {}}})
    (let [idx (per-element-coords :rf2-8bp3/action-defs)]
      (is (some? (get idx [:actions :bump])))
      (is (some? (get idx [:actions :reset])))
      (let [c (get idx [:actions :bump])]
        (is (= 're-frame.machine-source-coord-test (:ns c)))
        (is (integer? (:line c)))))))

(deftest reg-machine-stamps-on-spawn-action-definitions
  (testing "each fn literal under :on-spawn-actions is stamped, keyed by
  [:on-spawn-actions <id>]"
    (rf/reg-machine :rf2-8bp3/on-spawn-defs
      {:initial :idle
       :data    {}
       :on-spawn-actions {:capture-id (fn [data id] (assoc data :pending id))}
       :states  {:idle {}}})
    (let [idx (per-element-coords :rf2-8bp3/on-spawn-defs)]
      (is (some? (get idx [:on-spawn-actions :capture-id]))
          "the :capture-id on-spawn-action fn-form is keyed by
          [:on-spawn-actions :capture-id]"))))

;; ---- reference-site stamping inside the :states tree ----------------------

(deftest reg-machine-stamps-on-transition-keyword-references-via-definition
  (testing "On JVM the LispReader doesn't attach line/column meta to map
  literals, so transition-map reference-site coords aren't available
  here. The definition coords for the named guard / action ARE present
  (fn-forms are lists, which the reader does decorate)."
    (rf/reg-machine :rf2-8bp3/on-refs
      {:initial :idle
       :data    {}
       :guards  {:ok? (fn [_ _] true)}
       :actions {:do  (fn [_ _] {})}
       :states
       {:idle
        {:on
         {:submit {:target :done :guard :ok? :action :do}}}
        :done {}}})
    (let [idx (per-element-coords :rf2-8bp3/on-refs)]
      ;; Definition sites are stamped (they carry the fn-literal's meta).
      (is (some? (get idx [:guards :ok?]))
          "definition site is stamped for keyword references")
      (is (some? (get idx [:actions :do]))))))

(deftest reg-machine-stamps-inline-fn-references
  (testing "an inline fn literal in :guard / :action / :entry slot DOES get
  stamped at the reference site, since the fn-form is a list (which the
  Clojure LispReader does decorate with line/column meta) — distinct from
  any enclosing transition's coord. Inline-fn references work on JVM
  even though their enclosing map literals don't."
    (rf/reg-machine :rf2-8bp3/inline-refs
      {:initial :idle
       :data    {}
       :states
       {:idle
        {:entry (fn [_ _] {})
         :on    {:submit {:target :done
                          :guard  (fn [_ _] true)
                          :action (fn [_ _] {})}}}
        :done {}}})
    (let [idx (per-element-coords :rf2-8bp3/inline-refs)]
      (is (some? (get idx [:states :idle :entry]))
          "inline-fn :entry literal is stamped at the slot path")
      (is (some? (get idx [:states :idle :on :submit :guard]))
          "inline-fn :guard literal is stamped at the slot path")
      (is (some? (get idx [:states :idle :on :submit :action]))
          "inline-fn :action literal is stamped at the slot path"))))

(deftest reg-machine-stamps-vector-of-transitions
  (testing "an :on entry whose value is a vector of guarded-transition maps:
  on JVM the transition maps don't carry reader meta (LispReader limitation)
  but the named guards' definition coords ARE stamped (they're fn lists,
  which the reader does decorate)."
    (rf/reg-machine :rf2-8bp3/on-vec
      {:initial :idle
       :data    {}
       :guards  {:a? (fn [_ _] true)
                 :b? (fn [_ _] false)}
       :states
       {:idle
        {:on
         {:tick [{:guard :a? :target :one}
                 {:guard :b? :target :two}
                 {:target :three}]}}
        :one   {}
        :two   {}
        :three {}}})
    (let [idx (per-element-coords :rf2-8bp3/on-vec)]
      ;; Definition coords are stamped.
      (is (some? (get idx [:guards :a?])))
      (is (some? (get idx [:guards :b?]))))))

(deftest reg-machine-stamps-entry-and-exit-via-definition
  (testing "keyword :entry / :exit references resolve to their definition
  coords under [:actions <id>] — on JVM the slot itself isn't stamped (the
  keyword carries no meta) and the enclosing state-node map literal also
  doesn't (LispReader doesn't decorate maps), but the action fn-forms ARE
  stamped at their definition sites under [:actions <id>] which is what
  tools need to find the implementation."
    (rf/reg-machine :rf2-8bp3/entry-exit
      {:initial :a
       :data    {}
       :actions {:enter-a (fn [_ _] {})
                 :exit-a  (fn [_ _] {})}
       :states
       {:a {:entry :enter-a
            :exit  :exit-a
            :on    {:go :b}}
        :b {}}})
    (let [idx (per-element-coords :rf2-8bp3/entry-exit)]
      ;; Definition coords for the named actions present.
      (is (some? (get idx [:actions :enter-a])))
      (is (some? (get idx [:actions :exit-a]))))))

(deftest reg-machine-stamps-always-via-definition
  (testing ":always transitions reference named guards by keyword. On JVM
  the transition map itself doesn't carry reader meta, but the guard's
  fn-form definition coord IS stamped under [:guards <id>] — that's
  what tools navigate to."
    (rf/reg-machine :rf2-8bp3/always
      {:initial :a
       :data    {}
       :guards  {:enough? (fn [_ _] true)}
       :states
       {:a {:always [{:guard :enough? :target :b}]}
        :b {}}})
    (let [idx (per-element-coords :rf2-8bp3/always)]
      (is (some? (get idx [:guards :enough?]))))))

(deftest reg-machine-stamps-invoke-on-spawn-via-definition
  (testing ":spawn {:on-spawn :id}: keyword references resolve through
  the [:on-spawn-actions <id>] definition coord, where the fn-form lives"
    (rf/reg-machine :rf2-8bp3/invoke-os
      {:initial :idle
       :data    {}
       :on-spawn-actions {:cap (fn [data id] (assoc data :pending id))}
       :states
       {:idle {:spawn {:machine-id :child :on-spawn :cap}}}})
    (let [idx (per-element-coords :rf2-8bp3/invoke-os)]
      ;; Definition coord present.
      (is (some? (get idx [:on-spawn-actions :cap]))))))

(deftest reg-machine-stamps-hierarchical-via-definition
  (testing "nested :states recurse — on JVM, state-node maps carry no
  reader meta so direct path-tuple lookups for state nodes are absent.
  Definition coords for inline-fn references inside hierarchical states
  ARE stamped, since fn-forms always carry meta."
    (rf/reg-machine :rf2-8bp3/hier
      {:initial :outer
       :data    {}
       :states
       {:outer {:initial :inner
                :states
                {:inner   {:entry (fn [_ _] {})
                           :on    {:go {:target :sibling}}}
                 :sibling {}}}}})
    (let [idx (per-element-coords :rf2-8bp3/hier)]
      (is (some? (get idx [:states :outer :states :inner :entry]))
          "deeply-nested inline-fn :entry literal is stamped at the
          full path-tuple — recursion works on JVM for fn-forms"))))

;; ---- programmatic call (no literal walk possible) -------------------------

(deftest reg-machine-skips-stamping-for-non-literal-spec
  (testing "when reg-machine receives a symbol bound to a spec value (not a
  literal map form), the macro can't walk the literal — falls through to
  call-site-only stamping; :rf.machine/source-coords is absent rather than
  empty (avoids polluting the registered spec)"
    (let [my-spec {:initial :a :states {:a {}}}]
      (rf/reg-machine :rf2-8bp3/programmatic my-spec))
    ;; The spec itself round-trips; no per-element coord index attached.
    (is (= {:initial :a :states {:a {}}}
           (rf/machine-meta :rf2-8bp3/programmatic))
        "round-tripped spec carries no :rf.machine/source-coords key")
    ;; Top-level handler-meta still carries the macro's call-site coords.
    (let [meta (rf/handler-meta :event :rf2-8bp3/programmatic)]
      (is (some? (:line meta)))
      (is (some? (:ns meta))))))

;; ---- reg-machine* programmatic plain-fn surface ---------------------------

(deftest reg-machine*-plain-fn-surface
  (testing "reg-machine* (the plain-fn surface) registers a machine without
  any macro walking — equivalent to the legacy reg-machine defn. Used by
  code-gen pipelines that already carry a stamped spec."
    (rf/reg-machine* :rf2-8bp3/plain
                     {:initial :a :states {:a {}}})
    (is (= :rf2-8bp3/plain
           (some #{:rf2-8bp3/plain} (rf/machines)))
        "plain-fn registration shows up in (rf/machines) like macro registrations")
    (is (= {:initial :a :states {:a {}}}
           (rf/machine-meta :rf2-8bp3/plain))
        "spec round-trips verbatim")))
