package service

import (
	"errors"
	"testing"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
)

type MockTradeRepository struct {
	LoadFunc func() ([]model.PortfolioSnapshot, error)
	SaveFunc func(history []model.PortfolioSnapshot) error
}

func (m *MockTradeRepository) Load() ([]model.PortfolioSnapshot, error) {
	if m.LoadFunc != nil {
		return m.LoadFunc()
	}
	return []model.PortfolioSnapshot{}, nil
}

func (m *MockTradeRepository) Save(history []model.PortfolioSnapshot) error {
	if m.SaveFunc != nil {
		return m.SaveFunc(history)
	}
	return nil
}

func TestTradeHistoryService_Init(t *testing.T) {
	// Test successful Init
	repo := &MockTradeRepository{
		LoadFunc: func() ([]model.PortfolioSnapshot, error) {
			return []model.PortfolioSnapshot{
				{Actions: []string{"test"}},
			}, nil
		},
	}
	s := NewTradeHistoryServiceImpl(repo)
	err := s.Init()
	if err != nil {
		t.Fatalf("Expected no error, got %v", err)
	}
	if len(s.GetHistory()) != 1 {
		t.Errorf("Expected 1 history item, got %d", len(s.GetHistory()))
	}

	// Test failing Init
	repoErr := &MockTradeRepository{
		LoadFunc: func() ([]model.PortfolioSnapshot, error) {
			return nil, errors.New("load error")
		},
	}
	sErr := NewTradeHistoryServiceImpl(repoErr)
	err = sErr.Init()
	if err == nil {
		t.Error("Expected load error, got nil")
	}
}

func TestTradeHistoryService_AddSnapshot(t *testing.T) {
	repo := &MockTradeRepository{}
	s := NewTradeHistoryServiceImpl(repo)

	// Test GetLatestSnapshot empty
	_, ok := s.GetLatestSnapshot()
	if ok {
		t.Error("Expected GetLatestSnapshot to return false when empty")
	}

	// Subscribe
	ch := s.Subscribe()

	snap := model.PortfolioSnapshot{Actions: []string{"BUY"}}
	err := s.AddSnapshot(snap)
	if err != nil {
		t.Fatalf("Expected no error adding snapshot, got %v", err)
	}

	// Check subscriber received it
	select {
	case received := <-ch:
		if len(received.Actions) != 1 || received.Actions[0] != "BUY" {
			t.Errorf("Expected BUY action, got %v", received.Actions)
		}
	case <-time.After(100 * time.Millisecond):
		t.Error("Subscriber did not receive the broadcasted snapshot")
	}

	// Verify latest snapshot is correct
	latest, ok := s.GetLatestSnapshot()
	if !ok || len(latest.Actions) != 1 || latest.Actions[0] != "BUY" {
		t.Errorf("Expected latest snapshot to be the one added, got %v (ok: %t)", latest, ok)
	}

	// Fill history beyond max size (50) to trigger truncation
	s.maxSize = 5 // set small max size for testing
	for i := 0; i < 10; i++ {
		_ = s.AddSnapshot(model.PortfolioSnapshot{Actions: []string{"snap"}})
	}

	if len(s.GetHistory()) != 5 {
		t.Errorf("Expected history size to be capped at 5, got %d", len(s.GetHistory()))
	}

	// Unsubscribe
	s.Unsubscribe(ch)

	// Drain the channel until it's fully closed and empty
	for range ch {
	}
}

func TestTradeHistoryService_AddSnapshotSaveError(t *testing.T) {
	repo := &MockTradeRepository{
		SaveFunc: func(history []model.PortfolioSnapshot) error {
			return errors.New("save error")
		},
	}
	s := NewTradeHistoryServiceImpl(repo)
	err := s.AddSnapshot(model.PortfolioSnapshot{})
	if err == nil {
		t.Error("Expected save error, got nil")
	}
}

func TestTradeHistoryService_BroadcastFullBuffer(t *testing.T) {
	repo := &MockTradeRepository{}
	s := NewTradeHistoryServiceImpl(repo)

	// Subscribe but do not read (buffer size is 16)
	ch := s.Subscribe()

	// Add 17 snapshots (exceeds buffer size of 16)
	// The 17th snapshot should drop silently on the channel write case default branch
	for i := 0; i < 18; i++ {
		err := s.AddSnapshot(model.PortfolioSnapshot{})
		if err != nil {
			t.Fatalf("Failed to add snapshot: %v", err)
		}
	}

	// Drain buffer
	count := 0
	for {
		select {
		case <-ch:
			count++
		default:
			goto done
		}
	}
done:
	if count > 16 {
		t.Errorf("Expected buffer to drop items and not exceed channel limit, read %d items", count)
	}
}
