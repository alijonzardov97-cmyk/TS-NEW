# syntax=docker/dockerfile:1

# Stage 1: Build the Rust server
FROM rust:1.93-bookworm AS builder
WORKDIR /build

# Cache dependencies by copying only manifests first
COPY Cargo.toml Cargo.lock ./
COPY crates/ts-server/Cargo.toml crates/ts-server/
COPY crates/ts-db/Cargo.toml crates/ts-db/
COPY crates/ts-crypto/Cargo.toml crates/ts-crypto/
COPY crates/ts-common/Cargo.toml crates/ts-common/

# Create dummy source files for dependency caching
RUN mkdir -p crates/ts-server/src && echo "fn main() {}" > crates/ts-server/src/main.rs \
    && mkdir -p crates/ts-db/src && echo "" > crates/ts-db/src/lib.rs \
    && mkdir -p crates/ts-crypto/src && echo "" > crates/ts-crypto/src/lib.rs \
    && mkdir -p crates/ts-common/src && echo "" > crates/ts-common/src/lib.rs \
    && mkdir -p migrations && touch migrations/.keep

# Build dependencies only — cache mounts persist the registry + build artifacts between builds
# Each stage uses unique cache IDs to prevent parallel build race conditions on .cargo-ok files
ENV SQLX_OFFLINE=true
RUN --mount=type=cache,id=builder-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=builder-git,target=/usr/local/cargo/git \
    --mount=type=cache,id=builder-target,target=/build/target \
    find /usr/local/cargo/registry -name .cargo-ok -delete 2>/dev/null; \
    cargo build --release 2>/dev/null || true

# Copy actual source and rebuild (only our code recompiles, deps are cached)
COPY crates/ crates/
COPY migrations/ migrations/
RUN --mount=type=cache,id=builder-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=builder-git,target=/usr/local/cargo/git \
    --mount=type=cache,id=builder-target,target=/build/target \
    find /usr/local/cargo/registry -name .cargo-ok -delete 2>/dev/null; \
    touch crates/*/src/*.rs && cargo build --release \
    && cp target/release/ts-server /build/ts-server

# Stage 2: Build WASM crypto module
FROM rust:1.93-bookworm AS wasm-builder
RUN curl https://rustwasm.github.io/wasm-pack/installer/init.sh -sSf | sh
WORKDIR /build

# Copy only the crates needed for WASM build
COPY Cargo.toml Cargo.lock ./
COPY crates/ts-crypto/Cargo.toml crates/ts-crypto/
COPY crates/ts-crypto-wasm/Cargo.toml crates/ts-crypto-wasm/
# Dummy workspace members so Cargo.toml parses (they're excluded but referenced)
COPY crates/ts-server/Cargo.toml crates/ts-server/
COPY crates/ts-db/Cargo.toml crates/ts-db/
COPY crates/ts-common/Cargo.toml crates/ts-common/

# Copy actual source for crypto crates
COPY crates/ts-crypto/ crates/ts-crypto/
COPY crates/ts-crypto-wasm/ crates/ts-crypto-wasm/

# Build WASM package (separate cache IDs from builder stage to avoid parallel race conditions)
RUN --mount=type=cache,id=wasm-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=wasm-git,target=/usr/local/cargo/git \
    find /usr/local/cargo/registry -name .cargo-ok -delete 2>/dev/null; \
    cd crates/ts-crypto-wasm && \
    wasm-pack build --target web --out-dir /build/wasm-pkg && \
    rm -f /build/wasm-pkg/package.json /build/wasm-pkg/.gitignore

# Stage 3: Build the web client
FROM node:22-bookworm AS web-builder
WORKDIR /build
COPY clients/web/package.json clients/web/package-lock.json* ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci || npm install
COPY clients/web/ .
# Copy WASM package into the crypto directory
COPY --from=wasm-builder /build/wasm-pkg/ ./src/lib/crypto/wasm/
RUN npm run build

# Stage 3: Minimal runtime image
FROM debian:bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r ts && useradd -r -g ts -s /bin/false ts
WORKDIR /app

COPY --from=builder /build/ts-server .
COPY --from=web-builder /build/build ./static
COPY migrations/ ./migrations/

RUN mkdir -p /app/data/files && chown -R ts:ts /app
USER ts

EXPOSE 8080

ENV RUST_LOG=info
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

CMD ["./ts-server"]
