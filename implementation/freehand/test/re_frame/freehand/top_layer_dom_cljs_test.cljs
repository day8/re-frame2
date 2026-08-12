(ns re-frame.freehand.top-layer-dom-cljs-test
  "FH-TOPLAYER-003 / FH-TOPLAYER-004 — the DOM top layer in a REAL browser.

  The structural rows prove what a declaration MEANS. They cannot prove the
  thing the top layer exists for, because it is not in the tree: a modal
  dialog taking initial focus and giving it back, a background going inert,
  two auto popovers being one-at-a-time while a nested pair stacks, and a
  browser-initiated dismissal arriving as an event the author handles. None
  of that exists in jsdom — there is no top layer to promote anything into
  — so every claim here is read back off a live `document` after a real
  `react-dom/client` commit.

  The counting row is deliberately exact. \"No leaks\" is not a claim
  unless it is an integer: the deltas for document and window listeners are
  compared against a CONTROL mount that declares no top-layer state (so
  React's own bookkeeping cannot masquerade as ours), and observers and
  intervals are asserted at absolute zero. A retained observer here would
  compound once per open/close cycle, which is exactly the failure mode the
  native top layer exists to delete.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where it has no top layer to
  drive and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.top-layer :as top-layer]
            [re-frame.freehand.web :as web]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

(def fx-003 (conf/fixture :FH-TOPLAYER-003))
(def fx-004 (conf/fixture :FH-TOPLAYER-004))
(def fx-005 (conf/fixture :FH-TOPLAYER-005))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(defn- browser?
  "A real top layer, not merely a DOM. `showPopover` is the capability
  every claim below rests on, so it is the capability the guard asks for."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))
       (some? (.-showPopover (.-prototype js/HTMLElement)))))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit AND after the microtask checkpoint the top-layer batch flushes on
  — never racing them."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- next-task
  "A macrotask boundary. The platform queues a popover's `toggle` event as
  a task rather than firing it synchronously, so a test that observes
  browser-initiated dismissal has to wait for the platform's own timing."
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

;; ---------------------------------------------------------------------------
;; Views. Module-level — a declared view cannot close over a test's locals,
;; so everything varying rides props.
;; ---------------------------------------------------------------------------

