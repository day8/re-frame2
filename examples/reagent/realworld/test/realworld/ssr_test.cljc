(ns realworld.ssr-test
  "Headless test for realworld.ssr — `hydration-payload` selects the
   correct SSR-safe slice keys.

   `.cljc` mirrors the source: the helper is portable and the test only
   touches portable data."
  (:require [realworld.ssr :as ssr]))

(defn hydration-payload-test []
  (let [db {:rf/route {:id :route/home}
            :auth {:user {:username "alice"} :token "jwt"}
            :articles {:status :loaded :data [] :error nil :loaded-at 1 :attempt 1}
            :transient {:popup true}}
        payload (ssr/hydration-payload db [:div "hello"])]
    (assert (= #{:rf/route :auth :articles}
               (set (keys (:rf/app-db payload)))))))
