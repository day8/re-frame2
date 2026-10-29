(ns day8.re-frame2-xray.panels.shared.view-highlight
  "The host-app view hover highlight shared by the Epoch VIEWS table and
  the Views panel's flow graph: hovering a view row or node stamps the
  `.rf-xray-view-highlight` class (`theme/global-styles`, unscoped so it
  reaches the host page) on every DOM node Spec 006 marks with that view's
  `data-rf-view`.

  Clearing goes by CLASS, never by view id, and also runs from the
  container's detaching ref. React fires no mouseleave on unmount, and an
  index-keyed row can change its view id under a resting pointer, so a
  clear keyed on the row's current id would leave the previous view
  painted on the host page.")

(def highlight-class "rf-xray-view-highlight")

(defn- for-each-node!
  [selector f]
  (when (exists? js/document)
    (.forEach (.querySelectorAll js/document selector) f)
    nil))

(defn clear!
  "Remove the highlight from every node carrying it."
  []
  (for-each-node! (str "." highlight-class)
                  (fn [^js node] (.remove (.-classList node) highlight-class))))

(defn apply!
  "Highlight `view-id`'s rendered nodes, and only them."
  [view-id]
  (clear!)
  (when (some? view-id)
    (for-each-node! (str "[data-rf-view='" view-id "']")
                    (fn [^js node] (.add (.-classList node) highlight-class)))))

(defn clear-on-detach!
  "A container `:ref` that clears the highlight when the container
  unmounts. A var, so its identity is stable and React does not detach
  and re-attach it on every render."
  [el]
  (when (nil? el) (clear!)))
