;; Copyright © 2015, JUXT LTD.

(ns aero.core-test
  (:require [clojure.test :refer :all]
            [aero.core :refer :all]
            [clojure.java.io :as io]))

(deftest a-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= "Hello World!" (:greeting config)))))
