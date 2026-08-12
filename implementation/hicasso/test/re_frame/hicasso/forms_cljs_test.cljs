(ns re-frame.hicasso.forms-cljs-test
  "THE FORMS MODULE — ITS PROTOCOL, ITS MARKUP AND ITS PRICE (rf2-sh56).

  Five sections in one file, because the module is one view and splitting
  a dozen rows across three namespaces would cost more to read than it
  buys:

  | § | tier | what it decides |
  |---|---|---|
  | 1 | pure | the concern's spelling, which two gates pin, and its `app-db` layout |
  | 2 | L0 | every transition, driven through a real frame |
  | 3 | L0 | the five trap classes, and the near-miss each must NOT catch |
  | 4 | L2 | the body as a semantic tree — props, ownership, and the absence of a function |
  | 5 | hooks | the count, taken at React's own dispatcher |

  The mounted half is `re-frame.hicasso.forms-dom-cljs-test`: node
  identity across a commit, real blur ordering, focus, and whether
  `::h/revision` is doing any work on a real box.

  ## The eligibility rule is asserted in BOTH directions

  Every claim about eligibility has a near-miss beside it, because a
  fence wrong by being too EAGER is the direction nothing else here
  would notice. A revision that moved must discard the draft; a `:value`
  that moved under an unchanged revision must NOT. Delete the revision
  comparison and
  [[an-external-reset-discards-the-draft-immediately-and-dispatches-nothing]]
  reds; widen it to consult the value as well and
  [[a-value-change-under-an-equal-revision-continues-the-draft]] reds.
  Neither row is green because the other is.

  ## What the field SHOWS is read off its own body

  The rows below never recompute eligibility; they ask
  [[shown]], which renders the real body through `ht/tree` and reads the
  `:value` the input would carry. A helper that reimplemented the rule
  would be green against a module that had stopped using it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            ;; Side-effecting: the `:dispatch` fx a commit hands the
            ;; caller's event to. The module names it too; a suite that
            ;; relied on some other namespace having loaded it would be
            ;; asserting about the bundle rather than about the module.
            [re-frame.fx]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.forms :as forms]
            [re-frame.hicasso.hook-probe :as probe]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]
            ["react-dom/server" :as react-dom-server]))

;; ---------------------------------------------------------------------------
;; The application these rows drive
;;
;; Registered BEFORE `use-fixtures`, and that ordering is load-bearing:
;; the reset fixture captures its registrar baseline when it is
;; constructed, so a `reg-event` written after it is wiped by the first
;; reset and every row runs against a frame whose seed never landed.
;; `hook_budget_cljs_test` puts its two registrations above the fixture
;; for the same reason.
;; ---------------------------------------------------------------------------

(def ^:private control
  "The chapter's own address, and deliberately one that is ALSO a real
  domain path: `[:todo 7 :title]` is where this todo's committed title
  lives. A module that wrote the draft AT the control would clobber the
  value it exists to buffer, which is why the control is a KEY and not a
  path — see [[the-draft-does-not-live-at-the-control-address]]."
  [:todo 7 :title])

(def ^:private committed "Buy milk")

(rf/reg-event ::seed
  {:doc "The starting `app-db`: one todo, its title committed, revision 0."}
  (fn [_ _] {:db {:todo      {7 {:title committed :title-revision 0}}
                  :committed []
                  :cancelled []}}))

