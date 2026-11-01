import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 52

dependencies {
    api(project(":lib:cryptoaes"))
    api(project(":lib:i18n"))
}
