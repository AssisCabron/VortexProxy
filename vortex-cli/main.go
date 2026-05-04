package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/fatih/color"
	"github.com/fsnotify/fsnotify"
	"github.com/gorilla/websocket"
)

type Message struct {
	Type         string     `json:"type"`
	Code         string     `json:"code,omitempty"`
	ExperienceID string     `json:"experienceId,omitempty"`
	FileName     string     `json:"fileName,omitempty"`
	Content      string     `json:"content,omitempty"`
	Path         string     `json:"path,omitempty"`
	Files        []FileInfo `json:"files,omitempty"`
}

type FileInfo struct {
	Path    string `json:"path"`
	Content string `json:"content"`
}

var (
	cyan    = color.New(color.FgCyan).SprintFunc()
	green   = color.New(color.FgGreen).SprintFunc()
	yellow  = color.New(color.FgYellow).SprintFunc()
	red     = color.New(color.FgRed).SprintFunc()
	mag     = color.New(color.FgMagenta).SprintFunc()
	bold    = color.New(color.Bold).SprintFunc()
	blue    = color.New(color.FgBlue).SprintFunc()
)

const serverURL = "ws://localhost:8080"

func main() {
	fmt.Println(bold(mag("\n  V O R T E X   S T U D I O   L I N K")))
	fmt.Println(cyan("  ─────────────────────────────────────\n"))

	if len(os.Args) < 2 {
		fmt.Println("  Usage:")
		fmt.Println(yellow("    vortex link    ") + " - Pair with game server")
		fmt.Println(yellow("    vortex watch   ") + " - Sync current folder to server")
		fmt.Println()
		return
	}

	cmd := os.Args[1]
	switch cmd {
	case "link":
		runLink()
	case "watch":
		if len(os.Args) < 3 {
			fmt.Println(red("  Error: Missing Experience ID"))
			fmt.Println("  Usage: vortex watch <experience-id>")
			return
		}
		runWatch(os.Args[2])
	default:
		fmt.Printf("Unknown command: %s\n", cmd)
	}
}

func runLink() {
	code := fmt.Sprintf("%06d", rand.Intn(1000000))
	fmt.Printf("  Pairing Code: %s\n", bold(green(code)))
	fmt.Println("  Type " + yellow("/vx vincular "+code) + " in-game to pair.\n")

	c, _, err := websocket.DefaultDialer.Dial(serverURL, nil)
	if err != nil {
		log.Fatal("dial:", err)
	}
	defer c.Close()

	// Send AUTH message
	auth := Message{Type: "AUTH", Code: code}
	c.WriteJSON(auth)

	fmt.Printf("  [%s] Waiting for server confirmation...\n", cyan("WAIT"))

	done := make(chan bool)
	go func() {
		for {
			_, message, err := c.ReadMessage()
			if err != nil {
				return
			}
			var msg Message
			json.Unmarshal(message, &msg)
			if msg.Type == "LINK_ACK" {
				// SAVE SESSION
				os.WriteFile(".vortex_session", []byte(code), 0644)
				fmt.Printf("\n  [%s] %s\n", green("SUCCESS"), bold("Successfully linked to Vortex Server!"))
				fmt.Println("  Now you can use " + yellow("vortex watch <id>") + " to sync your code.\n")
				done <- true
				return
			}
		}
	}()

	select {
	case <-done:
	case <-time.After(5 * time.Minute):
		fmt.Println(red("  Timeout waiting for pairing."))
	}
}

