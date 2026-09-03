(ns re-frame.routing.url-bound
  "`:url-bound?` exclusivity check + claim-order maintenance for
  re-frame2 routing.

  Per Spec 012 §Multi-frame routing — 'Only one frame can own the URL at
  a time': registering a second `:url-bound? true` frame emits
  `:rf.error/duplicate-url-binding` per Spec 009 §error event catalogue.
  The check runs from the frame (re-)registration lifecycle hook
  (`:routing/on-frame-registered!`, fired by the frame engine
  `re-frame.frame/upsert-frame!` on BOTH first-time registration and
  re-registration — rf2-h1vqa4: frames do not flow through
  `registrar/register!`, so there is no registrar registration hook for
  frames; frame config is read from the frames store via
  `frame/frame-meta`).

  The recovery is `:no-recovery` per Spec 009. The frame metadata remains
  inspectable as-written (`rf/frame-meta`), but `url-owner-frame-id` (in
  `re-frame.routing.nav-fx`) enforces one active owner at fx time:
  non-owner `:rf.nav/push-url` / `:rf.nav/replace-url` calls no-op. The
  error surfaces the conflict; resolving it is the app's concern.

  The hook also maintains URL-ownership claim order in
  `re-frame.routing.nav-fx` (recording a `:url-bound? true` claim, dropping it
  when a frame opts out), so `url-owner-frame-id` resolves the FIRST-CLAIMED
  incumbent and a later duplicate cannot steal the browser URL even if its id
  sorts before the incumbent.

  The lifecycle hook observes only registrations issued AFTER routing loads.
  So frames that claimed `:url-bound? true` BEFORE `(require
  're-frame.routing)` never recorded a claim, and the claim order cannot be
  inferred from the frames store (an unordered map). The facade calls
  `reconcile-existing-url-bindings!` after publishing the hook to seed the
  unambiguous incumbent (and fail closed on an unrecoverable multi-binding
  load-order), so first-claim-wins holds regardless of frame-vs-routing load
  order.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `:routing/on-frame-registered!` publication. Late-bind
  publication is key-idempotent, so facade reloads simply re-publish;
  facade loads rerun the idempotent reconciliation."
  (:require [re-frame.frame :as frame]
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.trace :as trace]))

(defn- other-url-bound-frame-id
  "Scan the frames store for any live frame OTHER than `excluded-frame-id`
  that currently carries an explicit `:url-bound? true`. Returns the
  offending frame-id or nil.

  URL ownership is an explicit declaration; there is
  no `:rf/default`-owns-by-default floor. `:rf/default` is counted here only
  when it carries an explicit `:url-bound? true` like any other frame; an
  un-annotated `:rf/default` is NOT an implicit owner."
  [excluded-frame-id]
  (some (fn [other-frame-id]
          (when (and (not= other-frame-id excluded-frame-id)
                     (true? (nav-fx/url-bound?-from-config
                              (frame/frame-meta other-frame-id))))
            other-frame-id))
        (frame/frame-ids)))

(defn check-url-bound-exclusivity!
  "Frame (re-)registration lifecycle body. When the just-(re)registered
  frame's config carries `:url-bound? true` AND another frame already owns
  the URL, emit `:rf.error/duplicate-url-binding`. Per Spec 012 §Multi-frame
  routing and Spec 009 §error event catalogue. The frame's effective config
  is read from the frames store (`frame/frame-meta`) — the engine fires the
  hook AFTER the container + config are seated, so the read is always live.

  Recovery per Spec 009 is `:no-recovery` — the offending frame's config is
  already seated in the frames store, but the navigation fx
  (`:rf.nav/push-url` / `:rf.nav/replace-url`) consults
  `url-owner-frame-id`, so only the single active owner can mutate
  browser history. The app resolves the conflict by removing one of the
  bindings.

  This hook also maintains URL-ownership claim order
  (`re-frame.routing.nav-fx/url-claim-order`): a `:url-bound? true`
  registration records the frame's claim (appended iff not already present, so
  a hot-reload re-registration of the incumbent keeps its position and a later
  duplicate is appended AFTER it — never displacing it); a registration WITHOUT
  `:url-bound? true` drops any prior claim for that frame (it relinquished the
  binding). `url-owner-frame-id` then resolves the FIRST-CLAIMED still-live
  binding — the incumbent — so a later duplicate whose id sorts before the
  incumbent can no longer steal the browser URL (Spec 012 §Multi-frame routing:
  the existing owner is unchanged, the duplicate's history-mutation fxs no-op).

  Public so the façade can compose it into its
  `:routing/on-frame-registered!` hook body (both hosts)."
  [frame-id]
  (if (true? (nav-fx/url-bound?-from-config (frame/frame-meta frame-id)))
    (do
      ;; Record the claim FIRST so the incumbent keeps its claim position; a
      ;; duplicate appends after it and never steals ownership.
      (nav-fx/record-url-claim! frame-id)
      (when-let [other-frame-id (other-url-bound-frame-id frame-id)]
        (trace/emit-error! :rf.error/duplicate-url-binding
                           {:existing-frame  other-frame-id
                            :offending-frame frame-id
                            :reason          "Two frames carry :url-bound? true; only one frame may own the URL at a time."
                            :recovery        :no-recovery})))
    ;; A frame registration that does NOT carry `:url-bound? true`
    ;; relinquished any prior URL claim (e.g. `:rf/default {:url-bound?
    ;; false}` opting out): drop it so a stale claim cannot keep a now-unbound
    ;; frame at the head of the claim order. Idempotent.
    (nav-fx/drop-url-claim! frame-id)))

