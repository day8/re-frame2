(ns re-frame.routing.url-bound
  "`:url-bound?` exclusivity check + registration-hook install for
  re-frame2 routing.

  Per Spec 012 §Multi-frame routing — 'Only one frame can own the URL at
  a time': registering a second `:url-bound? true` frame emits
  `:rf.error/duplicate-url-binding` per Spec 009 §error event catalogue.
  The check runs from a registrar registration-hook (rf2-w50qm) so it
  fires on BOTH first-time and re-registration paths.

  The recovery is `:no-recovery` per Spec 009. The registry remains
  inspectable as-written, but `url-owner-frame-id` (in
  `re-frame.routing.nav-fx`) enforces one active owner at fx time:
  non-owner `:rf.nav/push-url` / `:rf.nav/replace-url` calls no-op. The
  error surfaces the conflict; resolving it is the app's concern.

  rf2-3l7xxz — the hook also maintains the URL-ownership CLAIM ORDER in
  `re-frame.routing.nav-fx` (recording a `:url-bound? true` claim, dropping it
  when a frame opts out), so `url-owner-frame-id` resolves the FIRST-CLAIMED
  incumbent and a later duplicate cannot steal the browser URL even if its id
  sorts before the incumbent.

  rf2-68k8as — `add-registration-hook!` is an append-only FUTURE observer; it
  does not replay existing registrations. So frames that claimed `:url-bound?
  true` BEFORE `(require 're-frame.routing)` never recorded a claim, and the
  resolver's old id-sort fallback let a later, alphabetically-earlier duplicate
  steal the URL from the true first-claimant. The façade now calls
  `reconcile-existing-url-bindings!` right after installing the hook to seed the
  unambiguous incumbent (and fail closed on an unrecoverable multi-binding
  load-order), so first-claim-wins holds regardless of frame-vs-routing load
  order.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `registrar/add-registration-hook!` call so a
  `:reload` re-wires it on a fresh registrar. Per the rf2-2yabr
  cohesion split: URL-BOUND-EXCLUSIVITY seam."
  (:require [re-frame.registrar :as registrar]
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.trace :as trace]))

(defn- frame-id-of-existing-url-binding
  "Scan the registrar's `:frame` map for any frame OTHER than `exclude-id`
  that currently carries an explicit `:url-bound? true`. Returns the
  offending frame-id or nil.

  EP-0002 (rf2-nn0jqa): URL ownership is an EXPLICIT declaration — there is
  no `:rf/default`-owns-by-default floor. `:rf/default` is counted here only
  when it carries an explicit `:url-bound? true` like any other frame; an
  un-annotated `:rf/default` is NOT an implicit owner."
  [exclude-id]
  (some (fn [[other-id other-meta]]
          (when (and (not= other-id exclude-id)
                     (true? (nav-fx/url-bound?-from-config other-meta)))
            other-id))
        (registrar/registrations :frame)))

(defn check-url-bound-exclusivity!
  "Registration-hook fn. When a `:frame` registration carries
  `:url-bound? true` AND another frame already owns the URL, emit
  `:rf.error/duplicate-url-binding`. Per Spec 012 §Multi-frame routing
  and Spec 009 §error event catalogue.

  Recovery per Spec 009 is `:no-recovery` — the offending registration's
  storage has already been written by `registrar/register!`, but the
  navigation fx (`:rf.nav/push-url` / `:rf.nav/replace-url`) consults
  `url-owner-frame-id`, so only the single active owner can mutate
  browser history. The app resolves the conflict by removing one of the
  bindings.

  rf2-3l7xxz — this hook ALSO maintains the URL-ownership CLAIM ORDER
  (`re-frame.routing.nav-fx/url-claim-order`): a `:url-bound? true`
  registration records the frame's claim (appended iff not already present, so
  a hot-reload re-registration of the incumbent keeps its position and a later
  duplicate is appended AFTER it — never displacing it); a registration WITHOUT
  `:url-bound? true` drops any prior claim for that frame (it relinquished the
  binding). `url-owner-frame-id` then resolves the FIRST-CLAIMED still-live
  binding — the incumbent — so a later duplicate whose id sorts before the
  incumbent can no longer steal the browser URL (Spec 012 §Multi-frame routing:
  the existing owner is unchanged, the duplicate's history-mutation fxs no-op).

  Public so the façade can wire it via `registrar/add-registration-hook!`."
  [{:keys [kind id now]}]
  (when (= :frame kind)
    (if (true? (nav-fx/url-bound?-from-config now))
      (do
        ;; Record the claim FIRST so the incumbent keeps its claim position; a
        ;; duplicate appends after it and never steals ownership (rf2-3l7xxz).
        (nav-fx/record-url-claim! id)
        (when-let [other (frame-id-of-existing-url-binding id)]
          (trace/emit-error! :rf.error/duplicate-url-binding
                             {:existing-frame  other
                              :offending-frame id
                              :reason          "Two frames carry :url-bound? true; only one frame may own the URL at a time."
                              :recovery        :no-recovery})))
      ;; A `:frame` registration that does NOT carry `:url-bound? true`
      ;; relinquished any prior URL claim (e.g. `:rf/default {:url-bound?
      ;; false}` opting out): drop it so a stale claim cannot keep a now-unbound
      ;; frame at the head of the claim order (rf2-3l7xxz). Idempotent.
      (nav-fx/drop-url-claim! id))))

(defn reconcile-existing-url-bindings!
  "Reconcile `:url-bound? true` frames ALREADY in the registry when the
  url-bound exclusivity hook is installed (rf2-68k8as). Called ONCE by the
  façade immediately after `add-registration-hook!`, so frames registered
  BEFORE `(require 're-frame.routing)` are not silently invisible to URL-
  ownership resolution.

  The hazard (rf2-68k8as): `add-registration-hook!` is an append-only FUTURE
  observer — it does NOT replay existing registrations (re-frame.registrar
  §registration-hooks). So a frame that claimed `:url-bound? true` before
  routing loaded never recorded a claim in `url-claim-order`, leaving the
  resolver to fall back to id-sorting — which let a later, alphabetically-
  earlier duplicate STEAL the URL from the true first-claimant (Spec 012
  §Multi-frame routing forbids exactly this: 'the existing owner is unchanged …
  resolving by id ordering would have let it').

  Why this can't simply replay in true claim order: the registrar's
  `(kind, id) → metadata` table is UNORDERED (re-frame.registrar), so the
  registration order of frames registered before the hook existed is NOT
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
  (let [bound-ids (->> (registrar/registrations :frame)
                       (keep (fn [[id meta]]
                               (when (true? (nav-fx/url-bound?-from-config meta))
                                 id)))
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
