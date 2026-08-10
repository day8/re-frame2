(ns negative
  "NEGATIVE FIXTURES — correct code that RESEMBLES each mistake, and must
  produce no Hicasso finding at all (rf2-hic-022).

  This is the half of the suite that decides whether the lint layer is worth
  shipping. A rule with only positive fixtures fires on valid code and
  nobody notices until it is annoying people, so every section below is
  written to sit as close to its counter-example as correct code can get:
  the merge that IS a map, the read inside a fn literal that runs during the
  body rather than after it, the mapped child that carries its `:key`, the
  button named four different legal ways, the head that is an ordinary view.

  Several forms here are silent because the check DECLINED to guess — a
  `for` whose body is a call, an element whose props are a symbol. That
  silence is the design, not a gap: see the export's README for what each
  check refuses to know."
  (:require [re-frame.hicasso :as h :refer [sub use-subs]]))

(declare row-view sanitize icon-node props)

;; A protocol and a multimethod for the method-parameter rows at the end of
;; this file. They are declared here rather than borrowed because a method
;; parameter that is a FUNCTION is what makes calling it ordinary code rather
;; than a mistake, and `Object`'s only method takes nothing but `this`.
(defprotocol Applies
  (apply-to [this f]))

(defmulti dispatch-on :kind)

;; ---------------------------------------------------------------------------
;; An EVENT VECTOR is not an element. `[:button]` at a prop and the element
;; `[:button]` are the same characters; only POSITION tells them apart, so no
;; element check looks inside a props map.
;; ---------------------------------------------------------------------------

(h/defview event-vectors-that-look-like-elements [_]
  [:div
   [:span {:on-mouse-enter [:button]} "hover"]
   [:button {:on-click [:a]} "Save"]
   [:button {:on-click [:a :with :args]} "Save with args"]])

;; ---------------------------------------------------------------------------
;; deferred-read — a read inside a fn literal that runs DURING the body
;; ---------------------------------------------------------------------------

;; `mapv` calls its fn during this body, so these reads are this boundary's
;; edges. An "fn literal inside a body" is NOT evidence of deferral.
(h/defview reads-inside-a-mapv [{:keys [ids]}]
  [:ul (mapv (fn [id] [:li {:key id} (sub [:todo/label id])]) ids)])

(h/defview reads-inside-a-for [{:keys [ids]}]
  [:ul (for [id ids] [:li {:key id} (sub [:todo/label id])])])

;; A callback that dispatches is exactly what the callback form is for.
(h/defview dispatch-inside-a-callback [_]
  [:button {:on-click (h/hfn [e] [:form/submit (.-value (.-target e))])}
   "Save"])

;; A read at the top of a body, then used inside a callback, is correct: the
;; READ happened during the body and the callback closes over its value.
(h/defview read-then-close-over-it [_]
  (let [id (sub [:todo/current])]
    [:button {:on-click (h/hfn [_e] [:todo/toggle id])} "toggle"]))

;; ---------------------------------------------------------------------------
;; parked-read — a mutable reference carrying anything but a parked read
;; ---------------------------------------------------------------------------

(h/defview reset-of-a-plain-value [{:keys [cache]}]
  (reset! cache (sub [:todo/current]))
  [:div "fine"])

(h/defview reset-of-a-delay-that-reads-nothing [{:keys [cache]}]
  (reset! cache (delay (sanitize "x")))
  [:div "fine"])

(h/defview reset-of-a-closure-that-reads-nothing [{:keys [cache]}]
  (reset! cache (fn [] (sanitize "x")))
  [:div "fine"])

;; ---------------------------------------------------------------------------
;; unkeyed-mapped-child — the keyed spellings, and the ones we cannot judge
;; ---------------------------------------------------------------------------

(h/defview keyed-for [{:keys [items]}]
  [:ul (for [item items] [:li {:key (:id item)} (:label item)])])

(h/defview keyed-map-fn-literal [{:keys [items]}]
  [:ul (map (fn [item] [:li {:key (:id item)} (:label item)]) items)])

;; The body is a CALL, so what it returns and whether it carries a key are
;; both unknown here. Silent by design.
(h/defview mapped-call [{:keys [items]}]
  [:ul (for [item items] (row-view item))])

;; A SYMBOL at position 1 may evaluate to a props map carrying the key, or
;; may be a child. The codec decides that at runtime with `map?` and this
;; cannot, so the commonest missing-key spelling of all is deliberately
;; silent. Being right is worth more than the catch.
(h/defview mapped-child-with-dynamic-position-1 [{:keys [items]}]
  [:ul (for [item items] [:li item])])

