(ns re-frame.machine-source-coord-test
  "Per-element source-coord stamping for machine specs. Per Spec 005
  §Source-coord stamping (rf2-npvsx, supersedes rf2-8bp3) — the
  `reg-machine` macro walks the literal spec form at expansion time and
  CO-LOCATES per-element source onto each guard / action / on-spawn-action
  entry (`:guards {<id> {:fn .. :source-coords {...} :source-code \"..\"}}`),
  plus a reference-site coord index under `:rf.machine/state-coords` keyed
  by `:states`-tree spec paths.

  Definition sites: each fn literal under `:guards` / `:actions` /
  `:on-spawn-actions` carries its `:source-coords` (and `:source-code`) ON
  its co-located entry — read at `(get-in spec [:guards <id> :source-coords])`.

  Reference sites: each transition / state-node / inline-fn slot inside the
  `:states` tree is keyed by its full spec path in `:rf.machine/state-coords`,
  e.g. `[:states :idle :on :submit :guard]`.

  Per the bead's exemption case (keyword reference vs definition for
  `:guard :form-valid?`-style indirection): the rule is **co-located on the
  element** for the definition, **reference-site (state-coords) only** for
  inline-fn literals. A keyword (`:form-valid?`) is a name, not a source
  form — it carries no reader metadata of its own, so it gets no
  reference-site coord; the enclosing transition map's coord is already
  stamped. Inline-fn references (`:guard (fn [_] ...)`) carry their own
  reader metadata, so the reference site gets a distinct coord and IS
  stamped.

  This test runs on JVM only because the source-coord-walking macro is
  Clojure-side. CLJS tests in machine_source_coord_cljs_test.cljs cover
  the same surface end-to-end through the macroexpansion the cljs
  compiler performs on .clj/.cljc macros.

  Reader-meta limitation on JVM: the standard Clojure `LispReader` only
  attaches `:line` / `:column` metadata to *list* forms (fn-bodies) —
  not to map or vector literals. So on JVM, the walker captures
  definition-site fn literals (the co-located entries under `:guards` /
  `:actions` / `:on-spawn-actions`) reliably; state-node and transition-map
  coords are not available on JVM because the source forms don't carry the
  reader meta the walker reads. The CLJS reader (cljs.tools.reader)
  enriches maps/vectors, so the CLJS counterpart test exercises the
  full path-tuple surface."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Helper: read a co-located element entry's source-coords off a
;; registered machine. `slot` is :guards / :actions / :on-spawn-actions.
(defn- element-coords [machine-id slot id]
  (get-in (rf/machine-meta machine-id) [slot id :source-coords]))

