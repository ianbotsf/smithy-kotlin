# Hashing Benchmarks

This project contains benchmarks for the [hashing implementations](../../../runtime/runtime-core/common/src/aws/smithy/kotlin/runtime/hashing)
(CRC32, CRC32C, MD5, SHA-1, SHA-256, HMAC, and ECDSA secp256r1) on the JVM and on the host's native target.

## Testing

```sh
./gradlew :tests:benchmarks:hashing-benchmarks:benchmark
```

Run a single platform with `jvmBenchmark` or `<nativeTarget>Benchmark` (e.g. `linuxX64Benchmark`, `macosArm64Benchmark`).

## Test data

Each benchmark processes a random payload of 64 B, 1 KiB, and 1 MiB:

* `HashFunctionBenchmark` — a single `update` followed by `digest`
* `HmacBenchmark` — HMAC with a 32-byte key over MD5, SHA-1, and SHA-256
* `EcdsaBenchmark` — an ECDSA secp256r1 signature (DER) with a fixed private key

## Implementations

Every benchmark is parameterized by `implementation`:

* `runtime` — what runtime-core ships (JVM stdlib, CRT, or Kotlin depending on algorithm and platform)
* `pure` — the pure-Kotlin implementations in this module

For `pure`, HMAC precomputes the key pads once per key (`PureHmac`), while `runtime` calls `hmac()` per op.

The `pure` MD5 and SHA-1 block functions are platform-specific (`jvm/src` and `native/src`): native is fastest with
every round written out and the message words in locals, which the JVM's JIT handles worse than rolled loops.

The `pure` ECDSA implementation is written to be constant-time with respect to the private key and nonce.

## Timing leak tests

`DudectTimingTest` runs dudect-style tests: it times an operation on a fixed secret vs random secrets and flags a leak
when Welch's |t| exceeds 4.5. A variable-time double-and-add k * G is the positive control. The tests are slow and
sensitive to host load, so they only run with `-Pdudect`:

```sh
./gradlew -Pdudect :tests:benchmarks:hashing-benchmarks:jvmTest
```

Native test binaries are debug builds by default, which don't reflect what LLVM does to optimized code. Build and run
the optimized test binary instead:

```sh
./gradlew -Pdudect :tests:benchmarks:hashing-benchmarks:linkDudectReleaseTestLinuxX64
tests/benchmarks/hashing-benchmarks/build/bin/linuxX64/dudectReleaseTest/dudect.kexe --ktest_filter='*DudectTimingTest*'
```
