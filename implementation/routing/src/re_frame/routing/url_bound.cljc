(ns re-frame.routing.url-bound
  "The one-URL-owner rule for re-frame2 routing.

  Per Spec 012 §Multi-frame routing, at most one live frame carries
  `:url-bound? true`. The routing-owned registration preflight
  (routing's `:preflight-config` lifecycle member, which `re-frame.frame/upsert-frame!`
  calls with the final config BEFORE any write) refuses a second one with
  `:rf.error/duplicate-url-binding`, so the frames store never holds two
  bindings and `re-frame.routing.nav-fx/url-owner-frame-id` reads the owner
  straight off it. Re-registering the owner is not a second binding.

  Internal namespace; the public facade is `re-frame.routing`, which composes
  this check into its preflight hook."
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.routing.nav-fx :as rf.routing.nav-fx]))

(defn- other-url-bound-frame-id
  "The live frame OTHER than `frame-id` that carries `:url-bound? true`, or
  nil. URL ownership is an explicit declaration: an un-annotated
  `:rf/default` owns nothing."
  [frame-id]
  (some (fn [other-frame-id]
          (when (and (not= other-frame-id frame-id)
                     (true? (rf.routing.nav-fx/url-bound?-from-config
                              (rf.frame/frame-meta other-frame-id))))
            other-frame-id))
        (rf.frame/frame-ids)))

(defn preflight-url-binding!
  "Registration-time PREFLIGHT: throw `:rf.error/duplicate-url-binding` when
  `config` carries `:url-bound? true` while another live frame owns the URL.
  It reads the frames store and writes nothing, so a refused first
  registration leaves no residue and a refused re-registration leaves the
  frame as it was. Returns nil."
  [frame-id config]
  (when (true? (:url-bound? config))
    (when-let [owner (other-url-bound-frame-id frame-id)]
      (rf.error/throw-error!
        :rf.error/duplicate-url-binding 'rf/make-frame
        (str "frame " (pr-str frame-id) " declares :url-bound? true, but "
             (pr-str owner) " already owns the URL and only one frame may; "
             "destroy that frame, or re-register it with :url-bound? false, first.")
        {:extra {:existing-frame owner :offending-frame frame-id}})))
  nil)
