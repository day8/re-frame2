(ns re-frame.freehand.pilot-overlay-cljs-test
  "CASE B, the HEADLESS half — every §B.2 requirement of the fitness
  harness, enumerated one test per row, over the pilot library in
  [[re-frame.freehand.pilot-overlay]].

  The harness's own R-B13 says what belongs here and what does not:
  open/close logic, dismissal policy and placement INPUTS are assertable
  as data; actual geometry and focus are owed to mounted tests. So this
  file makes no claim about a pixel and no claim about the active element
  — those are `pilot-overlay-dom-cljs-test`'s, and each row below names
  which half it is proving.

  It runs on the JVM and in Node from one `.cljc`. That is not a
  convenience: the JVM run IS the R-B10 host-boundary proof, because a
  library whose render path reached `js/document` could not produce a
  structural tree at all."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.pilot-overlay :as ui]
            [re-frame.live-frame :as live-frame]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(def ^:private fid :rf/default)
(def ^:private other-fid :acme/second-frame)

(defn- seed! [db] (frame/replace-app-db! fid db))
(defn- app-db [] (frame/frame-app-db-value fid))
(defn- records [] (get (app-db) ui/records-root))
(defn- record [address] (get (records) [ui/dropdown-kind address]))
(defn- send! ([ev] (send! ev fid)) ([ev f] (rf/dispatch-sync ev {:frame f})))

(defn- render-in!
  "Render `form` in frame `f` — one candidate, one structural walk, and NO
  commit. `v/sub` is legal only inside an active render, and the
  structural surface walks a body without being a host, so the two are
  composed here exactly as the controller suites compose them."
  [f form]
  (let [cand (cell/candidate (cell/cell :acme/probe) f)]
    (cell/with-capture cand (fn [] (t/render form)))))

(defn- render! [form] (render-in! fid form))

(defn- part?
  "Does `node` carry `data-part` = `p`? Guarded on `map?` because a
  structural tree holds text alongside nodes and `t/attrs` refuses a
  string — deliberately, so a projection cannot silently answer for a
  value that has none."
  [p node]
  (and (map? node) (= p (get (t/attrs node) :data-part))))

(defn- node-with-part
  "The FIRST node carrying `data-part` = `p`."
  [tree p]
  (t/find tree (partial part? p)))

(defn- nodes-with-part [tree p]
  (t/find-all tree (partial part? p)))

(defn- attrs-of [tree p] (t/attrs (node-with-part tree p)))

(defn- intent [tree p prop] (get (attrs-of tree p) prop))

(defn- fire!
  "Dispatch the intent a part carries under `prop`, filling the
  projection positions a live payload would."
  [tree p prop & args]
  (let [ev (intent tree p prop)]
    (send! (if (seq args) (into (vec (butlast ev)) args) ev))))

(def ^:private options ui/format-options)

(def ^:private address [:toolbar :doc-1 :format])

(defn- dropdown-form
  ([] (dropdown-form nil))
  ([selected]
   [ui/dropdown {:name      "format"
                 :label     "Export as…"
                 :control   address
                 :options   options
                 :selected  selected
                 :gap       4
                 :on-change [:toolbar/format-chosen :doc-1]}]))

(defn- init! []
  (ui/register!)
  (ui/register-app!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :init-fn init!}))

;; ===========================================================================
;; R-B1 — measure-then-place: the INPUTS are data, the timing is declared
;; ===========================================================================

