(ns re-frame.freehand.ownership-radix-dom-cljs-test
  "FH-REACT-009 — a compound React library stays React-primary behind ONE
  `v/defhost` door.

  Radix UI is the shape this file exists for, and it is the third of the
  three ownership shapes rf2-drpa3.182.9 routes. A Radix dialog is not a
  component; it is a PROTOCOL BETWEEN components, and every strand of it is
  something a view substrate is tempted to grow a vocabulary for:

    CONTEXT.   `Dialog.Root` owns the open state and publishes it to its
               parts through React context. The parts are siblings in the
               author's markup and collaborators in React's tree.

    asChild.   `Dialog.Trigger` does not render a button. It CLONES the
               element the caller gave it and injects a ref and a click
               handler — `cloneElement(child, {ref, onClick})`. The caller
               keeps their own element; Radix keeps the wiring.

    PORTAL.    `Dialog.Portal` re-parents its subtree into `document.body`
               through React's own `createPortal`, so the content escapes
               whatever `overflow` and `z-index` the author's layout has.

    FOCUS.     `Dialog.Content` moves focus in when it opens and puts it
               back where it was when it closes.

  ## The route, and the three things Freehand does NOT add

  Freehand grows none of that vocabulary. There is no neutral portal and
  there is not going to be one (Spec 004 §No neutral portal), there is no
  ref API, and there is no focus policy. What there is, is ONE door:
  register the WRAPPER — the component that composes Root, Trigger, Portal
  and Content — through `v/defhost`, and the entire protocol stays inside
  React's own tree, where it already works and where it is visibly React's
  rather than the substrate's vocabulary.

  Registering the PARTS would be the mistake, and it is worth naming because
  it is the one an author reaches for: four `v/defhost` declarations, one
  per part, arranged as siblings in a Freehand tree. They would render, and
  the context between them would not survive the crossing in any way an
  author could rely on — the parts would be four separate crossings rather
  than one React subtree. The declaration below registers the wrapper for
  exactly that reason, and the `:ownership` row measures that the compound
  really did collaborate.

  ## The promise that is WITHHELD, and why it is measured

  Spec 004 §Three disjoint planes states the boundary in one sentence:

    \"Freehand does NOT promise that a Freehand-authored child can
     participate in `asChild`, arbitrary `cloneElement`, or ref-injection
     protocols; keep such a region React-owned and register its wrapper
     through `v/defhost`.\"

  A withheld promise is worth what the measurement behind it is worth, so
  [[a-freehand-authored-child-does-not-take-an-injected-ref]] is an A/B over
  ONE `cloneElement(child, {ref})` call: the same probe, the same clone, two
  children. A React-authored `<button>` takes the ref. A declared Freehand
  view does not — and it does not FAIL either, because React 19 hands a
  `ref` prop to a function component as an ordinary unused prop. Silence is
  the finding. Knowing which of the two happens is the difference between an
  author who registers the wrapper and one who spends an afternoon on a ref
  that was never going to arrive.

  ## What runs here, and what does not

  Radix's own packages are NOT installed. Adding `@radix-ui/react-dialog` to
  `implementation/package.json` is a shared-file change on a repository
  where five witnesses land in parallel, and the law here is about where
  OWNERSHIP falls rather than about Radix's implementation of it. What runs
  is a surrogate reproducing the compound shape exactly — context between
  the parts, `asChild` cloning with a ref, a real `react-dom`
  `createPortal` into `document.body`, and focus moved on open and restored
  on close. Everything below is named after the Radix name it stands in for.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where there is no DOM to
  mount and it says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
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

(def react-009 (conf/fixture :FH-REACT-009))

(def ^:private fid :dom/ownership-radix)

;; ===========================================================================
;; The surrogate compound — `@radix-ui/react-dialog`, in the part about ownership
;; ===========================================================================
;;
;; Four components and one context, named after the parts they stand in for.
;; Nothing here is Freehand: it is ordinary React, which is the point — the
;; whole protocol has to be expressible without the substrate's help, or the
;; route this row states would not be a route.

(defonce ^:private dialog-context (react/createContext nil))

(defn- only-child
  "`React.Children.only` — the compound idiom's own accessor. It is what the
  real parts use, and it is what makes `asChild` a contract rather than a
  guess: a caller who hands over two elements is refused rather than having
  one of them silently cloned. Used here so a Freehand crossing that
  delivers its trailing children as a collection of one is handled by
  React's own reader rather than by an assumption about the shape."
  [^js props]
  (.only (.-Children react) (.-children props)))

(def ^:private dialog-root
  "`Dialog.Root` — owns the open state and publishes it to its parts. The
  parts are siblings in the author's markup and collaborators in the tree."
  (fn [^js props]
    (let [[open set-open] (react/useState false)
          trigger-ref     (react/useRef nil)
          on-open-change  (.-onOpenChange props)
          ctx             #js {:open       open
                               :triggerRef trigger-ref
                               :setOpen    (fn [v]
                                             (set-open v)
                                             (when on-open-change (on-open-change v)))}]
      (react/createElement (.-Provider dialog-context) #js {:value ctx}
                           (.-children props)))))

