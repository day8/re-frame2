(ns re-frame.freehand.slots-grammar-parity-jvm-test
  "Render slots and props forwarding are COMMON grammar — one spelling, one
  contract, two front ends — and this file is that claim's oracle.

  `v/render-fn` / `v/slot` and `v/spread` / `v/spread-safe` are the four verbs
  a component library needs to accept content and attributes from its caller.
  Each is a function on the public door AND a seq form the compiled analyzer
  recognises, which is exactly the arrangement that drifts: two front ends,
  two lowerings, and nothing but a convention that they mean the same thing.

  So the corpus is declared TWICE — [[re-frame.freehand.slot-views]] and its
  `{:compiled true}` twins in [[re-frame.freehand.slot-views-compiled]], the
  same bodies with the option map the only difference — rendered from the same
  calls, and the two structural trees compared. Mode against mode rather than
  each mode against a hand-written expectation: an expectation would be
  written twice and would agree with whichever mode was written first.

  Two things are deliberately NOT symmetric, and both are proved rather than
  smoothed over:

    - an INTERPRETED slot accepts an ordinary pure function as parameterized
      content; a COMPILED one refuses it at BUILD time, naming `v/render-fn`.
      The compiled tier's whole claim is that it can see what it lowers, and a
      function value is exactly what it cannot;
    - what a render-fn BODY answers differs by mode — markup interpreted, a
      node compiled — which is D010 rather than an inconsistency, and it is
      why caller and seam are declared in the same mode here.

  Normative owner: [`spec/004-Views.md`](../../../../../spec/004-Views.md)
  §Render slots and §Props forwarding; the compiled arm is
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../spec/004D-Freehand-Compiled-Grammar.md)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.slot-views :as views]
            [re-frame.freehand.slot-views-compiled :as compiled]
            [re-frame.freehand.tree :as tree]))

(def ^:private interpreted-ns "re-frame.freehand.slot-views")
(def ^:private compiled-ns "re-frame.freehand.slot-views-compiled")

(defn- as-interpreted-ids
  "Rewrite a compiled tree's `:view-id`s back onto the interpreted twins'
  namespace — the only difference between the two corpora, and it is not a
  difference PROMOTION makes at all: it is a difference living in another
  file makes."
  [tree]
  (walk/postwalk
    (fn [x]
      (if (and (keyword? x) (= compiled-ns (namespace x)))
        (keyword interpreted-ns (name x))
        x))
    tree))

(defn- rendered
  "`[interpreted-tree compiled-tree]` for one call, with the compiled tree's
  view-ids normalized onto the interpreted namespace."
  [view-name props]
  [(tree/render [(get views/by-name view-name) props])
   (as-interpreted-ids (tree/render [(get compiled/by-name view-name) props]))])

(defn- root-element
  "The one element a corpus view renders, reached past its own boundary node."
  [tree]
  (first (:children tree)))

;; ---------------------------------------------------------------------------
;; The parity table
;; ---------------------------------------------------------------------------

