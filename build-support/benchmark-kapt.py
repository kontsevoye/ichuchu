#!/usr/bin/env python3
"""Switch only a manual benchmark checkout to the old Room processor for an A/B control."""

import os
from pathlib import Path


def use_kapt(text):
    plugin = '    alias(libs.plugins.kotlin.ksp)'
    options = '''ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "false")
}'''
    dependency = '    ksp(libs.room.compiler)'
    if any(text.count(value) != 1 for value in [plugin, options, dependency]):
        raise ValueError('Unexpected processor configuration; do not benchmark a partial conversion')
    return (text.replace(plugin, '    id("kotlin-kapt")')
            .replace(options, '''kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}''')
            .replace(dependency, '    kapt(libs.room.compiler)'))


if __name__ == '__main__':
    if os.environ.get('CI_BENCHMARK') != '1' or os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch':
        raise SystemExit('Processor control is restricted to manual, non-publishing benchmarks')
    build_file = Path(__file__).resolve().parents[1] / 'android/app/build.gradle.kts'
    build_file.write_text(use_kapt(build_file.read_text()))
