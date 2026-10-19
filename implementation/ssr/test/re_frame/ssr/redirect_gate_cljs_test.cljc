(ns re-frame.ssr.redirect-gate-cljs-test
  "`:rf.server/redirect`'s URL gate gives one verdict per location on the JVM
  and on Node. The JVM parses with java.net.URI and CLJS with RFC 3986's
  reference grammar, so this pins the two readings to each other over the
  shapes the gate decides on: relative references, hosts named by `:allow`,
  `:allow :any`, network-path references, opaque URIs, and characters
  java.net.URI refuses."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.ssr :as rf.ssr]))

;; COLD-START the adapter slot: this ns shares the node bundle with suites
;; that seat other adapters, and `init!` raises when handed a different one.
(use-fixtures :once
  (fn [f]
    (rf/destroy-adapter!)
    (rf/init! rf.ssr/adapter)
    (try (f) (finally (rf/destroy-adapter!)))))

(def ^:private counter (atom 0))

(defn- verdict
  "`:landed` when `args` writes a redirect, else the `[error reason]` of the
  one gate record it ships."
  [args]
  (let [fid  (keyword "rf.ssr-redirect-gate" (str "frame" (swap! counter inc)))
        lid  (keyword "rf.ssr-redirect-gate" (str "cap" @counter))
        seen (atom [])]
    (rf/make-frame {:id fid :platform :server})
    (rf/reg-event ::attempt (fn [_ [_ a]] {:fx [[:rf.server/redirect a]]}))
    (rf.error-emit/register-error-listener! lid #(swap! seen conj %))
    (try
      (rf/dispatch-sync [::attempt args] {:frame fid})
      (if (:redirect (rf.ssr/peek-response fid))
        :landed
        (some (fn [{error :rf.error/id :keys [reason]}]
                (when (#{:rf.error/redirect-invalid-url
                         :rf.error/redirect-scheme-rejected
                         :rf.error/redirect-host-disallowed} error)
                  [error reason]))
              @seen))
      (finally
        (rf.error-emit/unregister-error-listener! lid)
        (rf/destroy-frame! fid)))))

(deftest one-verdict-per-location-on-both-platforms
  (doseq [[args expected]
          [[{:location "/account/settings"}                    :landed]
           [{:location "dashboard?tab=a#b"}                    :landed]
           [{:location "https://app.example.com/x"}
            [:rf.error/redirect-host-disallowed :relative-only-violation]]
           [{:location "https://app.example.com:8443/x" :allow ["app.example.com"]} :landed]
           [{:location "https://alice@APP.example.com/x" :allow ["app.example.com"]} :landed]
           [{:location "https://evil.example.com/x" :allow ["app.example.com"]}
            [:rf.error/redirect-host-disallowed :not-in-allowlist]]
           [{:location "//cdn.example.com/a" :allow :any}      :landed]
           [{:location "//cdn.example.com/a"}
            [:rf.error/redirect-host-disallowed :relative-only-violation]]
           [{:location "///evil.example/path" :allow ["evil.example"]}
            [:rf.error/redirect-host-disallowed :not-in-allowlist]]
           [{:location "//evil_example/path" :allow ["evil_example"]}
            [:rf.error/redirect-host-disallowed :not-in-allowlist]]
           [{:location "https:evil.example.com" :allow :any}
            [:rf.error/redirect-invalid-url :scheme-without-host]]
           [{:location "mailto:a@example.com" :allow :any}
            [:rf.error/redirect-scheme-rejected :scheme-not-allowed]]
           [{:location "/a b" :allow :any}
            [:rf.error/redirect-invalid-url :parse-failed]]
           [{:location "/\\evil.example.com" :allow :any}
            [:rf.error/redirect-invalid-url :parse-failed]]]]
    (is (= expected (verdict args)) (pr-str args))))
