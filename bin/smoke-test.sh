lein clean
lein cljsbuild once
lein run resources/jazz-config.edn documentation
open resources/public/schema/jazz/1.0/index.html
