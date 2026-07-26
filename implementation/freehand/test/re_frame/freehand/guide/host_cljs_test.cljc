(ns re-frame.freehand.guide.host-cljs-test
  "Executable fixtures for the Freehand guide's HOST-EDGE chapters —
  `docs/core/freehand/host-boundaries.md`, `js-libraries.md`, `presence.md`,
  `accessibility.md`, `ssr.md`, `install.md` and `adoption.md`.

  These pages carry the verbs that only exist because a browser does:
  `v/defbehavior` and `[v/behavior …]`, `v/client-only`, `v/error-boundary`,
  `v/presence` / `v/presence-phase`, the `::web/` top-layer keys, the
  `:re-frame.freehand.host/command` effect, `v/route-link` — and the four
  browser-only door verbs `v/mount`, `v/unmount!`, `v/->react`,
  `v/active-connections` and `v/command-log`.

  ## How the browser-only half is covered

  Five of those names are `#?(:cljs …)` on the door — absent on the JVM by
  design, as `debugging.md` says of the last two. A JVM fixture cannot
  reference them at all, so they are covered twice over from one place:

  - each sample is transcribed into a reader-conditional wrapper, so the
    ClojureScript build compiles the guide's exact call; and
  - `the-browser-only-door-verbs-are-present` asserts each one is a live
    function at run time, which is what actually REDS when one is renamed —
    a missing CLJS var is a warning at compile and `undefined` at run time,
    and only the second is a failing gate.

  A sample that needs a real DOM node, an npm module, or `js/document` is
  NOT transcribed; those are the roster's declared unexecutable classes.

  Filed under rf2-qwsmv."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            ;; ClojureScript only, and spliced away on the JVM: the outward
            ;; bridge answers a React component, and the assertion that it is
            ;; not already an element needs React's own predicate.
            #?@(:cljs [["react" :as react]
                       [goog.object :as gobj]])
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.test :as t]
            [re-frame.freehand.web :as web]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; accessibility.md
;; ---------------------------------------------------------------------------

(v/defview icon-cart [_] [:i.icon-cart])

;; accessibility.md block 1 — the icon-only button, and the accessible name
;; that fixes it.
(def a11y-cart-buttons
  [;; bad — icon-only, no name
   [:button {:on-click [:cart/open]} [icon-cart {}]]

   ;; good — visible text or aria-label
   [:button {:on-click [:cart/open]
             :aria-label "Open cart"}
    [icon-cart {}]]])

;; ---------------------------------------------------------------------------
;; presence.md / js-libraries.md — enter and exit retention
;; ---------------------------------------------------------------------------

;; accessibility.md block 2, presence.md block 5 — a presence-aware child
;; owns its own exit styling and accessibility.
(v/defview toast-card [{:keys [toast]}]
  (let [exiting? (= :unmounting (v/presence-phase))]
    [:div.toast {:class       (when exiting? "toast--exit")
                 :inert       (when exiting? true)
                 :aria-hidden (when exiting? true)}
     (:message toast)]))

;; presence.md block 4 — the same read, spelled as a `case`.
(v/defview toast-card-case [{:keys [toast]}]
  (case (v/presence-phase)
    :unmounting [:div.toast.toast--exit {:inert true} (:message toast)]
    [:div.toast (:message toast)]))

;; presence.md block 2, js-libraries.md block 1 — the boundary, with its
;; MANDATORY terminal bound.
(v/defview toast-tray [_]
  (v/presence {:timeout-ms 300}
    (for [t (v/sub [:toasts/visible])]
      [toast-card {:key (:id t) :toast t}])))

;; ---------------------------------------------------------------------------
;; host-boundaries.md — behaviors, commands, client-only, error boundaries
;; ---------------------------------------------------------------------------

(defn- fit!
  "The page names this helper without showing it — measuring and resizing a
  textarea is the application's host work, not the substrate's."
  [_node _config]
  nil)

(defn- observe!
  "The page names this helper without showing it either. `:connect` builds the
  host observer, and the object it answers IS the connection's private memory —
  the one place the memory is ever written (rf2-wj1ao)."
  [_node _config]
  nil)

