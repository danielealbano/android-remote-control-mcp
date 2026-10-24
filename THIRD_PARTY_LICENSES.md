# Third-Party Licenses

Android Remote Control MCP is licensed under the GNU General Public License v3.0 or later (see
[LICENSE.md](LICENSE.md)). It uses the third-party components listed below; each of them remains under its own
license, available at the linked location. Components marked as downloaded at runtime are not part of the APK.
Versions are intentionally not listed: the exact versions of a given release are recorded in
`gradle/libs.versions.toml` (plus Gradle's dependency resolution), `vendor/ngrok-java/ngrok-java-native/Cargo.lock`
and `vendor/cloudflared/go.mod`.

Dependencies used only at build or test time (JUnit, MockK, Turbine, Testcontainers, ktlint, detekt, the Android
Gradle Plugin, ...) are not part of the app and are not listed.

## Android libraries (Gradle dependencies)

| Component | License | Source |
|---|---|---|
| AndroidX / Jetpack libraries (`androidx.*`, `org.jetbrains.androidx.*`) | Apache-2.0 | https://github.com/androidx/androidx |
| Protocol Buffers (bundled by `androidx.datastore:datastore-preferences-external-protobuf`) | BSD-3-Clause | https://github.com/protocolbuffers/protobuf |
| Compose Multiplatform (`org.jetbrains.compose.*`) | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform |
| Kotlin standard library and kotlinx libraries (`org.jetbrains.kotlin`, `org.jetbrains.kotlinx`) | Apache-2.0 | https://github.com/JetBrains/kotlin and https://github.com/Kotlin |
| JetBrains Java annotations (`org.jetbrains:annotations`) | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| Ktor | Apache-2.0 | https://github.com/ktorio/ktor |
| Netty | Apache-2.0 | https://github.com/netty/netty |
| OkHttp | Apache-2.0 | https://github.com/square/okhttp |
| Okio | Apache-2.0 | https://github.com/square/okio |
| Coil | Apache-2.0 | https://github.com/coil-kt/coil |
| Dagger and Hilt | Apache-2.0 | https://github.com/google/dagger |
| Material Components for Android | Apache-2.0 | https://github.com/material-components/material-components-android |
| Accompanist | Apache-2.0 | https://github.com/google/accompanist |
| Google Play services client libraries (`com.google.android.gms`, `gms` flavor only) | Android Software Development Kit License | https://developer.android.com/studio/terms |
| Guava (`guava`, `failureaccess`, `listenablefuture`) | Apache-2.0 | https://github.com/google/guava |
| J2ObjC annotations | Apache-2.0 | https://github.com/google/j2objc |
| Error Prone annotations | Apache-2.0 | https://github.com/google/error-prone |
| AutoValue annotations | Apache-2.0 | https://github.com/google/auto |
| JSR-305 annotations (`com.google.code.findbugs:jsr305`) | BSD-3-Clause (the POM declares Apache-2.0) | https://github.com/findbugsproject/findbugs |
| libphonenumber | Apache-2.0 | https://github.com/google/libphonenumber |
| ONNX Runtime (Android) | MIT; bundles third-party code listed in its ThirdPartyNotices.txt | https://github.com/microsoft/onnxruntime |
| MCP Kotlin SDK | MIT | https://github.com/modelcontextprotocol/kotlin-sdk |
| java-jwt | MIT | https://github.com/auth0/java-jwt |
| Jackson (`jackson-core`, `jackson-databind`, `jackson-annotations`) | Apache-2.0 | https://github.com/FasterXML/jackson |
| Bouncy Castle (`bcprov`, `bcpkix`, `bcutil`) | Bouncy Castle Licence (MIT-style) | https://www.bouncycastle.org/licence.html |
| Checker Framework qualifiers (`checker-qual`) | MIT | https://github.com/typetools/checker-framework |
| SLF4J (`slf4j-api`, `slf4j-android`) | MIT | https://www.slf4j.org/license.html |
| Jetty ALPN API (`alpn-api`) | Apache-2.0 OR EPL-1.0 | https://github.com/jetty-project/jetty-alpn |
| Typesafe Config (`com.typesafe:config`) | Apache-2.0 | https://github.com/lightbend/config |
| kotlin-logging | Apache-2.0 | https://github.com/oshai/kotlin-logging |
| JSR-330 (`javax.inject`) and Jakarta Inject API | Apache-2.0 | https://github.com/eclipse-ee4j/injection-api |
| JSpecify annotations | Apache-2.0 | https://github.com/jspecify/jspecify |
| osmdroid | Apache-2.0 | https://github.com/osmdroid/osmdroid |

## Bundled native binaries

### cloudflared (`libcloudflared.so`, executed as a separate process)

| Component | License | Source |
|---|---|---|
| cloudflared | Apache-2.0 | https://github.com/cloudflare/cloudflared |
| Go standard library and runtime (statically linked) | BSD-3-Clause | https://go.dev/LICENSE |

Go modules compiled into it:

| Module | License | Source |
|---|---|---|
| github.com/BurntSushi/toml | MIT | https://pkg.go.dev/github.com/BurntSushi/toml |
| github.com/beorn7/perks | MIT | https://pkg.go.dev/github.com/beorn7/perks |
| github.com/cespare/xxhash/v2 | MIT | https://pkg.go.dev/github.com/cespare/xxhash/v2 |
| github.com/coreos/go-oidc/v3 | Apache-2.0 | https://pkg.go.dev/github.com/coreos/go-oidc/v3 |
| github.com/coreos/go-systemd/v22 | Apache-2.0 | https://pkg.go.dev/github.com/coreos/go-systemd/v22 |
| github.com/cpuguy83/go-md2man/v2 | MIT | https://pkg.go.dev/github.com/cpuguy83/go-md2man/v2 |
| github.com/facebookgo/grace | MIT | https://pkg.go.dev/github.com/facebookgo/grace |
| github.com/fsnotify/fsnotify | BSD-3-Clause | https://pkg.go.dev/github.com/fsnotify/fsnotify |
| github.com/getsentry/sentry-go | BSD-2-Clause | https://pkg.go.dev/github.com/getsentry/sentry-go |
| github.com/go-chi/chi/v5 | MIT | https://pkg.go.dev/github.com/go-chi/chi/v5 |
| github.com/go-chi/cors | MIT | https://pkg.go.dev/github.com/go-chi/cors |
| github.com/go-jose/go-jose/v4 | Apache-2.0 AND BSD-3-Clause | https://pkg.go.dev/github.com/go-jose/go-jose/v4 |
| github.com/go-logr/logr | Apache-2.0 | https://pkg.go.dev/github.com/go-logr/logr |
| github.com/go-logr/stdr | Apache-2.0 | https://pkg.go.dev/github.com/go-logr/stdr |
| github.com/gobwas/httphead | MIT | https://pkg.go.dev/github.com/gobwas/httphead |
| github.com/gobwas/pool | MIT | https://pkg.go.dev/github.com/gobwas/pool |
| github.com/gobwas/ws | MIT | https://pkg.go.dev/github.com/gobwas/ws |
| github.com/google/gopacket | BSD-3-Clause | https://pkg.go.dev/github.com/google/gopacket |
| github.com/google/uuid | BSD-3-Clause | https://pkg.go.dev/github.com/google/uuid |
| github.com/gorilla/websocket | BSD-2-Clause | https://pkg.go.dev/github.com/gorilla/websocket |
| github.com/grpc-ecosystem/grpc-gateway/v2 | BSD-3-Clause | https://pkg.go.dev/github.com/grpc-ecosystem/grpc-gateway/v2 |
| github.com/json-iterator/go | MIT | https://pkg.go.dev/github.com/json-iterator/go |
| github.com/klauspost/compress | Apache-2.0 AND MIT AND BSD-3-Clause | https://pkg.go.dev/github.com/klauspost/compress |
| github.com/mattn/go-colorable | MIT | https://pkg.go.dev/github.com/mattn/go-colorable |
| github.com/mattn/go-isatty | MIT | https://pkg.go.dev/github.com/mattn/go-isatty |
| github.com/mitchellh/go-homedir | MIT | https://pkg.go.dev/github.com/mitchellh/go-homedir |
| github.com/modern-go/concurrent | Apache-2.0 | https://pkg.go.dev/github.com/modern-go/concurrent |
| github.com/modern-go/reflect2 | Apache-2.0 | https://pkg.go.dev/github.com/modern-go/reflect2 |
| github.com/munnerz/goautoneg | BSD-3-Clause | https://pkg.go.dev/github.com/munnerz/goautoneg |
| github.com/pkg/errors | BSD-2-Clause | https://pkg.go.dev/github.com/pkg/errors |
| github.com/prometheus/client_golang | Apache-2.0 AND BSD-3-Clause | https://pkg.go.dev/github.com/prometheus/client_golang |
| github.com/prometheus/client_model | Apache-2.0 | https://pkg.go.dev/github.com/prometheus/client_model |
| github.com/prometheus/common | Apache-2.0 | https://pkg.go.dev/github.com/prometheus/common |
| github.com/prometheus/procfs | Apache-2.0 | https://pkg.go.dev/github.com/prometheus/procfs |
| github.com/quic-go/quic-go | MIT | https://pkg.go.dev/github.com/quic-go/quic-go (built from the fork github.com/chungthuang/quic-go) |
| github.com/rs/zerolog | MIT | https://pkg.go.dev/github.com/rs/zerolog |
| github.com/russross/blackfriday/v2 | BSD-2-Clause | https://pkg.go.dev/github.com/russross/blackfriday/v2 |
| github.com/urfave/cli/v2 | MIT | https://pkg.go.dev/github.com/urfave/cli/v2 (built from the fork github.com/ipostelnik/cli/v2) |
| go.opentelemetry.io/auto/sdk | Apache-2.0 | https://pkg.go.dev/go.opentelemetry.io/auto/sdk |
| go.opentelemetry.io/contrib/propagators | Apache-2.0 | https://pkg.go.dev/go.opentelemetry.io/contrib/propagators |
| go.opentelemetry.io/otel | Apache-2.0 | https://pkg.go.dev/go.opentelemetry.io/otel |
| go.opentelemetry.io/otel/exporters/otlp/otlptrace | Apache-2.0 | https://pkg.go.dev/go.opentelemetry.io/otel/exporters/otlp/otlptrace |
| go.opentelemetry.io/otel/metric | Apache-2.0 | https://pkg.go.dev/go.opentelemetry.io/otel/metric |
| go.opentelemetry.io/otel/sdk | Apache-2.0 | https://pkg.go.dev/go.opentelemetry.io/otel/sdk |
| go.opentelemetry.io/otel/trace | Apache-2.0 | https://pkg.go.dev/go.opentelemetry.io/otel/trace |
| go.opentelemetry.io/proto/otlp | Apache-2.0 | https://pkg.go.dev/go.opentelemetry.io/proto/otlp |
| go.uber.org/automaxprocs | MIT | https://pkg.go.dev/go.uber.org/automaxprocs |
| golang.org/x/crypto | BSD-3-Clause | https://pkg.go.dev/golang.org/x/crypto |
| golang.org/x/net | BSD-3-Clause | https://pkg.go.dev/golang.org/x/net |
| golang.org/x/oauth2 | BSD-3-Clause | https://pkg.go.dev/golang.org/x/oauth2 |
| golang.org/x/sync | BSD-3-Clause | https://pkg.go.dev/golang.org/x/sync |
| golang.org/x/sys | BSD-3-Clause | https://pkg.go.dev/golang.org/x/sys |
| golang.org/x/term | BSD-3-Clause | https://pkg.go.dev/golang.org/x/term |
| golang.org/x/text | BSD-3-Clause | https://pkg.go.dev/golang.org/x/text |
| google.golang.org/genproto/googleapis/api | Apache-2.0 | https://pkg.go.dev/google.golang.org/genproto/googleapis/api |
| google.golang.org/genproto/googleapis/rpc | Apache-2.0 | https://pkg.go.dev/google.golang.org/genproto/googleapis/rpc |
| google.golang.org/grpc | Apache-2.0 | https://pkg.go.dev/google.golang.org/grpc |
| google.golang.org/protobuf | BSD-3-Clause | https://pkg.go.dev/google.golang.org/protobuf |
| gopkg.in/natefinch/lumberjack.v2 | MIT | https://pkg.go.dev/gopkg.in/natefinch/lumberjack.v2 |
| gopkg.in/yaml.v2 | Apache-2.0 AND MIT | https://pkg.go.dev/gopkg.in/yaml.v2 |
| gopkg.in/yaml.v3 | Apache-2.0 AND MIT | https://pkg.go.dev/gopkg.in/yaml.v3 |
| nhooyr.io/websocket | MIT | https://pkg.go.dev/nhooyr.io/websocket |
| zombiezen.com/go/capnproto2 | MIT | https://pkg.go.dev/zombiezen.com/go/capnproto2 |

### ngrok-java (`libngrok_java.so`, loaded in-process through JNI)

| Component | License | Source |
|---|---|---|
| ngrok-java (built from a fork) | MIT OR Apache-2.0 | https://github.com/ngrok/ngrok-java (fork: https://github.com/danielealbano/ngrok-java) |
| Rust standard library (std, core, alloc; statically linked) | MIT OR Apache-2.0 | https://github.com/rust-lang/rust |

Rust crates compiled into it:

| Crate | License | Source |
|---|---|---|
| aho-corasick | Unlicense OR MIT | https://crates.io/crates/aho-corasick |
| arc-swap | MIT OR Apache-2.0 | https://crates.io/crates/arc-swap |
| awaitdrop | MIT OR Apache-2.0 | https://crates.io/crates/awaitdrop |
| axum-core | MIT | https://crates.io/crates/axum-core |
| base64 | MIT OR Apache-2.0 | https://crates.io/crates/base64 |
| bitflags | MIT OR Apache-2.0 | https://crates.io/crates/bitflags |
| block-buffer | MIT OR Apache-2.0 | https://crates.io/crates/block-buffer |
| bytes | MIT | https://crates.io/crates/bytes |
| cesu8 | Apache-2.0 OR MIT | https://crates.io/crates/cesu8 |
| cfg-if | MIT OR Apache-2.0 | https://crates.io/crates/cfg-if |
| chacha20 | MIT OR Apache-2.0 | https://crates.io/crates/chacha20 |
| combine | MIT | https://crates.io/crates/combine |
| cpufeatures | MIT OR Apache-2.0 | https://crates.io/crates/cpufeatures |
| crypto-common | MIT OR Apache-2.0 | https://crates.io/crates/crypto-common |
| digest | MIT OR Apache-2.0 | https://crates.io/crates/digest |
| doc-comment | MIT | https://crates.io/crates/doc-comment |
| either | MIT OR Apache-2.0 | https://crates.io/crates/either |
| form_urlencoded | MIT OR Apache-2.0 | https://crates.io/crates/form_urlencoded |
| futures | MIT OR Apache-2.0 | https://crates.io/crates/futures |
| futures-channel | MIT OR Apache-2.0 | https://crates.io/crates/futures-channel |
| futures-core | MIT OR Apache-2.0 | https://crates.io/crates/futures-core |
| futures-executor | MIT OR Apache-2.0 | https://crates.io/crates/futures-executor |
| futures-io | MIT OR Apache-2.0 | https://crates.io/crates/futures-io |
| futures-rustls | MIT OR Apache-2.0 | https://crates.io/crates/futures-rustls |
| futures-sink | MIT OR Apache-2.0 | https://crates.io/crates/futures-sink |
| futures-task | MIT OR Apache-2.0 | https://crates.io/crates/futures-task |
| futures-util | MIT OR Apache-2.0 | https://crates.io/crates/futures-util |
| generational-arena | MPL-2.0 | https://crates.io/crates/generational-arena |
| generic-array | MIT | https://crates.io/crates/generic-array |
| getrandom | MIT OR Apache-2.0 | https://crates.io/crates/getrandom |
| headers | MIT | https://crates.io/crates/headers |
| headers-core | MIT | https://crates.io/crates/headers-core |
| hostname | MIT | https://crates.io/crates/hostname |
| http | MIT OR Apache-2.0 | https://crates.io/crates/http |
| http-body | MIT | https://crates.io/crates/http-body |
| http-body-util | MIT | https://crates.io/crates/http-body-util |
| httpdate | MIT OR Apache-2.0 | https://crates.io/crates/httpdate |
| hyper | MIT | https://crates.io/crates/hyper |
| hyper-http-proxy | MIT | https://crates.io/crates/hyper-http-proxy |
| hyper-rustls | Apache-2.0 OR ISC OR MIT | https://crates.io/crates/hyper-rustls |
| hyper-util | MIT | https://crates.io/crates/hyper-util |
| idna | MIT OR Apache-2.0 | https://crates.io/crates/idna |
| itoa | MIT OR Apache-2.0 | https://crates.io/crates/itoa |
| jaffi_support | MIT OR Apache-2.0 | https://github.com/ngrok-oss/jaffi (built from the fork's `josh/lower-jni-version` branch) |
| jni | MIT OR Apache-2.0 | https://crates.io/crates/jni |
| jni-sys | MIT OR Apache-2.0 | https://crates.io/crates/jni-sys |
| lazy_static | MIT OR Apache-2.0 | https://crates.io/crates/lazy_static |
| libc | MIT OR Apache-2.0 | https://crates.io/crates/libc |
| lock_api | MIT OR Apache-2.0 | https://crates.io/crates/lock_api |
| log | MIT OR Apache-2.0 | https://crates.io/crates/log |
| match_cfg | MIT OR Apache-2.0 | https://crates.io/crates/match_cfg |
| matchers | MIT | https://crates.io/crates/matchers |
| memchr | Unlicense OR MIT | https://crates.io/crates/memchr |
| mime | MIT OR Apache-2.0 | https://crates.io/crates/mime |
| mio | MIT | https://crates.io/crates/mio |
| muxado | MIT OR Apache-2.0 | https://crates.io/crates/muxado |
| ngrok | MIT OR Apache-2.0 | https://crates.io/crates/ngrok |
| nu-ansi-term | MIT | https://crates.io/crates/nu-ansi-term |
| once_cell | MIT OR Apache-2.0 | https://crates.io/crates/once_cell |
| openssl-probe | MIT OR Apache-2.0 | https://crates.io/crates/openssl-probe |
| overload | MIT | https://crates.io/crates/overload |
| parking_lot | MIT OR Apache-2.0 | https://crates.io/crates/parking_lot |
| parking_lot_core | MIT OR Apache-2.0 | https://crates.io/crates/parking_lot_core |
| percent-encoding | MIT OR Apache-2.0 | https://crates.io/crates/percent-encoding |
| pin-project | Apache-2.0 OR MIT | https://crates.io/crates/pin-project |
| pin-project-lite | Apache-2.0 OR MIT | https://crates.io/crates/pin-project-lite |
| ppv-lite86 | MIT OR Apache-2.0 | https://crates.io/crates/ppv-lite86 |
| proxy-protocol | MIT OR Apache-2.0 | https://crates.io/crates/proxy-protocol |
| rand | MIT OR Apache-2.0 | https://crates.io/crates/rand |
| rand_chacha | MIT OR Apache-2.0 | https://crates.io/crates/rand_chacha |
| rand_core | MIT OR Apache-2.0 | https://crates.io/crates/rand_core |
| regex | MIT OR Apache-2.0 | https://crates.io/crates/regex |
| regex-automata | Unlicense OR MIT | https://crates.io/crates/regex-automata |
| regex-syntax | MIT OR Apache-2.0 | https://crates.io/crates/regex-syntax |
| ring | Apache-2.0 AND ISC | https://crates.io/crates/ring |
| rustls | Apache-2.0 OR ISC OR MIT | https://crates.io/crates/rustls |
| rustls-native-certs | Apache-2.0 OR ISC OR MIT | https://crates.io/crates/rustls-native-certs |
| rustls-pemfile | Apache-2.0 OR ISC OR MIT | https://crates.io/crates/rustls-pemfile |
| rustls-pki-types | MIT OR Apache-2.0 | https://crates.io/crates/rustls-pki-types |
| rustls-webpki | ISC | https://crates.io/crates/rustls-webpki |
| ryu | Apache-2.0 OR BSL-1.0 | https://crates.io/crates/ryu |
| scopeguard | MIT OR Apache-2.0 | https://crates.io/crates/scopeguard |
| serde | MIT OR Apache-2.0 | https://crates.io/crates/serde |
| serde_json | MIT OR Apache-2.0 | https://crates.io/crates/serde_json |
| sha1 | MIT OR Apache-2.0 | https://crates.io/crates/sha1 |
| sharded-slab | MIT | https://crates.io/crates/sharded-slab |
| signal-hook-registry | Apache-2.0 OR MIT | https://crates.io/crates/signal-hook-registry |
| slab | MIT | https://crates.io/crates/slab |
| slotmap | Zlib | https://crates.io/crates/slotmap |
| smallvec | MIT OR Apache-2.0 | https://crates.io/crates/smallvec |
| snafu | MIT OR Apache-2.0 | https://crates.io/crates/snafu |
| socket2 | MIT OR Apache-2.0 | https://crates.io/crates/socket2 |
| subtle | BSD-3-Clause | https://crates.io/crates/subtle |
| sync_wrapper | Apache-2.0 | https://crates.io/crates/sync_wrapper |
| thiserror | MIT OR Apache-2.0 | https://crates.io/crates/thiserror |
| thread_local | MIT OR Apache-2.0 | https://crates.io/crates/thread_local |
| tinyvec | Zlib OR Apache-2.0 OR MIT | https://crates.io/crates/tinyvec |
| tinyvec_macros | MIT OR Apache-2.0 OR Zlib | https://crates.io/crates/tinyvec_macros |
| tokio | MIT | https://crates.io/crates/tokio |
| tokio-retry | MIT | https://crates.io/crates/tokio-retry |
| tokio-rustls | MIT OR Apache-2.0 | https://crates.io/crates/tokio-rustls |
| tokio-socks | MIT | https://crates.io/crates/tokio-socks |
| tokio-util | MIT | https://crates.io/crates/tokio-util |
| tower | MIT | https://crates.io/crates/tower |
| tower-layer | MIT | https://crates.io/crates/tower-layer |
| tower-service | MIT | https://crates.io/crates/tower-service |
| tracing | MIT | https://crates.io/crates/tracing |
| tracing-core | MIT | https://crates.io/crates/tracing-core |
| tracing-log | MIT | https://crates.io/crates/tracing-log |
| tracing-subscriber | MIT | https://crates.io/crates/tracing-subscriber |
| try-lock | MIT | https://crates.io/crates/try-lock |
| typenum | MIT OR Apache-2.0 | https://crates.io/crates/typenum |
| unicode-bidi | MIT OR Apache-2.0 | https://crates.io/crates/unicode-bidi |
| unicode-normalization | MIT OR Apache-2.0 | https://crates.io/crates/unicode-normalization |
| untrusted | ISC | https://crates.io/crates/untrusted |
| url | MIT OR Apache-2.0 | https://crates.io/crates/url |
| want | MIT | https://crates.io/crates/want |
| zeroize | Apache-2.0 OR MIT | https://crates.io/crates/zeroize |

## Data and models

| Component | License | Source |
|---|---|---|
| DB-IP IP to City Lite database (compiled into the app at build time) | CC BY 4.0 | https://db-ip.com |
| Ai4Privacy multilingual PII anonymiser model (downloaded by the app on first use of Privacy Mode, not part of the APK) | MIT; Built with Llama | https://huggingface.co/ai4privacy/llama-ai4privacy-multilingual-categorical-anonymiser-openpii |