(def ^:private dialog-trigger
  "`Dialog.Trigger` with `asChild` — it renders NO element of its own. It
  clones the caller's element and injects the ref and the handler into it,
  which is the whole compound-component idiom in one call."
  (fn [^js props]
    (let [ctx (react/useContext dialog-context)]
      (react/cloneElement (only-child props)
                          #js {:ref     (.-triggerRef ctx)
                               :onClick (fn [_] ((.-setOpen ctx) true))}))))

(def ^:private dialog-portal
  "`Dialog.Portal` — React's own `createPortal`, into a target the author's
  layout does not contain. Rendered only while open, exactly as the real one
  is."
  (fn [^js props]
    (let [ctx (react/useContext dialog-context)]
      (when (.-open ctx)
        (react-dom/createPortal (.-children props) (.-body js/document))))))

(def ^:private dialog-content
  "`Dialog.Content` — moves focus in on open and puts it back on close. The
  restore rides the effect's CLEANUP, which is where a real one puts it: the
  content is already gone by then, so the only thing that can be focused is
  the trigger the ref points at."
  (fn [^js props]
    (let [ctx (react/useContext dialog-context)
          own (react/useRef nil)]
      (react/useEffect
        (fn []
          (some-> (.-current own) .focus)
          (fn [] (some-> (.-current (.-triggerRef ctx)) .focus)))
        #js [])
      (react/createElement
        "div" #js {:ref own :tabIndex -1 :className "radix-content"}
        (react/createElement "button"
                             #js {:className "radix-close"
                                  :onClick   (fn [_] ((.-setOpen ctx) false))}
                             "close")
        (.-children props)))))

(def ^:private radix-dialog
  "THE WRAPPER — the one component registered through the one door. It
  composes all four parts, so the protocol between them is React's own and
  never crosses a Freehand boundary."
  (fn [^js props]
    (react/createElement
      dialog-root #js {:onOpenChange (.-onOpenChange props)}
      (react/createElement dialog-trigger nil
                           (react/createElement "button"
                                                #js {:className "radix-trigger"}
                                                (.-label props)))
      (react/createElement dialog-portal nil
                           (react/createElement dialog-content nil
                                                (.-children props))))))

;; ===========================================================================
;; The clone probe — the A/B behind the withheld promise
;; ===========================================================================

(def ^:private clone-results
  "probe label -> the tag name the injected ref resolved to, or nil when no
  ref ever arrived. Written from inside the React component, so it records
  what the CLONE saw rather than what this file passed in."
  (atom {}))

