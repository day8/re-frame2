(ns hicasso-hmr-testbed.views
  "THE RELOADED NAMESPACE — the source file a real `shadow-cljs watch`
  really recompiles, and the only file the HMR gate edits on disk
  (rf2-vsgq, closing the browser half of rf2-hic-015).

  Everything the HMR matrix needs a committing renderer for is declared
  here, through the ordinary authoring surface: a controlled field, a
  child holding React hook state inside a host crossing, and an
  imperative host whose live instance is reachable through a callback
  `:ref`. When `shadow-cljs watch` reloads this module every top-level
  form below re-runs — the `reg-sub`/`reg-event` re-register and every
  `defview` and `defhost` `def` re-mints — which is precisely the event
  the contract is about.

  ## The hot line, and why this file is edited rather than generated

  [[generation-label]] carries the `rf2-vsgq:HOT-LINE` marker and the
  gate rewrites **that one line** to trigger a save. Nothing else in the
  file is touched, the runner restores it in a `finally` and again from
  the harness cleanup, and it refuses to start at all if the line is not
  in its canonical state — so a crashed previous run is a loud red rather
  than a fixture silently baked into the tree.

  Editing a tracked file was chosen over generating one into a gitignored
  path for a single reason: this is the most important file in the gate to
  READ, and a `defview` that lives in a JavaScript template string inside
  the runner is a `defview` nobody reviews. The compile path is the real
  macro either way; the reviewability is not.

  ## Why the label is rendered

  A reload counter alone cannot tell \"shadow delivered new code\" from
  \"the after-load hook ran twice on the old module\". The label is
  rendered into the DOM, so the gate reads the NEW literal off the screen
  and the reload is proven to have carried code rather than merely to have
  fired.

  ## What each declaration is for

  | declaration | the matrix row it serves |
  |---|---|
  | [[field]] | the focused controlled input — caret, selection and composition across a save |
  | [[hook-child]] / [[hook-host]] | a child's `useState` inside a host crossing |
  | [[imperative-note]] / [[note-host]] | the active imperative host: a live instance, held through a callback `:ref`, with a side effect React does not know about |
  | [[island-body]] | the island — a raw React function component, and the one component every wrapper is carried across (rf2-iq0a) |
  | [[host-head]] / [[escape-head]] | that island behind a raw `react/lazy`, so the `React.lazy` bridge meets a real recompile (rf2-y5x6j) |
  | [[app]] | the head whose re-mint is the whole cause, rendered once per frame so frame routing is readable per root |

  The foreign components — the hook child, the imperative host and the
  island — are written with React's own primitives and no Hicasso in
  them at all, which is what makes them a fair witness for \"React facts
  die with the fiber\" ([I6](../../../implementation/hicasso/spec/invariants.md)):
  the runtime is not being asked to preserve anything it could have
  preserved."
  (:require ["react" :as react]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]))

;; ---------------------------------------------------------------------------
;; The hot line
;; ---------------------------------------------------------------------------

(def generation-label
  "The literal the gate rewrites to produce a save. Rendered, so a witness
  reads the new code off the screen rather than trusting a counter.

  The marker comment is the runner's anchor and the canonical-state check
  keys on this exact line; do not reformat it."
  "GEN-A") ;; rf2-vsgq:HOT-LINE

;; ---------------------------------------------------------------------------
;; Instance ids — minted from a counter that OUTLIVES the reload
;; ---------------------------------------------------------------------------

(defonce ^:private !next-instance-id
  ;; `defonce`, so the counter survives the module re-evaluation and the
  ;; ids on either side of a save are comparable. A plain `def` would
  ;; restart at 0 and every post-save instance would collide with a
  ;; pre-save one — the witness would then be unable to tell a preserved
  ;; instance from a rebuilt one, which is the entire question.
  (volatile! 0))

(defn- next-instance-id! [] (vswap! !next-instance-id inc))

;; ---------------------------------------------------------------------------
;; The model — an ordinary re-frame2 app
;; ---------------------------------------------------------------------------

(rf/reg-sub :hmr/label  (fn [db _] (:label db)))
(rf/reg-sub :hmr/text   (fn [db _] (:text db)))
(rf/reg-sub :hmr/digits (fn [db _] (:digits db)))

(rf/reg-event :hmr/seed
  (fn [_ [_ label]] {:db {:label label :text "abcdef" :digits "123"}}))

