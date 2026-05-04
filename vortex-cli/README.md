# Vortex Studio CLI

The Vortex Studio CLI is a high-performance terminal tool designed to link your local development environment directly to the Vortex Minecraft Server.

## Features

- **Instant Pairing**: Link your local machine to your player account in-game using a simple 6-digit code.
- **Real-time Sync**: Every time you save a `.lua` or `.luau` file, it is automatically uploaded and live-reloaded in the server.
- **Aesthetic Interface**: Premium terminal output with color-coding and clear status indicators.

## Installation

1. Ensure you have **Go** installed on your system.
2. Navigate to the `vortex-cli` directory.
3. Build the binary:
   ```bash
   go build -o vortex.exe
   ```
4. (Optional) Move `vortex.exe` to a directory in your PATH.

## Usage

### 1. Linking your Environment
Run the following command to generate a pairing code:
```bash
vortex link
```
Then, inside the Minecraft server, type:
```
/vx vincular <CODE>
```

### 2. Synchronizing Scripts
Once linked, navigate to your experience's local folder and run:
```bash
vortex watch <experience-id>
```
Now, any changes you make to your Lua scripts will be reflected in-game instantly.

## Architecture

The CLI communicates with the Vortex Server using a secure WebSocket connection on port `8080`. It uses the `fsnotify` library for low-latency file watching.
