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
  | [[app]] | the head whose re-mint is the whole cause, rendered once per frame so frame routing is readable per root |

  The two foreign components are written with React's own primitives and
  no Hicasso in them at all, which is what makes them a fair witness for
  \"React facts die with the fiber\" ([I6](../../../docs/design/hicasso/product/invariants.md)):
  the runtime is not being asked to preserve anything it could have
  preserved."
  (:require ["react" :as react]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

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

  `note!` writes `textContent` on a node the component owns, DIRECTLY.
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
                 "note!"      (fn [text]
                                (when-some [n (.-current node-ref)]
                                  (set! (.-textContent n) text))
                                id)})
          #js [id])
        (react/createElement
          "p" #js {"data-testid" "note" "ref" node-ref} "")))))

;; ---------------------------------------------------------------------------
;; The crossings and the views
;; ---------------------------------------------------------------------------

(h/defhost hook-host HookChild
  "The host crossing the hook child sits inside, so the row is `child hook
  state IN A HOST` and not merely `hook state somewhere on the page`.")

(h/defhost note-host imperative-note
  "The imperative host. Its instance arrives through a callback `:ref` —
  HD-022's v0 spelling, a function and never a vector.")

(h/defview field
  "The focused controlled input. An ordinary `:value` off a subscription
  and an intent vector at `:on-input`; nothing test-only on it."
  [_]
  [:input {:data-testid "field"
           :type        "text"
           :value       (h/sub [:hmr/text])
           :on-input    [:hmr/edit ::h/value]}])

(h/defview digits-field
  "The refusing field the composition row is driven on — the case
  `hmr_registry`'s browser sibling could not reach, and the one where a
  held draft is provably the model's refusal rather than its agreement."
  [_]
  [:input {:data-testid "digits"
           :type        "text"
           :value       (h/sub [:hmr/digits])
           :on-input    [:hmr/edit-digits ::h/value]}])

(h/defview app
  "The head whose re-mint is the whole mechanism. Mounted once per frame,
  so each root reads its own frame and the routing row can compare them.

  `ref-sink` is handed down from the shell rather than closed over here:
  it must survive the reload so the driver keeps reading the same sink
  across a save, and anything defined in THIS namespace is replaced by the
  save under test."
  [{:keys [ref-sink]}]
  [:main {:data-testid "hmr-app"}
   [:span {:data-testid "gen-label"} generation-label]
   [:span {:data-testid "frame-label"} (h/sub [:hmr/label])]
   [field {}]
   [digits-field {}]
   [hook-host {}]
   [note-host {:ref ref-sink}]])
