(ns re-frame.freehand.pilot-react-interop-cljs-test
  "F5i, the HEADLESS half — which host shapes a React-library adopter can
  actually reach, and what the substrate says when they cannot.

  The pilot's subject is the first question every adopter asks: *can I use a
  third-party React component?* Freehand answers with a small set of named
  host shapes rather than a general escape hatch, and the honest finding of
  this pilot is that only ONE of them is reachable in this tree. So the
  suite below is deliberately two-sided:

    * the shape that EXISTS is exercised as an application author would —
      declared, mounted, commanded, and read back off the structural tree;
    * every shape that does NOT exist is asserted at its REFUSAL, verbatim,
      so the day the slice lands these tests fail and this pilot has to be
      rewritten instead of quietly continuing to pass.

  The mounted half is `pilot-react-interop-dom-cljs-test`: a real
  `react-dom/client` commit, a real `@xyflow/react` graph, and exact
  retained-instance counts after teardown."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.pilot-react-interop :as pilot]
            [re-frame.freehand.pilot-react-interop-compiled :as compiled]
            #?(:clj [re-frame.freehand.compiler :as compiler])
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ===========================================================================
;; Harness
;; ===========================================================================

(def ^:private fid :rf/default)

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (pilot/reset-ledger!))}))

(defn- seed! [db] (frame/replace-app-db! fid db))
(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))

(defn- render!
  "Render `form` structurally under a render candidate.

  The `cell/candidate` + `cell/with-capture` pair is INTERNAL, and reaching
  for it is a finding this pilot inherits rather than discovers: `t/render`
  is the blessed public structural verb and it opens no candidate, so it
  cannot render any view that calls `v/sub` — which is every view in this
  file that reads state. Recorded as such in the PR body, reproduced
  verbatim by [[t-render-alone-still-cannot-render-a-state-reading-view]],
  and already filed."
  [form]
  (cell/with-capture (cell/candidate (cell/cell :acme/probe) fid)
    (fn [] (t/render form))))

