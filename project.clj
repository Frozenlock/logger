(defproject org.clojars.frozenlock/logger "2.0.25"
  :description "Automatic logger for a BACnet network"
  :url "https://bacnethelp.com"
  :license {:name "GNU General Public License V3"
            :url "http://www.gnu.org/licenses/gpl-3.0.html"}
  :dependencies [[overtone/at-at "1.1.1"]
                 [clj-http "0.6.4"]
                 [org.clojars.frozenlock/gzip64 "1.0.0"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.4.0"]
                                  [bacure "0.3.12"]]}})