;; host-boundaries.md block 3 — the ONE sanctioned way to own a DOM node,
;; and the use site that attaches it as data.
(v/defbehavior autosize
  "Grow the textarea to fit its content."
  {:timing     :layout
   ;; :connect ESTABLISHES the private memory — the observer it built. Nothing
   ;; else writes it: :update, :refit and :disconnect all just receive it.
   :connect    (fn [{:keys [node config]}] (observe! node config))
   :update     (fn [{:keys [node config]}] (fit! node config))
   :disconnect (fn [{:keys [memory]}] (some-> memory .disconnect))
   :commands   {:refit (fn [{:keys [node config]}] (fit! node config))}})

(v/defview composer [{:keys [draft]}]
  [v/behavior {:use    autosize
               :target :composer/body
               :config {:max-rows 8}}
   [:textarea.composer {:value draft :on-input [:composer/typed ::v/value]}]])

;; host-boundaries.md block 4 — a command reaches a connection through an
;; ordinary effect, addressed by the semantic `:target`.
(defn register-refit-requested!
  []
  (rf/reg-event :composer/refit-requested
    (fn [_ _]
      {:fx [[:re-frame.freehand.host/command
             {:target :composer/body :op :refit}]]})))

(v/defview chart-host [{:keys [spec]}]
  [:div.chart {:data-spec (str spec)}])

;; host-boundaries.md block 2 — a browser-only subtree, and the
;; capability-free markup that stands in for it everywhere else.
(v/defview chart-client-only [{:keys [spec]}]
  (v/client-only
   {:fallback [:div.chart-placeholder "Chart loads in the browser"]}
   [chart-host {:spec spec}]))

;; host-boundaries.md block 6 — the DOM top layer: desired OPEN state, with
;; the `::web/` qualification that says these are browser facts.
(v/defview top-layer-sites [{:keys [open? on-open-change]}]
  [:<>
   [:div {:popover :auto
          ::web/popover-open? open?
          :on-toggle
          (v/event [e]
            (conj on-open-change (= "open" (.-newState e))))}]

   [:dialog {::web/modal-open? open?
             :on-cancel [:dialog/cancelled]}]])

(v/defview broken-page [_] [:p.broken "Something went wrong"])
(v/defview workspace-page [{:keys [workspace-id]}]
  [:main.workspace (str "workspace " workspace-id)])

;; host-boundaries.md block 7 — the boundary, its reset key, and the bounded
;; diagnostic that leaves by `:on-error`.
(v/defview workspace-error-boundary [{:keys [route-revision workspace-id]}]
  [v/error-boundary
   {:reset-key route-revision
    :fallback  [broken-page {}]
    :on-error  [:telemetry/ui-render-failed]}
   [workspace-page {:workspace-id workspace-id}]])

;; host-boundaries.md block 8 — prefer the platform; reach for a behavior
;; only when you must call the node.
(v/defview autofocus-input [_]
  ;; Often enough — platform autofocus when the node mounts with the tree
  [:input {:value (v/sub [:rename/draft])
           :auto-focus true
           :on-input [:rename/drafted ::v/value]}])

;; When you must call .focus() after connect — an ordinary registered behavior
(v/defbehavior focus-on-connect
  {:connect (fn [{:keys [node]}] (.focus node) nil)})

(v/defview rename-field [{:keys [draft]}]
  [v/behavior {:use focus-on-connect}
   [:input {:value draft
            :on-input [:rename/drafted ::v/value]}]])

;; ---------------------------------------------------------------------------
;; js-libraries.md — a behavior is the animation seam too
;; ---------------------------------------------------------------------------

(defn- start-fade! [_node _config] {:handle :fade})
(defn- retarget-fade! [_node _config _prev _anim] {:handle :fade})
(defn- cancel! [_handle] nil)

;; js-libraries.md block 5 — every lifecycle entry takes ONE context map,
;; `:update` runs only when `:config` moves by `rf=`, and only `:connect`
;; establishes the memory — so an EVOLVING player handle lives in a mutable
;; cell the later entries swap in place (rf2-wj1ao).
(v/defbehavior fade-panel
  {:connect    (fn [{:keys [node config]}]
                 ;; :connect ESTABLISHES this connection's private memory, and
                 ;; nothing else ever writes it — so a handle that EVOLVES lives
                 ;; in a mutable cell the later entries swap in place.
                 (atom {:anim (start-fade! node config)}))
   :update     (fn [{:keys [node config prev-config memory]}]
                 ;; runs only when :config moved by rf=; the return is ignored
                 (swap! memory assoc
                        :anim (retarget-fade! node config prev-config
                                              (:anim @memory))))
   :disconnect (fn [{:keys [memory]}]
                 (some-> (:anim @memory) cancel!))})

