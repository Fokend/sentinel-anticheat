# SentinelAC

Paper anti-cheat plugin source and build files.

## Requirements

- Paper 1.21.4
- Java 21

## Install

Download [`sentinel-anticheat-1.0.0.jar`](release/sentinel-anticheat-1.0.0.jar), copy it into the server's `plugins/` directory, and restart the server.

## Build

Run `./gradlew build` on Linux/macOS or `gradlew.bat build` on Windows. The output JAR is created in `build/libs/`.

The movement grace counters are tick-based and are decremented at most once per server tick, even if `PlayerMoveEvent` fires multiple times in that tick.