(deftest r-b1-placement-inputs-and-the-measuring-moment-are-declared-data
  (testing "R-B1 (headless half). Placement needs DOM measurement, so the
            design must NAME the phase it happens in. The name is not
            prose here: the behavior is registered `{:timing :layout}` —
            the closed value that means `before the browser paints` — and
            everything the measurement consumes rides as `:config`, which
            the structural tree records verbatim. The baseline's answer to
            the same requirement was to park the body at -10000px until it
            had measured; there is nothing to park when the measurement
            precedes the paint."
    (seed! {})
    (let [definition (behaviors/definition ui/anchor-box)
          tree       (render! (dropdown-form))
          boundary   (t/find tree #(= :re-frame.freehand/behavior (:view-id %)))]
      (is (= :layout (:timing definition))
          "the measuring moment is the declared, closed `:layout`")
      (is (contains? behaviors/timings :layout)
          "non-vacuous: `:layout` is a value the substrate's roster admits")
      (is (some? boundary) "the use site is a boundary node in the tree")
      (is (= {:open? false :gap 4} (get-in boundary [:props :config]))
          "the placement inputs are DATA a structural test reads")
      (is (= [ui/dropdown-kind address] (get-in boundary [:props :target]))
          "and the connection is addressed by the caller's domain identity")
      (is (= ui/anchor-box (get-in boundary [:props :use]))
          "the registered id rides the tree; the implementation does not"))))

(deftest r-b1-opening-moves-the-configuration-so-the-measurement-reruns
  (testing "R-B1 (headless half). A behavior's `:update` runs when the
            committed config MOVES by `rf=` and not otherwise, so a
            measurement that must happen on open has to be caused by
            something in the config. The open flag IS in the config, which
            is what makes `re-measure exactly when it opens, and at no
            other commit` a mechanical consequence rather than a hope."
    (seed! {})
    (let [closed (render! (dropdown-form))]
      (fire! closed "anchor" :on-click)
      (let [open* (render! (dropdown-form))
            cfg   (fn [tree] (get-in (t/find tree #(= :re-frame.freehand/behavior (:view-id %)))
                                     [:props :config]))]
        (is (= {:open? false :gap 4} (cfg closed)))
        (is (= {:open? true :gap 4} (cfg open*)))
        (is (not= (cfg closed) (cfg open*))
            "the configuration moved, so the measurement is due")))))

;; ===========================================================================
;; R-B2 — escaping clipping and stacking with no emulation warts
;; ===========================================================================

(deftest r-b2-the-panel-declares-a-top-layer-promotion-and-no-z-index-ladder
  (testing "R-B2 (headless half). The mechanism must be immune to ancestor
            transforms, which `position: fixed` emulation is not. The
            declaration says so: the panel carries a valid `:popover` mode
            and the reserved top-layer fact, and the whole library emits no
            `z-index` at all — there is no ladder to coordinate because
            nothing is competing inside a stacking context. The browser
            half proves the escape; this half proves there is no emulation
            to go wrong."
    (seed! {})
    (let [tree  (render! (dropdown-form))
          panel (node-with-part tree "panel")
          all   (filter map? (tree-seq map? :children tree))]
      (is (= "auto" (:popover (t/attrs panel)))
          "the panel is a popover, which is what makes the property legal")
      (is (= {:popover-open? false} (:rf.ui/top-layer panel))
          "and the desired state is the reserved structural fact, not an attribute")
      (is (empty? (filter #(contains? (:style (t/attrs %)) :z-index) all))
          "no element in the library's output declares a z-index")
      (is (empty? (filter #(= "re-frame.freehand.web" (namespace %))
                          (filter keyword? (mapcat (comp keys t/attrs) all))))
          "and no web-qualified property survived into :attrs"))))

;; ===========================================================================
;; R-B3 — outside-interaction dismiss, with a listener lifetime of ZERO
;; ===========================================================================

(deftest r-b3-the-dismissal-policy-is-one-idempotent-registered-event
  (testing "R-B3 (headless half). The baseline installs a document click
            listener in `open!` and removes it in `close!` — so an unmount
            while open leaks it permanently. Here dismissal is the
            PLATFORM's (light dismiss on an `:auto` popover) and the
            application's only job is to reconcile the state that drives
            the desired property. That reconciliation is one registered
            event, and it is idempotent: a dismissal the application
            already caused finds nothing to close and writes nothing."
    (seed! {})
    (let [tree (render! (dropdown-form))]
      (fire! tree "anchor" :on-click)
      (is (true? (:open? (record address))) "open")
      (send! [:acme.ui.dropdown/dismissed [ui/dropdown-kind address]])
      (is (nil? (record address)) "the browser's dismissal closed it")
      (let [before (app-db)]
        (send! [:acme.ui.dropdown/dismissed [ui/dropdown-kind address]])
        (is (= before (app-db))
            "and a second dismissal is inert — the record is already gone")))))

(deftest r-b3-dismissal-costs-the-library-no-lifecycle-at-all
  (testing "R-B3 (headless half). A listener whose lifetime must be
            reasoned about is a listener that can leak. The strongest form
            of `its lifetime is bounded by construction` is that it does
            not exist — and the whole dismissal path here is: a popover
            mode on an attribute, a native event the caller reconciles, and
            one registered handler. The behavior beside it declares no
            `:disconnect`, because it acquires nothing that would need one.
            The browser half turns this into an exact integer."
    (let [definition (behaviors/definition ui/anchor-box)
          tree       (do (seed! {}) (render! (dropdown-form)))]
      (is (nil? (:disconnect definition))
          "the library's only host boundary has nothing to release")
      (is (= "auto" (:popover (attrs-of tree "panel")))
          "dismissal is the platform's, off the popover mode")
      (is (= [:acme.ui.dropdown/toggle-reported [ui/dropdown-kind address]]
             (:on-toggle (attrs-of tree "panel")))
          "and the library's whole contribution is reconciling the report"))))

(deftest r-b3-the-dismissal-handshake-is-data-because-newstate-cannot-be
  (testing "R-B3 (headless half), and a FINDING. Reconciling a
            browser-initiated dismissal means reading `ToggleEvent.newState`,
            and there is no reserved projection for it — the closed roster
            is `::v/value` / `::v/checked` / `::v/key`. The sanctioned
            escape for exactly this shape is `v/event`, and a view that
            reaches for it can no longer render on the structural host at
            all, which for an overlay means no SSR and no headless
            assertion. The row pins BOTH halves: the refusal is real, and
            this library therefore counts reports instead of reading them."
    (seed! {})
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (t/render [:div {:popover :auto
                                  :on-toggle (v/event [_] [:acme.ui.dropdown/dismissed :x])}]))
        "a v/event callback at a handler site is refused by the structural render")
    (is (= [:acme.ui.dropdown/dismissed :x]
           (:on-click (t/attrs (t/render [:div {:on-click [:acme.ui.dropdown/dismissed :x]}]))))
        "non-vacuous: an ordinary event vector at the same site renders fine")

    (testing "and the handshake the library uses instead: the first report
              of a session acknowledges, a later one closes, and a report
              after the application already closed is inert"
      (let [k [ui/dropdown-kind address]]
        (send! [:acme.ui.dropdown/anchor-clicked k])
        (send! [:acme.ui.dropdown/toggle-reported k])
        (is (= {:open? true :active 0 :acked? true} (record address))
            "the opening report is acknowledged, not acted on")
        (send! [:acme.ui.dropdown/toggle-reported k])
        (is (nil? (record address)) "a second report is the browser dismissing")
        (let [before (app-db)]
          (send! [:acme.ui.dropdown/toggle-reported k])
          (is (= before (app-db)) "and a report with no record left is inert"))))))

;; ===========================================================================
;; R-B4 — the focus contract, per overlay class
;; ===========================================================================

(deftest r-b4-each-overlay-class-declares-the-platform-primitive-that-owns-focus
  (testing "R-B4 (headless half). The harness sets the bar at the native
            `<dialog>` / popover behaviour set, which re-com meets on ZERO
            of its six points. Meeting it is a declaration, not an
            implementation: the modal is a `<dialog>` promoted on the modal
            axis, so focus entry, containment, Escape and focus return are
            the platform's; the anchored panel is an `:auto` popover, which
            takes no focus and therefore leaves the anchor holding it. The
            browser half reads all six back off a live document."
    (seed! {:toolbar {:doc-1 {:deleting? true}}})
    (let [tree   (render! [ui/confirm {:name      "doc-1"
                                       :title     "Delete this document?"
                                       :open?     true
                                       :on-cancel [:toolbar/delete-cancelled :doc-1]
                                       :on-accept [:toolbar/delete-accepted :doc-1]}])
          root   (node-with-part tree "root")
          panel  (node-with-part (render! (dropdown-form)) "panel")]
      (is (= :dialog (:tag root)) "the modal is a <dialog>")
      (is (= {:modal-open? true} (:rf.ui/top-layer root))
          "promoted on the MODAL axis — showModal(), not the :open attribute")
      (is (= [:toolbar/delete-cancelled :doc-1] (:on-close (t/attrs root)))
          "and the platform's own dismissal is reconciled by the caller's intent")
      (is (= "auto" (:popover (t/attrs panel)))
          "the anchored panel is an :auto popover — light dismiss, no focus move")
      (is (nil? (:autofocus (t/attrs panel)))
          "it asks for no focus, so the anchor keeps it"))))

;; ===========================================================================
;; R-B5 — the keyboard grammar, whole, as one registered event
;; ===========================================================================

(deftest r-b5-the-single-dropdown-parity-set-is-one-event-driven-by-data
  (testing "R-B5. re-com's richer dropdown hand-rolls Enter / Escape /
            arrows / PageUp / PageDown over eleven local ratoms. Here the
            same grammar is ONE registered event whose only live input is
            `::v/key` — so every row below is an equality assertion with no
            browser, no ratom and no callback body."
    (seed! {})
    (let [tree (render! (dropdown-form))
          key! (fn [k] (fire! tree "anchor" :on-key-down k))
          st   #(record address)]
      (is (nil? (st)) "closed to begin with")

      (testing "closed: ArrowDown opens on the first option, ArrowUp on the last"
        (key! "ArrowDown")
        (is (= {:open? true :active 0} (st)))
        (send! [:acme.ui.dropdown/dismissed [ui/dropdown-kind address]])
        (key! "ArrowUp")
        (is (= {:open? true :active (dec (count options))} (st))))

      (testing "open: the arrows move the highlight and CLAMP at the ends"
        (key! "Home")
        (is (= 0 (:active (st))))
        (key! "ArrowUp")
        (is (= 0 (:active (st))) "clamped at the top")
        (key! "ArrowDown")
        (is (= 1 (:active (st))))
        (key! "End")
        (is (= (dec (count options)) (:active (st))))
        (key! "ArrowDown")
        (is (= (dec (count options)) (:active (st))) "clamped at the bottom"))

      (testing "Enter commits the highlighted option and closes"
        (key! "Home")
        (key! "Enter")
        (is (nil? (st)) "closed")
        (is (= (:value (first options)) (get-in (app-db) [:toolbar :doc-1 :format]))
            "and the caller's own event carried the chosen value"))

      (testing "Escape cancels: it closes without committing anything"
        (key! "ArrowDown")
        (key! "ArrowDown")
        (let [before (get-in (app-db) [:toolbar :doc-1 :format])]
          (key! "Escape")
          (is (nil? (st)) "closed")
          (is (= before (get-in (app-db) [:toolbar :doc-1 :format]))
              "and the caller's value is exactly what it was")))

      (testing "a key outside the grammar settles an event that changes nothing"
        (key! "ArrowDown")
        (let [before (app-db)]
          (key! "q")
          (is (= before (app-db))
              "the cost of a data keyboard site, stated rather than hidden"))))))

;; ===========================================================================
;; R-B6 — anchor-tracking honesty
;; ===========================================================================

(deftest r-b6-the-tracking-frequency-is-declared-and-the-escape-is-bounded
  (testing "R-B6. `positions once, at the commit that moved` is an
            acceptable contract if DECLARED; a silent every-frame loop is
            not. The declaration is mechanical: the only moments the
            behavior runs at are the two the substrate enumerates, and the
            only other way to reach it is a finite command roster
            addressed by the use site's own semantic id. There is no
            interval, no observer and no animation-frame arm anywhere in
            the definition — the browser half counts them."
    (let [definition (behaviors/definition ui/anchor-box)]
      (is (= #{:reposition} (set (keys (:commands definition))))
          "one command, named, and nothing else reaches the connection")
      (is (= :layout (:timing definition)))
      (is (some? (:connect definition)) "it measures when it connects")
      (is (some? (:update definition)) "and when the configuration moves")
      (is (nil? (:disconnect definition))
          "and releases nothing, because it acquired nothing"))))

;; ===========================================================================
;; R-B7 — one owner for the open state, per instance, collision-free
;; ===========================================================================

(deftest r-b7-open-state-has-exactly-one-owner-and-n-instances-do-not-collide
  (testing "R-B7. The baseline offers a caller-supplied `:model` OR an
            internal default ratom — two ways to own one flag, which is
            how a library acquires callers who own it differently. Here
            there is one way: the record lives at (kind, :control), the
            address is REQUIRED, and there is no prop that would let a
            caller bring its own. Two instances differ only in the address
            they were given."
    (seed! {})
    (let [a [:toolbar :doc-1 :format]
          b [:toolbar :doc-2 :format]
          form (fn [addr nm] [ui/dropdown {:name nm :label "Export as…" :control addr
                                           :options options
                                           :on-change [:toolbar/format-chosen :doc-1]}])]
      (fire! (render! (form a "a")) "anchor" :on-click)
      (is (= {:open? true :active 0} (record a)))
      (is (nil? (record b)) "the second instance is untouched")
      (fire! (render! (form b "b")) "anchor" :on-click)
      (is (= 2 (count (records))) "two records, two addresses, no coordination")

      (testing "and the props map is CLOSED, so a caller cannot smuggle a
                second owner in beside the address"
        (is (thrown? #?(:clj Throwable :cljs :default)
                     (render! [ui/dropdown {:name "c" :label "x" :control a
                                            :options options
                                            :on-change [:toolbar/format-chosen :doc-1]
                                            :model (atom nil)}])))))))

(deftest r-b7-the-address-is-required-rather-than-derived
  (testing "R-B7. A renderer-derived anchor would turn a sort, a rename or
            a parent extraction into a silent state migration, so the
            substrate refuses an absent `:control` instead of defaulting
            it — a default would collide every addressless control onto one
            record keyed by nil."
    (seed! {})
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (render! [ui/dropdown {:name "d" :label "x" :options options
                                        :on-change [:toolbar/format-chosen :doc-1]}])))))

;; ===========================================================================
;; R-B8 — transitions with no orphaned timers
;; ===========================================================================

(deftest r-b8-the-library-owns-no-timer-so-there-is-nothing-to-orphan
  (testing "R-B8. The baseline drives entering/in/exiting/out with 100 ms
            `setTimeout`s that nothing cancels, so unmounting mid-transition
            leaves them to fire. This library arms none: a top-layer
            element's own enter and exit are CSS
            (`transition-behavior: allow-discrete`, `@starting-style`), and
            the substrate's answer for content-level enter/exit —
            `v/presence` — carries a MANDATORY terminal bound rather than
            an open-ended timer. Nothing here schedules anything, which is
            the strongest version of `cancels cleanly`."
    (seed! {})
    (let [tree (render! (dropdown-form))]
      (send! [:acme.ui.dropdown/anchor-clicked [ui/dropdown-kind address]])
      (send! [:acme.ui.dropdown/dismissed [ui/dropdown-kind address]])
      (is (nil? (record address)) "a full cycle ran and left no record")
      (is (nil? (:on-transition-end (attrs-of tree "panel")))
          "the panel arms no transition callback of its own")
      (is (nil? (:on-animation-end (attrs-of tree "panel")))
          "and no animation callback either — the exit is the cascade's"))))

;; ===========================================================================
;; R-B9 — nesting determinism
;; ===========================================================================

(deftest r-b9-a-nested-overlay-is-ordinary-children-and-nothing-else
  (testing "R-B9 (headless half). Nesting needs no mechanism, only
            structure: the inner control is CHILDREN of the outer panel,
            so the inner popover is a DOM descendant of the outer one and
            the browser's own LIFO stack applies. A portal-based design has
            to reconstruct that relationship after re-parenting; there is
            nothing to reconstruct when the element never left."
    (seed! {})
    (let [tree   (render! [ui/menu-bar {:id :doc-1}])
          panels (nodes-with-part tree "panel")
          outer  (first panels)
          inner  (last panels)]
      (is (= 2 (count panels)) "two panels")
      (is (not= outer inner))
      (is (some #(identical? inner %) (filter map? (tree-seq map? :children outer)))
          "the inner panel is a DESCENDANT of the outer panel in the tree")
      (is (= {:popover-open? false} (:rf.ui/top-layer outer)))
      (is (= {:popover-open? false} (:rf.ui/top-layer inner))))))

(deftest r-b9-the-two-nested-controls-hold-independent-records
  (testing "R-B9 (headless half). LIFO dismissal is only meaningful if the
            two overlays have separate state to be in. They do: the outer
            and inner controls are addressed by different domain
            identities, so closing one is not an operation on the other."
    (seed! {})
    (let [tree   (render! [ui/menu-bar {:id :doc-1}])
          anchors (nodes-with-part tree "anchor")]
      (is (= 2 (count anchors)))
      (doseq [a anchors] (send! (:on-click (t/attrs a))))
      (is (= 2 (count (records))) "both open, independently")
      (send! [:acme.ui.dropdown/dismissed [ui/dropdown-kind [:menu :doc-1 :scope]]])
      (is (= 1 (count (records))) "the inner closed")
      (is (some? (get (records) [ui/dropdown-kind [:menu :doc-1 :format]]))
          "and the outer did not"))))

;; ===========================================================================
;; R-B10 — host-boundary hygiene
;; ===========================================================================

(deftest r-b10-the-whole-composition-renders-with-no-host-at-all
  (testing "R-B10. The render path must be free of `js/document` and
            `js/window` so a server can render the closed form — and the
            proof is that this very assertion runs on the JVM. A closed
            panel is one element carrying a desired state of `false`, a
            behavior boundary is an INERT MARKER recording its id, target
            and public config, and nothing connected."
    (seed! {})
    (let [tree (render! [ui/toolbar {:id :doc-1}])]
      (is (some? tree) "the toolbar rendered structurally")
      (is (= {:popover-open? false} (:rf.ui/top-layer (node-with-part tree "panel"))))
      (is (= {:modal-open? false}
             (:rf.ui/top-layer (t/find tree #(= :dialog (:tag %)))))
          "and the modal's closed desired state is a fact, not an absence")
      #?(:cljs
         (is (zero? (behaviors/connection-count))
             "a structural render connects nothing even where connections exist")
         :clj
         (is (contains? (behaviors/registered-ids) ui/anchor-box)
             "the behavior is registered on this host and still connects nothing")))))

;; ===========================================================================
;; R-B11 — frame / context continuity
;; ===========================================================================

(deftest r-b11-the-panel-resolves-the-same-frame-as-its-anchor
  (testing "R-B11. The efxb1h ruling preferred a top layer to a portal
            partly because there is no boundary to propagate frame context
            across. Here that is checked rather than assumed: the same
            declaration is rendered in TWO frames whose controller records
            differ, and each panel shows its own frame's state."
    (live-frame/make-frame {:id other-fid})
    (frame/replace-app-db! fid {})
    (frame/replace-app-db! other-fid {})
    (rf/dispatch-sync [:acme.ui.dropdown/anchor-clicked [ui/dropdown-kind address]]
                      {:frame fid})
    (let [here  (render-in! fid (dropdown-form))
          there (render-in! other-fid (dropdown-form))]
      (is (= "true" (:aria-expanded (attrs-of here "anchor")))
          "the frame that opened it sees it open")
      (is (= "false" (:aria-expanded (attrs-of there "anchor")))
          "a second frame rendering the SAME declaration sees its own state")
      (is (= {:popover-open? true} (:rf.ui/top-layer (node-with-part here "panel"))))
      (is (= {:popover-open? false} (:rf.ui/top-layer (node-with-part there "panel")))
          "and the panel followed its own frame, not the other one's"))))

;; ===========================================================================
;; R-B12 — total teardown (the headless half: no state survives)
;; ===========================================================================

(deftest r-b12-closing-retires-the-record-entirely
  (testing "R-B12 (headless half). The mounted half counts listeners,
            observers and timers. This half asserts the other thing a
            teardown can leak — state: a closed control leaves NO record
            behind, so an application that opened a thousand dropdowns
            holds nothing when they are all shut."
    (seed! {})
    (doseq [id [:a :b :c]]
      (send! [:acme.ui.dropdown/anchor-clicked [ui/dropdown-kind [:toolbar id]]]))
    (is (= 3 (count (records))))
    (doseq [id [:a :b :c]]
      (send! [:acme.ui.dropdown/dismissed [ui/dropdown-kind [:toolbar id]]]))
    (is (empty? (records)) "every record retired")
    (is (empty? (get (app-db) ui/records-root))
        "and the library's root holds nothing rather than a map of tombstones")))

;; ===========================================================================
;; R-B13 — the honest test split
;; ===========================================================================

(deftest r-b13-everything-provable-without-a-browser-is-proven-without-one
  (testing "R-B13. The split the harness demands is that open/close logic,
            dismissal policy and placement inputs are data, and geometry
            and focus are owed to a mounted run. This file is that split
            made real, so the row asserts the boundary itself: the state a
            dropdown holds, the intent every part carries, and the
            configuration the measurement consumes are all readable here —
            and the one thing that is NOT is any coordinate."
    (seed! {})
    (let [tree (render! (dropdown-form))]
      (is (vector? (intent tree "anchor" :on-click)) "intent is a vector")
      (is (vector? (intent tree "option" :on-click)) "on every part")
      (is (= ::v/key (last (intent tree "anchor" :on-key-down)))
          "and the keyboard site's only live input is a reserved projection")
      (let [style (:style (attrs-of tree "panel"))]
        (is (= "var(--acme-anchor-y)" (:top style))
            "the panel's position is expressed in terms the measurement fills")
        (is (every? string? (vals (select-keys style [:top :left :min-width])))
            "so no coordinate is claimed on a host that cannot measure one")))))

;; ===========================================================================
;; The composition's own claim: the two mechanisms address different parts
;; ===========================================================================

(deftest the-behavior-and-the-top-layer-land-on-different-elements
  (testing "The behavior measures the anchor's box and the top layer
            promotes the panel, so the two land on different elements. This
            row pins that as a structural fact rather than leaving it to a
            reviewer's eye. It is a modelling claim, not a workaround: ref
            composition means an element carrying both would hand the node
            to both, and `pilot-overlay-dom-cljs-test` mounts exactly that
            element to show it."
    (seed! {})
    (let [tree     (render! (dropdown-form))
          boundary (t/find tree #(= :re-frame.freehand/behavior (:view-id %)))
          decorated (first (:children boundary))
          promoted  (t/find-all tree #(and (map? %) (contains? % :rf.ui/top-layer)))]
      (is (some? boundary))
      (is (= "root" (get-in decorated [:attrs :data-part]))
          "the behavior decorates the ROOT")
      (is (= 1 (count promoted)) "exactly one element declares a desired state")
      (is (= "panel" (get-in (first promoted) [:attrs :data-part]))
          "and it is the PANEL, not the root")
      (is (nil? (:rf.ui/top-layer decorated))
          "so the decorated element declares no desired state at all"))))