(defn reconcile-existing-url-bindings!
  "Reconcile `:url-bound? true` frames ALREADY seated when the
  url-bound exclusivity hook is published. Called by the facade after
  publishing `:routing/on-frame-registered!` and on reload, so frames
  registered BEFORE `(require 're-frame.routing)` are not silently invisible
  to URL-ownership resolution.

  The lifecycle hook observes only future registrations; it does not
  replay existing ones. So a frame that claimed `:url-bound? true` before
  routing loaded never recorded a claim in `url-claim-order`, leaving the
  resolver without claim order. Choosing by frame id would violate Spec 012's
  rule that the existing owner remains unchanged.

  Why this can't simply replay in true claim order: the frames store is an
  UNORDERED `{frame-id record}` map (`re-frame.frame/frames`), so the
  registration order of frames seated before the hook existed is NOT
  recoverable. We therefore reconcile conservatively, preserving first-claim-
  wins where order is knowable and FAILING CLOSED where it is not:

  - ZERO pre-existing `:url-bound? true` frames → nothing to seed.
  - EXACTLY ONE → seed it as the incumbent claim. Order is trivially known
    (it is the only claimant), so this is the true first-claim.
  - TWO OR MORE → claim order is genuinely unknowable. We do NOT pick by id
    sort (that is the steal). We seed NO claim and emit one
    `:rf.error/duplicate-url-binding` per extra binding so the ambiguity is
    observable; `url-owner-frame-id` then fails closed (returns nil) for this
    state until the app re-registers/removes a binding through the now-live
    hook, which re-establishes a deterministic claim order.

  Idempotent: a single pre-existing claim is appended-iff-absent
  (`record-url-claim!`), and the multi-binding branch records no claim, so a
  second call (e.g. a façade `:reload`) is a no-op. Public so the façade can
  invoke it and the load-order test can drive it directly."
  []
  (let [bound-ids (->> (frame/frame-ids)
                       (filter (fn [id]
                                 (true? (nav-fx/url-bound?-from-config
                                          (frame/frame-meta id)))))
                       vec)]
    (cond
      (empty? bound-ids)
      nil

      (= 1 (count bound-ids))
      ;; Unambiguous incumbent: order is known (sole claimant). Seed it so the
      ;; resolver returns the true first-claim rather than falling back.
      (nav-fx/record-url-claim! (first bound-ids))

      :else
      ;; Multiple pre-existing url-bound frames, claim order lost to load order.
      ;; Fail closed (seed nothing) and surface the ambiguity once per extra
      ;; binding — the resolver returns nil for this state (no id-sort steal).
      (let [[incumbent & extras] (sort-by str bound-ids)]
        (doseq [offending extras]
          (trace/emit-error! :rf.error/duplicate-url-binding
                             {:existing-frame  incumbent
                              :offending-frame offending
                              :reason          "Multiple :url-bound? true frames were registered before re-frame.routing loaded; claim order is unrecoverable, so no frame owns the URL until one binding is re-registered or removed."
                              :recovery        :no-recovery}))
        nil))))