(rf/reg-event :hmr/edit
  ;; Accepts what is typed. The field is controlled, so the value on
  ;; screen after a save comes from app-db — which is exactly why a value
  ;; assertion cannot see the remount and the caret can.
  (fn [{:keys [db]} [_ typed]] {:db (assoc db :text typed)}))

(rf/reg-event :hmr/edit-digits
  ;; REFUSES anything but digits, and the composition row needs exactly
  ;; that. A composing draft must be a value the model never took, or
  ;; "the draft did not survive the save" would be satisfied by app-db
  ;; having simply accepted it and repainted the same characters back.
  (fn [{:keys [db]} [_ typed]]
    {:db (assoc db :digits (if (re-matches #"[0-9]*" typed) typed (:digits db)))}))

(rf/reg-event :hmr/set-label
  (fn [{:keys [db]} [_ label]] {:db (assoc db :label label)}))

;; ---------------------------------------------------------------------------
;; The foreign components — plain React, no Hicasso inside
;; ---------------------------------------------------------------------------

(defn- HookChild
  "A child holding ordinary React hook state, and nothing else.

  Two `useState` cells: the counter the driver bumps, and a per-mount
  instance id taken from the lazy initialiser — which runs once per fiber,
  so the id IS the fiber's identity as seen from outside. A remount shows
  up twice over, as a reset counter and as a new id, and the pair is what
  separates \"the state was cleared\" from \"the component was rebuilt\"."
  [^js props]
  (let [[n set-n]  (react/useState 0)
        [id _]     (react/useState next-instance-id!)]
    (react/createElement
      "div" #js {"data-testid" "hook-child"}
      (react/createElement "span" #js {"data-testid" "hook-count"} (str n))
      (react/createElement "span" #js {"data-testid" "hook-instance"} (str id))
      (react/createElement "button"
                           #js {"data-testid" "hook-bump"
                                "onClick"     (fn [_] (set-n inc))}
                           "bump")
      (.-children props))))

(def ^:private imperative-note
  "AN IMPERATIVE HOST — a `forwardRef` component publishing a handle
  through `useImperativeHandle`.

  `note` writes `textContent` on a node the component owns, DIRECTLY.
  That is the point of the row: the write is invisible to React, so no
  re-render restores it and no subscription repaints it. If the text is
  gone after a save, the only thing that can have removed it is the node
  having been destroyed — which is the claim.

  The handle also carries the instance id, so the driver can tell a
  re-attached ref to the SAME instance from a ref re-attached to a new
  one. A ref-callback firing is not by itself evidence of a remount:
  React detaches and re-attaches on plenty of occasions."
  (react/forwardRef
    (fn [^js _props ref]
      (let [node-ref (react/useRef nil)
            [id _]   (react/useState next-instance-id!)]
        (react/useImperativeHandle
          ref
          (fn []
            #js {"instanceId" id
                 "note"       (fn [text]
                                (when-some [n (.-current node-ref)]
                                  (set! (.-textContent n) text))
                                id)})
          #js [id])
        (react/createElement
          "p" #js {"data-testid" "note" "ref" node-ref} "")))))

;; ---------------------------------------------------------------------------
;; The island, and the `React.lazy` bridge it arrives through
;; ---------------------------------------------------------------------------
;;
;; ONE component, carried across every wrapper React has (rf2-iq0a), and
;; reached through a real `react/lazy` payload so the code-splitting bridge
;; meets a real recompile (rf2-y5x6j). The chain is
;;
;;   island-body   defn             a raw React function component over
;;                                  react/createElement, no Hicasso in it
;;     -> island   react/memo       the memo record around it
;;     -> *-head   react/lazy       a lazy record over the payload, one per crossing
;;     -> crossed  defhost AND [:>] both crossings, so neither door is assumed
;;                                  to behave like the other: the defhost one
;;                                  declares `:server :client-only` and gates
;;                                  the head behind it, the [:>] one is the
;;                                  door with the declaration erased
;;   with a callback `:ref` through both, and the save's re-mint over the top.
;;
;; Every link is a top-level `def` in this namespace, so a save re-evaluates
;; each one and the object at that position is NEW — allocation, never a
;; lookup by name. That is React's own remount rule, and it is the whole of
;; the island's HMR contract: the gate measures it by identity.
;;
;; ## The island holds NO SUBSCRIPTION, and that is a decision
;;
;; It reads `useState` and nothing else — no `n/use-sub`, no `h/sub`. A
;; subscribing island would contribute cells and reader memberships to
;; `runtime/residue`, and this region RE-SUSPENDS on every save: the head
;; is re-minted, its payload is fresh, and React shows the fallback until
;; the chunk arrives. The arrival lands on React's scheduler, while
;; `zero-stale-registrations` and `lost-cleanup-sabotage` take their
;; residue baselines behind `runtime/quiesced!` — a `setTimeout` past the
;; reap horizon. Those two clocks are not ordered with respect to each
;; other, so a subscribing island here would make every census comparison
;; in this suite a race against React's scheduler rather than a statement
;; about the runtime. Contributing zero to the census is what keeps this
;; fixture additive; the ownership claim is then made on the WHOLE census,
;; which the rest of the app populates, and it says the lazy re-load and
;; the remount leak nothing into it.

(defonce ^:private !island-loads
  ;; `defonce`, so the count spans the reload it is measuring. The whole of
  ;; rf2-y5x6j's "which of the two is it" turns on this integer: a head
  ;; re-minted by the save gets a fresh payload and the chunk is fetched
  ;; AGAIN, so the boundary re-loads rather than the loaded module
  ;; surviving.
  (volatile! 0))

(defn island-loads
  "How many times the chunk has been asked for. Read through the door."
  []
  @!island-loads)

(defn- island-body
  "THE ISLAND — a raw React function component: a plain `defn` over
  `react/createElement`, with no Hicasso in it. Its server policy is
  declared where a crossing declares one, on [[island-host]].

  Its `useState` instance id is minted from the counter that outlives the
  reload, exactly as [[HookChild]]'s is, so a rebuilt island and a
  preserved one are told apart by a number rather than by a repaint."
  [^js props]
  (let [[id _] (react/useState next-instance-id!)]
    (react/createElement
      "div" #js {"data-testid" (.-slot props)
                 "ref"         (.-nodeSink props)}
      (react/createElement "span" #js {"data-testid" (str (.-slot props) "-instance")}
                           (str id))
      (react/createElement "span" #js {"data-testid" (str (.-slot props) "-gen")}
                           generation-label))))

(def island
  "The memo wrapper — `react/memo` and nothing else. A memo record is the
  element type React reconciles on; a save re-evaluates this `def` and
  allocates a fresh record around the fresh function, so the record MUST
  NOT survive a hot reload, and the gate measures that by identity."
  (react/memo island-body))

(defn- load-island!
  "The chunk fetch, counted.

  A resolved promise rather than a network round trip: what is under test
  is the BRIDGE — `react/lazy`'s payload, the Client-only gate `defhost`
  puts in front of it, React's Suspense and the re-mint — and a real
  `shadow.lazy/load` would add a second module to the build without
  adding anything to the claim. The loader resolves to the module record
  `React.lazy` reads, `#js {:default island}`; it is the one part of the
  shape that is stubbed, and it is stubbed where
  `lazy_boundary_dom_cljs_test`'s `deferred-loader` stubs it."
  []
  (vswap! !island-loads inc)
  (js/Promise.resolve #js {:default island}))

;; TWO heads over ONE loader, which is `lazy_boundary_dom_cljs_test`'s own
;; shape for the same reason: it puts the payload's identity on the table.
;; One head per crossing also makes the loader count DISCRIMINATE — a clean
;; save re-mints both and costs two fetches, and the sabotage below pins one
;; of them and costs one.

(def host-head
  "The head behind the `defhost` crossing — a raw `react/lazy` record."
  (react/lazy load-island!))

(def escape-head
  "The head behind the `[:>]` crossing — the one the sabotage pins."
  (react/lazy load-island!))

;; --- the sabotage: a head that survived the reload -------------------------
;;
;; The fault rf2-y5x6j asks for: a bridge that caches a head BY NAME. That
;; would preserve identity across a reload and quietly contradict React's
;; remount rule — a `def` the save re-evaluates is a new object, and React
;; replaces the subtree at that position. This is that runtime, toggled at
;; run time — the app renders the head it captured before the save instead
;; of the one the save minted. Recoverable, unlike the lost-cleanup
;; sabotage: disarming restores the live head and the next save re-mints
;; normally.

(defonce ^:private !pinned-escape-head (volatile! nil))

(defn pin-escape-head!
  "Arm or disarm the pinned-head sabotage. Answers whether it is armed."
  [on?]
  (vreset! !pinned-escape-head (when on? escape-head))
  (some? @!pinned-escape-head))

(defn- escape-head-now
  []
  (or @!pinned-escape-head escape-head))

(defn live-heads
  "The heads as the module currently holds them, for the door's identity
  baseline. Answers the LIVE `escape-head` rather than
  [[escape-head-now]] — the sabotage is a fact about what the app RENDERS,
  and a baseline that moved with it could not see the pin."
  []
  #js {"islandBody" island-body
       "islandMemo" island
       "hostHead"   host-head
       "escapeHead" escape-head
       "rendered"   (escape-head-now)})

;; ---------------------------------------------------------------------------
;; The crossings and the views
;; ---------------------------------------------------------------------------

;; `defhost` takes its docstring BETWEEN the name and the component —
;; `(defhost sym "doc" Component opts?)`. Written the other way round the
;; docstring lands in the `opts` position and `mint-host!` walks a string
;; as an option map.
(rf.hicasso/defhost hook-host
  "The host crossing the hook child sits inside, so the row is `child hook
  state IN A HOST` and not merely `hook state somewhere on the page`."
  HookChild)

(rf.hicasso/defhost note-host
  "The imperative host. Its instance arrives through a callback `:ref` —
  HD-022's v0 spelling, a function and never a vector."
  imperative-note)

(rf.hicasso/defhost suspense-host
  "React's own Suspense, wrapping the split region and NOTHING else. The
  scope is deliberate: this region shows its fallback on every save, and a
  boundary drawn any wider would take the controlled field, the hook child
  and the imperative host down with it — every one of which is another
  section's subject."
  (.-Suspense react)
  {:slots #{:fallback}})

(rf.hicasso/defhost island-host
  "The `defhost` crossing over the lazy head — a declaration, with the
  island's server policy written where a crossing declares one.
  `:server :client-only` is the default and needs no writing; it is
  written so the policy is a fact a reader can see rather than a default
  nobody wrote. The island's props ABI and its `:ref` pass straight
  through the gate."
  host-head
  {:server :client-only})

(rf.hicasso/defview field
  "The focused controlled input. An ordinary `:value` off a subscription
  and an intent vector at `:on-input`; nothing test-only on it."
  [_]
  [:input {:data-testid "field"
           :type        "text"
           :value       (rf.hicasso/sub [:hmr/text])
           :on-input    [:hmr/edit ::rf.hicasso/value]}])

(rf.hicasso/defview digits-field
  "The refusing field the composition row is driven on — the case
  `hmr_registry`'s browser sibling could not reach, and the one where a
  held draft is provably the model's refusal rather than its agreement."
  [_]
  [:input {:data-testid "digits"
           :type        "text"
           :value       (rf.hicasso/sub [:hmr/digits])
           :on-input    [:hmr/edit-digits ::rf.hicasso/value]}])

(rf.hicasso/defview app
  "The head whose re-mint is the whole mechanism. Mounted once per frame,
  so each root reads its own frame and the routing row can compare them.

  `ref-sink` is handed down from the shell rather than closed over here:
  it must survive the reload so the driver keeps reading the same sink
  across a save, and anything defined in THIS namespace is replaced by the
  save under test. `island-refs` is handed down for the same reason and
  keyed by slot, so one stable function serves each crossing and React is
  never made to detach a ref for a render that remounted nothing."
  [{:keys [ref-sink island-refs]}]
  [:main {:data-testid "hmr-app"}
   [:span {:data-testid "gen-label"} generation-label]
   [:span {:data-testid "frame-label"} (rf.hicasso/sub [:hmr/label])]
   [field {}]
   [digits-field {}]
   [hook-host {}]
   [note-host {:ref ref-sink}]
   ;; The split region. Both crossings reach the same island through their
   ;; own head, so a save costs two fetches and neither door's conduct is
   ;; inferred from the other's.
   [suspense-host {:fallback [:span {:data-testid "island-fallback"} "loading"]}
    [island-host {:slot     "island-host"
                  :nodeSink (get island-refs "island-host")}]
    [:> (escape-head-now) {:slot     "island-escape"
                           :nodeSink (get island-refs "island-escape")}]]])
