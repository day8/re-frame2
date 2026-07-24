(ns re-frame.freehand.structural-slot-alias-jvm-test
  "An ALIASED attribute key that projects onto one of React's structural
  slots, ruled — and ruled two different ways, which is why it is proved
  here rather than assumed.

  Four authored keys reduce to a slot the walks own rather than to an
  ordinary attribute: `:key`, `:class`, `:style` — and `:id`, whenever the
  tag carries `#id` sugar. Every representation of those names reaches the
  SAME slot, because both emitters classify an attribute key by its `name`
  — so `:x/class`, `\"class\"` and `'class` are one key spelled three ways,
  and a guard or a router that compared the raw map key would see three.

  The ruling SPLITS them, because they are not the same kind of thing:

    - `:key` is REFUSED, in every representation but the exact one. React's
      key is not a prop — the reconciler consumes it and it never reaches
      the DOM — so an alias routed into it would not misspell an attribute,
      it would change which element React considers the same element across
      renders. Wrong element reuse is `:children`'s hazard class, not a
      misspelled attribute's.
    - `:class` and `:style` are ROUTED, canonicalized to the slot they
      project onto, because they are ordinary props that reach the DOM and
      `v/spread-safe` already accepts an aliased spelling of an accepted key
      and routes it. Refusing them on the direct path would make it stricter
      than the spread path for the same key.
    - `:id` is neither, and is the third answer: an id is an ordinary prop,
      but `#id` sugar has ALREADY written it, so an authored one is a
      SECOND spelling of an attribute the element already has. Two id
      spellings on one element is an ambiguity the grammar removes rather
      than ranks, and it was already refused for the exact `:id` — the
      alias just walked round the guard, because the guard compared the raw
      key. With no sugar there is no second spelling and nothing to rule
      on, so `:x/id` there stays an ordinary qualified attribute.

  Routing `:class` carries the obligation this file exists to hold down: the
  routed value must COMPOSE into the class string beside the tag shorthand,
  never assign over it. A router that assigned would silently drop
  `:div.a.b`'s classes — a worse defect than the one being fixed, and an
  invisible one, because the element still renders.

  Every row is asserted in BOTH modes from one declaration: interpreted
  through [[re-frame.freehand.tree/render]], compiled through the emitted
  lowering itself, evaluated and called. A rule that held in one mode would
  be the cross-mode divergence the substrate exists to rule out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.tree :as tree]))