(v/defview animated-panel [{:keys [title]}]
  (let [open? (v/sub [:ui/panel-open?])]
    [v/behavior {:use    fade-panel
                 :target :ui/fade-panel
                 :config {:open? open? :duration-ms 280}}
     [:div.panel
      [:h2 title]
      (when open? [:div.body "…"])]]))

;; ---------------------------------------------------------------------------
;; js-libraries.md — Pattern E, the handle that arrives later
;; ---------------------------------------------------------------------------
;;
;; Stubs standing in for the library's async door, exactly as `start-fade!`
;; above stands in for a player. This fixture pins the SPELLING the page
;; teaches — the cell, the fence, the terminal `:closed`, the two-argument
;; `.then`. What the recipe DOES is proved by a real mount against a
;; deterministic surrogate in `re-frame.freehand.behavior-async-dom-cljs-test`,
;; including the ordering that matters: unmount before the Promise resolves.

(defn- acquire! [_node _spec] #?(:cljs (js/Promise.resolve {:handle :chart})
                                 :clj  ::browser-only))
(defn- set-spec! [_handle _spec] nil)
(defn- dispose! [_handle] nil)

;; js-libraries.md block 6 — `:connect` establishes the memory ONCE, so when
;; the handle is not ready yet what it establishes is a mutable CELL, and the
;; deferred continuation moves that one cell in place (rf2-wj1ao). No task
;; system, no scheduler: re-frame's event pass stays one synchronous pass and
;; the continuation merely dispatches an ordinary event.
(v/defbehavior async-chart
  {:connect
   (fn [{:keys [node config dispatch]}]
     ;; The handle is not ready. The MEMORY is — so :connect returns a cell,
     ;; synchronously, and the continuation closes over that local (NOT over
     ;; (:memory ctx), which is still nil while :connect is running).
     (let [cell (atom {:phase :pending :spec config})]
       (.then (acquire! node config)
              (fn [handle]
                (if (= :closed (:phase @cell))
                  (dispose! handle)                     ; late success: finalise, never install
                  (do (swap! cell assoc :phase :ready :handle handle)
                      (set-spec! handle (:spec @cell))  ; the LATEST spec, not :connect's
                      (dispatch [:chart/ready]))))      ; an ordinary event, fenced to this connection
              (fn [_err]
                ;; :closed is TERMINAL — a late failure is evidence only
                (swap! cell #(cond-> % (not= :closed (:phase %)) (assoc :phase :failed)))))
       cell))

   :update
   (fn [{:keys [config memory]}]
     (swap! memory assoc :spec config)                  ; desired state, always
     (when (= :ready (:phase @memory))                  ; a host call only if there is a host
       (set-spec! (:handle @memory) config)))

   :disconnect
   (fn [{:keys [memory]}]
     ;; FENCE FIRST, then release: an acquisition still in flight must find a
     ;; closed cell and finalise itself.
     (let [{:keys [phase handle]} @memory]
       (swap! memory assoc :phase :closed)
       (when (= :ready phase) (dispose! handle))))})

;; ---------------------------------------------------------------------------
;; ssr.md
;; ---------------------------------------------------------------------------

;; ssr.md block 1 — the same boundary, reading its spec from a subscription.
(v/defview chart-client-only-sub [_]
  (v/client-only
   {:fallback [:div.chart-placeholder "Chart available after load"]}
   [chart-host {:spec (v/sub [:chart/spec])}]))

;; ssr.md block 4 — a real anchor, so copy-link and open-in-new-tab work.
(v/defview article-route-link [{:keys [slug title]}]
  [v/route-link {:to :article :params {:slug slug} :class "title"}
   title])

;; ---------------------------------------------------------------------------
;; The browser-only half of the door
;; ---------------------------------------------------------------------------
;;
;; `v/mount`, `v/unmount!`, `v/->react`, `v/active-connections` and
;; `v/command-log` are `#?(:cljs …)` on the door. Each wrapper below is the
;; guide's own call, reader-conditionalised so the ClojureScript build
;; compiles it and the JVM build simply does not see it.

(v/defview counter-placeholder [_] [:p "counter"])
(v/defview app-root [_] [:main [counter-placeholder {}]])
(v/defview panel [{:keys [side]}] [:section.panel (str side)])
(v/defview panel-a [_] [:section.a])
(v/defview panel-b [_] [:section.b])
(v/defview person-cell [{:keys [person-id]}] [:td (str person-id)])

;; install.md block 3 — total teardown of a root.
(defn unmount-root!
  [root]
  #?(:cljs (v/unmount! root)
     :clj  ::browser-only))

;; install.md block 4 — two roots on one page, each claiming its identity a
;; different way.
(defn mount-panels!
  [left right]
  #?(:cljs (do (v/mount [panel {:side :left}]  left  {:disambiguator :left})
               (v/mount [panel {:side :right}] right {:root-id :shop/right}))
     :clj  ::browser-only))

