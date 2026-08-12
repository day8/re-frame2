(ns re-frame.freehand.compiled-mount-dom-cljs-test
  "The compiled tier in a real browser — `{:compiled true}` mounted through
  `v/mount`, and every claim read back off `document`.

  Until this file existed, NO compiled view was browser-mountable: the
  declaration analyzed, the manifest reported, the structural tree
  rendered on both hosts, and the React emitter refused every compiled
  descriptor because the browser lowering was not built. Everything the
  compiled tier claimed about a page was therefore claimed about a page
  nobody had put on screen.

  So the assertions below are deliberately the ones a structural render
  cannot make:

  - **Cross-mode parity, in the DOM.** The compiled twin of the
    interpreted `page` is mounted against the SAME `FH-STRUCT-007` rows
    its interpreted original is pinned to by
    `react-mount-dom-cljs-test` — same fixture, same selectors, same
    expected text and attributes, unedited. Two emitters, one page.
  - **The wrapper the manifest names is the wrapper React ran.** A view
    whose analysis proved it reactive renders inside the atomic shell; a
    view whose analysis ELIDED its ViewCell renders with no shell at
    all. That is not read off the manifest — the manifest is the claim —
    it is read off the DOM, because a compiled body can ask
    `cell/observing?` and a candidate exists exactly when the ViewCell
    shell opened one.
  - **The reactive arms do their work.** A committed `:on-*` site
    dispatches into the frame the commit bound, and a subscription
    observes the current value and REPAINTS the mounted occurrence when
    it moves.

  This file rides the browser lane through its `-dom-cljs-test`
  namespace suffix. It also matches the node suites' broader regex,
  where it has no DOM to mount and says so rather than passing quietly —
  the declarations themselves still load, which is the cross-host half."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.compiled-views :as compiled]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.live-frame :as live-frame]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.test-support :as test-support]))

(def struct-007 (conf/fixture :FH-STRUCT-007))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  ;; The live-root registry and the emitter's boundary cache are both
  ;; process-global, so a root or a boundary left over from an earlier
  ;; test — or an earlier run of this file — could masquerade as this
  ;; test's own reload and hand React a component built for a different
  ;; declaration generation.
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit rather than racing it."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- live!
  "Leave React's act environment. A repaint driven by a DEPENDENCY rather
  than by a re-render call is the mechanism under test here, and inside
  an act environment React diverts that work to the act queue instead of
  flushing it where the browser would — so the assertion would be about
  act's drain order rather than about the substrate."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn- tick!
  "Yield one browser task, so React has flushed what the notification
  scheduled before anything is read back off `document`."
  []
  (js/Promise. (fn [resolve] (js/setTimeout #(resolve nil) 0))))

(defn- settle!
  "Close the cells' pending window and let the browser render what that
  notification scheduled.

  A source-side change MARKS the observing cells and returns — constant
  work, never a computation — so the repaint lands when the window closes
  at the host checkpoint. Closing it explicitly here, outside the act
  environment, is what makes the assertion that follows a claim about the
  substrate rather than about microtask ordering."
  []
  (cell/flush!)
  (tick!))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- unmount! [container mounted]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (when (some? mounted)
    (.unmount (.-react-root ^root/Root mounted)))
  (.remove container)
  nil)

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- attr [container selector n]
  (some-> (.querySelector container selector) (.getAttribute n)))

(defn- attrs-of
  "EVERY attribute the matched element carries, as a plain map. Read off
  `document` rather than named one by one, so an attribute that should
  not be there fails the comparison too."
  [container selector]
  (when-some [el (.querySelector container selector)]
    (into {} (map (fn [a] [(.-name a) (.-value a)])) (array-seq (.-attributes el)))))

;; ---------------------------------------------------------------------------
;; The shell witness
;; ---------------------------------------------------------------------------

(defn shell-witness
  "\"shell\" inside an open render candidate, \"no-shell\" outside one.

  A ViewCell is the ONLY thing that opens a candidate, so this is the
  presence of the reactive shell, observed from inside the very body the
  shell is (or is not) wrapping — and it reaches the DOM as an ordinary
  attribute, where a test can read it without a seam into the runtime.

  A plain `defn`, so a compiled body may call it: it owns no
  subscription, no occurrence and no memoization, and calling it is not
  a reactive site. That matters here — a witness that WAS a site would
  force the very shell it exists to detect."
  []
  (if (cell/observing?) "shell" "no-shell"))

;; ---------------------------------------------------------------------------
;; The arms. Module-level, because a declaration cannot close over a
;; test's locals — and because `{:compiled true}` is a macro-expansion
;; fact, so these are exactly the declarations a real application writes.
;; ---------------------------------------------------------------------------

(def ^:private fid :compiled-dom/frame)

(v/defview inert-probe
  "No subscription, no committed handler, no frame read — the analysis
  proves the reactive shell unnecessary, so the ViewCell is ELIDED and
  this body runs with no candidate above it."
  {:compiled true}
  [{:keys [caption]}]
  [:p#inert.probe {:data-shell (shell-witness)} caption])

(v/defview sub-probe
  "One subscription. A reactive site is proof the shell is needed, so the
  ViewCell is RETAINED and this body runs inside a candidate."
  {:compiled true}
  [_]
  [:p#reactive.probe {:data-shell (shell-witness)} (str (v/sub [:compiled/total]))])

(v/defview sub-probe-interpreted
  "The INTERPRETED twin of `sub-probe` — the same parameter vector and the
  same body, without the marker. It is the control for every reactive
  claim below: promotion may not change what a read observes, when it
  observes it, or what repaints because of it."
  [_]
  [:p#interpreted.probe {:data-shell (shell-witness)} (str (v/sub [:compiled/total]))])

(v/defview both-probes
  "Both reactive arms under ONE root, so a repaint claim is made about
  two lowerings in one commit discipline, on one page, at one moment."
  [_]
  [:div#both [sub-probe {}] [sub-probe-interpreted {}]])

(v/defview event-probe
  "One committed `:on-*` site. It reads the frame the commit bound, so
  the shell is retained for it exactly as it is for a subscription."
  {:compiled true}
  [{:keys [caption]}]
  [:button#press.probe {:data-shell (shell-witness)
                        :on-click   [:compiled/pressed]}
   caption])

(v/defview ui-event-probe
  "A committed site whose intent is a `(v/event …)` CONVERSION rather than
  a literal vector — the one spelling for an intent the closed projection
  trio cannot express. The compiled lowering used to hand the runtime the
  bare fn the callback carries, which `event-plan` classifies `:bare-fn`:
  the click ran the body and DISCARDED the vector it answered, with no
  error and nothing on screen to see (rf2-berc2)."
  {:compiled true}
  [{:keys [caption]}]
  [:button#converted.probe {:data-shell (shell-witness)
                            :on-click   (v/event [e] [:compiled/converted (.-detail e)])}
   caption])

(v/defview field-probe
  "A CONTROLLED input: a `value` prop makes the element controlled, and
  the door verdict for its handler is decided from those element facts.
  The compiled tier does not encode that verdict — it emits the facts and
  lets the one door predicate decide, which is why promotion cannot move
  a field between the synchronous and batched lanes."
  {:compiled true}
  [_]
  [:input#field {:type :text :value (str (v/sub [:compiled/text]))
                 :on-input [:compiled/typed :re-frame.freehand/value]}])

;; ---------------------------------------------------------------------------
;; Props forwarding — the SAME body, twice, with nothing but the marker
;; between them (rf2-51d4q)
;; ---------------------------------------------------------------------------
;;
;; The React emitter read `:attrs`, `:class`, `:style`, `:key` and `:events` off
;; an element's props and never read `:spread` or `:safe-spread` — so a
;; `{:compiled true}` declaration forwarding an attribute map mounted an element
;; with the WHOLE forwarded map missing, and the literal override beside it gone
;; too. Nothing threw and nothing warned: only the DOM knew.
;;
;; So these are pairs. Each claim is asserted on the compiled mount and on its
;; interpreted twin, read back off `document` the same way, and the two are
;; compared to each other — which is a stronger row than either alone, because
;; an emitter that dropped the same attribute in both modes would still fail it.

(def ^:private forwarded
  "The runtime attribute map both twins forward. A class (which COMPOSES
  with the tag sugar rather than replacing it), an ordinary attribute, a
  `data-*`, and a handler that has to become a committed site."
  {:class "from-caller" :title "forwarded" :data-x "1" :on-click [:compiled/pressed]})

(v/defview spread-probe
  "`(v/spread base overrides)` — the visible-cost forward. The literal
  override map is the second argument and wins collisions; the tag's
  `.card.wide` sugar composes ahead of whatever `:class` arrives."
  {:compiled true}
  [{:keys [attrs]}]
  [:div.card.wide (v/spread attrs {:data-override "literal"})])

(v/defview spread-probe-interpreted
  "The interpreted twin of `spread-probe` — the same body, without the
  marker."
  [{:keys [attrs]}]
  [:div.card.wide (v/spread attrs {:data-override "literal"})])

(v/defview safe-probe
  "`(v/spread-safe owned caller)` — the bounded forward a component
  library uses. The owned props win every collision, `:class` composes
  owned-first, and the deny law refuses a caller key that would clobber
  what the component promised."
  {:compiled true}
  [{:keys [attrs]}]
  [:input.field (v/spread-safe {:value "owned" :class "owned-class"
                                :on-input [:compiled/typed :re-frame.freehand/value]}
                               attrs)])

(v/defview safe-probe-interpreted
  "The interpreted twin of `safe-probe`."
  [{:keys [attrs]}]
  [:input.field (v/spread-safe {:value "owned" :class "owned-class"
                                :on-input [:compiled/typed :re-frame.freehand/value]}
                               attrs)])

(v/defview callback-sink
  "A child that ATTACHES the callback its parent handed it. The prop is an
  ordinary value to this view; what it has to BE is a roster callback, or
  the site it lands on has nothing to dispatch."
  [{:keys [on-pick]}]
  [:button#sink {:on-click on-pick} "pick"])

(v/defview callback-parent
  "A compiled parent passing `(v/event …)` ACROSS a boundary. The analyzer
  records such a prop as analysed content under its own key, so both v1
  emitters — reading `:value` — put `nil` on the props map: every
  declaration compiled, every mount resolved, and the callback the author
  wrote was simply not there."
  {:compiled true}
  [_]
  [:div [callback-sink {:on-pick (v/event [e] [:compiled/converted (.-detail e)])}]])

(v/defview interpreted-shell
  "An INTERPRETED child, mounted BY a compiled parent below, with children
  the compiled parent already lowered into React elements."
  [{:keys [children]}]
  [:div#interp children])

(v/defview crossing-probe
  "A compiled parent crossing into an interpreted child, forwarding
  children the compiled emitter resolved at build time. The crossing is
  a mount — the head is a descriptor, not a React component — so the call
  normalizes through the same boundary rules an interpreted crossing
  does."
  {:compiled true}
  [{:keys [label]}]
  [:section#crossing
   [interpreted-shell {} [:em.forwarded label]]])

(defn- register! []
  (rf/reg-sub :compiled/total (fn [db _] (:total db)))
  (rf/reg-sub :compiled/text  (fn [db _] (:text db)))
  (rf/reg-event :compiled/pressed
                (fn [{:keys [db]} _] {:db (update db :presses inc)}))
  (rf/reg-event :compiled/typed
                (fn [{:keys [db]} [_ v]] {:db (assoc db :text v)}))
  (rf/reg-event :compiled/converted
                (fn [{:keys [db]} ev] {:db (update db :converted (fnil conj []) ev)}))
  (rf/reg-event :compiled/total-set
                (fn [{:keys [db]} [_ n]] {:db (assoc db :total n)})))

;; ---------------------------------------------------------------------------
;; Typing, as the browser delivers it
;; ---------------------------------------------------------------------------

(defn- set-native-value!
  "Write `s` through `HTMLInputElement`'s own prototype setter, so React's
  value tracker sees the mutation exactly as it does for a real
  keystroke. Assigning `.-value` directly leaves the tracker's record
  unchanged and React skips the change event — the field would look
  typed-into while nothing was dispatched."
  [node s]
  (.call (.-set (js/Object.getOwnPropertyDescriptor
                  (.-prototype js/HTMLInputElement) "value"))
         node s))

(defn- keystroke!
  "One keystroke: append `ch` to whatever the node holds, then dispatch a
  real bubbling `input` event."
  [node ch]
  (set-native-value! node (str (.-value node) ch))
  (.dispatchEvent node (js/InputEvent. "input"
                                       #js {:bubbles true :cancelable false :data ch})))

(defn- seed! [db]
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid db)
  fid)

(defn- db [] (frame/frame-app-db-value fid))

;; ===========================================================================
;; Cross-mode parity — the SAME FH-STRUCT-007 rows, in a browser
;; ===========================================================================

(defn- check-row!
  [container {:keys [note selector selector-all tag text attrs] n :count}]
  (if selector-all
    (is (= n (.-length (.querySelectorAll container selector-all))) note)
    (let [el (.querySelector container selector)]
      (is (some? el) (str note " — " selector " matched"))
      (when el
        (when tag  (is (= tag (.-tagName el)) note))
        (when text (is (= text (.-textContent el)) note))
        (doseq [[attr-name expected] attrs]
          (is (= expected (.getAttribute el attr-name))
              (str note " — " attr-name)))))))

(deftest a-compiled-view-mounts-as-the-same-real-dom-its-interpreted-twin-does
  (testing "Per FH-STRUCT-007, rendered by the OTHER emitter: the compiled
            twin of the interpreted `page` — the same declaration with
            `{:compiled true}` added and nothing else changed — mounts
            through `v/mount` and produces the elements, converted
            attribute names, composed classes, text and keyed run of
            child boundaries the fixture pins its interpreted original
            to. `react-mount-dom-cljs-test` renders the original against
            these same rows; between them, promotion is proven not to
            change one thing about the page."
    (is (seq (:dom struct-007)) "the fixture's DOM table loaded")
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (let [container (host-node!)]
          (-> (act #(v/mount [compiled/page (:props struct-007)] container))
              (.then (fn [mounted]
                       (doseq [row (:dom struct-007)]
                         (check-row! container row))
                       (unmount! container mounted)))
              ;; Reports and releases; it never finishes (rf2-fyba). `cljs.test`
              ;; hands `done` a continuation that runs the WHOLE REMAINDER of the
              ;; run synchronously, so a `.catch` downstream of it claims whatever
              ;; a later namespace throws as this row's failure, prints it against
              ;; this row's label, and fires `done` a SECOND time — re-forcing
              ;; `run-block`'s unrealized delay and re-running that namespace.
              ;;
              ;; The teardown is ASYMMETRIC throughout this file and stays put.
              ;; The success arm's `unmount!` retires the React root AND detaches
              ;; the container; the failure arm can only `.remove`, because the
              ;; rejection may be the mount itself and `mounted` is the fulfilled
              ;; value — not in scope here at all. Neither belongs on a shared
              ;; trailing step.
              (.catch (fn [e]
                        (is false (str "compiled mount rejected: " e))
                        (.remove container)
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; The elision verdict is the wrapper React actually ran
;; ===========================================================================

(deftest an-elided-compiled-view-mounts-with-no-view-cell
  (testing "A compiled body whose analysis found no reactive site renders
            with NO ViewCell: no candidate is open above it, so the
            witness inside the body reports `no-shell`. The manifest
            claims `:view-cell :elided`; this is that claim observed in
            the DOM rather than restated. The reactive arm below is the
            control — without it, `no-shell` could be a constant."
    (if-not (browser?)
      (skip! "the browser job runs the shell assertions")
      (async done
        (register!)
        (seed! {:total 41})
        (let [container (host-node!)]
          (-> (act #(v/mount [inert-probe {:caption "inert"}] container {:frame fid}))
              (.then (fn [mounted]
                       (is (= :elided (:view-cell (v/manifest inert-probe)))
                           "the declaration's own analysis elided the ViewCell")
                       (is (false? (:reactive? (v/manifest inert-probe))))
                       (is (= "inert" (text container "#inert"))
                           "and it rendered — an elided shell is not an absent view")
                       (is (= "no-shell" (attr container "#inert" "data-shell"))
                           "no candidate was open above the body: no ViewCell ran")
                       (is (= "probe" (attr container "#inert" "class"))
                           "the sugar class survived the compiled lowering")
                       (is (= [:compiled :elided]
                              (:signature (get (fr/boundary-cache)
                                               (:view-id (v/describe inert-probe)))))
                           "and the boundary React reconciles was minted for exactly
                            that lowering and that verdict")
                       (unmount! container mounted)))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "inert mount rejected: " e))
                        (.remove container)
                        nil))
              (.then (fn [_] (done)))))))))

(deftest a-reactive-compiled-view-mounts-inside-the-atomic-shell
  (testing "A compiled body carrying a subscription renders INSIDE the
            atomic shell — the same shell an interpreted body renders
            inside, opening the same candidate. The witness reports
            `shell`, which is the exact opposite of the elided arm above
            over the same probe attribute, so neither answer is a
            constant."
    (if-not (browser?)
      (skip! "the browser job runs the shell assertions")
      (async done
        (register!)
        (seed! {:total 41})
        (let [container (host-node!)]
          (-> (act #(v/mount [sub-probe {}] container {:frame fid}))
              (.then (fn [mounted]
                       (is (= :present (:view-cell (v/manifest sub-probe))))
                       (is (true? (:reactive? (v/manifest sub-probe))))
                       (is (= "shell" (attr container "#reactive" "data-shell"))
                           "a candidate was open above the body: the ViewCell ran")
                       (is (= [:compiled :present]
                              (:signature (get (fr/boundary-cache)
                                               (:view-id (v/describe sub-probe))))))
                       (unmount! container mounted)))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "reactive mount rejected: " e))
                        (.remove container)
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; The subscription arm — observe, and repaint when the value moves
;; ===========================================================================

(deftest a-compiled-subscription-observes-and-repaints-the-mounted-occurrence
  (testing "The claim the compiled tier could not make before this slice:
            a `v/sub` inside a compiled body, mounted in a browser,
            observes the CURRENT value and repaints the mounted
            occurrence when that value moves. The read goes through the
            one shell — `reactive/sub-read` reaches
            `cell/observe-site!` — so the compiled view is repainted by
            the same committed dependency an interpreted twin would be
            repainted by."
    (if-not (browser?)
      (skip! "the browser job runs the repaint assertions")
      (async done
        (register!)
        (seed! {:total 41})
        (let [container (host-node!)
              mounted   (atom nil)]
          (-> (act #(v/mount [both-probes {}] container {:frame fid}))
              (.then (fn [m]
                       (reset! mounted m)
                       (is (= "41" (text container "#reactive"))
                           "the compiled arm's first render observed the current value")
                       (is (= "41" (text container "#interpreted"))
                           "and so did its interpreted twin")
                       (live!)
                       (rf/dispatch-sync [:compiled/total-set 42] {:frame fid})
                       (settle!)))
              (.then (fn [_]
                       (is (= "42" (text container "#interpreted"))
                           "the interpreted twin repainted — the control")
                       (is (= "42" (text container "#reactive"))
                           "and the compiled arm repainted the MOUNTED occurrence
                            identically, off the same committed dependency")
                       (unmount! container @mounted)))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "subscription arm rejected: " e))
                        (.remove container)
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; The event arm — a committed site, fired from a real DOM event
;; ===========================================================================

(deftest a-compiled-event-site-dispatches-into-the-committed-frame
  (testing "A committed `:on-*` site in a compiled body is recorded on
            this render's candidate through the ONE event-site
            constructor, becomes live at the SELECTED commit, and
            dispatches into the frame that commit bound. Fired here by a
            real DOM click, not by invoking the callback — a proxy that
            React never attached would pass the second and fail the
            first."
    (if-not (browser?)
      (skip! "the browser job runs the dispatch assertions")
      (async done
        (register!)
        (seed! {:presses 0})
        (let [container (host-node!)
              mounted   (atom nil)]
          (-> (act #(v/mount [event-probe {:caption "press"}] container {:frame fid}))
              (.then (fn [m]
                       (reset! mounted m)
                       (is (= :present (:view-cell (v/manifest event-probe)))
                           "an event site is a reactive site, so the shell is retained")
                       (is (= "shell" (attr container "#press" "data-shell")))
                       (is (= 0 (:presses (db))) "nothing has been dispatched yet")
                       (act #(.click (.querySelector container "#press")))))
              (.then (fn [_]
                       (is (= 1 (:presses (db)))
                           "the real click reached the committed frame")
                       (act #(.click (.querySelector container "#press")))))
              (.then (fn [_]
                       (is (= 2 (:presses (db)))
                           "and the site's proxy survived the repaint between clicks")
                       (unmount! container @mounted)))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "event arm rejected: " e))
                        (.remove container)
                        nil))
              (.then (fn [_] (done)))))))))

(deftest a-compiled-v-event-site-dispatches-the-vector-its-body-answered
  (testing "The arm above fires a LITERAL intent. This one fires a
            `(v/event …)` — a body that CONVERTS the native event into an
            intent — which is the whole reason the form exists. What is
            asserted is the vector app-db received, not that the click
            raised nothing: the defect this row exists for raised nothing
            at all. It ran the body, discarded the vector, and left an
            application whose button did exactly nothing with no
            diagnostic to search for."
    (if-not (browser?)
      (skip! "the browser job runs the dispatch assertions")
      (async done
        (register!)
        (seed! {})
        (let [container (host-node!)
              mounted   (atom nil)]
          (-> (act #(v/mount [ui-event-probe {:caption "convert"}] container {:frame fid}))
              (.then (fn [m]
                       (reset! mounted m)
                       (is (= "shell" (attr container "#converted" "data-shell"))
                           "a v/event site is a reactive site, so the shell is retained")
                       (is (nil? (:converted (db))) "nothing has been dispatched yet")
                       (act #(.dispatchEvent (.querySelector container "#converted")
                                             (js/CustomEvent. "click"
                                                              #js {:bubbles true :detail 7})))))
              (.then (fn [_]
                       (is (= [[:compiled/converted 7]] (:converted (db)))
                           "the EXACT vector the v/event body answered reached app-db,
                            carrying the value it read off the native event")
                       (act #(.dispatchEvent (.querySelector container "#converted")
                                             (js/CustomEvent. "click"
                                                              #js {:bubbles true :detail 8})))))
              (.then (fn [_]
                       (is (= [[:compiled/converted 7] [:compiled/converted 8]] (:converted (db)))
                           "and the site's proxy converted the second click too")
                       (unmount! container @mounted)))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "v/event arm rejected: " e))
                        (.remove container)
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; Props forwarding — the forwarded map really reaches the mounted element
;; ===========================================================================

(deftest a-compiled-v-spread-carries-the-forwarded-map-onto-the-element
  (testing "Every attribute the forwarded map and the literal override map
            carry is ON the mounted element, and the compiled element is
            attribute-for-attribute the element its interpreted twin
            mounts. Read off `document`: the defect compiled cleanly,
            mounted cleanly, and rendered `<div class=\"card wide\">` with
            everything else silently absent."
    (if-not (browser?)
      (skip! "the browser job runs the forwarding assertions")
      (async done
        (register!)
        (seed! {:presses 0})
        (let [c-compiled    (host-node!)
              c-interpreted (host-node!)
              mounts        (atom [])]
          (-> (act #(reset! mounts
                            [(v/mount [spread-probe {:attrs forwarded}] c-compiled {:frame fid})
                             (v/mount [spread-probe-interpreted {:attrs forwarded}]
                                      c-interpreted {:frame fid})]))
              (.then (fn [_]
                       (is (= {"class"         "card wide from-caller"
                               "title"         "forwarded"
                               "data-x"        "1"
                               "data-override" "literal"}
                              (attrs-of c-compiled "div"))
                           "the forwarded map, the literal override, and the sugar
                            composed ahead of the forwarded class")
                       (is (= (attrs-of c-interpreted "div") (attrs-of c-compiled "div"))
                           "and the compiled element IS the interpreted twin's element")
                       (is (= 0 (:presses (db))) "nothing dispatched before the click")
                       (act #(.click (.querySelector c-compiled "div")))))
              (.then (fn [_]
                       (is (= 1 (:presses (db)))
                           "a FORWARDED handler became a committed site and dispatched")
                       (act #(.click (.querySelector c-interpreted "div")))))
              (.then (fn [_]
                       (is (= 2 (:presses (db)))
                           "and the interpreted twin's forwarded handler dispatched too")
                       (doseq [[c m] (map vector [c-compiled c-interpreted] @mounts)]
                         (unmount! c m))))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "spread arm rejected: " e))
                        (.remove c-compiled)
                        (.remove c-interpreted)
                        nil))
              (.then (fn [_] (done)))))))))

(deftest a-compiled-v-spread-safe-folds-the-guarded-caller-under-the-owned-props
  (testing "The guarded caller map reaches the element; the OWNED props win
            every collision; `:class` is the one exception and COMPOSES,
            owned first. Asserted against the interpreted twin, which
            reaches the same fold through the same function."
    (if-not (browser?)
      (skip! "the browser job runs the forwarding assertions")
      (async done
        (register!)
        (seed! {:text ""})
        (let [caller        {:title "from-caller" :class "caller-class" :aria-label "L"}
              c-compiled    (host-node!)
              c-interpreted (host-node!)
              mounts        (atom [])]
          (-> (act #(reset! mounts
                            [(v/mount [safe-probe {:attrs caller}] c-compiled {:frame fid})
                             (v/mount [safe-probe-interpreted {:attrs caller}]
                                      c-interpreted {:frame fid})]))
              (.then (fn [_]
                       (is (= {"class"      "field owned-class caller-class"
                               "title"      "from-caller"
                               "aria-label" "L"
                               "value"      "owned"}
                              (attrs-of c-compiled "input"))
                           "the caller's attributes landed, and the classes composed
                            owned-first")
                       (is (= (attrs-of c-interpreted "input") (attrs-of c-compiled "input"))
                           "and the compiled element IS the interpreted twin's element")
                       (is (= "owned" (.-value (.querySelector c-compiled "input")))
                           "the owned controlled value is what React is holding")
                       (doseq [[c m] (map vector [c-compiled c-interpreted] @mounts)]
                         (unmount! c m))))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "spread-safe arm rejected: " e))
                        (.remove c-compiled)
                        (.remove c-interpreted)
                        nil))
              (.then (fn [_] (done)))))))))

(deftest a-permitted-caller-handler-through-v-spread-safe-becomes-a-committed-site
  (testing "The deny law below proves `v/spread-safe` REFUSES a caller
            handler that would clobber an owned one. That is only half the
            claim: the form exists to forward a caller map SAFELY, so the
            PERMITTED handler — a family the component does not own — has to
            reach the DOM as a live committed site rather than being blocked
            along with the forged one. Asserted the way its `v/spread` twin
            above asserts the same thing: what app-db holds after a REAL
            click, in both modes."
    (if-not (browser?)
      (skip! "the browser job runs the forwarding assertions")
      (async done
        (register!)
        (seed! {:presses 0 :text ""})
        ;; `safe-probe` owns `:on-input`, so `:on-click` is a family it does
        ;; NOT own — permitted by the same guard that denies `:on-input`.
        (let [caller        {:title "from-caller" :on-click [:compiled/pressed]}
              c-compiled    (host-node!)
              c-interpreted (host-node!)
              mounts        (atom [])]
          (-> (act #(reset! mounts
                            [(v/mount [safe-probe {:attrs caller}] c-compiled {:frame fid})
                             (v/mount [safe-probe-interpreted {:attrs caller}]
                                      c-interpreted {:frame fid})]))
              (.then (fn [_]
                       (is (= 0 (:presses (db))) "nothing dispatched before the click")
                       (act #(.click (.querySelector c-compiled "input")))))
              (.then (fn [_]
                       (is (= 1 (:presses (db)))
                           "a PERMITTED caller handler became a committed site and
                            dispatched through the compiled fold")
                       (act #(.click (.querySelector c-interpreted "input")))))
              (.then (fn [_]
                       (is (= 2 (:presses (db)))
                           "and the interpreted twin's permitted caller handler
                            dispatched too, so the guard blocks the forged carrier
                            without blocking the good one in either mode")
                       (doseq [[c m] (map vector [c-compiled c-interpreted] @mounts)]
                         (unmount! c m))))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "permitted caller-handler arm rejected: " e))
                        (.remove c-compiled)
                        (.remove c-interpreted)
                        nil))
              (.then (fn [_] (done)))))))))

(deftest a-compiled-v-event-prop-arrives-at-the-boundary-and-dispatches
  (testing "The same defect one boundary further out. A `(v/event …)` at a
            CALL SITE is analysed content, and an emitter reading only
            `:value` hands the crossing `{:on-pick nil}` — a callback that
            reaches the child ABSENT, so the button it lands on does
            nothing. What is asserted is the vector app-db received after a
            real click on the CHILD's element."
    (if-not (browser?)
      (skip! "the browser job runs the crossing assertions")
      (async done
        (register!)
        (seed! {})
        (let [container (host-node!)
              mounted   (atom nil)]
          (-> (act #(v/mount [callback-parent {}] container {:frame fid}))
              (.then (fn [m]
                       (reset! mounted m)
                       (is (some? (.querySelector container "#sink"))
                           "the child mounted")
                       (act #(.dispatchEvent (.querySelector container "#sink")
                                             (js/CustomEvent. "click"
                                                              #js {:bubbles true :detail 5})))))
              (.then (fn [_]
                       (is (= [[:compiled/converted 5]] (:converted (db)))
                           "the callback crossed the boundary intact and dispatched
                            the vector its body answered")
                       (unmount! container @mounted)))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "crossing callback arm rejected: " e))
                        (.remove container)
                        nil))
              (.then (fn [_] (done)))))))))

(deftest the-spread-safe-deny-law-still-fires-through-the-compiled-fold
  (testing "The bound is the whole point of the form, so it is not
            dev-gated and it does not lapse because the element was
            compiled: a caller map carrying an OWNED or structural key is
            refused, in both modes, from the one guard both folds call."
    (doseq [[what caller] {"an owned controlled prop" {:value "clobbered"}
                           "an owned handler family"  {:on-input [:caller/typed]}
                           "the reconciliation key"   {:key "k"}}]
      (is (= :rf.error/ui-tree-malformed
             (conf/caught-id #(v/spread-safe {:value "owned"
                                              :on-input [:compiled/typed]}
                                             caller)))
          (str what " is denied to the caller")))
    (is (map? (v/spread-safe {:value "owned"} {:title "fine"}))
        "non-vacuous: an ordinary caller key passes the same guard")))

(deftest a-compiled-controlled-input-round-trips-through-app-db
  (testing "A compiled `:input` carrying `value` is CONTROLLED, and its
            handler's lane is decided from those element facts by the one
            door predicate rather than from a bit the emitter baked in.
            The proof it is wired: what the field shows follows app-db,
            and typing into it reaches app-db."
    (if-not (browser?)
      (skip! "the browser job runs the controlled-input assertions")
      (async done
        (register!)
        (seed! {:text "ab"})
        (let [container (host-node!)
              mounted   (atom nil)]
          (-> (act #(v/mount [field-probe {}] container {:frame fid}))
              (.then (fn [m]
                       (reset! mounted m)
                       (let [el (.querySelector container "#field")]
                         (is (some? el) "the compiled controlled input mounted")
                         (is (= "ab" (.-value el))
                             "its value is the subscription's, not the DOM's")
                         (live!)
                         (keystroke! el "c")
                         (settle!))))
              (.then (fn [_]
                       (is (= "abc" (:text (db)))
                           "the committed handler carried the native value into app-db")
                       (is (= "abc" (.-value (.querySelector container "#field")))
                           "and the field shows what app-db now holds")
                       (unmount! container @mounted)))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "controlled-input arm rejected: " e))
                        (.remove container)
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; Crossings — a compiled parent mounting an interpreted child
;; ===========================================================================

(deftest a-compiled-parent-mounts-an-interpreted-child-in-a-browser
  (testing "Promotion is per declaration and not transitive, so the
            ordinary case is a compiled parent mounting an interpreted
            child. The parent lowered its children into React elements at
            build time and the interpreted child forwards them, so the
            crossing carries FINISHED markup in both directions — which
            the interpreted walk has to accept as a child rather than
            refuse as an unknown value."
    (if-not (browser?)
      (skip! "the browser job runs the crossing assertions")
      (async done
        (let [container (host-node!)]
          (-> (act #(v/mount [crossing-probe {:label "across"}] container))
              (.then (fn [mounted]
                       (is (= "across" (text container "#crossing #interp em.forwarded"))
                           "the compiled parent's forwarded child rendered inside the
                            interpreted boundary it crossed into")
                       (is (= [{:view-id (:view-id (v/describe interpreted-shell))
                                :lowering :interpreted}]
                              (mapv #(select-keys % [:view-id :lowering])
                                    (:crossings (v/manifest crossing-probe))))
                           "and the manifest named that crossing, and its mode")
                       (unmount! container mounted)))
              ;; Reports and releases; it never finishes (rf2-fyba). Asymmetric
              ;; teardown stays put — see the first mounted row above.
              (.catch (fn [e]
                        (is false (str "crossing mount rejected: " e))
                        (.remove container)
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest the-proof-is-not-vacuous
  (testing "Every claim above rests on these being true: the arms really
            are compiled declarations, the fixture really carries DOM
            rows, and the two shell verdicts really differ. Without this
            row a lowering that quietly fell back to the interpreted walk
            — or a fixture that loaded empty — would leave every
            assertion above green for the wrong reason."
    (doseq [[nm view] {:page compiled/page :inert inert-probe :sub sub-probe
                       :event event-probe :ui-event ui-event-probe :field field-probe
                       :spread spread-probe :safe safe-probe
                       :callback-parent callback-parent
                       :crossing crossing-probe}]
      (is (= :compiled (:lowering (v/describe view))) (str nm " is a compiled declaration"))
      (is (some? (v/manifest view)) (str nm " carries a compiled manifest")))
    (doseq [[nm view] {:crossing-target interpreted-shell
                       :spread-twin     spread-probe-interpreted
                       :safe-twin       safe-probe-interpreted}]
      (is (= :interpreted (:lowering (v/describe view)))
          (str nm " is genuinely interpreted — the forwarding twins are the control")))
    (is (<= 8 (count (:dom struct-007))) "the fixture's DOM table is fully loaded")
    (is (not= (:view-cell (v/manifest inert-probe))
              (:view-cell (v/manifest sub-probe)))
        "the two shell arms really do disagree about the ViewCell")
    (is (false? (cell/observing?)) "and the witness reads false outside any render")))
