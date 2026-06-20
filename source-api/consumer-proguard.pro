-keep class eu.kanade.tachiyomi.source.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.online.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.** extends eu.kanade.tachiyomi.source.MangaSource { public protected *; }
# Data classes exchanged across the extension boundary (constructed by extensions,
# only read by the app). Without an explicit keep, R8 strips their constructors as
# "unused", causing NoSuchMethodError when an extension instantiates them.
-keep class eu.kanade.tachiyomi.source.MangaSourceInfo { *; }
-keep interface eu.kanade.tachiyomi.source.MultiSourceCatalogSource { *; }

-keep class eu.kanade.tachiyomi.animesource.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.online.** { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.** extends eu.kanade.tachiyomi.animesource.AnimeSource { public protected *; }

-keep,allowoptimization class eu.kanade.tachiyomi.util.JsoupExtensionsKt { public protected *; }