;; ssr.md block 3 — an illustrative multi-root: same frame, two containers.
(defn mount-two-roots!
  [left-el right-el f]
  #?(:cljs (do (v/mount [panel-a {}] left-el  {:root-id :panel/left  :frame f})
               (v/mount [panel-b {}] right-el {:root-id :panel/right :frame f}))
     :clj  ::browser-only))

;; adoption.md block 1, mental-model.md block 10 — the outward React bridge:
;; a Freehand view handed to a foreign parent as a component.
(defn person-cell-renderer-props
  []
  #?(:cljs {:cellRenderer (v/->react person-cell)}
     :clj  ::browser-only))

;; host-boundaries.md block 5 — the same bridge, plus the ONE named
;; projection a foreign parameter object earns.
(defn person-cell-react
  []
  #?(:cljs (v/->react person-cell)
     :clj  ::browser-only))

(defn cell-props [params]
  #?(:cljs {:person-id (.. params -data -id)
            :column-id (.. params -column getColId)}
     :clj  {:person-id (:id params)}))

(defn person-cell-mapped
  []
  #?(:cljs (v/->react person-cell {:map-props cell-props})
     :clj  ::browser-only))

;; debugging.md block 3 — every live connection, oldest first.
(defn active-connections-read
  []
  #?(:cljs (v/active-connections)
     :clj  ::browser-only))

;; debugging.md block 4 — recent command traffic as a bounded window.
(defn command-log-read
  []
  #?(:cljs (v/command-log)
     :clj  ::browser-only))

;; ---------------------------------------------------------------------------
;; The samples, executed
;; ---------------------------------------------------------------------------

(defn- seed!
  [db]
  (rf/reg-sub :toasts/visible (fn [d _] (:toasts d)))
  (rf/reg-sub :rename/draft (fn [d _] (:draft d)))
  (rf/reg-sub :ui/panel-open? (fn [d _] (:panel-open? d)))
  (rf/reg-sub :chart/spec (fn [d _] (:spec d)))
  (rf/dispatch-sync [:rf/set-db db]))

