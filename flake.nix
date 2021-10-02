{
  description = "Kaitai Struct Compiler";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    sbt-derivation.url = "github:zaninime/sbt-derivation";
  };

  outputs = { self, nixpkgs, flake-utils, sbt-derivation }:
    flake-utils.lib.eachDefaultSystem (
      system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ sbt-derivation.overlay self.overlay.${system} ];
          };
        in
          {
            overlay = final: prev: with prev; {
              kaitai-struct-compiler = pkgs.sbt.mkDerivation {
                pname = "kaitai-struct-compiler";
                version = "0.9";

                src = ./.;
                depsSha256 = "sha256-21BoS04RbHWyfZYkIjJ6R5zjWEB9mIUMYqkcz4llHuY=";

                nativeBuildInputs = [ openjdk makeWrapper ];

                buildPhase = ''
                  sbt stage
                '';

                installPhase = ''
                  runHook preInstall

                  mkdir $out
                  cp -r jvm/target/universal/stage/* $out

                  runHook postInstall
                '';

                postInstall = ''
                  wrapProgram $out/bin/kaitai-struct-compiler \
                    --prefix PATH : ${lib.makeBinPath [ openjdk ] }
                '';
              };
            };

            packages = { inherit (pkgs) kaitai-struct-compiler; };

            defaultPackage = self.packages.${system}.kaitai-struct-compiler;

            devShell = self.defaultPackage.${system};
          }
    );
}