func runWatch(experienceID string) {
	// 1. Create/Ensure workspace folder
	workspacePath := filepath.Join(".", experienceID)
	if _, err := os.Stat(workspacePath); os.IsNotExist(err) {
		fmt.Printf("  [%s] Creating workspace folder: %s\n", yellow("INFO"), bold(experienceID))
		os.MkdirAll(workspacePath, 0755)
	}

	fmt.Printf("  [%s] Synchronizing Experience: %s\n", blue("STUDIO"), bold(experienceID))
	fmt.Printf("  Watching folder: %s\n\n", yellow(workspacePath))

	c, _, err := websocket.DefaultDialer.Dial(serverURL, nil)
	if err != nil {
		fmt.Printf(red("  Error: Could not connect to Vortex Server at %s\n"), serverURL)
		fmt.Println("  Make sure the server is running.")
		return
	}
	defer c.Close()

	// 1.5 Authenticate and Request PULL
	sessionCode, err := os.ReadFile(".vortex_session")
	if err == nil {
		auth := Message{Type: "AUTH", Code: string(sessionCode)}
		c.WriteJSON(auth)
		time.Sleep(100 * time.Millisecond)

		pull := Message{Type: "PULL_FILES", ExperienceID: experienceID}
		c.WriteJSON(pull)
	} else {
		fmt.Printf("  [%s] Warning: No pairing found. Use 'vortex link' first.\n", red("WARN"))
	}

	// Channel to handle messages from server
	go func() {
		for {
			_, message, err := c.ReadMessage()
			if err != nil {
				return
			}
			var msg Message
			json.Unmarshal(message, &msg)
			if msg.Type == "PULL_RESPONSE" {
				fmt.Printf("  [%s] Syncing existing files from server...\n", cyan("PULL"))
				for _, f := range msg.Files {
					localPath := filepath.Join(workspacePath, f.Path)
					// Ensure subfolder exists
					os.MkdirAll(filepath.Dir(localPath), 0755)
					
					data, _ := base64.StdEncoding.DecodeString(f.Content)
					os.WriteFile(localPath, data, 0644)
					fmt.Printf("    - %s\n", green(f.Path))
				}
				fmt.Printf("  [%s] Pull complete.\n\n", green("OK"))
			}
		}
	}()

	// 2. Initial Sync
	fmt.Printf("  [%s] Performing initial synchronization...\n", yellow("INIT"))
	filepath.Walk(workspacePath, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return nil
		}
		syncFile(c, experienceID, path, workspacePath)
		return nil
	})

	// 3. Setup Watcher
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		log.Fatal(err)
	}
	defer watcher.Close()

	go func() {
		for {
			select {
			case event, ok := <-watcher.Events:
				if !ok {
					return
				}
				if event.Op&fsnotify.Write == fsnotify.Write {
					syncFile(c, experienceID, event.Name, workspacePath)
				}
				// Handle new files/folders
				if event.Op&fsnotify.Create == fsnotify.Create {
					info, err := os.Stat(event.Name)
					if err == nil && info.IsDir() {
						watcher.Add(event.Name)
					} else {
						syncFile(c, experienceID, event.Name, workspacePath)
					}
				}
			case err, ok := <-watcher.Errors:
				if !ok {
					return
				}
				fmt.Println("error:", err)
			}
		}
	}()

	// Watch workspace recursively
	filepath.Walk(workspacePath, func(path string, info os.FileInfo, err error) error {
		if err == nil && info.IsDir() {
			watcher.Add(path)
		}
		return nil
	})

	fmt.Printf("  [%s] Real-time sync active. Edit your files to live-reload.\n", green("LIVE"))
	<-make(chan struct{})
}

func syncFile(c *websocket.Conn, expID, filePath, workspaceBase string) {
	if !strings.HasSuffix(filePath, ".lua") && !strings.HasSuffix(filePath, ".luau") {
		return
	}

	content, err := os.ReadFile(filePath)
	if err != nil {
		return
	}

	// Calculate relative path inside the workspace
	relPath, _ := filepath.Rel(workspaceBase, filePath)
	fmt.Printf("  [%s] Syncing %s...\n", yellow("SYNC"), cyan(relPath))

	msg := Message{
		Type:         "SYNC_FILE",
		ExperienceID: expID,
		FileName:     filepath.Base(filePath),
		Path:         relPath,
		Content:      base64.StdEncoding.EncodeToString(content),
	}

	err = c.WriteJSON(msg)
	if err != nil {
		fmt.Println(red("  Error sending file:"), err)
	}
}