(def parity-rows
  "One call per row. The tree is deliberately NOT written down: the claim is
  that the two front ends answer the SAME one, and a literal expectation
  beside them would only prove that someone typed it twice."
  [{:note  "an inline render-fn renders the slot's argument"
    :view  :inline-slot
    :props {:label "a"}}

   {:note  "a slot with no parameters is content, not a function of anything"
    :view  :inline-slot-nullary
    :props {}}

   {:note  "a two-argument slot passes both, in order"
    :view  :inline-slot-binary
    :props {:label "one"}}

   {:note  "the rendered output sits among its siblings with no wrapper node"
    :view  :inline-slot-among-siblings
    :props {:label "mid"}}

   {:note  "an absent slot renders nothing and the siblings close over the gap"
    :view  :inline-slot-absent
    :props {:row nil}}

   {:note  "a render-fn carried through a prop renders once per keyed row, with that row's value"
    :view  :table
    :props {:rows ["r1" "r2" "r3"]}}

   {:note  "a forwarded map composes with the tag's .class sugar and the site's overrides"
    :view  :spread-card
    :props {:attrs {:id "main" :class "wide" :data-kind "card"}}}

   {:note  "an override wins the forwarded map's own value"
    :view  :spread-card
    :props {:attrs {:title "caller's" :id "main"}}}

   {:note  "a forwarded map carrying a handler reaches the events slot"
    :view  :spread-card
    :props {:attrs {:on-click [:card/clicked]}}}

   {:note  "a forwarded map carrying a style map reaches the CSS grammar"
    :view  :spread-card
    :props {:attrs {:style {:color "red" :width 4}}}}

   {:note  "the one-argument spelling forwards the map and nothing else"
    :view  :spread-base-only
    :props {:attrs {:role "note" :aria-live "polite"}}}

   {:note  "an empty forward is an element with no attributes"
    :view  :spread-base-only
    :props {:attrs nil}}

   {:note  "a safe spread forwards the keys it permits, under the owned ones"
    :view  :safe-input
    :props {:caller {:aria-label "Email" :data-test "email" :placeholder "you@example.com"}}}

   {:note  "a permitted caller handler installs beside the owned one"
    :view  :safe-input
    :props {:caller {:on-focus [:field/focused]}}}

   {:note  "a nil caller leaves the component exactly as it declared itself"
    :view  :safe-input
    :props {:caller nil}}

   {:note  "the caller's class composes with the owned class and the sugar, owned first"
    :view  :safe-input-classy
    :props {:caller {:class "mt-4"}}}

   {:note  "an aliased caller class routes to the class slot rather than beside it"
    :view  :safe-input-classy
    :props {:caller {:x/class "mt-4"}}}])

(deftest slots-and-spreads-answer-one-tree-in-both-modes
  (testing "The acceptance claim of the slice: a component renders content and
            attributes its CALLER supplied, and promoting the component to
            {:compiled true} does not change the tree it renders."
    (is (<= 12 (count parity-rows)) "the table is not vacuously small")
    (doseq [{:keys [note view props]} parity-rows]
      (let [[i c] (rendered view props)]
        (is (= i c) (str note " — interpreted and compiled answer one tree"))))))

(deftest the-parity-proof-is-not-vacuous
  (testing "A parity proof where both sides are interpreted proves nothing, so
            the lowering each descriptor reports is asserted before its output
            is trusted — one side really is the interpreted walk, the other
            really is the compiled emitter."
    (doseq [[view-name interpreted] views/by-name]
      (let [promoted (get compiled/by-name view-name)]
        (is (some? promoted) (str view-name " has a promoted twin"))
        (is (= :interpreted (:lowering (v/describe interpreted)))
            (str view-name " is declared interpreted"))
        (is (= :compiled (:lowering (v/describe promoted)))
            (str view-name " is declared compiled"))))))

