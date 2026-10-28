# Isolated Linux toolchain for building and testing installed pack archives.
# No private signing key is copied into this image.
FROM node:22-bookworm-slim AS node
FROM golang:1.27.2-bookworm AS go
FROM rust:1.99.0-bookworm AS rust
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS dotnet
FROM maven:3.9-eclipse-temurin-21
USER root
RUN apt-get update && apt-get install -y --no-install-recommends python3 gnupg gpgv build-essential libicu-dev libssl-dev pkg-config && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm
COPY --from=go /usr/local/go /usr/local/go
COPY --from=rust /usr/local/cargo /usr/local/cargo
COPY --from=rust /usr/local/rustup /usr/local/rustup
COPY --from=dotnet /usr/share/dotnet /usr/share/dotnet
RUN ln -s /usr/share/dotnet/dotnet /usr/local/bin/dotnet
ENV PATH="/usr/local/go/bin:/usr/local/cargo/bin:${PATH}" CARGO_HOME=/usr/local/cargo RUSTUP_HOME=/usr/local/rustup DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
WORKDIR /work