;; Helper: read the reference-site (states-tree) coord index.
(defn- state-coords [machine-id]
  (-> (rf/machine-meta machine-id)
      :rf.machine/state-coords))

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
  (testing "each fn literal under :guards co-locates its source-coord at the
  fn-form's reader position on the element entry"
    (rf/reg-machine :rf2-8bp3/guard-defs
      {:initial :idle
       :data    {}
       :guards  {:always-true (fn [_] true)
                 :n-positive? (fn [{data :data}] (pos? (or (:n data) 0)))}
       :states  {:idle {}}})
    (let [m (rf/machine-meta :rf2-8bp3/guard-defs)]
      (is (some? (get-in m [:guards :always-true :fn]))
          "the :always-true guard entry carries its :fn")
      (is (some? (element-coords :rf2-8bp3/guard-defs :guards :always-true))
          "the :always-true guard fn-form carries co-located :source-coords")
      (is (some? (element-coords :rf2-8bp3/guard-defs :guards :n-positive?))
          "the :n-positive? guard fn-form carries co-located :source-coords")
      (let [c (element-coords :rf2-8bp3/guard-defs :guards :always-true)]
        (is (= 're-frame.machine-source-coord-test (:ns c)))
        (is (integer? (:line c)))
        (is (integer? (:column c)))))))

(deftest reg-machine-stamps-action-definitions
  (testing "each fn literal under :actions co-locates its source-coord"
    (rf/reg-machine :rf2-8bp3/action-defs
      {:initial :idle
       :data    {}
       :actions {:bump   (fn [{data :data}] {:data (update data :n (fnil inc 0))})
                 :reset  (fn [{data :data}] {:data (assoc data :n 0)})}
       :states  {:idle {}}})
    (is (some? (element-coords :rf2-8bp3/action-defs :actions :bump)))
    (is (some? (element-coords :rf2-8bp3/action-defs :actions :reset)))
    (let [c (element-coords :rf2-8bp3/action-defs :actions :bump)]
      (is (= 're-frame.machine-source-coord-test (:ns c)))
      (is (integer? (:line c))))))

(deftest reg-machine-stamps-on-spawn-action-definitions
  (testing "each fn literal under :on-spawn-actions co-locates its source-coord"
    (rf/reg-machine :rf2-8bp3/on-spawn-defs
      {:initial :idle
       :data    {}
       :on-spawn-actions {:capture-id (fn [{data :data id :id}] (assoc data :pending id))}
       :states  {:idle {}}})
    (is (some? (element-coords :rf2-8bp3/on-spawn-defs :on-spawn-actions :capture-id))
        "the :capture-id on-spawn-action fn-form carries co-located :source-coords")))

;; ---- reference-site stamping inside the :states tree ----------------------

(deftest reg-machine-stamps-on-transition-keyword-references-via-definition
  (testing "On JVM the LispReader doesn't attach line/column meta to map
  literals, so transition-map reference-site coords aren't available
  here. The definition coords for the named guard / action ARE present
  (fn-forms are lists, which the reader does decorate)."
    (rf/reg-machine :rf2-8bp3/on-refs
      {:initial :idle
       :data    {}
       :guards  {:ok? (fn [_] true)}
       :actions {:do  (fn [_] {})}
       :states
       {:idle
        {:on
         {:submit {:target :done :guard :ok? :action :do}}}
        :done {}}})
    ;; Definition sites co-locate their coord (they carry the fn-literal's meta).
    (is (some? (element-coords :rf2-8bp3/on-refs :guards :ok?))
        "definition site coord co-located for keyword references")
    (is (some? (element-coords :rf2-8bp3/on-refs :actions :do)))))

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
        {:entry (fn [_] {})
         :on    {:submit {:target :done
                          :guard (fn [_] true)
                          :action (fn [_] {})}}}
        :done {}}})
    (let [idx (state-coords :rf2-8bp3/inline-refs)]
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
       :guards  {:a? (fn [_] true)
                 :b? (fn [_] false)}
       :states
       {:idle
        {:on
         {:tick [{:guard :a? :target :one}
                 {:guard :b? :target :two}
                 {:target :three}]}}
        :one   {}
        :two   {}
        :three {}}})
    ;; Definition coords are co-located.
    (is (some? (element-coords :rf2-8bp3/on-vec :guards :a?)))
    (is (some? (element-coords :rf2-8bp3/on-vec :guards :b?)))))

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
       :actions {:enter-a (fn [_] {})
                 :exit-a  (fn [_] {})}
       :states
       {:a {:entry :enter-a
            :exit  :exit-a
            :on    {:go :b}}
        :b {}}})
    ;; Definition coords for the named actions co-located.
    (is (some? (element-coords :rf2-8bp3/entry-exit :actions :enter-a)))
    (is (some? (element-coords :rf2-8bp3/entry-exit :actions :exit-a)))))

(deftest reg-machine-stamps-always-via-definition
  (testing ":always transitions reference named guards by keyword. On JVM
  the transition map itself doesn't carry reader meta, but the guard's
  fn-form definition coord IS stamped under [:guards <id>] — that's
  what tools navigate to."
    (rf/reg-machine :rf2-8bp3/always
      {:initial :a
       :data    {}
       :guards  {:enough? (fn [_] true)}
       :states
       {:a {:always [{:guard :enough? :target :b}]}
        :b {}}})
    (is (some? (element-coords :rf2-8bp3/always :guards :enough?)))))

