{pkgs}:
let
  inherit (pkgs) lib;
  sdk = "${pkgs.android-sdk}/share/android-sdk";
  # Zig 0.15.2 cannot read the newer TBD format shipped by Xcode 27.
  # Both SDK discovery and linking stay on the SDK pinned by this flake.
  darwinSdkTools = pkgs.writeShellScriptBin "xcrun" ''
    exec ${pkgs.apple-sdk}/usr/bin/xcrun "$@"
  '';
in
  pkgs.devshell.mkShell {
    name = "ichuchu";
    motd = ''
      Ichuchu: build-apk debug | release | signed-release | check-native
    '';
    env = [
      {
        name = "ANDROID_HOME";
        value = sdk;
      }
      {
        name = "ANDROID_NDK_HOME";
        value = "${sdk}/ndk/29.0.14206865";
      }
      {
        name = "ANDROID_SDK_ROOT";
        value = sdk;
      }
      {
        name = "ANDROID_NDK_ROOT";
        value = "${sdk}/ndk/29.0.14206865";
      }
      {
        name = "JAVA_HOME";
        value = pkgs.jdk17.home;
      }
      {
        name = "ICHUCHU_NIX_SHELL";
        value = "1";
      }
      {
        name = "ICHUCHU_HOST_ZLIB_LIB";
        value = "${pkgs.zlib}/lib";
      }
      {
        name = "ICHUCHU_HOST_ZLIB_INCLUDE";
        value = "${pkgs.zlib.dev}/include";
      }
    ] ++ lib.optionals pkgs.stdenv.isDarwin [
      {
        name = "DEVELOPER_DIR";
        value = "${pkgs.apple-sdk}";
      }
      {
        name = "SDKROOT";
        value = "${pkgs.apple-sdk}/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk";
      }
    ];
    packages = (with pkgs; [
      android-sdk
      jdk17
      zigpkgs."0.15.2"
      git
      gnumake
      python3
      unzip
      shellcheck
      actionlint
      zlib
      ktfmt
      ktlint
      nodejs
      pnpm
    ]) ++ lib.optionals pkgs.stdenv.isDarwin [darwinSdkTools];
    commands = [
      {
        name = "build-apk";
        help = "Build debug, release, or signed-release (interactive or CI)";
        command = ''exec bash "$(git rev-parse --show-toplevel)/build-support/build-apk.sh" "$@"'';
      }
      {
        name = "check-native";
        help = "Run native Mosh protocol regressions";
        command = ''
          repo_root="$(git rev-parse --show-toplevel)"
          export ZIG_GLOBAL_CACHE_DIR="''${ZIG_GLOBAL_CACHE_DIR:-$repo_root/.cache/zig}"
          cd "$repo_root/zig-src"
          exec zig build test-mosh "$@"
        '';
      }
    ];
  }