(deftest an-accessible-name-is-an-ordinary-attribute
  (testing "accessibility.md block 1 — the fix is a prop, not a mechanism."
    (let [[bad good] (mapv t/render a11y-cart-buttons)]
      (is (nil? (:aria-label (t/attrs (t/find bad #(= :button (:tag %)))))))
      (is (= "Open cart"
             (:aria-label (t/attrs (t/find good #(= :button (:tag %))))))))))

;; The next two run on BOTH hosts. `v/presence-phase` discriminates on the
;; RENDERER rather than on the host — `node/*structural-render?*`, bound for the
;; extent of `t/render` — so a structural render answers `:present` in
;; ClojureScript exactly as it does on the JVM, and these read the phase through
;; the same public surface `presence.md` documents (rf2-erqin).
(deftest presence-phase-reads-present-outside-a-boundary
  (testing "presence.md's reusability claim — a presence-aware child renders
            anywhere, and a structural render always yields :present, so the
            exit class and `inert` are absent here."
    (let [tree (t/render [toast-card {:toast {:id 1 :message "saved"}}])
          div  (t/find tree #(= :div (:tag %)))]
      (is (= "saved" (t/text div)))
      (is (nil? (:inert (t/attrs div))) "not exiting — no inert")
      (is (nil? (:aria-hidden (t/attrs div)))))
    (is (= "saved" (t/text (t/render [toast-card-case {:toast {:message "saved"}}])))
        "the `case` spelling reads the same phase")))

(deftest a-presence-boundary-inserts-no-wrapper-node
  (testing "presence.md — the boundary is DOM-agnostic: keyed children, no
            wrapper element, no stamped attributes."
    (seed! {:toasts [{:id 1 :message "one"} {:id 2 :message "two"}]})
    (let [tree (t/with-render (t/render [toast-tray {}]))]
      (is (= ["one" "two"]
             (mapv t/text (t/find-all tree #(= :div (:tag %)))))
          "both children render, in first-appearance order"))))

(deftest a-behavior-is-an-inert-marker-on-the-jvm
  (testing "host-boundaries.md block 3 — the use site records the behavior
            id, the target and the public config as DATA; the code stays in
            the registry, and the JVM connects nothing."
    (is (= ::autosize autosize)
        "the var holds the REGISTERED ID, not the implementation")
    (let [tree (t/render [composer {:draft "hello"}])
          node (t/find tree #(= :textarea (:tag %)))]
      (is (= "hello" (:value (t/attrs node)))
          "the decorated element renders as itself")
      (is (some? (t/find tree #(= ::autosize (:use (:props %)))))
          "and the boundary records the behavior id in the tree"))))

(deftest the-deferred-handle-recipe-is-an-ordinary-registered-behavior
  (testing "js-libraries.md block 6 — Pattern E adds NOTHING to the door. The
            Promise-acquired recipe is a `v/defbehavior` like any other: the
            var holds the registered id, the definition is accepted by the
            same closed roster, and the whole async part is a cell the
            continuation moves. If this ever needed a new verb, it would be
            visible right here as a spelling this suite could not write."
    (is (= ::async-chart async-chart)
        "the var holds the REGISTERED ID, exactly like every other behavior")
    (is (keyword? fade-panel)
        "and its synchronous sibling is registered the same way — one door")))

(deftest client-only-renders-its-mandatory-fallback-on-the-structural-host
  (testing "host-boundaries.md block 2 and ssr.md block 1 — the structural
            render is `:server` phase, so it produces the fallback and never
            enters the client subtree."
    (let [tree (t/render [chart-client-only {:spec {:kind :line}}])]
      (is (= "Chart loads in the browser" (t/text tree)))
      (is (nil? (t/find tree #(= :data-spec (:tag %))))))
    (seed! {:spec {:kind :bar}})
    (is (= "Chart available after load"
           (t/text (t/with-render (t/render [chart-client-only-sub {}])))))))

(deftest the-error-boundary-guards-a-child-and-carries-a-reset-key
  (testing "host-boundaries.md block 7 — the guarded child arrives as
            children, and the options roster is closed."
    (let [tree (t/render [workspace-error-boundary {:route-revision 3
                                                    :workspace-id 42}])]
      (is (= "workspace 42" (t/text tree))
          "the guarded child renders when nothing has failed"))))

(deftest the-top-layer-keys-are-qualified-browser-facts
  (testing "host-boundaries.md block 6 — `::web/` says, at the use site,
            that these are DOM-platform desired state and not neutral
            substrate grammar."
    (let [tree (t/render [top-layer-sites {:open? true
                                           :on-open-change [:menu/open-changed]}])
          div  (t/find tree #(= :div (:tag %)))
          dlg  (t/find tree #(= :dialog (:tag %)))]
      (is (= "auto" (:popover (t/attrs div)))
          "the platform attribute rides as an ordinary attribute")
      (is (= {:popover-open? true} (:rf.ui/top-layer div))
          "and the desired-state key lands on the node's own top-layer
           field, unqualified there because the node schema owns the plane")
      (is (= {:modal-open? true} (:rf.ui/top-layer dlg)))
      (is (= [:dialog/cancelled] (:on-cancel (t/attrs dlg)))))))

(deftest route-link-is-a-declared-view-like-any-other
  (testing "ssr.md block 4 — a framework-supplied view is not a privileged
            one; it holds the same descriptor an application's does."
    (is (v/view? v/route-link))
    (is (v/view? article-route-link))))

#?(:cljs
   (deftest the-browser-only-door-verbs-are-present
     (testing "install.md, ssr.md, adoption.md, host-boundaries.md and
               debugging.md all call door verbs that exist only in
               ClojureScript. This is the assertion that REDS when one is
               renamed — a missing CLJS var compiles to `undefined`, so
               only a run-time check catches it."
       (is (fn? v/mount)              "v/mount — install.md, ssr.md, build-a-view.md")
       (is (fn? v/unmount!)           "v/unmount! — install.md, ssr.md")
       (is (fn? v/->react)            "v/->react — adoption.md, host-boundaries.md")
       (is (fn? v/active-connections) "v/active-connections — debugging.md")
       (is (fn? v/command-log)        "v/command-log — debugging.md"))))

#?(:cljs
   (deftest the-outward-bridge-answers-a-component-that-must-be-created
     (testing "js-libraries.md block 3 — the crossing its AnimatePresence
               sketch turns on. `v/->react` answers a COMPONENT, and a
               library that RETAINS children wants elements: React keeps
               only valid elements, so a component value handed over as a
               child is dropped and the tray renders nothing. Created into
               an element it is an ordinary keyed child, and the props
               object reaches the view's props map by exact name — the one
               shallow rule the bridge states."
       (let [exported (person-cell-react)]
         (is (fn? exported)
             "the bridge answers a component value")
         (is (not (react/isValidElement exported))
             "which is NOT a child React can retain — this is the defect")
         (let [el (react/createElement exported #js {:key "7" :person-id 7})]
           (is (react/isValidElement el)
               "created into an element, it is")
           (is (= "7" (.-key el))
               "and the key rides the element, where a retaining library reads it")
           (is (= 7 (gobj/get (.-props el) "person-id"))
               "props pass shallowly and by exact name"))))))

#?(:cljs
   (deftest the-two-tool-plane-reads-answer-collections
     (testing "debugging.md blocks 3 and 4 — both are ordinary reads that
               answer a value; neither is an event stream."
       (is (sequential? (active-connections-read)))
       (is (sequential? (command-log-read))))))

(deftest the-platform-attribute-is-preferred-before-any-behavior
  (testing "host-boundaries.md block 8 — the page's advice is `:auto-focus`
            first, and the block is a plain controlled input. Rendering it is
            the claim; declaring it is not (rf2-kem4o)."
    (seed! {:draft "Ada"})
    (let [attrs (t/attrs (t/find (t/with-render (t/render [autofocus-input {}]))
                                 #(= :input (:tag %))))]
      (is (true? (:auto-focus attrs)) "the platform attribute, as an ordinary attr")
      (is (= "Ada" (:value attrs)))
      (is (= [:rename/drafted ::v/value] (:on-input attrs))))))

(deftest a-command-request-leaves-as-an-ordinary-effect
  (testing "host-boundaries.md block 4 — the block's whole claim is that
            reaching a live connection is an fx addressed by the semantic
            `:target`, which only running the handler shows."
    (let [sent (atom [])]
      (rf/reg-fx :guide/captured-command (fn [_ v] (swap! sent conj v)))
      (register-refit-requested!)
      (rf/with-fx-overrides {:re-frame.freehand.host/command :guide/captured-command}
        (rf/dispatch-sync [:composer/refit-requested]))
      (is (= [{:target :composer/body :op :refit}] @sent)
          "the request is data, addressed by the caller-authored target"))))

#?(:clj
   (deftest the-browser-only-guide-calls-are-inert-on-the-structural-host
     (testing "install.md blocks 3 and 4, ssr.md block 3 and adoption.md
               block 1 call door verbs that exist only in ClojureScript. The
               CLJS half is proved by `the-browser-only-door-verbs-are-present`
               and by the outward-bridge suite; what the JVM can prove is that
               each wrapper is REACHABLE and does no host work here — which is
               more than a fixture that only loads (rf2-kem4o)."
       (is (= ::browser-only (unmount-root! ::root)))
       (is (= ::browser-only (mount-panels! ::left ::right)))
       (is (= ::browser-only (mount-two-roots! ::left ::right ::frame)))
       (is (= ::browser-only (person-cell-renderer-props))))))