(def ^:private clone-probe
  "Performs the SAME `cloneElement(child, {ref})` the compound trigger
  performs, on whichever single child it is handed, and records where the
  ref landed. Two call sites below hand it two different kinds of child;
  nothing else differs."
  (fn [^js props]
    (let [own   (react/useRef nil)
          label (.-probe props)]
      (react/useEffect
        (fn []
          (swap! clone-results assoc label (some-> (.-current own) .-tagName))
          js/undefined))
      (react/createElement "div" #js {:className "clone-probe"}
                           (react/cloneElement (only-child props) #js {:ref own})))))

;; ===========================================================================
;; The declarations — ONE door, twice
;; ===========================================================================

(v/defhost dialog
  "The compound dialog, registered as ONE host. `:callbacks` names the single
  outward position; everything else about the protocol stays inside React."
  radix-dialog
  {:callbacks {:onOpenChange :event}
   :children  :optional
   :ssr       :client-only})

(v/defhost as-child-probe
  "The clone probe. `:children :required` because the probe's whole job is to
  clone the one child it is given."
  clone-probe
  {:children :required
   :ssr      :client-only})

;; ---------------------------------------------------------------------------
;; The application under mount
;; ---------------------------------------------------------------------------

(rf/reg-sub :radix/opens (fn [db _] (:opens db)))

(rf/reg-event :radix/open-changed
  (fn [{:keys [db]} [_ open?]] {:db (update db :opens conj open?)}))

(v/defview dialog-page
  [_]
  [:section.dialog-page
   [dialog {:label        "open"
            :onOpenChange (v/event [open?] [:radix/open-changed open?])}
    ;; A Freehand-authored child, crossing as an ordinary React child. It
    ;; lands inside the registered component's OWN tree — which here means
    ;; inside the portal, because that is where the wrapper renders it.
    [:p.detail "detail"]]])

(v/defview inner-button
  "A DECLARED Freehand view, and therefore a Freehand-authored child in the
  sense the spec's withheld promise means: it crosses as an element of
  Freehand's own component, not as a `<button>` element React can clone a
  ref onto."
  [_]
  [:button.inner "inner"])

(v/defview clone-page
  "The A/B. Two probes, the same clone inside each, and the ONLY difference
  is what kind of child it is handed."
  [_]
  [:section.clone-page
   [as-child-probe {:probe "react-authored"}
    (react/createElement "button" #js {:className "inner"} "inner")]
   [as-child-probe {:probe "freehand-authored"}
    [inner-button {}]]])

;; ===========================================================================
;; Harness
;; ===========================================================================

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (reset! clone-results {})
                      (root/reset-registry!)
                      (fr/reset-boundaries!)
                      ;; Bookkeeping only — it tears nothing down, which is
                      ;; what lets `ms/residue-clean!` report a retained root
                      ;; AFTER a case rather than a reset masking it before
                      ;; the next one.
                      (ms/reset-ledger!))}))

(defn- setup! []
  (live-frame/make-frame {:id fid})
  ;; Seed `:opens` as a VECTOR. On an absent key `conj` would build a list and
  ;; prepend to it, so the open/close transcript would read backwards — an
  ;; ordering the assertion below is specifically about.
  (frame/replace-app-db! fid {:opens []})
  nil)

(defn- read* [query] (rf/subscribe-once query {:frame fid}))

(defn- body-content-nodes
  "How many portalled dialog contents `document.body` is carrying. Read off
  the DOCUMENT rather than off a container, because that is the whole point
  of a portal and the whole reason a container-scoped teardown would miss
  one."
  []
  (.-length (.querySelectorAll (.-body js/document) ".radix-content")))

(defn- active-class
  "The class of whatever currently has focus, so the two focus rows compare
  a name rather than a node."
  []
  (some-> (.-activeElement js/document) .-className))

(defn- released!
  [where]
  (ms/residue-clean! where
                     [["the door's live-root registry"        #(root/live-root-ids)]
                      ["portalled dialog contents in the body" #(body-content-nodes)]]))

;; ===========================================================================
;; 1 — the compound protocol, whole, behind one door
;; ===========================================================================

(deftest a-compound-library-crosses-as-one-registered-wrapper
  (testing "FH-REACT-009. Four collaborating components, one React context
            between them, a real portal and the library's own focus
            management — and ONE `v/defhost` registration, of the wrapper
            rather than of the parts.

            The ownership boundary is the pair of container assertions. The
            trigger is inside the Freehand root's container because Freehand
            rendered the host there. The content is NOT, because React's
            portal put it somewhere Freehand never chose and has no
            vocabulary for — and that is the correct outcome, not a gap: a
            substrate that could name that target would be a substrate with
            a neutral portal in it."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the mounted compound crossing")
      (async done
        (setup!)
        (let [own   (:ownership react-009)
              focus (:focus react-009)
              container (ms/host-node!)]
          (-> (ms/act #(v/mount [dialog-page {}] container {:frame fid}))
              (.then
                (fn [mounted]
                  (is (= 0 (body-content-nodes))
                      "FH-REACT-009 — closed, the portal renders nothing at all")
                  (is (some? (.querySelector container "button.radix-trigger"))
                      "FH-REACT-009 — the trigger is in the Freehand root's container, because
                       that is where Freehand rendered the host")
                  (ms/act (fn []
                            (.click (.querySelector container "button.radix-trigger"))
                            mounted))))
              (.then
                (fn [mounted]
                  (is (= (:trigger-in-container own)
                         (some? (.querySelector container "button.radix-trigger")))
                      "FH-REACT-009 — the trigger stays where Freehand put it")
                  (is (= (:content-in-container own)
                         (some? (.querySelector container ".radix-content")))
                      "FH-REACT-009 — and the CONTENT is not in the container at all — React's
                       portal owns where it went")
                  (is (= (:content-in-body own) (= 1 (body-content-nodes)))
                      "FH-REACT-009 — it is in `document.body`, which is React's target and not
                       one Freehand can name")
                  (is (= (:freehand-child-text own)
                         (.-textContent (.querySelector (.-body js/document)
                                                        ".radix-content p.detail")))
                      "FH-REACT-009 — the caller's Freehand child rendered inside the registered
                       component's OWN tree — which is inside the portal, because
                       that is where the wrapper renders its children")
                  (is (= [true] (read* [:radix/opens]))
                      "FH-REACT-009 — and the declared callback position carried the library's
                       own open/close protocol outward as an ordinary event")
                  (is (= (:on-open focus) (active-class))
                      "FH-REACT-009 — focus moved into the content, by the LIBRARY's effect —
                       the substrate neither moved it nor watched it move")
                  (ms/act (fn []
                            (.click (.querySelector (.-body js/document) "button.radix-close"))
                            mounted))))
              (.then
                (fn [mounted]
                  (is (= 0 (body-content-nodes))
                      "FH-REACT-009 — closing removed the portalled subtree")
                  (is (= (:on-close focus) (active-class))
                      "FH-REACT-009 — and focus went back to the trigger, restored by the
                       library's own effect cleanup")
                  (is (= [true false] (read* [:radix/opens]))
                      "FH-REACT-009 — and both halves of the library's own open/close
                       protocol crossed back, in order, through the ONE declared
                       position")
                  (ms/act (fn [] (v/unmount! mounted) nil))))
              (.then
                (fn [_]
                  (ms/remove-node! container)
                  (is (= (:after-unmount react-009)
                         {:body-content (body-content-nodes)
                          :roots        (count (root/live-root-ids))})
                      "FH-REACT-009 — and nothing survived, INCLUDING the node
                       React put outside the container, which no container-scoped
                       teardown would have reached")
                  (released! "FH-REACT-009 — after the compound teardown")))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              ;;
              ;; Nothing is hoisted onto the trailing step: the node removal and
              ;; the residue readings are the SUCCESS arm's, and a second failure
              ;; attributed to the leak rule would bury the one that actually
              ;; happened.
              (.catch (fn [e] (is false (str "case rejected: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; 2 — the withheld promise, measured
;; ===========================================================================

(deftest a-freehand-authored-child-does-not-take-an-injected-ref
  (testing "Spec 004 §Three disjoint planes withholds one promise by name:
            a Freehand-authored child is not promised participation in
            `asChild`, arbitrary `cloneElement`, or ref injection. This is
            that sentence as an A/B rather than as advice.

            One probe, one `cloneElement(child, {ref})`, two children. A
            React-authored `<button>` takes the ref and the probe reads its
            tag name back. A DECLARED Freehand view does not — and it does
            not fail either: React 19 hands a `ref` prop to a function
            component as an ordinary unused prop, so the ref simply never
            arrives and nothing says so. The SILENCE is the finding, and it
            is why the recovery is to register the wrapper through
            `v/defhost` and keep the cloning region React-owned, rather than
            to reach inward for a ref that was never going to come."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the mounted compound crossing")
      (async done
        (setup!)
        (let [expected  (:as-child react-009)
              container (ms/host-node!)]
          (-> (ms/act #(v/mount [clone-page {}] container {:frame fid}))
              (.then
                (fn [mounted]
                  (is (= 2 (.-length (.querySelectorAll container "button.inner")))
                      "FH-REACT-009 — both children RENDERED: this is a claim about ref
                       injection, not about whether the child crossed")
                  (is (= (:react-authored expected) (get @clone-results "react-authored"))
                      "FH-REACT-009 — the ref injected into a React-authored child
                       landed on the real element")
                  (is (= (:freehand-authored expected)
                         (get @clone-results "freehand-authored"))
                      "FH-REACT-009 — and the SAME injection into a Freehand-authored
                       child landed nowhere, silently: a declared view crosses as
                       an element of Freehand's own component, which takes the ref
                       prop and has nothing to do with it")
                  (is (contains? @clone-results "freehand-authored")
                      "FH-REACT-009 — non-vacuity: the probe really ran for that child, so the
                       nil above is a ref that did not arrive rather than a probe
                       that did not fire")
                  (ms/act (fn [] (v/unmount! mounted) nil))))
              (.then
                (fn [_]
                  (ms/remove-node! container)
                  (released! "FH-REACT-009 — after the clone probes")))
              ;; Reports and RELEASES, as above; nothing to hoist for the same
              ;; reason.
              (.catch (fn [e] (is false (str "case rejected: " e)) nil))
              (.then (fn [_] (done)))))))))
