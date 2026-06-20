(ns re-frame.machine-source-coord-cljs-test
  "CLJS coverage for co-located per-element source stamping. Per Spec 005
  §Source-coord stamping the `reg-machine` macro walks the literal spec form
  at expansion time and CO-LOCATES per-element source onto each guard /
  action / on-spawn-action entry, and CO-LOCATES a reference-site
  `:source-coords` onto each MAP node inside the `:states` tree (state-node /
  transition map) at its spec-path.

  The CLJS counterpart of machine_source_coord_test.clj. Per the keyword-
  reference rule the macro captures:
    - definition sites: each fn literal under :guards / :actions /
      :on-spawn-actions co-locates :source-coords / :source-code on its
      entry — read at (get-in spec [:guards <id> :source-coords])
    - reference sites: each MAP node (state-node, transition map) inside
      :states carries its own :source-coords directly — read at
      (get-in spec [:states :idle :source-coords]) etc. Inline-fn / keyword
      slots (:entry / :exit / :guard / :action / :on-spawn) hold a value,
      not a map, so they carry no coord of their own; a tool reads the
      nearest enclosing map's coord.

  CLJS reader-meta: the CLJS analyzer / cljs.tools.reader DOES attach
  :line / :column metadata to map and vector literals, so the full
  state-node / transition-map co-location surface is exercisable here
  (unlike on JVM where the standard LispReader only decorates list forms)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- element-coords [machine-id slot id]
  (get-in (machines/machine-meta machine-id) [slot id :source-coords]))

;; Read a co-located reference-site `:source-coords` off the MAP node
;; (state-node / transition map) at `spec-path` in the registered spec.
(defn- node-coords [machine-id spec-path]
  (get-in (machines/machine-meta machine-id) (conj (vec spec-path) :source-coords)))

;; ---- definition-site stamping --------------------------------------------

(deftest reg-machine-stamps-guard-and-action-definitions-cljs
  (testing "fn literals under :guards / :actions / :on-spawn-actions
  co-locate their definition coords on each entry"
    (rf/reg-machine :rf2-8bp3/defs
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :actions {:do  (fn [_] {})}
       :on-spawn-actions {:cap (fn [{data :data id :id}] (assoc data :pending id))}
       :states  {:idle {}}})
    (is (some? (element-coords :rf2-8bp3/defs :guards :ok?)))
    (is (some? (element-coords :rf2-8bp3/defs :actions :do)))
    (is (some? (element-coords :rf2-8bp3/defs :on-spawn-actions :cap)))))

;; ---- reference-site stamping (transition / state-node / inline-fn) --------

(deftest reg-machine-co-locates-transition-map-coords-cljs
  (testing "transition maps inside :on carry their own co-located
  :source-coords at their spec-path; inline-fn / keyword slots inside them
  carry NO coord of their own (a tool reads the enclosing map's coord)"
    (rf/reg-machine :rf2-8bp3/refs
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :states
       {:idle {:on {:submit {:target :done :guard :ok?}
                    :cancel {:target :idle :action (fn [_] {})}}}
        :done {}}})
    ;; Each transition MAP carries its co-located coord directly.
    (is (some? (node-coords :rf2-8bp3/refs [:states :idle :on :submit]))
        "transition map for :submit carries co-located :source-coords")
    (is (some? (node-coords :rf2-8bp3/refs [:states :idle :on :cancel]))
        "transition map for :cancel carries co-located :source-coords")
    ;; Inline-fn :action holds a fn VALUE, not a map — no coord on the slot.
    (is (fn? (get-in (machines/machine-meta :rf2-8bp3/refs)
                     [:states :idle :on :cancel :action]))
        "inline-fn :action value round-trips at its slot")))

(deftest reg-machine-co-locates-state-node-coords-cljs
  (testing "state-node maps inside :states carry their own co-located
  :source-coords at their spec-path (CLJS reader attaches meta to map
  literals)"
    (rf/reg-machine :rf2-8bp3/nodes
      {:initial :a
       :states
       {:a {:on {:go :b}}
        :b {}}})
    (is (some? (node-coords :rf2-8bp3/nodes [:states :a]))
        "state-node :a carries co-located :source-coords")
    (is (some? (node-coords :rf2-8bp3/nodes [:states :b]))
        "state-node :b carries co-located :source-coords")))

