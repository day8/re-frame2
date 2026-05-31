(ns day8.re-frame2-xray.panel-registry-cljs-test
  "Pure-data tests for the internal L4-tab registry (rf2-2moh1).

  ## Why `.cljc` + `_cljs_test` naming

  Same dual-target pattern as the other Xray helper tests:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  The registry's own docstring states it lives `.cljc` precisely so
  the JVM test corpus can exercise the pure-data surface —
  registration shape, ordering, mode partition — without spinning a
  CLJS runtime. Panel `:panel` views are CLJS-only; the registry never
  invokes `:panel`, so JVM tests register any stub value there.

  ## Isolation

  The registry is a process-wide `defonce` atom. Production installs
  the canonical tab inventory into it via `register-xray-handlers!`,
  so a test that asserts on `tab-entries` / `tabs-for-mode` against
  the GLOBAL atom would be coupled to whatever else loaded. Every test
  here therefore brackets its body in `reset-for-test!` (before) so it
  drives a clean-slate registry, asserting only on what IT registered.
  The `:after` fixture re-clears so a failing test can't poison a
  neighbour. (The production inventory is re-populated lazily by the
  per-panel install! fns / register-xray-handlers! when the real
  runtime loads; these unit tests never depend on it being present.)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.panel-registry :as reg]))

;; ---- fixtures -----------------------------------------------------------
;;
;; Function-form fixture (NOT the map `{:before .. :after ..}` form) —
;; the map form is a cljs.test affordance; clojure.test's `:each`
;; fixtures must be a fn of the test-fn. Writing it as a fn keeps the
;; `.cljc` running identically on both targets (the JVM corpus + the
;; node-test build). Clear before AND after so neither a leftover
;; production inventory nor a failed neighbour can leak in.

(use-fixtures :each
  (fn [test-fn]
    (reg/reset-for-test!)
    (test-fn)
    (reg/reset-for-test!)))

;; ---- helpers ------------------------------------------------------------

(defn- stub-panel
  "A stand-in `:panel` value. The registry never invokes it (it only
  stores it), so any sentinel works on both targets."
  []
  ::panel-stub)

