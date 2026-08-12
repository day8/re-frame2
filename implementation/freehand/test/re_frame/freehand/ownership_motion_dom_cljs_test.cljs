(ns re-frame.freehand.ownership-motion-dom-cljs-test
  "FH-REACT-010 — a React library that owns ANIMATION stays React-primary,
  and the property it animates has exactly one writer.

  Framer Motion is the shape this file exists for, and it is the second of
  the three ownership shapes rf2-drpa3.182.9 routes. It is also the one
  whose conflict is hardest to SEE. Google Maps takes a node over visibly.
  A Radix portal lands somewhere visibly else. Framer Motion competes for
  the channel React is already using: it writes `transform` and `opacity`
  imperatively onto a node React re-renders from props. Nothing errors,
  nothing warns, and the loser of the race is whoever wrote first.

  ## Two claims, and the first is the one an author needs first

  ONE WRITER. `transform` is a DOM property, and a DOM property has one
  value. Where the library owns it and the Freehand call does not author
  it, an ordinary commit — a prop moving, a subscription firing — leaves
  the library's imperative write standing, and the two coexist because they
  are writing different things. Where BOTH write it, the commit is simply
  later, the library's animation is gone, and the library's own book still
  says it is at 10. [[a-second-writer-silently-overwrites-the-library]]
  measures that, and the finding is the SILENCE: no error, no warning, and
  no way for the library to know.

  The recovery is not a mechanism, and Freehand deliberately does not grow
  one. It is a routing decision: stop authoring the property the library
  owns, and drive it through the library's own prop, which is an ordinary
  value on the ordinary plane.

  ONE EXIT-RETENTION OWNER. A departed keyed child has to stay mounted
  while its exit transition runs. Freehand has a retention primitive for
  exactly that — `v/presence`, Spec 004 §Presence — and so does every
  animation library. Two retention owners over one keyed subtree is two
  clocks deciding when a child is gone, which is a defect however well each
  clock works. So the routing is to pick one, and where the animation is
  the library's the retention is the library's too:
  [[the-library-owns-exit-retention-and-the-substrate-holds-nothing]]
  measures the substrate holding NOTHING for the retained child — the last
  commit stopped naming it while it was still on the page — rather than
  asserting a policy about it.

  ## What runs here, and what does not

  `framer-motion` is NOT installed. A real Framer Motion animation runs on
  `requestAnimationFrame` against a wall clock, so a gate built on it would
  be measuring frame timing rather than ownership; and adding the
  dependency is a shared-file change on a repository where five witnesses
  land in parallel. What runs is a surrogate with the clock taken out: a
  component that writes `transform` imperatively onto its own ref when a
  case says so, and a keyed list that retains a departed child until a case
  says the exit is done. Everything else — that the write is imperative,
  that React re-asserts what it authored, that the retained child is the
  library's — is the real shape.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where there is no DOM to
  mount and it says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(def react-010 (conf/fixture :FH-REACT-010))

(def ^:private fid :dom/ownership-motion)

;; ===========================================================================
;; The surrogate — `framer-motion`, in the part about who writes what
;; ===========================================================================

(def ^:private library-book
  "Every imperative write the LIBRARY performed, in order. Written by the
  library and never by a commit, so it is the library's own belief about
  where the animation is — which is exactly what a clobber leaves stale."
  (atom []))

(def ^:private node*
  "The node the library holds a ref to. Published so a case can drive the
  animation at a chosen moment instead of waiting for a frame."
  (atom nil))

(defn- animate!
  "The library's imperative write — `element.style.transform = …`, straight
  onto the ref, with React not consulted. A real one does this from a
  `requestAnimationFrame` callback; the only thing removed here is the
  clock."
  [x]
  (set! (.-transform (.-style @node*)) (str "translateX(" x "px)"))
  (swap! library-book conj [:animate x])
  nil)

(def ^:private motion-box
  "`motion.div` — holds a ref to its own element and animates it. `style` is
  passed straight through to React, exactly as the real component does: the
  library does not defend the property it animates, and could not."
  (fn [^js props]
    (let [own (react/useRef nil)]
      (react/useEffect (fn [] (reset! node* (.-current own)) js/undefined))
      (react/createElement "div"
                           #js {:ref        own
                                :className  "motion-box"
                                :style      (.-style props)
                                :data-label (.-label props)}
                           (.-children props)))))

;; ---------------------------------------------------------------------------
;; `AnimatePresence` — exit retention, owned by the library
;; ---------------------------------------------------------------------------

(def ^:private committed-props
  "What the LAST commit handed the retaining host, recorded from inside it.
  The divergence between this and the DOM is the retention, so it has to be
  read from the component rather than reconstructed from the test's intent."
  (atom nil))

