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
  offending frame-id or nil. The `:rf/default` frame's implicit
  `:url-bound? true` IS counted — the existing URL owner is unchanged."
  [exclude-id]
  (some (fn [[other-id other-meta]]
          (when (and (not= other-id exclude-id)
                     (or (true? (nav-fx/url-bound?-from-config other-meta))
                         (and (= :rf/default other-id)
                              (not (false? (nav-fx/url-bound?-from-config other-meta))))))
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

  Public so the façade can wire it via `registrar/add-registration-hook!`."
  [{:keys [kind id now]}]
  (when (= :frame kind)
    (when (true? (nav-fx/url-bound?-from-config now))
      (when-let [other (frame-id-of-existing-url-binding id)]
        (trace/emit-error! :rf.error/duplicate-url-binding
                           {:existing-frame  other
                            :offending-frame id
                            :reason          "Two frames carry :url-bound? true; only one frame may own the URL at a time."
                            :recovery        :no-recovery})))))
