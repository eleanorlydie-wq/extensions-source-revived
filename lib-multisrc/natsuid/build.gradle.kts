import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 2

dependencies {
    compileOnlyApi("com.squareup.okhttp3:okhttp-brotli:5.3.2")
}