(def ^:private finish-exit!
  "The library's own completion trigger, published so a case can end an exit
  at a chosen moment. A real one fires when its transition ends."
  (atom nil))

(def ^:private motion-list
  "`AnimatePresence` — reconciles the incoming keys against the keys it is
  already rendering, and RETAINS the ones that left. The reconciliation
  happens against a ref the library owns, which is what makes the retained
  child the library's rather than the caller's: nothing outside this
  component decides when it goes."
  (fn [^js props]
    (let [items    (.-items props)
          book     (react/useRef [])
          [_ tick] (react/useState 0)
          on-done  (.-onExitComplete props)]
      (reset! committed-props (vec items))
      (let [cur   (.-current book)
            keys* (set items)
            kept  (mapv (fn [e] (assoc e :exiting? (not (contains? keys* (:k e))))) cur)
            fresh (remove (fn [k] (some #(= k (:k %)) cur)) items)]
        (set! (.-current book)
              (into kept (map (fn [k] {:k k :exiting? false})) fresh)))
      (react/useEffect
        (fn []
          (reset! finish-exit!
                  (fn [k]
                    (set! (.-current book)
                          (vec (remove #(= k (:k %)) (.-current book))))
                    (tick inc)
                    (when on-done (on-done k))))
          js/undefined))
      (apply react/createElement "ul" #js {:className "motion-list"}
             (map (fn [e]
                    (react/createElement "li"
                                         #js {:key       (:k e)
                                              :className (if (:exiting? e) "exiting" "present")
                                              :data-k    (:k e)}
                                         (:k e)))
                  (.-current book))))))

;; ===========================================================================
;; The declarations — one door, twice
;; ===========================================================================

(v/defhost motion-panel
  "The animating component. `:style` is an ordinary prop like any other, and
  that is exactly the point of the two-writer case: nothing about the door
  stops a call authoring the property the library animates, because a door
  that policed a CSS property would be a door with an animation model in
  it.

  The `:map-props` adapter is here for the reason the option exists — to
  prepare a NON-PORTABLE HOST VALUE. Ordinary props cross shallowly and
  exactly (FH-REACT-002), so an authored `{:transform \"…\"}` arrives at
  React as the Clojure map it is, and React's style writer finds no
  properties on it and writes nothing at all. That is not a near miss to be
  papered over: it is the boundary doing its job, and it is measured in the
  first assertion of [[a-second-writer-silently-overwrites-the-library]]
  before the clobber it enables is measured.

  The adapter is written to preserve the KEY SET exactly — it converts a
  `:style` that is present and adds none that is not — because the answer
  carries exactly the keys the call authored (Spec 004 §The one
  ordinary-props adapter)."
  motion-box
  {:children  :optional
   :ssr       :client-only
   :map-props (fn [p] (cond-> p (contains? p :style) (update :style clj->js)))})

(v/defhost motion-collection
  "The retaining component. `:onExitComplete` is the ONE declared outward
  position: exit completion crosses back as an ordinary event, and the
  retention itself never crosses at all."
  motion-list
  {:callbacks {:onExitComplete :event}
   :children  :none
   :ssr       :client-only})

;; ---------------------------------------------------------------------------
;; The application under mount
;; ---------------------------------------------------------------------------

(rf/reg-sub :motion/label (fn [db _] (:label db)))
(rf/reg-sub :motion/x     (fn [db _] (:x db)))
(rf/reg-sub :motion/items (fn [db _] (:items db)))
(rf/reg-sub :motion/exited (fn [db _] (:exited db)))

(rf/reg-event :motion/label-changed (fn [{:keys [db]} [_ l]] {:db (assoc db :label l)}))
(rf/reg-event :motion/x-changed     (fn [{:keys [db]} [_ x]] {:db (assoc db :x x)}))
(rf/reg-event :motion/items-changed (fn [{:keys [db]} [_ i]] {:db (assoc db :items i)}))
(rf/reg-event :motion/exited        (fn [{:keys [db]} [_ k]] {:db (update db :exited conj k)}))

(v/defview uncontested-page
  "The call does NOT author `transform`. `:label` moves, which is an
  ordinary commit and nothing to do with the animation."
  [_]
  (let [label (v/sub [:motion/label])]
    [:section.uncontested [motion-panel {:label label}]]))

(v/defview contested-page
  "The call DOES author `transform`, from app-db, while the library animates
  the same property. Two writers, one channel. This is written here as the
  thing an author does by accident — a style prop that looks like layout
  and is in fact the library's — and the case below measures what it costs."
  [_]
  (let [label (v/sub [:motion/label])
        x     (v/sub [:motion/x])]
    [:section.contested
     [motion-panel {:label label
                    :style {:transform (str "translateX(" x "px)")}}]]))

(v/defview exit-page
  [_]
  (let [items (v/sub [:motion/items])]
    [:section.exit
     [motion-collection {:items          items
                         :onExitComplete (v/event [k] [:motion/exited k])}]]))

;; ===========================================================================
;; Harness
;; ===========================================================================

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (reset! library-book [])
                      (reset! node* nil)
                      (reset! committed-props nil)
                      (reset! finish-exit! nil)
                      (root/reset-registry!)
                      (fr/reset-boundaries!)
                      ;; Bookkeeping only — it tears nothing down, which is
                      ;; what lets `ms/residue-clean!` report a retained root
                      ;; AFTER a case rather than a reset masking it before
                      ;; the next one.
                      (ms/reset-ledger!))}))

(defn- setup! [db]
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid db)
  nil)

(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))
(defn- read* [query] (rf/subscribe-once query {:frame fid}))

