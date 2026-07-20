(ns re-frame.ui.sub-overrides
  "Internal React carriage for compiled-view Story subscription overrides.

  Story publishes a map at a React ownership boundary; every sub-bearing
  compiled view reads the nearest value through an ordinary hook and binds the
  host-neutral `re-frame.ui.reactive/*sub-overrides*` door only while its own
  body executes.  The reactive core then lowers a hit through the real
  observation port to a callback-free static handle.

  This is deliberately not a public `re-frame.ui` API.  Story owns the author
  surface; this namespace owns only the first-party React carriage.  Both the
  hook and provider collapse in an advanced production build, so ordinary
  compiled views pay no per-view override consult there."
  (:require ["react" :as react]
            [re-frame.adapter.sub-override-context :as override-context]
            [re-frame.interop :as interop]))

(defn ^:no-doc use-current
  "Return the closest mounted override map in a debug/tool build, else nil.
  The condition is the compile-time goog.DEBUG seam used by the downstream
  resolver too; production therefore has neither a useContext hook nor a
  per-sub branch."
  []
  (when interop/debug-enabled?
    (react/useContext override-context/override-context)))

(defn ^:no-doc provider-element
  "Wrap one React `child` in the internal override Provider.  Debug/tool only;
  production returns `child` unchanged.  Nested calls use React's normal
  nearest-provider/LIFO context semantics."
  [overrides child]
  (if interop/debug-enabled?
    (react/createElement (.-Provider override-context/override-context)
                         #js {:value overrides}
                         child)
    child))
