(ns re-frame.bench.hicasso.front.revision-dom-cljs-test
  "THE REVISION PROP ON A CONTROLLED ELEMENT (rf2-zq8kh, the shape ruled in
  `docs/design/hicasso/studio/revision-prop-spec.md`).

  `::h/revision` — `:re-frame.hicasso/revision` — re-baselines a controlled
  text field to the model on an EXPLICIT CALLER REVISION change, and never
  on value equality. That is HD-019's reset law, kept from D016, and the
  rows below are written against the law's own wording: a revision must be
  able to change while the value is equal, and a value change under an
  unchanged revision must continue the draft rather than reset it.

  ## Why the mechanism rows run FIRST

  The design's economic case is **zero new machinery**: the transport is
  React's own per-commit controlled re-assert, off the fresh props object
  the codec mints per render (HD-004 refuses prop-object caching). Three
  React behaviours carry it and none of them is a public contract —
  the same class as the `defaultValue` mirror
  [[re-frame.bench.hicasso.front.controlled/last-rendered]] leans on.
  If they do not hold, the prop needs real machinery and the design does
  not survive contact. So §0 pins them before anything else runs, as
  DESIGN-VALIDATION rows rather than as regression rows. **They hold**:
  the re-assert repairs foreign drift on both tags, without remount.

  §0's hydration row is the one the spec demoted furthest — its claim had
  no source cite and its failure mode was node loss — and **that one does
  not hold.** React discards the server's node when a client render
  arrives mid-adoption. The row now runs a control arm to place the blame:
  an identical render carrying an UNCHANGED revision loses the node the
  same way, so the conduct is adoption's rather than the prop's, and the
  documented behaviour is the fallback the spec pre-committed — the reset
  defers past adoption, exactly as it defers past a composition. The third
  arm pins what the shipped feature actually rests on, and is green.

  ## What is a proxy, and what is the path

  A row asserting the prop is *present* would prove nothing. The reset
  path is a COMMIT that would not otherwise have happened, so §1 puts a
  `React.memo` wall between the caller and the element: with the value
  equal and the revision equal the wall bails and the field keeps its
  drift; with the value equal and the revision BUMPED the wall opens, the
  element re-commits, and React re-asserts the model over the draft.
  That is the whole of \"explicit caller revision fires a reset; value
  equality never does\", and it is the only shape in which the two halves
  are distinguishable at all.

  ## The composition rows and their standing limit

  `bench/hicasso/ime_run.cjs` drives trusted CDP composition and owns the
  fence; dispatched `CompositionEvent`s do not exercise React's
  composition plugin or the browser's composition range. What a dispatched
  event CAN reach is the half [[shadowed-props]] holds — an `input` event
  carrying `isComposing`, and the release paths — so §4 witnesses the
  deferral through those and says so, exactly as
  `arm1_controlled_grid_dom_cljs_test` §7 does. Chromium-only composition
  evidence is the harness's standing limit and covers these rows too.

  Runtime: `-dom-cljs-test`. Every row needs a real React tree over a real
  document; under `:node-test` each degrades to a stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.controlled :as controlled]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server]))

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a controlled field needs a real React tree over a real DOM — " why)))

;; ---------------------------------------------------------------------------
;; The harness
;; ---------------------------------------------------------------------------

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- drop-container! [c]
  (when-some [p (.-parentNode c)] (.removeChild p c))
  nil)

(defn- drift!
  "Move the field's value WITHOUT telling React — the foreign write an
  autofill, an extension or a non-React script performs, and the drift the
  re-assert exists to repair.

  Written through the PROTOTYPE's own `value` setter, because React
  patches the instance setter to maintain its change tracker; a plain
  `set!` would update the tracker too, after which React reads the node as
  already agreeing and the re-assert this file is measuring never runs."
  [node v]
  (let [proto (if (= "TEXTAREA" (.-tagName node))
                js/HTMLTextAreaElement.prototype
                js/HTMLInputElement.prototype)
        d     (js/Object.getOwnPropertyDescriptor proto "value")]
    (.call (.-set d) node v)
    nil))

(defn- noop-input
  "A change handler that takes nothing — the model refuses every keystroke.
  Present because a controlled element without one is uncontrolled, and
  because `install!` needs a handler slot to have anything to wrap."
  [_e])

