(ns re-frame.hicasso.test
  "`ht` — HICASSO'S SUPPORTED TESTING SURFACE, L0 to L2 (rf2-hic-020).

  The machinery a Hicasso witness needs was, until this namespace, private
  to the benchmark tree: the canonical-DOM comparator that made two
  renderings comparable, the intent capture that said what a page MEANS,
  and the body-run door the read-extent matrix asserts through. All three
  were reachable only by a test that lived inside the bench lane. This is
  that machinery, extracted, named and documented — the same instruments,
  now a product surface.

      (:require [re-frame.hicasso :as h]
                [re-frame.hicasso.test :as ht])

  ## The ladder, and why the tier is the first decision

  A Hicasso test's hardest question is not *what do I assert* but *what
  can this tier honestly prove*. [[ladder]] is that answer as data, and
  every refusal in this namespace cites it rather than restating it:

      L0  event handlers, subscriptions, state transitions
          → pure CLJ/CLJS. This namespace adds nothing; see §L0 below.
      L1  codecs, intents, controlled/revision laws, the boundary ABI
          → pure data and property assertions. [[element-props]],
            [[materialize]], [[controlled?]], [[revision]], [[boundary?]],
            [[host?]], [[callback?]], [[view-name]], [[host-policy]],
            [[canonical-dom]], [[capture-intents]], [[fire!]].
      L2  one hook-free Hicasso body, run for its semantic tree
          → [[render]] + [[find]] / [[find-all]] / [[attrs]] / [[text]] /
            [[intents]].
      L3  React lifecycle, context, hooks, refs, errors, foreign hosts
          → the mounted facade (rf2-hic-027). NOT here.
      L4  IME, caret, focus, hydration, layout, performance
          → real browsers.

  **L2 is an assertion model, not a renderer.** It runs a body and reads
  the hiccup that body returned. There is no fake hooks dispatcher, no
  shallow renderer and no simulated React lifecycle anywhere in this file
  — the four things a substrate's own test kit is most tempted to invent,
  and the four this one refuses to. Where L2 cannot honestly answer, it
  REFUSES and names the tier that can. An input a harness cannot model
  and answers anyway is worse than one it declines: the plausible answer
  is believed.

  ## L0 — the tier this namespace deliberately does not touch

  L0 is *pure re-frame*. An event handler is a function of `db` and an
  event vector; a subscription is a function of its inputs; a state
  transition is the pair. None of that needs a view substrate, so none of
  it needs a Hicasso helper, and shipping one would be a shim in front of
  doors core already publishes:

      (deftest toggling-a-todo-flips-its-done-flag
        (let [db  {:todos {1 {:done false}}}
              db' (:db (handler {:db db} [:todo/toggle 1]))]
          (is (true? (get-in db' [:todos 1 :done])))))

      (rf/with-new-frame [f {:initial-events [[:rf/set-db seed]]}]
        (rf/dispatch-sync [:todo/toggle 1])
        (is (= expected (rf/subscribe-once [:todo/visible]))))

  Frame scope is the programmer's ordinary bracket — `rf/with-new-frame`
  or `rf/with-frame` — at L0 and at every tier above it. That is the L0
  contract, stated so a worker knows what L0 officially is, and the
  reason [[ladder]]'s L0 row names no mechanism of this namespace's own.

  ## The tree is Spec 004B version 1, not a second schema

  [[render]] answers the **versioned structural tree** that
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../../spec/004B-UI-Tree-and-Conversion.md)
  §The node schema already pins — the same closed node set, the same
  discrimination order, the same `:rf.ui/tree-version` root gate, the
  same `{:rf.ui/opaque :fn}` sentinel — so a tree from a Hicasso body and
  a tree from a Freehand declaration are the same VALUE KIND and read
  with the same vocabulary. rf2-hic-020 audited that schema before
  writing anything and reuses it whole; the projections below carry
  `re-frame.freehand.test`'s semantics for the same reason.

  Two things the audit found are stated rather than left to be
  discovered:

  - **[[attrs]] values are author-space and unnormalized.** 004B §Attr
    value normalization is the SSR fingerprint's input — the px rule, the
    canonical style map — and a fingerprint is not what L2 claims. Only
    the `.class#id` sugar is folded, because the author cannot see it
    otherwise. Assert the authored value.
  - **A view-boundary node records the CALL, never the child's
    rendering.** See §Children below.

  ### The scope of the claim: TREES EMITTED, not forms accepted

  004B version 1 governs the tree this namespace **emits**. It does not
  govern which author forms Hicasso accepts, and the two are different
  questions with different owners: the grammar is Hicasso's (spec SN, the
  HD rulings), the tree schema is 004B's, and this walk is both stages in
  one pass because it takes author hiccup and answers a node.

  The distinction is load-bearing at exactly one row. 004B §Child
  normalization says `nil`/`false`/`true` children are dropped **at tree
  build**; Hicasso raises `:rf.error/hicasso-true-child` for a `true`
  child (HD-016). There is no contradiction: Hicasso's grammar refuses
  the value upstream of tree build, so a `true` never reaches the rule
  that would drop it, and every tree this namespace emits satisfies 004B
  as written. What a reader must not conclude from *\"the tree is 004B
  version 1\"* is that a form 004B tolerates is a form Hicasso accepts —
  the schema describes the output, and the refusals are the substrate's.
  `re-frame.hicasso.test-kit-runtime-parity-cljs-test` drives both sides
  of that row so the two never drift apart quietly.

  ## What L2 runs, and what it refuses

  [[render]] takes a hiccup form whose head is **a body function** — the
  `(fn [props] …)` a `defview` is minted from:

      (defn todo-row-body [{:keys [id]}]
        (let [todo (h/sub [:todo/by-id id])]
          [:li {:on-click [:todo/toggle id]} (:text todo)]))

      (h/defview todo-row [props] (todo-row-body props))

      (deftest the-row-carries-its-toggle-intent
        (let [tree (ht/render [todo-row-body {:id 1}]
                              {:reads {[:todo/by-id 1] {:text \"milk\"}}})
              li   (ht/find tree #(= :li (:tag %)))]
          (is (= \"milk\" (ht/text li)))
          (is (= [:todo/toggle 1] (:on-click (ht/attrs li))))))

  **A minted `defview` head is refused, and the refusal is the honest
  answer rather than a limitation nobody wrote down.**
  `re-frame.hicasso.impl.collector/mint-view!` closes over the body and
  retains no reference to it — `boundary-head?` is one own-property read,
  there is no registry and no map — so a minted head cannot be run
  without React, and running it under React is L3. Naming the body, as
  above, costs one line and keeps the view under test running AS
  WRITTEN. See [[render]]'s refusal for the full statement.

  ### Children

  **What a child IS is the runtime's answer, never a second one.** The
  walk dispatches on `codec/child-kind` and `codec/vector-kind` — the
  same two doors `codec/as-element` and `codec/vec->element` dispatch on
  — so a keyword child is the text the runtime renders it as, `[:<> …]`
  is a fragment because it is React's Fragment there, and a `true` child
  raises `:rf.error/hicasso-true-child` because the runtime raises it.
  rf2-hic-020's audit found nine places where this walk had its own
  opinion; a branch that exists twice agrees once.

  **A form the runtime REFUSES is refused here with the runtime's own
  id.** `codec/vector-kind` is the preflight and not merely the head
  discrimination, so an empty vector raises
  `:rf.error/hicasso-empty-vector` and a malformed escape — `[:>]`,
  `[:> nil]`, `[:> :div]` — raises `:rf.error/hicasso-raw-no-component`
  or `:rf.error/hicasso-raw-not-a-component`, each carrying the runtime's
  recovery rather than one of this namespace's. **Opacity is a claim
  about a form L2 cannot read, and it is honest only where the runtime
  CAN read it**: a second audit found `[:> :div]` answered
  `:assert-it-at-l3`, which tells the programmer to mount, in a browser,
  a form that will not mount anywhere.

  - A **nested body function** is refused: a plain function in head
    position is a loud error in Hicasso itself (HD-016), and a kit that
    accepted one would teach a spelling the runtime rejects.
  - A **minted boundary head** records a **view-boundary node** — its
    `:view-id`, the props the call site passed, and the call site's own
    children. Its body does NOT run, and the node claims nothing about
    what it would render. Assert its props here; render its body to
    assert its contents.
  - A **`defhost` crossing**, a **well-formed raw escape `[:> …]`**, a
    **raw React element** and anything else the codec hands to React
    untouched are **opaque** and refuse with a pointer to L3. So does an
    unforced `delay`, which Hicasso itself refuses at a boundary
    crossing. A MALFORMED escape is not opaque — see above.

  ### Reads

  `:reads` is the injected fixture map — query vector to value. A read
  the fixtures do not answer is a **refusal**, never a nil: an unanswered
  read that returned nil would make a body that reads the wrong key look
  exactly like a body that reads the right one and finds nothing.

  The resolver is **discardable**. The fixtures are installed as cells on
  a probe frame keyword minted for the one call, and removed when it
  returns, so nothing subscribes, nothing is watched, no ref-count moves
  and no disposal obligation is left behind. Frame scope stays the
  programmer's ordinary bracket: [[render]] takes no frame and binds
  none.

  ## Scope

  Dev/test only. Nothing in a production bundle may `:require` this
  namespace — it is its own source root (`hicasso/test_kit/src`) and is
  absent from the artefact's published `:paths`, so it is reachable when
  used and unreachable otherwise."
  (:refer-clojure :exclude [find])
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.controlled :as controlled]
            [re-frame.hicasso.impl.intent :as intent]))

;; ---------------------------------------------------------------------------
;; Refusals — one constructor, so every refusal has the same identity shape
;; ---------------------------------------------------------------------------

(def ^:private where
  "The raising site every refusal in this namespace names."
  're-frame.hicasso.test)

(defn- refuse!
  "Raise a structured, source-located refusal.

  The ex-data is the ASSERTABLE identity — `:rf.error/id`, `:where`,
  `:reason`, `:recovery` and whatever the site adds — because
  `(is (thrown? …))` is not a witness: it is green for a throw from any
  layer with any id. A test of a refusal asserts the map."
  [id reason recovery extra]
  ;; rf2:builder-bypass-ok - `id` is a PARAMETER, so the message carries the
  ;; `[:rf.error/...]` token the source cannot show. Same shape as
  ;; `re-frame.hicasso.impl.collector/fail!`.
  (throw (ex-info (str reason " [" id "]")
                  (merge {:rf.error/id id :where where
                          :reason reason :recovery recovery}
                         extra))))

;; ---------------------------------------------------------------------------
;; L0 — the ladder, as data
;; ---------------------------------------------------------------------------

(def ladder
  "THE TESTING LADDER, as data — five rows, in tier order.

  It is data rather than prose because the refusals below cite it: a
  refusal that pointed at 'the mounted tier' in a hand-written string
  would drift from the tier table the day the table moved. Each row:

      :tier       the tier keyword
      :proves     what a test at this tier can honestly claim
      :mechanism  the tool that tier is written with
      :here?      whether THIS namespace ships that tier

  Spec 009 §9 of the Hicasso specification and
  `docs/design/hicasso/product/lanes/testing-xray.md` are the normative
  owners; this is their table, readable from a test."
  [{:tier :l0 :here? false
    :proves    "event handlers, subscriptions, state transitions"
    :mechanism "pure CLJ/CLJS tests over core's own doors — no view substrate"}
   {:tier :l1 :here? true
    :proves    "codecs, intents, controlled/revision laws, the boundary ABI"
    :mechanism "pure data and property assertions"}
   {:tier :l2 :here? true
    :proves    "one registered hook-free Hicasso body, as a semantic tree"
    :mechanism "re-frame.hicasso.test/render under injected read fixtures"}
   {:tier :l3 :here? false
    :proves    "React lifecycle, context, hooks, refs, errors, foreign hosts"
    :mechanism "the mounted React DOM facade (rf2-hic-027) with Testing Library"}
   {:tier :l4 :here? false
    :proves    "IME, caret, focus, hydration, layout, performance"
    :mechanism "Chromium, Firefox and WebKit witnesses"}])

(def ^:private by-tier (into {} (map (juxt :tier identity)) ladder))

(defn- tier-pointer
  "The one sentence a refusal uses to send a caller up the ladder. Read
  from [[ladder]] so a tier's description has one home."
  [tier]
  (let [{:keys [proves mechanism]} (by-tier tier)]
    (str (str/upper-case (name tier)) " owns " proves " — " mechanism ".")))

;; ---------------------------------------------------------------------------
;; L1 — the boundary ABI, read off minted values
;; ---------------------------------------------------------------------------
;;
;; `h/defview`, `h/defhost` and `h/hfn` are macros, and what a macro
;; expansion DECIDES is observable on the value it hands back: the marker
;; the expansion's target minted. Asserting the marker rather than the
;; expanded form is what keeps a safe refactor quiet and a changed
;; contract loud — the discipline `public-door-macros-cljs-test` already
;; established for this package, published here so a consumer's own
;; declarations can be held to it.

(defn boundary?
  "Is `v` a minted Hicasso boundary — the value `h/defview` defines?

  One own-property read (`codec/boundary-head?`). True for a `defview`
  var, false for the plain function its body is, which is the
  discrimination that makes this an assertion rather than a restatement
  of `fn?`."
  [v]
  (codec/boundary-head? v))

(defn host?
  "Is `v` a minted host crossing — the value `h/defhost` defines? False
  for the foreign component the declaration named, which is the point:
  the crossing is a distinct value with a policy of its own."
  [v]
  (codec/host-head? v))

(defn callback?
  "Is `v` the one callback form — the value `h/hfn` expands to? False for
  an identically-written plain `fn`, so a position that imposes a
  contract can tell them apart and so can a test."
  [v]
  (intent/callback? v))

(defn view-name
  "The `\"<ns>/<sym>\"` name a minted boundary or host carries — the same
  string React DevTools shows and Spec 009's `rf:render:<name>` measure
  is keyed on. `nil` for anything unminted."
  [v]
  (when (or (fn? v) (object? v))
    (unchecked-get v "displayName")))

(defn host-policy
  "The `:ssr` policy a `defhost` crossing was declared with —
  `:client-only`, `{:fallback <hiccup>}` or `:render` — read back as
  data. Refuses anything that is not a minted crossing rather than
  answering nil, because a nil here would read as `:client-only`'s
  neighbour."
  [v]
  (when-not (host? v)
    (refuse! :rf.error/hicasso-test-not-a-host
             (str "host-policy reads the `:ssr` policy off a minted `h/defhost` "
                  "crossing; it was given " (pr-str (type v)) ".")
             :pass-the-defhost-var
             {:value v}))
  (codec/host-ssr v))

;; ---------------------------------------------------------------------------
;; L1 — the codec, projected
;; ---------------------------------------------------------------------------

(defn- form-props
  "The author's attribute map for a hiccup form, with the `:&` remainder
  folded under the owned-literal law — `codec/merge-caller`'s own answer,
  never a second merge."
  [form]
  (let [p (nth form 1 nil)]
    (codec/merge-caller (if (map? p) p {}))))

(def ^:private probe-dispatch
  "The dispatch a lowering runs under when nothing is being rendered.
  Lowering an intent requires an ambient dispatch (a lowered handler
  closes over one), and L1 is not a render — so the projection binds a
  dispatch that CANNOT fire: it exists to be closed over and refuses if
  anything calls it, which is louder than a no-op that would make a
  handler look wired."
  (fn [event-v]
    (refuse! :rf.error/hicasso-test-l1-dispatch
             (str "A handler lowered by an L1 projection was invoked. "
                  "L1 reads the codec's emission as data; firing a handler is "
                  "behaviour. " (tier-pointer :l3))
             :assert-the-intent-as-data
             {:event event-v})))

(def ^:private l1-frame
  "The frame keyword an L1 lowering binds. It names no live frame — L1
  resolves nothing and dispatches nothing — and exists so the emitted
  slots are the ones a render would emit."
  ::l1)

(defn- opaque-value
  "004B §The opaque marker, applied to one emitted slot value: a function
  records as `{:rf.ui/opaque :fn}` — the site's existence and spelling
  are what a structural test asserts on, its behaviour is L3."
  [v]
  (cond
    (fn? v)     {:rf.ui/opaque :fn}
    (object? v) (persistent!
                  (reduce (fn [m k] (assoc! m k (let [x (unchecked-get v k)]
                                                  (if (fn? x) {:rf.ui/opaque :fn} x))))
                          (transient {})
                          (js-keys v)))
    :else       v))

(defn element-props
  "The **emitted prop slots** of one native hiccup form, as a Clojure map
  of slot name to value — the codec's own conversion, read as data.

      (ht/element-props [:div#main.wide {:class \"tall\" :tab-index 0}])
      ;; => {\"id\" \"main\" \"className\" \"wide tall\" \"tabIndex\" 0}

  This is the L1 answer to 'what does the codec do with what I wrote' —
  the `.class#id` fold, the canonical slot names, the `:&` remainder's
  owned-literal law, the reserved-key handling — asserted as values
  rather than inferred from a rendering. A lowered handler records as
  `{:rf.ui/opaque :fn}` (004B §The opaque marker): the slot's existence
  and spelling are the claim, and the intent it carries is asserted with
  [[materialize]] or read off an L2 tree.

  Native forms only. A form whose head is a boundary, a host or anything
  other than a tag keyword is refused — there are no props to emit."
  [form]
  (when-not (and (vector? form) (keyword? (nth form 0 nil)))
    (refuse! :rf.error/hicasso-test-not-a-native-form
             (str "element-props projects ONE native hiccup form — a vector "
                  "whose head is a tag keyword. It was given " (pr-str form) ".")
             :pass-a-native-hiccup-form
             {:value form}))
  (let [parsed (codec/cached-parse (nth form 0))
        props  (form-props form)
        js-props (intent/with-frame l1-frame probe-dispatch
                   (fn [] (codec/convert-props props parsed)))]
    (persistent!
      (reduce (fn [m k] (assoc! m k (opaque-value (unchecked-get js-props k))))
              (transient {})
              (js-keys js-props)))))

(defn materialize
  "THE MARKER LAW, as a pure function: the vector `intent` materializes
  to, given what the event target would have carried.

      (ht/materialize [:filter/set ::h/value] {:value \"done\"})
      ;; => [:filter/set \"done\"]

  `target` is `{:value … :checked …}` — the two facts `::h/value` and
  `::h/checked` read. This is `re-frame.hicasso.impl.intent/materialize`
  itself, not a re-derivation, so the substitution rule under test is the
  one the browser path runs. Top level only, exactly as the runtime's is.

  It is a data projection and not an event: nothing is dispatched,
  nothing is prevented, no handler runs."
  [intent-v {:keys [value checked]}]
  (when-not (vector? intent-v)
    (refuse! :rf.error/hicasso-test-not-an-intent
             (str "materialize takes an intent VECTOR; it was given "
                  (pr-str intent-v) ".")
             :pass-an-intent-vector
             {:value intent-v}))
  (intent/materialize intent-v #js {"target" #js {"value" value "checked" checked}}))

(defn controlled?
  "Does the codec install the **controlled shadow** for this native form —
  HD-019's synchronous door, and Invariant I15's subject?

  True exactly when `re-frame.hicasso.impl.controlled/install!` selects a
  component other than the tag itself, which is the runtime's own
  decision rather than a re-derivation of it: a field is controlled
  because the emitted element is, not because the author wrote something
  that looks controlled."
  [form]
  (when-not (and (vector? form) (keyword? (nth form 0 nil)))
    (refuse! :rf.error/hicasso-test-not-a-native-form
             (str "controlled? asks about ONE native hiccup form — a vector "
                  "whose head is a tag keyword. It was given " (pr-str form) ".")
             :pass-a-native-hiccup-form
             {:value form}))
  (let [parsed   (codec/cached-parse (nth form 0))
        props    (form-props form)
        js-props (intent/with-frame l1-frame probe-dispatch
                   (fn [] (codec/convert-props props parsed)))
        tag      (.-tag parsed)]
    (not (identical? tag (controlled/install! tag js-props)))))

(defn revision
  "The `::h/revision` value a native form carries, or nil.

  A trigger and not an attribute: a change to it re-baselines a
  controlled field to the model without remounting it (HD-019's reset
  law). It is read **pre-merge-conversion**, off the author's own
  attribute map after `:&` folds, which is where the codec reads it —
  so a remainder cannot arm a reset the element's author never wrote,
  here or there."
  [form]
  (get (form-props form) codec/revision-key))

;; ---------------------------------------------------------------------------
;; L1 — the canonical DOM comparator
;; ---------------------------------------------------------------------------

(defn canonical-dom
  "Serialise a DOM node's subtree with every element's attribute names
  **sorted** — the fairness gate two renderings are compared through.

  `innerHTML` preserves insertion order, and two front ends write props
  in different orders, so comparing it compares the SERIALISER rather
  than the page. Sorting the names compares the DOM. Without it two
  renderings can be declared equivalent while building different pages,
  which is the whole reason a comparison needs a canonical form at all.

  Extracted from the measurement lane's own gate
  (`re-frame.bench.hicasso.lane/canonical`), unchanged in behaviour.
  Comments are dropped; a node kind with no textual form records as
  `#<nodeType>` rather than vanishing.

  It answers the DOM's shape and nothing about React: two canonically
  equal pages may still differ in lifecycle, identity and hydration —
  distinct claims, each owed its own witness (004B, and the specification
  §9 on naming the equality a witness proves)."
  [node]
  (when-not (and (some? node) (number? (.-nodeType node)))
    (refuse! :rf.error/hicasso-test-not-a-dom-node
             (str "canonical-dom serialises a DOM node's subtree; it was given "
                  (pr-str node) ". " (tier-pointer :l2)
                  " A semantic tree is compared with ordinary `=`.")
             :pass-a-dom-node
             {:value node}))
  (let [out (array)]
    (letfn [(walk [n]
              (case (.-nodeType n)
                1 (let [tag   (str/lower-case (.-tagName n))
                        attrs (->> (array-seq (.-attributes n))
                                   (map (fn [a] [(.-name a) (.-value a)]))
                                   (sort-by first))]
                    (.push out (str "<" tag))
                    (doseq [[k v] attrs] (.push out (str " " k "=\"" v "\"")))
                    (.push out ">")
                    (doseq [c (array-seq (.-childNodes n))] (walk c))
                    (.push out (str "</" tag ">")))
                3 (.push out (.-nodeValue n))
                8 nil
                (.push out (str "#" (.-nodeType n)))))]
      (doseq [c (array-seq (.-childNodes node))] (walk c)))
    (.join out "")))

;; ---------------------------------------------------------------------------
;; L1 — intent capture
;; ---------------------------------------------------------------------------

(defonce ^:private !capture-seq (atom 0))

(defn capture-intents
  "Run `f` with the frame's dispatched events captured, and answer
  `{:value <f's value> :intents [event-v …]}`.

  The capture is taken at **Spec 009's `:events` observation port** — the
  substrate's own public stream — so the witness holds no hook into the
  rendering under test and reads the same events whichever substrate
  produced them. That is what let one script judge a Hicasso rendering, a
  raw UIx rendering and a hydrated page against ONE stated expectation.

  Events from other frames are ignored, so a capture taken while another
  frame is live is still this frame's. The listener is registered when
  `f` starts and removed when it returns, whatever `f` does, so neither a
  seed before nor a teardown after can leak into the capture.

  **State the expectation.** Comparing two captures to each other is
  green for a drift they SHARE; comparing each to a written-out vector is
  not. That is a merged-PR audit's finding, kept here because the helper
  is what makes the cheap comparison easy:

      (let [{:keys [intents]} (ht/capture-intents ::app #(run-the-script!))]
        (is (= expected-intents intents)))

  Synchronous: it captures what `f` dispatches before it returns. A
  script whose steps span turns is the mounted tier's driver
  (rf2-hic-027), and this is the port that one arms too."
  [frame-kw f]
  (let [log (atom [])
        k   (keyword "re-frame.hicasso.test"
                     (str "intent-capture-" (swap! !capture-seq inc)))]
    (rf/register-listener! :events k
                           (fn [record]
                             (when (= frame-kw (:frame record))
                               (swap! log conj (:event record)))))
    (try
      (let [v (f)] {:value v :intents @log})
      (finally (rf/unregister-listener! :events k)))))

;; ---------------------------------------------------------------------------
;; L1 — one handler position, fired with a stated event
;; ---------------------------------------------------------------------------

(defn- synthetic-event
  "The event object a lowered handler is given — the NATIVE signals it
  reads, written as data.

  Native and not React's synthetic event, and that is the point rather
  than a shortcut: React's synthetic keyboard event DROPS `isComposing`,
  so a witness that read the synthetic event would be deaf to the
  composing law it exists to assert. These are the properties Hicasso's
  own handlers read — `target.value`, `target.checked`, `key`,
  `isComposing`, `keyCode` — and nothing else is invented."
  [{:keys [value checked key composing? key-code]} !prevented]
  #js {"target"         #js {"value" value "checked" checked}
       "key"            key
       "isComposing"    (boolean composing?)
       "keyCode"        (or key-code 0)
       "preventDefault" (fn [] (vreset! !prevented true) js/undefined)})

(defn fire!
  "Lower one handler position of a native form and **invoke it** with an
  event described as data, inside `frame-kw`'s dispatch — the composition
  witness, without a browser.

      (ht/fire! ::app [:input {:on-input [:draft/edit 1 ::h/value]}]
                :on-input {:value \"milk\"})
      ;; => {:intents [[:draft/edit 1 \"milk\"]] :prevented? false}

  It answers `{:intents […] :prevented? bool}` — what the position
  dispatched, captured at Spec 009's `:events` port, and whether the
  handler called `preventDefault`. The dispatch is the frame's own, so
  the events reach real handlers and app-db really moves; assert the
  result with an ordinary subscription read.

  **What it proves.** The whole path from the authoring spelling to a
  moved app-db: the codec's prop walk, the intent lowering, the marker
  substitution, the prevent-default policy, the key-map's branches and
  the composition gate — with the NATIVE signals a browser sends, which
  is where the composing law is readable at all.

  **What it does not prove**, and is therefore not a substitute for:
  anything the DOM owns. No element exists, so there is no bubbling, no
  default action, no focus, no caret, no selection, no IME state machine
  and no React event system. Those are L3 and L4 — a green here says the
  handler MEANS the right thing, never that a user reaching it has the
  right experience.

  Native forms only; an event position with no value at `prop` refuses,
  because a silently absent handler is the failure this door exists to
  make loud."
  [frame-kw form prop event]
  (when-not (and (vector? form) (keyword? (nth form 0 nil)))
    (refuse! :rf.error/hicasso-test-not-a-native-form
             (str "fire! drives ONE native hiccup form — a vector whose head "
                  "is a tag keyword. It was given " (pr-str form) ".")
             :pass-a-native-hiccup-form
             {:value form}))
  (let [props (form-props form)]
    (when-not (contains? props prop)
      (refuse! :rf.error/hicasso-test-no-handler-at-position
               (str "the form carries no value at " (pr-str prop)
                    ". A handler position that is silently absent is exactly "
                    "the fault this door exists to make loud; the positions "
                    "written are " (pr-str (vec (keys props))) ".")
               :name-a-position-the-form-writes
               {:position prop :written (vec (keys props))}))
    (let [handler (intent/with-frame frame-kw (collector/frame-dispatch frame-kw)
                    (fn [] (intent/lower-prop prop (get props prop))))]
      (when-not (fn? handler)
        (refuse! :rf.error/hicasso-test-position-is-not-a-handler
                 (str (pr-str prop) " lowered to " (pr-str handler)
                      " rather than to a function — it is not a handler "
                      "position, so there is nothing to fire.")
                 :name-an-event-position
                 {:position prop}))
      (let [!prevented (volatile! false)
            captured   (capture-intents
                         frame-kw
                         (fn [] (handler (synthetic-event event !prevented))))]
        {:intents    (:intents captured)
         :prevented? @!prevented}))))

;; ---------------------------------------------------------------------------
;; L2 — the tree, per Spec 004B version 1
;; ---------------------------------------------------------------------------

(def tree-version
  "The structural-tree schema version [[render]] stamps on its root —
  Spec 004B version 1. Validated first by every consumer, which is what
  makes a tree from a future emitter fail loud rather than read wrong."
  1)

(def ^:private tree-version-key :rf.ui/tree-version)

;; 004B §The opaque marker pins DATA as the EDN value grammar exactly —
;; the grammar the tree's print/read promise is stated in — rather than as
;; whatever the host's collection and number predicates happen to admit. The
;; two predicates below are that grammar, and they are what makes the
;; namespace docstring's "plain, serialisable Clojure data" a checked claim
;; rather than an advertised one: before rf2-hic-020's audit a host object
;; and a function used as a MAP KEY both walked into the tree unremarked.

(defn- edn-scalar?
  "Is `x` one of EDN's scalars — the values an EDN reader takes back as
  the value that was printed? `##NaN` is excluded although it prints and
  reads: it is not equal to itself, so a tree holding one is not equal to
  itself, and the tree is an equality input."
  [x]
  (or (string? x) (keyword? x) (boolean? x) (nil? x) (symbol? x)
      (and (number? x) (== x x))
      (uuid? x)
      ;; EDN's other built-in tagged literal, `#inst`.
      (instance? js/Date x)))

(defn- non-data
  "`[path value]` for the FIRST value inside `x` that is not data, or nil
  when `x` is data all the way down. `path` is the map keys and vector
  indices walked to reach it, so a refusal names the offending SITE.

  The collection arms name EDN's four collections rather than asking
  `coll?`, because `coll?` is a question about a host INTERFACE and
  collections implement it with no EDN spelling at all: a record answers
  `map?` while printing a tag no reader has, and a persistent queue
  answers a collection predicate while printing `#queue […]`. Read-only
  and short-circuiting — nothing is rebuilt and the walk stops at the
  first offender, so an ordinary scalar prop costs one predicate."
  [x]
  (cond
    (edn-scalar? x) nil

    (instance? PersistentQueue x) [[] x]

    ;; `reduce-kv` over a vector answers index/element — exactly the path
    ;; segment wanted — so maps and vectors share one arm. A KEY is
    ;; checked as well as a value: it is inside the recorded value just
    ;; as much, and it was the hole the audit found.
    (or (and (map? x) (not (record? x))) (vector? x))
    (reduce-kv (fn [_ k v]
                 (if-some [found (or (when (non-data k) [[] k])
                                     (when-some [[p bad] (non-data v)]
                                       [(into [k] p) bad]))]
                   (reduced found)
                   nil))
               nil
               x)

    ;; A set or a seq has no position worth naming, so the path stops.
    (or (set? x) (seq? x))
    (reduce (fn [_ v] (when-some [found (non-data v)] (reduced found))) nil x)

    :else [[] x]))

(defn- offender-name
  "How a refusal NAMES the value that may not be recorded. `##NaN` by its
  own spelling rather than as a number, because the number grammar is
  otherwise open and 'carries a number' would read as nonsense."
  [x]
  (cond
    (and (number? x) (not (== x x))) "##NaN"
    (fn? x)                          "a function"
    (record? x)                      "a record"
    (object? x)                      "a host object"
    :else                            (str "a " (pr-str (type x)))))

(defn- opaque-prop
  "004B §The opaque marker at a prop or handler site: a function IS the
  marker; anything outside the EDN value grammar, at any depth inside a
  recorded value, is REJECTED rather than marked.

  Rejected rather than marked because below the key the grammar names no
  site, and a marker written there would claim one that does not exist
  and silently replace a value the author will go looking for."
  [k v]
  (if (fn? v)
    {:rf.ui/opaque :fn}
    (do (when-some [[path bad] (non-data v)]
          (refuse! :rf.error/ui-tree-malformed
                   (str "the value at " (pr-str k)
                        (when (seq path) (str ", at " (pr-str path) " within it,"))
                        " is " (offender-name bad) ", which this tree cannot "
                        "record: 004B pins it as plain data an EDN reader takes "
                        "back, and the opaque marker occupies a SITE rather than "
                        "a value inside one.")
                   :hoist-it-to-its-own-site
                   {:key k :path (into [k] path) :value bad}))
        v)))

(defn- refuse-opaque!
  [id reason extra]
  (refuse! id (str reason " " (tier-pointer :l3)) :assert-it-at-l3 extra))

(defn- walk-props
  "The props map a boundary call site passed, recorded verbatim with
  004B's opaque rule applied at each key. `:key` and `:children` are
  fields of the node rather than props, so they are not recorded here."
  [props]
  (persistent!
    (reduce-kv (fn [m k v]
                 (if (or (= :key k) (= :children k))
                   m
                   (assoc! m k (opaque-prop k v))))
               (transient {})
               props)))

;; ---------------------------------------------------------------------------
;; L2 — the namespace context (004B §Namespaces)
;; ---------------------------------------------------------------------------
;;
;; A body is written without knowing where it will be mounted, so `<svg>`-ness
;; is not a fact about a subtree — it is established by the element that opens
;; the namespace and read by the elements built underneath it. React infers it
;; from the tag at commit time and stores nothing; the TREE has to carry it,
;; because `:ns` is a pinned field of the element variant and two nodes that
;; differ only by namespace are two different nodes.

(def ^:private html-encodings
  "The `:annotation-xml` encodings that make its children HTML — the
  HTML specification's own integration point."
  #{"text/html" "application/xhtml+xml"})

(defn- element-ns
  "The namespace an element IS in. `:svg` and `:math` open one; every
  other element inherits the context it was built in. `nil` is HTML, and
  004B requires `:ns` to be ABSENT there — one representation per node."
  [tag inherited]
  (case tag
    :svg  :svg
    :math :mathml
    inherited))

(defn- children-ns
  "The namespace an element's CHILDREN are built in. Two integration
  points revert to HTML: `:foreignObject` always, and `:annotation-xml`
  when its `:encoding` says its content is HTML."
  [tag own attrs]
  (case tag
    :foreignObject  nil
    :annotation-xml (when-not (html-encodings (:encoding attrs)) own)
    own))

;; ---------------------------------------------------------------------------
;; L2 — the walk
;; ---------------------------------------------------------------------------

(declare walk)

(defn- child-forms
  "The raw child values of a hiccup form — everything after the head and
  the attribute map, if there is one."
  [form]
  (subvec form (if (map? (nth form 1 nil)) 2 1)))

(defn- coalesce
  "Append one child to the canonical run, MERGING it into the text run it
  continues. 004B §Children — adjacent text runs are one string."
  [out x]
  (let [n (count out)]
    (if (and (string? x) (pos? n) (string? (nth out (dec n))))
      (assoc out (dec n) (str (nth out (dec n)) x))
      (conj out x))))

(defn- canonical-children
  "The canonical `:children` vector for a run of hiccup values — 004B
  §Children, in ONE place.

  `codec/child-kind` decides what each value IS, so the grammar here is
  the runtime's own and cannot drift from it. Seqs splice in document
  order; adjacent text coalesces; empty strings drop AFTER coalescing,
  which is the order the rule is stated in and the only order under which
  a `\"\"` between two strings joins them rather than breaking the run."
  [xs ns-ctx]
  (into []
        (remove #(= "" %))
        (reduce (fn add [out x]
                  (let [kind (codec/child-kind x)]
                    (case kind
                      :nothing out
                      :splice  (reduce add out x)
                      (coalesce out (walk kind x ns-ctx)))))
                []
                xs)))

(defn- element-node
  [form ns-ctx]
  (let [parsed  (codec/cached-parse (nth form 0))
        tag     (keyword (.-tag parsed))
        props   (form-props form)
        ;; The codec's own two shorthand rules, taken from it rather than
        ;; restated: an explicit id WINS over `#id`, and the shorthand
        ;; class is PREPENDED to a declared one
        ;; (`codec/fold-shorthand!`).
        classes (codec/class-names (.-className parsed) (:class props))
        id      (or (:id props) (.-id parsed))
        events  (persistent!
                  (reduce-kv (fn [m k v]
                               (if (intent/event-prop? k)
                                 (assoc! m k (opaque-prop k v))
                                 m))
                             (transient {})
                             props))
        attrs   (persistent!
                  (reduce-kv (fn [m k v]
                               (cond
                                 (intent/event-prop? k) m
                                 (= :key k)             m
                                 (= :class k)           m
                                 (= :id k)              m
                                 (nil? v)               m
                                 :else (assoc! m k (opaque-prop k v))))
                             (transient {})
                             props))
        attrs   (cond-> attrs
                  (some? classes) (assoc :class classes)
                  (some? id)      (assoc :id id))
        own-ns   (element-ns tag ns-ctx)
        children (canonical-children (child-forms form)
                                     (children-ns tag own-ns attrs))]
    (cond-> {:tag tag}
      (some? own-ns)         (assoc :ns own-ns)
      (seq attrs)            (assoc :attrs attrs)
      (seq events)           (assoc :events events)
      (contains? props :key) (assoc :key (:key props))
      (seq children)         (assoc :children children))))

(defn- fragment-node
  "`[:<> …]` — the variant whose job is to hold a run of children.

  Its `:children` are retained WHEN EMPTY, alone among the variants: they
  are its required discriminator, so `{}` would be a malformed node
  rather than an empty fragment (004B §Canonical uniqueness). Only `:key`
  is read off its attribute map, exactly as `codec/fragment-element`
  reads it — a fragment has no attributes to emit."
  [form ns-ctx]
  (let [props (when (map? (nth form 1 nil)) (nth form 1))]
    (cond-> {:children (canonical-children (child-forms form) ns-ctx)}
      (contains? props :key) (assoc :key (:key props)))))

(defn- boundary-node
  "A child boundary, recorded as the CALL it is: its view id, the props
  the call site passed and the children the call site wrote. Its body
  does not run — `mint-view!` retains no reference to it — so this node
  claims nothing about what the child would render, and [[text]] over it
  answers the call site's own children and no more."
  [form ns-ctx]
  (let [head     (nth form 0)
        props    (form-props form)
        recorded (walk-props props)
        children (canonical-children (child-forms form) ns-ctx)]
    ;; `mint-view!` stamps `displayName` on the component unconditionally
    ;; — not behind `goog.DEBUG` — so a head minted through `h/defview`
    ;; always names itself and the fallback is unreachable through the
    ;; public door. It is a placeholder for a head marked by hand through
    ;; the impl surface, and deliberately a plain string rather than a
    ;; framework keyword: the tree is data a consumer reads, and minting
    ;; a reserved id for an unreachable case would be vocabulary nobody
    ;; can encounter.
    (cond-> {:view-id (or (view-name head) "<unnamed boundary>")}
      (seq recorded)         (assoc :props recorded)
      (contains? props :key) (assoc :key (:key props))
      (seq children)         (assoc :children children))))

(defn- walk-vector
  "One hiccup VECTOR to its 004B node. `codec/vector-kind` is the
  runtime's own vector preflight, so `:<>` is a fragment here because it
  is a fragment there, and the two escapes React interprets for itself
  refuse to L3 rather than being recorded as elements named `:<>` and
  `:>`.

  **The preflight, and not `codec/head-kind` alone, because opacity is a
  claim about a form the kit cannot read and it is honest only where the
  runtime CAN.** `codec/vector-kind` raises the runtime's own refusal for
  the two shapes that are malformed rather than opaque — a headless
  vector, and a `[:> …]` whose Component slot holds nothing React will
  mint a fiber for — so those arrive here already refused, with the
  runtime's id, reason and recovery. Before rf2-hic-020's second audit
  this walk asked `head-kind` directly and answered `[]` with a generic
  malformed HEAD, and `[:> :div]` with a pointer to L3: an instruction to
  go mount, in a browser, a form that cannot mount anywhere."
  [form ns-ctx]
  (case (codec/vector-kind form)
    :tag      (element-node form ns-ctx)
    :fragment (fragment-node form ns-ctx)
    :boundary (boundary-node form ns-ctx)

    :host
    (let [head (nth form 0)]
      (refuse-opaque! :rf.error/hicasso-test-host-is-opaque
                      (str "a `h/defhost` crossing (" (pr-str (view-name head))
                           ") reached the semantic tree. What a foreign React "
                           "component renders is React's answer, not "
                           "Hicasso's, and L2 has no way to ask it.")
                      {:host (view-name head) :value form}))

    ;; Reached only for a WELL-FORMED escape: `codec/vector-kind` has run
    ;; the escape's own grammar and a malformed one never gets here.
    :raw
    (refuse-opaque! :rf.error/hicasso-test-react-is-opaque
                    (str "the raw escape `[:> …]` reached the semantic tree. "
                         "It hands its component to React untouched (HD-011), "
                         "so what it renders is React's answer and L2 has no "
                         "way to ask it.")
                    {:value form})

    :invalid
    ;; `:invalid` implies a head, because the preflight refused the
    ;; headless vector under its own id.
    (let [head (nth form 0)]
      (if (fn? head)
        (refuse! :rf.error/hicasso-test-plain-fn-head
                 (str "a plain function is in hiccup head position. Hicasso "
                      "refuses that outright (HD-016) rather than embedding it "
                      "silently, so this kit refuses it too. Mint the boundary "
                      "with `h/defview`, or — to run this body at L2 — pass it "
                      "as the ROOT form of `render`.")
                 :mint-the-boundary-or-render-it-as-the-root
                 {:value form})
        (refuse! :rf.error/ui-tree-malformed
                 (str "a hiccup vector's head must be a tag keyword, `:<>`, "
                      "`:>`, a `h/defview` boundary or a `h/defhost` crossing; "
                      "got " (pr-str head) ".")
                 :fix-the-head
                 {:value form})))))

(defn- walk
  "One hiccup value that CONTRIBUTES A CHILD, as its 004B node or as
  text. `kind` is `codec/child-kind`'s answer, computed by the caller:
  `:nothing` and `:splice` are the RUN's business (see
  [[canonical-children]]) rather than a node's, so they never arrive
  here and this function is total over what does.

  Every arm is the runtime's own answer for that kind, or an honest
  refusal where L2 cannot give one."
  [kind x ns-ctx]
  (case kind
    ;; 004B §Children: a number is text via JS `ToString`.
    :text   (str x)
    ;; The runtime renders a keyword or symbol child as its `name`
    ;; (`codec/as-element`), so the tree records the text it renders.
    ;; This arm used to refuse, which taught that a legal spelling was a
    ;; mistake — the sharpest way an instrument can be wrong.
    :named  (name x)
    :markup (walk-vector x ns-ctx)

    :true-child
    (refuse! :rf.error/hicasso-true-child
             (str "a `true` child reached the semantic tree. `nil` and `false` "
                  "render nothing; `true` is an error (HD-016), and the runtime "
                  "raises this same id for it rather than dropping it.")
             :use-nil-or-false
             {:value x})

    :react-element
    (refuse-opaque! :rf.error/hicasso-test-react-is-opaque
                    (str "a raw React element reached the semantic tree. L2 "
                         "reads the hiccup a body wrote; a value React alone "
                         "can interpret has no semantic form here.")
                    {:value x})

    :foreign
    (if (delay? x)
      (refuse-opaque! :rf.error/hicasso-deferred-read-at-boundary
                      (str "an unforced `delay` reached a boundary crossing. "
                           "Hicasso refuses it there rather than forcing it, "
                           "because forcing an author's explicit deferral would "
                           "change what their program means.")
                      {:value x})
      (refuse! :rf.error/ui-tree-malformed
               (str "a child outside the tree's value grammar. The runtime "
                    "hands this to React as it stands, which has no semantic "
                    "form here; content is a string, a number, a keyword or a "
                    "symbol. Got " (pr-str x) ".")
               :fix-the-child
               {:value x}))))

;; ---------------------------------------------------------------------------
;; L2 — the discardable read resolver
;; ---------------------------------------------------------------------------

(defonce ^:private !probe-seq (atom 0))

(defn- install-fixtures!
  "Install one discardable cell per fixture query under `frame-kw`, and
  answer the sub-keys installed.

  A cell whose `.reaction` derefs to the fixture value is exactly what
  `read-key!` looks for, so a fixtured read is a plain deref on the
  runtime's own read path — no second resolver, no branch in the
  collector, and nothing for a fixture to be inconsistent with. The frame
  keyword is minted per call, so an installed key cannot collide with a
  live cell, and [[render]] removes every one of them on the way out."
  [frame-kw reads]
  (let [keys' (mapv (fn [[query-v _]] [frame-kw query-v]) reads)]
    (swap! collector/!cells
           (fn [cells]
             (reduce-kv (fn [m query-v v]
                          ;; `epoch` alongside the reaction because a
                          ;; cell is read for one or the other and never
                          ;; for both at once: `read-key!` takes the
                          ;; reaction, an entry's `getSnapshot` takes the
                          ;; epoch. A fixture cell that carried only the
                          ;; half this call needs would answer `NaN` to
                          ;; the other, which is the shape of instrument
                          ;; fault that reports a precise wrong number.
                          (assoc m [frame-kw query-v]
                                 #js {"reaction" (atom v) "epoch" 0}))
                        cells
                        reads)))
    keys'))

(defn- verify-fixtures!
  "Refuse if the body read a key no fixture answered.

  The read set is the runtime's own (`collector/reads-of` over the entry
  the render resolved), so this asks what the body ACTUALLY read rather
  than what its source appears to read — a read inside a `when` that was
  not taken is correctly absent, and a read from an inlined helper is
  correctly present."
  [frame-kw reads entry]
  (let [supplied (into #{} (map (fn [[q _]] [frame-kw q])) reads)
        missing  (remove supplied (collector/reads-of entry))]
    (when (seq missing)
      (refuse! :rf.error/hicasso-test-missing-read-fixture
               (str "the body read " (count missing) " subscription"
                    (when (> (count missing) 1) "s")
                    " no fixture answers: "
                    (str/join ", " (map (comp pr-str second) missing))
                    ". A missing fixture refuses rather than resolving to nil, "
                    "because a nil would make a body that reads the wrong key "
                    "look exactly like one that reads the right key and finds "
                    "nothing.")
               :add-the-query-to-reads
               {:missing   (mapv second missing)
                :supplied  (mapv first reads)
                :phase     :after-body-run}))))

(defn render
  "Run one hook-free Hicasso **body** under injected read fixtures, and
  answer its versioned semantic tree.

      (ht/render [todo-row-body {:id 1}]
                 {:reads {[:todo/by-id 1] {:text \"milk\"}}})
      ;; => {:rf.ui/tree-version 1
      ;;     :tag :li
      ;;     :events {:on-click [:todo/toggle 1]}
      ;;     :children [\"milk\"]}

  `form` is `[body-fn props & children]` — the ordinary hiccup call
  spelling, with the **body function** in head position. `opts` carries
  one key, `:reads`, the fixture map from query vector to value.

  ## What it is

  The body runs on the runtime's own body-run path
  (`re-frame.hicasso.impl.collector/render-body`), so the generation
  fence, the ambient-read extent (I7) and the read-set accounting are the
  real ones — a read escaping into a callback or a timer refuses here
  exactly as it refuses in a browser. The hiccup that body returned is
  then walked into the Spec 004B tree, INSIDE the same render window, so
  a `for` inside the body realises where the runtime realises it and a
  read inside that `for` is legal for the same reason it is legal on a
  page.

  It is not a renderer. No React element is created, no hook runs, no
  dispatcher is installed, and nothing is committed, mounted or painted.

  ## Minted heads, and the one build where they refuse

  `[some-view {…}]` is accepted with the `h/defview` head written where
  the call site writes it. A minted head is a React component whose body
  runs only inside the shell, so it carries that body on itself for this
  kit to find (`codec/retain-body!`, rf2-kjf5) — and what runs here is
  the body AS WRITTEN, with no hook, no element and no React.

  The retention is **dev only**. In an `:advanced` + `goog.DEBUG=false`
  build the property was never written, and a minted head refuses with
  `:rf.error/hicasso-test-boundary-body-not-retained` pointing at L3 —
  which costs nothing, because a production bundle may not `:require`
  this namespace at all.

  ## What it refuses

  - **A `h/defhost` crossing, a raw React element, an unforced `delay`**,
    anywhere in the tree — opaque, with a pointer to L3.
  - **A read no fixture answers** — see [[render]]'s `:reads`.

  ## Frame scope

  It takes no frame and binds none. The fixtures resolve on a probe frame
  keyword minted for this call and removed when it returns; frame scope
  for the events and subscriptions AROUND a view test stays the
  programmer's ordinary `rf/with-new-frame` / `rf/with-frame` bracket."
  ([form] (render form {}))
  ([form {:keys [reads] :or {reads {}}}]
   (when-not (and (vector? form) (seq form))
     (refuse! :rf.error/hicasso-test-not-a-render-form
              (str "render takes a hiccup form `[body-fn props & children]`; "
                   "it was given " (pr-str form) ".")
              :pass-a-hiccup-form
              {:value form}))
   (let [minted (nth form 0)
         ;; A minted head runs its body inside the shell and nowhere else,
         ;; so the mint attaches that body to the head under a DEV-ONLY
         ;; property (rf2-kjf5). Substituting it here is the whole of L2's
         ;; support for `[some-view …]`: everything below then reads the
         ;; body function, and the view runs AS WRITTEN.
         head   (if (codec/boundary-head? minted)
                  (or (codec/retained-body minted)
                      ;; Reachable in one build only — `:advanced` with
                      ;; `goog.DEBUG=false`, where the property was never
                      ;; written. There is genuinely nothing to run, so the
                      ;; refusal stands exactly as it did.
                      (refuse! :rf.error/hicasso-test-boundary-body-not-retained
                               (str "`" (view-name minted) "` is a minted boundary "
                                    "and this is a production build, where a "
                                    "boundary does not retain its body — the mint "
                                    "keeps it under a `goog.DEBUG` property that "
                                    "was compiled away, so there is nothing here "
                                    "to run without React. Render the BODY: write "
                                    "it as a named fn and mint the view from it, "
                                    "or assert this boundary mounted. "
                                    (tier-pointer :l3))
                               :render-the-body-fn-or-mount-at-l3
                               {:view (view-name minted)}))
                  minted)]
     (when (codec/host-head? head)
       (refuse-opaque! :rf.error/hicasso-test-host-is-opaque
                       (str "`" (view-name head) "` is a `h/defhost` crossing. "
                            "What a foreign React component renders is React's "
                            "answer, not Hicasso's.")
                       {:host (view-name head)}))
     (when-not (fn? head)
       (refuse! :rf.error/hicasso-test-not-a-body
                (str "render's head is the BODY FUNCTION a `h/defview` is "
                     "minted from — `(fn [props] …)`. It was given "
                     (pr-str head) ".")
                :pass-the-body-fn
                {:value head}))
     (when-not (map? reads)
       (refuse! :rf.error/hicasso-test-bad-reads
                (str ":reads is the fixture map from query vector to value; it "
                     "was given " (pr-str reads) ".")
                :pass-a-map-of-query-to-value
                {:value reads}))
     (let [frame-kw (keyword "re-frame.hicasso.test"
                             (str "probe-" (swap! !probe-seq inc)))
           has-props? (map? (nth form 1 nil))
           props    (if has-props? (nth form 1) {})
           ;; The children a call site writes reach a body as the
           ;; `:children` PROP — hicasso's own crossing shape
           ;; (`codec/boundary-element`) — and they reach it as HICCUP,
           ;; unwalked, because the body may render them, wrap them or
           ;; drop them. `codec/realize-children` is the flatten, so the
           ;; one-level seq splice is the runtime's rather than a second
           ;; rule with the same name.
           props    (if-some [cs (codec/realize-children form (if has-props? 2 1))]
                      (assoc props :children cs)
                      props)
           !tree    (volatile! nil)
           keys'    (install-fixtures! frame-kw reads)]
       (try
         (collector/render-body
           frame-kw
           (fn hicasso-test-body [p]
             (vreset! !tree (canonical-children [(head p)] nil))
             nil)
           props)
         (verify-fixtures! frame-kw reads (collector/last-reads))
         ;; 004B §Versioning — the emitter returns the ROOT NODE, always a
         ;; map, carrying the version. A body that answered ONE node roots
         ;; in it; a body that answered text, several nodes, or NOTHING
         ;; roots in a fragment, which is the variant whose job is to hold
         ;; a run. The empty case is `{:children []}` and not `{}`:
         ;; `:children` is the fragment's required discriminator, so the
         ;; bare version map the kit used to answer was a malformed node
         ;; every projection had to special-case.
         (let [children @!tree]
           (if (and (= 1 (count children)) (map? (nth children 0)))
             (assoc (nth children 0) tree-version-key tree-version)
             {tree-version-key tree-version :children children}))
         (finally
           (swap! collector/!cells (fn [cells] (apply dissoc cells keys')))))))))

;; ---------------------------------------------------------------------------
;; L2 — the projections (004B §Projections; `re-frame.freehand.test`'s
;; semantics, over the same schema)
;; ---------------------------------------------------------------------------

(defn- node-kind
  "Discriminate a MAP node per 004B's pinned order: `:tag` → element,
  else `:view-id` → view boundary, else `:children` → fragment. More than
  one primary, or none of them, is malformed — a projection over a
  malformed node fails loud rather than reading a plausible answer off a
  broken tree."
  [m]
  (let [primaries (cond-> 0
                    (contains? m :tag)     inc
                    (contains? m :view-id) inc)]
    (when (> primaries 1)
      (refuse! :rf.error/ui-tree-malformed
               (str "a tree node may carry only ONE of :tag / :view-id (the "
                    "closed node set); got "
                    (pr-str (select-keys m [:tag :view-id])))
               :no-recovery
               {:value m}))
    (cond
      (contains? m :tag)      :element
      (contains? m :view-id)  :view-boundary
      (contains? m :children) :fragment
      :else
      (refuse! :rf.error/ui-tree-malformed
               (str "a map node needs a discriminating field (:tag / :view-id "
                    "/ :children); got " (pr-str m))
               :no-recovery
               {:value m}))))

(defn- not-a-node!
  [got]
  (refuse! :rf.error/ui-tree-malformed
           (str "not a structural tree node — a projection takes a node (the "
                "value ht/render returns, or any node reached by traversing it "
                "with (tree-seq map? :children tree)); got " (pr-str got) ".")
           :no-recovery
           {:value got}))

(defn find-all
  "Every node under `tree` (the root included) for which `pred` is truthy,
  in document order; an empty vector when nothing matches.

  `pred`'s domain is **node maps only** — a raw
  `(tree-seq map? :children tree)` walk yields text as host strings, and
  those are dropped before the predicate runs, so a membership test reads
  the same on a tree with text and one without. Read fields:
  `(:tag %)` for an element, `(:view-id %)` for a boundary call."
  [tree pred]
  (filterv pred (filter map? (tree-seq map? :children tree))))

(defn find
  "The FIRST node under `tree` (the root included, document order) for
  which `pred` is truthy, or nil. `nil` threads through a missed match,
  so `(ht/attrs (ht/find tree p))` nil-puns rather than throwing."
  [tree pred]
  (first (find-all tree pred)))

(defn attrs
  "The MERGED attribute projection of a node — the ONE attribute read:

    element        → `:attrs` merged with `:events` (collision-free by
                     construction: every `:on-*` name is routed to
                     `:events`, so the domains are disjoint)
    view-boundary  → the `:props` the call site passed
    fragment       → `{}` (no attributes exist; total, not an error)
    nil            → nil

  A keyword lookup on a node reads its FIELDS, never its attributes:
  `(:on-click node)` is a field miss. An intent assertion is an equality
  check — `(is (= [:cart/add 42] (:on-click (ht/attrs node))))`."
  [node]
  (cond
    (nil? node)    nil
    (string? node) (refuse! :rf.error/ui-tree-malformed
                            (str "text content is not a node — attrs projects "
                                 "map nodes; read text with ht/text on the "
                                 "PARENT node.")
                            :no-recovery
                            {:value node})
    (map? node)    (case (node-kind node)
                     :element       (merge {} (:attrs node) (:events node))
                     :view-boundary (or (:props node) {})
                     :fragment      {})
    :else          (not-a-node! node)))

(defn- text*
  [n]
  (apply str
         (map (fn [c]
                (cond
                  (string? c) c
                  (map? c)    (text* c)
                  :else (refuse! :rf.error/ui-tree-malformed
                                 (str "a :children entry must be a node map or "
                                      "text content (a string); got "
                                      (pr-str c) ".")
                                 :no-recovery
                                 {:value c})))
              (:children n))))

(defn text
  "The concatenation of `node`'s text descendants in document order,
  descending through elements, fragments and view-boundary calls alike.
  No whitespace normalization beyond what the tree carries. `nil` → nil.

  Over a **view-boundary** node this answers the children the CALL SITE
  wrote, never the child's own rendering — the child's body did not run
  (see the namespace docstring §Children)."
  [node]
  (cond
    (nil? node)    nil
    (string? node) (refuse! :rf.error/ui-tree-malformed
                            (str "text content is not a node — it IS the text; "
                                 "call ht/text on the node that contains it.")
                            :no-recovery
                            {:value node})
    (map? node)    (do (node-kind node) (text* node))
    :else          (not-a-node! node)))

(defn intents
  "Every **event vector** the tree carries, in document order — one
  rendering's intent inventory, as data.

  The pure counterpart of [[capture-intents]]: that one says what a page
  DID dispatch when it was touched, this one says what it OFFERS to
  dispatch. Both are event vectors, so a script's stated expectation and
  a tree's inventory are comparable values — and neither stands in for
  the other, because a site can exist and never fire and a fired intent
  can carry a materialized value no tree can hold.

  Only handler sites holding a literal intent VECTOR are answered.
  A key-map site contributes each of its branches' vectors; a site
  carrying `{:rf.ui/opaque :fn}` contributes nothing, because a function
  is exactly the site whose intent is not data."
  [tree]
  (into []
        (comp (filter map?)
              (mapcat (fn [n] (vals (:events n))))
              (mapcat (fn [v] (cond (vector? v) [v]
                                    (map? v)    (filterv vector? (vals v))
                                    :else       nil))))
        (tree-seq map? :children tree)))
