(ns hyperphor.alzabo.render-test
  (:require [clojure.test :refer [deftest is]]
            [hyperphor.alzabo.render :as render]))

(deftest test-linkify
  (is (= ["see " [:a {:href "http://a.b/c"} "http://a.b/c"] ", and " [:a {:href "https://x.y"} "https://x.y"] "."]
         (render/linkify "see http://a.b/c, and https://x.y.")))
  (is (= ["no links, just http: and h"] (render/linkify "no links, just http: and h")))
  (is (nil? (render/linkify nil))))
