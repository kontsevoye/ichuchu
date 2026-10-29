.PHONY: build app apk release signed-release release-plan check-release

build:
	cd zig-src && zig build -Doptimize=ReleaseSmall -Dtarget=aarch64-linux-android.24 jni

apk:
	bash build-support/build-apk.sh debug

release:
	bash build-support/build-apk.sh release

signed-release:
	bash build-support/build-apk.sh signed-release

app: apk
	cd android && ./gradlew installDebug
	adb shell am start -n io.github.kontsevoye.ichuchu/.MainActivity

fmt:
	ktfmt --kotlinlang-style $(shell find $(KT_SRC) -name '*.kt')

lint:
	ktlint --editorconfig=android/.editorconfig "android/app/src/**/*.kt" "android/**/*.gradle.kts"


release-plan:
	node build-support/release.mjs

check-release:
	node --test build-support/*.test.mjs
