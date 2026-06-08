package repository

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

type TestData struct {
	Name  string `json:"name"`
	Value int    `json:"value"`
}

func TestWriteAtomicJSON_Success(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "rebalancer-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	filePath := filepath.Join(tempDir, "subdir", "test.json")
	data := TestData{Name: "hello", Value: 123}

	err = WriteAtomicJSON(filePath, data)
	if err != nil {
		t.Errorf("Expected no error, got %v", err)
	}

	// Verify file exists
	if _, err := os.Stat(filePath); os.IsNotExist(err) {
		t.Errorf("Expected file %s to exist", filePath)
	}

	// Read content and check values
	content, err := os.ReadFile(filePath)
	if err != nil {
		t.Fatalf("Failed to read file: %v", err)
	}

	if len(content) == 0 {
		t.Errorf("Expected non-empty file content")
	}
}

func TestWriteAtomicJSON_MkdirError(t *testing.T) {
	// A path that cannot be created (requires root permissions)
	filePath := "/nonexistent-root-dir-xyz/test.json"
	data := TestData{Name: "hello", Value: 123}

	err := WriteAtomicJSON(filePath, data)
	if err == nil {
		t.Errorf("Expected error when creating directory in root, got nil")
	}
}

func TestWriteAtomicJSON_EncodeError(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "rebalancer-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	filePath := filepath.Join(tempDir, "test.json")

	// Channels cannot be JSON serialized
	chData := make(chan int)

	err = WriteAtomicJSON(filePath, chData)
	if err == nil {
		t.Errorf("Expected JSON encoding error, got nil")
	}
}

func TestFileTradeRepository(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "rebalancer-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	filePath := filepath.Join(tempDir, "trade-history.json")
	repo := NewFileTradeRepository(filePath)

	// 1. Load from non-existent file
	history, err := repo.Load()
	if err != nil {
		t.Errorf("Expected no error when file is missing, got %v", err)
	}
	if len(history) != 0 {
		t.Errorf("Expected empty history, got %d items", len(history))
	}

	// 2. Save and Load
	snaps := []model.PortfolioSnapshot{
		{
			Timestamp:                 time.Now().Round(time.Second),
			TotalValueUSD:             decimal.NewFromFloat(5000.50),
			DrawdownPercent:           decimal.NewFromFloat(1.5),
			FiatDeploymentPercent:     decimal.NewFromFloat(20.0),
			EffectiveUsdTargetPercent: decimal.NewFromFloat(10.0),
			Assets:                    map[string]model.AssetSnapshot{},
			Actions:                   []string{"SELL BTC", "BUY ETH"},
		},
	}

	err = repo.Save(snaps)
	if err != nil {
		t.Errorf("Expected no error on Save, got %v", err)
	}

	loadedSnaps, err := repo.Load()
	if err != nil {
		t.Errorf("Expected no error on Load, got %v", err)
	}
	if len(loadedSnaps) != 1 {
		t.Fatalf("Expected 1 snapshot, got %d", len(loadedSnaps))
	}

	if !loadedSnaps[0].TotalValueUSD.Equal(snaps[0].TotalValueUSD) {
		t.Errorf("Expected TotalValueUSD %v, got %v", snaps[0].TotalValueUSD, loadedSnaps[0].TotalValueUSD)
	}

	// 3. Load invalid JSON
	err = os.WriteFile(filePath, []byte("{invalid json"), 0644)
	if err != nil {
		t.Fatalf("Failed to write invalid JSON file: %v", err)
	}

	loadedInvalid, err := repo.Load()
	if err != nil {
		t.Errorf("Expected no error loading invalid JSON, got %v", err)
	}
	if len(loadedInvalid) != 0 {
		t.Errorf("Expected empty history for invalid JSON, got %d items", len(loadedInvalid))
	}
}

func TestFilePortfolioStatsRepository(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "rebalancer-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	filePath := filepath.Join(tempDir, "portfolio-stats.json")
	repo := NewFilePortfolioStatsRepository(filePath)

	// 1. Load from non-existent file
	stats, err := repo.Load()
	if err != nil {
		t.Errorf("Expected no error when file is missing, got %v", err)
	}
	if !stats.AllTimeHigh.IsZero() {
		t.Errorf("Expected zero AllTimeHigh, got %v", stats.AllTimeHigh)
	}

	// 2. Save and Load
	s := model.PortfolioStats{
		AllTimeHigh: decimal.NewFromFloat(15000.75),
	}

	err = repo.Save(s)
	if err != nil {
		t.Errorf("Expected no error on Save, got %v", err)
	}

	loadedStats, err := repo.Load()
	if err != nil {
		t.Errorf("Expected no error on Load, got %v", err)
	}

	if !loadedStats.AllTimeHigh.Equal(s.AllTimeHigh) {
		t.Errorf("Expected AllTimeHigh %v, got %v", s.AllTimeHigh, loadedStats.AllTimeHigh)
	}

	// 3. Load invalid JSON
	err = os.WriteFile(filePath, []byte("{invalid json"), 0644)
	if err != nil {
		t.Fatalf("Failed to write invalid JSON file: %v", err)
	}

	loadedInvalid, err := repo.Load()
	if err != nil {
		t.Errorf("Expected no error loading invalid JSON, got %v", err)
	}
	if !loadedInvalid.AllTimeHigh.IsZero() {
		t.Errorf("Expected zero AllTimeHigh for invalid JSON, got %v", loadedInvalid.AllTimeHigh)
	}
}

func TestWriteAtomicJSON_RenameError(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "rebalancer-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	// Target is a directory itself. Renaming a file to a directory will fail.
	err = WriteAtomicJSON(tempDir, TestData{Name: "fail", Value: 1})
	if err == nil {
		t.Errorf("Expected error when renaming to a directory path, got nil")
	}
}

func TestFileTradeRepository_LoadReadError(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "rebalancer-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	// Point the file path directly to the directory. Reading a directory will fail.
	repo := NewFileTradeRepository(tempDir)
	_, err = repo.Load()
	if err == nil {
		t.Errorf("Expected read error when file path is a directory, got nil")
	}
}

func TestFilePortfolioStatsRepository_LoadReadError(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "rebalancer-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	// Point the file path directly to the directory. Reading a directory will fail.
	repo := NewFilePortfolioStatsRepository(tempDir)
	_, err = repo.Load()
	if err == nil {
		t.Errorf("Expected read error when file path is a directory, got nil")
	}
}

func TestWriteAtomicJSON_CreateTempError(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "rebalancer-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	badDir := filepath.Join(tempDir, "readonly")
	err = os.Mkdir(badDir, 0000)
	if err != nil {
		t.Fatalf("Failed to mkdir: %v", err)
	}
	defer os.Chmod(badDir, 0700) // allow cleanup

	filePath := filepath.Join(badDir, "test.json")
	err = WriteAtomicJSON(filePath, TestData{Name: "fail"})
	if err == nil {
		t.Errorf("Expected error from CreateTemp, got nil")
	}
}

func TestWriteAtomicJSON_SimplePath(t *testing.T) {
	filePath := "test-simple-repos.json"
	defer os.Remove(filePath)

	err := WriteAtomicJSON(filePath, TestData{Name: "simple"})
	if err != nil {
		t.Errorf("Expected no error, got %v", err)
	}

	if _, err := os.Stat(filePath); os.IsNotExist(err) {
		t.Errorf("Expected file to exist")
	}
}