;; `#(…)` as the mapping function: the element expression is not written at
;; a fixed position, so the check declines rather than guessing at `%`.
(h/defview mapped-by-an-anonymous-shorthand [{:keys [items]}]
  [:ul (mapv #(vector :li {:class "row"} %) items)])

;; A head that is a view var takes its key the same way; the check reads the
;; props map, not the tag.
(h/defview keyed-view-children [{:keys [items]}]
  [:ul (for [item items] [row-view {:key (:id item) :item item}])])

;; Not children at all — a mapped seq of ordinary data.
(h/defview mapped-data [{:keys [items]}]
  [:div (str (mapv :id items))])

;; ---------------------------------------------------------------------------
;; nameless-interactive-element — every legal way to name one
;; ---------------------------------------------------------------------------

(h/defview named-buttons [_]
  [:div
   [:button {:on-click [:a]} "Save"]
   [:button {:aria-label "Close"  :on-click [:b]}]
   [:button {:aria-labelledby "panel-title"}]
   [:button {:title "Help"}]
   [:button.icon {:on-click [:c]} "Delete"]
   ;; A child we cannot judge — it may well render text.
   [:button {:on-click [:d]} icon-node]
   ;; Dynamic props: the name may be in there.
   [:button props]
   [:a {:href "/help"} "Help"]
   ;; An <a> with NO href is not a link and not focusable, so it needs no
   ;; accessible name. The tag set and this condition are the compiled
   ;; substrates' (`re-frame.ui.compiler.a11y`), not invented here.
   [:a {:name "section-3"}]
   [:a {:class "anchor-target"}]])

;; ---------------------------------------------------------------------------
;; function-in-head-position — ordinary heads, and fn literals at PROP
;; positions, which are legal everywhere
;; ---------------------------------------------------------------------------

(h/defview ordinary-heads [{:keys [item]}]
  [:div
   [row-view {:item item}]
   [:<> [:span "fragment"]]
   [:> js/Fragment {} [:span "raw"]]])

(h/defview fn-literals-at-prop-positions [_]
  [:div {:ref (fn [node] (some-> node .focus))}
   [:input {:on-change #(js/console.log %)}]
   [:button {:on-click (h/hfn [_e] [:save])} "Save"]])

;; A vector of functions is ordinary data, not hiccup: the head is a symbol.
(h/defview a-vector-of-callbacks [_]
  (let [handlers [(fn [] :a) (fn [] :b)]]
    [:div (str (count handlers))]))


;; ---------------------------------------------------------------------------
;; A qualified keyword IS a tag -- the runtime reads it through `name` -- so
;; these are ordinary non-interactive elements rather than exemptions.
;; ---------------------------------------------------------------------------

(h/defview qualified-keyword-heads [{:keys [rows]}]
  [:div
   [:app/row {:id 1}]
   (for [r rows] [:app/cell {:key (:id r)} r])])

;; Quoted hiccup is data an author wrote down, not hiccup the runtime will
;; interpret, so nothing judges inside a quotation.
(h/defview quoted-hiccup [_]
  [:div (str '[(fn [] :a) "quoted, so never interpreted"])])

;; ---------------------------------------------------------------------------
;; direct-view-call — a LOCAL that happens to be spelled like the view
;;
;; The self-call check knows exactly one name: the view's own. A parameter, a
;; destructured prop, a `let`, a `for` binding or a function argument spelled
;; the same way is an ORDINARY LOCAL, and calling one is ordinary Clojure. The
;; first draft of the check compared SPELLING and nothing else, so every form
;; below was reported at ERROR — and because the required lane runs
;; `--fail-level error`, correct code was refused (merged-PR audit #7794).
;; ---------------------------------------------------------------------------

;; A parameter shadows the view for the whole body.
(h/defview card [card]
  [:div.card (card)])

;; Destructuring binds exactly the same way.
(h/defview label [{:keys [label]}]
  [:div (label)])

;; So does a `let` ...
(h/defview via-let [_]
  (let [via-let (fn [] "ordinary")]
    [:div (via-let)]))

;; ... a `for` binding, where `(cell :label)` is an ordinary map lookup ...
(h/defview cell [{:keys [cells]}]
  [:ul (for [cell cells] [:li {:key (:id cell)} (cell :label)])])

;; ... the parameter of a function literal ...
(h/defview row [{:keys [rows]}]
  [:ul (mapv (fn [row] [:li {:key (row :id)} (row :label)]) rows)])

;; ... the parameter of the callback form ...
(h/defview event [_]
  [:button {:on-click (h/hfn [event] [:form/submit (event :value)])} "Save"])

;; ... and a `letfn` name.
(h/defview badge [{:keys [n]}]
  (letfn [(badge [x] (str "#" x))]
    [:span (badge n)]))

;; A `catch` binding and an `as->` name are locals too. Contrived — nobody
;; names a caught value after a view — but a rule with a hole in its notion of
;; "bound" is a rule that fires on correct code, so the hole is closed and
;; witnessed rather than argued about.
(h/defview retry [{:keys [attempt]}]
  [:div
   (try (attempt) (catch :default retry (retry)))
   (as-> attempt retry (retry))])

;; ---------------------------------------------------------------------------
;; direct-view-call — the `letfn` GRAMMAR, one row per production
;;
;; The row above drives a `letfn` NAME, and the repair that added it claimed
;; `letfn` parameters generally. It had read a fnspec as `[nm params]` and
;; collected `params` only where that second node was a VECTOR — true of the
;; single-arity spelling and of nothing else, because a MULTI-ARITY fnspec's
;; second node is the first arity LIST. No arity's parameters were read at all,
;; so every use of one reported at ERROR and clj-kondo exited 3 (merged-PR
;; audit #7804). The claim was wider than the one shape the witness drove.
;;
;; So these rows come from the GRAMMAR rather than from the repair. A fnspec is
;; a `fn` tail — `letfn` expands each one to `(fn nm …)` — and that grammar is
;;
;;   fnspec => (name [params*] prepost-map? exprs*)
;;           | (name ([params*] prepost-map? exprs*)+)
;;
;; every production of which is written out below, with the local spelled like
;; the enclosing view. Not driven, and deliberately: a `&` REST name in head
;; position, because a rest seq is never a legal callee, so there is no correct
;; program to protect.
;; ---------------------------------------------------------------------------

;; Multi-arity — the shape the audit reproduced.
(h/defview arity-lists [_]
  (letfn [(apply-it
            ([arity-lists] (arity-lists))
            ([arity-lists x] (arity-lists x)))]
    [:div (apply-it (fn [& _] "ok"))]))

;; A prepost map sits exactly where a beginner's eye expects the next arity, so
;; a walker that counts positions rather than reading them goes wrong here.
(h/defview arity-prepost [_]
  (letfn [(guarded
            ([arity-prepost] {:pre [(some? arity-prepost)]} (arity-prepost))
            ([arity-prepost x] (arity-prepost x)))]
    [:div (guarded (fn [& _] "ok"))]))

;; Destructuring inside an arity list binds the same names it binds anywhere.
(h/defview arity-destructured [_]
  (letfn [(unpack
            ([{:keys [arity-destructured]}] (arity-destructured))
            ([{:keys [arity-destructured] :as m} x]
             (str (arity-destructured x) m)))]
    [:div (unpack {:arity-destructured (fn [& _] "ok")})]))

;; A variadic arity beside a fixed one: the FIXED parameters of the variadic
;; arity are ordinary locals, `&` notwithstanding.
(h/defview arity-variadic [_]
  (letfn [(spread
            ([arity-variadic] (arity-variadic))
            ([arity-variadic & xs] (arity-variadic (count xs))))]
    [:div (spread (fn [& _] "ok")) (spread (fn [& _] "ok") 1 2)]))

;; An arity can bind nothing at all, and an EMPTY parameter vector beside a
;; binding one must not stop the binding one being read.
(h/defview zero-arity [_]
  (letfn [(maybe
            ([] "ok")
            ([zero-arity] (zero-arity)))]
    [:div (maybe) (maybe (fn [] "also ok"))]))

;; `fn` has no docstring, so a leading string is an ordinary body expression —
;; and an expression sitting where the next arity might have gone.
(h/defview string-first [_]
  (letfn [(described
            ([string-first] "one argument" (string-first))
            ([string-first x] "two arguments" (string-first x)))]
    [:div (described (fn [& _] "ok"))]))

;; Several fnspecs, mutually recursive. A binding in ANY fnspec silences the
;; whole `letfn`, so only the multi-arity one binds the name here — otherwise
;; the row would pass on its neighbour's coverage rather than its own.
(h/defview mutual [_]
  (letfn [(evens [f n] (if (zero? n) (f) (odds f (dec n))))
          (odds
            ([mutual] (mutual))
            ([mutual n] (evens mutual n)))]
    [:div (evens (fn [& _] "ok") 2)]))

;; And a multi-arity fnspec nested inside another fnspec's body.
(h/defview nested [_]
  (letfn [(outer [x]
            (letfn [(inner
                      ([nested] (nested))
                      ([nested y] (nested y)))]
              (str (inner (fn [& _] x)) (inner (fn [& _] x) 1))))]
    [:div (outer "ok")]))

;; ---------------------------------------------------------------------------
;; direct-view-call — the binding forms that are neither `let`-shaped nor a
;; `fn` tail
;;
;; The rows above drive the roster. A ROSTER is also the whole defect: the hook
;; has no scope table, so it recognises a binding form by NAME, and a local
;; introduced by a form nobody wrote down is not seen — so calling it reports,
;; at ERROR, which stops the build. Six such forms were measured live at the CI
;; pin after the `letfn` repair landed (rf2-ka4d).
;;
;; So, once more, from the GRAMMAR rather than from the repair: one row per
;; production of each form, with the local spelled like the enclosing view and
;; nothing else in that row bound, so that no row passes on a neighbour's
;; coverage.
;; ---------------------------------------------------------------------------

;; `(dotimes [name n] body*)` is the whole grammar — one pair, the target a
;; symbol, no second production. It is the one row here that no correct program
;; reaches: a counter is an integer, and calling one throws whatever the linter
;; says. The hole is closed anyway, because the roster it was missing from is
;; otherwise the COMPLETE `let`-shaped family, and a hole in a roster is what
;; all of this is made of.
(h/defview tick [_]
  (dotimes [tick 3] (tick))
  [:div "ticked"])

;; `(this-as name body*)`, and `this` in a JS callback may perfectly well be a
;; function — that is what makes this one a program somebody writes.
(h/defview handler [_]
  [:div {:ref (fn [] (this-as handler (handler)))}])

;; A `reify` method is a `fn` tail with its name in front, exactly as a `letfn`
;; fnspec is, and its parameters are ordinary locals.
(h/defview boxed [_]
  [:div (str (reify Applies (apply-to [_ boxed] (boxed))))])

;; The binding method in the SECOND protocol group, behind another method and
;; behind a bare protocol NAME: a reader that stops at the first spec, or that
;; mistakes a token for a method, does not reach this one.
(h/defview second-group [_]
  [:div (str (reify
               Object  (toString [_] "first")
               Applies (apply-to [_ second-group] (second-group))))])

;; `specify!` takes an ordinary EXPRESSION where the others take a name, and
;; that expression is written as a LIST here on purpose: it sits exactly where
;; a method sits, and reading it as one is the mistake available.
(h/defview marked [_]
  [:div (str (specify! (js-obj) Applies (apply-to [_ marked] (marked))))])

;; `extend-type` names a type and then the same method lists.
(h/defview extended [_]
  (extend-type string Applies (apply-to [_ extended] (extended)))
  [:div "extended"])

;; `extend-protocol` inverts that — one protocol, then a type before each
;; group — so the binding method here follows both a token and another method.
(h/defview spread-out [_]
  (extend-protocol Applies
    string (apply-to [_ f] (f))
    number (apply-to [_ spread-out] (spread-out)))
  [:div "spread"])

;; `(defmethod multifn dispatch-val & fn-tail)`, first production: the tail
;; written flat. A `defmethod` inside a body is unusual code, and it is the
;; only place a hook can see one — a top-level `defmethod` is not inside any
;; view, so nothing here ever reads it.
(h/defview run-it [_]
  (defmethod dispatch-on :fn [run-it] (run-it))
  [:div "registered"])

;; Second production: the tail written as arity lists. Both arities bind, and
;; both must go quiet.
(h/defview run-any [_]
  (defmethod dispatch-on :any
    ([run-any] (run-any))
    ([run-any x] (run-any x)))
  [:div "registered"])

;; ---------------------------------------------------------------------------
;; parked-read / deferred-read — a READING DOOR that a local has taken
;;
;; `:refer [sub]` leaves an ordinary simple symbol behind, and a local may be
;; named `sub` like anything else. `api/resolve` answers with the var either
;; way — it reads the ns form and never sees locals — so all three read rules
;; reported somebody's own function as a subscription read (rf2-c6t6). Warning
;; level, unlike the rows above, which is why this half is smaller: a spurious
;; warning is noise, and a spurious error stops a programmer working.
;;
;; The doors are referred here, and read once unshadowed, so that the shadowed
;; rows below are shadowing something real.
;; ---------------------------------------------------------------------------

(h/defview referred-doors [_]
  [:div (sub [:todo/current]) (str (use-subs {:x [:todo/current]}))])

(h/defview shadowed-parked-read [{:keys [cache]}]
  (let [sub (fn [_] "ordinary")]
    (reset! cache (delay (sub [:x]))))
  [:div "fine"])

(h/defview shadowed-deferred-read [_]
  (let [sub (fn [_] "ordinary")]
    (js/setTimeout (fn [] (sub [:x])) 100))
  [:div "fine"])

;; The callback form's own PARAMETER is a binding like any other, and it is
;; outside the body the check walks.
(h/defview shadowed-in-a-callback [_]
  [:button {:on-click (h/hfn [sub] [:todo/toggle (sub :value)])} "Save"])

;; The second door shadows the same way.
(h/defview shadowed-use-subs [{:keys [cache]}]
  (let [use-subs (fn [_] {:x 1})]
    (reset! cache (delay (use-subs {:x [:y]}))))
  [:div "fine"])
