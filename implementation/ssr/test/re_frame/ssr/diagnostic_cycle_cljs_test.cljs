(ns re-frame.ssr.diagnostic-cycle-cljs-test
  "THE DIAGNOSTIC PATH MUST NOT ITSELF THROW.

  `cljs.core`'s printer descends into a plain JS object or array with no
  seen-set, and a foreign graph may be CYCLIC — React 19's `ctx.Provider` is
  the context, whose `Provider` key points back at itself. So
  `[ThemeContext.Provider {…}]`, the very mistake
  `:rf.error/invalid-hiccup-head` exists to catch, would raise `RangeError`
  instead of the error were `re-frame.ssr.emit` to print the offending
  value. The emitter's head errors describe a malformed head, and the
  element around any head, by shape, and print none of it.

  Each guarded site must show that a cyclic input yields the site's OWN
  error id and that the thrown ex-data survives `pr-str` at a downstream
  sink. [[outcome]] returns a map rather than letting a throw abort the var,
  so a regression fails with `\"RangeError\"` in its own failure text."
  (:require ["react" :as react]
            [cljs.test :refer-macros [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private corpus-context (react/createContext "unset"))

(def ^:private provider
  "`ctx.Provider` — what an author writes in head position."
  (.-Provider corpus-context))

;; ---------------------------------------------------------------------------
;; Observation
;; ---------------------------------------------------------------------------

(defn- outcome
  "Run `f` and describe what happened as DATA: on a throw, the host error
  NAME, the framework error id, the message, and whether the ex-data
  survives `pr-str`."
  [f]
  (try
    {:returned (f)}
    (catch :default e
      (let [data (ex-data e)]
        {:threw    (.-name e)
         :error-id (:rf.error/id data)
         :message  (ex-message e)
         :ex-data-printable?
         (try (string? (pr-str data)) (catch :default _ false))}))))

(defn- rejected-with
  "Assert `f` threw the framework error `id` with printable ex-data."
  [id label f]
  (let [o (outcome f)]
    (is (= id (:error-id o))
        (str label " must throw " id "; got " (pr-str o)))
    (is (true? (:ex-data-printable? o))
        (str label "'s ex-data must survive pr-str at a downstream sink; got "
             (pr-str (dissoc o :message))))
    o))

;; ---------------------------------------------------------------------------
;; re-frame.ssr.emit — three throw sites
;; ---------------------------------------------------------------------------

(deftest emit-rejects-a-cyclic-hiccup-head-with-its-own-error
  (testing "THE CENTRAL CASE: a provider is neither `keyword?` nor `ifn?`, so
           it falls to `reject-invalid-hiccup-head!`, at the top level or deep
           in markup (the path `render-to-string` takes)"
    (doseq [[label el] [["a provider in head position"  [provider {:value "dark"}]]
                        ["a provider nested in markup"  [:div.hosts [:h1 "hosts"]
                                                         [provider {:value "dark"} "x"]]]]]
      (rejected-with :rf.error/invalid-hiccup-head label
                     #(rf.ssr.emit/emit-element el)))))

(deftest emit-rejects-a-cyclic-reserved-rf-head-with-its-own-error
  (testing "a cycle among an `:rf/*` element's props is counted, never printed"
    (rejected-with :rf.error/invalid-hiccup-head "an unrecognised :rf/* head"
                   #(rf.ssr.emit/emit-element [:rf/suspense-boundry {:ctx provider}]))))

(deftest emit-rejects-a-cyclic-reagent-native-head-with-its-own-error
  (testing "`[:> ctx.Provider …]` is what `:>` interop is FOR"
    (rejected-with :rf.error/ssr-reagent-native-head "a :> provider element"
                   #(rf.ssr.emit/emit-element [:> provider {:value "dark"}]))))