(def ^:private params
  "The one binding the dynamic rows read, so a computed attribute value is a
  real runtime value in BOTH modes rather than a literal in disguise."
  '[{:keys [flag]}])

(defn- compiled-tree
  "The structural tree the COMPILED front end answers for `body` — the
  emitted lowering, evaluated and called, rather than a claim about the
  form it emitted. `props` supplies the one binding the dynamic rows read."
  ([body] (compiled-tree body {}))
  ([body props]
   (let [lowering (compiler/compile-structural-view
                    {:form            (list 'v/defview 'subject params body)
                     :menv            nil
                     :ns-sym          're-frame.freehand.structural-slot-alias-jvm-test
                     :vname           'subject
                     :view-id         ::subject
                     :params          params
                     :body            [body]
                     :children-policy :optional})]
     ((eval (:body lowering)) props))))

(defn- interpreted-tree
  "The structural tree the INTERPRETED walk answers for the same body, with
  the root's version stripped so the two modes' values are comparable. The
  compiled front end binds `flag` through its params; the interpreted walk
  has no params, so the same value is substituted into the form."
  ([body] (interpreted-tree body {}))
  ([body props]
   (dissoc (tree/render (walk/postwalk-replace
                          (into {} (map (fn [[k x]] [(symbol (name k)) x])) props)
                          body))
           :rf.ui/tree-version)))

;; ---------------------------------------------------------------------------
;; The routed slots — `:class` and `:style`
;; ---------------------------------------------------------------------------

(def routed-rows
  "Declarations whose attribute key is an ALIAS of a slot-owning key, and
  the ONE tree both modes answer.

  The `.class` rows are the load-bearing ones. Each carries a tag shorthand
  AND an aliased class, so a router that assigned into the class slot rather
  than composing into it answers `\"c\"` where the contract says `\"a b c\"`
  — the shorthand silently gone, on an element that still renders."
  [{:note  "a namespaced :class composes into the shorthand, exactly as :class does"
    :body  '[:div.a.b {:x/class "c"}]
    :tree  {:tag :div :attrs {:class "a b c"}}}

   {:note  "and it keeps the whole class grammar — a flag map still renders its truthy entries in lexicographic order"
    :body  '[:div.a.b {:x/class {:open true :busy false}}]
    :tree  {:tag :div :attrs {:class "a b open"}}}

   {:note  "a vector value keeps vector order beneath the shorthand"
    :body  '[:span.tag {:x/class ["c" "d"]}]
    :tree  {:tag :span :attrs {:class "tag c d"}}}

   {:note  "a namespaced :style reaches the CSS grammar, so numbers still gain px"
    :body  '[:div {:x/style {:color "red" :width 4}}]
    :tree  {:tag :div :attrs {:style {:color "red" :width "4px"}}}}

   {:note  "an aliased class and an aliased style on one element"
    :body  '[:section.panel {:x/class "open" :x/style {:z-index 3}}]
    :tree  {:tag :section :attrs {:class "panel open" :style {:z-index "3"}}}}])

(deftest an-alias-of-a-slot-owning-key-routes-to-that-slot
  (testing "Per the rf2-drpa3.93 ruling: `:class` and `:style` are ordinary
            props that reach the DOM, and the substrate already routes an
            aliased spelling of an accepted key to that key's slot
            (`v/spread-safe`). So an alias on the DIRECT attribute path is
            routed too — refusing it there would make the direct path
            stricter than the spread path for the same key, which an author
            would experience as arbitrary."
    (is (<= 4 (count routed-rows)) "the table is not vacuously small")
    (doseq [{:keys [note body tree]} routed-rows]
      (is (= tree (interpreted-tree body)) (str note " — interpreted"))
      (is (= tree (compiled-tree body)) (str note " — compiled")))))

(deftest a-routed-class-composes-with-the-shorthand-rather-than-replacing-it
  (testing "The correctness obligation the routing carries, stated on its
            own so it cannot be lost inside a table. The tag shorthand and
            an authored class are MERGED — a routed alias that assigned
            would drop `.a.b` silently, and the element would still render,
            which is why this is asserted rather than assumed."
    (doseq [[mode tree] [["interpreted" (interpreted-tree '[:div.a.b {:x/class "c"}])]
                         ["compiled"    (compiled-tree    '[:div.a.b {:x/class "c"}])]]]
      (let [classes (set (str/split (get-in tree [:attrs :class]) #" "))]
        (is (contains? classes "a") (str mode " — the shorthand's first class survived"))
        (is (contains? classes "b") (str mode " — and its second"))
        (is (contains? classes "c") (str mode " — and the aliased class arrived"))))))

(def dynamic-rows
  "The same routing where the VALUE is only known at render. A compiled
  declaration settles a literal class into a constant and hands a computed
  one to the shared class rule instead, so these rows walk the other half of
  the compiled path — and the shared rule is the interpreted walk's own."
  [{:note  "a computed aliased class still composes beneath the shorthand"
    :body  '[:div.a.b {:x/class flag}]
    :props {:flag "c"}
    :tree  {:tag :div :attrs {:class "a b c"}}}

   {:note  "and a flag map whose entry is computed keeps the one lexicographic order"
    :body  '[:div.a.b {:x/class {:open flag}}]
    :props {:flag true}
    :tree  {:tag :div :attrs {:class "a b open"}}}

   {:note  "a computed style value normalizes through the CSS grammar"
    :body  '[:div {:x/style {:width flag}}]
    :props {:flag 4}
    :tree  {:tag :div :attrs {:style {:width "4px"}}}}])

(deftest a-routed-alias-composes-at-render-time-too
  (testing "The literal and the computed paths through a routed alias are
            the SAME rule, reached from two directions: a build-time
            constant in one mode, the shared class and style rules at render
            in both. A router that only composed the literal case would look
            correct in every table above."
    (doseq [{:keys [note body props tree]} dynamic-rows]
      (is (= tree (interpreted-tree body props)) (str note " — interpreted"))
      (is (= tree (compiled-tree body props)) (str note " — compiled")))))

;; ---------------------------------------------------------------------------
;; The one-map case — an exact :class beside an alias COMPOSES — rf2-c9kus
;; ---------------------------------------------------------------------------

(def compose-rows
  "One attrs map carrying BOTH the exact :class and an alias projecting onto the
  class slot. The ruling is COMPOSE, not last-wins: the two class values MERGE —
  sugar first, then the exact :class, then the alias — identically in both
  modes. A last-wins fold silently dropped whichever entry the map yielded
  first, and for a map past the array-map threshold which one that is depends on
  iteration order, contract on no host (rf2-c9kus)."
  [{:note "exact :class beside a namespaced alias composes, sugar-first"
    :body '[:div.a {:class "b" :x/class "c"}]
    :tree {:tag :div :attrs {:class "a b c"}}}

   {:note "the source order of the two keys does not change the verdict — exact-first either way"
    :body '[:div.a {:x/class "c" :class "b"}]
    :tree {:tag :div :attrs {:class "a b c"}}}

   {:note "no sugar — the exact and the alias still compose, exact-first"
    :body '[:div {:class "b" :x/class "c"}]
    :tree {:tag :div :attrs {:class "b c"}}}

   {:note "the whole class grammar survives the compose — a flag-map alias renders lexicographically"
    :body '[:div.a {:class "b" :x/class {:open true :busy false}}]
    :tree {:tag :div :attrs {:class "a b open"}}}

   {:note "a vector alias keeps its order after the exact class"
    :body '[:div {:class "b" :x/class ["c" "d"]}]
    :tree {:tag :div :attrs {:class "b c d"}}}])

(deftest an-exact-class-and-an-alias-in-one-map-compose-in-both-modes
  (testing "rf2-c9kus ruling: COMPOSE. An exact :class and an alias projecting
            onto the class slot in one map MERGE — sugar first, then the exact
            :class, then the alias — identically interpreted and compiled. This
            is the one-map case of the .93 routing rule, previously mis-behaving
            as last-wins; it reuses that same compose path with no new
            diagnostic. Distinct from the id slot (rf2-5r1af REFUSE): an id is
            referential and nondeterministic on conflict, so it is rejected; a
            class is set-valued with an obvious deterministic union, so it
            composes."
    (doseq [{:keys [note body tree]} compose-rows]
      (is (= tree (interpreted-tree body)) (str note " — interpreted"))
      (is (= tree (compiled-tree body)) (str note " — compiled")))))

(deftest the-composed-class-keeps-every-value-in-both-modes
  (testing "The correctness obligation stated on its own: neither the exact
            :class value nor the alias value may be silently dropped. Asserted as
            a SET membership so a last-wins regression — which drops one — fails
            here even if the surviving order changed."
    (doseq [[mode tree] [["interpreted" (interpreted-tree '[:div.a {:class "b" :x/class "c"}])]
                         ["compiled"    (compiled-tree    '[:div.a {:class "b" :x/class "c"}])]]]
      (let [classes (set (str/split (get-in tree [:attrs :class]) #" "))]
        (is (contains? classes "a") (str mode " — the shorthand class survived"))
        (is (contains? classes "b") (str mode " — the exact :class value survived"))
        (is (contains? classes "c") (str mode " — and the aliased value was not dropped"))))))

;; ---------------------------------------------------------------------------
;; The refused slot — `:key`
;; ---------------------------------------------------------------------------

(def key-aliases
  "Every representation of React's key an author can write in an attribute
  map. All three reduce to the same emitted slot, so all three are refused."
  ['[:div {:x/key "k"}]
   '[:div {"key" "k"}]
   '[:div {key "k"}]])

(deftest an-alias-of-react-s-key-is-refused-in-the-interpreted-walk
  (testing "Per the rf2-drpa3.93 ruling: React's key is NOT a prop. The
            reconciler consumes it and it never reaches the DOM, so an
            alias silently routed into it would change reconciliation
            IDENTITY — preserved DOM state landing on the wrong row, or a
            remount where none was intended. That is a structural
            divergence, the `:children` hazard class, so `:key` keeps its
            one spelling and every alias of it is refused."
    (doseq [body key-aliases]
      (let [ex (try (tree/render body)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str (pr-str body) " is refused"))
        (when ex
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              (str (pr-str body) " — the walk's existing diagnostic id, not a new one"))
          (is (str/includes? (ex-message ex) "reconciler")
              (str (pr-str body) " — the message says WHY a key is different from an attribute")))))))

(deftest an-alias-of-react-s-key-is-refused-in-compiled-mode-too
  (testing "The same law at the other tier, through the macro an author
            actually writes. A declaration the interpreted walk refuses and
            the compiler accepted would be exactly the two-answers-for-one-
            declaration split the refusal exists to close."
    ;; `*ns*` is bound explicitly because `macroexpand` resolves the head
    ;; against the namespace it runs IN, and a test runs inside the runner's
    ;; namespace where `v` names nothing — the form would come back
    ;; unexpanded and the assertion below would pass vacuously.
    (let [ex (try (binding [*ns* (the-ns 're-frame.freehand.structural-slot-alias-jvm-test)]
                    (macroexpand
                      '(v/defview subject
                         {:compiled true}
                         [_]
                         [:div {:x/key "k"}])))
                  nil
                  (catch Exception e e))]
      (is (some? ex) ":x/key is refused at macroexpansion")
      (let [data (ex-data (or (ex-cause ex) ex))]
        (is (= :rf.ui.compile/rejected-prop-spelling (:rf.ui.compile/error data))
            "and it reuses the rejected-spelling id rather than minting a new one")))
    ;; The control that makes the refusal above mean something: the same
    ;; expansion, of a declaration carrying a ROUTED alias, succeeds. A
    ;; macroexpansion that failed for an unrelated reason would look
    ;; identical from the catch block.
    (is (seq? (binding [*ns* (the-ns 're-frame.freehand.structural-slot-alias-jvm-test)]
                (macroexpand
                  '(v/defview subject
                     {:compiled true}
                     [_]
                     [:div.a.b {:x/class "c" :x/style {:color "red"}}]))))
        "an aliased :class and :style expand — the refusal above is about :key alone")
    (is (map? (compiler/compile-structural-view
                {:form            (list 'v/defview 'subject params '[:div {:key "k"}])
                 :menv            nil
                 :ns-sym          're-frame.freehand.structural-slot-alias-jvm-test
                 :vname           'subject
                 :view-id         ::subject
                 :params          params
                 :body            ['[:div {:key "k"}]]
                 :children-policy :optional}))
        "while the exact :key spelling still compiles — the refusal is about the ALIAS")))

;; ---------------------------------------------------------------------------
;; The slot the TAG already occupies — `#id` sugar
;; ---------------------------------------------------------------------------

(def id-aliases
  "Every representation of an authored id an element can carry beside `#id`
  sugar. All four reduce to the emitted `id` slot the sugar already wrote,
  so all four are the one ambiguity."
  ['[:div#sugar {:id "alias"}]
   '[:div#sugar {:x/id "alias"}]
   '[:div#sugar {"id" "alias"}]
   '[:div#sugar {id "alias"}]])

(deftest an-authored-id-beside-id-sugar-is-refused-however-it-is-spelled
  (testing "`#sugar` already wrote the emitted id, so an authored key that
            projects onto that slot spells the element's id a second time.
            The exact `:id` was always refused; the ALIASES were not,
            because the guard compared the raw key — and React writes both
            pairs into one JavaScript property, so the authored one
            silently replaced the sugar while the structural tree went on
            reporting both. A selector, a label or a debug tool reading the
            tree would target an id the browser does not have."
    (doseq [body id-aliases]
      (let [ex (try (tree/render body)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str (pr-str body) " is refused"))
        (when ex
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              (str (pr-str body) " — the walk's existing diagnostic id, not a new one"))
          (is (str/includes? (ex-message ex) "twice")
              (str (pr-str body) " — the message says the id is spelled twice")))))))

(deftest the-compiled-analyzer-refuses-the-same-four-spellings
  (testing "The compiled tier reaches the same verdict through its own
            diagnostic, `:rf.ui.compile/id-sugar-conflict` — the id
            already had one, so the alias needs no new id. A declaration
            the interpreted walk refuses and the compiler accepted would be
            the two-answers-for-one-declaration split the guard exists to
            close.

            The string and symbol spellings are refused at the compiled
            tier by the prior non-keyword-prop rule, so only the keyword
            pair is asserted here — a compiled props map's keys are literal
            keywords by grammar."
    (doseq [body '[[:div#sugar {:id "alias"}] [:div#sugar {:x/id "alias"}]]]
      (let [ex (try (compiled-tree body) nil (catch Exception e e))]
        (is (some? ex) (str (pr-str body) " is refused at compile time"))
        (when ex
          (is (= :rf.ui.compile/id-sugar-conflict
                 (:rf.ui.compile/error (ex-data (or (ex-cause ex) ex))))
              (str (pr-str body) " — reusing the existing compile diagnostic")))))))

(def id-nil-rows
  "The same ambiguity, spelled with a `nil` VALUE. The authored key is what
  makes the id a second time; what the key holds is the ordinary attribute
  question and cannot answer the identity one."
  ['[:div#sugar {:id nil}]
   '[:div#sugar {:x/id nil}]])

(deftest a-nil-authored-id-beside-id-sugar-is-the-same-ambiguity-in-both-modes
  (testing "PRESENCE decides the conflict, never truth. The compiled
            analyzer scans the props map's KEYS and has no value to
            consult, so it always refused these two; the structural and
            React walks guarded on `(some? raw)` and accepted them. That
            split is the sharpest shape the defect can take — adding
            `{:compiled true}` to a working view turns a rendering element
            into a compile error, and nothing in the declaration changed."
    (doseq [body id-nil-rows]
      (let [ex (try (interpreted-tree body) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str (pr-str body) " is refused by the interpreted walk"))
        (when ex
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              (str (pr-str body) " — the walk's existing diagnostic id, not a new one"))
          (is (str/includes? (ex-message ex) "twice")
              (str (pr-str body) " — the message says the id is spelled twice"))))
      (let [ex (try (compiled-tree body) nil (catch Exception e e))]
        (is (some? ex) (str (pr-str body) " is refused at compile time"))
        (when ex
          (is (= :rf.ui.compile/id-sugar-conflict
                 (:rf.ui.compile/error (ex-data (or (ex-cause ex) ex))))
              (str (pr-str body) " — reusing the existing compile diagnostic")))))))

(deftest a-nil-id-with-no-sugar-is-still-an-ordinary-dropped-attribute
  (testing "The presence rule belongs to the SHORTHAND's ambiguity and
            nowhere else. With no `#id` sugar there is no second spelling,
            so a nil id is the ordinary nil attribute the generic law
            drops — a guard that refused every nil id would be a worse
            defect than the bypass it closed, because a conditional id is
            an ordinary thing to write."
    (doseq [body '[[:div {:id nil}] [:div {:x/id nil}]]]
      (is (= {:tag :div} (interpreted-tree body)) (str (pr-str body) " — interpreted"))
      (is (= {:tag :div} (compiled-tree body)) (str (pr-str body) " — compiled")))))

(def id-control-rows
  "The control that makes the refusals above mean something. A guard that
  turned every id away — or every qualified attribute — would look
  identical in a table of rejections and would be a worse defect than the
  bypass it closed. These forms carry exactly ONE id and are accepted."
  [{:note "the shorthand alone is the ordinary case"
    :body '[:div#sugar]
    :tree {:tag :div :attrs {:id "sugar"}}}

   {:note "an :id prop with no sugar is untouched — the ambiguity is the shorthand's"
    :body '[:div {:id "plain"}]
    :tree {:tag :div :attrs {:id "plain"}}}

   {:note "and a qualified id with no sugar keeps its authored name, as every ordinary qualified attribute does"
    :body '[:div {:x/id "alias"}]
    :tree {:tag :div :attrs {:x/id "alias"}}}

   {:note "the shorthand beside an unrelated attribute is not a conflict"
    :body '[:div#sugar {:x/title "ok"}]
    :tree {:tag :div :attrs {:id "sugar" :x/title "ok"}}}

   {:note "nor is a handler whose name merely contains id"
    :body '[:div#sugar {:data-id "d"}]
    :tree {:tag :div :attrs {:id "sugar" :data-id "d"}}}])

(deftest one-id-is-accepted-in-both-modes-however-it-is-spelled
  (testing "The guard fires on the SECOND spelling, not on the name. An
            element with one id — from the shorthand, from a prop, or from
            a qualified prop with no shorthand to collide with — renders
            exactly as it did, in both modes."
    (doseq [{:keys [note body tree]} id-control-rows]
      (is (= tree (interpreted-tree body)) (str note " — interpreted"))
      (is (= tree (compiled-tree body)) (str note " — compiled")))))

;; ---------------------------------------------------------------------------
;; The slot spelled twice with NO shorthand — rf2-5r1af
;; ---------------------------------------------------------------------------

(def no-sugar-id-pairs
  "Two AUTHORED keys that both project onto the id slot, with no `#id`
  shorthand in sight. `#id` sugar is the first spelling ONLY when present;
  with no sugar the second id-slot key in the map is the conflict, and React
  writes one of the pair into `id` while the structural tree reports both —
  the winner decided by map iteration order, contract on no host."
  ['[:div {:id "a" :x/id "b"}]
   '[:div {:x/id "b" :id "a"}]          ; the order does not change the verdict
   '[:div {:id nil :x/id "b"}]          ; PRESENCE, not value
   ;; a map past the array-map threshold (>8 entries) hashes its keys rather
   ;; than keeping insertion order, and the pair still collides
   '[:div {:id "a" :x/id "b" :data-1 1 :data-2 2 :data-3 3
           :data-4 4 :data-5 5 :data-6 6 :data-7 7}]])

(deftest two-authored-id-spellings-with-no-sugar-are-refused-in-both-modes
  (testing "rf2-5r1af. The same law the shorthand guard enforces, reached
            without the shorthand: two spellings of one emitted attribute on
            one element is an ambiguity the grammar removes rather than
            ranks. Refused identically on the interpreted walk and in the
            compiled analyzer, through the diagnostics each already owns —
            no new always-on id."
    (doseq [body no-sugar-id-pairs]
      (let [ex (try (interpreted-tree body) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str (pr-str body) " is refused by the interpreted walk"))
        (when ex
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              (str (pr-str body) " — the walk's existing diagnostic, not a new id"))
          (is (str/includes? (ex-message ex) "twice")
              (str (pr-str body) " — the message says the id is spelled twice"))))
      (let [ex (try (compiled-tree body) nil (catch Exception e e))]
        (is (some? ex) (str (pr-str body) " is refused at compile time"))
        (when ex
          (is (= :rf.ui.compile/id-sugar-conflict
                 (:rf.ui.compile/error (ex-data (or (ex-cause ex) ex))))
              (str (pr-str body) " — reusing the existing compile diagnostic")))))))

(deftest an-id-slot-cardinality-rule-leaves-ordinary-attribute-pairs-alone
  (testing "The guard fires on the id SLOT alone. A lone qualified id keeps
            its authored name, and TWO spellings of an ordinary attribute —
            `:title` and `:x/title` — are accepted, because ordinary
            attributes stay in author space (rf2-drpa3.93) and this is an
            id-slot cardinality rule, not a namespace ban. Generalising it to
            every attribute was considered and DECLINED."
    (doseq [{:keys [note body tree]}
            [{:note "a lone qualified id with no sugar stays in author space"
              :body '[:div {:x/id "alias"}] :tree {:tag :div :attrs {:x/id "alias"}}}
             {:note ":title and :x/title on one element are two ordinary attributes, not a conflict"
              :body '[:div {:title "a" :x/title "b"}]
              :tree {:tag :div :attrs {:title "a" :x/title "b"}}}]]
      (is (= tree (interpreted-tree body)) (str note " — interpreted"))
      (is (= tree (compiled-tree body)) (str note " — compiled")))))

;; ---------------------------------------------------------------------------
;; The runtime v/spread seam — rf2-5r1af (the load-bearing caveat)
;; ---------------------------------------------------------------------------

(deftest a-spread-that-merges-to-two-ids-is-refused-at-the-shared-seam
  (testing "rf2-5r1af spread seam. `v/spread` returns a plain runtime MAP, so
            the id ambiguity is caught at `node/spread-attrs` — the ONE seam
            an interpreted body and a compiled lowering both call. A patch to
            the literal sites alone would leave the compiled spread accepting
            the pair, a NEW cross-mode split."
    (testing "the interpreted seam (v/spread itself)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"twice"
                            (v/spread {:id "a"} {:x/id "b"}))
          "two id spellings across the two maps")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"twice"
                            (v/spread {:id "a" :x/id "b"}))
          "or two spellings within one map")
      (is (map? (v/spread {:id "a"} {:id "b"}))
          "but two EXACT :id writes are later-arg-wins and merge to one — no conflict"))
    (testing "the compiled seam calls the very same fn from its lowering"
      (let [ex (try (compiled-tree '[:div (v/spread {:id "a"} {:x/id "b"})])
                    nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "the compiled spread refuses the pair at runtime")
        (when ex
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              "through the interpreted seam's diagnostic — a runtime map, so a runtime refusal"))))))

(deftest id-sugar-beside-a-spread-that-carries-an-id-is-refused
  (testing "rf2-5r1af probed defect. `#id` sugar wrote the element's id, and a
            forwarded map spells it again. Interpreted, the post-spread map
            reaches the element walk and the cardinality guard fires; a spread
            that itself merges to two ids is caught at the seam in BOTH modes,
            before the element is even built. The `v/spread` forms are
            UNQUOTED here so the call actually runs — a quoted one would be an
            inert list, not the map the seam sees."
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"twice"
                          (tree/render [:div#sugar (v/spread {:id "a"})]))
        "sugar plus a single forwarded id, interpreted")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"twice"
                          (tree/render [:div#sugar (v/spread {:id "a" :x/id "b"})]))
        "sugar plus a forwarded map that is itself two ids, interpreted")
    (let [ex (try (compiled-tree '[:div#sugar (v/spread {:id "a" :x/id "b"})])
                  nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "and compiled, the two-id spread map is refused at the shared seam")
      (when ex
        (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex))))))))

;; ---------------------------------------------------------------------------
;; The scope fence
;; ---------------------------------------------------------------------------

(def canonical-rows
  "The declarations the ruling must NOT have touched. A regression here
  would be worse than the bug being fixed: `:key`, `:class` and `:style`
  spelled exactly are the ordinary way every view in the corpus is written."
  [{:note "the exact :class still composes with the shorthand"
    :body '[:div.a.b {:class "c"}]
    :tree {:tag :div :attrs {:class "a b c"}}}

   {:note "the exact :style still normalizes through the CSS grammar"
    :body '[:div {:style {:color "red" :width 4}}]
    :tree {:tag :div :attrs {:style {:color "red" :width "4px"}}}}

   {:note "the exact :key is still lifted onto the node and is never an attribute"
    :body '[:div {:key "k"}]
    :tree {:tag :div :key "k"}}

   {:note "a shorthand with no authored class is untouched"
    :body '[:div.a.b]
    :tree {:tag :div :attrs {:class "a b"}}}

   {:note "and a namespaced ORDINARY attribute stays in author space, namespace intact"
    :body '[:div {:x/title "ok" :x/tab-index 3}]
    :tree {:tag :div :attrs {:x/title "ok" :x/tab-index "3"}}}])

(deftest the-canonical-spellings-are-unchanged
  (testing "The ruling is about the ALIAS shape only. `:key`, `:class` and
            `:style` written exactly keep precisely the behaviour they had,
            and a qualified key that projects onto an ORDINARY prop is not
            canonicalized at all — the structural tree carries authored
            names, so rewriting one would edit the tree for no gain."
    (doseq [{:keys [note body tree]} canonical-rows]
      (is (= tree (interpreted-tree body)) (str note " — interpreted"))
      (is (= tree (compiled-tree body)) (str note " — compiled")))))