(defn- transform-of
  "The property under contention, read off the REAL node rather than off
  either writer's idea of it."
  [container]
  (.-transform (.-style (.querySelector container ".motion-box"))))

(defn- dom-keys
  [container]
  (mapv #(.getAttribute % "data-k")
        (array-seq (.querySelectorAll container ".motion-list li"))))

(defn- exiting-keys
  [container]
  (mapv #(.getAttribute % "data-k")
        (array-seq (.querySelectorAll container ".motion-list li.exiting"))))

(defn- observed
  "The three books the exit rows compare: what is on the page, which of it
  is retained, and what the last commit handed the host."
  [container]
  {:dom      (dom-keys container)
   :exiting  (exiting-keys container)
   :props    @committed-props})

(defn- released! [where]
  (ms/residue-clean! where [["the door's live-root registry" #(root/live-root-ids)]]))

;; ===========================================================================
;; 1 — one writer: the library's imperative write survives an ordinary commit
;; ===========================================================================

(deftest the-librarys-imperative-write-survives-an-unrelated-commit
  (testing "FH-REACT-010, the uncontested case, and the one that has to hold
            before the route is a route at all. The library owns `transform`
            and writes it straight onto its own ref; the Freehand call
            authors a different prop entirely. A commit moves that other
            prop.

            React re-renders the element and writes what it authored — which
            does not include `transform` — so the library's write is still
            standing afterwards. Two writers coexist here because they are
            writing DIFFERENT things, which is the whole of the routing
            advice and the reason it is advice rather than a mechanism."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the mounted animation route")
      (async done
        (setup! {:label "one"})
        (let [row       (:one-writer react-010)
              container (ms/host-node!)]
          (-> (ms/act #(v/mount [uncontested-page {}] container {:frame fid}))
              (.then
                (fn [mounted]
                  (animate! 10)
                  (is (= (:after-library-write row) (transform-of container))
                      "FH-REACT-010 — the library wrote the property it owns")
                  (ms/act (fn [] (send! [:motion/label-changed "two"]) mounted))))
              (.then
                (fn [mounted]
                  (is (= "two" (.getAttribute (.querySelector container ".motion-box")
                                              "data-label"))
                      "the commit really happened — a prop React authored moved")
                  (is (= (:after-unrelated-commit row) (transform-of container))
                      "FH-REACT-010 — and the library's imperative write is
                       untouched, because the call authors no `transform` for
                       React to re-assert over it")
                  (is (= (:library-book row) @library-book)
                      "the library wrote once and was never asked again")
                  (ms/act (fn [] (v/unmount! mounted) nil))))
              (.then (fn [_]
                       (ms/remove-node! container)
                       (is (= (:after-unmount react-010)
                              {:roots (count (root/live-root-ids))}))
                       (released! "FH-REACT-010 — after the uncontested case")))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              ;;
              ;; Nothing is hoisted onto the trailing step: the node removal and
              ;; the residue reading are the SUCCESS arm's, and a second failure
              ;; attributed to the leak rule would bury the one that actually
              ;; happened.
              (.catch (fn [e] (is false (str "case rejected: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; 2 — two writers: the commit wins, and the library does not know
;; ===========================================================================

(deftest a-second-writer-silently-overwrites-the-library
  (testing "The same library, the same imperative write, and one difference:
            the call authors `transform` too, from app-db. Two writers, one
            DOM property, and a DOM property has one value.

            The commit is simply later, so the animation is gone. What makes
            this worth a row rather than a footnote is the SILENCE — there is
            no error, no warning, and the library's own book still says it is
            at 10, because nothing told it otherwise. An author debugging
            this sees an animation that 'sometimes does not run', and the
            cause is a style prop three files away that looks like layout.

            This is a CONFLICT, not a defect in either party, and Freehand
            grows no mechanism for it: a door that policed a CSS property
            would be a door with an animation model in it. The recovery is
            the routing decision — drive the animated property through the
            library's own prop, and author no style for it."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the mounted animation route")
      (async done
        (setup! {:label "one" :x 0})
        (let [row       (:two-writers react-010)
              container (ms/host-node!)]
          (-> (ms/act #(v/mount [contested-page {}] container {:frame fid}))
              (.then
                (fn [mounted]
                  (is (= (:authored-before-any-animation row) (transform-of container))
                      "FH-REACT-010 — the authored style reached React at all. It
                       only does because the declaration carries the ONE adapter:
                       ordinary props cross shallowly and exactly, so a Clojure
                       `{:transform …}` map arrives as the map it is and React's
                       style writer finds nothing on it. A conflict has to be
                       real before it can be measured")
                  (animate! 10)
                  (is (= (:after-library-write row) (transform-of container))
                      "the library wrote, and for the moment it holds the property")
                  (ms/act (fn [] (send! [:motion/x-changed 3]) mounted))))
              (.then
                (fn [mounted]
                  (is (= (:after-authored-commit row) (transform-of container))
                      "FH-REACT-010 — the commit authored the SAME property and
                       won: the library's animation is gone from the node")
                  (is (= (:library-book row) @library-book)
                      "FH-REACT-010 — and the library's own book is unchanged, so
                       it still believes it is at 10. The loss is silent in both
                       directions: nothing warned, and nothing can be asked")
                  (ms/act (fn [] (v/unmount! mounted) nil))))
              (.then (fn [_]
                       (ms/remove-node! container)
                       (released! "FH-REACT-010 — after the contested case")))
              ;; Reports and RELEASES, as above; nothing to hoist for the same
              ;; reason.
              (.catch (fn [e] (is false (str "case rejected: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; 3 — one exit-retention owner
;; ===========================================================================

(deftest the-library-owns-exit-retention-and-the-substrate-holds-nothing
  (testing "A departed keyed child stays mounted while its exit runs, and
            SOMETHING has to own that retention. Freehand has a primitive for
            it (Spec 004 §Presence) and so does every animation library —
            which means the routing question is not how to retain but WHO
            does, because two owners over one keyed subtree is two clocks
            deciding when a child is gone.

            Where the animation is the library's, the retention is the
            library's, and this case measures that as an ABSENCE rather than
            asserting it as a policy. Freehand drops the key from the items
            it hands over; the last commit's props no longer name it; and the
            child is still on the page, marked exiting, held by a book inside
            the registered component that nothing outside can reach. Then the
            library finishes, the child goes, and completion crosses back
            through the ONE declared callback position as an ordinary event.

            The divergence between `:dom` and `:props` in the middle row IS
            the retention. There is no third book."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the mounted animation route")
      (async done
        (setup! {:items ["a" "b"] :exited []})
        (let [rows      (:exit react-010)
              container (ms/host-node!)]
          (-> (ms/act #(v/mount [exit-page {}] container {:frame fid}))
              (.then
                (fn [mounted]
                  (is (= (:mounted rows) (observed container))
                      "FH-REACT-010 — both keys on the page, nothing exiting, and
                       the commit's props naming both")
                  (ms/act (fn [] (send! [:motion/items-changed ["a"]]) mounted))))
              (.then
                (fn [mounted]
                  (is (= (:after-freehand-drops-b rows) (observed container))
                      "FH-REACT-010 — Freehand stopped naming `b` and `b` is still
                       on the page, marked exiting. The substrate holds nothing
                       for it: the divergence between the DOM and the committed
                       props IS the retention, and it is the library's alone")
                  (is (= [] (read* [:motion/exited]))
                      "and nothing has completed, because the library has not
                       finished")
                  (ms/act (fn [] (@finish-exit! "b") mounted))))
              (.then
                (fn [mounted]
                  (is (= (:after-library-finishes rows)
                         (assoc (observed container) :completed (read* [:motion/exited])))
                      "FH-REACT-010 — the library finished, the retained child
                       went, and completion crossed back through the ONE declared
                       position as an ordinary event")
                  (ms/act (fn [] (v/unmount! mounted) nil))))
              (.then (fn [_]
                       (ms/remove-node! container)
                       (is (= (:after-unmount react-010)
                              {:roots (count (root/live-root-ids))}))
                       (released! "FH-REACT-010 — after the retention case")))
              ;; Reports and RELEASES, as above; nothing to hoist for the same
              ;; reason.
              (.catch (fn [e] (is false (str "case rejected: " e)) nil))
              (.then (fn [_] (done)))))))))