(deftest reg-machine-co-locates-vector-of-transitions-cljs
  (testing "vector :on transitions co-locate a coord per transition map index"
    (rf/reg-machine :rf2-8bp3/vec
      {:initial :idle
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
    (is (some? (node-coords :rf2-8bp3/vec [:states :idle :on :tick 0])))
    (is (some? (node-coords :rf2-8bp3/vec [:states :idle :on :tick 1])))
    (is (some? (node-coords :rf2-8bp3/vec [:states :idle :on :tick 2])))))

(deftest reg-machine-co-locates-always-cljs
  (testing ":always vector — each transition map co-locates a coord per index"
    (rf/reg-machine :rf2-8bp3/always
      {:initial :a
       :guards  {:enough? (fn [_] true)}
       :states
       {:a {:always [{:guard :enough? :target :b}]}
        :b {}}})
    (is (some? (node-coords :rf2-8bp3/always [:states :a :always 0])))
    ;; Definition coord co-located too.
    (is (some? (element-coords :rf2-8bp3/always :guards :enough?)))))

(deftest reg-machine-co-locates-entry-exit-cljs
  (testing ":entry / :exit slots hold fn / keyword VALUES, not maps — they
  carry NO coord of their own; the enclosing state-node's coord stands in.
  The state-node IS co-located."
    (rf/reg-machine :rf2-8bp3/ee
      {:initial :a
       :actions {:enter-a (fn [_] {})}
       :states
       {:a {:entry :enter-a
            :exit (fn [_] {})
            :on    {:go :b}}
        :b {}}})
    ;; The enclosing state-node carries the coord that stands in for its slots.
    (is (some? (node-coords :rf2-8bp3/ee [:states :a]))
        "state-node :a carries the coord standing in for its :entry / :exit")
    ;; The inline-fn :exit value round-trips; the keyword :entry too.
    (is (fn? (get-in (machines/machine-meta :rf2-8bp3/ee) [:states :a :exit])))
    (is (= :enter-a (get-in (machines/machine-meta :rf2-8bp3/ee) [:states :a :entry])))
    ;; Definition coord co-located on the named action.
    (is (some? (element-coords :rf2-8bp3/ee :actions :enter-a)))))

(deftest reg-machine-co-locates-hierarchical-states-cljs
  (testing "nested :states recurse — state-node coords co-locate at the
  full leaf-prefixed path"
    (rf/reg-machine :rf2-8bp3/hier
      {:initial :outer
       :states
       {:outer {:initial :inner
                :states  {:inner   {:on {:go {:target :sibling}}}
                          :sibling {}}}}})
    (is (some? (node-coords :rf2-8bp3/hier [:states :outer]))
        "outer state-node co-locates its coord")
    (is (some? (node-coords :rf2-8bp3/hier [:states :outer :states :inner]))
        "inner state-node co-locates its coord at the recursive path")
    (is (some? (node-coords :rf2-8bp3/hier [:states :outer :states :inner :on :go]))
        "transition map inside hierarchical inner state co-locates its coord")))

;; ---- inline-fn :source-code co-location -----------------------------------

;; Read the inline-fn `:source-code` string for an inline slot off the
;; enclosing `:states`-tree map node.
(defn- inline-source [machine-id enclosing-path slot]
  (get-in (machines/machine-meta machine-id)
          (conj (vec enclosing-path) :source-code slot)))

(deftest reg-machine-stamps-inline-action-source-code-cljs
  (testing "inline transition `:action` / state `:entry` / `:exit` / inline
  `:guard` fns carry their `:source-code` on the enclosing `:states`-tree map
  node — parity with the named-guard `:source-code` stamp (rf2-se70xj). The
  inline slot value itself stays a bare fn (the runtime resolves it via fn?)."
    (rf/reg-machine :rf2-se70xj/inline
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :states
       {:idle {:entry (fn [_] {:data {:entered? true}})
               :exit  (fn [_] {:data {:exited? true}})
               :on    {:submit {:target :done :guard (fn [{data :data}] (:ready? data))}
                       :cancel {:target :idle :action (fn [_] {:data {:cancelled? true}})}}}
        :done {}}})
    ;; Inline transition :action.
    (let [src (inline-source :rf2-se70xj/inline [:states :idle :on :cancel] :action)]
      (is (string? src) "inline :action carries :source-code")
      (is (re-find #":cancelled\?" src)))
    ;; Inline state :entry / :exit.
    (is (re-find #":entered\?" (inline-source :rf2-se70xj/inline [:states :idle] :entry)))
    (is (re-find #":exited\?"  (inline-source :rf2-se70xj/inline [:states :idle] :exit)))
    ;; Inline transition :guard.
    (is (re-find #":ready\?"   (inline-source :rf2-se70xj/inline [:states :idle :on :submit] :guard)))
    ;; Slot values stay bare fns — not wrapped into a map.
    (is (fn? (get-in (machines/machine-meta :rf2-se70xj/inline) [:states :idle :on :cancel :action])))
    (is (fn? (get-in (machines/machine-meta :rf2-se70xj/inline) [:states :idle :entry])))))

(deftest reg-machine-stamps-inline-always-single-map-source-code-cljs
  (testing "an inline `:always` `:action` written as a SINGLE MAP (not a
  vector of candidates) carries its `:source-code` at the bare `:always`
  spec-path — parity with the vector `:always [{…}]` form (rf2-k7yqod). The
  runtime + validator both accept the single-map `:always` (wrapping it into
  a one-candidate vector), so the macro must stamp it too — otherwise the
  Epoch panel renders `#object[Function]` for it."
    (rf/reg-machine :rf2-k7yqod/always-single-map
      {:initial :a
       :data    {:pending? true}
       :states
       {:a {:always {:target :b
                     :guard  (fn [{data :data}] (:pending? data))
                     :action (fn [{data :data}]
                               {:data (assoc data :always-single-fired? true)})}}
        :b {}}})
    ;; Single-map `:always` keys at the bare `:always` path (no index).
    (let [action-src (inline-source :rf2-k7yqod/always-single-map [:states :a :always] :action)
          guard-src  (inline-source :rf2-k7yqod/always-single-map [:states :a :always] :guard)]
      (is (string? action-src)
          "single-map :always :action carries :source-code at [:states :a :always]")
      (is (re-find #":always-single-fired\?" action-src)
          "the captured :source-code is the inline action body")
      (is (string? guard-src)
          "single-map :always :guard carries :source-code at [:states :a :always]")
      (is (re-find #":pending\?" guard-src)))
    ;; The slot values stay bare fns (the runtime resolves them via fn?).
    (is (fn? (get-in (machines/machine-meta :rf2-k7yqod/always-single-map)
                     [:states :a :always :action])))
    (is (fn? (get-in (machines/machine-meta :rf2-k7yqod/always-single-map)
                     [:states :a :always :guard])))
    ;; The single-map form does NOT mistakenly key under an index 0.
    (is (nil? (inline-source :rf2-k7yqod/always-single-map [:states :a :always 0] :action))
        "single-map :always is NOT keyed at index 0 (that's the vector form)")))

(deftest reg-machine-stamps-inline-always-vector-source-code-cljs
  (testing "a VECTOR `:always` co-locates each candidate map's inline
  `:action` source at its index (rf2-k7yqod). The vector form keys per
  index; a multi-candidate vector stamps each candidate independently so the
  Epoch source lookup does NOT hardcode index 0 onto the wrong candidate."
    (rf/reg-machine :rf2-k7yqod/always-vector
      {:initial :a
       :data    {}
       :guards  {:first? (fn [_] false)}
       :states
       {:a {:always [{:guard  :first?
                      :target :b
                      :action (fn [_] {:data {:always-vec-0-fired? true}})}
                     {:target :c
                      :action (fn [_] {:data {:always-vec-1-fired? true}})}]}
        :b {}
        :c {}}})
    ;; Each candidate's inline :action is stamped at its OWN index.
    (let [src-0 (inline-source :rf2-k7yqod/always-vector [:states :a :always 0] :action)
          src-1 (inline-source :rf2-k7yqod/always-vector [:states :a :always 1] :action)]
      (is (string? src-0) "vector :always candidate 0 carries :source-code at index 0")
      (is (re-find #":always-vec-0-fired\?" src-0))
      (is (string? src-1) "vector :always candidate 1 carries :source-code at index 1")
      (is (re-find #":always-vec-1-fired\?" src-1))
      ;; The two candidates' sources are DISTINCT — index 0 is not reused.
      (is (not= src-0 src-1)
          "each candidate keys its own source — Xray must not hardcode index 0"))))

(deftest reg-machine-skips-inline-source-for-keyword-references-cljs
  (testing "keyword-reference slots carry NO inline :source-code — their body
  lives on the named :actions / :guards entry's own :source-code (rf2-se70xj)"
    (rf/reg-machine :rf2-se70xj/kw
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :actions {:do  (fn [_] {})}
       :states
       {:idle {:on {:submit {:target :done :guard :ok? :action :do}}}
        :done {}}})
    (is (nil? (inline-source :rf2-se70xj/kw [:states :idle :on :submit] :action)))
    (is (nil? (inline-source :rf2-se70xj/kw [:states :idle :on :submit] :guard)))
    (is (string? (get-in (machines/machine-meta :rf2-se70xj/kw) [:actions :do :source-code])))))

;; ---- programmatic call (no walking) --------------------------------------

(deftest reg-machine-skips-stamping-for-non-literal-spec-cljs
  (testing "when the spec is bound to a symbol (not a literal map form), the
  macro can't walk and the registered spec carries no co-located source /
  state-node :source-coords"
    (let [spec {:initial :a :states {:a {}}}]
      (rf/reg-machine :rf2-8bp3/programmatic spec))
    (is (= {:initial :a :states {:a {}}}
           (machines/machine-meta :rf2-8bp3/programmatic))
        "registered spec round-trips with no co-located source")))

;; ---- reg-machine* plain-fn surface ---------------------------------------

(deftest reg-machine*-plain-fn-surface-cljs
  (testing "reg-machine* registers without macro walking — equivalent to
  the legacy reg-machine defn"
    (machines/reg-machine* :rf2-8bp3/plain
                     {:initial :a :states {:a {}}})
    (is (some? (some #{:rf2-8bp3/plain} (machines/machines)))
        "(rf/machines) lists the plain-fn registered machine")
    (is (= {:initial :a :states {:a {}}}
           (machines/machine-meta :rf2-8bp3/plain))
        "spec round-trips verbatim")))