(deftest a-slot-renders-a-real-boundary-and-a-real-forward
  (testing "The parity assertions compare two trees and would pass if BOTH
            were empty. These pin what the trees actually contain, so the
            corpus is rendering something."
    (let [[i c] (rendered :table {:rows ["r1" "r2"]})]
      (doseq [[mode t] [["interpreted" i] ["compiled" c]]]
        (let [cells (->> (tree-seq map? :children t)
                         (filter #(= :re-frame.freehand.slot-views/cell (:view-id %))))]
          (is (= 2 (count cells))
              (str mode " — the slot mounted one declared child per row"))
          (is (= ["r1" "r2"] (mapv #(get-in % [:props :label]) cells))
              (str mode " — each row's own value reached its render-fn")))))
    (let [[i _] (rendered :safe-input {:caller {:aria-label "Email"}})
          el    (root-element i)]
      (is (= "Email" (get-in el [:attrs :aria-label]))
          "the permitted caller key really is forwarded")
      (is (= "owned" (get-in el [:attrs :value]))
          "and the owned prop is untouched"))))

;; ---------------------------------------------------------------------------
;; The bounded half of the bargain
;; ---------------------------------------------------------------------------

(def denied-caller-keys
  "Keys a `v/spread-safe` caller may not carry against
  [[re-frame.freehand.slot-views/safe-input]] — the structural / controlled
  identity keys, plus the component's OWN handler family in both phases, plus
  the alternate spellings that reach the same emitted slot."
  [{:note "the structural key" :caller {:key "k"}}
   {:note "the reserved ref slot" :caller {:ref "r"}}
   {:note "the controlled value the sync door proves" :caller {:value "hijacked"}}
   {:note "the controlled checked flag" :caller {:checked true}}
   {:note "the component's own handler" :caller {:on-change [:hijack]}}
   {:note "its capture phase" :caller {:on-change-capture [:hijack]}}
   {:note "an already-camel spelling of it" :caller {"onChange" [:hijack]}}
   {:note "a namespaced spelling of a structural key" :caller {:consumer/ref "r"}}])

(deftest a-safe-spread-forwards-only-permitted-keys
  (testing "The bound is what lets a component keep a promise about the
            element it renders: a controlled input stays controlled and an
            owned handler stays the one that fires. The denial is a LOUD
            refusal rather than a silent drop — an author who meant to
            override learns that they cannot, in both modes and in every
            build."
    (doseq [{:keys [note caller]} denied-caller-keys]
      (doseq [[mode by-name] [["interpreted" views/by-name]
                              ["compiled" compiled/by-name]]]
        (let [outcome (try (tree/render [(get by-name :safe-input) {:caller caller}])
                           (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
          (is (= :rf.error/ui-tree-malformed (:rf.error/id outcome))
              (str note " is denied — " mode))
          (is (not (str/includes? (pr-str outcome) "hijacked"))
              (str note " — the denied VALUE never reaches the tree (" mode ")")))))))

(deftest a-denied-key-is-absent-from-the-tree
  (testing "Stated as absence as well as refusal, because the two are
            different failures: a guard that threw AFTER folding would still
            have written the key somewhere a later reader could find it."
    (doseq [[mode by-name] [["interpreted" views/by-name]
                            ["compiled" compiled/by-name]]]
      (let [permitted (root-element
                        (tree/render [(get by-name :safe-input)
                                      {:caller {:aria-label "Email"}}]))]
        (is (= "owned" (get-in permitted [:attrs :value]))
            (str mode " — the owned controlled value is the one in the tree"))
        (is (= {:on-change [:field/changed]} (:events permitted))
            (str mode " — the owned handler is the only one installed"))))))

;; ---------------------------------------------------------------------------
;; The mode asymmetry — an ordinary fn is content interpreted, and is not compiled
;; ---------------------------------------------------------------------------

(v/defview fn-slot
  "An ordinary pure FUNCTION as parameterized content. Legal here; the
  compiled twin below does not exist, because it cannot."
  [{:keys [row]}]
  [:tbody [:tr (v/slot row "a")]])

(defn- compile-refusal
  "The ex-data of the compile error `body` raises under `{:compiled true}`, or
  nil when the body compiles."
  [body]
  (try
    (compiler/compile-structural-view
      {:form            (list 'v/defview 'subject '[props] body)
       :menv            nil
       :ns-sym          're-frame.freehand.slots-grammar-parity-jvm-test
       :vname           'subject
       :view-id         ::subject
       :params          '[props]
       :body            [body]
       :children-policy :optional})
    nil
    (catch clojure.lang.ExceptionInfo ex
      (assoc (ex-data ex) :message (ex-message ex)))))

(deftest an-ordinary-fn-is-content-interpreted-and-is-refused-compiled
  (testing "Acceptance criterion 2, and the asymmetry is DELIBERATE. An
            interpreted slot has nothing to prove about the function it
            invokes, so an ordinary pure fn is parameterized content there.
            A compiled slot's whole claim is that it can SEE what it lowers,
            so the same fn is refused — at build time, with the render-fn
            recovery named, not at render with a puzzle."
    (is (= {:tag :tbody :children [{:tag :tr :children [{:tag :td :children ["a"]}]}]}
           (root-element (tree/render [fn-slot {:row (fn [r] [:td r])}])))
        "an ordinary pure fn renders as parameterized content, interpreted")
    (let [d (compile-refusal '[:tbody [:tr (v/slot (fn [r] [:td r]) "a")]])]
      (is (= :rf.ui.compile/bad-slot (:rf.ui.compile/error d))
          "and the same content, written lexically, is refused compiled")
      (is (str/includes? (:message d) "v/render-fn")
          "naming the form that IS compiled render content")
      (is (= :make-template-visible (first (:recovery d)))
          "leading with the recovery that actually fits")
      (is (= :keep-interpreted (last (:recovery d)))
          "and ending on the rung that is always available — this body runs, unchanged, interpreted")
      (is (= :re-frame.freehand/v1 (:re-frame.freehand/grammar d))
          "named against the grammar that refused it"))))

(deftest the-arity-contract-is-host-independent
  (testing "A render-fn is a FIXED-arity callback, and the two hosts disagree
            about what a mismatch does — JavaScript drops surplus arguments
            silently, the JVM throws a raw ArityException. Neither is a
            diagnostic, so the count is checked before the call."
    (let [d (try (tree/render [views/seam {:rows ["a"]
                                           :row  (v/render-fn [r extra] [:td r])}])
                 (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
          "a prop-carried render-fn's arity is enforced at the slot")
      (is (= {:expected 2 :actual 1} (select-keys d [:expected :actual]))
          "and the diagnostic states both counts"))
    (let [d (compile-refusal '[:tbody [:tr (v/slot (v/render-fn [r x] [:td r]) "a")]])]
      (is (= :rf.ui.compile/slot-arity (:rf.ui.compile/error d))
          "an INLINE render-fn's arity is settled at build time instead"))))

(deftest a-slot-refuses-a-value-that-is-not-content
  (testing "The roster of things a slot can invoke is closed, so a value
            outside it is a didactic refusal rather than a call that happens
            to fail."
    (doseq [bad ["a string" 42 {:a 1} [:div]]]
      (let [d (try (tree/render [views/seam {:rows ["a"] :row bad}])
                   (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
        (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
            (str (pr-str bad) " is not slot content"))
        (is (str/includes? (:reason d) "v/render-fn")
            (str (pr-str bad) " — and the refusal names the recovery"))))))

(deftest a-render-fn-prop-records-as-its-own-opaque-marker
  (testing "Spec 004B §The opaque marker: a slot-carrying prop is a CONTRACT
            between the caller and the seam, so the boundary records the
            authoring form rather than the mode-neutral `:fn`. Both modes
            reach the same record, because both lower a render-fn to the same
            roster callback."
    (doseq [[mode by-name] [["interpreted" views/by-name]
                            ["compiled" compiled/by-name]]]
      (let [t    (tree/render [(get by-name :table) {:rows ["r1"]}])
            seam (->> (tree-seq map? :children t)
                      (filter #(str/ends-with? (str (:view-id %)) "/seam"))
                      first)]
        (is (= {:rf.ui/opaque :v/render-fn} (get-in seam [:props :row]))
            (str mode " — the render-fn prop records under its own marker"))))))

(deftest the-tree-still-round-trips-as-edn
  (testing "Everything above records into a tree that prints and READS BACK
            EQUAL — the promise the whole node schema is stated in, and the
            one a carrier smuggled into a prop slot would break."
    (doseq [{:keys [view props]} parity-rows]
      (let [[i c] (rendered view props)]
        (is (= i (read-string (pr-str i))) "interpreted")
        (is (= c (read-string (pr-str c))) "compiled")))))
