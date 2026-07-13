import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 36

dependencies {
    //noinspection UseTomlInstead
    implementation("org.brotli:dec:0.1.2")
}
