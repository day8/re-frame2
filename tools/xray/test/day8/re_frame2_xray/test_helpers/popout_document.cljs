(ns day8.re-frame2-xray.test-helpers.popout-document
  "Two-document stubs for the node lane's pop-out rows (rf2-3x7nj.25.5,
  rf2-3x7nj.27.2).

  ## Why this exists

  `popout!` paints the shell into the pop-out window's document from
  code running in the OPENER's realm, so every `js/document` /
  `js/window` in that code names the opener, and DOM events do not cross
  the realm boundary. A row pinning 'this reads the node's OWN document'
  therefore needs TWO documents: the opener's, installed as the
  `js/document` / `js/window` globals, and a distinct pop-out one the
  element under test belongs to. A row in which both are the same
  document cannot fail, whichever one the code reads.

  The stubs record listeners rather than dispatching events: a row
  drives a drag by calling the handler the code registered, on the
  document or window it registered it on — which is exactly the edge
  under test.

  Only the node lane can install the globals (rf2-higwg: in the
  `:browser-test` build `window.document` is non-configurable and `set!`
  silently no-ops), so [[with-opener-globals]] runs its body only where
  the stub takes.")

(defn listener-target
  "A stub EventTarget recording `addEventListener` /
  `removeEventListener`. Answers `{:target <obj> :listeners <atom>}`,
  the atom holding `{event-name [handler …]}` — a removal takes the
  handler back out, so a fully detached target reads all-empty vectors."
  []
  (let [listeners (atom {})
        target    (js-obj)]
    (set! (.-addEventListener target)
          (fn [ev-name handler]
            (swap! listeners update ev-name (fnil conj []) handler)
            nil))
    (set! (.-removeEventListener target)
          (fn [ev-name handler]
            (swap! listeners update ev-name
                   (fn [hs] (vec (remove #(identical? % handler) hs))))
            nil))
    {:target target :listeners listeners}))

(defn mk-document
  "A stub Document with its own window. Answers:

    :doc              the document — a listener target carrying a `body`
                      (whose `style.cursor` starts \"\" and whose
                      `contains` answers membership of the `:contents`
                      set), an `activeElement` slot, and `defaultView`
    :listeners        the document's listener atom
    :window           its `defaultView` — a listener target whose
                      `document` is `:doc`
    :window-listeners the window's listener atom
    :contents         an atom of the elements `body.contains` reports"
  []
  (let [{doc :target listeners :listeners}      (listener-target)
        {win :target win-listeners :listeners} (listener-target)
        contents (atom #{})
        body     (js-obj "style" (js-obj "cursor" ""))]
    (set! (.-contains body) (fn [el] (contains? @contents el)))
    (set! (.-body doc) body)
    (set! (.-activeElement doc) body)
    (set! (.-defaultView doc) win)
    (set! (.-document win) doc)
    {:doc              doc
     :listeners        listeners
     :window           win
     :window-listeners win-listeners
     :contents         contents}))

(defn listeners-on
  "The handlers `listeners` (a listener atom) holds for `ev-name`."
  [listeners ev-name]
  (get @listeners ev-name []))

(defn detached?
  "True when the listener atom holds no handler at all."
  [listeners]
  (every? empty? (vals @listeners)))

(defn- can-stub-globals?
  "True iff this host lets `set!` replace `js/document` (the rf2-higwg
  probe `mount_cljs_test` also uses): write a marker, read it back,
  restore."
  []
  (let [marker (js-obj "rf2-3x7nj-marker" true)
        prior  (when (exists? js/document) js/document)]
    (set! js/document marker)
    (let [installed? (identical? js/document marker)]
      (if prior
        (set! js/document prior)
        (when installed?
          (js-delete js/goog.global "document")))
      installed?)))

(defn with-opener-globals
  "Run `(f opener)` with `js/document` and `js/window` stubbed as the
  OPENER's — `opener` is a [[mk-document]] map — restoring both globals
  afterwards. Runs nothing where the host refuses the stub (the browser
  lane), so the row is a node-lane row by construction."
  [f]
  (when (can-stub-globals?)
    (let [opener   (mk-document)
          had-doc? (exists? js/document)
          prior-d  (when had-doc? js/document)
          had-win? (exists? js/window)
          prior-w  (when had-win? js/window)]
      (set! js/document (:doc opener))
      (set! js/window (:window opener))
      (try
        (f opener)
        (finally
          (if had-doc?
            (set! js/document prior-d)
            (js-delete js/goog.global "document"))
          (if had-win?
            (set! js/window prior-w)
            (js-delete js/goog.global "window")))))))
