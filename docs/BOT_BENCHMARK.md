# Vortex bot connection benchmark

This benchmark opens real Minecraft protocol clients against a running Vortex/Velocity endpoint.
It is meant to measure the connection path, login/configuration flow, keep-alive handling, movement packets,
disconnects, and target process CPU/RAM while many bot players are online.

## Basic run

Start Velocity normally, then run:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -q "-Dexec.classpathScope=test" "-Dexec.mainClass=org.assiscabron.vortexProxy.platform.backend.BotConnectionBenchmark" "-Dexec.args=--host=127.0.0.1 --port=25565 --bots=100 --rampPerSecond=25 --holdSeconds=30" exec:java
```

## Measuring the Velocity process

Pass the Velocity Java process id:

```powershell
mvn -q "-Dexec.classpathScope=test" "-Dexec.mainClass=org.assiscabron.vortexProxy.platform.backend.BotConnectionBenchmark" "-Dexec.args=--host=127.0.0.1 --port=25565 --bots=250 --rampPerSecond=50 --holdSeconds=60 --moveIntervalMs=500 --pid=12345" exec:java
```

On Windows, the benchmark reads target RAM from `tasklist`.
CPU is read from Java `ProcessHandle` total CPU duration and reported as:

- `targetCpuCorePercent`: percent of one CPU core consumed by the target process.
- `targetCpuMachinePercent`: percent of the whole machine consumed by the target process.

## Useful knobs

- `--bots=1000`: total bot clients.
- `--rampPerSecond=100`: connection ramp speed.
- `--holdSeconds=120`: how long bots stay online after the join phase.
- `--timeoutSeconds=120`: maximum wait for all bots to reach play state.
- `--moveIntervalMs=1000`: how often each joined bot sends movement.
- `--namePrefix=VortexBot`: username prefix. Names are automatically trimmed to 16 characters.

Use small batches first, then grow: `50`, `100`, `250`, `500`, `1000`.
The benchmark uses real protocol connections, so it will exercise the proxy and backend much harder than the logical session benchmark.
