(ns day8.re-frame2-xray.host-registrar
  "The host app's registrar, read the way Xray's panels need it.

  The panels read registrations through the source store
  (`{:source :store …}`), because their sub computations run under Xray's own
  image-loaded frame and a frame-scoped read would resolve through Xray's
  image instead of the host's. A store read of an id that more than one
  namespace registers throws `:rf.error/ambiguous-registration`, so a panel
  asks [[ambiguous-namespaces]] first and reads with [[store-meta]], which
  answers nil for such an id, as for one nothing registers. See spec/API.md
  §Public registrar query API."
  (:require [re-frame.core :as rf]))

(defn ambiguous-namespaces
  "The namespaces that register `id` of `kind`, sorted, when more than one
  does, so no frame made without `:images` can run it; nil otherwise."
  [kind id]
  (some->> (get (rf/ambiguous-registrations {:source :store :kind kind}) id)
           keys
           (map str)
           sort
           vec))

(defn store-meta
  "The host's registration metadata for `id` of `kind` —
  `(rf/handler-meta {:source :store :kind kind :id id})` — or nil when nothing
  registers it or more than one namespace does."
  [kind id]
  (when-not (ambiguous-namespaces kind id)
    (rf/handler-meta {:source :store :kind kind :id id})))
