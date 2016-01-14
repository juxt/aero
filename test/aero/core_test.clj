;; Copyright © 2015, JUXT LTD.

(ns aero.core-test
  (:require [clojure.test :refer :all]
            [aero.core :refer :all]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(deftest basic-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= "Hello World!" (:greeting config))))
  (testing "Reading empty config returns nil"
    (is (= nil (read-config "test/aero/empty-config.edn")))))

(deftest hostname-test
  (is (=
       (edn/read
        {:readers (readers {:profile :default
                            :hostname "emerald"})}
        (java.io.PushbackReader. (io/reader "test/aero/hosts.edn")))
       {:color "green" :weight 10}))
  (is (=
       (edn/read
        {:readers (readers {:profile :default
                            :hostname "granite"})}
        (java.io.PushbackReader. (io/reader "test/aero/hosts.edn")))
       {:color "black" :weight nil}))
  (is (=
       (edn/read
        {:readers (readers {:profile :default
                            :hostname "diamond"})}
        (java.io.PushbackReader. (io/reader "test/aero/hosts.edn")))
       {:color "white" :weight nil})))
