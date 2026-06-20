(ns re-frame.machine-source-coord-test
  "Per-element source-coord stamping for machine specs. Per Spec 005
  §Source-coord stamping — the
  `reg-machine` macro walks the literal spec form at expansion time and
  CO-LOCATES per-element source onto each guard / action / on-spawn-action
  entry (`:guards {<id> {:fn .. :source-coords {...} :source-code \"..\"}}`),
  and CO-LOCATES a reference-site `:source-coords` onto each MAP node inside
  the `:states` tree (state-node / transition map) at its spec-path.

  Definition sites: each fn literal under `:guards` / `:actions` /
  `:on-spawn-actions` carries its `:source-coords` (and `:source-code`) ON
  its co-located entry — read at `(get-in spec [:guards <id> :source-coords])`.

  Reference sites: each MAP node inside the `:states` tree (state-node,
  transition map) carries its own `:source-coords` directly — read at
  `(get-in spec [:states :idle :source-coords])` for the `:idle` state-node
  and `(get-in spec [:states :idle :on :submit :source-coords])` for the
  `:submit` transition map. Inline-fn / keyword slots (`:entry` / `:exit`
  / `:guard` / `:action` / `:on-spawn`) hold a value, not a map, so they
  carry no coord of their own; a tool reads the nearest enclosing map's
  coord (mirroring the keyword-reference rule).

  This test runs on JVM only because the source-coord-walking macro is
  Clojure-side. CLJS tests in machine_source_coord_cljs_test.cljs cover
  the same surface end-to-end through the macroexpansion the cljs
  compiler performs on .clj/.cljc macros.

  Reader-meta limitation on JVM: the standard Clojure `LispReader` only
  attaches `:line` / `:column` metadata to *list* forms (fn-bodies) —
  not to map or vector literals. So on JVM, the walker captures
  definition-site fn literals (the co-located entries under `:guards` /
  `:actions` / `:on-spawn-actions`) reliably; state-node and transition-map
  `:source-coords` are not available on JVM because the source map forms
  don't carry the reader meta the walker reads. The CLJS reader
  (cljs.tools.reader) enriches maps/vectors, so the CLJS counterpart test
  exercises the full co-located state-node / transition-map surface."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Helper: read a co-located element entry's source-coords off a
;; registered machine. `slot` is :guards / :actions / :on-spawn-actions.
(defn- element-coords [machine-id slot id]
  (get-in (machines/machine-meta machine-id) [slot id :source-coords]))

;; Helper: read a co-located reference-site `:source-coords` off the MAP
;; node (state-node / transition map) at `spec-path` inside the registered
;; spec's `:states` tree.
(defn- node-coords [machine-id spec-path]
  (get-in (machines/machine-meta machine-id) (conj (vec spec-path) :source-coords)))

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
    (let [m (machines/machine-meta :rf2-8bp3/guard-defs)]
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

;; ---- inline-fn :source-code co-location ----------------------
;;
;; Inline `:entry` / `:exit` / `:guard` / `:action` fn LITERALS inside the
;; `:states` tree hold a fn VALUE, not a map, so they cannot carry a
;; `:source-code` of their own (the same reason `:source-coords`
;; lives on the enclosing node). The reg-machine macro co-locates each inline
;; fn's `pr-str` source onto the ENCLOSING `:states`-tree map node under a
;; `{<slot> <source-string>}` map keyed `:source-code` — so Xray's Epoch-panel
;; micro-step renders an inline action's CODE instead of `#object[Function]`.
;;
;; This works on JVM (unlike the reference-site `:source-coords`, which needs
;; the CLJS reader's map-literal meta): the source string is the `pr-str` of
;; the fn LITERAL (a list, which the LispReader does decorate / pr-str
;; faithfully), and the co-location is `assoc-in`/`get-in` on the enclosing
;; node — neither depends on map-literal reader meta.

;; Read the inline-fn `:source-code` string for an inline slot off the
;; enclosing `:states`-tree map node. `enclosing-path` is the spec-path to the
;; enclosing state-node / transition map; `slot` is :entry/:exit/:guard/:action.
(defn- inline-source [machine-id enclosing-path slot]
  (get-in (machines/machine-meta machine-id)
          (conj (vec enclosing-path) :source-code slot)))

(deftest reg-machine-stamps-inline-transition-action-source-code
  (testing "an inline transition `:action` fn carries its `:source-code` on
  the enclosing transition map — parity with the guard `:source-code` stamp
  (rf2-se70xj). The guard `:source-code` is the foil that already worked."
    (rf/reg-machine :rf2-se70xj/inline-action
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :states
       {:idle {:on {:submit {:target :done :guard :ok?}
                    :cancel {:target :idle :action (fn [_] {:data {:cancelled? true}})}}}
        :done {}}})
    ;; The named guard's :source-code (the parity baseline — already worked).
    (is (string? (get-in (machines/machine-meta :rf2-se70xj/inline-action)
                         [:guards :ok? :source-code]))
        "named guard carries :source-code (parity baseline)")
    ;; The inline transition :action carries :source-code on the
    ;; enclosing transition map.
    (let [src (inline-source :rf2-se70xj/inline-action [:states :idle :on :cancel] :action)]
      (is (string? src)
          "inline transition :action carries :source-code on the enclosing transition map")
      (is (re-find #"\(fn" src)
          "the captured :source-code is the inline action fn's source form")
      (is (re-find #":cancelled\?" src)
          "the captured :source-code is the action body, not the enclosing map"))
    ;; The inline-fn slot value itself stays a BARE fn (the runtime engine
    ;; resolves it via fn? and stamps it as the trace :action-id) — NOT wrapped.
    (is (fn? (get-in (machines/machine-meta :rf2-se70xj/inline-action)
                     [:states :idle :on :cancel :action]))
        "inline :action slot value stays a bare fn (not wrapped into a map)")))

(deftest reg-machine-stamps-inline-entry-exit-source-code
  (testing "inline state `:entry` / `:exit` fns carry their `:source-code` on
  the enclosing state-node (rf2-se70xj)"
    (rf/reg-machine :rf2-se70xj/inline-ee
      {:initial :a
       :states
       {:a {:entry (fn [_] {:data {:entered? true}})
            :exit  (fn [_] {:data {:exited? true}})
            :on    {:go :b}}
        :b {}}})
    (let [entry-src (inline-source :rf2-se70xj/inline-ee [:states :a] :entry)
          exit-src  (inline-source :rf2-se70xj/inline-ee [:states :a] :exit)]
      (is (string? entry-src) "inline :entry carries :source-code")
      (is (re-find #":entered\?" entry-src))
      (is (string? exit-src) "inline :exit carries :source-code")
      (is (re-find #":exited\?" exit-src)))
    ;; Slot values stay bare fns.
    (is (fn? (get-in (machines/machine-meta :rf2-se70xj/inline-ee) [:states :a :entry])))
    (is (fn? (get-in (machines/machine-meta :rf2-se70xj/inline-ee) [:states :a :exit])))))

(deftest reg-machine-stamps-inline-guard-source-code
  (testing "an inline transition `:guard` fn carries its `:source-code` on the
  enclosing transition map (rf2-se70xj)"
    (rf/reg-machine :rf2-se70xj/inline-guard
      {:initial :idle
       :states
       {:idle {:on {:submit {:target :done :guard (fn [{data :data}] (:ready? data))}}}
        :done {}}})
    (let [src (inline-source :rf2-se70xj/inline-guard [:states :idle :on :submit] :guard)]
      (is (string? src) "inline :guard carries :source-code")
      (is (re-find #":ready\?" src)))))

(deftest reg-machine-stamps-inline-always-single-map-source-code
  (testing "an inline `:always` `:action` / `:guard` written as a SINGLE MAP
  carries its `:source-code` at the bare `:always` spec-path (rf2-k7yqod).
  The runtime + validator both accept the single-map `:always`, so the macro
  must stamp it — parity with the vector `:always [{…}]` form. Works on JVM:
  the source is the `pr-str` of the fn LITERAL (a list the LispReader
  decorates), and the co-location is `assoc-in`/`get-in` on the enclosing
  map — neither needs map-literal reader meta."
    (rf/reg-machine :rf2-k7yqod/always-single
      {:initial :a
       :data    {:pending? true}
       :states
       {:a {:always {:target :b
                     :guard  (fn [{data :data}] (:pending? data))
                     :action (fn [{data :data}]
                               {:data (assoc data :always-single-fired? true)})}}
        :b {}}})
    (let [action-src (inline-source :rf2-k7yqod/always-single [:states :a :always] :action)
          guard-src  (inline-source :rf2-k7yqod/always-single [:states :a :always] :guard)]
      (is (string? action-src)
          "single-map :always :action carries :source-code at [:states :a :always]")
      (is (re-find #"\(fn" action-src))
      (is (re-find #":always-single-fired\?" action-src)
          "the captured :source-code is the action body, not the enclosing map")
      (is (string? guard-src) "single-map :always :guard carries :source-code")
      (is (re-find #":pending\?" guard-src)))
    ;; The inline-fn slot values stay BARE fns (the runtime resolves via fn?).
    (is (fn? (get-in (machines/machine-meta :rf2-k7yqod/always-single)
                     [:states :a :always :action])))
    (is (fn? (get-in (machines/machine-meta :rf2-k7yqod/always-single)
                     [:states :a :always :guard])))
    ;; The single-map form does NOT mistakenly key at index 0.
    (is (nil? (inline-source :rf2-k7yqod/always-single [:states :a :always 0] :action))
        "single-map :always is NOT keyed at index 0 (that's the vector form)")))

(deftest reg-machine-stamps-inline-always-vector-source-code
  (testing "a VECTOR `:always` co-locates each candidate map's inline
  `:action` source at its OWN index (rf2-k7yqod) — so the source lookup does
  not hardcode index 0 onto the wrong candidate."
    (rf/reg-machine :rf2-k7yqod/always-vec-src
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
    (let [src-0 (inline-source :rf2-k7yqod/always-vec-src [:states :a :always 0] :action)
          src-1 (inline-source :rf2-k7yqod/always-vec-src [:states :a :always 1] :action)]
      (is (string? src-0) "vector :always candidate 0 carries :source-code at index 0")
      (is (re-find #":always-vec-0-fired\?" src-0))
      (is (string? src-1) "vector :always candidate 1 carries :source-code at index 1")
      (is (re-find #":always-vec-1-fired\?" src-1))
      (is (not= src-0 src-1)
          "each candidate keys its own source — the index-0 hardcode would
           reuse the wrong body"))))

(deftest reg-machine-skips-inline-source-for-keyword-references
  (testing "keyword-reference slots (`:action :clear-hold`) carry NO inline
  :source-code on the enclosing node — their body lives on the named
  :actions / :guards entry's own :source-code (rf2-se70xj)"
    (rf/reg-machine :rf2-se70xj/kw-refs
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :actions {:do  (fn [_] {})}
       :states
       {:idle {:on {:submit {:target :done :guard :ok? :action :do}}}
        :done {}}})
    ;; No inline :source-code for keyword-reference slots on the transition.
    (is (nil? (inline-source :rf2-se70xj/kw-refs [:states :idle :on :submit] :action)))
    (is (nil? (inline-source :rf2-se70xj/kw-refs [:states :idle :on :submit] :guard)))
    ;; The named entries DO carry their own :source-code (the existing path).
    (is (string? (get-in (machines/machine-meta :rf2-se70xj/kw-refs) [:actions :do :source-code])))
    (is (string? (get-in (machines/machine-meta :rf2-se70xj/kw-refs) [:guards :ok? :source-code])))))

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

(deftest reg-machine-co-locates-on-map-nodes-only-not-inline-fn-slots
  (testing "per rf2-vqja2 ONLY map nodes (state-node / transition map) get a
  co-located `:source-coords`; inline-fn slots (`:entry` / `:guard` /
  `:action`) hold a fn VALUE, not a map, so they carry no coord of their
  own. On JVM the enclosing state-node / transition-map literals carry no
  reader meta (LispReader decorates only list forms), so neither the
  state-node nor the inline-fn-slot reference coords are present here — the
  CLJS counterpart exercises the map-node co-location. The inline-fn VALUES
  themselves still round-trip at their spec paths."
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
    (let [m (machines/machine-meta :rf2-8bp3/inline-refs)]
      ;; No coord co-located on map nodes (JVM map literals carry no meta).
      (is (nil? (node-coords :rf2-8bp3/inline-refs [:states :idle])))
      (is (nil? (node-coords :rf2-8bp3/inline-refs [:states :idle :on :submit])))
      ;; Inline-fn slots are never co-located (they hold a fn, not a map);
      ;; the fn value itself round-trips at its slot.
      (is (fn? (get-in m [:states :idle :entry])))
      (is (fn? (get-in m [:states :idle :on :submit :action]))))))

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

(deftest reg-machine-recurses-hierarchical-states
  (testing "nested :states recurse — on JVM, state-node maps carry no
  reader meta so co-located node coords are absent here, but the recursion
  must still WALK the nested tree without error and leave the nested
  structure intact (the inline-fn values round-trip at their deep paths).
  The CLJS counterpart asserts the co-located node coords land."
    (rf/reg-machine :rf2-8bp3/hier
      {:initial :outer
       :data    {}
       :states
       {:outer {:initial :inner
                :states
                {:inner   {:entry (fn [_] {})
                           :on    {:go {:target :sibling}}}
                 :sibling {}}}}})
    (let [m (machines/machine-meta :rf2-8bp3/hier)]
      ;; No coord on JVM (map literals carry no reader meta); structure intact.
      (is (nil? (node-coords :rf2-8bp3/hier [:states :outer :states :inner])))
      (is (fn? (get-in m [:states :outer :states :inner :entry]))
          "deeply-nested inline-fn :entry value round-trips — recursion
          walked the nested tree intact")
      (is (= :sibling (get-in m [:states :outer :states :inner :on :go :target]))))))

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
           (machines/machine-meta :rf2-8bp3/programmatic))
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
    (machines/reg-machine* :rf2-8bp3/plain
                     {:initial :a :states {:a {}}})
    (is (= :rf2-8bp3/plain
           (some #{:rf2-8bp3/plain} (machines/machines)))
        "plain-fn registration shows up in (rf/machines) like macro registrations")
    (is (= {:initial :a :states {:a {}}}
           (machines/machine-meta :rf2-8bp3/plain))
        "spec round-trips verbatim")))

;; ---- defmachine: value-registered per-element source capture --
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
    (let [meta (machines/machine-meta :rf2-gwj8l/plain-door)]
      ;; Bare-fn entries — no co-located source-coords / source-code.
      (is (fn? (get-in meta [:guards :may-close?]))
          "plain (def) machine carries bare fns, not co-located entry maps")
      ;; No reference-site `:source-coords` co-located on any state-node
      ;; (the macro saw only the symbol, so no literal walk).
      (is (not (contains? (get-in meta [:states :locked]) :source-coords)))
      (is (not (contains? (get-in meta [:states :open]) :source-coords)))
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
    (let [meta (machines/machine-meta :rf2-gwj8l/value-door)]
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
