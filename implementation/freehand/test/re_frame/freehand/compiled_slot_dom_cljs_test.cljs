(ns re-frame.freehand.compiled-slot-dom-cljs-test
  "A compiled `v/slot` in a real browser — the BROWSER half of the render
  slot's cross-mode claim.

  The slots slice shipped `v/slot` as common grammar and proved it on the
  structural host, mode against mode. The browser emitter had no `:slot`
  arm at all, so the declarations below did not render wrongly — they
  could not be COMPILED. `case` with no default arm answers a node kind it
  does not know with `IllegalArgumentException: No matching clause`,
  thrown inside macro expansion, naming neither the view nor the form.

  Two lanes prove the repair, and the file rides both:

  - in the NODE suites, which have no DOM, the declarations still load —
    and loading them is the macro expansion that used to fail, so a
    missing arm reds the whole build here first;
  - in the BROWSER job, the same declarations mount and every claim is
    read back off `document`: an inline render-fn's content, a
    prop-carried render-fn invoked once per keyed row with that row's
    own value, and an absent slot whose siblings close over the gap.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §Render slots;
  the compiled tier is
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../spec/004D-Freehand-Compiled-Grammar.md)."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.test-support :as test-support]))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

;; ---------------------------------------------------------------------------
;; The declarations. Module-level, and COMPILED — expanding them is the
;; half of this claim that runs everywhere.
;; ---------------------------------------------------------------------------

(v/defview cell
  "An ordinary declared child, mounted from INSIDE a render-fn body — so
  the browser lane proves a slot renders a boundary and not merely markup."
  {:compiled true}
  [{:keys [label]}]
  [:td.cell label])

(v/defview inline-slot
  "The slot the compiler can see whole: the render-fn is lexically visible
  at the invocation, so its body is lowered by the React emitter."
  {:compiled true}
  [{:keys [label]}]
  [:table [:tbody [:tr#inline (v/slot (v/render-fn [r] [:td.inline r]) label)]]])

(v/defview seam
  "The library seam: content arrives as a PROP and is invoked per row."
  {:compiled true}
  [{:keys [rows row]}]
  [:tbody (for [r rows] [:tr {:key r} (v/slot row r)])])

(v/defview seam-table
  "The caller, supplying the seam's content. Caller and seam are declared
  in the same MODE, which is what the crossing law requires."
  {:compiled true}
  [{:keys [rows]}]
  [:table#seam [seam {:rows rows :row (v/render-fn [r] [cell {:label r}])}]])

(v/defview absent-slot
  "An ABSENT slot renders nothing, and the siblings close over the gap — a
  component may offer content it does not require."
  {:compiled true}
  [{:keys [row]}]
  [:div#absent [:span.before "before"] (v/slot row "a") [:span.after "after"]])

;; ---------------------------------------------------------------------------
;; The host-neutral lane
;; ---------------------------------------------------------------------------

(deftest a-compiled-slot-declaration-expands
  (testing "The defect was a macro-expansion crash, so the loaded
            declaration IS the assertion: reaching this test at all means
            the browser emitter lowered every `v/slot` above. The lowering
            each declaration reports is asserted too, so a declaration that
            quietly fell back to the interpreted walk could not pass."
    (doseq [[note view] [["an inline render-fn" inline-slot]
                         ["a prop-carried render-fn" seam]
                         ["a slot's caller" seam-table]
                         ["an absent slot" absent-slot]]]
      (is (= :compiled (:lowering (v/describe view)))
          (str note " — declared compiled"))
      (is (some? (v/manifest view))
          (str note " — and carries its analysed manifest")))))

;; ---------------------------------------------------------------------------
;; The browser lane
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- unmount! [container mounted]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (when (some? mounted)
    (.unmount (.-react-root ^root/Root mounted)))
  (.remove container)
  nil)

(defn- mounted!
  "Mount `form` into a fresh host node, run `check` against the container,
  and tear the root down whatever happens.

  The rejection handler sits UPSTREAM of the step that calls `done`, and the
  single `done` sits at the tail with nothing after it (rf2-o0n1). `done` runs
  the whole remainder of the run synchronously, so a `.catch` downstream of it
  would claim a later namespace's throw as this row's failure and fire `done` a
  second time."
  [form check done]
  (let [container (host-node!)]
    (-> (act #(v/mount form container))
        (.then (fn [m]
                 (check container)
                 ;; ASYMMETRIC, so it stays put: `m` is what the mount resolved
                 ;; with, and the rejection arm never had a root to unmount —
                 ;; it can only detach the host node, which it does below.
                 (unmount! container m)))
        (.catch (fn [e]
                  (is false (str "compiled slot mount rejected: " e))
                  (.remove container)
                  nil))
        (.then (fn [_] (done))))))

(deftest an-inline-render-fn-renders-its-content-in-the-dom
  (testing "The render-fn's body is lowered by the SAME emitter that
            lowered the view around it, so its content is ordinary
            compiled markup sitting among its siblings."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (mounted! [inline-slot {:label "a"}]
                  (fn [c]
                    (is (some? (.querySelector c "tr#inline > td.inline"))
                        "the slot's content is a real child of the element that declared it")
                    (is (= "a" (.-textContent (.querySelector c "td.inline")))
                        "and the slot's argument reached the render-fn"))
                  done)))))

(deftest a-prop-carried-render-fn-renders-once-per-row
  (testing "The library seam invokes content its CALLER supplied, once per
            keyed row, with that row's own value — the shape a component
            library is built out of."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (mounted! [seam-table {:rows ["r1" "r2" "r3"]}]
                  (fn [c]
                    (let [cells (array-seq (.querySelectorAll c "table#seam td.cell"))]
                      (is (= 3 (count cells))
                          "one declared child boundary per row")
                      (is (= ["r1" "r2" "r3"] (mapv #(.-textContent %) cells))
                          "each row's own value reached its render-fn")))
                  done)))))

(deftest an-absent-slot-renders-nothing-and-the-siblings-close-up
  (testing "A nil carrier renders nothing at all — not an empty wrapper —
            so the slot costs nothing in the DOM when the caller supplied
            no content."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (mounted! [absent-slot {:row nil}]
                  (fn [c]
                    (let [host (.querySelector c "div#absent")]
                      (is (= 2 (.-length (.-children host)))
                          "the absent slot contributed no node")
                      (is (= "beforeafter" (.-textContent host))
                          "and the siblings sit next to each other")))
                  done)))))
