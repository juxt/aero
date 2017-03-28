(ns aero.lumo-test
  (:require
   aero.core-test
   [cljs.test :refer-macros [deftest is testing run-tests]]))

(defn -main [& argv]
  (println "Testing with lumo")
  (run-tests 'aero.core-test))