(deftest reg-machine-stamps-invoke-on-spawn-via-definition
  (testing ":spawn {:on-spawn :id}: keyword references resolve through
  the [:on-spawn-actions <id>] definition coord, where the fn-form lives"
    (rf/reg-machine :rf2-8bp3/invoke-os
      {:initial :idle
       :data    {}
       :on-spawn-actions {:cap (fn [{data :data id :id}] (assoc data :pending id))}
       :states
       {:idle {:spawn {:machine-id :child :on-spawn :cap}}}})
    ;; Definition coord co-located.
    (is (some? (element-coords :rf2-8bp3/invoke-os :on-spawn-actions :cap)))))

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
                {:inner   {:entry (fn [_] {})
                           :on    {:go {:target :sibling}}}
                 :sibling {}}}}})
    (let [idx (state-coords :rf2-8bp3/hier)]
      (is (some? (get idx [:states :outer :states :inner :entry]))
          "deeply-nested inline-fn :entry literal is stamped at the
          full path-tuple — recursion works on JVM for fn-forms"))))

;; ---- programmatic call (no literal walk possible) -------------------------

(deftest reg-machine-skips-stamping-for-non-literal-spec
  (testing "when reg-machine receives a symbol bound to a spec value (not a
  literal map form), the macro can't walk the literal — falls through to
  call-site-only stamping; no co-location and no :rf.machine/state-coords
  (avoids polluting the registered spec)"
    (let [my-spec {:initial :a :states {:a {}}}]
      (rf/reg-machine :rf2-8bp3/programmatic my-spec))
    ;; The spec itself round-trips; no co-located entries / state-coords.
    (is (= {:initial :a :states {:a {}}}
           (rf/machine-meta :rf2-8bp3/programmatic))
        "round-tripped spec carries no co-located source / state-coords")
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

;; ---- defmachine: value-registered per-element source capture (rf2-gwj8l) --
;;
;; The common app shape is `(def m {…}) … (reg-machine :id m)`. `reg-machine`
;; sees only the `m` symbol at its call site, so its literal-walk captures
;; nothing (proven by `reg-machine-skips-stamping-for-non-literal-spec` above).
;; `defmachine` walks the literal AT THE DEFINITION SITE and stamps the
;; per-element source onto the def'd VALUE, so it travels into `reg-machine`
;; and the `:machine-guard` / `:machine-action` registrar handler-metas (the
;; Epoch machine-cascade source surface) light up for value-registered
;; machines exactly as for inline ones.

;; The value-registered door-machine shape (mirrors machine_epochs/core.cljs).
(rf/defmachine value-door-machine
  {:initial :locked
   :data    {:opened-count 0 :held-open? false}
   :guards  {:may-close? (fn guard-may-close? [{data :data}] (not (:held-open? data)))}
   :actions {:count-open (fn action-count-open [{data :data}] {:data (update data :opened-count (fnil inc 0))})
             :clear-hold (fn action-clear-hold [{data :data}] {:data (assoc data :held-open? false)})}
   :states  {:locked {:on {:door/insert-coin :closed}}
             :closed {:exit :clear-hold :on {:door/push :open}}
             :open   {:entry :count-open :on {:door/close {:target :closed :guard :may-close?}}}}})

;; A plain `(def …)` of the SAME spec — the foil that carries NO source.
(def plain-door-machine
  {:initial :locked
   :data    {:opened-count 0 :held-open? false}
   :guards  {:may-close? (fn guard-may-close? [{data :data}] (not (:held-open? data)))}
   :actions {:count-open (fn action-count-open [{data :data}] {:data (update data :opened-count (fnil inc 0))})
             :clear-hold (fn action-clear-hold [{data :data}] {:data (assoc data :held-open? false)})}
   :states  {:locked {:on {:door/insert-coin :closed}}
             :closed {:exit :clear-hold :on {:door/push :open}}
             :open   {:entry :count-open :on {:door/close {:target :closed :guard :may-close?}}}}})

(deftest plain-def-value-registered-has-no-per-element-source
  (testing "a plain (def m …) + (reg-machine :id m): the macro sees only the
  symbol, so the :guards / :actions entries are bare fns (no co-located
  :source-coords / :source-code) and the :machine-guard / :machine-action
  handler-metas are nil — the rf2-gwj8l bug shape (the foil for defmachine
  below)"
    (rf/reg-machine :rf2-gwj8l/plain-door plain-door-machine)
    (let [meta (rf/machine-meta :rf2-gwj8l/plain-door)]
      ;; Bare-fn entries — no co-located source-coords / source-code.
      (is (fn? (get-in meta [:guards :may-close?]))
          "plain (def) machine carries bare fns, not co-located entry maps")
      (is (not (contains? meta :rf.machine/state-coords)))
      (is (nil? (rf/handler-meta :machine-action [:rf2-gwj8l/plain-door :clear-hold])))
      (is (nil? (rf/handler-meta :machine-guard [:rf2-gwj8l/plain-door :may-close?]))))))

(deftest defmachine-value-registered-carries-per-element-source
  (testing "a (defmachine m …) + (reg-machine :id m): the definition-site
  walk co-locates :source-coords + :source-code onto each :guards / :actions
  entry of the def'd value, so source travels into reg-machine and the
  :machine-guard / :machine-action handler-metas carry :rf.handler/source +
  coords — exactly what the Epoch machine-cascade reads (cascade-row-coord /
  cascade-row-source-form). rf2-gwj8l + rf2-npvsx."
    (rf/reg-machine :rf2-gwj8l/value-door value-door-machine)
    (let [meta (rf/machine-meta :rf2-gwj8l/value-door)]
      ;; Co-located entries carry :fn + :source-coords + :source-code.
      (is (fn? (get-in meta [:guards :may-close? :fn]))
          "value-registered defmachine entry carries its :fn")
      (is (some? (get-in meta [:guards :may-close? :source-coords])))
      (is (some? (get-in meta [:actions :count-open :source-coords])))
      (is (some? (get-in meta [:actions :clear-hold :source-coords])))
      (let [c (get-in meta [:actions :clear-hold :source-coords])]
        (is (= 're-frame.machine-source-coord-test (:ns c)))
        (is (integer? (:line c))))
      ;; Per-id fn-form source strings.
      (is (string? (get-in meta [:guards :may-close? :source-code])))
      (is (string? (get-in meta [:actions :count-open :source-code])))
      (is (string? (get-in meta [:actions :clear-hold :source-code])))
      ;; Registrar handler-metas — the Epoch machine-cascade source surface.
      (let [exit-meta  (rf/handler-meta :machine-action [:rf2-gwj8l/value-door :clear-hold])
            entry-meta (rf/handler-meta :machine-action [:rf2-gwj8l/value-door :count-open])
            guard-meta (rf/handler-meta :machine-guard  [:rf2-gwj8l/value-door :may-close?])]
        (is (some? exit-meta)  ":clear-hold (exit) handler-meta present")
        (is (some? entry-meta) ":count-open (entry) handler-meta present")
        (is (some? guard-meta) ":may-close? (guard) handler-meta present")
        (is (string? (:rf.handler/source exit-meta))
            "exit action handler-meta carries the fn source")
        (is (= :clear-hold (:rf/action-id exit-meta)))
        (is (= :may-close? (:rf/guard-id guard-meta)))
        (is (some? (:line guard-meta)) "guard handler-meta carries source coords")))))

(deftest defmachine-accepts-optional-docstring
  (testing "defmachine accepts an optional leading docstring like def, riding
  it onto the def'd var's metadata, and still stamps source on the value"
    (rf/defmachine documented-machine
      "A documented machine."
      {:initial :a
       :guards  {:g? (fn [_] true)}
       :states  {:a {}}})
    (is (= "A documented machine." (:doc (meta #'documented-machine))))
    (is (fn? (get-in documented-machine [:guards :g? :fn])))
    (is (string? (get-in documented-machine [:guards :g? :source-code])))))
