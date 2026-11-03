# Port OS for Android TV. The whole public surface is:
#
#   make                  = make build
#   make build            debug APK for ENV (app/build/outputs/apk/<env>Debug/)
#   make lint             ktlint + detekt (all rules) + Android Lint; fails on any finding
#   make format           apply ktlint fixes
#   make test             JVM and Robolectric unit tests
#   make test-device      instrumented tests on the connected emulator or TV (ADB_SERIAL)
#   make install          install the debug APK on ADB_SERIAL
#   make setup-tv         grant the one-time permissions (background launch, media sessions, accessibility service)
#   make release          signed release APK (needs the signing key, see deploy)
#   make deploy           release + upload to the downloads bucket
#   make publish-repo     copy this directory to github.com/portpowered/portos-android-tv (DRY_RUN=1 to preview)
#   make clean
#
# Variables: ENV (pilot|local, default pilot), ADB_SERIAL (default: the only connected device).

ENV ?= pilot
ADB_SERIAL ?=
PACKAGE := com.portoperatingsystem.tv

# Prefer an installed JDK 21: Android Gradle does not run on the newest JDKs.
JDK21 := $(wildcard $(HOME)/scoop/apps/temurin21-jdk/current)
ifneq ($(JDK21),)
export JAVA_HOME := $(JDK21)
endif

# make on Windows runs recipes with cmd.exe, which cannot run ./gradlew.
GRADLE := $(if $(filter Windows_NT,$(OS)),"$(CURDIR)/gradlew.bat",./gradlew) --console=plain
ADB := adb $(if $(ADB_SERIAL),-s $(ADB_SERIAL),)
VARIANT := $(if $(filter local,$(ENV)),Local,$(if $(filter pilot,$(ENV)),Pilot,$(error ENV must be pilot or local)))

.DEFAULT_GOAL := build
.PHONY: build lint format test test-device install setup-tv release deploy publish-repo clean

build:
	$(GRADLE) :app:assemble$(VARIANT)Debug

lint:
	$(GRADLE) --continue ktlintCheck detekt :protocol:lint :app:lint$(VARIANT)Debug

format:
	$(GRADLE) ktlintFormat

test:
	$(GRADLE) :protocol:test :app:test$(VARIANT)DebugUnitTest

test-device:
	$(GRADLE) :app:connected$(VARIANT)DebugAndroidTest

install: build
	$(ADB) install -r app/build/outputs/apk/$(ENV)/debug/app-$(ENV)-debug.apk

setup-tv:
	$(ADB) shell appops set $(PACKAGE) SYSTEM_ALERT_WINDOW allow
	$(ADB) shell cmd notification allow_listener $(PACKAGE)/$(PACKAGE).state.MediaSessionListener
	bash scripts/enable-accessibility.sh $(PACKAGE) "$(ADB_SERIAL)"

release:
	bash scripts/release.sh $(ENV)

deploy: release
	bash scripts/deploy.sh $(ENV)

publish-repo:
	bash scripts/publish-repo.sh

clean:
	$(GRADLE) clean