(rf/reg-event ::title-committed
  {:doc "The caller's `:on-commit`, in the chapter's own shape — accept a
         trimmed candidate, refuse a blank one, and advance the revision
         either way. It also RECORDS what it was handed, so a row can
         assert on the candidate rather than only on its effect."}
  (fn [{:keys [db]} [_ id candidate]]
    (let [title (str/trim candidate)
          db    (update db :committed conj [id candidate])]
      (if (str/blank? title)
        {:db (update-in db [:todo id :title-revision] inc)}
        {:db (-> db
                 (assoc-in [:todo id :title] title)
                 (update-in [:todo id :title-revision] inc))}))))

(rf/reg-event ::title-cancelled
  {:doc "The optional `:on-cancel`, so a row can prove the module fires it
         once, and only for a session that really ended."}
  (fn [{:keys [db]} [_ id]] {:db (update db :cancelled conj id)}))

(rf/reg-event ::settle
  {:doc "An external reset: a later settle writes the accepted value AND
         advances the revision together, which is the async fence the
         chapter names."}
  (fn [{:keys [db]} [_ id title]]
    {:db (-> db
             (assoc-in [:todo id :title] title)
             (update-in [:todo id :title-revision] inc))}))

(rf/reg-event ::retitle-only
  {:doc "A `:value` change with the revision STANDING — the near-miss the
         eligibility rule must not catch."}
  (fn [{:keys [db]} [_ id title]] {:db (assoc-in db [:todo id :title] title)}))

;; The comparison arms §5 needs, and nothing else reads them.

(rf/reg-sub ::title (fn [db _] (get-in db [:todo 7 :title])))

(rf/reg-event ::typed
  (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:todo 7 :title] v)}))

(h/defview hand-written-field
  "A controlled field written BY HAND — the arrangement `buffered-field`
  replaces, minus the buffering. One boundary, one read, one controlled
  element."
  [_]
  [:input {:type "text" :value (h/sub [::title]) :on-input [::typed ::h/value]}])

(h/defview uncontrolled-field
  "The same boundary and the same read over an UNCONTROLLED element, so
  §5's third hook can be shown to belong to the controlled element rather
  than to the shell."
  [_]
  [:input {:type "text" :placeholder (h/sub [::title])}])

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Driving one, and reading what it would show
;; ---------------------------------------------------------------------------

(defn- with-app [f]
  (rf/with-new-frame [frame (rf/make-frame {:initial-events [[::seed]]})]
    (f frame)))

(defn- db-of [frame] (rf/app-db-value frame))
(defn- send! [frame event-v] (rf/dispatch-sync event-v {:frame frame}))
(defn- draft-at [frame ctl] (rf/subscribe-once [forms/drafts ctl] {:frame frame}))
(defn- record-of [frame] (draft-at frame control))
(defn- title-of [frame] (get-in (db-of frame) [:todo 7 :title]))
(defn- revision-of [frame] (get-in (db-of frame) [:todo 7 :title-revision]))
(defn- committed-log [frame] (:committed (db-of frame)))
(defn- cancelled-log [frame] (vec (:cancelled (db-of frame))))

(defn- type! [frame revision text]
  (send! frame [forms/edit-id control revision text]))

(defn- commit! [frame revision]
  (send! frame [forms/commit-id control revision [::title-committed 7]]))

(defn- cancel!
  ([frame revision] (cancel! frame revision nil))
  ([frame revision on-cancel]
   (send! frame [forms/cancel-id control revision on-cancel])))

(defn- field-tree
  "One `buffered-field` body, under its EXACT read set. `ht/tree` refuses
  a read no fixture answers, so this map is the body's read set written
  out — and a body that grew a second read would red this file rather
  than quietly cost a re-render per keystroke."
  [props record]
  (ht/tree [forms/buffered-field (merge {:control   control
                                         :value     committed
                                         :on-commit [::title-committed 7]}
                                        props)]
           {:subs {[forms/drafts control] record}}))

(defn- input-attrs [tree] (ht/attrs (ht/find tree #(= :input (:tag %)))))

(defn- shown
  "What the field would DISPLAY for `record` under `revision`, against a
  committed `value` — rendered through the real body rather than
  recomputed, so a module that stopped consulting the revision cannot
  leave this helper agreeing with it."
  ([record revision] (shown record revision committed))
  ([record revision value]
   (:value (input-attrs (field-tree {::h/revision revision :value value} record)))))

(defn- shown-now
  "What the field shows against this frame's own record, revision and
  committed title — the three props a parent boundary would hand it."
  [frame]
  (shown (record-of frame) (revision-of frame) (title-of frame)))

(defn- refusal
  "The framework ex-data a refused READ produced.

  Read off the error CHANNEL rather than off a throw. A sub body's throw
  is caught by the runtime, fanned, and recovered to `nil`, so a row
  spelled `(is (thrown? …))` would be RED over a working refusal and one
  spelled `(is (nil? …))` GREEN over a deleted one. `state_cljs_test`
  names that trap at length; this is its narrow form."
  [thunk]
  (let [records (volatile! [])]
    (error-emit/register-error-listener! ::forms (fn [r] (vswap! records conj r)))
    (try
      (try (thunk) (catch :default _ nil))
      (finally (error-emit/unregister-error-listener! ::forms)))
    (->> (concat (map (comp ex-data :exception) @records)
                 (map (comp ex-data ex-cause :exception) @records))
         (filter #(some-> % :rf.error/id namespace (= "rf.error")))
         first)))

;; ---------------------------------------------------------------------------
;; 1. The concern — the string two gates pin, and the layout the chapter promises
;; ---------------------------------------------------------------------------

(deftest the-concern-is-what-the-bundle-gate-pins
  ;; `hicasso/scripts/check_bundle_isolation.cjs` scans a release bundle
  ;; for the literal `re-frame.hicasso.forms/drafts`, and pins that
  ;; spelling against the fully written keyword in `forms.cljs`. This row
  ;; is the other end of the pin: the written spelling really is this
  ;; namespace's own `::drafts`, so a namespace rename cannot leave the
  ;; gate scanning for a string nobody emits any more.
  (is (= ::forms/drafts forms/drafts))
  (is (= "re-frame.hicasso.forms/drafts" (str (symbol forms/drafts)))))

(deftest the-draft-does-not-live-at-the-control-address
  (with-app
    (fn [frame]
      (type! frame 0 "Buy oat milk")
      (testing "the record is under the MODULE's key, addressed by the control"
        (is (= {:revision 0 :draft "Buy oat milk"}
               (get-in (db-of frame) [:ui ::forms/drafts control]))
            "the chapter's `app-db` promise, literally: the draft appears
             in snapshots, headless tests and Xray, at an address a reader
             can predict from the props it wrote"))
      (testing "and the committed title — which the control NAMES — has not moved"
        (is (= committed (title-of frame))
            "a module that wrote the draft at `[:todo 7 :title]` would have
             overwritten the value buffering exists to protect")))))

;; ---------------------------------------------------------------------------
;; 2. The protocol
;; ---------------------------------------------------------------------------

(deftest no-session-until-the-first-edit
  (with-app
    (fn [frame]
      (is (nil? (record-of frame))
          "focus creates nothing. There is no state to mint before there
           is something to remember")
      (is (= committed (shown-now frame))
          "and the field is populated before anybody types, so the first
           keystroke lands on top of the committed text rather than into
           an empty box — there is no load-the-form step to forget"))))

(deftest an-edit-opens-the-session-and-moves-nothing-else
  (with-app
    (fn [frame]
      (type! frame 0 "Buy oat")
      (is (= {:revision 0 :draft "Buy oat"} (record-of frame)))
      (is (= "Buy oat" (shown-now frame)))
      (is (= committed (title-of frame))
          "the committed title has not moved — that is what BUFFERED means")
      (is (= 0 (revision-of frame))
          "and typing is not a reset"))))

(deftest a-commit-hands-the-caller-the-candidate-and-ends-the-session
  (with-app
    (fn [frame]
      (type! frame 0 "  Buy oat milk  ")
      (commit! frame 0)
      (is (= [[7 "  Buy oat milk  "]] (committed-log frame))
          "the RAW candidate, APPENDED to the caller's own event vector.
           Trimming is the handler's decision and the module takes none")
      (is (= "Buy oat milk" (title-of frame)))
      (is (nil? (record-of frame)) "and the session is over"))))

(deftest a-refused-commit-still-ends-the-session
  (with-app
    (fn [frame]
      (type! frame 0 "   ")
      (commit! frame 0)
      (is (= committed (title-of frame)) "the caller refused the blank draft")
      (is (= 1 (revision-of frame))
          "and advanced the revision, which is the only way a same-value
           refusal becomes observable at all")
      (is (nil? (record-of frame))
          "the session ended on the module's side whatever the caller
           decided — two different facts, and this one is the module's")
      (is (= committed (shown-now frame))
          "so the blank text the caller refused is not left sitting in the
           box looking accepted"))))

(deftest a-second-commit-does-nothing
  (with-app
    (fn [frame]
      (type! frame 0 "Buy oat milk")
      (commit! frame 0)
      (commit! frame 0)
      (is (= 1 (count (committed-log frame)))
          "Enter then blur, or double Enter: the second finds no record.
           Idempotence by the MODEL, so no handler has to know what order
           it ran in"))))

(deftest escape-clears-the-draft-and-the-late-blur-commits-nothing
  (with-app
    (fn [frame]
      (type! frame 0 "Buy oat milk")
      (cancel! frame 0)
      (is (nil? (record-of frame)))
      (is (= committed (shown-now frame)) "Escape shows `:value` again")
      (commit! frame 0)
      (is (= [] (committed-log frame))
          "*cancel beats the late blur*, answered by the model rather than
           by ordering: the blur already queued behind the Escape finds no
           session to commit")
      (is (= committed (title-of frame))))))

(deftest cancel-reports-only-a-session-that-really-ended
  (with-app
    (fn [frame]
      (cancel! frame 0 [::title-cancelled 7])
      (is (= [] (cancelled-log frame))
          "cancelling nothing has no domain meaning, so it reports nothing")
      (type! frame 0 "Buy oat milk")
      (cancel! frame 0 [::title-cancelled 7])
      (is (= [7] (cancelled-log frame)))
      (cancel! frame 0 [::title-cancelled 7])
      (is (= [7] (cancelled-log frame))
          "and once, not once per Escape"))))

(deftest an-external-reset-discards-the-draft-immediately-and-dispatches-nothing
  ;; D016: *external reset — no render-time dispatch; a changed reset key
  ;; makes the old draft ineligible immediately.* Delete the revision
  ;; comparison and this row reds on its second assertion, with the stale
  ;; draft still in the box.
  (with-app
    (fn [frame]
      (type! frame 0 "half typed")
      (send! frame [::settle 7 "Buy almond milk"])
      (is (= 1 (revision-of frame)))
      (is (= "Buy almond milk" (shown-now frame))
          "the field shows the caller's value from the very next render")
      (is (= {:revision 0 :draft "half typed"} (record-of frame))
          "and the record is still THERE — nothing was dispatched at render
           time, and nothing had to be. A stale record is INERT, not a
           thing to sweep")
      (testing "the blur that arrives afterwards commits nothing"
        (commit! frame 1)
        (is (= [] (committed-log frame))
            "a commit carrying the CURRENT revision meets a record carrying
             the old one — the stale-write fence in its exact shape")
        (is (= "Buy almond milk" (title-of frame)))))))

(deftest the-fence-is-the-render-and-that-limit-is-stated
  ;; THE HONEST LIMIT, pinned rather than left to be discovered.
  ;;
  ;; The row above shows a blur AFTER the reset's render being refused,
  ;; which is the case a page produces: the reset moves a prop, React
  ;; re-renders and re-commits the field, and the handler a later blur
  ;; reaches carries the NEW revision. This row is the other one — an
  ;; intent captured BEFORE that render and delivered after it, which
  ;; only a synthetic dispatch can build — and it commits.
  ;;
  ;; That is the maximum the model can know. The module holds one fact
  ;; about the reset, the revision the record was written under, and the
  ;; caller's current revision is a PROP: it reaches the module only
  ;; through a render, and D016 forbids a render-time dispatch that could
  ;; carry it any other way. The chapter's sentence — *any commit still
  ;; associated with the old revision becomes a no-op* — is therefore
  ;; satisfied by the render and not by the handler, and the residual case
  ;; is the caller's own supersession policy to hold.
  (with-app
    (fn [frame]
      (type! frame 0 "half typed")
      (send! frame [::settle 7 "Buy almond milk"])
      (commit! frame 0)
      (is (= [[7 "half typed"]] (committed-log frame))
          "a commit intent minted before the reset rendered still matches
           the record it was minted beside. Fence it harder and this row
           reds — deliberately, because the change would need somewhere
           for the current revision to come from")
      (is (= "half typed" (title-of frame))))))

(deftest a-value-change-under-an-equal-revision-continues-the-draft
  ;; THE NEAR-MISS. A rule wrong by being too eager — one that consulted
  ;; the value as well as the revision, or discarded on any `app-db` move
  ;; — would delete a user's half-typed edit because something unrelated
  ;; refreshed the title. This is the row that catches it, and nothing
  ;; else in this file would.
  (with-app
    (fn [frame]
      (type! frame 0 "half typed")
      (send! frame [::retitle-only 7 "Buy soy milk"])
      (is (= 0 (revision-of frame)) "the caller did not intend a reset")
      (is (= "half typed" (shown-now frame))
          "so the draft continues. If the caller MEANT to replace the edit
           it must move the revision — the distinction is explicit
           precisely so the component never guesses")
      (commit! frame 0)
      (is (= [[7 "half typed"]] (committed-log frame))
          "and the edit commits, unharmed"))))

(deftest the-next-edit-replaces-a-stale-record-whole
  (with-app
    (fn [frame]
      (type! frame 0 "half typed")
      (send! frame [::settle 7 "Buy almond milk"])
      (type! frame 1 "Buy almond milk now")
      (is (= {:revision 1 :draft "Buy almond milk now"} (record-of frame))
          "atomically replaced, not merged — nothing read the old record to
           get here, which is why a stale one can be left lying around
           without a sweeper")
      (commit! frame 1)
      (is (= "Buy almond milk now" (title-of frame))))))

(deftest a-commit-with-no-on-commit-still-ends-the-session
  (with-app
    (fn [frame]
      (type! frame 0 "Buy oat milk")
      (send! frame [forms/commit-id control 0 nil])
      (is (nil? (record-of frame)))
      (is (= [] (committed-log frame))
          "`:on-commit` is required by the chapter, and this module cannot
           refuse a missing one without minting a diagnostic id — see the
           namespace docstring. What it does NOT do is dispatch a vector
           whose head is the text the user typed"))))

(deftest an-application-ends-a-durable-draft-with-the-framework-clear
  ;; The chapter's *Draft lifetime*: a draft deliberately survives
  ;; re-render, remount, virtualization and navigation, so every one needs
  ;; a causal owner and an end event — route entry, explicit cancel, or
  ;; the successful save reply. This is the door those owners use, and it
  ;; is `reg-state`'s, shared with every other concern on the page.
  (with-app
    (fn [frame]
      (type! frame 0 "abandoned on navigation")
      (send! frame [:re-frame.hicasso/clear forms/drafts control])
      (is (nil? (record-of frame)))
      (is (= {} (:ui (db-of frame)))
          "and the concern map is pruned rather than left holding an empty
           entry — one representation of unset, not two"))))

;; ---------------------------------------------------------------------------
;; 3. The five trap classes
;; ---------------------------------------------------------------------------
;;
;; `rf2-hic-051`'s recipes carried these five and the module inherits
;; every one, because it IS those recipes with the protocol moved behind
;; a view. Three are decided here, one in §4, and two need a screen and
;; are the mounted suite's. `forms-recipes.md` is where the table was
;; first written down.

(deftest trap-re-minted-ephemeral-state-two-fields-two-drafts
  ;; TRAP 5, the addressing half. A draft held in a render closure dies on
  ;; re-render; a draft at a FIXED path is one draft every instance
  ;; shares, and two rows open together with nothing on screen to say so.
  (with-app
    (fn [frame]
      (send! frame [forms/edit-id [:todo 7 :title] 0 "seven"])
      (send! frame [forms/edit-id [:todo 8 :title] 0 "eight"])
      (is (= "seven" (:draft (draft-at frame [:todo 7 :title]))))
      (is (= "eight" (:draft (draft-at frame [:todo 8 :title]))))
      (testing "and one field's commit does not end the other's session"
        (send! frame [forms/commit-id [:todo 7 :title] 0 [::title-committed 7]])
        (is (nil? (draft-at frame [:todo 7 :title])))
        (is (some? (draft-at frame [:todo 8 :title])))))))

(deftest trap-re-minted-ephemeral-state-a-bad-address-is-refused-by-name
  ;; The other half of the addressing trap, and the reason this module
  ;; needs no diagnostic id of its own: a `nil` control is every instance
  ;; sharing one draft, and `reg-state` already refuses it BY NAME at the
  ;; READ — which is the field's first render, before anything is written.
  (with-app
    (fn [frame]
      (let [d (refusal #(draft-at frame nil))]
        (is (some? d) "a nil control refuses rather than sharing a draft")
        (is (= :rf.error/hicasso-state-bad-key (:rf.error/id d))
            "by an id that is already shipped and already catalogued")
        (is (= ::forms/drafts (:concern d))
            "and it names THIS module's concern, so the message points at
             the field the author wrote")))))

(deftest trap-same-value-blindness-the-model-half
  ;; TRAP 2. The user drafts something the caller refuses; the caller
  ;; keeps the value it had. Equality cannot carry that decision, so the
  ;; revision does — and here is the model saying so.
  (with-app
    (fn [frame]
      (type! frame 0 committed)
      (commit! frame 0)
      (is (= committed (title-of frame)) "the value did not move")
      (is (= 1 (revision-of frame))
          "and the revision did, which is the only thing that can tell a
           field its baseline is a NEW decision rather than the old one"))))

(deftest trap-commit-flicker-a-superseded-settle-changes-nothing
  ;; TRAP 3. Two writes, the older settling last. The chapter's fence is
  ;; *settle value and revision together*, and what it buys is that the
  ;; late one cannot reopen a session the newer one closed.
  (with-app
    (fn [frame]
      (type! frame 0 "first")
      (commit! frame 0)
      (type! frame 1 "second")
      (send! frame [::settle 7 "first (server)"])
      (commit! frame 2)
      (is (= [[7 "first"]] (committed-log frame))
          "the settle moved the revision to 2, so the re-rendered field's
           blur carries 2 and meets a record written at 1 — superseded,
           and it never reaches the caller")
      (is (= "first (server)" (title-of frame))
          "and the newer value stands rather than flickering back"))))

;; ---------------------------------------------------------------------------
;; 4. L2 — the body as a semantic tree
;; ---------------------------------------------------------------------------

(deftest the-field-renders-value-revision-and-three-intents
  (let [attrs (input-attrs (field-tree {::h/revision 4} nil))]
    (is (= committed (:value attrs)) "no record, so the committed value")
    (is (= 4 (::h/revision attrs))
        "the caller's revision reaches the element — this module adds no
         reset vocabulary of its own and forwards the controlled law's")
    (is (= [forms/edit-id control 4 ::h/value] (:on-input attrs))
        "the keystroke carries the address and the generation it was typed
         under — POSITIONAL, because a marker inside a payload map is
         substituted nowhere and would arrive as the keyword itself")
    (is (= [forms/commit-id control 4 [::title-committed 7]] (:on-blur attrs)))
    (is (= {"Enter"  [forms/commit-id control 4 [::title-committed 7]]
            "Escape" [forms/cancel-id control 4 nil]}
           (:on-key-down attrs))
        "the key MAP, not a callback reading `.key` — which is what keeps
         the substrate's own composition gate, and why this file finds no
         function anywhere")))

(deftest the-field-shows-the-draft-only-while-its-revision-matches
  (is (= "half typed" (shown {:revision 4 :draft "half typed"} 4))
      "a matching record renders its draft")
  (is (= committed (shown {:revision 4 :draft "half typed"} 5))
      "a superseded one renders `:value`, with no dispatch at render time
       and no comparison state of its own to keep in step")
  (is (= committed (shown nil 4))
      "and absence is the third case, which is the same case"))

(deftest the-field-is-controlled-and-carries-the-reset-trigger
  ;; `ht/controlled?` and `ht/revision` ask the RUNTIME which component
  ;; the codec installs and what it read pre-merge, so this is the
  ;; substrate's own answer rather than a re-reading of what was written.
  (let [form [:input {:type "text" :value committed ::h/revision 4
                      :on-input [forms/edit-id control 4 ::h/value]}]]
    (is (true? (ht/controlled? form)))
    (is (= 4 (ht/revision form)))))

(deftest other-props-pass-through-and-the-owned-slots-do-not
  (let [attrs (input-attrs (field-tree {::h/revision 0
                                        :on-cancel   [::title-cancelled 7]
                                        :placeholder "What needs doing?"
                                        :class       "title"
                                        :name        "title"
                                        :data-testid "todo-title"
                                        :aria-label  "Title"}
                                       nil))]
    (testing "through to the input, because a field nobody can label or
              style is not a field"
      (is (= "What needs doing?" (:placeholder attrs)))
      (is (= "title" (:class attrs)))
      (is (= "title" (:name attrs)))
      (is (= "todo-title" (:data-testid attrs)))
      (is (= "Title" (:aria-label attrs))))
    (testing "`:type` defaults to text and is not forced"
      (is (= "text" (:type attrs)))
      (is (= "email" (:type (input-attrs (field-tree {:type "email"} nil))))))
    (testing "and the module's own configuration does NOT reach the DOM"
      (is (= [] (filterv (partial contains? attrs)
                         [:control :on-commit :on-cancel :key]))
          "`:control` on a DOM node is a React warning at best and a
           serialized vector in the markup at worst"))
    (testing "`:on-cancel` reached the Escape branch instead"
      (is (= [forms/cancel-id control 0 [::title-cancelled 7]]
             (get (:on-key-down attrs) "Escape"))))))

(deftest the-owned-slots-win-a-collision
  ;; Owned-last merge — the guide's own recipe, with no symbol minted for
  ;; it. A caller who writes `:value` has misunderstood the field rather
  ;; than configured it, and the field's answer is to keep owning the slot
  ;; rather than to render a value nothing can update.
  (let [attrs (input-attrs (field-tree {::h/revision 0
                                        :value       "written by the caller"
                                        :on-input    [:app/typed]
                                        :on-blur     [:app/blurred]
                                        :on-key-down {"Enter" [:app/entered]}}
                                       {:revision 0 :draft "half typed"}))]
    (is (= "half typed" (:value attrs)))
    (is (= [forms/edit-id control 0 ::h/value] (:on-input attrs)))
    (is (= [forms/commit-id control 0 [::title-committed 7]] (:on-blur attrs)))
    (is (= #{"Enter" "Escape"} (set (keys (:on-key-down attrs)))))))

(deftest trap-arity-sniffed-done-fn-every-handler-site-is-data
  ;; TRAP 4, and TRAP 1's structural half with it. Spec 004B records a
  ;; function-valued handler as `{:rf.ui/opaque :fn}`, so a completion
  ;; callback smuggled in as a prop would show up here by tag and name.
  ;; And the twin-atom stack cannot exist in a body that runs at all:
  ;; `ht/tree` installs no React dispatcher, so a body holding local state
  ;; would not have run — a statement by the instrument rather than by the
  ;; author.
  (let [trees  [(field-tree {::h/revision 0} nil)
                (field-tree {::h/revision 0 :on-cancel [::title-cancelled 7]}
                            {:revision 0 :draft "half typed"})]
        opaque (for [tree  trees
                     node  (ht/find-all tree map?)
                     [k v] (ht/attrs node)
                     :when (= {:rf.ui/opaque :fn} v)]
                 [(:tag node) k])]
    (is (= [] (vec opaque))
        "not one function-valued prop. Give any slot a `(h/hfn …)` and this
         row names the tag and the prop it appeared on")
    (testing "and every intent the field offers is a vector of data"
      (is (= 4 (count (ht/intents (first trees))))
          "input, blur, Enter, Escape — four sites, four vectors, and the
           Escape one carries a `nil` `:on-cancel` rather than a closure"))))

;; ---------------------------------------------------------------------------
;; 5. The price, counted at React's own dispatcher
;; ---------------------------------------------------------------------------
;;
;; I9 freezes a boundary's hook budget at two, and `hook_budget_cljs_test`
;; witnesses that for the shell. The question HERE is narrower and is the
;; one this module could have got wrong: does `buffered-field` add a
;; third? A server render runs the body for real — the shell's hooks, the
;; read collection, the codec's emission — through React's own dispatcher,
;; and it runs in the node lane, which is in the fast-PR spine.
;;
;; The reading is taken as a THREE-ARM comparison, because the number on
;; its own does not answer the question. A controlled field spends a
;; shadow `useState` of its own — the codec installs a component for it,
;; and that is true of every controlled field on any page — so the field
;; measures THREE and the interesting fact is whose the third is. The
;; uncontrolled arm shows the shell alone at two; the hand-written
;; controlled arm shows the same three the module's field costs. What
;; `buffered-field` adds is the difference between those two arms, and it
;; is nothing.

(def ^:private frame-id ::forms-hooks)

(defn- server-render! [hiccup]
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [::seed]))
  (is (true? (probe/install!))
      "React's client-internals dispatcher slot was not found — the hook
       budget is UNWITNESSED, not satisfied")
  (let [!html (volatile! nil)
        hooks (probe/record!
                (fn []
                  (vreset! !html
                           (react-dom-server/renderToString
                             (mount/provider frame-id
                                             (codec/root-element frame-id hiccup))))))]
    {:html @!html :hooks hooks}))

(deftest the-third-hook-belongs-to-the-controlled-element-not-to-the-shell
  ;; The two control arms, so the module's reading below is a comparison
  ;; rather than a number. Neither of these has anything to do with forms.
  (let [bare       (server-render! [uncontrolled-field {}])
        controlled (server-render! [hand-written-field {}])]
    (testing "the premise: both bodies really rendered an input"
      (is (some? (re-find #"<input" (:html bare))))
      (is (some? (re-find #"<input" (:html controlled)))))
    (testing "an uncontrolled element costs the shell's two and nothing"
      (is (= ["useContext" "useSyncExternalStore"] (:hooks bare)))
      (is (= (count collector/shell-hook-ledger) (count (:hooks bare)))))
    (testing "a HAND-WRITTEN controlled field costs a third — the shadow
              `useState` the codec's controlled component holds, which
              every controlled field on any page pays and which I9 does
              not charge to the shell"
      (is (= ["useContext" "useSyncExternalStore" "useState"] (:hooks controlled))))))

(deftest the-field-costs-what-a-hand-written-controlled-field-costs
  (let [{:keys [html hooks]} (server-render!
                               [forms/buffered-field
                                {:control     control
                                 :value       committed
                                 ::h/revision 0
                                 :on-commit   [::title-committed 7]}])]
    (testing "the premise: React really rendered the field"
      (is (some? (re-find #"<input" html))))
    (testing "the shell's two and the controlled element's one — the SAME
              three the hand-written arm above costs. `buffered-field`
              holds no draft of its own, no comparison record and no
              effect: everything it knows is in `app-db` and reaches it
              through the same subscription hook every other boundary uses"
      (is (= (:hooks (server-render! [hand-written-field {}])) hooks))
      (is (= ["useContext" "useSyncExternalStore" "useState"] hooks)))
    (testing "so the module's own contribution is zero, stated as the
              difference the two arms leave"
      (is (= 0 (- (count hooks)
                  (count (:hooks (server-render! [hand-written-field {}])))))))
    (testing "and the shell's ledger did not move — no `useRef`, and the
              one `useState` is the element's rather than a boundary's"
      (is (= 2 (count collector/shell-hook-ledger)))
      (is (= [] (filterv #{"useRef"} hooks)))
      (is (= 1 (count (filterv #{"useState"} hooks)))))))
