package repository

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
)

// TradeRepository persists and loads portfolio snapshots.
type TradeRepository interface {
	Save(history []model.PortfolioSnapshot) error
	Load() ([]model.PortfolioSnapshot, error)
}

// PortfolioStatsRepository persists and loads overall stats like ATH.
type PortfolioStatsRepository interface {
	Save(stats model.PortfolioStats) error
	Load() (model.PortfolioStats, error)
}

// syncFile and closeFile are package-level variable function pointers.
// In production, they call standard os.File Sync and Close.
// In tests, they can be overridden/stubbed to inject disk-write failures,
// allowing us to test failure recovery paths and achieve 100% test coverage.
var syncFile = func(f *os.File) error { return f.Sync() }
var closeFile = func(f *os.File) error { return f.Close() }

// WriteAtomicJSON writes JSON to a temp file and renames it to the target atomically.
// This is a standard production safety pattern: writing directly to a state file
// can result in corruption if the application exits or loses power mid-write.
// Instead, we write to a temporary file, flush it, close it, and then rename it
// to the target path (renaming is an atomic OS call).
func WriteAtomicJSON(filePath string, value interface{}) error {
	dir := filepath.Dir(filePath)
	if dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return fmt.Errorf("failed to create directory %s: %w", dir, err)
		}
	}

	tempFile, err := os.CreateTemp(dir, filepath.Base(filePath)+".*.tmp")
	if err != nil {
		return fmt.Errorf("failed to create temp file: %w", err)
	}
	tempName := tempFile.Name()

	// defer registers a function call that executes automatically when the surrounding
	// function (WriteAtomicJSON) exits, whether it returns normally or via an error.
	// This ensures our temporary file is cleaned up even if encoder or sync calls fail.
	defer func() {
		if tempName != "" {
			_ = os.Remove(tempName)
		}
	}()

	encoder := json.NewEncoder(tempFile)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(value); err != nil {
		_ = closeFile(tempFile)
		return fmt.Errorf("failed to encode JSON: %w", err)
	}

	// Sync forces the operating system to flush in-memory file buffers to the physical disk.
	if err := syncFile(tempFile); err != nil {
		_ = closeFile(tempFile)
		return fmt.Errorf("failed to sync temp file: %w", err)
	}

	// File must be closed before renaming on some operating systems (e.g. Windows).
	if err := closeFile(tempFile); err != nil {
		return fmt.Errorf("failed to close temp file: %w", err)
	}

	// Rename atomically replaces the target file with our fully-written temp file.
	if err := os.Rename(tempName, filePath); err != nil {
		return fmt.Errorf("failed to atomically move temp file to %s: %w", filePath, err)
	}

	// Clear tempName to prevent the deferred os.Remove from cleaning it up on exit.
	tempName = ""
	return nil
}

// FileTradeRepository implements TradeRepository using a local JSON file.
type FileTradeRepository struct {
	mu       sync.Mutex
	filePath string
}

// NewFileTradeRepository creates a new FileTradeRepository.
func NewFileTradeRepository(filePath string) *FileTradeRepository {
	return &FileTradeRepository{filePath: filePath}
}

func (r *FileTradeRepository) Save(history []model.PortfolioSnapshot) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	return WriteAtomicJSON(r.filePath, history)
}

func (r *FileTradeRepository) Load() ([]model.PortfolioSnapshot, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	// os.IsNotExist is a standard helper in Go to check if an error returned by
	// a filesystem call indicates that the file does not exist yet.
	if _, err := os.Stat(r.filePath); os.IsNotExist(err) {
		return []model.PortfolioSnapshot{}, nil
	}

	data, err := os.ReadFile(r.filePath)
	if err != nil {
		return nil, err
	}

	var history []model.PortfolioSnapshot
	if err := json.Unmarshal(data, &history); err != nil {
		return []model.PortfolioSnapshot{}, nil
	}
	return history, nil
}

// FilePortfolioStatsRepository implements PortfolioStatsRepository using a local JSON file.
type FilePortfolioStatsRepository struct {
	mu       sync.Mutex
	filePath string
}

// NewFilePortfolioStatsRepository creates a new FilePortfolioStatsRepository.
func NewFilePortfolioStatsRepository(filePath string) *FilePortfolioStatsRepository {
	return &FilePortfolioStatsRepository{filePath: filePath}
}

func (r *FilePortfolioStatsRepository) Save(stats model.PortfolioStats) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	return WriteAtomicJSON(r.filePath, stats)
}

func (r *FilePortfolioStatsRepository) Load() (model.PortfolioStats, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, err := os.Stat(r.filePath); os.IsNotExist(err) {
		return model.PortfolioStats{}, nil
	}

	data, err := os.ReadFile(r.filePath)
	if err != nil {
		return model.PortfolioStats{}, err
	}

	var stats model.PortfolioStats
	if err := json.Unmarshal(data, &stats); err != nil {
		return model.PortfolioStats{}, nil
	}
	return stats, nil
}
