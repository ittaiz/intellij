package(default_visibility = ["//visibility:public"])

java_library(
    name = "blaze-plugin-dev",
    srcs = glob(["src/**/*.java"]),
    deps = [
        "//blaze-base",
        "//blaze-java",
        "//intellij-platform-sdk:devkit",
        "//intellij-platform-sdk:plugin_api",
        "//third_party:jsr305",
    ],
)

filegroup(
    name = "plugin_xml",
    srcs = ["src/META-INF/blaze-plugin-dev.xml"],
)

load(
    "//intellij_test:test_defs.bzl",
    "intellij_test",
)

intellij_test(
    name = "integration_tests",
    srcs = glob(["tests/integrationtests/**/*.java"]),
    integration_tests = True,
    required_plugins = "com.google.idea.blaze.ijwb",
    test_package_root = "com.google.idea.blaze.plugin",
    deps = [
        ":blaze-plugin-dev",
        "//blaze-base",
        "//blaze-base:integration_test_utils",
        "//blaze-base:unit_test_utils",
        "//ijwb:ijwb_bazel",
        "//intellij-platform-sdk:plugin_api_for_tests",
        "//intellij_test:lib",
        "//third_party:jsr305",
        "//third_party:test_lib",
    ],
)
