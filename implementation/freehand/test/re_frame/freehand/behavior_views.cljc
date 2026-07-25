(ns re-frame.freehand.behavior-views
  "The declarations the `FH-BEHAVIOR-*` suites render.

  Three registered behaviors and the use sites that exercise the closed
  grammar around them. The behaviors record their lifecycle into plain
  atoms rather than touching a host, so the SAME declarations run on the
  JVM (where nothing connects and the atoms stay empty — which is itself
  the inert-marker claim) and in a real browser (where the atoms are the
  lifecycle transcript the mounted suites assert)."
  (:require [re-frame.freehand :as v]))

;; ---------------------------------------------------------------------------
;; The lifecycle transcript
;; ---------------------------------------------------------------------------

(def transcript
  "Every lifecycle call, in order — `{:behavior :op :target :config
  :memory}`. A plain vector, so a suite asserts an exact SEQUENCE rather
  than a count, and reads back the private memory the context carried
  without the memory ever having been visible in the tree."
  (atom []))

(defn reset-transcript! [] (reset! transcript []) nil)

(defn ops
  "Just the operations, in order — the shape most cases pin."
  []
  (mapv :op @transcript))

(defn- record!
  [op {:keys [behavior target config prev-config memory]}]
  (swap! transcript conj {:behavior behavior :op op :target target
                          :config config :prev-config prev-config
                          :memory memory})
  nil)

(def dispatches
  "Every outward dispatch a behavior's fenced context attempted, paired with
  the boolean the context answered. `[event accepted?]`."
  (atom []))

(defn reset-dispatches! [] (reset! dispatches []) nil)

(def last-dispatch
  "The `:dispatch` fn of the most recent connection — kept so a suite can
  hold a context PAST its teardown, which is the only way to prove the
  generation fence makes it inert rather than merely unused."
  (atom nil))

;; ---------------------------------------------------------------------------
;; The behaviors
;; ---------------------------------------------------------------------------

(v/defbehavior probe
  "The ordinary case: passive timing, the full lifecycle, and a small
  command roster. Its memory is a plain map `:connect` establishes, so a
  suite can prove the memory survives an update and reaches a command
  without the memory ever being visible in the tree.

  Only `:connect` returns anything meaningful: `:update`, `:disconnect` and
  each command are called for their effect and their returns are IGNORED
  (Spec 004 §The registration and the closed timing set)."
  {:timing     :passive
   :connect    (fn [{:keys [dispatch] :as ctx}]
                 (record! :connect ctx)
                 (reset! last-dispatch dispatch)
                 {:updates 0})
   :update     (fn [ctx] (record! :update ctx) nil)
   :disconnect (fn [ctx] (record! :disconnect ctx) nil)
   :commands   {:mark     (fn [{:keys [node args] :as ctx}]
                            (record! :mark ctx)
                            #?(:cljs (when node
                                       (.setAttribute node "data-mark"
                                                      (str (:label args)))))
                            nil)
                :announce (fn [{:keys [dispatch args]}]
                            (swap! dispatches conj
                                   [(:event args) (dispatch (:event args))])
                            nil)}})

;; ---------------------------------------------------------------------------
;; The ordinary imperative-library shape — a VOID-returning host mutator
;; ---------------------------------------------------------------------------
;;
;; FH-BEHAVIOR-009 (rf2-wj1ao). Every other behavior here returns its memory
;; from `:update` and from each command, which is what a Clojure author writes
;; without thinking about it — and it is NOT what the libraries a behavior
;; exists for look like. `map.setOptions(…)`, `workbook.setValue(…)`,
;; `chart.update(spec)` and `addEventListener` all mutate and answer NOTHING, so
;; the first one of those to run through a lifecycle entry whose return replaced
;; the memory erased the very instance `:disconnect` has to release. `mutator`
;; is that shape, faithfully: the memory is built once by `:connect` and every
;; later entry is a void mutator.

(defn- void-mutate!
  "An ordinary JS-shaped host mutator: it mutates the instance and answers
  NOTHING. Records the operation into the instance's own mutable cell, so the
  suite can prove the mutations really ran against the SAME instance rather
  than merely that a map survived."
  [memory op]
  (swap! (:calls memory) conj op)
  nil)

(v/defbehavior mutator
  "The void-returning-mutator case. `:connect` builds the instance — a map
  carrying an id and its own mutable cell — and `:update`, the `:mutate`
  command and `:disconnect` all just receive it."
  {:timing     :passive
   :connect    (fn [ctx]
                 (record! :connect ctx)
                 {:instance :live :calls (atom [])})
   :update     (fn [{:keys [memory] :as ctx}]
                 (record! :update ctx)
                 (void-mutate! memory :update))
   :disconnect (fn [ctx] (record! :disconnect ctx) nil)
   :commands   {:mutate (fn [{:keys [memory] :as ctx}]
                          (record! :mutate ctx)
                          (void-mutate! memory :mutate))}})

(v/defbehavior measure
  "The `:layout` arm — work that must finish before the browser paints."
  {:timing     :layout
   :connect    (fn [{:keys [node] :as ctx}]
                 (record! :connect ctx)
                 #?(:cljs (when node (.setAttribute node "data-measured" "layout")))
                 nil)
   :disconnect (fn [ctx] (record! :disconnect ctx) nil)})

(v/defbehavior canvas
  "An OPAQUE behavior — it owns the node's descendants, so Freehand
  children on that node are an error."
  {:opaque  true
   :connect (fn [ctx] (record! :connect ctx) nil)})

;; ---------------------------------------------------------------------------
;; Accepted use sites
;; ---------------------------------------------------------------------------

(v/defview plain
  "The ordinary attachment: an id, a semantic target, and public config."
  [{:keys [label]}]
  [:section.host
   [v/behavior {:use probe :target :probe/one :config {:label (or label "a")}}
    [:div.node "content"]]])

(v/defview mutating
  "The ordinary imperative-library attachment: a behavior whose `:update` and
  whose command are VOID-returning host mutators (FH-BEHAVIOR-009)."
  [{:keys [label]}]
  [:section.host
   [v/behavior {:use mutator :target :probe/mutator :config {:label (or label "a")}}
    [:div.node "content"]]])

(v/defview no-target
  "A behavior nothing commands needs no target."
  [_]
  [v/behavior {:use probe :config {:label "quiet"}}
   [:div.node]])

(v/defview layout-timed
  "The `:layout` arm at a use site."
  [_]
  [v/behavior {:use measure :target :probe/measured}
   [:div.node]])

(v/defview opaque-host
  "An opaque behavior over an empty node."
  [_]
  [v/behavior {:use canvas :target :probe/canvas}
   [:div.node]])

(v/defview pair
  "Two occurrences under DISTINCT semantic ids — the ordinary multi-instance
  case, and the decoy arm of the command law."
  [_]
  [:section.host
   [v/behavior {:use probe :target :probe/one :config {:label "one"}}
    [:div.node {:data-id "one"}]]
   [v/behavior {:use probe :target :probe/two :config {:label "two"}}
    [:div.node {:data-id "two"}]]])

(v/defview twins
  "Two occurrences claiming ONE semantic id — the ambiguity a command
  refuses rather than resolves."
  [_]
  [:section.host
   [v/behavior {:use probe :target :probe/same :config {:label "one"}}
    [:div.node {:data-id "one"}]]
   [v/behavior {:use probe :target :probe/same :config {:label "two"}}
    [:div.node {:data-id "two"}]]])

(v/defview mixed-timing
  "A `:passive` behavior FIRST in document order and a `:layout` behavior
  second. React attaches refs bottom-up and runs every layout effect before
  any passive one, so if the declared timing were ignored the transcript
  would read in document order; honouring it puts the layout connection
  first, which is exactly what `before the browser paints` buys."
  [_]
  [:section.host
   [v/behavior {:use probe :target :probe/passive}
    [:div.node {:data-id "passive"}]]
   [v/behavior {:use measure :target :probe/layout}
    [:div.node {:data-id "layout"}]]])

(v/defview control-mount
  "The CONTROL: the same markup with no behavior at all. A cleanup
  assertion measured against this cannot mistake React's own bookkeeping
  for the substrate's."
  [_]
  [:section.host
   [:div.node "content"]])

;; ---------------------------------------------------------------------------
;; Compiled twins (rf2-drpa3.116/.127)
;; ---------------------------------------------------------------------------
;;
;; The SAME use sites, promoted with `{:compiled true}`. A behavior now has a
;; compiled form, so a view that attaches one need no longer stay interpreted.
;; These twins carry byte-identical markup to their interpreted originals, so a
;; test can assert the compiled structural marker equals the interpreted one and
;; the compiled browser lifecycle transcript equals the interpreted one — the
;; cross-mode agreement D013 requires (FH-BEHAVIOR-003, rf2-drpa3.127).

(v/defview plain-compiled
  "The compiled twin of `plain`."
  {:compiled true}
  [{:keys [label]}]
  [:section.host
   [v/behavior {:use probe :target :probe/one :config {:label (or label "a")}}
    [:div.node "content"]]])

(v/defview no-target-compiled
  "The compiled twin of `no-target`."
  {:compiled true}
  [_]
  [v/behavior {:use probe :config {:label "quiet"}}
   [:div.node]])

(v/defview pair-compiled
  "The compiled twin of `pair`."
  {:compiled true}
  [_]
  [:section.host
   [v/behavior {:use probe :target :probe/one :config {:label "one"}}
    [:div.node {:data-id "one"}]]
   [v/behavior {:use probe :target :probe/two :config {:label "two"}}
    [:div.node {:data-id "two"}]]])

(v/defview layout-compiled
  "The compiled twin of `layout-timed` — the `:layout` arm, promoted."
  {:compiled true}
  [_]
  [v/behavior {:use measure :target :probe/measured}
   [:div.node]])

(v/defview control-compiled
  "The compiled twin of `control-mount` — the same markup, no behavior."
  {:compiled true}
  [_]
  [:section.host
   [:div.node "content"]])

(v/defview opaque-host-compiled
  "The compiled twin of `opaque-host` — an opaque behavior over an EMPTY node,
  the positive control the discriminating opaque-child case is measured against
  (rf2-drpa3.127)."
  {:compiled true}
  [_]
  [v/behavior {:use canvas :target :probe/canvas}
   [:div.node]])

(v/defview opaque-with-children-compiled
  "The compiled twin of `opaque-with-children` — an opaque behavior over a node
  that carries Freehand children. The build cannot prove opacity (a registered
  fact), so the compiled BROWSER path must refuse this at runtime, exactly as
  the interpreted browser walk and the JVM structural render do (rf2-drpa3.127)."
  {:compiled true}
  [_]
  [v/behavior {:use canvas} [:div.node "content the host would overwrite"]])

;; ---------------------------------------------------------------------------
;; Refused use sites — one view per arm of the closed grammar
;; ---------------------------------------------------------------------------

(v/defview unknown-option
  [_]
  [v/behavior {:use probe :on-connect [:nope]} [:div.node]])

(v/defview missing-use
  [_]
  [v/behavior {:target :probe/one} [:div.node]])

(v/defview unregistered-use
  [_]
  [v/behavior {:use :nowhere/absent} [:div.node]])

(v/defview config-not-map
  [_]
  [v/behavior {:use probe :config 3} [:div.node]])

(v/defview config-carries-a-callback
  [_]
  [v/behavior {:use probe :config {:limits {:on-done (fn [] nil)}}} [:div.node]])

(v/defview two-children
  [_]
  [v/behavior {:use probe} [:div.node] [:div.other]])

(v/defview view-child
  [_]
  [v/behavior {:use probe} [control-mount {}]])

(v/defview fragment-child
  [_]
  [v/behavior {:use probe} [:<> [:div.node]]])

(v/defview text-child
  [_]
  [v/behavior {:use probe} "just text"])

(v/defview opaque-with-children
  [_]
  [v/behavior {:use canvas} [:div.node "content the host would overwrite"]])