(defn- render!
  "One commit, synchronously, so the assertion on the next line reads the
  DOM this render produced and not the one before it."
  [root el]
  (react-dom/flushSync (fn [] (.render root el)))
  nil)

(defn- field
  "The hiccup under test: a controlled text field, optionally carrying a
  revision. `tag` is `:input` or `:textarea`."
  ([tag value] (field tag value ::absent))
  ([tag value revision]
   [tag (cond-> {:id "f" :value value :on-input noop-input}
          (not= ::absent revision) (assoc :re-frame.hicasso/revision revision))]))

(defn- node [c] (.querySelector c "#f"))

;; The memo wall. Shallow prop comparison is React's own, so an equal
;; value under an equal revision BAILS — no body run, no element, no
;; commit, nothing to re-assert with. That bail is what makes the reset a
;; measurable event rather than a side effect of re-rendering.
(def ^:private walled-field
  (react/memo
   (fn [props]
     (codec/as-element
      (field (keyword (unchecked-get props "tag"))
             (unchecked-get props "value")
             (unchecked-get props "revision"))))))

(defn- walled
  [tag value revision]
  (react/createElement walled-field
                       #js {"tag" (name tag) "value" value "revision" revision}))

(defn- errors-of
  "Run `f` and return the `ex-data` of whatever it threw, or nil. The
  codec's refusals are `ex-info`s, and the id is what a row asserts on."
  [f]
  (try (f) nil (catch :default e (ex-data e))))

;; ---------------------------------------------------------------------------
;; 0 — THE MECHANISM, PINNED BEFORE ANYTHING ELSE
;;
;; Three React behaviours carry the whole transport and none is a public
;; contract: the codec mints a fresh props object per render, React marks
;; a host update on props IDENTITY rather than value, and the commit's
;; `updateInput` assigns whenever the DOM disagrees. If these rows red,
;; the zero-new-machinery premise is gone and the prop needs machinery of
;; its own — which is a design finding, not a test failure.
;; ---------------------------------------------------------------------------

(deftest the-per-commit-re-assert-repairs-foreign-drift
  (testing "THE NAMED INVARIANT ROW (spec §5 d). A controlled field's DOM
           is re-asserted on EVERY commit, so an equal-value render over a
           drifted field repairs it — and does so without remounting, which
           is what lets the reset keep the node, the focus and the
           selection. If React ever stops doing this, this row goes red
           rather than five rows going subtly wrong."
    (if-not (browser?)
      (skip! "the invariant is React's own, and needs React's own commit")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (render! root (codec/as-element (field :input "committed")))
          (let [n (node c)]
            (is (= "committed" (.-value n)))
            (drift! n "foreign")
            (is (= "foreign" (.-value n)) "the drift really landed")
            (render! root (codec/as-element (field :input "committed")))
            (is (= "committed" (.-value n))
                "the commit re-asserted the model over the drift")
            (is (identical? n (node c))
                "and kept the node — no remount, so no focus or selection
                 was thrown away to do it"))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c)))))))

(deftest the-same-chain-holds-on-a-textarea
  (testing "R4. `updateTextarea` is a parallel chain to `updateInput` and
           was unpinned in the design; the reset rides it identically, so
           it is pinned here rather than assumed from the input's row."
    (if-not (browser?)
      (skip! "a textarea's mirror is React's own")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (render! root (codec/as-element (field :textarea "committed")))
          (let [n (node c)]
            (is (= "TEXTAREA" (.-tagName n)))
            (drift! n "foreign")
            (render! root (codec/as-element (field :textarea "committed")))
            (is (= "committed" (.-value n)))
            (is (identical? n (node c))))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c)))))))

(defn- hydrate-then-render!
  "Bake server bytes for `rev-in`, adopt them, and issue ONE client render
  carrying `rev-out` **before adoption has completed** — `hydrateRoot`
  returns before the tree is adopted, which is what makes the render
  mid-adoption. Calls `k` with the server's node, the container and the
  root once the dust has settled."
  [rev-in rev-out k]
  (let [c (container!)]
    (set! (.-innerHTML c)
          (react-dom-server/renderToString
           (codec/as-element (field :input "server" rev-in))))
    (let [server-node (node c)
          root        (react-dom-client/hydrateRoot
                       c (codec/as-element (field :input "server" rev-in)))]
      (drift! server-node "draft")
      (.render root (codec/as-element (field :input "server" rev-out)))
      (js/setTimeout
       (fn []
         (react-dom/flushSync (fn [] nil))
         (k server-node c root))
       0))))

(deftest a-reset-around-hydration-adoption
  (testing "R3, WITNESS-GATED INTENT rather than assertion (spec §4). The
           design claimed a revision arriving mid-adoption lands on the
           first post-adoption commit, on the SERVER's node. That claim had
           no source cite and the failure mode if it were wrong was node
           loss, so the spec demoted it and pre-committed the fallback
           rather than improvising one. **It is wrong, and the fallback is
           what ships.**

           React discards the server's node when a client render arrives
           mid-adoption. The control arm below is what makes that a finding
           about ADOPTION rather than about the revision: an identical
           mid-adoption render carrying an UNCHANGED revision loses the node
           in exactly the same way. So the revision neither causes the deopt
           nor escapes it, and the documented conduct is the pre-committed
           one — a reset arriving mid-adoption defers past adoption, the
           same shape the IME carve-out already has. The third arm is the
           one the shipped feature rests on, and it is green: once adoption
           is complete a bump keeps the node and lands the reset."
    (if-not (browser?)
      (skip! "hydration needs a document to adopt")
      (async done
        ;; ARM A — the control. Same render, revision UNCHANGED.
        (hydrate-then-render!
         "rev-1" "rev-1"
         (fn [server-node c root]
           (let [control-kept? (identical? server-node (node c))]
             (react-dom/flushSync #(.unmount root))
             (drop-container! c)
             ;; ARM B — the variable. Same render, revision BUMPED.
             (hydrate-then-render!
              "rev-1" "rev-2"
              (fn [server-node c root]
                (let [bumped-kept? (identical? server-node (node c))]
                  (is (= control-kept? bumped-kept?)
                      "THE GATED CLAIM, ADJUDICATED: whatever React does to
                       the server's node mid-adoption, it does it with and
                       without a revision change alike. The revision is not
                       what decides it, so 'the reset lands on the server's
                       node' was never the revision's claim to make.")
                  (is (false? bumped-kept?)
                      "and what React actually does is DISCARD it — the
                       deopt R3 named as the failure mode. Recorded here as
                       the conduct rather than shipped as a claim.")
                  (is (= "server" (.-value (node c)))
                      "the field still shows the model afterwards; what is
                       lost is the NODE's identity, not the value — so the
                       cost is focus and selection, which is exactly why the
                       documented conduct is to defer past adoption")
                  (react-dom/flushSync #(.unmount root))
                  (drop-container! c)
                  ;; ARM C — the one the feature rests on. Adoption first,
                  ;; THEN the bump.
                  (let [c2 (container!)]
                    (set! (.-innerHTML c2)
                          (react-dom-server/renderToString
                           (codec/as-element (field :input "server" "rev-1"))))
                    (let [server-node2 (node c2)
                          root2 (react-dom-client/hydrateRoot
                                 c2 (codec/as-element (field :input "server" "rev-1")))]
                      (js/setTimeout
                       (fn []
                         (react-dom/flushSync (fn [] nil))
                         (try
                           (is (identical? server-node2 (node c2))
                               "adoption completed on the server's node, with
                                nothing else in flight")
                           (drift! (node c2) "draft")
                           (render! root2 (codec/as-element
                                           (field :input "server" "rev-2")))
                           (is (identical? server-node2 (node c2))
                               "AND THE POST-ADOPTION BUMP KEEPS IT — no
                                remount, so the focus and the selection
                                survive the reset on a hydrated node exactly
                                as they do on a client-rendered one")
                           (is (= "server" (.-value (node c2)))
                               "with the reset landing: the drift is gone and
                                the field is re-baselined to the model")
                           (finally
                             (react-dom/flushSync #(.unmount root2))
                             (drop-container! c2)
                             (done))))
                       0)))))))))))))

;; ---------------------------------------------------------------------------
;; 1 — THE RESET LAW AT THE ELEMENT
;;
;; The memo wall is the point. Without it every render commits and the two
;; halves of the law are indistinguishable; with it, a commit happens only
;; because the caller changed the revision.
;; ---------------------------------------------------------------------------

(deftest a-revision-change-resets-the-field-through-a-memo-wall
  (testing "THE ACTUAL RESET PATH (spec §5 a). Value equal, revision
           bumped: the wall opens, the element re-commits, and React
           re-asserts the model over the draft in the field. The node is
           kept and the focus is kept — a reset is not a remount."
    (if-not (browser?)
      (skip! "the wall needs a real commit to bail out of")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (render! root (walled :input "committed" "rev-1"))
          (let [n (node c)]
            (.focus n)
            (drift! n "a draft the model never took")
            (is (= "a draft the model never took" (.-value n)))
            (render! root (walled :input "committed" "rev-2"))
            (is (= "committed" (.-value n))
                "the draft is discarded and the field re-baselined to the model")
            (is (identical? n (node c)) "WITHOUT REMOUNT — the same node")
            (is (identical? n (.-activeElement js/document))
                "and the focus survived, which a remount would have taken")
            (is (= (count "committed") (.-selectionStart n))
                "the caret lands at end-of-model on the commit carrying the
                 reset — the platform's own conduct for a `value` assignment,
                 which D016's caret clause licenses")
            (is (not (.hasAttribute n "revision"))
                "and the trigger left no trace: a reset is not an attribute"))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c)))))))

(deftest an-unchanged-revision-never-resets
  (testing "spec §5 b, SCOPED TO AT REST. An unchanged revision does not
           itself reset anything — behind the wall it produces no commit at
           all, so the draft stands. The scope matters and the spec insists
           on it: an unchanged revision plus foreign drift plus any
           UNRELATED re-render does rewrite the field, because a controlled
           field's DOM is re-asserted on every commit. The revision
           GUARANTEES a commit through the wall; it is not the only source
           of one."
    (if-not (browser?)
      (skip! "the bail-out is React's own")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (render! root (walled :input "committed" "rev-1"))
          (let [n (node c)]
            (drift! n "a draft")
            (render! root (walled :input "committed" "rev-1"))
            (is (= "a draft" (.-value n))
                "no revision change, no commit, no reset — the draft continues"))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c)))))))

(deftest a-value-change-never-consults-the-revision
  (testing "THE TRIGGER IS THE ONLY THING THE LAW FIXES AT THIS PATH, and
           this row is scoped to that. D016's draft-continuation clause is
           the BUFFERED CONTROLLER's protocol — unbuilt, deferred past v0
           by the charter — and HD-019 kept its trigger sentence for the
           element and nothing else. At a raw element there is no buffered
           draft to continue: the model IS the field, so a `:value` change
           re-asserts, as it always did. What this pins is the half that
           does belong here — the value change is delivered as ordinary
           controlled conduct and nothing consults the revision on the way.
           Resets are by explicit caller revision, NEVER by value equality."
    (if-not (browser?)
      (skip! "needs a real commit")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (render! root (walled :input "first" "rev-1"))
          (let [n (node c)]
            (is (= "first" (.-value n)))
            (render! root (walled :input "second" "rev-1"))
            (is (= "second" (.-value n))
                "the model moved and the field shows it — ordinary controlled
                 conduct, and NOT a reset: nothing consulted the revision")
            (is (identical? n (node c))))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c)))))))

(deftest equal-but-fresh-revision-values-are-inert
  (testing "spec §5 c. The comparison is CLJS `=`, which is React.memo's
           own on these values, so two distinct-but-equal revision objects
           are one revision. This is why the authored-data rule matters:
           if it would be a good instance key it is a good revision value —
           a domain fact written by events, never a render-order index,
           never a counter minted in render, never `random-uuid`, each of
           which would reset the field on every render."
    (if-not (browser?)
      (skip! "needs a real commit")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (render! root (walled :input "committed" "rev-1"))
          (let [n (node c)]
            (drift! n "a draft")
            (render! root (walled :input "committed" (str "rev-" 1)))
            (is (= "a draft" (.-value n))
                "a freshly built but EQUAL revision reset nothing")
            (render! root (walled :input "committed" "rev-2"))
            (is (= "committed" (.-value n))
                "the control: a genuinely different revision does reset"))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c)))))))