(defn- dynamic-tab
  "Minimal valid Dynamic tab entry with the given id + order."
  [id order]
  {:id id :label (str id) :mnem (subs (name id) 0 1)
   :modes #{:dynamic} :order order :panel (stub-panel)})

;; ---- (1) reg-l4-tab! — registration shape + return ----------------------

(deftest reg-l4-tab-stores-and-returns-the-entry
  (testing "reg-l4-tab! returns the registered entry unchanged and makes
            it visible to the lookup helpers"
    (let [tab (dynamic-tab :epoch 0)
          ret (reg/reg-l4-tab! tab)]
      (is (= tab ret) "returns the entry it registered")
      (is (= tab (reg/tab-by-id :dynamic :epoch))
          "tab-by-id resolves the entry under [mode id]")
      (is (= [tab] (reg/tab-entries))
          "the single registration is the only entry"))))

(deftest reg-l4-tab-keyed-by-mode-and-id
  (testing "the composite [mode id] key keeps the SAME id under two modes
            as two SEPARATE entries (Dynamic Routing vs Static Routes —
            same :routes id, different panel)"
    (let [dyn {:id :routes :label "Routing" :mnem "r"
               :modes #{:dynamic} :order 6 :panel ::dynamic-routing}
          sta {:id :routes :label "Routes" :mnem "r"
               :modes #{:static} :order 2 :panel ::static-routes}]
      (reg/reg-l4-tab! dyn)
      (reg/reg-l4-tab! sta)
      (is (= dyn (reg/tab-by-id :dynamic :routes)))
      (is (= sta (reg/tab-by-id :static :routes)))
      (is (= 2 (count (reg/tab-entries)))
          "same id under two modes → two distinct entries"))))

;; ---- (2) idempotent in-place replace ------------------------------------

(deftest reg-l4-tab-replaces-in-place
  (testing "re-registering the same [mode id] REPLACES the prior entry
            (shadow-cljs :after-load must not stack duplicates)"
    (let [v1 (assoc (dynamic-tab :epoch 0) :label "Epoch v1")
          v2 (assoc (dynamic-tab :epoch 0) :label "Epoch v2")]
      (reg/reg-l4-tab! v1)
      (reg/reg-l4-tab! v2)
      (is (= 1 (count (reg/tab-entries)))
          "second registration replaces, not appends")
      (is (= "Epoch v2" (:label (reg/tab-by-id :dynamic :epoch)))
          "the latest registration wins"))))

;; ---- (3) :pre assertion enforcement -------------------------------------

(deftest reg-l4-tab-rejects-malformed-entries
  (testing "the :pre contract rejects each malformed shape (the spec for
            a well-formed tab entry — kw id, string label, single-mode
            set, optional string mnem / placeholder-bead, optional
            number order)"
    (let [throws? (fn [tab]
                    (try (reg/reg-l4-tab! tab) false
                         (catch #?(:clj Throwable :cljs :default) _ true)))]
      (is (throws? {:id "not-a-keyword" :label "X" :modes #{:dynamic} :panel ::p})
          "id must be a keyword")
      (is (throws? {:id :x :label :not-a-string :modes #{:dynamic} :panel ::p})
          "label must be a string")
      (is (throws? {:id :x :label "X" :modes [:dynamic] :panel ::p})
          "modes must be a set, not a vector")
      (is (throws? {:id :x :label "X" :modes #{:dynamic :static} :panel ::p})
          "modes must be a SINGLE-element set")
      (is (throws? {:id :x :label "X" :modes #{:bogus} :panel ::p})
          "the mode must be :dynamic or :static")
      (is (throws? {:id :x :label "X" :modes #{:dynamic} :order "0" :panel ::p})
          "order, when present, must be a number")
      (is (throws? {:id :x :label "X" :modes #{:dynamic}
                    :placeholder-bead :not-a-string :panel ::p})
          "placeholder-bead, when present, must be a string"))))

(deftest reg-l4-tab-accepts-optional-slots-absent
  (testing "mnem / order / placeholder-bead are all optional — an entry
            omitting them still registers"
    (let [tab {:id :minimal :label "Minimal" :modes #{:dynamic} :panel ::p}]
      (is (= tab (reg/reg-l4-tab! tab))
          "a minimal entry (no mnem / order / placeholder-bead) is valid"))))

;; ---- (4) tabs-for-mode — partition + ordering ---------------------------

(deftest tabs-for-mode-partitions-by-mode
  (testing "tabs-for-mode returns only the entries registered against the
            requested mode"
    (reg/reg-l4-tab! (dynamic-tab :epoch 0))
    (reg/reg-l4-tab! (dynamic-tab :appdb 1))
    (reg/reg-l4-tab! {:id :catalogue :label "Catalogue" :mnem "c"
                      :modes #{:static} :order 0 :panel ::p})
    (is (= [:epoch :appdb] (mapv :id (reg/tabs-for-mode :dynamic)))
        "only Dynamic tabs surface for :dynamic")
    (is (= [:catalogue] (mapv :id (reg/tabs-for-mode :static)))
        "only Static tabs surface for :static")))

(deftest tabs-for-mode-sorts-by-order-ascending
  (testing "lower :order comes first regardless of registration order"
    (reg/reg-l4-tab! (dynamic-tab :third 2))
    (reg/reg-l4-tab! (dynamic-tab :first 0))
    (reg/reg-l4-tab! (dynamic-tab :second 1))
    (is (= [:first :second :third] (mapv :id (reg/tabs-for-mode :dynamic)))
        "render order follows :order, not insertion order")))

(deftest tabs-for-mode-nil-order-trails
  (testing "an entry with no :order sorts AFTER every ordered entry (the
            sort keys nil orders to +Inf so an unspecified order doesn't
            crash the sort)"
    (reg/reg-l4-tab! (dynamic-tab :ordered-0 0))
    (reg/reg-l4-tab! {:id :unordered :label "Unordered" :mnem "u"
                      :modes #{:dynamic} :panel ::p})
    (reg/reg-l4-tab! (dynamic-tab :ordered-1 1))
    (is (= [:ordered-0 :ordered-1 :unordered]
           (mapv :id (reg/tabs-for-mode :dynamic)))
        "the nil-order entry trails the ordered ones")))

(deftest tabs-for-mode-empty-when-mode-unpopulated
  (testing "a mode with no registrations yields an empty vector (never nil)"
    (reg/reg-l4-tab! (dynamic-tab :epoch 0))
    (is (= [] (reg/tabs-for-mode :static))
        "no Static tabs registered → empty vec")
    (is (vector? (reg/tabs-for-mode :static)))))

;; ---- (5) tab-by-id — miss returns nil -----------------------------------

(deftest tab-by-id-miss-returns-nil
  (testing "tab-by-id returns nil for an unregistered [mode id] (the L4
            detail panel renders an unknown-tab stub in that case so a
            stale :selected-tab doesn't crash the render)"
    (reg/reg-l4-tab! (dynamic-tab :epoch 0))
    (is (nil? (reg/tab-by-id :dynamic :not-registered))
        "unknown id in a populated mode → nil")
    (is (nil? (reg/tab-by-id :static :epoch))
        "a real id under the WRONG mode → nil (composite key miss)")))

;; ---- (6) tab-ids-for-mode -----------------------------------------------

(deftest tab-ids-for-mode-is-the-id-set
  (testing "tab-ids-for-mode is the set of ids registered for the mode —
            drives the select-tab event's contains? guard so unknown ids
            land as no-ops"
    (reg/reg-l4-tab! (dynamic-tab :epoch 0))
    (reg/reg-l4-tab! (dynamic-tab :appdb 1))
    (reg/reg-l4-tab! {:id :catalogue :label "Catalogue" :mnem "c"
                      :modes #{:static} :order 0 :panel ::p})
    (is (= #{:epoch :appdb} (reg/tab-ids-for-mode :dynamic)))
    (is (= #{:catalogue} (reg/tab-ids-for-mode :static)))))

(deftest tab-ids-for-mode-empty-set-when-unpopulated
  (testing "an empty mode yields the empty set"
    (is (= #{} (reg/tab-ids-for-mode :dynamic)))))

;; ---- (7) default-tab-for-mode -------------------------------------------

(deftest default-tab-for-mode-is-the-lowest-order-tab
  (testing "default-tab-for-mode is the FIRST tab in tabs-for-mode order —
            the default landing tab when :selected-tab is unset"
    (reg/reg-l4-tab! (dynamic-tab :appdb 1))
    (reg/reg-l4-tab! (dynamic-tab :epoch 0))
    (is (= :epoch (reg/default-tab-for-mode :dynamic))
        "the lowest-:order tab is the default landing tab")))

(deftest default-tab-for-mode-nil-when-empty
  (testing "no tabs registered for the mode → nil (test-only state;
            production always has the canonical inventory installed)"
    (is (nil? (reg/default-tab-for-mode :dynamic)))
    (is (nil? (reg/default-tab-for-mode :static)))))

;; ---- (8) unreg-l4-tab! — drops every mode for an id ---------------------

(deftest unreg-l4-tab-drops-id-across-modes
  (testing "unreg-l4-tab! removes the id's entries under EVERY mode (the
            same :routes id under both Dynamic + Static both drop)"
    (reg/reg-l4-tab! {:id :routes :label "Routing" :mnem "r"
                      :modes #{:dynamic} :order 6 :panel ::dyn})
    (reg/reg-l4-tab! {:id :routes :label "Routes" :mnem "r"
                      :modes #{:static} :order 2 :panel ::sta})
    (reg/reg-l4-tab! (dynamic-tab :epoch 0))
    (is (= 3 (count (reg/tab-entries))) "baseline — 3 entries")
    (reg/unreg-l4-tab! :routes)
    (is (nil? (reg/tab-by-id :dynamic :routes)) "Dynamic :routes dropped")
    (is (nil? (reg/tab-by-id :static :routes)) "Static :routes dropped")
    (is (= [:epoch] (mapv :id (reg/tab-entries)))
        "only the untouched :epoch entry survives")))

(deftest unreg-l4-tab-unknown-id-is-noop
  (testing "unreg-l4-tab! on an id that was never registered leaves the
            registry untouched (no throw, no spurious removal)"
    (reg/reg-l4-tab! (dynamic-tab :epoch 0))
    (reg/unreg-l4-tab! :never-registered)
    (is (= [:epoch] (mapv :id (reg/tab-entries))))))

;; ---- (9) reset-for-test! ------------------------------------------------

(deftest reset-for-test-clears-the-registry
  (testing "reset-for-test! clears every entry so a fixture can drive a
            clean-slate registration cycle"
    (reg/reg-l4-tab! (dynamic-tab :epoch 0))
    (reg/reg-l4-tab! (dynamic-tab :appdb 1))
    (is (= 2 (count (reg/tab-entries))) "baseline — populated")
    (reg/reset-for-test!)
    (is (empty? (reg/tab-entries))
        "registry is empty after reset (tab-entries is a seq over the
         atom's vals — empty rather than literal [])")
    (is (= [] (reg/tabs-for-mode :dynamic)))
    (is (nil? (reg/default-tab-for-mode :dynamic)))))