(v/defview menu
  "One popover, controlled. `:on-toggle` is the author's reconciliation
  seam: the browser dismisses without asking, and the substrate writes no
  application state on its behalf."
  [{:keys [menu-id open? on-toggle label]}]
  [:div
   [:button#opener "Open"]
   [:div {:id                 menu-id
          :popover            :auto
          ::web/popover-open? open?
          :on-toggle          on-toggle}
    (str "Account " label)]])

(v/defview menus
  "A nested pair inside a non-nested sibling's reach. `submenu` is a DOM
  DESCENDANT of `menu`, which is what makes it a nested popover rather than
  a second top-level one; `sibling` is neither."
  [{:keys [menu-id nested-id sibling-id outer? inner? sibling?]}]
  [:div
   [:div {:id menu-id :popover :auto ::web/popover-open? outer? :on-toggle identity}
    "Account"
    [:div {:id nested-id :popover :auto ::web/popover-open? inner? :on-toggle identity}
     "Submenu"]]
   [:div {:id sibling-id :popover :auto ::web/popover-open? sibling? :on-toggle identity}
    "Notifications"]])

(v/defview confirm
  "A modal dialog and an outside control. The outside button is what
  proves inertness and what focus must come back to."
  [{:keys [dialog-id opener-id inside-id open?]}]
  [:div
   [:button {:id opener-id} "Delete"]
   [:dialog {:id dialog-id ::web/modal-open? open? :on-close identity}
    [:button {:id inside-id} "Confirm"]]])

(v/defview plain
  "The CONTROL: the same shape with no top-layer state at all, so React's
  own listener bookkeeping can be subtracted from the measurement."
  [{:keys [label]}]
  [:div
   [:button#opener "Open"]
   [:div#account-menu (str "Account " label)]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (.unmount root)
  (.remove container)
  nil)

(defn- by-id [id] (js/document.getElementById id))

(defn- open? [id] (some-> (by-id id) (.matches ":popover-open")))
(defn- modal? [id] (some-> (by-id id) (.matches ":modal")))

(defn- skip! [why]
  (is true (str "a real browser top layer is required — " why)))

(defn- advisories
  "The top-layer advisory records the trace diagnostic bus carried while
  `f` ran, in order.

  The trace surface is what the claim is about: an advisory a tool can
  only reach by reading English prose is not evidence, so the assertions
  read the same channel a tool would."
  [f]
  (let [records (atom [])
        ids     #{(:unreconciled-id fx-005) (:refused-id fx-005)}
        k       (keyword (gensym "fh-toplayer-advisory-"))]
    (trace-tooling/register-listener!
      k (fn [ev] (when (contains? ids (:operation ev)) (swap! records conj ev))))
    (try
      (f)
      @records
      (finally
        (trace-tooling/unregister-listener! k)))))

;; ===========================================================================
;; FH-TOPLAYER-003 — the platform's own behaviour
;; ===========================================================================

(deftest fh-toplayer-003-a-modal-takes-focus-inerts-the-page-and-gives-focus-back
  (testing "Per FH-TOPLAYER-003 (browser): `modal-open?` reaches
            showModal(), so the dialog takes initial focus, the background
            cannot take it back while the dialog is up, and close() returns
            focus to where it came from — all of it the platform's, none of
            it the substrate's."
    (if-not (browser?)
      (skip! "the browser job runs the focus assertions")
      (async done
        (let [[container root] (mount!)
              {:keys [dialog-id opener-id inside-id]} fx-003
              render (fn [open?]
                       (act #(.render root (fr/element
                                             [confirm {:dialog-id dialog-id
                                                       :opener-id opener-id
                                                       :inside-id inside-id
                                                       :open?     open?}]))))]
          (-> (render false)
              (.then (fn [_]
                       (is (false? (modal? dialog-id)) "closed before the desired state says open")
                       (.focus (by-id opener-id))
                       (is (= (by-id opener-id) js/document.activeElement)
                           "the page owns focus before the dialog opens")
                       (render true)))
              (.then (fn [_]
                       (is (true? (modal? dialog-id)) "showModal() ran at the commit")
                       (is (.contains (by-id dialog-id) js/document.activeElement)
                           "the platform moved initial focus into the dialog")
                       ;; Inertness: the background is not merely visually
                       ;; behind the dialog, it cannot take focus.
                       (.focus (by-id opener-id))
                       (is (.contains (by-id dialog-id) js/document.activeElement)
                           "the background is inert — focus stays in the dialog")
                       (render false)))
              (.then (fn [_]
                       (is (false? (modal? dialog-id)) "close() ran at the commit")
                       (is (= (by-id opener-id) js/document.activeElement)
                           "the platform returned focus to the invoking control")
                       (teardown! container root)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

(deftest fh-toplayer-003-nested-popovers-stack-and-close-together
  (testing "Per FH-TOPLAYER-003 (browser): a NESTED pair opened in ONE
            commit is both open — the ancestor is shown first, so the
            descendant is genuinely nested — a non-nested sibling closes
            them by the platform's one-at-a-time rule with the substrate
            calling nothing, and hiding the ancestor closes the descendant
            with it in ONE call."
    (if-not (browser?)
      (skip! "the browser job runs the nesting assertions")
      (async done
        (let [[container root] (mount!)
              {:keys [menu-id nested-id sibling-id]} fx-003
              render (fn [outer? inner? sibling?]
                       (act #(.render root (fr/element
                                             [menus {:menu-id    menu-id
                                                     :nested-id  nested-id
                                                     :sibling-id sibling-id
                                                     :outer?     outer?
                                                     :inner?     inner?
                                                     :sibling?   sibling?}]))))
              ops    (atom 0)]
          (-> (render false false false)
              (.then (fn [_]
                       (reset! ops (top-layer/operation-count))
                       (render true true false)))
              (.then (fn [_]
                       (is (true? (open? menu-id)) "the ancestor popover is open")
                       (is (true? (open? nested-id))
                           "the nested popover is open TOO — document order beat React's bottom-up refs")
                       (is (= 2 (- (top-layer/operation-count) @ops))
                           "exactly two shows, one per popover")
                       (reset! ops (top-layer/operation-count))
                       ;; A sibling is not an ancestor, so the platform's
                       ;; one-at-a-time rule closes the pair. The substrate
                       ;; performs ONE call — the sibling's — and closes
                       ;; nothing itself.
                       (render true true true)))
              (.then (fn [_]
                       (is (true? (open? sibling-id)) "the sibling popover is open")
                       (is (false? (open? menu-id)) "the platform light-dismissed the ancestor")
                       (is (false? (open? nested-id)) "and the nested popover with it")
                       (is (= 1 (- (top-layer/operation-count) @ops))
                           "one show; the substrate closed nothing")
                       ;; Back to the nested pair, then close the ancestor:
                       ;; the platform takes the descendant down with it, so
                       ;; the descendant's own diff finds nothing to do.
                       (render true true false)))
              (.then (fn [_]
                       (is (true? (open? menu-id)) "the pair is back")
                       (is (true? (open? nested-id)) "both halves")
                       (reset! ops (top-layer/operation-count))
                       (render false false false)))
              (.then (fn [_]
                       (is (false? (open? menu-id)) "the ancestor is closed")
                       (is (false? (open? nested-id)) "and the descendant closed with it")
                       (is (= 1 (- (top-layer/operation-count) @ops))
                           "ONE hide closed both — LIFO is the platform's, not ours")
                       (teardown! container root)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

(deftest fh-toplayer-003-browser-dismissal-reaches-the-author-and-nothing-else
  (testing "Per FH-TOPLAYER-003 (browser): when the browser dismisses a
            controlled popover, the native event reaches the author's
            handler and the substrate writes no application state — so the
            desired state still says open, and the next commit re-opens it.
            Reconciliation is the author's, through ordinary intent."
    (if-not (browser?)
      (skip! "the browser job runs the dismissal assertions")
      (async done
        (let [[container root] (mount!)
              {:keys [menu-id]} fx-003
              seen   (atom [])
              ;; React 19 surfaces the platform's `ToggleEvent` fields on its
              ;; own synthetic event; the native event is read as the fallback
              ;; so the assertion is about the PLATFORM's report, not about
              ;; which wrapper delivered it.
              on-tog (fn [^js e]
                       (swap! seen conj (or (.-newState e)
                                            (some-> (.-nativeEvent e) .-newState))))
              render (fn [open? label]
                       (act #(.render root (fr/element
                                             [menu {:menu-id   menu-id
                                                    :open?     open?
                                                    :label     label
                                                    :on-toggle on-tog}]))))
              ops    (atom 0)]
          (-> (render true "a")
              (.then (fn [_]
                       (is (true? (open? menu-id)) "the popover opened at the commit")
                       ;; The browser closing it of its own accord — what
                       ;; Escape and a light dismiss both come down to.
                       (.hidePopover (by-id menu-id))
                       (next-task)))
              (.then (fn [_]
                       (is (false? (open? menu-id)) "the browser closed it")
                       (is (= ["closed"] @seen)
                           "the native event reached the author's handler")
                       (reset! ops (top-layer/operation-count))
                       ;; The desired state was never touched by the
                       ;; dismissal, so the next commit re-asserts it. That
                       ;; is the contract, and it is why the author must
                       ;; reconcile rather than the substrate guessing.
                       (render true "b")))
              (.then (fn [_]
                       (is (true? (open? menu-id))
                           "an unreconciled desired state re-opens the popover")
                       (is (= 1 (- (top-layer/operation-count) @ops))
                           "exactly one show — the diff saw the node, not a memory")
                       (render false "c")))
              (.then (fn [_]
                       (is (false? (open? menu-id))
                           "the author reconciled, and the node follows")
                       (teardown! container root)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; FH-TOPLAYER-004 — the commit law, the tracking frequency, the counts
;; ===========================================================================

(deftest fh-toplayer-004-an-uncommitted-render-and-a-detach-reach-no-host
  (testing "Per FH-TOPLAYER-004: the host call rides a ref, so building
            elements performs no host work at all, a detach performs none,
            and a node that left the document is skipped rather than
            asked to open."
    (if-not (browser?)
      (skip! "the browser job owns the host-operation counter")
      (let [before (top-layer/operation-count)]
        ;; Building elements — the shape an abandoned candidate would leave
        ;; behind. React runs a ref only for a render it COMMITTED.
        (dotimes [_ 5]
          (fr/element [menu {:menu-id (:menu-id fx-004) :open? true :label "x"
                             :on-toggle identity}]))
        (top-layer/flush-top-layer!)
        (is (= before (top-layer/operation-count))
            "building elements performed zero host operations")
        (is (zero? (top-layer/pending-count)) "and enqueued nothing")

        (let [props (top-layer/install! #js {} :div
                                        {:popover            :auto
                                         :on-toggle          identity
                                         ::web/popover-open? true})
              ref   (gobj/get props "ref")
              orphan (js/document.createElement "div")]
          (is (fn? ref) "the desired state installed a commit-time ref")
          (ref nil)
          (top-layer/flush-top-layer!)
          (is (= before (top-layer/operation-count))
              "a detach performed zero host operations")
          ;; A node the ref saw but the document never held: enqueued, then
          ;; skipped, because a disconnected node has no top layer to enter.
          (.setAttribute orphan "popover" "auto")
          (ref orphan)
          (is (= 1 (top-layer/pending-count)) "the batch holds exactly the one node")
          (top-layer/flush-top-layer!)
          (is (= before (top-layer/operation-count))
              "a disconnected node was skipped, not asked to open")
          (is (zero? (top-layer/pending-count)) "and the batch drained whole"))))))

(deftest fh-toplayer-004-a-retired-generation-is-cancelled-while-its-node-stays-connected
  (testing "Per FH-TOPLAYER-004: a queued operation belongs to the ref
            OCCURRENCE that queued it, not to the node. A generation that
            retires before the flush withdraws its own operation even though
            the node is still in the document — connectivity is not
            ownership — and a retirement never takes a NEWER generation's
            claim on the same node down with it."
    (if-not (browser?)
      (skip! "the browser job owns the host-operation counter")
      (let [before (top-layer/operation-count)
            node   (js/document.createElement "div")
            ref!   (fn []
                     (gobj/get (top-layer/install! #js {} :div
                                                   {:popover            :auto
                                                    :on-toggle          identity
                                                    ::web/popover-open? true})
                               "ref"))]
        (.setAttribute node "popover" "auto")
        (.appendChild js/document.body node)
        (try
          ;; The seam: a commit removes the intrinsic without removing the
          ;; element, so the ref retires while the node stays connected and
          ;; no successor replaces the queued fact.
          (let [retiring (ref!)]
            (retiring node)
            (is (= 1 (top-layer/pending-count)) "the generation queued its operation")
            (retiring nil)
            (is (zero? (top-layer/pending-count))
                "and withdrew it on retirement, node still connected")
            (top-layer/flush-top-layer!)
            (is (= before (top-layer/operation-count))
                "a retired generation performed zero host operations")
            (is (false? (.matches node ":popover-open"))
                "and the node it no longer speaks for was left closed"))

          ;; A successor's claim outlives the predecessor's retirement,
          ;; whichever order the host runs attach and detach in.
          (let [older (ref!)
                newer (ref!)]
            (older node)
            (newer node)
            (older nil)
            (is (= 1 (top-layer/pending-count))
                "the successor's entry survived the predecessor's retirement")
            (top-layer/flush-top-layer!)
            (is (true? (.matches node ":popover-open"))
                "and it is the successor's desired state the node landed in"))
          (finally
            (.remove node)))))))

(deftest fh-toplayer-004-a-generation-retiring-during-the-flush-does-not-act
  (testing "Per FH-TOPLAYER-004: the ownership fence has to travel INTO the
            ordered flush, not merely gate its entry. The batch is performed
            in document order, so the flush is a LOOP — and every top-layer
            host call has a synchronous callback seam: `beforetoggle` fires
            inside `showPopover()`, and Freehand permits a plain handler
            there. An earlier ordered call can therefore retire a later
            generation while the loop is still running. A flush that dropped
            the owner as it sorted would have nothing left to withdraw, and
            the retired generation's node would be opened on behalf of a
            generation that had already let go — before paint, one frame
            after it stopped speaking for that node."
    (if-not (browser?)
      (skip! "the browser job owns the ordered-flush assertions")
      (let [log    (atom [])
            outer  (js/document.createElement "div")
            inner  (js/document.createElement "div")
            ref!   (fn []
                     (gobj/get (top-layer/install! #js {} :div
                                                   {:popover            :auto
                                                    :on-toggle          identity
                                                    ::web/popover-open? true})
                               "ref"))
            watch! (fn [^js node tag]
                     ;; Only the OPEN transition: a browser-initiated close of
                     ;; an ancestor is the platform reconciling, not a host
                     ;; call this batch made.
                     (.addEventListener node "beforetoggle"
                                        (fn [^js e]
                                          (when (= "open" (.-newState e))
                                            (swap! log conj tag)))))]
        ;; NESTED, so document order is unambiguous (an ancestor sorts ahead
        ;; of its descendant) and both may legally be open at once.
        (.setAttribute outer "popover" "auto")
        (.setAttribute inner "popover" "auto")
        (.appendChild outer inner)
        (.appendChild js/document.body outer)
        (watch! outer :outer)
        (watch! inner :inner)
        (try
          (let [a (ref!)
                b (ref!)]
            ;; The seam: the FIRST ordered host call retires the SECOND
            ;; generation, synchronously, from inside `showPopover()`.
            (.addEventListener outer "beforetoggle" (fn [_] (b nil)))
            (a outer)
            (b inner)
            (is (= 2 (top-layer/pending-count)) "both generations queued")
            (top-layer/flush-top-layer!)
            (is (= [:outer] @log)
                (str "the retired generation's host call did not happen. Saw: "
                     (pr-str @log)))
            (is (true? (.matches outer ":popover-open"))
                "the generation that still spoke opened its node")
            (is (false? (.matches inner ":popover-open"))
                "and the one that had let go left its node closed")
            (is (zero? (top-layer/pending-count)) "the batch drained whole"))
          (finally
            (.remove outer)))))))

(deftest fh-toplayer-004-a-mid-flush-retirement-spares-a-successors-claim
  (testing "Per FH-TOPLAYER-004: the same identity check that cancels a
            retired generation's own work is what stops it taking a NEWER
            generation's claim on the same node down with it — and that has
            to hold DURING the flush too, not only before it. A predecessor
            retiring from inside an earlier ordered host call finds an entry
            that is not its own, and the successor's operation happens."
    (if-not (browser?)
      (skip! "the browser job owns the ordered-flush assertions")
      (let [log   (atom [])
            outer (js/document.createElement "div")
            inner (js/document.createElement "div")
            ref!  (fn []
                    (gobj/get (top-layer/install! #js {} :div
                                                  {:popover            :auto
                                                   :on-toggle          identity
                                                   ::web/popover-open? true})
                              "ref"))]
        (.setAttribute outer "popover" "auto")
        (.setAttribute inner "popover" "auto")
        (.appendChild outer inner)
        (.appendChild js/document.body outer)
        (doseq [[^js node tag] [[outer :outer] [inner :inner]]]
          (.addEventListener node "beforetoggle"
                             (fn [^js e]
                               (when (= "open" (.-newState e))
                                 (swap! log conj tag)))))
        (try
          (let [a     (ref!)
                older (ref!)
                newer (ref!)]
            (.addEventListener outer "beforetoggle" (fn [_] (older nil)))
            (a outer)
            (older inner)
            (newer inner)                                  ; the successor's claim
            (is (= 2 (top-layer/pending-count))
                "one entry per node — the successor replaced the predecessor's")
            (top-layer/flush-top-layer!)
            (is (= [:outer :inner] @log)
                (str "the successor's claim survived the mid-flush retirement. Saw: "
                     (pr-str @log)))
            (is (true? (.matches inner ":popover-open"))
                "and its desired state is the one the node landed in")
            (is (zero? (top-layer/pending-count)) "the batch drained whole"))
          (finally
            (.remove outer)))))))

(deftest fh-toplayer-004-a-generation-retires-through-a-composed-release
  (testing "Per FH-TOPLAYER-004: retirement survives ref COMPOSITION. On an
            element sharing its node with another mechanism the install
            chains, so React holds the chain rather than this generation —
            and the chain releases a participant that answered no cleanup of
            its own by calling it with nil, which is exactly the retiring
            call. The generation is the closure either way, so an entry is
            withdrawn through a composed release as surely as through a bare
            detach."
    (if-not (browser?)
      (skip! "the browser job owns the host-operation counter")
      (let [before (top-layer/operation-count)
            node   (js/document.createElement "div")
            seen   (atom [])
            ;; The other mechanism on this node — a behavior-shaped ref
            ;; already installed on the props, which the install must chain
            ;; onto rather than clobber.
            earlier (fn earlier-ref [n] (swap! seen conj (if (some? n) :attach :release)) nil)
            props   (top-layer/install! (doto #js {} (gobj/set "ref" earlier))
                                        :div
                                        {:popover            :auto
                                         :on-toggle          identity
                                         ::web/popover-open? true})
            chained (gobj/get props "ref")]
        (.setAttribute node "popover" "auto")
        (.appendChild js/document.body node)
        (try
          (is (not (identical? chained earlier))
              "the install composed rather than clobbering the ref already there")
          (let [cleanup (chained node)]
            (is (= [:attach] @seen) "the earlier participant took the node first")
            (is (= 1 (top-layer/pending-count)) "and the generation queued its operation")
            ;; React 19 releases a ref that answered a cleanup by CALLING it.
            (is (fn? cleanup) "a composed ref answers a cleanup function")
            (cleanup)
            (is (= [:attach :release] @seen) "the earlier participant was released too")
            (is (zero? (top-layer/pending-count))
                "the generation retired through the composed release"))
          (top-layer/flush-top-layer!)
          (is (= before (top-layer/operation-count))
              "so the retired generation performed zero host operations")
          (is (false? (.matches node ":popover-open"))
              "and the node was left closed")
          (finally
            (.remove node)))))))

(deftest fh-toplayer-004-an-unchanged-desired-state-costs-nothing-and-lands-before-paint
  (testing "Per FH-TOPLAYER-004: the operation happens before the first
            frame after the commit, and re-committing an unchanged desired
            state performs no further operations however many renders
            follow — the tracking frequency is the commit, and nothing
            between commits."
    (if-not (browser?)
      (skip! "the browser job runs the timing assertions")
      (async done
        (let [[container root] (mount!)
              menu-id (:menu-id fx-004)
              render  (fn [open? label]
                        (act #(.render root (fr/element
                                              [menu {:menu-id   menu-id
                                                     :open?     open?
                                                     :label     label
                                                     :on-toggle identity}]))))
              painted (atom ::unset)
              ops     (atom 0)]
          (-> (render false "a")
              (.then (fn [_]
                       ;; Scheduled BEFORE the opening commit: this callback
                       ;; runs at the next frame, so what it sees is what
                       ;; the first paint after the commit would have shown.
                       (let [seen (js/Promise.
                                    (fn [resolve]
                                      (js/requestAnimationFrame
                                        (fn []
                                          (reset! painted (open? menu-id))
                                          (resolve nil)))))]
                         (.then (render true "b") (fn [_] seen)))))
              (.then (fn [_]
                       (is (true? @painted)
                           "the popover was already open at the first frame — no wrong-state paint")
                       (is (true? (open? menu-id)) "and it stayed open")
                       (reset! ops (top-layer/operation-count))
                       ;; N further commits, same desired state, a changing
                       ;; label so React genuinely re-renders each time.
                       (reduce (fn [p n] (.then p (fn [_] (render true (str "r" n)))))
                               (js/Promise.resolve nil)
                               (range (:renders fx-004)))))
              (.then (fn [_]
                       (is (= 0 (- (top-layer/operation-count) @ops))
                           (str (:renders fx-004) " re-commits of an unchanged desired state "
                                "performed zero host operations"))
                       (is (true? (open? menu-id)) "and the popover is still open")
                       (teardown! container root)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

(deftest fh-toplayer-004-a-full-cycle-retains-exactly-nothing
  (testing "Per FH-TOPLAYER-004: mount, open, close and unmount arms
            nothing that outlives the cycle. Listener deltas are measured
            against a CONTROL mount with no top-layer state, so React's own
            bookkeeping cannot be mistaken for ours; observers and intervals
            are zero absolutely, because the top layer needs none of them —
            that is the whole reason it replaces hand-rolled overlays."
    (if-not (browser?)
      (skip! "the browser job owns the retention counts")
      (async done
        (let [menu-id  (:menu-id fx-004)
              original #js {"docAdd"    (.-addEventListener js/document)
                            "docRemove" (.-removeEventListener js/document)
                            "winAdd"    (.-addEventListener js/window)
                            "winRemove" (.-removeEventListener js/window)
                            "ro"        (.-ResizeObserver js/window)
                            "io"        (.-IntersectionObserver js/window)
                            "mo"        (.-MutationObserver js/window)
                            "interval"  (.-setInterval js/window)}
              counts   #js {}
              bump!    (fn [k n] (gobj/set counts k (+ n (gobj/get counts k 0))))
              zero!    (fn [] (doseq [k ["doc" "win" "obs" "iv"]] (gobj/set counts k 0)))
              orig     (fn [k] (gobj/get original k))
              observed (fn [k ctor]
                         ;; Only the COUNT matters: if any of these is ever
                         ;; constructed the assertion has already failed, so
                         ;; the shim delegates through Reflect and stays out
                         ;; of the way of a page that legitimately uses one.
                         (fn [& args]
                           (bump! k 1)
                           (js/Reflect.construct ctor (to-array args))))
              wrap!    (fn []
                         (zero!)
                         (set! (.-addEventListener js/document)
                               (fn [t l o] (bump! "doc" 1) (.call (orig "docAdd") js/document t l o)))
                         (set! (.-removeEventListener js/document)
                               (fn [t l o] (bump! "doc" -1) (.call (orig "docRemove") js/document t l o)))
                         (set! (.-addEventListener js/window)
                               (fn [t l o] (bump! "win" 1) (.call (orig "winAdd") js/window t l o)))
                         (set! (.-removeEventListener js/window)
                               (fn [t l o] (bump! "win" -1) (.call (orig "winRemove") js/window t l o)))
                         (set! (.-ResizeObserver js/window) (observed "obs" (orig "ro")))
                         (set! (.-IntersectionObserver js/window) (observed "obs" (orig "io")))
                         (set! (.-MutationObserver js/window) (observed "obs" (orig "mo")))
                         (set! (.-setInterval js/window)
                               (fn [f d] (bump! "iv" 1) (.call (orig "interval") js/window f d))))
              restore! (fn []
                         (set! (.-addEventListener js/document) (orig "docAdd"))
                         (set! (.-removeEventListener js/document) (orig "docRemove"))
                         (set! (.-addEventListener js/window) (orig "winAdd"))
                         (set! (.-removeEventListener js/window) (orig "winRemove"))
                         (set! (.-ResizeObserver js/window) (orig "ro"))
                         (set! (.-IntersectionObserver js/window) (orig "io"))
                         (set! (.-MutationObserver js/window) (orig "mo"))
                         (set! (.-setInterval js/window) (orig "interval")))
              snapshot (fn [] {:doc (gobj/get counts "doc") :win (gobj/get counts "win")
                               :obs (gobj/get counts "obs") :iv (gobj/get counts "iv")})
              cycle!   (fn [element-for]
                         (let [[container root] (mount!)]
                           (-> (act #(.render root (element-for false)))
                               (.then (fn [_] (act #(.render root (element-for true)))))
                               (.then (fn [_] (act #(.render root (element-for false)))))
                               (.then (fn [_] (teardown! container root) nil)))))
              control  (atom nil)]
          (wrap!)
          (-> (cycle! (fn [_] (fr/element [plain {:label "c"}])))
              (.then (fn [_]
                       (reset! control (snapshot))
                       (zero!)
                       (cycle! (fn [open?]
                                 (fr/element [menu {:menu-id   menu-id
                                                    :open?     open?
                                                    :label     "t"
                                                    :on-toggle identity}])))))
              (.then (fn [_]
                       (let [measured (snapshot)]
                         (restore!)
                         (is (= (:doc @control) (:doc measured))
                             "no net document listener beyond the control's")
                         (is (= (:win @control) (:win measured))
                             "no net window listener beyond the control's")
                         (is (= 0 (:obs measured))
                             "zero resize / intersection / mutation observers")
                         (is (= 0 (:iv measured)) "zero intervals")
                         (is (zero? (top-layer/pending-count))
                             "and the commit batch is drained"))))
              ;; `restore!` stays on BOTH arms: on the success arm it has to run
              ;; BEFORE the assertions read the counters, so it is not the tail
              ;; teardown the trailing step could own.
              (.catch (fn [e] (restore!) (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; FH-TOPLAYER-005 — the advisories: published at a commit, and typed
;; ===========================================================================

(deftest fh-toplayer-005-an-uncommitted-candidate-publishes-no-advisory
  (testing "Per FH-TOPLAYER-005: an advisory is evidence, and evidence is
            published only from a SELECTED commit. Building an element's
            props is not one — React may restart or abandon that render —
            so constructing top-layer props for a controlled node with no
            reconciliation handler publishes exactly nothing, and the
            committed callback ref publishes the typed record."
    (if-not (browser?)
      (skip! "the browser job owns the advisory channels")
      (let [attrs {:popover            :auto
                   ::web/popover-open? true}
            node  (js/document.createElement "div")]
        (.setAttribute node "popover" "auto")
        (.appendChild js/document.body node)
        (try
          (let [refs      (atom [])
                candidate (advisories
                            (fn []
                              (dotimes [_ 3]
                                (swap! refs conj
                                       (gobj/get (top-layer/install! #js {} :div attrs) "ref")))))
                committed (advisories (fn [] ((first @refs) node)))]
            (is (= 3 (count @refs)) "three candidates were built")
            (is (= [] candidate)
                "three uncommitted candidates published no record at all")

            (is (= 1 (count committed))
                "the committed ref published exactly one record")
            (when-some [ev (first committed)]
              (let [tags (:tags ev)]
                (is (= (:unreconciled-id fx-005) (:operation ev))
                    "under the catalogued diagnostic id")
                (is (= :warning (:op-type ev)) "on the diagnostic channel")
                (is (= (:recovery fx-005) (:recovery ev)) "carrying its recovery")
                (is (= :div (:tag tags)) "naming the element that declared it")
                (is (= :popover (:mechanism tags)) "and the mechanism")
                (is (= [:on-toggle :on-before-toggle] (:handlers tags))
                    "and the handlers that would reconcile it")))

            ;; The advisory is about the DECLARATION, so a declaration that
            ;; does handle its own dismissal publishes nothing at its commit
            ;; either — the record names a real mistake or it names nothing.
            (let [handled (advisories
                            (fn []
                              (let [ref (gobj/get (top-layer/install!
                                                    #js {} :div
                                                    (assoc attrs :on-toggle identity))
                                                  "ref")]
                                (ref node))))]
              (is (= [] handled) "a reconciled declaration published no record")))
          (finally
            (top-layer/flush-top-layer!)
            (.remove node)))))))

(deftest fh-toplayer-005-a-refused-host-call-becomes-typed-evidence
  (testing "Per FH-TOPLAYER-005: a host call the browser refuses publishes
            typed evidence naming the call, the element and the browser's
            own reason, then the runtime CONTINUES — the operation is
            mechanical and the next render can fix the mistake."
    (if-not (browser?)
      (skip! "the browser job owns a real host refusal")
      (let [dialog (js/document.createElement "dialog")]
        (.setAttribute dialog "id" (:dialog-id fx-005))
        (.appendChild js/document.body dialog)
        (try
          ;; Already open NON-modally: showModal() on an open dialog is the
          ;; refusal the advisory exists to name, and it is the browser's
          ;; refusal, not a stubbed one.
          (.show dialog)
          (is (true? (.-open dialog)) "the dialog is open, non-modally")
          (let [ref      (gobj/get (top-layer/install! #js {} :dialog
                                                       {::web/modal-open? true
                                                        :on-close         identity})
                                   "ref")
                captured (advisories (fn [] (ref dialog) (top-layer/flush-top-layer!)))]
            (is (= 1 (count captured))
                "the refusal published exactly one record")
            (when-some [ev (first captured)]
              (let [tags (:tags ev)]
                (is (= (:refused-id fx-005) (:operation ev))
                    "under the catalogued diagnostic id")
                (is (= (:recovery fx-005) (:recovery ev)) "carrying its recovery")
                (is (= "showModal()" (:host-call tags)) "naming the host call")
                (is (= :dialog (:tag tags)) "and the element")
                (is (= (:dialog-id fx-005) (:element-id tags)) "by its id")
                (is (string? (:exception tags))
                    "and the browser's own reason for refusing")))
            (is (false? (.matches dialog ":modal"))
                "the refused promotion did not happen")
            (is (zero? (top-layer/pending-count))
                "and the batch drained past the refusal rather than aborting"))
          (finally
            (.close dialog)
            (.remove dialog)))))))
