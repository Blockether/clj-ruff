(ns build
  "Build/deploy for clj-ruff. The main `com.blockether/ruff` jar is small (one
   namespace + a namespaced VERSION). The ruff binaries are published as
   per-platform artifacts such as `com.blockether/ruff-native-linux-x64`, each
   carrying the prebuilt `ruff` executable under `prebuilds/<platform>/`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.blockether/ruff)
(def native-platforms #{"linux-x64" "linux-arm64" "darwin-arm64" "darwin-x64" "windows-x64"})
(def native-libs {"linux-x64"    "libruff_c.so"
                  "linux-arm64"  "libruff_c.so"
                  "darwin-arm64" "libruff_c.dylib"
                  "darwin-x64"   "libruff_c.dylib"
                  "windows-x64"  "ruff_c.dll"})

(def version
  "VERSION env (set by CI from the release tag) wins; otherwise the
   resources/VERSION file tagged `-SNAPSHOT` for local builds."
  (let [v (System/getenv "VERSION")]
    (cond
      (and v (str/starts-with? v "v")) (subs v 1)
      v v
      :else (str (str/trim (slurp "resources/VERSION")) "-SNAPSHOT"))))

(def class-dir "target/classes")
(def native-class-dir "target/native-classes")
(def jar-file (format "target/%s.jar" (name lib)))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_] (b/delete {:path "target"}))

(defn- pom-data [description]
  [[:description description]
   [:url "https://github.com/Blockether/clj-ruff"]
   [:licenses [:license [:name "MIT License"] [:url "https://opensource.org/licenses/MIT"]]]
   [:scm [:url "https://github.com/Blockether/clj-ruff"]
    [:connection "scm:git:https://github.com/Blockether/clj-ruff.git"]
    [:developerConnection "scm:git:ssh://git@github.com/Blockether/clj-ruff.git"]]])

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data (pom-data "Clojure binding to ruff — Python code formatting — over a first-party cdylib via FFM.")})
  ;; Ship src + ONLY the META-INF/native-image config from resources/ — NOT
  ;; resources/prebuilds (those belong in the per-platform native jars) and NOT
  ;; the root resources/VERSION (we write the namespaced ruff/VERSION below).
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/copy-dir {:src-dirs ["resources/META-INF"] :target-dir (str class-dir "/META-INF")})
  ;; NAMESPACED version resource (ruff/VERSION) so it can't collide with other
  ;; libs' root `VERSION` on a shared classpath (which made a sibling lib resolve
  ;; a foreign version and 404 its native download).
  (let [vfile (io/file class-dir "ruff" "VERSION")]
    (io/make-parents vfile)
    (spit vfile version))
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Built:" jar-file "version:" version))

(defn- native-lib [platform]
  (symbol "com.blockether" (str "ruff-native-" platform)))

(defn native-jar [{:keys [platform]}]
  (let [platform (some-> platform name)]
    (when-not (native-platforms platform)
      (throw (ex-info (str "Unknown native platform: " platform) {:platform platform :known native-platforms})))
    (let [fname (native-libs platform)
          src   (format "resources/prebuilds/%s/%s" platform fname)
          lib*  (native-lib platform)
          jar*  (format "target/%s.jar" (name lib*))]
      (b/delete {:path native-class-dir})
      (b/delete {:path jar*})
      (when-not (.exists (io/file src))
        (throw (ex-info (str "ruff cdylib not found (build native/ruff-c first): " src) {:platform platform :path src})))
      (b/write-pom {:class-dir native-class-dir
                    :lib lib*
                    :version version
                    :basis @basis
                    :src-dirs []
                    :pom-data (pom-data (format "Prebuilt ruff-c cdylib (FFM) for %s." platform))})
      (b/copy-file {:src src :target (format "%s/prebuilds/%s/%s" native-class-dir platform fname)})
      (b/jar {:class-dir native-class-dir :jar-file jar*})
      (println "Built:" jar* "version:" version)
      jar*)))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote :artifact jar-file :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

(defn deploy-native [{:keys [platform]}]
  (let [platform (some-> platform name)
        jar*     (native-jar {:platform platform})
        lib*     (native-lib platform)]
    (dd/deploy {:installer :remote :artifact jar* :pom-file (b/pom-path {:lib lib* :class-dir native-class-dir})})))

(defn install [_]
  (jar nil)
  (dd/deploy {:installer :local :artifact jar-file :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

(defn install-native [{:keys [platform]}]
  (let [platform (some-> platform name)
        jar*     (native-jar {:platform platform})
        lib*     (native-lib platform)]
    (dd/deploy {:installer :local :artifact jar* :pom-file (b/pom-path {:lib lib* :class-dir native-class-dir})})))