(defn- caught
  "The `ex-data` of the diagnostic `thunk` raised, or nil when it returned
  normally. The whole ex-data, because the interesting half of a refusal here
  is what it NAMES."
  [thunk]
  (try (thunk) nil (catch #?(:clj Throwable :cljs :default) e (ex-data e))))

(defn- message
  "The human sentence of the diagnostic `thunk` raised, or nil."
  [thunk]
  (try (thunk) nil (catch #?(:clj Throwable :cljs :default) e (ex-message e))))

(defn- has? [s sub] (and (string? s) (str/includes? s sub)))

(defn- behavior-nodes
  [tree]
  (t/find-all tree #(and (map? %) (= :re-frame.freehand/behavior (:view-id %)))))

;; ===========================================================================
;; SHAPE 1 and 2 — the qualified host leaf, and the explicit React wrapper
;; ===========================================================================
;;
;; These are the same finding twice, and it is the pilot's headline. A React
;; component with value props (React-Vega, AG Grid, a Radix primitive) is a
;; qualified LEAF; a React-owned protocol (hooks, context, `asChild` cloning)
;; is an explicit WRAPPER. Both are foreign components, so both enter a
;; Freehand tree at the third legal vector head — the declared host
;; descriptor. There is no fourth door.
;;
;; The classifier already KNOWS about that head. What is missing is
;; everything behind it: no public verb mints a host descriptor, and both
;; emitters refuse to cross one. So an adopter's very first integration
;; attempt does not fail with "you spelled it wrong" — it fails with "this
;; lands later", which is the right diagnostic and a hard stop.

(deftest the-foreign-component-head-classifies-but-cannot-be-crossed
  (testing "the third legal vector head is REAL — `classify-head` answers
            `:host` for a host descriptor, so a foreign component is a
            first-class citizen of the grammar and not an unclassified
            value. Everything that would make it USEFUL is what is absent."
    (is (= :host (descriptor/classify-head pilot/foreign-leaf))
        "a host descriptor classifies, totally, today")
    (is (true? (descriptor/host-descriptor? pilot/foreign-leaf)))))

(deftest a-react-component-cannot-enter-a-freehand-tree-today
  (testing "The obvious first integration — a chart component with value
            props at a vector head — is REFUSED on the structural host, and
            the refusal names the slice that will lift it rather than
            pretending the spelling was wrong. This is the pilot's headline
            finding: two of the four host shapes are unreachable, and they
            are the two an adopter reaches for first."
    (let [d (caught #(t/render [pilot/chart-as-a-leaf {}]))
          m (message #(t/render [pilot/chart-as-a-leaf {}]))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
          "the structural emitter refuses a host descriptor")
      (is (has? m "host descriptor")
          "and says what it refused")
      (is (has? m "host-lifecycle slice")
          "and names the slice that lands it — a hard stop, not a typo"))))

(deftest no-public-verb-mints-a-foreign-component-boundary
  (testing "There is no way to build the value the classifier recognises.
            `pilot/foreign-leaf` is a hand-written map carrying the reserved
            `:re-frame.freehand/host` marker — the pilot reaching PAST the
            door on purpose. The door itself publishes nothing that mints
            one, so an adopter cannot even construct the thing that would
            then be refused."
    #?(:clj
       (let [publics (set (map name (keys (ns-publics (find-ns 're-frame.freehand)))))]
         (is (not (contains? publics "host"))
             "no v/host")
         (is (not (contains? publics "react"))
             "no v/react")
         (is (empty? (filter #(has? % "host") publics))
             (str "nothing on the door mints or names a host boundary; got "
                  (pr-str (sort publics)))))
       :cljs
       (is true "the door's public roster is enumerated on the JVM arm"))))

;; ===========================================================================
;; SHAPE 4 — the outward bridge `v/->react`
;; ===========================================================================
;;
;; The AG-Grid-class case: a library that wants a COMPONENT VALUE, not an
;; element (`{:cellRenderer …}`). D014 rules the spelling `v/->react`; F5c is
;; the slice. Neither has landed, so this integration cannot be attempted at
;; all — and unlike the leaf there is not even a refusal to meet, because
;; there is no call to make.

(deftest the-outward-bridge-does-not-exist-yet
  (testing "`v/->react` is D014's ruled spelling for handing a declared view
            to a React library that takes a component value. It is not on the
            door. An adopter integrating AG Grid's `cellRenderer` today
            writes a hand-rolled UIx/Helix wrapper — which is exactly the
            repetition D014 exists to remove. This assertion FAILS the day
            F5c lands, which is the point of writing it."
    #?(:clj
       (let [publics (set (map name (keys (ns-publics (find-ns 're-frame.freehand)))))]
         (is (not (contains? publics "->react"))
             "v/->react is absent — F5c has not landed"))
       :cljs
       (is true "the door's public roster is enumerated on the JVM arm"))))

;; ===========================================================================
;; SHAPE 3 — the registered behavior, which DOES exist
;; ===========================================================================
;;
;; The one shape an adopter can use today, and — for the class of library it
;; was designed for — a genuinely good one. A SpreadJS-class grid is opaque,
;; mutable, listener-owning host state over one node, which is the behavior's
;; exact description.

(deftest the-behavior-use-site-is-inert-data-on-the-structural-host
  (testing "Everything the integration declares is DATA in the tree: the
            registered id, the caller-authored semantic target, and the
            config verbatim. A tool, a catalogue and a structural test read
            the integration without holding one line of its implementation —
            and on this host nothing connects, so the library's own ledger
            has to still be at zero."
    (seed! {:invoice {:rows [["Widget" 10] ["Gasket" 4]]}})
    (let [tree (render! [pilot/invoice-sheet {}])
          [b]  (behavior-nodes tree)]
      (is (some? b) "the use site renders as a behavior boundary")
      (is (= :re-frame.freehand.pilot-react-interop/sheet (:use (t/attrs b)))
          "carrying the REGISTERED id, not the implementation")
      (is (= :invoice/sheet (:target (t/attrs b)))
          "and the semantic id a command will address it by")
      (is (= {:rows [["Widget" 10] ["Gasket" 4]] :read-only? false}
             (:config (t/attrs b)))
          "and the config verbatim — the sub's value, recorded as data")
      (is (= :div (:tag (first (:children b))))
          "over exactly one element — the node the widget will own")
      (is (= {:instances 0 :listeners 0 :constructed 0 :destroyed 0}
             (pilot/ledger-snapshot))
          "and NOTHING connected: a structural render performs no host work"))))

(deftest a-behavior-lives-inside-an-ordinary-freehand-page
  (testing "The boundary is around the NODE, not around the region. The
            toolbar above the grid is ordinary Freehand markup with ordinary
            event intent, and it sits in the same tree as the opaque host —
            which is what makes the behavior shape composable rather than an
            island."
    (seed! {:invoice {:rows [["a" 1]]}})
    (let [tree   (render! [pilot/invoice-page {}])
          button (t/find tree #(= :button (:tag %)))]
      (is (= [:invoice/export-requested ","] (:on-click (t/attrs button)))
          "ordinary event intent, as data, beside an opaque host")
      (is (= 1 (count (behavior-nodes tree)))
          "and exactly one host boundary in the page"))))

(deftest a-command-is-data-an-ordinary-handler-returns
  (testing "The imperative operation an application performs on the widget —
            export — never appears in a view. It is one reserved effect a
            plain `reg-event` handler returns, addressed by the semantic id
            the use site authored. On the structural host there is no live
            connection, so the command performs no host work.

            THREE things an adopter has to discover the hard way, and all
            three are reported as findings:

            1. An fx refusal does NOT propagate out of `dispatch-sync` —
               re-frame catches it and routes it to the always-on error axis.
               The public seam for observing one is
               `rf/register-listener! :errors`.
            2. The record that arrives there is NOT typed as a command
               refusal. `:re-frame.freehand.host/command` is a registrar fx,
               so its typed diagnostic is flattened to the generic
               `:rf.error/fx-handler-exception` and the real id has to be
               dug out of the record's `:exception`. An operator surface
               that wanted to show `your command was refused, and why` has to
               know to unwrap.
            3. On the JVM there is no refusal to observe AT ALL. The command
               fx is registered `{:platforms #{:client}}`, so the structural
               host SKIPS it (a `:rf.fx/skipped-on-platform` trace) and the
               JVM refusal `command!` carries is never reached through the
               documented `{:fx [[…]]}` path. Spec 004 §The structural marker
               says a command there `is refused with the same channel
               diagnostic`; through the data path it is silently skipped."
    (is (some? (rf/handler-meta :fx :re-frame.freehand.host/command))
        "the command channel is an ordinary registered effect")
    (let [seen (atom [])]
      (rf/register-listener! :errors ::commands
                             (fn [rec] (swap! seen conj rec)))
      (try
        (send! [:invoice/export-requested ","])
        (is (= {:instances 0 :listeners 0 :constructed 0 :destroyed 0}
               (pilot/ledger-snapshot))
            "a command with no live connection performs NO host work")
        (let [ids   (mapv :error @seen)
              inner (mapv #(:rf.error/id (ex-data (:exception %))) @seen)]
          #?(:cljs
             (do
               (is (= [:rf.error/fx-handler-exception] ids)
                   (str "the error axis sees the GENERIC fx wrapper, not the "
                        "command's own id — finding (2) above; got " (pr-str ids)))
               (is (= [:rf.error/behavior-command-refused] inner)
                   (str "and the typed refusal is one unwrap deeper, inside the "
                        "record's :exception; got " (pr-str inner))))
             :clj
             (is (empty? ids)
                 (str "on the STRUCTURAL host the client-only fx is skipped, so "
                      "there is no refusal record to observe — finding (3) above; "
                      "got " (pr-str ids)))))
        (finally
          (rf/unregister-listener! :errors ::commands))))))

(deftest the-config-rule-forbids-passing-a-component-through-the-use-site
  (testing "This is the constraint that shapes every React integration built
            on a behavior, and it is worth stating plainly because it is not
            obvious until you hit it: `:config` is data all the way down, so
            a React component — a function — cannot travel through it. There
            is therefore NO generic `[v/behavior {:use react-host :config
            {:component SomeComponent}}]`; every foreign component needs its
            own `v/defbehavior` registration that names it.

            That reads as a limitation and is mostly not one — a component's
            props ARE data, and one named place to map data to props is
            where you want it. But it is a real design consequence and an
            adopter meets it on day one."
    (let [m (message
              #(t/render [v/behavior
                          {:use pilot/sheet :config {:component (fn [] nil)}}
                          [:div.sheet-host]]))]
      (is (has? m "DATA through and through")
          (str "a component value in :config is refused; got " (pr-str m)))
      (is (has? m "preconstructed host instance")
          "and the diagnostic names exactly this mistake"))))

(deftest an-opaque-host-refuses-freehand-children
  (testing "A chart or grid owns every descendant of its node. Declaring
            `{:opaque true}` turns 'children here are silently overwritten'
            into a refusal at the use site — which is the correct trade, and
            it is also the mechanism that makes the nested-React-root
            workaround one-way: no Freehand content can be interleaved into
            a foreign component's subtree."
    (let [m (message #(t/render [pilot/opaque-host-with-children {}]))]
      (is (has? m "opaque") (str "got " (pr-str m)))
      (is (has? m "no Freehand children")))))

(deftest a-behavior-cannot-wrap-a-declared-view
  (testing "A behavior's child is ONE element. An adopter who reads
            'behaviors are how you integrate a component' and tries to wrap a
            declared view in one meets a refusal naming the rule, not a
            silent no-op."
    (let [m (message #(t/render [pilot/behavior-over-a-view {}]))]
      (is (has? m "ONE element") (str "got " (pr-str m))))))

;; ===========================================================================
;; The integration that needed NO host shape at all
;; ===========================================================================
;;
;; A headless table core (TanStack Table's class) is a pure function: options
;; and state in, a row model out. Its React adapter exists only to hold the
;; state and re-run the computation when it moves — which is what re-frame
;; already is. So the integration is a `reg-sub` and ordinary markup, and the
;; correct amount of new substrate surface for it is zero.
;;
;; Reporting a non-gap is as much the pilot's job as reporting a gap: a
;; substrate that grew a `v/use-headless-library` for this would be worse.

(deftest a-headless-library-needs-no-host-shape
  (testing "The row model is computed by a framework-free core inside an
            ordinary subscription, and the rows it answers are ordinary
            Freehand markup. There is no behavior, no leaf, no wrapper and no
            bridge in the resulting tree — and there is nothing missing."
    (seed! {:ledger {:rows [{:id 1 :name "Zoe" :owed 5}
                            {:id 2 :name "Ada" :owed 9}]
                     :sort {:sort-by-key :name}}})
    (let [tree (render! [pilot/headless-table {}])
          rows (t/find-all tree #(= :tr (:tag %)))]
      (is (empty? (behavior-nodes tree))
          "no host boundary — the integration needed none")
      (is (= 3 (count rows)) "a header row and two data rows")
      (is (= ["Ada" "9"] (mapv t/text (:children (nth rows 1))))
          "sorted by the core, rendered by Freehand")
      (is (= [:ledger/sorted :name]
             (:on-click (t/attrs (t/find tree #(= :button (:tag %))))))
          "and the sort control is ordinary event intent"))))

(deftest the-headless-core-is-the-same-value-on-both-hosts
  (testing "Nothing about the core is host-bound, which is why it needs no
            shape: the same call answers the same value on the JVM and in
            ClojureScript, so a structural test of a table is a real test."
    (let [model (pilot/core-row-model
                  {:columns [{:key :name :label "Name"}]
                   :data    [{:id 1 :name "b"} {:id 2 :name "a"}]}
                  {:sort-by-key :name})]
      (is (= [{:key :name :label "Name"}] (:header model)))
      (is (= [2 1] (mapv :id (:rows model)))
          "sorted rows, in the order the core decided")
      (is (= "a" (-> model :rows first :cells first :value))))))

;; ===========================================================================
;; The COMPILED arm — newly answerable, and the answer is two-sided
;; ===========================================================================
;;
;; Compiled views became browser-mountable, so `can a compiled page host a
;; React library?` stopped being academic. It has two halves and the pilot
;; reports both.

#?(:clj
   (defn- compile-body
     "Run the compiled front end over `body`, as the macro does, and answer
     the diagnostic it is refused with — or `::accepted`."
     [body]
     (try
       (compiler/compile-structural-view
         {:form            (list 'v/defview 'subject '[props] body)
          :menv            nil
          :ns-sym          're-frame.freehand.pilot-react-interop-cljs-test
          :vname           'subject
          :view-id         ::subject
          :params          '[props]
          :body            [body]
          :children-policy :optional})
       ::accepted
       (catch clojure.lang.ExceptionInfo ex
         (assoc (select-keys (ex-data ex) [:rf.ui.compile/error :op :recovery])
                :message (ex-message ex))))))

#?(:clj
   (deftest a-compiled-body-cannot-attach-the-one-reachable-host-shape
     (testing "The compiled tier refuses `[v/behavior …]` at BUILD time. So
               the ONLY host shape Freehand offers today is unavailable in
               the compiled mode, and any view that owns a chart, a grid or
               an editor is interpreted-forever — no promotion, ever, for
               precisely the views whose neighbours most want it.

               The refusal also MISNAMES what it refused. `v/behavior` is a
               FRAMEWORK-supplied boundary declared on the public door, and
               the analyzer classifies it `:op :foreign` — `a foreign
               component boundary`. An author reading that goes looking for
               the third-party component they did not write. Two sibling
               framework boundaries declared the ordinary way, `v/route-link`
               and `v/markup`, compile without comment, which is what makes
               the misclassification legible as one."
       (let [d (compile-body '[v/behavior {:use :x/y :target :t} [:div]])]
         (is (= :rf.ui.compile/unsupported-form (:rf.ui.compile/error d))
             (str "the compiled grammar refuses the attachment; got " (pr-str d)))
         (is (= :foreign (:op d))
             "classified as a FOREIGN component — the misnaming")
         (is (str/includes? (:message d) "foreign component boundary")
             "and the sentence says so to the author")
         (is (= :extract-declared-child (first (:recovery d)))
             "naming the recovery this pilot then takes"))
       (is (= ::accepted (compile-body '[v/route-link {:to :x} "t"]))
           "a sibling FRAMEWORK boundary compiles without comment")
       (is (= ::accepted (compile-body '[v/markup {:value nil}]))
           "and so does the other one"))))

(deftest a-compiled-parent-can-contain-the-interpreted-integration
  (testing "The refusal's own recovery works, and this is the half worth
            saying as plainly as the gap: a COMPILED page mounts the
            interpreted view that owns the React library as an ordinary
            declared child. The hot markup is lowered, the integration is
            not, and the boundary between them is one line of source.

            The manifest MARKS the crossing rather than quietly claiming the
            subtree — so `where does the compiled tier stop` is a fact a tool
            can read, not something an author has to remember."
    (seed! {:invoice {:rows [["Widget" 10]]}})
    (let [m (v/manifest compiled/hot-list)]
      (is (some? m) "the page really is compiled")
      (is (= :re-frame.freehand/v1 (:grammar m)))
      (is (= [{:view-id :re-frame.freehand.pilot-react-interop/invoice-sheet
               :lowering :interpreted}]
             (mapv #(select-keys % [:view-id :lowering]) (:crossings m)))
          (str "one crossing, MARKED interpreted — the integration; got "
               (pr-str (:crossings m)))))
    (let [tree (render! [compiled/hot-list {:title "Invoices"}])
          [b]  (behavior-nodes tree)]
      (is (= "Invoices" (t/text (t/find tree #(= :h1 (:tag %)))))
          "the compiled parent's own markup rendered")
      (is (some? b) "and the interpreted child's host boundary is in the tree")
      (is (= :invoice/sheet (:target (t/attrs b)))
          "carrying the same semantic target it has interpreted"))))

(deftest the-headless-integration-promotes-but-not-in-one-line
  (testing "The library that needed no host shape in the interpreted mode
            needs none in the compiled mode either — the LIBRARY cost
            nothing to promote. What cost something was the view around it:
            the compiled grammar refused `[:th {:on-click [:ledger/sorted
            key]}]` because the event vector captures a `for` binding, and
            refused `^{:key k}` metadata because a compiled list row carries
            a literal `:key` PROP. Both refusals are right and both messages
            are excellent. Neither is a one-line change.

            The trees are therefore text-equal rather than shape-equal: the
            recovery introduced child boundaries the interpreted twin does
            not have. That is the honest result of taking the compiler's
            advice, and it is what an adopter's diff looks like.

            One more thing promotion surfaced: the compiled tier's a11y
            analyzer refused `:on-click` on a bare `<th>`
            (`:rf.ui.compile/a11y-click-non-interactive`). The interpreted
            twin carried the identical mistake in silence. Promotion does not
            only move WHEN a mistake surfaces — for a11y it decides WHETHER."
    (seed! {:ledger {:rows [{:id 1 :name "Zoe" :owed 5}
                            {:id 2 :name "Ada" :owed 9}]
                     :sort {:sort-by-key :name}}})
    (let [interpreted (render! [pilot/headless-table {}])
          promoted    (render! [compiled/compiled-headless-table {}])
          rows        (fn [tree] (mapv #(mapv t/text (:children %))
                                       (t/find-all tree #(= :tr (:tag %)))))]
      (is (some? (v/manifest compiled/compiled-headless-table))
          "the promoted twin really is compiled")
      (is (empty? (behavior-nodes promoted))
          "and still needs no host shape")
      (is (= (rows interpreted) (rows promoted))
          "the two modes render the same table from the same core"))))

;; ===========================================================================
;; The inherited finding, reproduced
;; ===========================================================================

(deftest t-render-alone-still-cannot-render-a-state-reading-view
  (testing "Third pilot, same wall. `t/render` is the PUBLIC structural
            surface and it opens no render candidate, so it cannot render any
            view that calls `v/sub` — and a React-library integration reads
            state like any other view. Every structural assertion in this
            file goes through the internal `cell/candidate` +
            `cell/with-capture` composition instead."
    (seed! {:invoice {:rows [["a" 1]]}})
    (let [d (caught #(t/render [pilot/invoice-sheet {}]))]
      (is (= :rf.error/view-read-outside-render (:rf.error/id d))
          (str "the blessed public verb refuses a state-reading view; got "
               (pr-str d))))
    (is (some? (render! [pilot/invoice-sheet {}]))
        "and the internal composition is what makes the same render work")))