;; ---------------------------------------------------------------------------
;; 2 — THE REFUSALS
;; ---------------------------------------------------------------------------

(deftest a-revision-on-a-non-controlled-element-is-refused
  (testing "spec §5 e / §3.5. Per-render, in the codec's error shape, on
           the same cost shape as `check-ref!`. The acceptance predicate is
           `controlled-text-tag?` — the one that already chooses the shadow
           component, reused rather than duplicated."
    (doseq [[what hiccup]
            [["a :div"
              [:div {:re-frame.hicasso/revision "r" :value "x" :on-input noop-input}]]
             ["a value-less <input>"
              [:input {:re-frame.hicasso/revision "r" :on-input noop-input}]]
             ["an <input> with a nil :value, which is the same statement"
              [:input {:re-frame.hicasso/revision "r" :value nil :on-input noop-input}]]
             ["a value-less checkbox, written the idiomatic way"
              [:input {:type :checkbox :checked true
                       :re-frame.hicasso/revision "r" :on-input noop-input}]]
             ["a <select>, which has no text cursor and no mirror"
              [:select {:re-frame.hicasso/revision "r" :value "x" :on-input noop-input}]]]]
      (is (= :rf.error/hicasso-revision-not-controlled
             (:rf.error/id (errors-of #(codec/as-element hiccup))))
          what)))
  (testing "and the coverage is stated honestly rather than overstated.
           `controlled-text-tag?` is deliberately TYPE-BLIND — tag plus a
           non-nil emitted `value` — so these are ACCEPTED and the revision
           is simply inert on them. 'A checkbox is refused' is the wrong
           sentence; 'a value-less checkbox is refused' is the right one."
    (doseq [[what hiccup]
            [["a checkbox carrying a form-submission value"
              [:input {:type :checkbox :value "yes" :checked true
                       :re-frame.hicasso/revision "r" :on-input noop-input}]]
             ["an <input type=number>, with caret semantics that do not apply"
              [:input {:type :number :value "1"
                       :re-frame.hicasso/revision "r" :on-input noop-input}]]]]
      (is (nil? (errors-of #(codec/as-element hiccup))) what))))

(deftest a-remainder-may-not-arm-a-reset
  (testing "R1 — THE `:&` DOOR, the repair the adversarial pass called the
           big one. `merge-caller` denies a remainder only the structural
           slots plus the slots owned by literals the element wrote, so a
           `::h/revision` in a remainder survives the merge. Reading it
           PRE-merge is what stops it arming anything; refusing it is the
           lane's posture, because a remainder asking for conduct it cannot
           be given should learn that from a diagnostic rather than from a
           field that never resets."
    (is (= :rf.error/hicasso-revision-from-remainder
           (:rf.error/id (errors-of
                          #(codec/as-element
                            [:input {:value "committed" :on-input noop-input
                                     :& {:re-frame.hicasso/revision "hostile"}}]))))
        "a hostile remainder cannot force a field re-baseline the element's
         author never wrote"))
  (testing "and the literal wins when both are present — no refusal, because
           `merge-caller`'s owned-literal law has already denied the
           remainder's copy on the canonical slot `revision`, so the key
           that survives to be tested is the author's own"
    (if-not (browser?)
      (skip! "the winning half is a behaviour, not a shape")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (is (nil? (errors-of
                     #(render! root
                               (codec/as-element
                                [:input {:id "f" :value "committed" :on-input noop-input
                                         :re-frame.hicasso/revision "mine"
                                         :& {:re-frame.hicasso/revision "hostile"}}]))))
              "both present: the literal is what arms, and nothing is refused")
          (let [n (node c)]
            (drift! n "a draft")
            (render! root (codec/as-element
                           [:input {:id "f" :value "committed" :on-input noop-input
                                    :re-frame.hicasso/revision "mine-2"
                                    :& {:re-frame.hicasso/revision "hostile"}}]))
            (is (= "committed" (.-value n)) "and it is the literal's bump that resets")
            (is (not (.hasAttribute n "revision"))
                "with neither copy reaching the DOM"))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c)))))))

;; ---------------------------------------------------------------------------
;; 3 — NEVER A DOM ATTRIBUTE
;; ---------------------------------------------------------------------------

(deftest the-revision-is-never-a-dom-attribute
  (testing "spec §5 f. The strip is codec-level and consumed before
           emission, and the SSR entry runs the SAME codec under
           `renderToString` — one renderer in two places — so the server
           bytes cannot carry a revision by construction rather than by a
           server-side special case."
    (let [bytes (react-dom-server/renderToString
                 (codec/as-element (field :input "committed" "rev-1")))]
      (is (not (re-find #"revision" bytes))
          (str "no revision in the server bytes: " bytes))
      (is (re-find #"value=\"committed\"" bytes)
          "while the model value is there, as the hydrated-controlled-input
           witness already asserts"))
    (if-not (browser?)
      (skip! "the rendered half needs a rendered node")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (render! root (codec/as-element (field :input "committed" "rev-1")))
          (let [n (node c)]
            (is (not (.hasAttribute n "revision")))
            (is (not (re-find #"revision" (.-outerHTML n)))
                (str "nothing named revision in the rendered element: "
                     (.-outerHTML n))))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c))))))
  (testing "and the marker the codec stashes for `install!` does not survive
           it either — it exists for the length of one call"
    (let [el (codec/as-element (field :input "committed" "rev-1"))]
      (is (undefined? (unchecked-get (.-props el) controlled/revision-slot))
          "deleted as it was read")
      (is (undefined? (unchecked-get (.-props el) "revision"))
          "and never emitted under its own name")))
  (testing "every OTHER spelling is an ordinary attribute, which is the
           documented honest loss: the exact namespaced keyword or nothing"
    (let [bytes (react-dom-server/renderToString
                 (codec/as-element
                  [:input {:value "committed" :on-input noop-input :revision "rev-1"}]))]
      (is (re-find #"revision=\"rev-1\"" bytes)
          (str "a misspelled bare :revision is silent and becomes an
                ordinary DOM attribute: " bytes)))))

;; ---------------------------------------------------------------------------
;; 4 — THE COMPOSITION DEFERRAL
;;
;; The half a dispatched event can reach. `ime_run.cjs` owns the fence.
;; ---------------------------------------------------------------------------

(defn- composing-input!
  "An `input` event carrying `isComposing`, which is exactly what
  `front.controlled/composing-input?` reads off the NATIVE event."
  [n v]
  (drift! n v)
  (.dispatchEvent n (js/InputEvent. "input" #js {:bubbles true :isComposing true}))
  nil)

(deftest a-revision-arriving-mid-composition-defers-to-the-close
  (testing "spec §3.4 / §5 h. The argument is mechanical before it is
           philosophical: there is no cancel primitive to build an
           immediate variant from, and the only immediate write available
           — `element.value` — silently ABORTS the composition, which on a
           normalising field corrupts the commit (the measured SSHSH row).
           So while the shadow is held the glass keeps showing the
           composition, and the reset lands at the close. Between the bump
           and the close the model is correct throughout; the glass is not.
           That is honest loss #1, and this row is what states it."
    (if-not (browser?)
      (skip! "a composition needs a real field to compose into")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (render! root (codec/as-element (field :input "committed" "rev-1")))
          (let [n (node c)]
            (.focus n)
            (composing-input! n "kana-in-flight")
            (is (= "kana-in-flight" (.-value n))
                "the shadow holds the live draft, so React's own restore
                 finds nothing to write")
            (render! root (codec/as-element (field :input "committed" "rev-2")))
            (is (= "kana-in-flight" (.-value n))
                "THE DEFERRAL: the revision bumped mid-composition and the
                 exchange was NOT destroyed — no immediate write, so no
                 silent abort")
            (.dispatchEvent n (js/FocusEvent. "focusout" #js {:bubbles true}))
            (react-dom/flushSync (fn [] nil))
            (is (= "committed" (.-value n))
                "and the release converges the field to the then-current
                 model — blur is one of the four exits, and every one of
                 them converges"))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c)))))))

(deftest the-deferral-cannot-strand-the-field-and-promises-no-more
  (testing "R2 — THE OVERCLAIM, STRUCK. 'The reset cannot be lost' is
           false: on an ACCEPTING field the model keeps taking every
           composing update while the shadow is held, so a post-bump
           dispatch — including the composition's own final input event —
           supersedes the reset by ordinary event order, exactly as it
           would at rest, and discarded pre-reset content can ride back in
           through the draft echo. What IS true is that the deferral cannot
           STRAND the field: every exit converges it to the then-current
           model. This row pins the honest conduct, with the trajectory
           recorded, because the row as originally specified reds by
           construction."
    (if-not (browser?)
      (skip! "the echo needs a real event order")
      (let [c      (container!)
            root   (react-dom-client/createRoot c)
            ;; An ACCEPTING model: whatever the field says, the model takes.
            !model (atom "committed")
            accept (fn [e] (reset! !model (.. e -target -value)))
            paint! (fn [rev]
                     (render! root (codec/as-element
                                    [:input {:id "f" :value @!model :on-input accept
                                             :re-frame.hicasso/revision rev}])))]
        (try
          (paint! "rev-1")
          (let [n (node c)]
            (.focus n)
            (composing-input! n "kana")
            (is (= "kana" @!model) "the accepting model took the composing update")
            ;; The caller rejects the edit: it restores the model AND bumps
            ;; the revision, which is the only spelling the law accepts.
            (reset! !model "committed")
            (paint! "rev-2")
            (is (= "kana" (.-value n))
                "deferred to the close, as §3.4 requires — the glass still
                 shows the composition")
            ;; ...and then the exchange produces one more update, which is
            ;; what the commit's own final input event also is.
            (composing-input! n "kana-more")
            (is (= "kana-more" @!model)
                "the post-bump dispatch moved the model, by ordinary event
                 order, exactly as it would at rest")
            ;; The app re-renders on that model change. It happens AFTER the
            ;; handler rather than inside it, and that ordering is
            ;; re-frame2's own: `dispatch` drains on a macrotask (Spec 002
            ;; §Drain scheduling), so the model has not moved by the end of
            ;; the change handler and the re-render is a separate turn. The
            ;; revision does not move — the caller bumped it once, and one
            ;; bump is one reset.
            (paint! "rev-2")
            (.dispatchEvent n (js/FocusEvent. "focusout" #js {:bubbles true}))
            (react-dom/flushSync (fn [] nil))
            (is (= "kana-more" (.-value n))
                "AND THE RESET DID NOT SURVIVE IT. The release converges the
                 field to the THEN-CURRENT model, which the post-bump update
                 already moved — not the model the reset produced, and
                 discarded pre-reset content rode back in through the draft
                 echo. This is the correct semantics and it is also a real
                 outcome; R2 exists so the prose says so instead of claiming
                 the reset cannot be lost.")
            (is (= @!model (.-value n))
                "what IS true: the field is converged to the model rather
                 than stranded against it. Every exit converges."))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c)))))))

;; ---------------------------------------------------------------------------
;; 5 — NO THIRD `flushSync` SITE
;; ---------------------------------------------------------------------------

(deftest the-reset-adds-no-flush-site
  (testing "spec §5 j / §6.5. HD-019 grants the `flushSync` exception to an
           AUDITED MECHANISM rather than to a count. `converge!` holds the
           one `flushSync` expression in the namespace and is reached from
           exactly two call sites — the keystroke path and the
           `compositionend` path — at most one per event. The revision adds
           no call site to that set: out-of-band resets ride ORDINARY
           commits, which is what this row measures. A reset arriving with
           no event at all must move the field without any handler running,
           because there is no handler to run it in."
    (if-not (browser?)
      (skip! "an ordinary commit needs a real commit")
      (let [c     (container!)
            root  (react-dom-client/createRoot c)
            !runs (atom 0)
            paint! (fn [rev]
                     (render! root
                              (codec/as-element
                               [:input {:id "f" :value "committed"
                                        :on-input (fn [_e] (swap! !runs inc))
                                        :re-frame.hicasso/revision rev}])))]
        (try
          (paint! "rev-1")
          (let [n (node c)]
            (drift! n "a draft")
            (paint! "rev-2")
            (is (= "committed" (.-value n)) "the reset landed")
            (is (zero? @!runs)
                "and no change handler ran to land it — so no converge, and
                 no flush: the out-of-band reset rode an ordinary commit"))
          (finally (react-dom/flushSync #(.unmount root)) (drop-container! c)))))))
